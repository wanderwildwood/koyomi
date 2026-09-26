package com.wanderwildwood.koyomi.glance

import android.content.Context
import android.text.format.DateFormat
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Today's events for the lock screen: those that run all day first, then the rest that have
 * not yet finished, each with its time. Nothing when today has nothing left.
 */
class TodayOnLockScreen : GlanceProvider() {

    override fun enabled(context: Context): Boolean = Settings(context).lockScreen

    override fun lines(context: Context): List<Line> {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val all = CalendarStore(context).occurrences(today, today)
        val allDay = all.filter { it.allDay }.sortedBy { it.title.lowercase() }
        val timed = all.filter { !it.allDay && it.finish.isAfter(now) }.sortedBy { it.start }
        val time = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm")
        val lines = allDay.map { Line(text = it.title, lead = context.getString(R.string.lock_all_day)) } +
            timed.map {
                // An event under way since before today says so rather than giving yesterday's time.
                val lead = if (it.start.toLocalDate().isBefore(today)) context.getString(R.string.lock_until, it.finish.format(time))
                else it.start.format(time)
                Line(text = it.title, lead = lead)
            }
        if (lines.isEmpty()) return emptyList()
        return lines.take(MAX).mapIndexed { i, l -> if (i == 0) l.copy(heading = context.getString(R.string.lock_today)) else l }
    }

    private companion object {
        // Glance trims to the room it has; more than this would never fit.
        const val MAX = 6
    }
}
