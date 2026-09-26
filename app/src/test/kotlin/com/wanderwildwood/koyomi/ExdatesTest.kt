package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Exdates
import org.junit.Assert.assertEquals
import org.junit.Test

class ExdatesTest {
    private val halfHourEarlier = -30L * 60 * 1000

    @Test fun utcDateTimesMove() {
        assertEquals(
            "20260929T223000Z,20261006T223000Z",
            Exdates.shift("20260929T230000Z,20261006T230000Z", halfHourEarlier),
        )
    }

    @Test fun zonedLinesKeepTheirZone() {
        assertEquals(
            "America/New_York;20260929T183000",
            Exdates.shift("America/New_York;20260929T190000", halfHourEarlier),
        )
    }

    @Test fun datesMoveByWholeDays() {
        assertEquals("20260930", Exdates.shift("20260929", 24L * 60 * 60 * 1000))
    }

    @Test fun nothingToMove() {
        assertEquals(null, Exdates.shift(null, halfHourEarlier))
        assertEquals("20260929T230000Z", Exdates.shift("20260929T230000Z", 0))
    }
}
