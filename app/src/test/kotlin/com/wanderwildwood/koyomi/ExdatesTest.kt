package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Exdates
import com.wanderwildwood.koyomi.data.SeriesShift
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

class ExdatesTest {
    private val zone = ZoneId.of("America/New_York")
    private val halfHourEarlier = SeriesShift(0, Duration.ofMinutes(-30))

    @Test fun utcDateTimesMove() {
        assertEquals(
            "20260929T223000Z,20261006T223000Z",
            Exdates.shift("20260929T230000Z,20261006T230000Z", halfHourEarlier, zone),
        )
    }

    @Test fun zonedLinesKeepTheirZone() {
        assertEquals(
            "America/New_York;20260929T183000",
            Exdates.shift("America/New_York;20260929T190000", halfHourEarlier, zone),
        )
    }

    @Test fun datesMoveByWholeDays() {
        assertEquals("20260930", Exdates.shift("20260929", SeriesShift(1, Duration.ZERO), zone))
    }

    @Test fun utcTimesMoveOnTheSeriesClockAcrossAChangeOfTheClocks() {
        // 09:00 in New York on Tuesday 27 October (13:00 UTC), a week on: 3 November, when the
        // clocks have gone back, 09:00 is 14:00 UTC.
        assertEquals("20261103T140000Z", Exdates.shift("20261027T130000Z", SeriesShift(7, Duration.ZERO), zone))
    }

    @Test fun nothingToMove() {
        assertEquals(null, Exdates.shift(null, halfHourEarlier, zone))
        assertEquals("20260929T230000Z", Exdates.shift("20260929T230000Z", SeriesShift.NONE, zone))
    }
}
