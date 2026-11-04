/*
 * Adapted from DAVx5 core/sync/CalendarSyncer.kt (bitfireAT, GPL-3.0-or-later).
 */
package com.wanderwildwood.davsync.sync

import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.calendar.AndroidCalendarProvider
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.resource.local.LocalCalendar
import com.wanderwildwood.davsync.resource.local.LocalCalendarStore
import com.wanderwildwood.davsync.resource.remote.CalDavCollection
import com.wanderwildwood.davsync.resource.remote.CalendarQueryFilter
import net.fortuna.ical4j.model.Component
import java.time.ZonedDateTime
import java.util.logging.Level

/** Sync logic for calendars. */
class CalendarSyncer(
    davSync: DavSync,
    accountId: AccountId,
    resync: ResyncType?,
    syncResult: SyncResult,
    settings: SyncSettings = SyncSettings()
) : Syncer<LocalCalendarStore, LocalCalendar>(davSync, accountId, resync, syncResult, settings) {

    override val dataStore = LocalCalendarStore(davSync)

    override val collectionType = Collection.TYPE_CALENDAR

    override fun acceptCollection(collection: Collection) = collection.supportsVEVENT != false

    override fun prepare(client: LocalStorageClient): Boolean {
        val calendarProvider = AndroidCalendarProvider(accountId, client)
        if (settings.eventColors)
            calendarProvider.provideCss3ColorIndices()
        else
            calendarProvider.removeColorIndices()
        return true
    }

    override suspend fun syncCollection(client: LocalStorageClient, localCollection: LocalCalendar, remoteCollectionInfo: Collection) {
        logger.log(
            Level.INFO,
            "Synchronizing calendar #{0}, collection ID: {1}, URL: {2}",
            arrayOf<Any?>(localCollection.androidCalendar.id, localCollection.dbCollectionId, localCollection.androidCalendar.name)
        )
        val limitStart = settings.timeRangePastDays?.let { pastDays ->
            ZonedDateTime.now().minusDays(pastDays.toLong()).toInstant()
        }
        val syncManager = CalendarSyncManager(
            davSync = davSync,
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            localCollection = localCollection,
            collectionInfo = remoteCollectionInfo,
            remoteCollection = CalDavCollection(
                httpClient = httpClient,
                url = remoteCollectionInfo.url,
                filter = CalendarQueryFilter(components = listOf(Component.VEVENT), timeRangeStart = limitStart)
            ),
            resync = resync,
            settings = settings
        )
        syncManager.performSync()
    }
}
