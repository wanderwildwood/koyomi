package com.wanderwildwood.koyomi.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import com.android.calendar.calendarcommon2.EventRecurrence
import com.android.calendar.calendarcommon2.Time
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Everything this app knows is in the phone's own calendar store, the one DAVx5 syncs into
 * and every other calendar app reads. There is no database of its own and no account: an
 * event written here reaches Nextcloud because DAVx5 notices the row is dirty, not because
 * this app sends anything.
 *
 * The write paths follow Etar's `EditEventHelper` and `DeleteEventHelper` closely, because
 * a sync adapter is particular about how an exception to a repeating event is shaped, and
 * Etar's shape is the one that has been round-tripping through DAVx5 for years.
 */
class CalendarStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!canRead()) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.VISIBLE,
                Calendars.CALENDAR_ACCESS_LEVEL,
                Calendars.IS_PRIMARY,
            ),
            null, null,
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    accountName = c.getString(2).orEmpty(),
                    accountType = c.getString(3).orEmpty(),
                    visible = c.getInt(4) == 1,
                    writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    isPrimary = !c.isNull(6) && c.getInt(6) == 1,
                )
            }
        }
        return out
    }

    fun setVisible(calendarId: Long, visible: Boolean) {
        if (!canWrite()) return
        resolver.update(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId),
            ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) },
            null, null,
        )
    }

    /** Every occurrence touching any day from [from] to [to], inclusive, in visible calendars. */
    fun occurrences(from: LocalDate, to: LocalDate, query: String? = null): List<Occurrence> {
        if (!canRead()) return emptyList()
        val zone = ZoneId.systemDefault()
        // Widened by a day either side: an all-day event is stored in UTC, so near midnight
        // its millis fall on the neighbouring local day. The date check below trims it back.
        val fromMs = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()

        val out = mutableListOf<Occurrence>()
        resolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID,
                Instances.CALENDAR_ID,
                Instances.TITLE,
                Instances.EVENT_LOCATION,
                Instances.BEGIN,
                Instances.END,
                Instances.ALL_DAY,
                Instances.RRULE,
                Instances.DESCRIPTION,
                Instances.ORIGINAL_ID,
            ),
            "${Instances.VISIBLE} = 1 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})",
            null,
            "${Instances.BEGIN} ASC, ${Instances.END} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(6) == 1
                val begin = c.getLong(4)
                val end = c.getLong(5)
                val o = Occurrence(
                    eventId = c.getLong(0),
                    calendarId = c.getLong(1),
                    title = c.getString(2).orEmpty(),
                    location = c.getString(3)?.takeIf { it.isNotBlank() },
                    begin = begin,
                    end = end,
                    allDay = allDay,
                    repeats = !c.getString(7).isNullOrBlank() || !c.isNull(9),
                    start = toLocal(begin, allDay, zone),
                    finish = toLocal(end, allDay, zone),
                )
                if (o.lastDay.isBefore(from) || o.firstDay.isAfter(to)) continue
                if (query != null) {
                    val q = query.trim().lowercase()
                    val description = c.getString(8).orEmpty()
                    val hit = o.title.lowercase().contains(q) ||
                        o.location.orEmpty().lowercase().contains(q) ||
                        description.lowercase().contains(q)
                    if (!hit) continue
                }
                out += o
            }
        }
        return out
    }

    fun event(eventId: Long): EventRecord? {
        if (!canRead()) return null
        val reminders = mutableListOf<Reminder>()
        resolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${Reminders.MINUTES} ASC",
        )?.use { c ->
            while (c.moveToNext()) reminders += Reminder(c.getInt(0), c.getInt(1))
        }
        resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(
                Events._ID,
                Events.CALENDAR_ID,
                Events.TITLE,
                Events.DESCRIPTION,
                Events.EVENT_LOCATION,
                Events.DTSTART,
                Events.DTEND,
                Events.DURATION,
                Events.ALL_DAY,
                Events.EVENT_TIMEZONE,
                Events.RRULE,
                Events._SYNC_ID,
                Events.ORIGINAL_ID,
                Events.STATUS,
                Events.ACCOUNT_TYPE,
                Events.EXDATE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            return EventRecord(
                id = c.getLong(0),
                calendarId = c.getLong(1),
                title = c.getString(2).orEmpty(),
                description = c.getString(3)?.takeIf { it.isNotBlank() },
                location = c.getString(4)?.takeIf { it.isNotBlank() },
                dtStart = c.getLong(5),
                dtEnd = if (c.isNull(6)) null else c.getLong(6),
                duration = c.getString(7),
                allDay = c.getInt(8) == 1,
                timezone = c.getString(9),
                rrule = c.getString(10)?.takeIf { it.isNotBlank() },
                syncId = c.getString(11),
                originalId = if (c.isNull(12)) null else c.getLong(12),
                status = if (c.isNull(13)) null else c.getInt(13),
                reminders = reminders,
                accountType = c.getString(14).orEmpty(),
                exdate = c.getString(15)?.takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    /** Is this occurrence still there? An alert for one that was deleted since says nothing. */
    fun occurrenceExists(eventId: Long, begin: Long): Boolean {
        if (!canRead()) return false
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin - 1)
            ContentUris.appendId(it, begin + 1)
        }.build()
        resolver.query(
            uri,
            arrayOf(Instances.EVENT_ID),
            "${Instances.EVENT_ID} = ? AND ${Instances.BEGIN} = ?",
            arrayOf(eventId.toString(), begin.toString()),
            null,
        )?.use { return it.count > 0 }
        return false
    }

    // ------------------------------------------------------------------------------------
    // Writing

    /** Inserts a new event and returns its id, or null if the provider refused it. */
    fun insert(draft: Draft): Long? {
        if (!canWrite()) return null
        val values = draft.toValues(ZoneId.systemDefault().id)
        values.put(Events.HAS_ALARM, if (draft.reminders.isNotEmpty()) 1 else 0)
        values.put(Events.STATUS, Events.STATUS_CONFIRMED)
        values.put(Events.HAS_ATTENDEE_DATA, 1)
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        writeReminders(id, draft.reminders, emptyList(), force = true)
        return id
    }

    /**
     * Saves [draft] over [record]. [occurrenceBegin] is the provider's BEGIN of the occurrence
     * that was opened; for a non-repeating event it is simply the event's start.
     *
     * Etar's `saveEvent`, less "this and every later one", and plus a move to another
     * calendar, which Etar does not offer.
     */
    fun update(record: EventRecord, occurrenceBegin: Long, occurrenceEnd: Long, draft: Draft, scope: Scope): Boolean {
        if (!canWrite()) return false
        if (draft.calendarId != null && draft.calendarId != record.calendarId) {
            return move(record, occurrenceBegin, draft)
        }
        val tz = record.timezone ?: ZoneId.systemDefault().id
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, record.id)
        val values = draft.toValues(tz)
        values.remove(Events.CALENDAR_ID)
        values.put(Events.HAS_ALARM, if (draft.reminders.isNotEmpty()) 1 else 0)

        val wasRepeating = record.rrule != null
        val isRepeating = draft.rrule != null
        val (newBegin, newEnd) = draft.millis()

        when {
            !wasRepeating -> {
                // A plain event, or one that has just been made to repeat.
                if (!isRepeating) dropUnchangedTimes(values, record, occurrenceBegin, occurrenceEnd, newBegin, newEnd, draft)
                if (resolver.update(uri, values, null, null) <= 0) return false
                writeReminders(record.id, draft.reminders, record.reminders, force = false)
            }

            scope == Scope.ONE && record.syncId == null -> {
                // No sync id yet: see [exclude]. The occurrence leaves the series and comes
                // back as an event of its own.
                if (!exclude(record, occurrenceBegin)) return false
                values.put(Events.CALENDAR_ID, record.calendarId)
                values.putNull(Events.RRULE)
                values.putNull(Events.DURATION)
                values.put(Events.DTEND, newEnd)
                values.put(Events.STATUS, record.status ?: Events.STATUS_CONFIRMED)
                val inserted = resolver.insert(Events.CONTENT_URI, values) ?: return false
                writeReminders(ContentUris.parseId(inserted), draft.reminders, emptyList(), force = true)
            }

            scope == Scope.ONE -> {
                // An exception, made through the provider's own exception path, which links
                // it to the series by row id and copies the series' sync id itself. That path
                // takes only the columns AOSP lists in ALLOWED_IN_EXCEPTION — no DTEND, which
                // it works out from DURATION — so the values are built for it from scratch.
                val allowed = ContentValues().apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, occurrenceBegin)
                    put(Events.TITLE, draft.title.trim())
                    put(Events.EVENT_LOCATION, draft.location.trim().ifEmpty { null })
                    put(Events.DESCRIPTION, draft.notes.trim().ifEmpty { null })
                    put(Events.DTSTART, newBegin)
                    put(
                        Events.DURATION,
                        if (draft.allDay) "P${maxOf(1, (newEnd - newBegin) / DAY_MS)}D" else "P${(newEnd - newBegin) / 1000}S",
                    )
                    put(Events.ALL_DAY, if (draft.allDay) 1 else 0)
                    put(Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else tz)
                    put(Events.HAS_ALARM, if (draft.reminders.isNotEmpty()) 1 else 0)
                    put(Events.STATUS, record.status ?: Events.STATUS_CONFIRMED)
                }
                val inserted = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, record.id), allowed)
                    ?: return false
                writeReminders(ContentUris.parseId(inserted), draft.reminders, emptyList(), force = true)
            }

            !isRepeating -> {
                // Every occurrence, and the rule is gone: the series is replaced by one event.
                resolver.delete(syncAwareUri(record), null, null)
                values.put(Events.CALENDAR_ID, record.calendarId)
                values.put(Events.STATUS, record.status ?: Events.STATUS_CONFIRMED)
                val inserted = resolver.insert(Events.CONTENT_URI, values) ?: return false
                writeReminders(ContentUris.parseId(inserted), draft.reminders, emptyList(), force = true)
            }

            else -> {
                // Every occurrence. The series keeps its own first date: what moves is only
                // the difference the edit made to the occurrence that was opened.
                val unchanged = dropUnchangedTimes(values, record, occurrenceBegin, occurrenceEnd, newBegin, newEnd, draft)
                if (!unchanged) {
                    values.put(Events.DTSTART, seriesStart(occurrenceBegin, newBegin, record.dtStart, draft.allDay))
                    // See seriesValues: a timing update carries the exclusions with it, and they
                    // move with the series, or the occurrences they removed come back.
                    values.put(Events.EXDATE, Exdates.shift(record.exdate, newBegin - occurrenceBegin))
                }
                if (resolver.update(uri, values, null, null) <= 0) return false
                writeReminders(record.id, draft.reminders, record.reminders, force = false)
            }
        }
        return true
    }

    /**
     * Saves [draft] into another calendar: a new event there, and the old one deleted.
     *
     * Not CALENDAR_ID changed in place: the row would keep the sync id and server address
     * of the collection it came from, which no sync adapter is asked to follow. A new row is
     * uploaded to the new collection and the deleted one removed from the old, which is how
     * a move between two CalDAV collections is made on a server too.
     *
     * Offered only where nothing would be lost: a whole series that has no changed or
     * cancelled occurrences of its own ([hasExceptions]), or an event that does not repeat.
     */
    private fun move(record: EventRecord, occurrenceBegin: Long, draft: Draft): Boolean {
        val tz = record.timezone ?: ZoneId.systemDefault().id
        val values = draft.toValues(tz)
        values.put(Events.HAS_ALARM, if (draft.reminders.isNotEmpty()) 1 else 0)
        values.put(Events.STATUS, record.status ?: Events.STATUS_CONFIRMED)
        values.put(Events.HAS_ATTENDEE_DATA, 1)
        if (record.rrule != null && draft.rrule != null) {
            // As in update(): the series keeps its first date and its exclusions, moved by
            // whatever the edit did to the occurrence that was opened.
            val (newBegin, _) = draft.millis()
            values.put(Events.DTSTART, seriesStart(occurrenceBegin, newBegin, record.dtStart, draft.allDay))
            values.put(Events.EXDATE, Exdates.shift(record.exdate, newBegin - occurrenceBegin))
        }
        val inserted = resolver.insert(Events.CONTENT_URI, values) ?: return false
        val id = ContentUris.parseId(inserted)
        writeReminders(id, draft.reminders, record.reminders, force = true)
        if (resolver.delete(syncAwareUri(record), null, null) <= 0) {
            // The old one stays, so the new one goes, or the event would be there twice.
            resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
            return false
        }
        return true
    }

    /** Has this series any occurrence changed or cancelled on its own? A move would lose it. */
    fun hasExceptions(record: EventRecord): Boolean {
        if (!canRead() || record.rrule == null) return false
        val where = StringBuilder("${Events.ORIGINAL_ID} = ?")
        val args = mutableListOf(record.id.toString())
        if (record.syncId != null) {
            where.append(" OR (${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.CALENDAR_ID} = ?)")
            args += record.syncId
            args += record.calendarId.toString()
        }
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), where.toString(), args.toTypedArray(), null)
            ?.use { return it.count > 0 }
        return false
    }

    /**
     * Etar's `checkTimeDependentFields`: if nothing about when the event happens changed,
     * leave every time column alone, so that saving a new title does not rewrite a start
     * time the server gave us (and possibly round it). Returns true if they were dropped.
     */
    private fun dropUnchangedTimes(
        values: ContentValues,
        record: EventRecord,
        oldBegin: Long,
        oldEnd: Long,
        newBegin: Long,
        newEnd: Long,
        draft: Draft,
    ): Boolean {
        val same = oldBegin == newBegin && oldEnd == newEnd && record.allDay == draft.allDay &&
            record.rrule == draft.rrule
        if (same) {
            values.remove(Events.DTSTART)
            values.remove(Events.DTEND)
            values.remove(Events.DURATION)
            values.remove(Events.ALL_DAY)
            values.remove(Events.RRULE)
            values.remove(Events.EVENT_TIMEZONE)
        }
        return same
    }

    /** Etar's `getStartTimeForRecurrence`. */
    private fun seriesStart(oldBegin: Long, newBegin: Long, seriesBegin: Long, allDay: Boolean): Long {
        var start = seriesBegin + (newBegin - oldBegin)
        if (allDay) {
            start = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return start
    }

    fun delete(record: EventRecord, occurrenceBegin: Long, occurrenceEnd: Long, scope: Scope): Boolean {
        if (!canWrite()) return false

        // Deleting an exception outright would bring back the occurrence it replaced, so an
        // exception is cancelled instead, which is what Etar's deleteExceptionEvent does.
        if (record.originalId != null) {
            val values = ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }
            return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, record.id), values, null, null) > 0
        }

        if (record.rrule == null || scope == Scope.ALL ||
            (scope == Scope.FOLLOWING && occurrenceBegin == record.dtStart)
        ) {
            return resolver.delete(syncAwareUri(record), null, null) > 0
        }

        return when (scope) {
            Scope.ONE -> if (record.syncId == null) exclude(record, occurrenceBegin) else {
                // A cancelled exception, through the provider's exception path; see update().
                val values = ContentValues().apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, occurrenceBegin)
                    put(Events.STATUS, Events.STATUS_CANCELED)
                }
                resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, record.id), values) != null
            }

            Scope.FOLLOWING -> {
                // The series now ends the second before this occurrence. UNTIL alone does it:
                // a COUNT that would have ended the series sooner would have meant there was
                // no such occurrence to press on, so the count is simply dropped.
                val recurrence = EventRecurrence().apply { parse(record.rrule) }
                val until = Time().apply {
                    if (record.allDay) timezone = Time.TIMEZONE_UTC
                    set(occurrenceBegin)
                    second = second - 1
                    normalize()
                    switchTimezone(Time.TIMEZONE_UTC)
                    if (record.allDay) {
                        hour = 0
                        minute = 0
                        second = 0
                        isAllDay = true
                        normalize()
                    }
                }
                recurrence.until = until.format2445()
                recurrence.count = 0
                val values = seriesValues(record).apply { put(Events.RRULE, recurrence.toString()) }
                resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, record.id), values, null, null) > 0
            }

            Scope.ALL -> false
        }
    }

    /**
     * The whole of a series' timing, as it stands. Any update that touches one part of it —
     * an EXDATE, a new UNTIL, a new start — has to carry all of it: measured on the emulator,
     * an update carrying EXDATE alone made the store re-expand the series as if it did not
     * repeat, and every occurrence after the first vanished from every calendar app.
     */
    private fun seriesValues(record: EventRecord): ContentValues = ContentValues().apply {
        put(Events.DTSTART, record.dtStart)
        put(Events.RRULE, record.rrule)
        put(Events.DURATION, record.duration ?: record.dtEnd?.let { "P${(it - record.dtStart) / 1000}S" } ?: "P3600S")
        putNull(Events.DTEND)
        put(Events.EVENT_TIMEZONE, record.timezone ?: ZoneId.systemDefault().id)
        put(Events.ALL_DAY, if (record.allDay) 1 else 0)
        put(Events.EXDATE, record.exdate)
    }

    /**
     * Takes one occurrence out of a series by adding it to the series' EXDATE.
     *
     * Used instead of an exception when the series has no sync id — every event in a local
     * calendar, and one made on the phone that DAVx5 has not uploaded yet. The calendar store
     * files exceptions under the series' sync id, and with none to file them under, measured
     * on the emulator, a single cancelled exception made every occurrence of the series
     * disappear. EXDATE is plain iCalendar and DAVx5 carries it to the server as it is.
     */
    private fun exclude(record: EventRecord, begin: Long): Boolean {
        val stamp = if (record.allDay) {
            java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC))
        } else {
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(Instant.ofEpochMilli(begin).atOffset(ZoneOffset.UTC))
        }
        val values = seriesValues(record).apply {
            put(Events.EXDATE, listOfNotNull(record.exdate, stamp).joinToString(","))
        }
        return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, record.id), values, null, null) > 0
    }

    /**
     * A local calendar has no sync adapter to finish a delete, so its rows are removed as
     * one would, and are not left marked for a sync that never comes. Etar does the same.
     */
    private fun syncAwareUri(record: EventRecord): Uri {
        val base = ContentUris.withAppendedId(Events.CONTENT_URI, record.id)
        if (record.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) return base
        val calendar = calendars().firstOrNull { it.id == record.calendarId } ?: return base
        return base.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, calendar.accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, calendar.accountType)
            .build()
    }

    /**
     * Replaces the reminders only if they changed, as Etar's `saveReminders` does. A server
     * may hold e-mail or other kinds of reminder alongside the alerts this app shows; a
     * reminder that stays keeps the method it came with.
     */
    private fun writeReminders(eventId: Long, minutes: List<Int>, original: List<Reminder>, force: Boolean) {
        val wanted = minutes.distinct().sorted()
        if (!force && wanted == original.map { it.minutes }.sorted()) return
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        for (m in wanted) {
            val method = original.firstOrNull { it.minutes == m }?.method ?: Reminders.METHOD_ALERT
            resolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, m)
                    put(Reminders.METHOD, method)
                },
            )
        }
    }

    companion object {
        fun toLocal(ms: Long, allDay: Boolean, zone: ZoneId): LocalDateTime =
            if (allDay) {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
            } else {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
            }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** How much of a repeating event an edit or a delete applies to. */
enum class Scope { ONE, FOLLOWING, ALL }
