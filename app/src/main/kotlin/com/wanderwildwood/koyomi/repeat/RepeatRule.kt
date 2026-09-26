package com.wanderwildwood.koyomi.repeat

import com.android.calendar.calendarcommon2.EventRecurrence
import com.wanderwildwood.koyomi.data.Draft
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A repeat rule in the shape the editor can show: Etar's `RecurrencePickerDialog.RecurrenceModel`,
 * with `canHandleRecurrenceRule`, `copyEventRecurrenceToModel` and `copyModelToEventRecurrence`
 * carried over. The parsing and writing of the RRULE itself is Etar's own `EventRecurrence`,
 * unchanged, so a rule that comes from Nextcloud is read by the same code that has been
 * reading them in Etar.
 *
 * A rule this shape cannot hold — two days of the month, a BYSETPOS, an hourly rule — is
 * never opened in the editor. It is shown, and left exactly as it is on save.
 */
data class RepeatRule(
    val freq: Freq = Freq.WEEKLY,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthly: Monthly = Monthly.BY_DATE,
    /** For [Monthly.BY_WEEKDAY]: 1 to 5, or -1 for the last. */
    val nth: Int = 1,
    val end: End = End.NEVER,
    val until: LocalDate? = null,
    val count: Int = 10,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }
    enum class Monthly { BY_DATE, BY_WEEKDAY }
    enum class End { NEVER, DATE, COUNT }

    /** The RRULE for an event starting on [start]; [allDay] decides how UNTIL is written. */
    fun toRrule(start: LocalDate, allDay: Boolean, weekStart: DayOfWeek): String {
        val er = EventRecurrence()
        er.freq = when (freq) {
            Freq.DAILY -> EventRecurrence.DAILY
            Freq.WEEKLY -> EventRecurrence.WEEKLY
            Freq.MONTHLY -> EventRecurrence.MONTHLY
            Freq.YEARLY -> EventRecurrence.YEARLY
        }
        er.interval = if (interval <= 1) 0 else interval
        er.wkst = Draft.erDay(weekStart)

        when (end) {
            End.DATE -> {
                val date = until ?: start
                // Inclusive of that day's occurrence. An all-day series ends at the UTC
                // midnight of its last date, which is how an all-day event is stored; a
                // timed one at the last second of the date, wherever the phone is.
                val instant = if (allDay) {
                    date.atStartOfDay(ZoneOffset.UTC).toInstant()
                } else {
                    date.atTime(LocalTime.of(23, 59, 59)).atZone(ZoneId.systemDefault()).toInstant()
                }
                er.until = UNTIL_FORMAT.format(instant.atOffset(ZoneOffset.UTC))
                er.count = 0
            }
            End.COUNT -> {
                er.count = count.coerceAtLeast(1)
                er.until = null
            }
            End.NEVER -> {
                er.count = 0
                er.until = null
            }
        }

        er.bydayCount = 0
        er.bymonthdayCount = 0
        when (freq) {
            Freq.WEEKLY -> {
                val days = weekdays.ifEmpty { setOf(start.dayOfWeek) }.sortedBy { it.value }
                er.byday = IntArray(days.size) { Draft.erDay(days[it]) }
                er.bydayNum = IntArray(days.size)
                er.bydayCount = days.size
            }
            Freq.MONTHLY -> if (monthly == Monthly.BY_DATE) {
                er.bymonthday = intArrayOf(start.dayOfMonth)
                er.bymonthdayCount = 1
            } else {
                er.byday = intArrayOf(Draft.erDay(start.dayOfWeek))
                er.bydayNum = intArrayOf(nth)
                er.bydayCount = 1
            }
            else -> Unit
        }
        return er.toString()
    }

    companion object {
        private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /** A fresh rule for an event on [start], the way Etar seeds its picker. */
        fun fresh(freq: Freq, start: LocalDate): RepeatRule = RepeatRule(
            freq = freq,
            weekdays = setOf(start.dayOfWeek),
            nth = nthOf(start),
            until = start.plusMonths(if (freq == Freq.YEARLY) 36 else if (freq == Freq.MONTHLY) 3 else 1),
        )

        /** Which week of its month [date] falls in, 1 to 5. */
        fun nthOf(date: LocalDate): Int = (date.dayOfMonth - 1) / 7 + 1

        /** Whether [date] is the last of its weekday in its month. */
        fun isLastOfMonth(date: LocalDate): Boolean = date.plusWeeks(1).month != date.month

        fun parse(rrule: String): EventRecurrence? =
            runCatching { EventRecurrence().apply { parse(rrule) } }.getOrNull()

        /** Etar's `canHandleRecurrenceRule`. */
        fun canEdit(rrule: String): Boolean {
            val er = parse(rrule) ?: return false
            when (er.freq) {
                EventRecurrence.DAILY, EventRecurrence.WEEKLY,
                EventRecurrence.MONTHLY, EventRecurrence.YEARLY -> Unit
                else -> return false
            }
            if (er.count > 0 && !er.until.isNullOrEmpty()) return false
            // Parts the shape above has nowhere to keep.
            if (er.bysecondCount > 0 || er.byminuteCount > 0 || er.byhourCount > 0 ||
                er.byyeardayCount > 0 || er.byweeknoCount > 0 || er.bysetposCount > 0
            ) return false
            if (er.bymonthCount > 0 && er.freq != EventRecurrence.YEARLY) return false
            var nthCount = 0
            for (i in 0 until er.bydayCount) if (supportedNth(er.bydayNum[i])) nthCount++
            if (nthCount > 1) return false
            if (nthCount > 0 && er.freq != EventRecurrence.MONTHLY) return false
            if (er.bymonthdayCount > 1) return false
            if (er.freq == EventRecurrence.MONTHLY) {
                if (er.bydayCount > 1) return false
                if (er.bydayCount > 0 && er.bymonthdayCount > 0) return false
                if (er.bydayCount == 1 && nthCount == 0) return false
            }
            if (er.freq == EventRecurrence.YEARLY && (er.bydayCount > 0 || er.bymonthdayCount > 0 || er.bymonthCount > 1)) {
                return false
            }
            if (er.freq == EventRecurrence.DAILY && (er.bydayCount > 0 || er.bymonthdayCount > 0)) return false
            return true
        }

        private fun supportedNth(n: Int) = n in 1..5 || n == -1

        /** Etar's `copyEventRecurrenceToModel`. Null if [canEdit] would say no. */
        fun from(rrule: String, start: LocalDate, allDay: Boolean): RepeatRule? {
            if (!canEdit(rrule)) return null
            val er = parse(rrule) ?: return null
            val freq = when (er.freq) {
                EventRecurrence.DAILY -> Freq.DAILY
                EventRecurrence.WEEKLY -> Freq.WEEKLY
                EventRecurrence.MONTHLY -> Freq.MONTHLY
                else -> Freq.YEARLY
            }
            var rule = fresh(freq, start).copy(interval = er.interval.coerceAtLeast(1))
            if (er.count > 0) rule = rule.copy(end = End.COUNT, count = er.count)
            untilDate(er.until, allDay)?.let { rule = rule.copy(end = End.DATE, until = it) }
            if (freq == Freq.WEEKLY && er.bydayCount > 0) {
                rule = rule.copy(weekdays = (0 until er.bydayCount).map { Draft.dayOfWeek(er.byday[it]) }.toSet())
            }
            if (freq == Freq.MONTHLY && er.bydayCount == 1) {
                rule = rule.copy(monthly = Monthly.BY_WEEKDAY, nth = er.bydayNum[0])
            }
            return rule
        }

        /** The last date an UNTIL covers, as the phone would show it. */
        fun untilDate(until: String?, allDay: Boolean): LocalDate? {
            if (until.isNullOrEmpty() || until.length < 8) return null
            return runCatching {
                val date = LocalDate.parse(until.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
                if (until.length >= 15 && until.endsWith("Z") && !allDay) {
                    val t = LocalTime.parse(until.substring(9, 15), DateTimeFormatter.ofPattern("HHmmss"))
                    LocalDateTime.of(date, t).atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
                } else {
                    date
                }
            }.getOrNull()
        }
    }
}
