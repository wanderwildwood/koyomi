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

    /**
     * Every exclusion moved by [change], in the form it was written in, so a series moved
     * from 19:00 to 18:30 still leaves out the evenings it left out before. The move is made on
     * the clock: a zoned or floating time moves on its own clock, a UTC one on the series'
     * clock [zone], so a move across a change of the clocks keeps lining up with the occurrences.
     */
    fun shift(exdate: String?, change: SeriesShift, zone: ZoneId): String? {
        if (exdate.isNullOrBlank() || change.isNone) return exdate
        return exdate.split("\n").joinToString("\n") { line ->
            val semi = line.indexOf(';')
            val tz = if (semi >= 0) line.substring(0, semi) else null
            val list = if (semi >= 0) line.substring(semi + 1) else line
            val moved = list.split(",").joinToString(",") { raw -> shiftOne(raw.trim(), change, zone) }
            if (tz != null) "$tz;$moved" else moved
        }
    }

    private fun shiftOne(v: String, change: SeriesShift, zone: ZoneId): String = runCatching {
        when {
            v.length == 8 -> DATE.format(LocalDate.parse(v, DATE).plusDays(change.days))
            v.endsWith("Z") -> UTC.format(
                change.apply(Instant.from(UTC.withZone(ZoneOffset.UTC).parse(v)).atZone(zone).toLocalDateTime())
                    .atZone(zone).toInstant().atOffset(ZoneOffset.UTC),
            )
            else -> LOCAL.format(change.apply(LocalDateTime.parse(v, LOCAL)))
        }
    }.getOrDefault(v)
}
