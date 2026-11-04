/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.resource.local

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.contentValuesOf
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.util.DavUtils.extractCollectionName
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.calendar.AndroidCalendarProvider
import at.bitfire.synctools.storage.calendar.EventsContract.asSyncAdapter
import java.time.ZoneId
import java.util.logging.Level
import javax.annotation.WillNotClose

class LocalCalendarStore(
    davSync: DavSync
): LocalDataStore<LocalCalendar> {

    private val context = davSync.context
    private val logger = davSync.logger


    override val authority: String
        get() = CalendarContract.AUTHORITY

    override fun acquireLocalStorageClient(throwOnMissingPermissions: Boolean): LocalStorageClient? {
        return acquireContentProviderClient(throwOnMissingPermissions)?.let { LocalStorageClient(it) }
    }

    private fun acquireContentProviderClient(throwOnMissingPermissions: Boolean): ContentProviderClient? {
        return try {
            context.contentResolver.acquireContentProviderClient(authority)
        } catch (e: SecurityException) {
            if (throwOnMissingPermissions) {
                throw e
            } else {
                null
            }
        }
    }

    override suspend fun create(accountId: AccountId, client: LocalStorageClient, fromCollection: Collection): LocalCalendar {
        val account = accountId

        // If the collection doesn't have a color, use a default color.
        val collectionWithColor =
            if (fromCollection.color != null)
                fromCollection
            else
                fromCollection.copy(color = DEFAULT_COLOR)

        val values = valuesFromCollectionInfo(
            info = collectionWithColor,
            withColor = true
        ).apply {
            // ACCOUNT_NAME and ACCOUNT_TYPE are required (see docs)! If it's missing, other apps will crash.
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)

            // Email address for scheduling. Used by the calendar provider to determine whether the
            // user is ORGANIZER/ATTENDEE for a certain event.
            put(Calendars.OWNER_ACCOUNT, account.name)

            // flag as visible & syncable at creation, might be changed by user at any time
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }

        logger.info("Adding local calendar: $values")
        val provider = AndroidCalendarProvider(account, client)
        return LocalCalendar(provider.createAndGetCalendar(values))
    }

    override fun getAll(accountId: AccountId, client: LocalStorageClient): List<LocalCalendar> {
        val account = accountId
        return AndroidCalendarProvider(account, client)
            .findCalendars("${Calendars.SYNC_EVENTS}!=0", null)
            .map { androidCalendar -> LocalCalendar(androidCalendar) }
    }

    override fun getByDbCollectionId(
        accountId: AccountId,
        client: LocalStorageClient,
        dbCollectionId: Long
    ): LocalCalendar? {
        val account = accountId
        return AndroidCalendarProvider(account, client)
            .findFirstCalendar("${Calendars._SYNC_ID}=?", arrayOf(dbCollectionId.toString()))
            ?.let { androidCalendar -> LocalCalendar(androidCalendar) }
    }

    override fun update(
        accountId: AccountId,
        client: LocalStorageClient,
        localCollection: LocalCalendar,
        fromCollection: Collection
    ) {
        val values = valuesFromCollectionInfo(
            info = fromCollection,
            withColor = true      // the server's colors (DAVx5's "manage calendar colors", on by default)
        )

        logger.log(Level.FINE, "Updating local calendar {0}: {1}", arrayOf(fromCollection.url, values))
        val androidCalendar = localCollection.androidCalendar
        val provider = AndroidCalendarProvider(androidCalendar.account, client)

        provider.updateCalendar(androidCalendar.id, values)
    }


    private fun valuesFromCollectionInfo(info: Collection, withColor: Boolean): ContentValues {
        val values = contentValuesOf(
            Calendars._SYNC_ID to info.id,

            // Collection URL, so that other apps (which don't have access to DAVx⁵'s database)
            // can find out the URL of the collection. Uses the same column as Google Calendar.
            AndroidCalendarProvider.COLUMN_CALENDAR_URL to info.url.toString(),

            Calendars.CALENDAR_DISPLAY_NAME to
                    if (info.displayName.isNullOrBlank()) extractCollectionName(info.url) else info.displayName,

            Calendars.ALLOWED_AVAILABILITY to arrayOf(
                Events.AVAILABILITY_BUSY,
                Events.AVAILABILITY_FREE
            ).joinToString(",") { it.toString() },

            Calendars.ALLOWED_ATTENDEE_TYPES to arrayOf(
                Attendees.TYPE_NONE,
                Attendees.TYPE_OPTIONAL,
                Attendees.TYPE_REQUIRED,
                Attendees.TYPE_RESOURCE
            ).joinToString(",") { it.toString() },

            Calendars.ALLOWED_REMINDERS to arrayOf(
                Reminders.METHOD_DEFAULT,
                Reminders.METHOD_ALERT,
                Reminders.METHOD_EMAIL
            ).joinToString(",") { it.toString() },
        )

        // By default, set all new calendars as non-primary. Right now we do not support actually fetching the primary calendar.
        // Google Calendar uses this field to determine whether to read the calendar name together with the account name in TalkBack:
        //   if primary, the calendar name is excluded, and as such, all calendars in the account sound the same.
        values.put(Calendars.IS_PRIMARY, 0)

        if (withColor && info.color != null)
            values.put(Calendars.CALENDAR_COLOR, info.color)

        if (info.privWriteContent && !info.forceReadOnly) {
            values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            values.put(Calendars.CAN_MODIFY_TIME_ZONE, 1)
            values.put(Calendars.CAN_ORGANIZER_RESPOND, 1)
        } else
            values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)

        info.timezoneId?.let { tzId ->
            val validSystemTzId = try {
                ZoneId.of(tzId)
                true
            } catch (_: Exception) {
                false
            }
            values.put(Calendars.CALENDAR_TIME_ZONE, tzId.takeIf { validSystemTzId })
        }

        return values
    }

    override fun updateAccount(oldAccount: Account, newAccount: Account, @WillNotClose client: LocalStorageClient?) {
        if (client == null)
            return
        val values = contentValuesOf(
            // Account name to be changed
            Calendars.ACCOUNT_NAME to newAccount.name,
            // Owner account of this calendar to be changed. Used by the calendar
            // provider to determine whether the user is ORGANIZER/ATTENDEE (usually an email address) for a certain event.
            Calendars.OWNER_ACCOUNT to newAccount.name
        )
        val uri = Calendars.CONTENT_URI.asSyncAdapter(oldAccount)
        client.update(uri, values, "${Calendars.ACCOUNT_NAME}=?", arrayOf(oldAccount.name))
    }

    override fun delete(localCollection: LocalCalendar) {
        logger.info("Deleting local calendar: $localCollection")
        localCollection.androidCalendar.delete()
    }


    companion object {
        /** For a calendar the server gives no color: black, on an e-ink screen. */
        const val DEFAULT_COLOR = 0xFF000000.toInt()
    }

}
