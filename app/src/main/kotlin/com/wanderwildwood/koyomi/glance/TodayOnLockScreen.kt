package com.wanderwildwood.koyomi.glance

import android.content.Context
import android.text.format.DateFormat
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Settings
import com.wanderwildwood.koyomi.ui.Dates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Today's events for the lock screen: those that run all day first, then the rest that have
 * not yet finished, each with its time. When today has nothing left, nothing - or, if asked
 * for in Settings, the next event on a later day, with its day.
 */
class TodayOnLockScreen : GlanceProvider() {

    override fun enabled(context: Context): Boolean = Settings(context).lockScreen

    override fun lines(context: Context): List<Line> {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val all = CalendarStore(context).occurrences(today, today)
        val allDay = all.filter { it.allDay }.sortedBy { it.title.lowercase() }
        val timed = all.filter { !it.allDay && it.finish.isAfter(now) }.sortedBy { it.start }
        // Follows the phone's 12/24-hour setting with AM and PM, as Glance's own Today does since
        // its 0.1.27: a bare "10:15" on a 12-hour phone could be morning or night.
        val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a")
        val lines = allDay.map { Line(text = it.title, lead = context.getString(R.string.lock_all_day)) } +
            timed.map {
                // An event under way since before today says so rather than giving yesterday's time.
                val lead = if (it.start.toLocalDate().isBefore(today)) context.getString(R.string.lock_until, it.finish.format(time))
                else it.start.format(time)
                Line(text = it.title, lead = lead)
            }
        if (lines.isEmpty()) return next(context, today)
        return lines.take(MAX).mapIndexed { i, l -> if (i == 0) l.copy(heading = context.getString(R.string.lock_today)) else l }
    }

    /** The first event after today, within [AHEAD] days, as one line; empty when switched off. */
    private fun next(context: Context, today: LocalDate): List<Line> {
        if (!Settings(context).lockScreenNext) return emptyList()
        val first = CalendarStore(context).occurrences(today.plusDays(1), today.plusDays(AHEAD))
            .filter { it.firstDay.isAfter(today) }
            .minWithOrNull(compareBy({ it.firstDay }, { !it.allDay }, { it.start }))
            ?: return emptyList()
        // Within the week the day's name is enough; further off, the date.
        val day = if (first.firstDay.isBefore(today.plusDays(7))) DateTimeFormatter.ofPattern("EEE")
        else DateTimeFormatter.ofPattern("MMM d")
        // The day takes the narrow left column; the time goes with the title, where there is
        // room for AM and PM on the 12-hour clock.
        val text = if (first.allDay) first.title
        else context.getString(R.string.lock_next_timed, Dates.time(context, first.start.toLocalTime()), first.title)
        return listOf(Line(text = text, lead = first.firstDay.format(day), heading = context.getString(R.string.lock_next)))
    }

    private companion object {
        // How far ahead the next event is looked for.
        const val AHEAD = 30L
        // Glance trims to the room it has; more than this would never fit.
        const val MAX = 6
    }
}
