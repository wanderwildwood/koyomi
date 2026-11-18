package com.wanderwildwood.koyomi.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * What an edit of "every one" did to the occurrence that was opened, in calendar terms: whole
 * days, and a change of the time of day. Not a count of milliseconds, which across a change
 * of the clocks would move a series that does not cross one by an hour (09:00 to 08:00).
 */
data class SeriesShift(val days: Long, val time: Duration) {
    val isNone: Boolean get() = days == 0L && time.isZero

    /** [begin] moved by this shift, on the clock of [zone] (UTC for an all-day event). */
    fun apply(begin: Long, allDay: Boolean, zone: ZoneId): Long {
        val z = if (allDay) ZoneOffset.UTC else zone
        return apply(Instant.ofEpochMilli(begin).atZone(z).toLocalDateTime()).atZone(z).toInstant().toEpochMilli()
    }

    fun apply(at: LocalDateTime): LocalDateTime = at.plusDays(days).plus(time)

    companion object {
        val NONE = SeriesShift(0, Duration.ZERO)

        /** The shift from [oldBegin] to [newBegin], read on the clock of [zone] (UTC when all-day). */
        fun between(oldBegin: Long, newBegin: Long, allDay: Boolean, zone: ZoneId): SeriesShift {
            val z = if (allDay) ZoneOffset.UTC else zone
            val old = Instant.ofEpochMilli(oldBegin).atZone(z).toLocalDateTime()
            val new = Instant.ofEpochMilli(newBegin).atZone(z).toLocalDateTime()
            val days = ChronoUnit.DAYS.between(old.toLocalDate(), new.toLocalDate())
            return SeriesShift(days, Duration.between(old.toLocalTime(), new.toLocalTime()))
        }
    }
}
