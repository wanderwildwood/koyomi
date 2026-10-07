package com.wanderwildwood.koyomi.data

import java.time.LocalDate
import java.time.LocalDateTime

/** One calendar as the phone knows it: a DAVx5 collection, a Google calendar, a local one. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val visible: Boolean,
    val writable: Boolean,
    val isPrimary: Boolean,
)

/**
 * One occurrence of an event, as `CalendarContract.Instances` expands it.
 *
 * [begin] and [end] are the provider's own milliseconds and are kept untouched, because they
 * are what a recurrence exception has to be keyed on (`ORIGINAL_INSTANCE_TIME`). Converting
 * them to a local time and back would move an all-day occurrence by the zone offset, and the
 * exception would then match no occurrence at all.
 *
 * [start] and [finish] are for drawing. An all-day event is stored as UTC midnights, so its
 * dates are read in UTC; a timed one is read in the phone's zone. [finish] is exclusive for
 * an all-day event, as the provider stores it — use [lastDay] for the last date it covers.
 */
data class Occurrence(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val repeats: Boolean,
    val start: LocalDateTime,
    val finish: LocalDateTime,
) {
    val firstDay: LocalDate get() = start.toLocalDate()

    val lastDay: LocalDate
        get() {
            val d = finish.toLocalDate()
            // An all-day end is the midnight after; a timed event ending exactly at midnight
            // is also over by then and does not touch the next day.
            return if ((allDay || finish.toLocalTime() == java.time.LocalTime.MIDNIGHT) && d.isAfter(firstDay)) {
                d.minusDays(1)
            } else {
                d
            }
        }

    fun touches(date: LocalDate): Boolean = !date.isBefore(firstDay) && !date.isAfter(lastDay)
}

/** A reminder as the provider stores it. [method] is kept so saving does not rewrite it. */
data class Reminder(val minutes: Int, val method: Int)

/** The event row itself, read from `Events`, which is what editing and deleting work on. */
data class EventRecord(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timezone: String?,
    val rrule: String?,
    val syncId: String?,
    val exdate: String?,
    val originalId: Long?,
    val status: Int?,
    val reminders: List<Reminder>,
    val accountType: String,
    val appPackage: String? = null,
    val appUri: String? = null,
)
