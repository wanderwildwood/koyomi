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
) {
    /** The provider's DTSTART and DTEND for this draft, after the weekly shift below. */
    fun millis(): Pair<Long, Long> {
        val (s, e) = shiftedDates()
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
        val rule = rrule ?: return startDate to endDate
        val r = runCatching { EventRecurrence().apply { parse(rule) } }.getOrNull() ?: return startDate to endDate
        if (r.freq != EventRecurrence.WEEKLY || r.bydayCount == 0 || r.byday == null) return startDate to endDate
        val days = (0 until r.bydayCount).map { dayOfWeek(r.byday[it]) }.toSet()
        if (startDate.dayOfWeek in days) return startDate to endDate
        val offset = (1..7).first { startDate.plusDays(it.toLong()).dayOfWeek in days }.toLong()
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
        }
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

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
