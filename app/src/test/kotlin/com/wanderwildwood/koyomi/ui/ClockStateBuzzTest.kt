package com.wanderwildwood.koyomi.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockStateBuzzTest {
    @Test fun eachTypedDigitIsToldToTheBuzz() {
        val state = ClockState(9, 30, twelveHour = true)
        var keys = 0
        state.onKey = { keys++ }
        state.hour = 7
        state.minute = 45
        assertEquals(2, keys)
        assertEquals(45, state.minute)
    }

    @Test fun choosingTheHalfOfDayIsNotADigit() {
        val state = ClockState(9, 30, twelveHour = true)
        var keys = 0
        state.onKey = { keys++ }
        state.isAfternoon = true
        assertEquals(0, keys)
    }
}
