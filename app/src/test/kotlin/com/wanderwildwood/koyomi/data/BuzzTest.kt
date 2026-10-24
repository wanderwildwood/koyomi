package com.wanderwildwood.koyomi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BuzzTest {
    @Test fun offToBeginWith() = assertFalse(Buzz.DEFAULT)

    @Test fun noTickWhenOff() {
        var ticks = 0
        repeat(5) { Buzz.press(Buzz.DEFAULT) { ticks++ } }
        assertEquals(0, ticks)
    }

    @Test fun oneTickPerPressWhenOn() {
        var ticks = 0
        repeat(5) { Buzz.press(true) { ticks++ } }
        assertEquals(5, ticks)
    }
}
