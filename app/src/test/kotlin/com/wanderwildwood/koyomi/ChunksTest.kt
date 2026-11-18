package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.inChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunksTest {
    private class TooLarge : RuntimeException()

    @Test fun everythingGoesInOrderInChunks() {
        val seen = mutableListOf<List<Int>>()
        assertTrue(inChunks((1..450).toList(), 200, { it is TooLarge }) { seen += it.toList() })
        assertEquals(listOf(200, 200, 50), seen.map { it.size })
        assertEquals((1..450).toList(), seen.flatten())
    }

    @Test fun aChunkTooLargeIsHalvedUntilItGoes() {
        val seen = mutableListOf<Int>()
        // Anything over 60 items is too much for one call.
        assertTrue(inChunks((1..300).toList(), 200, { it is TooLarge }) { if (it.size > 60) throw TooLarge() else seen += it })
        assertEquals((1..300).toList(), seen)
    }

    @Test fun anotherFailureEndsIt() {
        var calls = 0
        assertFalse(inChunks((1..10).toList(), 4, { it is TooLarge }) { calls++; if (calls == 2) throw IllegalStateException() })
        assertEquals(2, calls)
    }

    @Test fun oneItemTooLargeOnItsOwnEndsIt() {
        assertFalse(inChunks(listOf(1, 2, 3), 2, { it is TooLarge }) { if (2 in it) throw TooLarge() })
    }

    @Test fun nothingIsAllIn() {
        assertTrue(inChunks(emptyList<Int>(), 200, { true }) { throw TooLarge() })
    }
}
