package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Ics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class IcsTest {
    private val phone = ZoneId.of("America/New_York")

    private fun cal(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun timedWithTzid() {
        val e = Ics.parse(
            cal(
                "BEGIN:VEVENT", "UID:a1@example.org", "SUMMARY:Pottery class",
                "DTSTART;TZID=Europe/Berlin:20261012T183000", "DTEND;TZID=Europe/Berlin:20261012T200000",
                "END:VEVENT",
            ),
            phone,
        ).single()
        assertFalse(e.allDay)
        assertEquals("Europe/Berlin", e.zone.id)
        assertEquals(ms("2026-10-12T16:30:00Z"), e.startMillis)
        assertEquals(ms("2026-10-12T18:00:00Z"), e.endMillis)
        assertEquals("a1@example.org", e.uid)
        assertNull(e.unknownZone)
    }

    @Test
    fun utcAndFloating() {
        val list = Ics.parse(
            cal(
                "BEGIN:VEVENT", "SUMMARY:Call", "DTSTART:20261012T140000Z", "DURATION:PT45M", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:Supper", "DTSTART:20261012T180000", "END:VEVENT",
            ),
            phone,
        )
        assertEquals(ms("2026-10-12T14:00:00Z"), list[0].startMillis)
        assertEquals(ms("2026-10-12T14:45:00Z"), list[0].endMillis)
        assertEquals("UTC", list[0].zone.id)
        // Floating: the phone's own time, 18:00 in New York.
        assertEquals(ms("2026-10-12T22:00:00Z"), list[1].startMillis)
        assertEquals(list[1].startMillis, list[1].endMillis)
        assertEquals(phone, list[1].zone)
    }

    @Test
    fun allDay() {
        val list = Ics.parse(
            cal(
                "BEGIN:VEVENT", "SUMMARY:Harvest fair", "DTSTART;VALUE=DATE:20261017", "DTEND;VALUE=DATE:20261019", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:One day", "DTSTART;VALUE=DATE:20261020", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:Outlook day", "X-MICROSOFT-CDO-ALLDAYEVENT:TRUE",
                "DTSTART;TZID=Eastern Standard Time:20261021T000000", "DTEND;TZID=Eastern Standard Time:20261022T000000", "END:VEVENT",
            ),
            phone,
        )
        assertTrue(list.all { it.allDay })
        assertEquals(ms("2026-10-17T00:00:00Z"), list[0].startMillis)
        assertEquals(ms("2026-10-19T00:00:00Z"), list[0].endMillis)
        assertEquals("P2D", list[0].duration())
        assertEquals(ms("2026-10-21T00:00:00Z"), list[1].endMillis)
        assertEquals(ms("2026-10-21T00:00:00Z"), list[2].startMillis)
        assertEquals(ms("2026-10-22T00:00:00Z"), list[2].endMillis)
    }

    @Test
    fun repeatingPassesRuleThrough() {
        val e = Ics.parse(
            cal(
                "BEGIN:VEVENT", "UID:r1", "SUMMARY:Choir", "DTSTART;TZID=America/Chicago:20261007T190000",
                "DTEND;TZID=America/Chicago:20261007T203000", "RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=10", "END:VEVENT",
                // A changed occurrence of that series: left out, the series is here.
                "BEGIN:VEVENT", "UID:r1", "RECURRENCE-ID;TZID=America/Chicago:20261014T190000", "SUMMARY:Choir (moved)",
                "DTSTART;TZID=America/Chicago:20261015T190000", "DTEND;TZID=America/Chicago:20261015T203000", "END:VEVENT",
            ),
            phone,
        ).single()
        assertEquals("FREQ=WEEKLY;BYDAY=WE;COUNT=10", e.rrule)
        assertEquals("P5400S", e.duration())
    }

    @Test
    fun foldedLinesAndEscapes() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Soup\\, bread\\; and a \r\n long walk\r\n" +
            "DESCRIPTION:Bring a bowl\\nand a spoon\\N\\\\ok\r\n\tdone\r\nLOCATION:Hall 2\\, back door\r\n" +
            "DTSTART:20261012T120000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val e = Ics.parse(text, phone).single()
        assertEquals("Soup, bread; and a long walk", e.title)
        assertEquals("Bring a bowl\nand a spoon\n\\okdone", e.description)
        assertEquals("Hall 2, back door", e.location)
    }

    @Test
    fun alarmDoesNotOverwriteEvent() {
        val e = Ics.parse(
            cal(
                "BEGIN:VEVENT", "SUMMARY:Dentist", "DESCRIPTION:Check-up", "DTSTART:20261012T090000Z",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM",
                "END:VEVENT",
            ),
            phone,
        ).single()
        assertEquals("Check-up", e.description)
    }

    @Test
    fun invitationImportsCancellationDoesNot() {
        val invite = cal(
            "METHOD:REQUEST", "BEGIN:VEVENT", "SUMMARY:Planning", "DTSTART:20261012T150000Z",
            "ORGANIZER;CN=\"Someone: here\":mailto:organizer@example.org", "ATTENDEE;RSVP=TRUE:mailto:guest@example.org",
            "END:VEVENT",
        )
        assertEquals("Planning", Ics.parse(invite, phone).single().title)
        assertTrue(Ics.parse(invite.replace("METHOD:REQUEST", "METHOD:CANCEL"), phone).isEmpty())
        val cancelled = cal("BEGIN:VEVENT", "STATUS:CANCELLED", "DTSTART:20261012T150000Z", "END:VEVENT")
        assertTrue(Ics.parse(cancelled, phone).isEmpty())
    }

    @Test
    fun zones() {
        assertEquals(ZoneId.of("Europe/Paris"), Ics.ianaZone("/mozilla.org/20050126_1/Europe/Paris"))
        assertEquals(ZoneId.of("Asia/Tokyo"), Ics.ianaZone("\"Asia/Tokyo\""))
        assertNull(Ics.ianaZone("Somewhere Standard Time"))
        val e = Ics.parse(cal("BEGIN:VEVENT", "DTSTART;TZID=Somewhere Standard Time:20261012T090000", "END:VEVENT"), phone).single()
        assertEquals("Somewhere Standard Time", e.unknownZone)
        assertEquals(phone, e.zone)
    }

    @Test
    fun durations() {
        assertEquals(Duration.ofDays(14), Ics.duration("P2W"))
        assertEquals(Duration.ofMinutes(90), Ics.duration("PT1H30M"))
        assertEquals(Duration.ofSeconds(3600), Ics.duration("P3600S"))
        assertEquals(Duration.ofDays(1).plusHours(2), Ics.duration("P1DT2H"))
        assertEquals(Duration.ofMinutes(-15), Ics.duration("-PT15M"))
        assertNull(Ics.duration("P"))
        assertNull(Ics.duration("soon"))
    }

    @Test
    fun brokenEventIsSkippedNotFatal() {
        val list = Ics.parse(
            cal(
                "BEGIN:VEVENT", "SUMMARY:No start", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:Bad date", "DTSTART:2026-10-12", "END:VEVENT",
                "BEGIN:VEVENT", "SUMMARY:Fine", "DTSTART;VALUE=DATE:20261012", "END:VEVENT",
            ),
            phone,
        )
        assertEquals(listOf("Fine"), list.map { it.title })
    }
}
