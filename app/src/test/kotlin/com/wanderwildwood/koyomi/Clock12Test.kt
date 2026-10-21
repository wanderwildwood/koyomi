package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.ui.Clock12
import com.wanderwildwood.koyomi.ui.ClockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Clock12Test {

    @Test
    fun faceHours() {
        assertEquals(12, Clock12.faceHour(0))
        assertEquals(1, Clock12.faceHour(1))
        assertEquals(11, Clock12.faceHour(11))
        assertEquals(12, Clock12.faceHour(12))
        assertEquals(1, Clock12.faceHour(13))
        assertEquals(11, Clock12.faceHour(23))
    }

    @Test
    fun halves() {
        assertFalse(Clock12.isPm(0))
        assertFalse(Clock12.isPm(11))
        assertTrue(Clock12.isPm(12))
        assertTrue(Clock12.isPm(23))
    }

    @Test
    fun twelveIsTheAwkwardHour() {
        assertEquals(0, Clock12.toHour24(12, pm = false))  // 12 AM, midnight
        assertEquals(12, Clock12.toHour24(12, pm = true))  // 12 PM, noon
        assertEquals(1, Clock12.toHour24(1, pm = false))
        assertEquals(13, Clock12.toHour24(1, pm = true))
        assertEquals(23, Clock12.toHour24(11, pm = true))
    }

    @Test
    fun everyHourRoundTrips() {
        for (h in 0..23) {
            assertEquals(h, Clock12.toHour24(Clock12.faceHour(h), Clock12.isPm(h)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun noHourZeroOnTheFace() {
        Clock12.toHour24(0, pm = false)
    }

    @Test
    fun typedHours() {
        assertEquals(18, Clock12.typed(6, pm = true))   // the bug MMD has: 6 with PM showing
        assertEquals(6, Clock12.typed(6, pm = false))
        assertEquals(12, Clock12.typed(12, pm = true))
        assertEquals(0, Clock12.typed(12, pm = false))
        assertEquals(0, Clock12.typed(0, pm = false))
        assertEquals(12, Clock12.typed(0, pm = true))
        assertEquals(18, Clock12.typed(18, pm = false)) // typed on the 24-hour clock
    }

    @Test
    fun switchingHalfKeepsTheFace() {
        assertEquals(21, Clock12.withHalf(9, pm = true))
        assertEquals(9, Clock12.withHalf(21, pm = false))
        assertEquals(12, Clock12.withHalf(0, pm = true))
        assertEquals(0, Clock12.withHalf(12, pm = false))
        assertEquals(9, Clock12.withHalf(9, pm = false))
    }

    @Test
    fun stateOnTheTwelveHourClock() {
        // 12:30 AM opens as 12 AM and is unchanged by OK.
        val s = ClockState(0, 30, twelveHour = true)
        assertEquals(12, s.hour)
        assertFalse(s.isAfternoon)
        assertEquals(0, s.hour24)
        // Typing 6 with PM chosen is six in the evening.
        s.isAfternoon = true
        assertEquals(12, s.hour24)
        s.hour = 6
        assertEquals(18, s.hour24)
        assertEquals(6, s.hour)
        // Back to AM keeps the six.
        s.isAfternoon = false
        assertEquals(6, s.hour24)
        // Noon.
        val noon = ClockState(12, 0, twelveHour = true)
        assertEquals(12, noon.hour)
        assertTrue(noon.isAfternoon)
        noon.hour = 12
        assertEquals(12, noon.hour24)
        // MMD always lays out its 24-hour face, so it never draws a toggle of its own.
        assertTrue(s.is24hour)
    }

    @Test
    fun stateOnTheTwentyFourHourClock() {
        val s = ClockState(0, 30, twelveHour = false)
        assertEquals(0, s.hour)
        s.hour = 18
        assertEquals(18, s.hour24)
        s.hour = 0
        assertEquals(0, s.hour24)
    }
}
