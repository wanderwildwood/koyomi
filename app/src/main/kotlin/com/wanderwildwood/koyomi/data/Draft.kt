package com.wanderwildwood.koyomi.data

import android.content.ContentValues
import android.provider.CalendarContract.Events
import com.android.calendar.calendarcommon2.EventRecurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An event as the editor holds it. Dates and times are the phone's local ones, the way they
 * were shown; [endDate] is the last day an all-day event covers, not the day after.
 */
data class Draft(
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val calendarId: Long? = null,
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val rrule: String? = null,
    val reminders: List<Int> = emptyList(),
    /**
     * Android's link from an event to the app that made it (Wallet's card, say), for a new
     * event only: an edit leaves the stored one as it is.
     */
    val appPackage: String? = null,
    val appUri: String? = null,
) {
    /** The provider's DTSTART and DTEND for this draft, after the weekly shift below. */
    fun millis(): Pair<Long, Long> = millisFor(shiftedDates())

    /**
     * The start as the person set it, before the weekly shift: for an edit of every occurrence,
     * the change they made to the occurrence they opened is this minus that occurrence's start.
     */
    fun startAsSet(): Long = millisFor(startDate to endDate).first

    private fun millisFor(dates: Pair<LocalDate, LocalDate>): Pair<Long, Long> {
        val (s, e) = dates
        return if (allDay) {
            val start = s.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            // At least one whole day, as Etar ensures.
            val end = maxOf(e.plusDays(1), s.plusDays(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            start to end
        } else {
            val zone = ZoneId.systemDefault()
            val start = s.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
            val end = e.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
            start to maxOf(end, start)
        }
    }

    /**
     * Etar's `offsetStartTimeIfNecessary`: a weekly rule on Tuesdays and Thursdays whose first
     * date is a Monday would put a stray first occurrence on that Monday, because DTSTART is
     * always an occurrence. The start moves forward to the first day the rule names.
     */
    private fun shiftedDates(): Pair<LocalDate, LocalDate> {
        val offset = shiftDays(startDate, weeklyDays(rrule))
        return startDate.plusDays(offset) to endDate.plusDays(offset)
    }

    /** Etar's `getContentValuesFromModel` and `addRecurrenceRule`. */
    fun toValues(timezone: String): ContentValues {
        val (start, end) = millis()
        return ContentValues().apply {
            calendarId?.let { put(Events.CALENDAR_ID, it) }
            put(Events.TITLE, title.trim())
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else timezone)
            put(Events.DTSTART, start)
            put(Events.RRULE, rrule)
            if (rrule != null) {
                val duration = if (allDay) {
                    "P${(end - start + DAY_MS - 1) / DAY_MS}D"
                } else {
                    "P${(end - start) / 1000}S"
                }
                put(Events.DURATION, duration)
                putNull(Events.DTEND)
            } else {
                putNull(Events.DURATION)
                put(Events.DTEND, end)
            }
            put(Events.DESCRIPTION, notes.trim().ifEmpty { null })
            put(Events.EVENT_LOCATION, location.trim().ifEmpty { null })
            if (appPackage != null && appUri != null) {
                put(Events.CUSTOM_APP_PACKAGE, appPackage)
                put(Events.CUSTOM_APP_URI, appUri)
            }
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** The days a weekly rule names, or null for any other rule (or none). */
        fun weeklyDays(rrule: String?): Set<DayOfWeek>? {
            val rule = rrule ?: return null
            val r = runCatching { EventRecurrence().apply { parse(rule) } }.getOrNull() ?: return null
            if (r.freq != EventRecurrence.WEEKLY || r.bydayCount == 0 || r.byday == null) return null
            return (0 until r.bydayCount).map { dayOfWeek(r.byday[it]) }.toSet()
        }

        /** Days forward from [date] to the first of [days]; none when [date] is one, or no days are named. */
        fun shiftDays(date: LocalDate, days: Set<DayOfWeek>?): Long {
            if (days.isNullOrEmpty() || date.dayOfWeek in days) return 0
            return (1..7).first { date.plusDays(it.toLong()).dayOfWeek in days }.toLong()
        }

        /**
         * The series' new first start after "every one" was edited from one occurrence. Only the
         * person's own change ([change], the opened occurrence's new start as set minus its old
         * one) moves the series' start; then, as for a new event, the start goes forward to the
         * first day a weekly rule names ([days]), since the provider always counts the first
         * start as an occurrence. The exclusions move by [change] alone, not by that shift.
         */
        fun seriesStartAfterEdit(seriesBegin: Long, change: Long, allDay: Boolean, days: Set<DayOfWeek>?, zone: ZoneId): Long {
            val moved = seriesBegin + change
            return if (allDay) {
                val date = java.time.Instant.ofEpochMilli(moved).atZone(ZoneOffset.UTC).toLocalDate()
                date.plusDays(shiftDays(date, days)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } else {
                val at = java.time.Instant.ofEpochMilli(moved).atZone(zone)
                at.plusDays(shiftDays(at.toLocalDate(), days)).toInstant().toEpochMilli()
            }
        }

        fun dayOfWeek(erDay: Int): DayOfWeek = when (erDay) {
            EventRecurrence.MO -> DayOfWeek.MONDAY
            EventRecurrence.TU -> DayOfWeek.TUESDAY
            EventRecurrence.WE -> DayOfWeek.WEDNESDAY
            EventRecurrence.TH -> DayOfWeek.THURSDAY
            EventRecurrence.FR -> DayOfWeek.FRIDAY
            EventRecurrence.SA -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }

        fun erDay(day: DayOfWeek): Int = when (day) {
            DayOfWeek.MONDAY -> EventRecurrence.MO
            DayOfWeek.TUESDAY -> EventRecurrence.TU
            DayOfWeek.WEDNESDAY -> EventRecurrence.WE
            DayOfWeek.THURSDAY -> EventRecurrence.TH
            DayOfWeek.FRIDAY -> EventRecurrence.FR
            DayOfWeek.SATURDAY -> EventRecurrence.SA
            DayOfWeek.SUNDAY -> EventRecurrence.SU
        }

        /** A draft of an existing event, showing the occurrence that was opened. */
        fun of(record: EventRecord, begin: Long, end: Long, keepRule: Boolean): Draft {
            val zone = ZoneId.systemDefault()
            val s = CalendarStore.toLocal(begin, record.allDay, zone)
            val e = CalendarStore.toLocal(end, record.allDay, zone)
            return Draft(
                title = record.title,
                location = record.location.orEmpty(),
                notes = record.description.orEmpty(),
                calendarId = record.calendarId,
                allDay = record.allDay,
                startDate = s.toLocalDate(),
                startTime = s.toLocalTime(),
                endDate = if (record.allDay) maxOf(s.toLocalDate(), e.toLocalDate().minusDays(1)) else e.toLocalDate(),
                endTime = e.toLocalTime(),
                rrule = if (keepRule) record.rrule else null,
                reminders = record.reminders.map { it.minutes }.distinct().sorted(),
            )
        }
    }
}
