package com.wanderwildwood.koyomi.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The EXDATE column, which holds the occurrences a series leaves out. Its lines are
 * separated by newlines, each an optional `TZID;` and then a comma list of dates or
 * date-times: UTC ones ending in Z, floating ones read in the TZID, or bare dates.
 */
object Exdates {
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Every exclusion moved by [offsetMs], in the form it was written in, so a series moved
     * from 19:00 to 18:30 still leaves out the evenings it left out before.
     */
    fun shift(exdate: String?, offsetMs: Long): String? {
        if (exdate.isNullOrBlank() || offsetMs == 0L) return exdate
        return exdate.split("\n").joinToString("\n") { line ->
            val semi = line.indexOf(';')
            val tz = if (semi >= 0) line.substring(0, semi) else null
            val list = if (semi >= 0) line.substring(semi + 1) else line
            val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
            val moved = list.split(",").joinToString(",") { raw -> shiftOne(raw.trim(), offsetMs, zone) }
            if (tz != null) "$tz;$moved" else moved
        }
    }

    private fun shiftOne(v: String, offsetMs: Long, zone: ZoneId): String = runCatching {
        when {
            v.length == 8 -> DATE.format(LocalDate.parse(v, DATE).plusDays(Math.floorDiv(offsetMs, DAY_MS)))
            v.endsWith("Z") -> UTC.format(
                Instant.from(UTC.withZone(ZoneOffset.UTC).parse(v)).plusMillis(offsetMs).atOffset(ZoneOffset.UTC),
            )
            else -> LOCAL.format(
                LocalDateTime.parse(v, LOCAL).atZone(zone).toInstant().plusMillis(offsetMs).atZone(zone).toLocalDateTime(),
            )
        }
    }.getOrDefault(v)
}
