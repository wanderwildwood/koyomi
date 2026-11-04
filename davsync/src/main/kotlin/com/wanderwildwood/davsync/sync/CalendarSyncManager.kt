/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.sync

import android.provider.CalendarContract.Events
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.R
import java.util.UUID
import at.bitfire.dav4jvm.ktor.DavCalendar
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.resource.local.LocalCalendar
import com.wanderwildwood.davsync.resource.local.LocalEvent
import com.wanderwildwood.davsync.resource.local.LocalResource
import com.wanderwildwood.davsync.resource.remote.CalDavCollection
import com.wanderwildwood.davsync.resource.remote.WebDavCollection
import com.wanderwildwood.davsync.util.DavUtils
import com.wanderwildwood.davsync.util.DavUtils.extractFileName
import at.bitfire.synctools.exception.InvalidResourceException
import at.bitfire.synctools.icalendar.CalendarUidSplitter
import at.bitfire.synctools.icalendar.ICalendarGenerator
import at.bitfire.synctools.icalendar.ICalendarParser
import at.bitfire.synctools.mapping.calendar.AndroidEventBuilder
import at.bitfire.synctools.mapping.calendar.AndroidEventHandler
import at.bitfire.synctools.mapping.calendar.DefaultProdIdGenerator
import at.bitfire.synctools.mapping.calendar.SequenceUpdater
import io.ktor.client.HttpClient
import io.ktor.http.content.TextContent
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.VEvent
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.util.logging.Level

/**
 * Synchronization manager for CalDAV collections; handles events (VEVENT).
 */
class CalendarSyncManager(
    davSync: DavSync,
    accountId: AccountId,
    httpClient: HttpClient,
    syncResult: SyncResult,
    override val localCollection: LocalCalendar,
    collectionInfo: Collection,
    override val remoteCollection: CalDavCollection,
    resync: ResyncType?,
    settings: SyncSettings
) : SyncManager<LocalEvent>(
    davSync,
    accountId,
    httpClient,
    SyncDataType.EVENTS,
    syncResult,
    collectionInfo,
    resync,
    settings
) {



    override suspend fun prepare(): Boolean {
        // if there are dirty exceptions for events, mark their master events as dirty, too
        val recurringCalendar = localCollection.recurringCalendar
        recurringCalendar.processDeletedExceptions()
        recurringCalendar.processDirtyExceptions()

        // now find dirty events that have no instances and set them to deleted
        localCollection.androidCalendar.deleteDirtyEventsWithoutInstances()

        return true
    }

    override fun syncAlgorithm(capabilities: WebDavCollection.Capabilities) =
        if (settings.timeRangePastDays != null || !capabilities.canCollectionSync)
            SyncAlgorithm.PROPFIND_REPORT
        else
            SyncAlgorithm.COLLECTION_SYNC

    override suspend fun generateUpload(resource: LocalEvent, capabilities: WebDavCollection.Capabilities): GeneratedResource {
        val localEvent = resource.androidEvent
        logger.log(Level.FINE, "Preparing upload of event #{0}: {1}", arrayOf(resource.id, localEvent))

        /* Increase SEQUENCE of main event in memory and remember new value.
        Will be written to provider later over onSuccessContext. */
        val updatedSequence = SequenceUpdater().increaseSequence(localEvent.main)

        // map Android event to iCalendar (also generates UID, if necessary)
        val handler = AndroidEventHandler(
            accountName = resource.recurringCalendar.calendar.account.name,
            prodIdGenerator = DefaultProdIdGenerator(davSync.iCalProdId)
        )
        val mappedEvents = handler.mapToVEvents(localEvent)

        // persist UID if it was generated
        if (mappedEvents.generatedUid)
            resource.updateUid(mappedEvents.uid)

        // generate iCalendar and convert to request body
        val iCalWriter = StringWriter()
        ICalendarGenerator().write(mappedEvents.associatedEvents, iCalWriter)
        val outgoingContent = TextContent(
            text = iCalWriter.toString(),
            contentType = DavCalendar.MIME_ICALENDAR_UTF8
        )

        return GeneratedResource(
            suggestedFileName = DavUtils.fileNameFromUid(mappedEvents.uid, "ics"),
            content = outgoingContent,
            onSuccessContext = GeneratedResource.OnSuccessContext(
                sequence = updatedSequence
            )
        )
    }

    override suspend fun processDownload(result: WebDavCollection.MultiGetItem) {
        result.url.withExceptionContext {
            val fileName = extractFileName(result.url)
            try {
                processICalendar(
                    fileName = fileName,
                    eTag = result.eTag,
                    scheduleTag = result.scheduleTag,
                    reader = StringReader(result.content)
                )
            } catch (e: InvalidResourceException) {
                logger.log(Level.WARNING, "Could not map event", e)
                notifyInvalidResource(e, fileName)
            }
        }
    }

    override suspend fun postProcess() {}


    // helpers

    private suspend fun processICalendar(fileName: String, eTag: String, scheduleTag: String?, reader: Reader) {
        val calendar = ICalendarParser().parse(reader)

        val uidsAndEvents = CalendarUidSplitter<VEvent>().associateByUid(calendar, Component.VEVENT)
        if (uidsAndEvents.size != 1) {
            logger.warning("Received iCalendar with not exactly one UID; ignoring $fileName")
            return
        }
        // Event: main VEVENT and potentially attached exceptions (further VEVENTs with RECURRENCE-ID)
        val event = uidsAndEvents.values.first()

        // map AssociatedEvents (VEVENTs) to EventAndExceptions (Android events)
        val androidEvent = AndroidEventBuilder(
            calendar = localCollection.androidCalendar,
            syncId = fileName,
            eTag = eTag,
            scheduleTag = scheduleTag,
            flags = LocalResource.FLAG_REMOTELY_PRESENT
        ).build(event)

        // add default reminder (if desired)
        settings.defaultAlarm?.let { minBefore ->
            logger.info("Adding default alarm ($minBefore min before) to $event")
            DefaultReminderBuilder(minBefore = minBefore).add(to = androidEvent)
        }

        // create/update local event in calendar provider
        val local = localCollection.findByName(fileName)
        if (local != null) {
            local.withExceptionContext {
                logger.info("Updating $fileName in local calendar: $event")
                local.update(androidEvent)
            }
        } else {
            logger.info("Adding $fileName to local calendar: $event")
            localCollection.add(androidEvent)
        }
    }


    /**
     * The phone's version as a new event: written out as iCalendar the way an upload is, given a
     * new UID and "(phone's copy)" on its titles, read back the way a download is, and added
     * marked dirty with its own file name, so the next sync uploads it as a new item.
     */
    override suspend fun keepAsCopy(local: LocalEvent) {
        try {
            val handler = AndroidEventHandler(
                accountName = local.recurringCalendar.calendar.account.name,
                prodIdGenerator = DefaultProdIdGenerator(davSync.iCalProdId)
            )
            val writer = StringWriter()
            ICalendarGenerator().write(handler.mapToVEvents(local.androidEvent).associatedEvents, writer)

            val newUid = UUID.randomUUID().toString()
            val newName = "$newUid.ics"
            val text = writer.toString()
                .replace(Regex("\r?\n[ \t]"), "")        // unfold long lines first
                .lines()
                .joinToString("\r\n") { line ->
                    when {
                        line.startsWith("UID:") -> "UID:$newUid"
                        line.startsWith("SUMMARY") -> {
                            val colon = line.indexOf(':')
                            line.substring(0, colon + 1) + context.getString(R.string.davsync_conflict_copy, line.substring(colon + 1))
                        }
                        else -> line
                    }
                }
            val events = CalendarUidSplitter<VEvent>().associateByUid(ICalendarParser().parse(StringReader(text)), Component.VEVENT)
            val event = events.values.singleOrNull() ?: throw IllegalStateException("copy did not parse to one event")
            val androidEvent = AndroidEventBuilder(
                calendar = localCollection.androidCalendar,
                syncId = newName,
                eTag = null,
                scheduleTag = null,
                flags = 0
            ).build(event)
            androidEvent.main.entityValues.put(Events.DIRTY, 1)
            val id = localCollection.add(androidEvent)
            logger.info("Kept the phone's version of ${local.fileName} as event #$id ($newName)")
            requestUploadSync(localCollection.androidCalendar.account)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't keep the phone's version of ${local.fileName}", e)
        }
    }

}
