package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.alerts.Alerts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTimingTest {
    private val hour = 3_600_000L
    private val begin = 1_800_000_000_000L

    @Test
    fun `a reminder missed for a whole event is dropped`() {
        assertTrue(Alerts.cameTooLate(end = begin + hour, alarmTime = begin - 600_000L, now = begin + 2 * hour))
    }

    @Test
    fun `at start on an event with no length still rings`() {
        assertFalse(Alerts.cameTooLate(end = begin, alarmTime = begin, now = begin + 50))
    }

    @Test
    fun `a snooze that lands after the end still rings`() {
        assertFalse(Alerts.cameTooLate(end = begin + hour, alarmTime = begin + hour + 300_000L, now = begin + hour + 300_050L))
    }

    @Test
    fun `a reminder during the event rings`() {
        assertFalse(Alerts.cameTooLate(end = begin + hour, alarmTime = begin, now = begin + 1_000L))
    }
}
