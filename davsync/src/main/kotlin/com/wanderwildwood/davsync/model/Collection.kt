/*
 * Adapted from DAVx5 core/db/Collection.kt (bitfireAT, GPL-3.0-or-later): the same fields and
 * the same reading of a WebDAV response, without Room, push or tasks/journals. Collections are
 * kept as JSON in the account's own user data (see AccountStore), so they go with the account.
 */
package com.wanderwildwood.davsync.model

import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.toUrlOrNull
import at.bitfire.dav4jvm.ktor.withTrailingSlash
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarDescription
import at.bitfire.dav4jvm.property.caldav.CalendarTimezone
import at.bitfire.dav4jvm.property.caldav.CalendarTimezoneId
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.carddav.AddressbookDescription
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.synctools.icalendar.timezoneDefToTzId
import at.bitfire.synctools.util.trimToNull
import com.wanderwildwood.davsync.util.DavUtils.extractCollectionName
import io.ktor.http.Url
import org.json.JSONArray
import org.json.JSONObject

data class Collection(
    /** Stable within the account; written into the local calendar / address book to find it again. */
    val id: Long = 0,
    val type: String,
    val url: Url,
    val privWriteContent: Boolean = true,
    val privUnbind: Boolean = true,
    val forceReadOnly: Boolean = false,
    val displayName: String? = null,
    val description: String? = null,
    val color: Int? = null,
    val timezoneId: String? = null,
    val supportsVEVENT: Boolean? = null,
    val supportsVTODO: Boolean? = null,
    /** Chosen by the user for syncing. */
    val sync: Boolean = false,
) {

    init {
        require(url.encodedPath.endsWith('/')) { "Not a collection URL (path does not end with a slash): $url" }
    }

    companion object {
        const val TYPE_ADDRESSBOOK = "ADDRESS_BOOK"
        const val TYPE_CALENDAR = "CALENDAR"

        /** A collection from a PROPFIND answer, or null when it is neither a calendar nor an address book. */
        fun fromDavResponse(dav: Response): Collection? {
            val url = dav.href.withTrailingSlash()
            val type: String = dav[ResourceType::class.java]?.let { resourceType ->
                when {
                    resourceType.types.contains(CardDAV.Addressbook) -> TYPE_ADDRESSBOOK
                    resourceType.types.contains(CalDAV.Calendar) -> TYPE_CALENDAR
                    else -> null    // webcal subscriptions are left out
                }
            } ?: return null

            var privWriteContent = true
            var privUnbind = true
            dav[CurrentUserPrivilegeSet::class.java]?.let { privilegeSet ->
                privWriteContent = privilegeSet.mayWriteContent
                privUnbind = privilegeSet.mayUnbind
            }

            val displayName = dav[DisplayName::class.java]?.displayName.trimToNull()
            var description: String? = null
            var color: Int? = null
            var timezoneId: String? = null
            var supportsVEVENT: Boolean? = null
            var supportsVTODO: Boolean? = null
            when (type) {
                TYPE_ADDRESSBOOK ->
                    dav[AddressbookDescription::class.java]?.let { description = it.description }
                TYPE_CALENDAR -> {
                    dav[CalendarDescription::class.java]?.let { description = it.description }
                    dav[CalendarColor::class.java]?.let { color = it.color }
                    dav[CalendarTimezoneId::class.java]?.let { timezoneId = it.identifier }
                    if (timezoneId == null)
                        dav[CalendarTimezone::class.java]?.vTimeZone?.let { timezoneId = timezoneDefToTzId(it) }
                    supportsVEVENT = true
                    supportsVTODO = true
                    dav[SupportedCalendarComponentSet::class.java]?.let {
                        supportsVEVENT = it.supportsEvents
                        supportsVTODO = it.supportsTasks
                    }
                }
            }
            return Collection(
                type = type,
                url = url,
                privWriteContent = privWriteContent,
                privUnbind = privUnbind,
                displayName = displayName,
                description = description,
                color = color,
                timezoneId = timezoneId,
                supportsVEVENT = supportsVEVENT,
                supportsVTODO = supportsVTODO,
            )
        }

        fun toJson(list: List<Collection>): String = JSONArray().apply {
            for (c in list) put(JSONObject().apply {
                put("id", c.id); put("type", c.type); put("url", c.url.toString())
                put("privWriteContent", c.privWriteContent); put("privUnbind", c.privUnbind)
                put("forceReadOnly", c.forceReadOnly); put("sync", c.sync)
                c.displayName?.let { put("displayName", it) }
                c.description?.let { put("description", it) }
                c.color?.let { put("color", it) }
                c.timezoneId?.let { put("timezoneId", it) }
                c.supportsVEVENT?.let { put("supportsVEVENT", it) }
                c.supportsVTODO?.let { put("supportsVTODO", it) }
            })
        }.toString()

        fun fromJson(json: String?): List<Collection> {
            if (json.isNullOrEmpty()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.getJSONObject(i)
                    val url = o.getString("url").toUrlOrNull() ?: return@mapNotNull null
                    Collection(
                        id = o.getLong("id"),
                        type = o.getString("type"),
                        url = url,
                        privWriteContent = o.optBoolean("privWriteContent", true),
                        privUnbind = o.optBoolean("privUnbind", true),
                        forceReadOnly = o.optBoolean("forceReadOnly", false),
                        sync = o.optBoolean("sync", false),
                        displayName = o.optString("displayName").ifEmpty { null },
                        description = o.optString("description").ifEmpty { null },
                        color = if (o.has("color")) o.getInt("color") else null,
                        timezoneId = o.optString("timezoneId").ifEmpty { null },
                        supportsVEVENT = if (o.has("supportsVEVENT")) o.getBoolean("supportsVEVENT") else null,
                        supportsVTODO = if (o.has("supportsVTODO")) o.getBoolean("supportsVTODO") else null,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    fun title() = displayName ?: extractCollectionName(url)
    fun readOnly() = forceReadOnly || !privWriteContent
}
