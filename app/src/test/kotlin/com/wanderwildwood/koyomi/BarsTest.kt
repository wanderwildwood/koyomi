package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.ui.layBars
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BarsTest {
    private val sunday = LocalDate.of(2026, 9, 20)

    /** An all-day event from [from] to [to], inclusive. */
    private fun allDay(title: String, from: LocalDate, to: LocalDate) =
        Occurrence(0, 1, title, null, 0, 0, true, false, from.atStartOfDay(), to.plusDays(1).atStartOfDay())

    @Test fun barsAreClippedToTheWeek() {
        val trip = allDay("Trip", sunday.minusDays(2), sunday.plusDays(2))
        val bar = layBars(listOf(trip), sunday).single()
        assertEquals(0, bar.from)
        assertEquals(2, bar.to)
    }

    @Test fun overlappingBarsTakeSeparateLanesAndFreeLanesAreReused() {
        val long = allDay("Long", sunday.plusDays(1), sunday.plusDays(4))
        val inside = allDay("Inside", sunday.plusDays(2), sunday.plusDays(2))
        val after = allDay("After", sunday.plusDays(5), sunday.plusDays(6))
        val bars = layBars(listOf(inside, after, long), sunday).associateBy { it.occurrence.title }
        assertEquals(0, bars.getValue("Long").lane)
        assertEquals(1, bars.getValue("Inside").lane)
        assertEquals(0, bars.getValue("After").lane) // lane 0 is free again after Thursday
    }
}
