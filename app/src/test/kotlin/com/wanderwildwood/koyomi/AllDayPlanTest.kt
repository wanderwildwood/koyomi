package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.AllDayKind
import com.wanderwildwood.koyomi.data.AllDayMode
import com.wanderwildwood.koyomi.data.AllDayPlan
import com.wanderwildwood.koyomi.data.AllDaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AllDayPlanTest {
    private val ny = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val auckland = ZoneId.of("Pacific/Auckland")

    private fun at(day: String, time: String, zone: ZoneId) =
        LocalDateTime.parse("${day}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun setting(mode: AllDayMode, eve: Int = 18 * 60, morn: Int = 7 * 60) = AllDaySetting(mode, eve, morn)

    @Test fun offMakesNothing() {
        val d = LocalDate.of(2026, 11, 3)
        assertTrue(AllDayPlan.fires(d, d, setting(AllDayMode.OFF), ny).isEmpty())
    }

    @Test fun eveningIsTheDayBeforeAtTheChosenTime() {
        val d = LocalDate.of(2026, 11, 3)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.EVENING), ny).single()
        assertEquals(AllDayKind.EVENING, f.kind)
        assertEquals(at("2026-11-02", "18:00", ny), f.atMs)
        // "Tomorrow" is over when the day starts.
        assertEquals(at("2026-11-03", "00:00", ny), f.validUntilMs)
    }

    @Test fun morningIsTheDayItselfAndHoldsToTheLastDay() {
        val first = LocalDate.of(2026, 11, 3)
        val last = LocalDate.of(2026, 11, 5)
        val f = AllDayPlan.fires(first, last, setting(AllDayMode.MORNING, morn = 6 * 60 + 30), ny).single()
        assertEquals(AllDayKind.MORNING, f.kind)
        assertEquals(at("2026-11-03", "06:30", ny), f.atMs)
        assertEquals(at("2026-11-06", "00:00", ny), f.validUntilMs)
    }

    @Test fun bothMakesTwoInOrder() {
        val d = LocalDate.of(2026, 11, 3)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), ny)
        assertEquals(listOf(AllDayKind.EVENING, AllDayKind.MORNING), f.map { it.kind })
        assertTrue(f[0].atMs < f[1].atMs)
    }

    @Test fun clockChangeInTheFallIsKeptOnTheWallClock() {
        // The clocks go back on 1 Nov 2026: the evening before the 2nd is 18:00 EST, 25 hours
        // after the evening before that was 18:00 EDT.
        val evening = { d: LocalDate -> AllDayPlan.fires(d, d, setting(AllDayMode.EVENING), ny).single().atMs }
        val a = evening(LocalDate.of(2026, 11, 1))
        val b = evening(LocalDate.of(2026, 11, 2))
        assertEquals(at("2026-10-31", "18:00", ny), a)
        assertEquals(at("2026-11-01", "18:00", ny), b)
        assertEquals(25L * 3_600_000, b - a)
    }

    @Test fun clockChangeInTheSpringIsKeptOnTheWallClock() {
        // The clocks go forward on 8 Mar 2026: the day itself is 23 hours long.
        val d = LocalDate.of(2026, 3, 8)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), ny)
        assertEquals(at("2026-03-07", "18:00", ny), f[0].atMs)
        assertEquals(at("2026-03-08", "07:00", ny), f[1].atMs)
        // 07:00 EDT is 11:00 UTC, 18:00 EST the evening before is 23:00 UTC.
        assertEquals(Instant.parse("2026-03-07T23:00:00Z").toEpochMilli(), f[0].atMs)
        assertEquals(Instant.parse("2026-03-08T11:00:00Z").toEpochMilli(), f[1].atMs)
        assertEquals(at("2026-03-09", "00:00", ny), f[1].validUntilMs)
    }

    @Test fun aTimeInTheSkippedHourMovesForwardNotAway() {
        val d = LocalDate.of(2026, 3, 8)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.MORNING, morn = 2 * 60 + 30), ny).single()
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), f.atMs) // 03:30 EDT
    }

    @Test fun theSameDateRingsAtTheLocalTimeInAnyZone() {
        val d = LocalDate.of(2026, 11, 3)
        listOf(ny, tokyo, auckland, ZoneId.of("America/Los_Angeles")).forEach { z ->
            val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), z)
            assertEquals(at("2026-11-02", "18:00", z), f[0].atMs)
            assertEquals(at("2026-11-03", "07:00", z), f[1].atMs)
        }
    }

    @Test fun anAllDayDateIsReadFromItsUtcMidnightInEveryZone() {
        // The provider stores 3 Nov as 2026-11-03T00:00Z. Read in a zone west of Greenwich that
        // instant is still the 2nd; the date must come from UTC, as toLocal does.
        val utcMidnight = Instant.parse("2026-11-03T00:00:00Z").toEpochMilli()
        listOf(ny, tokyo, auckland, ZoneId.of("Pacific/Honolulu")).forEach { _ ->
            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(utcMidnight), ZoneOffset.UTC).toLocalDate()
            assertEquals(LocalDate.of(2026, 11, 3), date)
        }
        assertEquals(LocalDate.of(2026, 11, 2), Instant.ofEpochMilli(utcMidnight).atZone(ny).toLocalDate())
    }

    @Test fun changingZoneMovesTheMomentButNotTheDay() {
        val d = LocalDate.of(2026, 11, 3)
        val inNy = AllDayPlan.fires(d, d, setting(AllDayMode.MORNING), ny).single().atMs
        val inTokyo = AllDayPlan.fires(d, d, setting(AllDayMode.MORNING), tokyo).single().atMs
        assertEquals(at("2026-11-03", "07:00", ny), inNy)
        assertEquals(at("2026-11-03", "07:00", tokyo), inTokyo)
        assertTrue(inNy > inTokyo)
    }

    // --- an event's own reminders ----------------------------------------------------------

    @Test fun ownReminderOnTheMorningCoversTheMorning() {
        val d = LocalDate.of(2026, 11, 3)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), ny)
        // 540 minutes before the midnight is wrong way round: -540 is 09:00 on the day.
        assertTrue(AllDayPlan.coveredByOwn(f[1], d, listOf(-540), ny))
        assertTrue(AllDayPlan.coveredByOwn(f[1], d, listOf(0), ny))
        assertFalse(AllDayPlan.coveredByOwn(f[0], d, listOf(-540), ny))
    }

    @Test fun ownReminderTheEveningBeforeCoversTheEvening() {
        val d = LocalDate.of(2026, 11, 3)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), ny)
        // 360 minutes before midnight is 18:00 the day before.
        assertTrue(AllDayPlan.coveredByOwn(f[0], d, listOf(360), ny))
        assertFalse(AllDayPlan.coveredByOwn(f[1], d, listOf(360), ny))
    }

    @Test fun ownReminderAWeekBeforeCoversNeither() {
        val d = LocalDate.of(2026, 11, 3)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.BOTH), ny)
        f.forEach { assertFalse(AllDayPlan.coveredByOwn(it, d, listOf(9540), ny)) }
        f.forEach { assertFalse(AllDayPlan.coveredByOwn(it, d, emptyList(), ny)) }
    }

    @Test fun ownReminderIsReadOnTheClockOfTheDayNotAFixedOffset() {
        // 1 Nov 2026 is 25 hours long; an own reminder of 360 minutes before the 2nd's midnight
        // rings at 18:00 EST on the 1st, the same day as the evening reminder.
        val d = LocalDate.of(2026, 11, 2)
        val f = AllDayPlan.fires(d, d, setting(AllDayMode.EVENING), ny).single()
        assertTrue(AllDayPlan.coveredByOwn(f, d, listOf(360), ny))
    }

    // --- no duplicates ---------------------------------------------------------------------

    private fun key(calendar: Long = 3, uid: String? = "bins-42@example.org", title: String = "Bins", begin: Long = 1000, kind: AllDayKind = AllDayKind.EVENING) =
        AllDayPlan.keys(calendar, uid, title, begin, kind).first()

    @Test fun aKeyNamesTheOccurrenceAndTheKind() {
        val a = key()
        assertEquals(a, key())
        assertTrue(a != key(kind = AllDayKind.MORNING))
        assertTrue(a != key(begin = 2000))
        assertTrue(a != key(calendar = 4))
        assertTrue(a != key(uid = "bins-43@example.org"))
    }

    @Test fun aKeyHasNoRowIdSoAFetchAgainKeepsIt() {
        // A followed calendar's events come back under new row ids on every fetch; the key is
        // made of what the feed itself says, so it is the same before and after.
        assertEquals(key(), AllDayPlan.keys(3, "bins-42@example.org", "Bins", 1000, AllDayKind.EVENING).first())
        // The UID is what counts while there is one: a new title is still the same event.
        assertEquals(key(), key(title = "Bins and recycling"))
    }

    @Test fun withoutAUidTheTitleStandsIn() {
        assertEquals(key(uid = null), key(uid = ""))
        assertEquals(key(uid = null), key(uid = null))
        assertTrue(key(uid = null) != key(uid = null, title = "Recycling"))
        // With a UID, the title's key is kept too, so a moment without the UID changes nothing.
        val both = AllDayPlan.keys(3, "bins-42@example.org", "Bins", 1000, AllDayKind.EVENING)
        assertEquals(2, both.size)
        assertEquals(key(uid = null), both[1])
    }

    @Test fun aKeyKeepsTheStateFilesSeparatorsWhateverTheTitle() {
        // Shown keys are stored as key@until and snoozes as key=at;key=at.
        for (k in listOf(key(uid = "a;b=c@d"), key(uid = null, title = "x@y=z;w"))) {
            assertFalse(k, k.contains('@') || k.contains('=') || k.contains(';'))
        }
    }

    @Test fun theOldKeyCannotBeMistakenForANewOne() {
        // The row-id key of earlier versions is read once, to not ring again after the update.
        assertEquals("7:1000:1", AllDayPlan.legacyKey(7, 1000, AllDayKind.EVENING))
        assertTrue(AllDayPlan.keys(7, null, "1000", 1, AllDayKind.EVENING).none { it == AllDayPlan.legacyKey(7, 1000, AllDayKind.EVENING) })
    }

    @Test fun aSettingSurvivesBeingStored() {
        val s = setting(AllDayMode.BOTH, 19 * 60 + 15, 6 * 60 + 45)
        assertEquals(s, AllDaySetting.decode(s.encode()))
        assertEquals(AllDaySetting(), AllDaySetting.decode(null))
        assertEquals(AllDaySetting(), AllDaySetting.decode("nonsense"))
        assertEquals(18 * 60, AllDaySetting.decode("EVENING,99999,5").eveningMinute)
    }
}
