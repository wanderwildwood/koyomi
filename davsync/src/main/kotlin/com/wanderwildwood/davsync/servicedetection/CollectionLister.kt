/*
 * Adapted from DAVx5 core/servicedetection/ServiceRefresher.kt (discoverHomesets),
 * HomeSetRefresher.kt and ServiceDetectionUtils.kt (bitfireAT, GPL-3.0-or-later): the user's own
 * home sets from the principal, then a depth-1 listing of each. Left out: group memberships and
 * calendar proxies (other people's collections), push and webcal subscriptions.
 */
package com.wanderwildwood.davsync.servicedetection

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.ktor.withTrailingSlash
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.carddav.AddressbookHomeSet
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.webdav.WebDAV
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder.Service
import io.ktor.client.HttpClient
import io.ktor.http.Url
import java.util.logging.Level
import java.util.logging.Logger

class CollectionLister(
    private val httpClient: HttpClient,
    private val logger: Logger
) {

    /** The home sets the principal names for [service]. */
    suspend fun homeSets(principalUrl: Url, service: Service): Set<Url> {
        val found = mutableSetOf<Url>()
        val principal = DavResource(httpClient, principalUrl, logger)
        val property = if (service == Service.CARDDAV) CardDAV.AddressbookHomeSet else CalDAV.CalendarHomeSet
        principal.propfind(0, WebDAV.DisplayName, WebDAV.ResourceType, property).responses().collect { davResponse ->
            val hrefs = if (service == Service.CARDDAV)
                davResponse[AddressbookHomeSet::class.java]?.hrefs
            else
                davResponse[CalendarHomeSet::class.java]?.hrefs
            for (href in hrefs.orEmpty())
                principal.location.resolve(href)?.let { found += it.withTrailingSlash() }
        }
        return found
    }

    /** The calendars or address books in [homeSet]. A home set the server refuses (403/404/410) gives none. */
    suspend fun collections(homeSet: Url, service: Service): List<Collection> {
        val type = if (service == Service.CARDDAV) Collection.TYPE_ADDRESSBOOK else Collection.TYPE_CALENDAR
        val found = mutableListOf<Collection>()
        try {
            DavResource(httpClient, homeSet, logger).propfind(1, *collectionQueryProperties(service))
                .responses()
                .collect { response ->
                    if (!response.isSuccess()) return@collect
                    val collection = Collection.fromDavResponse(response) ?: return@collect
                    if (collection.type == type)
                        found += collection
                }
        } catch (e: HttpException) {
            if (e is UnauthorizedException) throw e
            if (e.statusCode !in arrayOf(403, 404, 410)) throw e
            logger.log(Level.INFO, "Home set $homeSet not accessible", e)
        }
        return found
    }

    /**
     * Everything for [service] from a finished discovery: the collections in each home set, plus
     * any collection the typed address pointed at directly.
     */
    suspend fun all(info: DavResourceFinder.Configuration.ServiceInfo, service: Service): List<Collection> {
        val homeSets = info.homeSets.toMutableSet()
        info.principal?.let { homeSets += homeSets(it, service) }
        val byUrl = LinkedHashMap<Url, Collection>()
        for (homeSet in homeSets)
            for (c in collections(homeSet, service))
                byUrl[c.url] = c
        for ((url, c) in info.collections)
            byUrl.putIfAbsent(url, c)
        return byUrl.values.toList()
    }

    companion object {
        fun collectionQueryProperties(service: Service): Array<Property.Name> =
            arrayOf(
                WebDAV.CurrentUserPrivilegeSet,
                WebDAV.DisplayName,
                WebDAV.Owner,
                WebDAV.ResourceType
            ) + when (service) {
                Service.CARDDAV -> arrayOf(CardDAV.AddressbookDescription)
                Service.CALDAV -> arrayOf(
                    CalDAV.CalendarColor,
                    CalDAV.CalendarDescription,
                    CalDAV.CalendarTimezone,
                    CalDAV.CalendarTimezoneId,
                    CalDAV.SupportedCalendarComponentSet
                )
            }
    }
}
