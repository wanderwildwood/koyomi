package com.wanderwildwood.koyomi.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * One event found in an .ics file, ready to be added.
 *
 * An all-day event's [start] and [end] are UTC midnights, as the calendar store keeps them,
 * and [end] is the day after the last one it covers. A timed one is in [zone]: the file's
 * TZID, UTC for a time written with a "Z", or the phone's own for a "floating" time.
 * [unknownZone] is a TZID that could not be read, when the phone's zone stood in for it.
 */
data class IcsEvent(
    val uid: String?,
    val title: String,
    val location: String?,
    val description: String?,
    val allDay: Boolean,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val zone: ZoneId,
    val rrule: String?,
    val unknownZone: String? = null,
) {
    val startMillis: Long get() = start.toInstant().toEpochMilli()
    val endMillis: Long get() = maxOf(end.toInstant().toEpochMilli(), startMillis)

    /** The store's DURATION for a repeating event, which has no DTEND. */
    fun duration(): String = if (allDay) {
        "P${maxOf(1L, (endMillis - startMillis + DAY_MS - 1) / DAY_MS)}D"
    } else {
        "P${(endMillis - startMillis) / 1000}S"
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * A small reader of iCalendar (RFC 5545), for the files other apps hand over: an invitation
 * from a mail client, a booking, a term's dates. It takes the events and leaves the rest.
 *
 * Etar's reader was looked at first and not taken: it keeps one value per property name across
 * a whole event, so a reminder's DESCRIPTION inside the event overwrites the event's own, and
 * it unescapes text with three regular expressions in turn, which reads "\\n" wrongly.
 *
 * What it does: lines folded with a space or a tab are joined; text is unescaped; a DATE is an
 * all-day event; a time with "Z" is UTC, one with a TZID is in that zone, and one with neither
 * is the phone's. A TZID may be an IANA name or a Windows one, as Outlook and Exchange write
 * them ("Eastern Standard Time"); a name that is neither is read from the file's own VTIMEZONE,
 * by its offsets and the days its clocks change. RRULE is passed through as written. Reminders, attendees and RSVP are left:
 * an invitation is added as a plain event. A cancelled event, or a file that is a
 * cancellation, adds nothing. A changed occurrence (RECURRENCE-ID) is left out when the series
 * it belongs to is in the same file, since adding both would show that day twice.
 */
object Ics {
    /**
     * [local] is the phone's zone. [zoneOf] turns a TZID into a zone; the default knows the
     * IANA names, those with a path in front of them as some programs write them, and the
     * Windows names. A TZID it does not know is looked for among the file's VTIMEZONEs.
     */
    fun parse(text: String, local: ZoneId, zoneOf: (String) -> ZoneId? = ::zone): List<IcsEvent> {
        val lines = unfold(text)
        var method: String? = null
        val stack = ArrayDeque<String>()
        val found = mutableListOf<Map<String, Property>>()
        var current: MutableMap<String, Property>? = null
        val custom = mutableMapOf<String, CustomZone>()
        var zoneId: String? = null
        var zoneParts = CustomZone(null, null)
        var part: MutableMap<String, Property>? = null
        for (raw in lines) {
            if (raw.isBlank()) continue
            val p = property(raw) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    val what = p.value.uppercase()
                    stack.addLast(what)
                    when (what) {
                        "VEVENT" -> current = mutableMapOf()
                        "VTIMEZONE" -> { zoneId = null; zoneParts = CustomZone(null, null) }
                        "STANDARD", "DAYLIGHT" -> part = mutableMapOf()
                    }
                }
                "END" -> {
                    val what = p.value.uppercase()
                    when (what) {
                        "VEVENT" -> {
                            current?.let { found += it }
                            current = null
                        }
                        "STANDARD", "DAYLIGHT" -> {
                            // Of several, the latest is the one in force now.
                            val rule = part?.let(::clockRule)
                            if (rule != null) {
                                val daylight = what == "DAYLIGHT"
                                val had = if (daylight) zoneParts.daylight else zoneParts.standard
                                if (had == null || (rule.since ?: LocalDateTime.MIN) >= (had.since ?: LocalDateTime.MIN)) {
                                    zoneParts = if (daylight) zoneParts.copy(daylight = rule) else zoneParts.copy(standard = rule)
                                }
                            }
                            part = null
                        }
                        "VTIMEZONE" -> zoneId?.let { custom[it] = zoneParts }
                    }
                    // Close back to the matching BEGIN, so one missing END does not swallow the rest.
                    val at = stack.lastIndexOf(what)
                    if (at >= 0) while (stack.size > at) stack.removeLast()
                }
                else -> when (stack.lastOrNull()) {
                    "VEVENT" -> current?.putIfAbsent(p.name, p)
                    "VCALENDAR" -> if (p.name == "METHOD") method = p.value.trim().uppercase()
                    "VTIMEZONE" -> if (p.name == "TZID") zoneId = p.value.trim().trim('"')
                    "STANDARD", "DAYLIGHT" -> part?.putIfAbsent(p.name, p)
                }
            }
        }
        if (method == "CANCEL") return emptyList()

        val series = found.filter { it["RECURRENCE-ID"] == null && it["RRULE"] != null }.mapNotNull { it["UID"]?.value }.toSet()
        return found
            .filter { it["STATUS"]?.value?.trim()?.uppercase() != "CANCELLED" }
            .filter { e -> e["RECURRENCE-ID"] == null || e["UID"]?.value !in series }
            .mapNotNull { event(it, local, ZoneReader(zoneOf, custom, local)) }
    }

    /**
     * Turns a TZID into a zone: [zoneOf] first, then the file's VTIMEZONE of that name, read
     * at [at] since the guess depends on the year.
     */
    private class ZoneReader(val zoneOf: (String) -> ZoneId?, val custom: Map<String, CustomZone>, val local: ZoneId) {
        fun of(tzid: String, at: LocalDateTime): ZoneId? = zoneOf(tzid) ?: custom[tzid]?.let { customZone(it, at, local) }
    }

    private fun event(props: Map<String, Property>, local: ZoneId, zones: ZoneReader): IcsEvent? {
        val dtStart = props["DTSTART"] ?: return null
        val start = time(dtStart, local, zones) ?: return null
        val msAllDay = props["X-MICROSOFT-CDO-ALLDAYEVENT"]?.value?.trim().equals("TRUE", ignoreCase = true)
        val end = props["DTEND"]?.let { time(it, local, zones) }
        val duration = props["DURATION"]?.value?.let(::duration)

        val allDay = start.date || msAllDay
        val zone = if (allDay) UTC else start.zone
        val begin: ZonedDateTime
        val finish: ZonedDateTime
        if (allDay) {
            val first = start.at.toLocalDate()
            begin = first.atStartOfDay(ZoneOffset.UTC)
            val last = when {
                end != null -> end.at.toLocalDate().let {
                    // A Microsoft all-day event is written as times from midnight to midnight.
                    if (!end.date && end.at.toLocalTime() != java.time.LocalTime.MIDNIGHT) it.plusDays(1) else it
                }
                duration != null -> begin.plus(duration).toLocalDate()
                else -> first.plusDays(1)
            }
            finish = maxOf(last, first.plusDays(1)).atStartOfDay(ZoneOffset.UTC)
        } else {
            begin = start.at.atZone(start.zone)
            finish = when {
                end != null && !end.date -> end.at.atZone(end.zone)
                duration != null -> begin.plus(duration)
                else -> begin
            }
        }
        return IcsEvent(
            uid = props["UID"]?.value?.trim()?.ifEmpty { null },
            title = props["SUMMARY"]?.let { unescape(it.value).trim() }.orEmpty(),
            location = props["LOCATION"]?.let { unescape(it.value).trim() }?.ifEmpty { null },
            description = props["DESCRIPTION"]?.let { unescape(it.value).trim() }?.ifEmpty { null },
            allDay = allDay,
            start = begin,
            end = finish,
            zone = zone,
            rrule = props["RRULE"]?.value?.trim()?.ifEmpty { null },
            unknownZone = if (allDay) null else start.unknownZone,
        )
    }

    /** A DTSTART or DTEND: the moment as written, and the zone it is read in. */
    private class Time(val at: LocalDateTime, val zone: ZoneId, val date: Boolean, val unknownZone: String?)

    private fun time(p: Property, local: ZoneId, zones: ZoneReader): Time? {
        val v = p.value.trim()
        val isDate = p.params["VALUE"].equals("DATE", ignoreCase = true) || DATE.matches(v)
        if (isDate) {
            val m = DATE.find(v) ?: return null
            val d = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
                ?: return null
            return Time(d.atStartOfDay(), ZoneOffset.UTC, true, null)
        }
        val m = DATE_TIME.matchEntire(v) ?: return null
        val (y, mo, d, h, mi, s, z) = m.destructured
        val at = runCatching {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt().coerceAtMost(59))
        }.getOrNull() ?: return null
        // ZoneId "UTC", not ZoneOffset.UTC: the store keeps the zone by name, and the
        // offset's name is "Z", which nothing reading the store knows.
        if (z.isNotEmpty()) return Time(at, UTC, false, null)
        val tzid = p.params["TZID"]?.trim()?.ifEmpty { null } ?: return Time(at, local, false, null)
        val zone = zones.of(tzid, at)
        return if (zone != null) Time(at, zone, false, null) else Time(at, local, false, tzid)
    }

    /** An IANA name, or failing that a Windows one. */
    fun zone(tzid: String): ZoneId? = ianaZone(tzid) ?: windowsZone(tzid)

    /**
     * "Eastern Standard Time", "W. Europe Standard Time": the names Windows gives its zones,
     * which Outlook and Exchange write as the TZID. The phone's own table (ICU) is asked first,
     * for the phone's country and then for the world, so a Canadian phone reads Eastern time
     * as Toronto's; [WINDOWS] holds the common ones for when ICU is not there to ask.
     */
    fun windowsZone(tzid: String): ZoneId? {
        val id = tzid.trim().trim('"')
        if (id.isEmpty()) return null
        val icu = runCatching {
            val country = Locale.getDefault().country
            (if (country.length == 2) android.icu.util.TimeZone.getIDForWindowsID(id, country) else null)
                ?: android.icu.util.TimeZone.getIDForWindowsID(id, "001")
        }.getOrNull()
        icu?.let { name -> runCatching { return ZoneId.of(name) } }
        return WINDOWS.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.let { ZoneId.of(it.value) }
    }

    /**
     * One STANDARD or DAYLIGHT part of a VTIMEZONE: the offset it sets, from when, and the
     * yearly day it comes back ([month], and the [nth] [weekday] of it, -1 the last), if that
     * could be read.
     */
    private data class ClockRule(val offset: ZoneOffset, val since: LocalDateTime?, val month: Int?, val nth: Int?, val weekday: java.time.DayOfWeek?) {
        fun dayIn(year: Int): LocalDate? {
            val m = month ?: return null
            val d = weekday ?: return null
            val n = nth ?: return null
            val first = LocalDate.of(year, m, 1)
            return if (n < 0) first.with(TemporalAdjusters.lastInMonth(d)) else first.with(TemporalAdjusters.dayOfWeekInMonth(n, d))
        }
    }

    private data class CustomZone(val standard: ClockRule?, val daylight: ClockRule?)

    private fun clockRule(props: Map<String, Property>): ClockRule? {
        val offset = props["TZOFFSETTO"]?.value?.trim()?.let(::offset) ?: return null
        val since = props["DTSTART"]?.value?.trim()?.let { DATE_TIME.matchEntire(it) }?.destructured?.let { (y, mo, d, h, mi, s) ->
            runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt()) }.getOrNull()
        }
        val rule = props["RRULE"]?.value?.uppercase()?.split(';')?.mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
        }?.toMap().orEmpty()
        val month = rule["BYMONTH"]?.toIntOrNull()?.takeIf { it in 1..12 }
        val byDay = rule["BYDAY"]?.let { BY_DAY.matchEntire(it) }
        val weekday = byDay?.groupValues?.get(2)?.let { day -> WEEKDAYS.indexOf(day).takeIf { it >= 0 }?.let { java.time.DayOfWeek.of(it + 1) } }
        val nth = byDay?.groupValues?.get(1)?.ifEmpty { null }?.toIntOrNull()?.takeIf { it in -1..5 && it != 0 }
        return ClockRule(offset, since, month, nth, weekday)
    }

    /** "-0500", "+0530", "+053000". */
    private fun offset(text: String): ZoneOffset? {
        val m = OFFSET.matchEntire(text) ?: return null
        val (sign, h, mi, s) = m.destructured
        val total = h.toInt() * 3600 + mi.toInt() * 60 + s.ifEmpty { "0" }.toInt()
        return runCatching { ZoneOffset.ofTotalSeconds(if (sign == "-") -total else total) }.getOrNull()
    }

    /**
     * A zone for a VTIMEZONE whose TZID is no zone's name, as Outlook writes "Customized Time
     * Zone" or "(UTC-05:00) Eastern Time (US & Canada)". With no daylight time it is a fixed
     * offset. With daylight time it is a real zone with the same two offsets whose clocks change
     * on the same days in [at]'s year: the phone's own if it fits, else a common one, else the
     * first that does. Any of them gives the same times; the phone's reads best.
     */
    private fun customZone(tz: CustomZone, at: LocalDateTime, local: ZoneId): ZoneId? {
        val standard = tz.standard ?: tz.daylight ?: return null
        val daylight = tz.daylight?.takeIf { tz.standard != null && it.offset != standard.offset }
        val year = at.year
        val fits = { zone: ZoneId -> fitsYear(zone, year, standard, daylight) }
        if (fits(local)) return local
        if (daylight == null) return ZoneId.ofOffset("GMT", standard.offset)
        return WINDOWS.values.asSequence().map { ZoneId.of(it) }.firstOrNull(fits)
            ?: ZoneId.getAvailableZoneIds().sorted().asSequence()
                .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
                .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
                .firstOrNull(fits)
    }

    /** Whether [zone]'s clocks in [year] are [standard]'s, and [daylight]'s on its days. */
    private fun fitsYear(zone: ZoneId, year: Int, standard: ClockRule, daylight: ClockRule?): Boolean {
        val rules = zone.rules
        val from = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant()
        val until = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant()
        if (rules.getStandardOffset(from) != standard.offset) return false
        val changes = generateSequence(rules.nextTransition(from)) { rules.nextTransition(it.instant) }
            .takeWhile { it.instant < until }.toList()
        if (daylight == null) return changes.isEmpty() && rules.getOffset(from) == standard.offset
        if (changes.size != 2) return false
        val on = changes.firstOrNull { it.offsetAfter == daylight.offset } ?: return false
        val off = changes.firstOrNull { it.offsetAfter == standard.offset } ?: return false
        daylight.dayIn(year)?.let { if (it != on.dateTimeBefore.toLocalDate()) return false }
        standard.dayIn(year)?.let { if (it != off.dateTimeBefore.toLocalDate()) return false }
        return true
    }

    /** "Europe/Berlin", or "/mozilla.org/20050126_1/Europe/Berlin" with its prefix dropped. */
    fun ianaZone(tzid: String): ZoneId? {
        val id = tzid.trim().trim('"')
        runCatching { return ZoneId.of(id) }
        var i = id.indexOf('/')
        while (i >= 0) {
            val rest = id.substring(i + 1)
            if (rest.contains('/')) runCatching { return ZoneId.of(rest) }
            i = id.indexOf('/', i + 1)
        }
        return null
    }

    /**
     * An RFC 5545 duration: "P1D", "PT1H30M", "P2W", "-PT15M" — and the calendar store's own
     * "P3600S", which leaves out the "T".
     */
    fun duration(text: String): Duration? {
        val t = text.trim().uppercase()
        val m = DURATION.matchEntire(t) ?: return null
        val (sign, w, d, h, mi, s) = m.destructured
        if (listOf(w, d, h, mi, s).all { it.isEmpty() }) return null
        fun n(x: String) = x.ifEmpty { "0" }.toLong()
        val total = Duration.ofDays(7 * n(w) + n(d)).plusHours(n(h)).plusMinutes(n(mi)).plusSeconds(n(s))
        return if (sign == "-") total.negated() else total
    }

    /** Joins folded lines: a line break followed by one space or tab continues the line. */
    fun unfold(text: String): List<String> =
        text.removePrefix("﻿")
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace("\n ", "").replace("\n\t", "")
            .split('\n')

    /** TEXT values: "\n" or "\N" is a line break, and "\\", "\;" and "\," are the character. */
    fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    class Property(val name: String, val params: Map<String, String>, val value: String)

    /** "NAME;PARAM=a;PARAM2=\"b:c\":value", with colons and semicolons inside quotes kept. */
    fun property(line: String): Property? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val parts = mutableListOf<String>()
        quoted = false
        var from = 0
        for (i in head.indices) {
            val c = head[i]
            if (c == '"') quoted = !quoted
            if (c == ';' && !quoted) { parts += head.substring(from, i); from = i + 1 }
        }
        parts += head.substring(from)
        val params = parts.drop(1).mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim().uppercase() to part.substring(eq + 1).trim().trim('"')
        }.toMap()
        return Property(parts[0].trim().uppercase(), params, line.substring(colon + 1))
    }

    private val UTC: ZoneId = ZoneId.of("UTC")
    private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val DATE_TIME = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z?)$""", RegexOption.IGNORE_CASE)
    private val OFFSET = Regex("""^([+-])(\d{2})(\d{2})(\d{2})?$""")
    private val BY_DAY = Regex("""^([+-]?\d)?(SU|MO|TU|WE|TH|FR|SA)$""")
    private val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** The common Windows zone names and CLDR's zone for each, for when ICU cannot be asked. */
    private val WINDOWS = linkedMapOf(
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Alaskan Standard Time" to "America/Anchorage",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Atlantic Standard Time" to "America/Halifax",
        "Newfoundland Standard Time" to "America/St_Johns",
        "Central Standard Time (Mexico)" to "America/Mexico_City",
        "SA Pacific Standard Time" to "America/Bogota",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "GTB Standard Time" to "Europe/Bucharest",
        "FLE Standard Time" to "Europe/Kiev",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Russian Standard Time" to "Europe/Moscow",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "Arabian Standard Time" to "Asia/Dubai",
        "India Standard Time" to "Asia/Kolkata",
        "China Standard Time" to "Asia/Shanghai",
        "Singapore Standard Time" to "Asia/Singapore",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "W. Australia Standard Time" to "Australia/Perth",
        "Cen. Australia Standard Time" to "Australia/Adelaide",
        "AUS Central Standard Time" to "Australia/Darwin",
        "E. Australia Standard Time" to "Australia/Brisbane",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "New Zealand Standard Time" to "Pacific/Auckland",
    )
    private val DURATION = Regex("""^([+-]?)P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
}
