package com.wanderwildwood.koyomi.data

import android.content.Context
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/** Which of the four views the app shows. */
enum class View { MONTH, WEEK, DAY, AGENDA }

/** The handful of choices this app remembers, in plain SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var openOn: View
        get() = runCatching { View.valueOf(prefs.getString(OPEN_ON, null)!!) }.getOrDefault(View.MONTH)
        set(v) = prefs.edit().putString(OPEN_ON, v.name).apply()

    /** Unset means the phone's own first day of the week. */
    var weekStart: DayOfWeek
        get() = prefs.getInt(WEEK_START, 0).takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
            ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek
        set(v) = prefs.edit().putInt(WEEK_START, v.value).apply()

    var weekNumbers: Boolean
        get() = prefs.getBoolean(WEEK_NUMBERS, false)
        set(v) = prefs.edit().putBoolean(WEEK_NUMBERS, v).apply()

    var defaultCalendar: Long
        get() = prefs.getLong(DEFAULT_CALENDAR, -1)
        set(v) = prefs.edit().putLong(DEFAULT_CALENDAR, v).apply()

    /** Minutes before a new event; -1 for none. */
    var defaultReminder: Int
        get() = prefs.getInt(DEFAULT_REMINDER, 10)
        set(v) = prefs.edit().putInt(DEFAULT_REMINDER, v).apply()

    /** Whether a reminder lights the screen with the event, rather than only notifying. */
    var wakeScreen: Boolean
        get() = prefs.getBoolean(WAKE_SCREEN, true)
        set(v) = prefs.edit().putBoolean(WAKE_SCREEN, v).apply()

    /** Whether today's events are handed to Glance for the lock screen. */
    var lockScreen: Boolean
        get() = prefs.getBoolean(LOCK_SCREEN, true)
        set(v) = prefs.edit().putBoolean(LOCK_SCREEN, v).apply()

    /** Said to be switched on in DuraSpeed's list, which no app can read; see DuraSpeed. */
    var duraSpeedAllowed: Boolean
        get() = prefs.getBoolean(DURASPEED_ALLOWED, false)
        set(v) = prefs.edit().putBoolean(DURASPEED_ALLOWED, v).apply()

    /** The newest system stop already looked at. */
    var duraSpeedStopSeen: Long
        get() = prefs.getLong(DURASPEED_STOP_SEEN, 0)
        set(v) = prefs.edit().putLong(DURASPEED_STOP_SEEN, v).apply()

    private companion object {
        const val DURASPEED_ALLOWED = "duraspeed_allowed"
        const val DURASPEED_STOP_SEEN = "duraspeed_stop_seen"
        const val LOCK_SCREEN = "lock_screen"
        const val OPEN_ON = "open_on"
        const val WEEK_START = "week_start"
        const val WEEK_NUMBERS = "week_numbers"
        const val DEFAULT_CALENDAR = "default_calendar"
        const val DEFAULT_REMINDER = "default_reminder"
        const val WAKE_SCREEN = "wake_screen"
    }
}
