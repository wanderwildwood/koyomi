package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Ics
import com.wanderwildwood.koyomi.data.IcsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The repeating-events stress file (src/test/resources/stress.ics, made by tools/make_stress.py
 * with a made-up cast) read by [Ics] and counted, month by month, against the table the script
 * writes from its own day-by-day walk of the same rules. The walk here is a third, independent
 * one: it reads what [Ics] produced (start, zone, RRULE, EXDATE) the way the calendar store
 * would, so a start, zone, UNTIL or EXDATE that is read wrongly shows as a different month count.
 */
class StressTest {
    private val phone = ZoneId.of("America/New_York")

    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText()

    private val events: List<IcsEvent> by lazy { Ics.parse(resource("stress.ics"), phone) }

    private val expected: Map<YearMonth, Int> by lazy {
        resource("stress-expected.csv").lines().drop(1).filter { it.isNotBlank() }.associate {
            val (m, n) = it.split(',')
            YearMonth.parse(m) to n.toInt()
        }
    }

    private val first = LocalDate.of(2026, 1, 1)
    private val last = LocalDate.of(2027, 12, 31)

    private fun rule(rrule: String) = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun weekday(code: String) = DayOfWeek.of(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").indexOf(code) + 1)

    private fun nthWeekday(month: YearMonth, n: Int, day: DayOfWeek): LocalDate? {
        val all = (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { it.dayOfWeek == day }
        return if (n > 0) all.getOrNull(n - 1) else all.getOrNull(all.size + n)
    }

    /** The dates, in the event's own zone, of every occurrence, EXDATE left out. */
    private fun walk(e: IcsEvent): List<ZonedDateTime> {
        val r = rule(e.rrule!!)
        val zone = if (e.allDay) ZoneOffset.UTC else e.zone
        val start = e.start
        val startDate = start.withZoneSameInstant(zone).toLocalDate()
        val time = start.withZoneSameInstant(zone).toLocalTime()
        val interval = r["INTERVAL"]?.toInt() ?: 1
        val count = r["COUNT"]?.toInt()
        val until: java.time.Instant? = r["UNTIL"]?.let { u ->
            when {
                u.length == 8 -> LocalDate.parse(u, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59).atZone(zone).toInstant()
                u.endsWith("Z") -> LocalDateTime.parse(u.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC)
                else -> LocalDateTime.parse(u, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(zone).toInstant()
            }
        }
        val byDay = r["BYDAY"]?.split(',')?.map { Regex("""([+-]?\d)?(..)""").matchEntire(it)!!.let { m -> (m.groupValues[1].toIntOrNull() ?: 0) to weekday(m.groupValues[2]) } }
        val out = mutableListOf<ZonedDateTime>()
        var d = startDate
        var n = 0
        val end = last.plusDays(40)
        while (!d.isAfter(end)) {
            val hit = when (r["FREQ"]) {
                "DAILY" -> java.time.temporal.ChronoUnit.DAYS.between(startDate, d) % interval == 0L
                "WEEKLY" -> {
                    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(
                        startDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                        d.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    )
                    weeks % interval == 0L && d.dayOfWeek in (byDay?.map { it.second } ?: listOf(startDate.dayOfWeek))
                }
                "MONTHLY" -> {
                    val months = (d.year - startDate.year) * 12L + d.monthValue - startDate.monthValue
                    if (months % interval != 0L) false
                    else if (r["BYMONTHDAY"] != null) d.dayOfMonth == r["BYMONTHDAY"]!!.toInt()
                    else {
                        val ym = YearMonth.from(d)
                        val picks = byDay!!.flatMap { (nth, day) ->
                            if (nth != 0) listOfNotNull(nthWeekday(ym, nth, day))
                            else (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { it.dayOfWeek == day }
                        }.sorted()
                        val pos = r["BYSETPOS"]?.toInt()
                        val chosen = if (pos == null) picks else listOfNotNull(if (pos > 0) picks.getOrNull(pos - 1) else picks.getOrNull(picks.size + pos))
                        d in chosen
                    }
                }
                "YEARLY" -> d.monthValue == startDate.monthValue && d.dayOfMonth == startDate.dayOfMonth
                else -> false
            }
            if (hit) {
                val at = d.atTime(time).atZone(zone)
                if (until != null && at.toInstant().isAfter(until)) break
                n++
                if (count != null && n > count) break
                out += at
            }
            d = d.plusDays(1)
        }
        val gone = e.exdate?.split(',').orEmpty().toSet()
        return out.filter { at ->
            val stamp = if (e.allDay) at.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            else at.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            stamp !in gone
        }
    }

    private fun counts(): Map<YearMonth, Int> {
        val seen = sortedMapOf<YearMonth, Int>()
        for (e in events) {
            val days = if (e.rrule != null) walk(e).map { if (e.allDay) it.toLocalDate() else it.withZoneSameInstant(phone).toLocalDate() }
            else listOf(if (e.allDay) e.start.toLocalDate() else e.start.withZoneSameInstant(phone).toLocalDate())
            for (d in days) if (d in first..last) seen.merge(YearMonth.from(d), 1, Int::plus)
        }
        return seen
    }

    @Test fun theFileHasAboutThreeHundredEvents() {
        val total = resource("stress.ics").lines().count { it == "BEGIN:VEVENT" }
        assertTrue("$total events", total in 300..400)
    }

    @Test fun editsOfASeriesInTheFileAreLeftOutAndTheRestAreKept() {
        val text = resource("stress.ics")
        val all = text.lines().count { it == "BEGIN:VEVENT" }
        val edits = text.lines().count { it.startsWith("RECURRENCE-ID") }
        val withSeries = events.filter { it.recurrenceId == null }.size
        val kept = events.count { it.recurrenceId != null }
        // 30 edits belong to a series in the file: dropped, so the day shows once. The other 40 stand alone.
        assertEquals(70, edits)
        assertEquals(all - 30, events.size)
        assertEquals(40, kept)
        assertEquals(all - 70, withSeries)
    }

    @Test fun twoEditsSharingAUidKeepTheirOwnDays() {
        val byUid = events.filter { it.recurrenceId != null }.groupBy { it.uid }
        assertTrue(byUid.values.any { it.size == 2 })
        assertTrue(byUid.values.all { list -> list.map { it.startMillis }.distinct().size == list.size })
    }

    @Test fun exdatesFromSeveralLinesAndZonesAllArrive() {
        val withEx = events.filter { it.exdate != null }
        assertEquals(24, withEx.size)
        assertTrue(withEx.all { it.exdate!!.split(',').size in 3..4 })
        assertTrue(withEx.filter { it.allDay }.all { e -> e.exdate!!.split(',').all { it.length == 8 } })
        assertTrue(withEx.filter { !it.allDay }.all { e -> e.exdate!!.split(',').all { it.endsWith("Z") } })
    }

    @Test fun aWindowsZoneNameIsReadAsItsZone() {
        val zones = events.filter { it.rrule != null && !it.allDay }.map { it.zone.id }.toSet()
        assertTrue(zones.toString(), zones.containsAll(listOf("Europe/Berlin", "America/Chicago", "Asia/Tokyo", "America/New_York")))
    }

    @Test fun aSeriesKeepsItsWallClockAcrossBothDstChanges() {
        val e = events.first { it.rrule?.contains("COUNT=300") == true }
        val days = walk(e)
        val inMarch = days.first { it.toLocalDate() == LocalDate.of(2026, 3, 10) }
        val inFebruary = days.first { it.toLocalDate() == LocalDate.of(2026, 3, 1) }
        assertEquals(inFebruary.toLocalTime(), inMarch.toLocalTime())
        val nov = days.first { it.toLocalDate() == LocalDate.of(2026, 11, 10) }
        assertEquals(inFebruary.toLocalTime(), nov.toLocalTime())
        // 24 hours a day is not the same thing: the instants are an hour apart across a change.
        assertEquals(3600L, Math.abs(java.time.Duration.between(inMarch.toInstant(), inFebruary.toInstant().plus(java.time.Duration.ofDays(9))).seconds))
    }

    @Test fun everyMonthHasTheNumberOfOccurrencesTheTableSays() {
        val seen = counts()
        val bad = expected.filter { (m, n) -> seen[m] != n }.map { (m, n) -> "$m expected $n saw ${seen[m]}" }
        assertEquals(bad.joinToString("; "), 0, bad.size)
        assertEquals(expected.keys, seen.keys)
    }
}
