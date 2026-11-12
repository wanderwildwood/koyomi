package com.wanderwildwood.koyomi.data

/**
 * A reminder of an all-day event as a person thinks of it: which day, and what time that day.
 * [daysBefore] is 0 for the event's first day, 1 for the day before; a negative number is a
 * day after the first, which only a synced event brings. [minuteOfDay] is 0 to 1439.
 */
data class AllDayReminderAt(val daysBefore: Int, val minuteOfDay: Int)

/**
 * The arithmetic between that and what the calendar store keeps, which is one number: minutes
 * before the midnight that starts the event's first day. So 9:00 on the day is -540, midnight
 * on the day is 0, and 17:00 the day before is 420. Every number has exactly one day and time,
 * and comes back unchanged, so a reminder that arrived by sync is shown as it is and saved as
 * it was.
 */
object AllDayReminder {
    const val DAY = 1440

    /** The day before at 9:00, what a new all-day event has had from the start. */
    const val DEFAULT = 900

    /** The days offered; a reminder that came with another day keeps it, offered beside these. */
    val DAY_CHOICES = listOf(0, 1, 2, 7)

    fun split(minutes: Int): AllDayReminderAt {
        // The day is the one whose midnight is at or before the reminder: rounding up, for
        // negative numbers too, which plain division does not do.
        val daysBefore = -Math.floorDiv(-minutes, DAY)
        return AllDayReminderAt(daysBefore, daysBefore * DAY - minutes)
    }

    fun minutes(daysBefore: Int, minuteOfDay: Int): Int {
        require(minuteOfDay in 0 until DAY) { "minute of day $minuteOfDay" }
        return daysBefore * DAY - minuteOfDay
    }

    fun minutes(at: AllDayReminderAt): Int = minutes(at.daysBefore, at.minuteOfDay)

    /**
     * The reminders of an event in the editor once its all-day switch is moved. A reminder
     * means something different on each side of that switch (minutes before a start, or a time
     * of day before a date), so none is carried across as a number.
     *
     * A new event whose reminders are still the ones it was given takes what a new event of
     * the other kind is given, which may be none. Otherwise an event with no reminder keeps
     * none, and one with any gets the plain one for its new kind.
     *
     * [timedDefault] and [allDayDefault] are the settings, null for none.
     */
    fun switched(current: List<Int>, toAllDay: Boolean, isNew: Boolean, timedDefault: Int?, allDayDefault: Int?): List<Int> {
        val from = listOfNotNull(if (toAllDay) timedDefault else allDayDefault)
        val to = listOfNotNull(if (toAllDay) allDayDefault else timedDefault)
        return when {
            isNew && current == from -> to
            current.isEmpty() -> emptyList()
            to.isNotEmpty() -> to
            toAllDay -> listOf(DEFAULT)
            else -> listOf(TIMED_FALLBACK)
        }
    }

    /** Ten minutes before, for a timed event that must have a reminder when the setting is none. */
    const val TIMED_FALLBACK = 10
}
