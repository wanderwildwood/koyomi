package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.AllDayReminder
import com.wanderwildwood.koyomi.data.AllDayReminderAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllDayReminderTest {
    private fun at(days: Int, hour: Int, minute: Int = 0) = AllDayReminderAt(days, hour * 60 + minute)

    @Test fun nineOnTheDayIsMinusFiveForty() {
        assertEquals(-540, AllDayReminder.minutes(0, 9 * 60))
        assertEquals(at(0, 9), AllDayReminder.split(-540))
    }

    @Test fun midnightOnTheDayIsNought() {
        assertEquals(0, AllDayReminder.minutes(0, 0))
        assertEquals(at(0, 0), AllDayReminder.split(0))
    }

    @Test fun theLastMinuteOfTheDayIsStillTheDay() {
        assertEquals(-1439, AllDayReminder.minutes(0, 23 * 60 + 59))
        assertEquals(at(0, 23, 59), AllDayReminder.split(-1439))
    }

    @Test fun fiveInTheAfternoonTheDayBefore() {
        assertEquals(420, AllDayReminder.minutes(1, 17 * 60))
        assertEquals(at(1, 17), AllDayReminder.split(420))
    }

    @Test fun theDefaultIsNineTheDayBefore() {
        assertEquals(at(1, 9), AllDayReminder.split(AllDayReminder.DEFAULT))
    }

    @Test fun aWeekBeforeAtNine() {
        assertEquals(9540, AllDayReminder.minutes(7, 9 * 60))
        assertEquals(at(7, 9), AllDayReminder.split(9540))
    }

    @Test fun whatSyncBringsIsReadAsADayAndATime() {
        // Fifteen minutes before midnight is 23:45 the day before.
        assertEquals(at(1, 23, 45), AllDayReminder.split(15))
        // A whole day before is the midnight that starts the day before, not the end of it.
        assertEquals(at(1, 0), AllDayReminder.split(1440))
        assertEquals(at(2, 23, 59), AllDayReminder.split(1441))
        assertEquals(at(1, 0, 1), AllDayReminder.split(1439))
        assertEquals(at(7, 0), AllDayReminder.split(10080))
        assertEquals(at(3, 18), AllDayReminder.split(3 * 1440 - 18 * 60))
    }

    @Test fun aReminderAfterTheFirstDayIsADayAfter() {
        // 01:00 on the second day of the event.
        assertEquals(at(-1, 1), AllDayReminder.split(-1500))
        assertEquals(at(-1, 0), AllDayReminder.split(-1440))
        assertEquals(-1500, AllDayReminder.minutes(-1, 60))
    }

    @Test fun everyNumberComesBackAsItWas() {
        for (m in -3 * 1440..15 * 1440) {
            val at = AllDayReminder.split(m)
            assertTrue("$m: ${at.minuteOfDay}", at.minuteOfDay in 0 until 1440)
            assertEquals(m, AllDayReminder.minutes(at))
        }
    }

    @Test fun everyDayAndTimeComesBackAsItWas() {
        for (days in -2..8) for (minute in 0 until 1440) {
            assertEquals(AllDayReminderAt(days, minute), AllDayReminder.split(AllDayReminder.minutes(days, minute)))
        }
    }

    // --- the all-day switch in the editor ---------------------------------------------------

    private fun switched(current: List<Int>, toAllDay: Boolean, isNew: Boolean = true, timed: Int? = 10, allDay: Int? = 900) =
        AllDayReminder.switched(current, toAllDay, isNew, timed, allDay)

    @Test fun aNewEventTakesTheOtherKindsOwnReminder() {
        assertEquals(listOf(900), switched(listOf(10), toAllDay = true))
        assertEquals(listOf(10), switched(listOf(900), toAllDay = false))
        assertEquals(listOf(-420), switched(listOf(10), toAllDay = true, allDay = -420))
    }

    @Test fun aNewEventFollowsASettingOfNoneOnEitherSide() {
        // No reminder for timed events, one for all-day events: the switch brings it.
        assertEquals(listOf(900), switched(emptyList(), toAllDay = true, timed = null))
        assertEquals(emptyList<Int>(), switched(listOf(900), toAllDay = false, timed = null))
        // And the other way about.
        assertEquals(emptyList<Int>(), switched(listOf(10), toAllDay = true, allDay = null))
        assertEquals(listOf(10), switched(emptyList(), toAllDay = false, allDay = null))
    }

    @Test fun remindersTakenOffStayOff() {
        assertEquals(emptyList<Int>(), switched(emptyList(), toAllDay = true))
        assertEquals(emptyList<Int>(), switched(emptyList(), toAllDay = false))
        assertEquals(emptyList<Int>(), switched(emptyList(), toAllDay = true, isNew = false, timed = null))
    }

    @Test fun remindersThatWereChangedBecomeThePlainOne() {
        assertEquals(listOf(900), switched(listOf(30, 60), toAllDay = true))
        assertEquals(listOf(10), switched(listOf(-540, 420), toAllDay = false))
        assertEquals(listOf(360), switched(listOf(15), toAllDay = true, isNew = false, allDay = 360))
        assertEquals(listOf(30), switched(listOf(900), toAllDay = false, isNew = false, timed = 30))
    }

    @Test fun anEventWithAReminderKeepsOneWhenTheSettingIsNone() {
        assertEquals(listOf(900), switched(listOf(30), toAllDay = true, allDay = null))
        assertEquals(listOf(10), switched(listOf(-540), toAllDay = false, timed = null))
        assertEquals(listOf(900), switched(listOf(10), toAllDay = true, isNew = false, allDay = null))
    }
}
