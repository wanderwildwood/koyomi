package com.wanderwildwood.koyomi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpareCalendarTest {
    private fun cal(id: Long, account: String, type: String = "LOCAL", writable: Boolean = true) =
        CalendarInfo(id, account, account, type, visible = true, writable = writable, isPrimary = false)

    private val pcSync = cal(1, "PC Sync")
    private val kompakt = cal(2, "Kompakt")
    private val synced = cal(3, "ada@cloud.example.org", type = "bitfire.at.davdroid")
    private val link = cal(4, "link-abc", writable = false)

    @Test
    fun emptyPcSyncGoesBesideAnotherPhoneCalendar() {
        assertEquals(listOf(kompakt, synced), SpareCalendar.withoutSpare(listOf(pcSync, kompakt, synced)) { true })
    }

    @Test
    fun pcSyncWithAnEventStays() {
        assertEquals(listOf(pcSync, kompakt), SpareCalendar.withoutSpare(listOf(pcSync, kompakt)) { false })
    }

    @Test
    fun pcSyncAloneStays() {
        // A synced calendar or a followed link is not a phone-only place for an event.
        assertEquals(listOf(pcSync, synced, link), SpareCalendar.withoutSpare(listOf(pcSync, synced, link)) { true })
    }
}
