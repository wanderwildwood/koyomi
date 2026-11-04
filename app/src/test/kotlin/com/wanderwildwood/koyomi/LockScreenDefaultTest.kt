package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.LOCK_SCREEN_OFF_SINCE
import com.wanderwildwood.koyomi.data.lockScreenDefault
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An untouched lock-screen switch: on for installs from before 0.1.41, off for newer ones. */
class LockScreenDefaultTest {
    @Test fun cutoffIsTheReleaseOf0141() =
        assertEquals(Instant.parse("2026-10-30T14:35:21Z").toEpochMilli(), LOCK_SCREEN_OFF_SINCE)

    @Test fun olderInstallKeepsEventsOnTheLockScreen() =
        assertTrue(lockScreenDefault(Instant.parse("2026-10-21T09:00:00Z").toEpochMilli()))

    @Test fun newInstallStartsOff() =
        assertFalse(lockScreenDefault(Instant.parse("2026-11-05T09:00:00Z").toEpochMilli()))

    @Test fun unknownInstallTimeStartsOff() = assertFalse(lockScreenDefault(Long.MAX_VALUE))
}
