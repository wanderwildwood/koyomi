package com.wanderwildwood.koyomi.repeat

import android.content.res.Resources
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.ui.Dates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * A rule in words: "Every 10 days, until 1 December 2026".
 *
 * After Etar's `EventRecurrenceFormatter`, which says "Monthly" for a rule that runs every
 * third month; this one says what the rule does for every shape the editor can make.
 */
object RepeatText {

    fun of(res: Resources, rrule: String?, start: LocalDate, allDay: Boolean): String {
        if (rrule == null) return res.getString(R.string.repeat_never)
        val rule = RepeatRule.from(rrule, start, allDay) ?: return res.getString(R.string.repeat_custom)
        return of(res, rule, start)
    }

    fun of(res: Resources, rule: RepeatRule, start: LocalDate): String {
        val n = rule.interval.coerceAtLeast(1)
        val base = when (rule.freq) {
            RepeatRule.Freq.DAILY -> res.getQuantityString(R.plurals.repeat_daily, n, n)
            RepeatRule.Freq.WEEKLY -> {
                val days = rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }
                if (n == 1 && days == WEEKDAYS) {
                    res.getString(R.string.repeat_weekdays)
                } else {
                    res.getQuantityString(R.plurals.repeat_weekly, n, n, dayList(res, days))
                }
            }
            RepeatRule.Freq.MONTHLY -> {
                val on = if (rule.monthly == RepeatRule.Monthly.BY_DATE) {
                    res.getString(R.string.repeat_on_day, start.dayOfMonth)
                } else {
                    nthWeekday(res, rule.nth, start.dayOfWeek)
                }
                res.getQuantityString(R.plurals.repeat_monthly, n, n, on)
            }
            RepeatRule.Freq.YEARLY -> res.getQuantityString(R.plurals.repeat_yearly, n, n)
        }
        return when (rule.end) {
            RepeatRule.End.NEVER -> base
            RepeatRule.End.DATE -> res.getString(R.string.repeat_until, base, Dates.long(rule.until ?: start))
            RepeatRule.End.COUNT -> res.getQuantityString(R.plurals.repeat_times, rule.count, base, rule.count)
        }
    }

    /** "the fourth Friday", "the last Friday". */
    fun nthWeekday(res: Resources, nth: Int, day: DayOfWeek): String {
        val name = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val ordinal = res.getStringArray(R.array.repeat_ordinals)
        val which = if (nth == -1) ordinal.last() else ordinal[(nth - 1).coerceIn(0, 4)]
        return res.getString(R.string.repeat_on_nth, which, name)
    }

    private fun dayList(res: Resources, days: Set<DayOfWeek>): String {
        val sorted = days.sortedBy { it.value }
        val style = if (sorted.size > 2) TextStyle.SHORT else TextStyle.FULL
        val names = sorted.map { it.getDisplayName(style, Locale.getDefault()) }
        return when (names.size) {
            1 -> names[0]
            else -> res.getString(R.string.repeat_and, names.dropLast(1).joinToString(", "), names.last())
        }
    }

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
}
