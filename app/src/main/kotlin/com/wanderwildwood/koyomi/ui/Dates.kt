package com.wanderwildwood.koyomi.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Every date and time on screen, in the phone's own language and order. The patterns come
 * from Android's best-pattern lookup, so "Friday 25 September" in Britain is "Friday,
 * September 25" in America without a setting to choose between them.
 */
object Dates {
    private fun pattern(skeleton: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    private fun format(skeleton: String, t: TemporalAccessor) = pattern(skeleton).format(t)

    fun month(m: YearMonth): String = format("LLLLyyyy", m.atDay(1))
    fun long(d: LocalDate): String = format("EEEEdMMMMyyyy", d)
    fun dayTitle(d: LocalDate): String = format("EEEEdMMMM", d)
    fun medium(d: LocalDate): String = format("EEEdMMM", d)
    fun short(d: LocalDate): String = format("dMMM", d)
    fun weekday(d: LocalDate): String = format("EEE", d)
    fun weekdayNarrow(d: LocalDate): String = format("EEEEE", d)

    fun time(context: Context, t: LocalTime): String =
        format(if (DateFormat.is24HourFormat(context)) "Hm" else "hma", t)

    /** "9 am" or "09" — the label beside an hour line. */
    fun hour(context: Context, h: Int): String =
        if (DateFormat.is24HourFormat(context)) {
            "%02d".format(h)
        } else {
            // "12pm", not "12 PM": the label has 44dp, and the space is what made it wrap.
            format("ha", LocalTime.of(h, 0)).replace(" ", "").replace("\u202f", "").lowercase()
        }

    /** When an occurrence happens, in one line: "Fri 25 Sep, 09:00 – 10:30". */
    fun span(context: Context, start: LocalDateTime, finish: LocalDateTime, allDay: Boolean): String {
        val first = start.toLocalDate()
        if (allDay) {
            val last = finish.toLocalDate().minusDays(1).coerceAtLeast(first)
            return if (last == first) medium(first) else "${medium(first)} – ${medium(last)}"
        }
        val s = "${medium(first)}, ${time(context, start.toLocalTime())}"
        return if (finish.toLocalDate() == first) {
            "$s – ${time(context, finish.toLocalTime())}"
        } else {
            "$s – ${medium(finish.toLocalDate())}, ${time(context, finish.toLocalTime())}"
        }
    }
}
