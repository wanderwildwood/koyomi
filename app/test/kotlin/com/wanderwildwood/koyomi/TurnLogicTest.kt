package com.wanderwildwood.koyomi

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnLogicTest {
    private val up = TurnLogic.PORTRAIT
    private val left = TurnLogic.LANDSCAPE
    private val right = TurnLogic.REVERSE_LANDSCAPE

    /** Feeds a pose for [seconds] at 20 readings a second, with the jitter a hand makes; returns the mode at the end. */
    private fun TurnLogic.hold(sx: Float, sy: Float, seconds: Float, jitter: Float = 0.3f, from: Long = clock): Int {
        var mode = this.mode
        val steps = (seconds * 20).toInt()
        repeat(steps) {
            clock += 50
            mode = feed(clock, sx + noise() * jitter, sy + noise() * jitter)
        }
        return mode
    }

    private var clock = 0L
    private var seed = 12345L
    private fun noise(): Float {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return (seed % 2001) / 1000f - 1f
    }

    @Test fun uprightStaysUpright() {
        val t = TurnLogic()
        assertEquals(up, t.hold(0f, 9.7f, 5f))
    }

    @Test fun strongSidewaysLeftTurns() {
        val t = TurnLogic()
        assertEquals(left, t.hold(9.6f, -0.3f, 3f))
    }

    @Test fun strongSidewaysRightTurns() {
        val t = TurnLogic()
        assertEquals(right, t.hold(-9.6f, 0.3f, 3f))
    }

    /** Held on its side but leaned far back: only about 2 m/s^2 of gravity lies along the screen. */
    @Test fun sidewaysLeanedBackStillTurns() {
        val t = TurnLogic()
        assertEquals(left, t.hold(2.0f, 0.1f, 3f, jitter = 0.5f))
        assertEquals(right, TurnLogic().hold(-2.0f, 0.1f, 3f, jitter = 0.5f))
    }

    @Test fun jitterOnASidewaysHoldDoesNotFlip() {
        val t = TurnLogic()
        t.hold(2.0f, 0.1f, 2f, jitter = 0.7f)
        repeat(6) { assertEquals(left, t.hold(1.6f + it % 2 * 0.8f, 0.2f, 1f, jitter = 0.7f)) }
    }

    @Test fun backToUprightReturnsToPortrait() {
        val t = TurnLogic()
        t.hold(2.0f, 0.1f, 3f)
        assertEquals(up, t.hold(0f, 9.7f, 2f))
    }

    @Test fun upsideDownIsShownUpright() {
        val t = TurnLogic()
        assertEquals(up, t.hold(0f, -9.7f, 3f))
    }

    @Test fun flatKeepsTheScreenForAWhileThenGoesUpright() {
        val t = TurnLogic()
        t.hold(9.6f, -0.3f, 3f)
        assertEquals(left, t.hold(0.15f, 0.1f, 1.5f, jitter = 0.05f))
        assertEquals(up, t.hold(0.15f, 0.1f, 3.5f, jitter = 0.05f))
    }

    @Test fun passingThroughFlatOnTheWayDoesNotTurn() {
        val t = TurnLogic()
        t.hold(9.6f, -0.3f, 3f)
        t.hold(0.2f, 0.1f, 0.6f, jitter = 0.05f)
        assertEquals(left, t.hold(9.6f, -0.3f, 1f))
    }

    @Test fun aSlowTiltThroughTheGapChangesNothingUntilItArrives() {
        val t = TurnLogic()
        assertEquals(up, t.hold(5.5f, 7f, 2f))   // about 38 degrees: not sideways yet
        assertEquals(left, t.hold(9.2f, 2.5f, 3f)) // about 75 degrees: sideways
    }

    @Test fun anglesMatchTheSystemListener() {
        assertEquals(0, TurnLogic.angle(0f, 9.7f))
        assertEquals(270, TurnLogic.angle(9.6f, -0.3f))
        assertEquals(90, TurnLogic.angle(-9.6f, 0f))
        assertEquals(180, TurnLogic.angle(0f, -9.7f))
    }
}
