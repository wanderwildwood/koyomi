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

    // The screen on its side is 366 dp tall on a Kompakt, less an 18 dp gesture bar; the status
    // bar is hidden there. One 48 dp header line and a short weekday row leave five weeks three
    // lines each and six weeks two; with the status bar it was two and one, and with the old top
    // bar, tab row and foot (about 180 dp) none.
    @Test fun fiveWeeksOnTheSideHoldThreeLines() {
        val hidden = 366f - 18f - 48f - 15f
        // The week's dashed rule takes 1 dp of each row; the day number row is 15 dp there.
        assertEquals(3, monthRoom(hidden / 5 - 1f, 15f, 13f))
        assertEquals(2, monthRoom(hidden / 6 - 1f, 15f, 13f))
        val shown = hidden - 24f
        assertEquals(2, monthRoom(shown / 5 - 1f, 17f, 13f))
        val before = 366f - 42f - 180f - 20f
        assertEquals(0, monthRoom(before / 5, 17f, 13f))
    }
}
