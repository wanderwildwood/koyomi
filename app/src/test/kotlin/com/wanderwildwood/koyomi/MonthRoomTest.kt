package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.ui.monthRoom
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthRoomTest {
    // A cell holds its number row and then whole 13 dp lines.
    @Test fun wholeLinesUnderTheNumber() {
        assertEquals(0, monthRoom(16f, 17f, 13f))
        assertEquals(1, monthRoom(30f, 17f, 13f))
        assertEquals(2, monthRoom(43f, 17f, 13f))
        assertEquals(4, monthRoom(70f, 17f, 13f))
    }

    // The screen on its side is 366 dp tall on a Kompakt, less a 24 dp status bar and an 18 dp
    // gesture bar. One 48 dp header line and a short weekday row leave five weeks two lines
    // each; the old top bar, tab row and foot (about 180 dp) left them none.
    @Test fun fiveWeeksOnTheSideHoldTwoLines() {
        val after = 366f - 42f - 48f - 18f
        assertEquals(2, monthRoom(after / 5, 17f, 13f))
        assertEquals(2, monthRoom(after / 6, 17f, 13f))
        val before = 366f - 42f - 180f - 20f
        assertEquals(0, monthRoom(before / 5, 17f, 13f))
    }
}
