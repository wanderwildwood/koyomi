package com.wanderwildwood.koyomi.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * What another app's "add to calendar" asks for: the extras of an `ACTION_INSERT`, read the
 * way Etar's `EditEventFragment` reads them. Nothing is saved from them; they fill the editor.
 */
data class Prefill(
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val rrule: String? = null,
    val begin: Long? = null,
    val end: Long? = null,
    val allDay: Boolean = false,
    val calendarId: Long? = null,
) {
    /**
     * [base] carries the calendar and reminder a new event would have had. With no begin, the
     * event starts at the next whole hour, as a new event here does.
     *
     * An all-day begin is a date. Apps send it two ways — the local midnight, as Etar does, or
     * the UTC midnight the store itself uses — and a UTC midnight read in a zone west of
     * Greenwich is the evening before. So an exact UTC midnight is read in UTC, and anything
     * else in the phone's zone.
     */
    fun draft(base: Draft, zone: ZoneId, now: LocalDateTime): Draft {
        val start = begin?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) }
            ?: now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        var d = base.copy(
            title = title?.trim().orEmpty(),
            location = location?.trim().orEmpty(),
            notes = description?.trim().orEmpty(),
            rrule = rrule?.trim()?.ifEmpty { null },
        )
        val chosen = calendarId
        if (chosen != null) d = d.copy(calendarId = chosen)
        if (allDay) {
            val first = begin?.let { date(it, zone) } ?: start.toLocalDate()
            // The end is the day after the last one, as the store keeps it.
            val last = end?.let { date(it, zone).minusDays(1) }?.takeIf { !it.isBefore(first) } ?: first
            return d.copy(
                allDay = true,
                startDate = first,
                endDate = last,
                reminders = if (d.reminders.isEmpty()) emptyList() else listOf(900),
            )
        }
        val finish = end?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) }
            ?.takeIf { !it.isBefore(start) } ?: start.plusHours(1)
        return d.copy(
            allDay = false,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = finish.toLocalDate(),
            endTime = finish.toLocalTime(),
        )
    }

    private fun date(ms: Long, zone: ZoneId): LocalDate {
        val utc = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        return if (utc.toLocalTime() == java.time.LocalTime.MIDNIGHT) utc.toLocalDate() else Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }
}
