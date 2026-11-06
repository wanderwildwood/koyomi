package com.wanderwildwood.davsync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test
    fun theThreeChoicesAreKept() {
        for (i in SyncPolicy.INTERVALS) assertEquals(i, SyncPolicy.interval(i))
    }

    @Test
    fun missingOrOddIntervalIsHourly() {
        assertEquals(3600L, SyncPolicy.interval(null))
        assertEquals(3600L, SyncPolicy.interval(-1))
        assertEquals(3600L, SyncPolicy.interval(60))
    }

    @Test
    fun wifiOnlyHoldsBackAScheduledSyncOnMobileData() {
        assertFalse(SyncPolicy.mayRun(manual = false, wifiOnly = true, onWifi = false))
    }

    @Test
    fun aPullOrSyncNowRunsOnMobileDataEvenWifiOnly() {
        assertTrue(SyncPolicy.mayRun(manual = true, wifiOnly = true, onWifi = false))
    }

    @Test
    fun wifiOrNoRuleRuns() {
        assertTrue(SyncPolicy.mayRun(manual = false, wifiOnly = true, onWifi = true))
        assertTrue(SyncPolicy.mayRun(manual = false, wifiOnly = false, onWifi = false))
    }
}
