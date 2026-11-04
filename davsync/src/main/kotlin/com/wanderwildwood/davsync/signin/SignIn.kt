package com.wanderwildwood.davsync.signin

import android.accounts.Account
import at.bitfire.dav4jvm.ktor.toUrlOrNull
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.network.Credentials
import com.wanderwildwood.davsync.network.HttpClientBuilder
import com.wanderwildwood.davsync.servicedetection.CollectionLister
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder.Service
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.util.logging.Level
import javax.net.ssl.SSLException

/** Why a typed sign-in did not work, in the terms the reader can act on (as in Memo). */
enum class Problem { BAD_ADDRESS, PLAIN_HTTP, NOT_FOUND, UNREACHABLE, CERTIFICATE, WRONG_PASSWORD, NO_SERVICE, NOTHING_FOUND, KEYSTORE, OTHER }

sealed class SignInResult {
    /** What the server answered: the address as typed, the login, and its calendars or address books. */
    data class Good(val server: Url, val credentials: Credentials, val collections: List<Collection>) : SignInResult()
    data class Bad(val problem: Problem, val detail: String = "") : SignInResult()
}

/**
 * The typed sign-in, like Memo's: a server address, a user name and an app password, asked of
 * the server once before anything is kept. Discovery is DAVx5's: the typed address itself, then
 * its /.well-known/caldav or /.well-known/carddav, then the user's principal and home sets. So
 * it works with Nextcloud and with any standard server (Radicale, Baïkal, Fastmail, mailbox.org).
 */
object SignIn {

    /**
     * Checks [address], [user] and [password] for [service]. Nothing is saved here; [keep] saves
     * a good result.
     */
    suspend fun check(davSync: DavSync, address: String, user: String, password: String, service: Service): SignInResult =
        withContext(davSync.ioDispatcher) {
            if (address.isBlank()) return@withContext SignInResult.Bad(Problem.BAD_ADDRESS)
            val base = normalise(address)
            val url = base.toUrlOrNull() ?: return@withContext SignInResult.Bad(Problem.BAD_ADDRESS)
            val uri = runCatching { URI(base) }.getOrNull() ?: return@withContext SignInResult.Bad(Problem.BAD_ADDRESS)
            if (!plainHttpAllowed(base)) return@withContext SignInResult.Bad(Problem.PLAIN_HTTP)
            val credentials = Credentials(user.trim(), password.trim())
            HttpClientBuilder.build(davSync.httpUserAgent, credentials, url.host).use { client ->
                // First a plain request, so a wrong address, no network or a bad certificate is named
                // as such; discovery below keeps those to itself.
                try {
                    client.request(url) { method = HttpMethod.Options }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return@withContext SignInResult.Bad(problemFor(e), e.message.orEmpty())
                } catch (e: Exception) {
                    return@withContext SignInResult.Bad(Problem.OTHER, e.message.orEmpty())
                }
                try {
                    val finder = DavResourceFinder(uri, client, davSync.logger)
                    val configuration = finder.findInitialConfiguration()
                    val info = if (service == Service.CALDAV) configuration.calDAV else configuration.cardDAV
                    if (info == null)
                        return@withContext SignInResult.Bad(if (configuration.encountered401) Problem.WRONG_PASSWORD else Problem.NO_SERVICE)
                    val collections = CollectionLister(client, davSync.logger).all(info, service)
                    if (collections.isEmpty())
                        return@withContext SignInResult.Bad(Problem.NOTHING_FOUND)
                    SignInResult.Good(url, credentials, collections)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: at.bitfire.dav4jvm.ktor.exception.UnauthorizedException) {
                    SignInResult.Bad(Problem.WRONG_PASSWORD)
                } catch (e: IOException) {
                    SignInResult.Bad(problemFor(e), e.message.orEmpty())
                } catch (e: Exception) {
                    davSync.logger.log(Level.WARNING, "Sign-in check failed", e)
                    SignInResult.Bad(Problem.OTHER, e.message.orEmpty())
                }
            }
        }

    /**
     * Keeps a good result as a sync account named "user@host", with nothing turned on: the user
     * picks what to keep on the phone. Turning everything on would copy calendars and address
     * books another app (DAVx5) may already sync, and on Nextcloud the server's own read-only
     * books too (every user of the server, recently contacted people). Returns null when it could not be kept: an account by that
     * name exists already, or the Keystore would not seal the password.
     */
    fun keep(davSync: DavSync, good: SignInResult.Good): Account? {
        val name = "${good.credentials.username}@${good.server.host}"
        val collections = good.collections.map { it.copy(sync = false) }
        return davSync.accounts.create(name, good.server, good.credentials, collections)
    }

    /**
     * Whether [address] may be used: any https one, or an http one whose host is on a home or VPN
     * network (a private or Tailscale address, "localhost", a name with no dots, or one ending
     * .local, .lan, .home.arpa, .internal or .ts.net). Plain http to a public host would send the
     * login in the clear across the internet. (Memo's rule.)
     */
    fun plainHttpAllowed(address: String): Boolean {
        if (address.startsWith("https://")) return true
        val host = address.toUrlOrNull()?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.contains(':') || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")))
            return isPrivatePeer(host)
        return !host.contains('.') || listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net").any { host.endsWith(it) }
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    internal fun problemFor(e: IOException): Problem = when (e) {
        is UnknownHostException -> Problem.NOT_FOUND
        is SSLException -> Problem.CERTIFICATE
        else -> Problem.UNREACHABLE
    }

    /** A private, loopback, link-local or Tailscale (100.64/10) address literal. (Memo's.) */
    internal fun isPrivatePeer(ip: String?): Boolean {
        val addr = ip?.substringBefore('%')?.lowercase()?.trim() ?: return false
        // A literal only: a name made of hex letters and dots must never reach a DNS lookup.
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(addr)
        val v6 = ':' in addr && Regex("^[0-9a-f.:]+$").matches(addr)
        if (!v4 && !v6) return false
        val bytes = runCatching { java.net.InetAddress.getByName(addr).address }.getOrNull() ?: return false
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (bytes.size == 16) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            val loopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
            return loopback || (a and 0xFE) == 0xFC || (a == 0xFE && (b and 0xC0) == 0x80)
        }
        return false
    }
}
