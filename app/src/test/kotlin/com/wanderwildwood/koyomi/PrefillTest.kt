package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Draft
import com.wanderwildwood.koyomi.data.Prefill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class PrefillTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2026, 10, 5, 14, 20)
    private val base = Draft(calendarId = 3, reminders = listOf(10))

    private fun ms(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun timedFromExtras() {
        val d = Prefill(
            title = " Test supper ", location = "Kitchen", description = "Bring bread",
            begin = ms("2026-10-09T18:00"), end = ms("2026-10-09T19:30"),
        ).draft(base, zone, now)
        assertEquals("Test supper", d.title)
        assertEquals("Kitchen", d.location)
        assertEquals("Bring bread", d.notes)
        assertFalse(d.allDay)
        assertEquals(LocalDate.of(2026, 10, 9), d.startDate)
        assertEquals(LocalTime.of(18, 0), d.startTime)
        assertEquals(LocalTime.of(19, 30), d.endTime)
        assertEquals(3L, d.calendarId)
        assertEquals(listOf(10), d.reminders)
    }

    @Test
    fun noBeginIsNextHourAndAnHourLong() {
        val d = Prefill(title = "Walk").draft(base, zone, now)
        assertEquals(LocalDate.of(2026, 10, 5), d.startDate)
        assertEquals(LocalTime.of(15, 0), d.startTime)
        assertEquals(LocalTime.of(16, 0), d.endTime)
        val late = Prefill().draft(base, zone, LocalDateTime.of(2026, 10, 5, 23, 40))
        assertEquals(LocalDate.of(2026, 10, 6), late.startDate)
        assertEquals(LocalTime.MIDNIGHT, late.startTime)
    }

    @Test
    fun endBeforeBeginIsIgnored() {
        val d = Prefill(begin = ms("2026-10-09T18:00"), end = ms("2026-10-09T17:00")).draft(base, zone, now)
        assertEquals(LocalTime.of(19, 0), d.endTime)
    }

    @Test
    fun allDayFromLocalOrUtcMidnight() {
        val local = Prefill(allDay = true, begin = ms("2026-10-17T00:00"), end = ms("2026-10-19T00:00")).draft(base, zone, now)
        assertTrue(local.allDay)
        assertEquals(LocalDate.of(2026, 10, 17), local.startDate)
        assertEquals(LocalDate.of(2026, 10, 18), local.endDate)
        assertEquals(listOf(900), local.reminders)

        val utc = Prefill(allDay = true, begin = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()).draft(base, zone, now)
        assertEquals(LocalDate.of(2026, 10, 17), utc.startDate)
        assertEquals(LocalDate.of(2026, 10, 17), utc.endDate)
    }

    @Test
    fun ruleAndCalendarCarried() {
        val d = Prefill(rrule = "FREQ=WEEKLY;BYDAY=FR", calendarId = 7, begin = ms("2026-10-09T18:00")).draft(base, zone, now)
        assertEquals("FREQ=WEEKLY;BYDAY=FR", d.rrule)
        assertEquals(7L, d.calendarId)
    }
}
