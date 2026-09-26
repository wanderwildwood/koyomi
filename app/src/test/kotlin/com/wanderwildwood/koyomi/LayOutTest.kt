package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.ui.layOut
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class LayOutTest {
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, 25, h, m)
    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime) =
        Occurrence(id, 1, "e$id", null, 0, 0, false, false, s, e)

    @Test fun apartEventsTakeTheWholeWidth() {
        val out = layOut(listOf(ev(1, at(9), at(10)), ev(2, at(10), at(11))))
        assertEquals(listOf(1, 1), out.map { it.third })
    }

    @Test fun overlapsShareColumnsAndReuseTheFreeOne() {
        val out = layOut(listOf(ev(1, at(9), at(12)), ev(2, at(9, 30), at(10)), ev(3, at(10, 30), at(11))))
        val byId = out.associateBy { it.first.eventId }
        assertEquals(2, byId.getValue(1).third)
        assertEquals(0, byId.getValue(1).second)
        assertEquals(1, byId.getValue(2).second)
        assertEquals(1, byId.getValue(3).second) // the column event 2 left free
    }
}
