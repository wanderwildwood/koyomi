package com.wanderwildwood.koyomi.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
 * is the phone's. RRULE is passed through as written. Reminders, attendees and RSVP are left:
 * an invitation is added as a plain event. A cancelled event, or a file that is a
 * cancellation, adds nothing. A changed occurrence (RECURRENCE-ID) is left out when the series
 * it belongs to is in the same file, since adding both would show that day twice.
 */
object Ics {
    /**
     * [local] is the phone's zone. [zoneOf] turns a TZID into a zone; the default knows the
     * IANA names, and those with a path in front of them as some programs write them.
     */
    fun parse(text: String, local: ZoneId, zoneOf: (String) -> ZoneId? = ::ianaZone): List<IcsEvent> {
        val lines = unfold(text)
        var method: String? = null
        val stack = ArrayDeque<String>()
        val found = mutableListOf<Map<String, Property>>()
        var current: MutableMap<String, Property>? = null
        for (raw in lines) {
            if (raw.isBlank()) continue
            val p = property(raw) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    val what = p.value.uppercase()
                    stack.addLast(what)
                    if (what == "VEVENT") current = mutableMapOf()
                }
                "END" -> {
                    val what = p.value.uppercase()
                    if (what == "VEVENT") {
                        current?.let { found += it }
                        current = null
                    }
                    // Close back to the matching BEGIN, so one missing END does not swallow the rest.
                    val at = stack.lastIndexOf(what)
                    if (at >= 0) while (stack.size > at) stack.removeLast()
                }
                else -> when (stack.lastOrNull()) {
                    "VEVENT" -> current?.putIfAbsent(p.name, p)
                    "VCALENDAR" -> if (p.name == "METHOD") method = p.value.trim().uppercase()
                }
            }
        }
        if (method == "CANCEL") return emptyList()

        val series = found.filter { it["RECURRENCE-ID"] == null && it["RRULE"] != null }.mapNotNull { it["UID"]?.value }.toSet()
        return found
            .filter { it["STATUS"]?.value?.trim()?.uppercase() != "CANCELLED" }
            .filter { e -> e["RECURRENCE-ID"] == null || e["UID"]?.value !in series }
            .mapNotNull { event(it, local, zoneOf) }
    }

    private fun event(props: Map<String, Property>, local: ZoneId, zoneOf: (String) -> ZoneId?): IcsEvent? {
        val dtStart = props["DTSTART"] ?: return null
        val start = time(dtStart, local, zoneOf) ?: return null
        val msAllDay = props["X-MICROSOFT-CDO-ALLDAYEVENT"]?.value?.trim().equals("TRUE", ignoreCase = true)
        val end = props["DTEND"]?.let { time(it, local, zoneOf) }
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

    private fun time(p: Property, local: ZoneId, zoneOf: (String) -> ZoneId?): Time? {
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
        val zone = zoneOf(tzid)
        return if (zone != null) Time(at, zone, false, null) else Time(at, local, false, tzid)
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
    private val DURATION = Regex("""^([+-]?)P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
}
