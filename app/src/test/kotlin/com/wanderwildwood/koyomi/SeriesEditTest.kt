package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Draft
import com.wanderwildwood.koyomi.data.SeriesShift
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SeriesEditTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(date: LocalDate, time: LocalTime = LocalTime.of(9, 0)) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    private fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // A weekly series on Mondays and Wednesdays from Monday 5 October 2026.
    private val first = LocalDate.of(2026, 10, 5)

    @Test fun daysChangedFromALaterOccurrenceKeepTheFirstWeek() {
        // Opened on Wednesday 14 October, the days changed to Tuesday and Thursday, the time
        // left alone. The person changed nothing about the date: the series keeps its first
        // week and starts on its first Tuesday, not a day the rule does not name.
        val start = Draft.seriesStartAfterEdit(at(first), change = SeriesShift.NONE, allDay = false, days = setOf(TUESDAY, THURSDAY), zone = zone)
        assertEquals(LocalDate.of(2026, 10, 6), day(start))
        assertEquals(LocalTime.of(9, 0), java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalTime())
    }

    @Test fun aFirstDayTheNewRuleStillNamesStays() {
        // Opened on Wednesday 14 October, the days changed to Monday and Friday: the opened day
        // would shift two days to Friday, but that shift is not a change to the series, which
        // keeps Monday 5 October, a day the new rule names.
        val start = Draft.seriesStartAfterEdit(at(first), change = SeriesShift.NONE, allDay = false, days = setOf(MONDAY, FRIDAY), zone = zone)
        assertEquals(first, day(start))
    }

    @Test fun aTimeChangeMovesTheSeriesByThatMuch() {
        val hour = SeriesShift(0, java.time.Duration.ofHours(1))
        val start = Draft.seriesStartAfterEdit(at(first), change = hour, allDay = false, days = setOf(MONDAY, WEDNESDAY), zone = zone)
        assertEquals(at(first, LocalTime.of(10, 0)), start)
    }

    @Test fun aDateMovedAcrossAChangeOfTheClocksKeepsTheTime() {
        // A series from Monday 5 October 2026 at 09:00, opened on Saturday 31 October and moved
        // to Sunday 1 November, the day the clocks go back: 25 hours apart, but one day on the
        // clock. The series moves one day and stays at 09:00.
        val opened = at(LocalDate.of(2026, 10, 31))
        val moved = at(LocalDate.of(2026, 11, 1))
        val change = SeriesShift.between(opened, moved, allDay = false, zone = zone)
        assertEquals(SeriesShift(1, java.time.Duration.ZERO), change)
        val start = Draft.seriesStartAfterEdit(at(first), change, allDay = false, days = null, zone = zone)
        assertEquals(at(LocalDate.of(2026, 10, 6)), start)
    }

    @Test fun anAllDaySeriesStaysOnWholeDays() {
        val utcFirst = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val start = Draft.seriesStartAfterEdit(utcFirst, change = SeriesShift.NONE, allDay = true, days = setOf(THURSDAY), zone = zone)
        assertEquals(LocalDate.of(2026, 10, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), start)
    }

    @Test fun shiftDaysGoesToTheFirstNamedDay() {
        assertEquals(0, Draft.shiftDays(first, null))
        assertEquals(0, Draft.shiftDays(first, setOf(MONDAY)))
        assertEquals(1, Draft.shiftDays(first, setOf(TUESDAY, FRIDAY)))
        assertEquals(4, Draft.shiftDays(first, setOf(FRIDAY)))
    }
}
