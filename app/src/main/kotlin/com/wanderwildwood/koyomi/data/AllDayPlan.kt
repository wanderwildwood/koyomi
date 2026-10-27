package com.wanderwildwood.koyomi.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** When a calendar's all-day events remind: never, the evening before, the morning of, or both. */
enum class AllDayMode { OFF, EVENING, MORNING, BOTH }

enum class AllDayKind(val code: Int) {
    /** "Tomorrow: ..." */
    EVENING(1),

    /** "Today: ..." */
    MORNING(2),
    ;

    companion object {
        fun of(code: Int): AllDayKind? = entries.firstOrNull { it.code == code }
    }
}

/** One calendar's choice. Times are minutes after midnight, in the phone's zone. */
data class AllDaySetting(
    val mode: AllDayMode = AllDayMode.OFF,
    val eveningMinute: Int = DEFAULT_EVENING,
    val morningMinute: Int = DEFAULT_MORNING,
) {
    val evening: LocalTime get() = LocalTime.of(eveningMinute / 60, eveningMinute % 60)
    val morning: LocalTime get() = LocalTime.of(morningMinute / 60, morningMinute % 60)

    fun encode(): String = "${mode.name},$eveningMinute,$morningMinute"

    companion object {
        const val DEFAULT_EVENING = 18 * 60
        const val DEFAULT_MORNING = 7 * 60

        fun decode(text: String?): AllDaySetting {
            val parts = text?.split(',') ?: return AllDaySetting()
            val mode = runCatching { AllDayMode.valueOf(parts[0]) }.getOrDefault(AllDayMode.OFF)
            fun minute(i: Int, default: Int) = parts.getOrNull(i)?.toIntOrNull()?.takeIf { it in 0 until 1440 } ?: default
            return AllDaySetting(mode, minute(1, DEFAULT_EVENING), minute(2, DEFAULT_MORNING))
        }
    }
}

/** One reminder to ring: [atMs] is when, [validUntilMs] is when it stops being true. */
data class AllDayFire(val kind: AllDayKind, val atMs: Long, val validUntilMs: Long)

/**
 * The arithmetic of reminders for all-day events, kept apart from the alarms so it can be
 * tested. An all-day event is a date, not an instant: [firstDay] is the date the provider's
 * UTC midnight stands for, and everything here is worked out on the phone's own clock, so
 * "the evening before at 6 PM" is 6 PM wherever the phone is, including across a clock change.
 */
object AllDayPlan {

    /** The reminders [setting] asks for an event that covers [firstDay] to [lastDay]. */
    fun fires(firstDay: LocalDate, lastDay: LocalDate, setting: AllDaySetting, zone: ZoneId): List<AllDayFire> {
        val out = mutableListOf<AllDayFire>()
        if (setting.mode == AllDayMode.EVENING || setting.mode == AllDayMode.BOTH) {
            out += AllDayFire(
                AllDayKind.EVENING,
                firstDay.minusDays(1).atTime(setting.evening).atZone(zone).toInstant().toEpochMilli(),
                // "Tomorrow" stops being true when the day arrives.
                firstDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
        if (setting.mode == AllDayMode.MORNING || setting.mode == AllDayMode.BOTH) {
            out += AllDayFire(
                AllDayKind.MORNING,
                firstDay.atTime(setting.morning).atZone(zone).toInstant().toEpochMilli(),
                // "Today" holds to the end of the event's last day.
                lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
        return out
    }

    /**
     * Whether the event has a reminder of its own that rings on the same day as [fire] does, so
     * this one would only say it twice. [reminderMinutes] are the provider's: minutes before the
     * midnight that starts the event's first day, as the provider times an all-day alarm.
     */
    fun coveredByOwn(fire: AllDayFire, firstDay: LocalDate, reminderMinutes: List<Int>, zone: ZoneId): Boolean {
        val midnight = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val fireDay = java.time.Instant.ofEpochMilli(fire.atMs).atZone(zone).toLocalDate()
        return reminderMinutes.any { m ->
            java.time.Instant.ofEpochMilli(midnight - m * 60_000L).atZone(zone).toLocalDate() == fireDay
        }
    }

    /** What makes one reminder the same reminder wherever it is worked out again. */
    fun key(eventId: Long, begin: Long, kind: AllDayKind): String = "$eventId:$begin:${kind.code}"
}
