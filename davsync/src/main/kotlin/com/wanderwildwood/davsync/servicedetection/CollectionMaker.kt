/*
 * Adapted from DAVx5 core/repository/DavCollectionRepository.kt (createRemote, generateMkColXml)
 * (bitfireAT, GPL-3.0-or-later): a new calendar or address book in the user's home set, by
 * MKCALENDAR or an extended MKCOL, named and (calendars) colored; then the list of collections
 * read again, as ServiceRefresher does.
 */
package com.wanderwildwood.davsync.servicedetection

import android.accounts.Account
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.network.HttpClientBuilder
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder.Service
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.logging.Level

object CollectionMaker {

    sealed class Result {
        /** Made, listed, and turned on here: its id in the account. */
        data class Made(val id: Long) : Result()
        /** The server keeps no calendars or address books for this user where we can make one. */
        data object NoHome : Result()
        data object Password : Result()
        data object Unreachable : Result()
        data class Refused(val detail: String) : Result()
    }

    /**
     * Makes a calendar (or, for [Service.CARDDAV], an address book) called [name] on [account]'s
     * server, in the first home set its principal names, then reads the list again and turns
     * the new one on. [color] is 0xAARRGGBB, for calendars.
     */
    suspend fun create(davSync: DavSync, account: Account, service: Service, name: String, color: Int?): Result =
        withContext(davSync.ioDispatcher) {
            withClient(davSync, account, service) { client, info ->
                val lister = CollectionLister(client, davSync.logger)
                val homeSets = info.homeSets.toMutableSet()
                info.principal?.let { homeSets += lister.homeSets(it, service) }
                val home = homeSets.firstOrNull() ?: return@withClient Result.NoHome
                val url = URLBuilder(home).appendPathSegments(UUID.randomUUID().toString(), "").build()
                val (method, body) = if (service == Service.CALDAV)
                    "MKCALENDAR" to calendarXml(name, color)
                else
                    "MKCOL" to addressBookXml(name)
                DavResource(client, url, davSync.logger).mkCol(body, method) { }
                val found = lister.all(info, service)
                davSync.accounts.refreshCollections(account, found)
                val made = davSync.accounts.collections(account).firstOrNull { it.url == url }
                    ?: davSync.accounts.collections(account).firstOrNull { it.url.encodedPath == url.encodedPath }
                    ?: return@withClient Result.Refused("")
                davSync.accounts.setSync(account, made.id, true)
                Result.Made(made.id)
            }
        }

    /**
     * Reads [account]'s calendars or address books from the server again, so one made or
     * removed elsewhere (in Nextcloud) shows here; the choices already made are kept.
     */
    suspend fun refresh(davSync: DavSync, account: Account, service: Service): Result =
        withContext(davSync.ioDispatcher) {
            withClient(davSync, account, service) { client, info ->
                val found = CollectionLister(client, davSync.logger).all(info, service)
                if (found.isNotEmpty()) davSync.accounts.refreshCollections(account, found)
                Result.Made(0)
            }
        }

    private suspend fun withClient(
        davSync: DavSync, account: Account, service: Service,
        block: suspend (HttpClient, DavResourceFinder.Configuration.ServiceInfo) -> Result,
    ): Result {
        val server = davSync.accounts.server(account) ?: return Result.Refused("")
        val credentials = davSync.accounts.credentials(account) ?: return Result.Password
        return try {
            HttpClientBuilder.build(davSync.httpUserAgent, credentials, server.host).use { client ->
                val finder = DavResourceFinder(URI(server.toString()), client, davSync.logger)
                val configuration = finder.findInitialConfiguration()
                val info = (if (service == Service.CALDAV) configuration.calDAV else configuration.cardDAV)
                    ?: return@use if (configuration.encountered401) Result.Password else Result.Unreachable
                block(client, info)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            Result.Password
        } catch (e: HttpException) {
            davSync.logger.log(Level.WARNING, "Server refused", e)
            Result.Refused(e.message.orEmpty())
        } catch (e: IOException) {
            Result.Unreachable
        } catch (e: Exception) {
            davSync.logger.log(Level.WARNING, "Collection request failed", e)
            Result.Refused(e.message.orEmpty())
        }
    }

    /** The MKCALENDAR body: a name, a color (Apple's property, which Nextcloud reads) and events only. */
    fun calendarXml(name: String, color: Int?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<C:mkcalendar xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\" xmlns:A=\"http://apple.com/ns/ical/\">")
        append("<D:set><D:prop>")
        append("<D:displayname>").append(escape(name)).append("</D:displayname>")
        if (color != null) append("<A:calendar-color>").append(colorHex(color)).append("</A:calendar-color>")
        append("<C:supported-calendar-component-set><C:comp name=\"VEVENT\"/></C:supported-calendar-component-set>")
        append("</D:prop></D:set></C:mkcalendar>")
    }

    /** The extended MKCOL body (RFC 5689) for an address book. */
    fun addressBookXml(name: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<D:mkcol xmlns:D=\"DAV:\" xmlns:CARD=\"urn:ietf:params:xml:ns:carddav\">")
        append("<D:set><D:prop>")
        append("<D:resourcetype><D:collection/><CARD:addressbook/></D:resourcetype>")
        append("<D:displayname>").append(escape(name)).append("</D:displayname>")
        append("</D:prop></D:set></D:mkcol>")
    }

    /** 0xAARRGGBB as CalDAV writes it: #RRGGBBAA. */
    fun colorHex(color: Int): String {
        val rgb = color and 0xFFFFFF
        val alpha = (color ushr 24) and 0xFF
        return "#%06X%02X".format(rgb, alpha)
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
