package com.wanderwildwood.koyomi.data

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZoneId
import javax.net.ssl.SSLException

/**
 * A calendar followed by its link. It lives in the phone's calendar store as a read-only local
 * calendar, [calendarId]; this is what is remembered about where it came from. [url] is a secret
 * (Google's "secret address" lets anyone who has it read the calendar): it is kept in this app's
 * own storage, left out of backups, and is never logged or shown whole.
 */
data class Subscription(
    val calendarId: Long,
    val url: String,
    val name: String,
    /** When the last fetch that worked finished, in epoch milliseconds; 0 if none has. */
    val fetchedAt: Long = 0,
    /** What the server said the last time it did not work; null when the last fetch did. */
    val error: String? = null,
)

/** What a fetch brought back. [Failed.message] is the server's own words, never the address. */
sealed interface Fetched {
    data class Body(val text: String) : Fetched
    /** [message] is the server's own words for [Kind.SERVER]; the others are named, and worded by the caller. */
    data class Failed(val kind: Kind, val message: String = "") : Fetched
    enum class Kind { SERVER, NO_HOST, TIMEOUT, CERTIFICATE, UNREACHABLE, TOO_BIG, OPEN }
}

fun interface Fetcher {
    fun fetch(url: String): Fetched
}

/** Where a subscription's events go. Replaces all of them, or none, and says which. */
fun interface SubscriptionTarget {
    fun replaceEvents(calendarId: Long, events: List<IcsEvent>): Boolean
}

sealed interface Refreshed {
    data class Done(val subscription: Subscription, val events: Int) : Refreshed
    /** Nothing changed: the last copy stays, and [subscription] carries what went wrong. */
    data class Kept(val subscription: Subscription) : Refreshed
}

object SubscriptionSync {
    /**
     * Fetches [sub] and swaps its events for what came back, in one go ([target] is one
     * transaction). A failed fetch, a reply that is not a calendar, or a target that refuses,
     * leaves the copy as it was.
     */
    fun refresh(
        sub: Subscription,
        fetcher: Fetcher,
        target: SubscriptionTarget,
        zone: ZoneId,
        now: Long,
        failure: (Fetched.Failed) -> String = { it.message.ifEmpty { it.kind.name } },
        notACalendar: String = "Not a calendar",
        notSaved: String = "Not saved",
    ): Refreshed {
        val body = when (val got = runCatching { fetcher.fetch(sub.url) }.getOrElse { Fetched.Failed(Fetched.Kind.OPEN) }) {
            is Fetched.Failed -> return Refreshed.Kept(sub.copy(error = failure(got)))
            is Fetched.Body -> got.text
        }
        // An empty calendar is a calendar; a login page is not, and must not wipe the real copy.
        if (!body.contains("BEGIN:VCALENDAR", ignoreCase = true)) return Refreshed.Kept(sub.copy(error = notACalendar))
        val events = runCatching { Ics.parse(body, zone) }.getOrNull() ?: return Refreshed.Kept(sub.copy(error = notACalendar))
        if (!target.replaceEvents(sub.calendarId, events)) return Refreshed.Kept(sub.copy(error = notSaved))
        return Refreshed.Done(sub.copy(fetchedAt = now, error = null, name = sub.name), events.size)
    }

    /** One line saying what the server said, from its status and the start of its reply. */
    fun describe(code: Int, statusLine: String?, body: String?): String {
        val snippet = body.orEmpty()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
        val head = listOfNotNull(code.toString(), statusLine?.trim()?.ifEmpty { null }).joinToString(" ")
        val status = statusLine.orEmpty().trim()
        return if (snippet.isEmpty() || snippet.equals(status, ignoreCase = true)) head else "$head: $snippet"
    }
}

/** Fetches a link over https. Nothing about the address is logged, and no message carries it. */
class HttpFetcher : Fetcher {
    override fun fetch(url: String): Fetched {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Datebook")
                setRequestProperty("Accept", "text/calendar, text/plain, */*")
            }
            val code = connection.responseCode
            if (code in 200..299) {
                val bytes = connection.inputStream.use { readCapped(it) }
                    ?: return Fetched.Failed(Fetched.Kind.TOO_BIG)
                Fetched.Body(String(bytes, Charsets.UTF_8))
            } else {
                val text = runCatching { connection.errorStream?.use { readCapped(it, 4096) }?.let { String(it, Charsets.UTF_8) } }.getOrNull()
                Fetched.Failed(Fetched.Kind.SERVER, SubscriptionSync.describe(code, connection.responseMessage, text))
            }
        } catch (e: UnknownHostException) {
            Fetched.Failed(Fetched.Kind.NO_HOST)
        } catch (e: SocketTimeoutException) {
            Fetched.Failed(Fetched.Kind.TIMEOUT)
        } catch (e: SSLException) {
            Fetched.Failed(Fetched.Kind.CERTIFICATE)
        } catch (e: IOException) {
            // The exception's own text can hold the address, so it is not used.
            Fetched.Failed(Fetched.Kind.UNREACHABLE)
        } catch (e: RuntimeException) {
            Fetched.Failed(Fetched.Kind.OPEN)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int = MAX_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > cap) return if (cap == MAX_BYTES) null else out.toByteArray()
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}

/**
 * The links, kept in a preferences file of their own, which no backup or device transfer takes
 * (res/xml/no_backup.xml leaves out the whole app). One line per calendar.
 */
class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): List<Subscription> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix("s").toLongOrNull() ?: return@mapNotNull null
        decode(id, value as? String ?: return@mapNotNull null)
    }.sortedBy { it.name.lowercase() }

    fun put(sub: Subscription) {
        prefs.edit().putString("s${sub.calendarId}", encode(sub)).apply()
    }

    fun remove(calendarId: Long) {
        prefs.edit().remove("s$calendarId").apply()
    }

    companion object {
        const val FILE = "subscriptions"
        private const val SEP = '\u001F'

        private fun clean(s: String?) = s.orEmpty().replace(SEP, ' ').replace('\n', ' ')

        fun encode(s: Subscription): String =
            listOf(clean(s.url), clean(s.name), s.fetchedAt.toString(), clean(s.error)).joinToString(SEP.toString())

        fun decode(id: Long, line: String): Subscription? {
            val f = line.split(SEP)
            if (f.size < 4 || f[0].isEmpty()) return null
            return Subscription(id, f[0], f[1], f[2].toLongOrNull() ?: 0, f[3].ifEmpty { null })
        }
    }
}
