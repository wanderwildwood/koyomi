package com.wanderwildwood.koyomi.data

import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalTime
import java.util.Properties

/**
 * An event being written, kept through the system stopping the app in the background: the
 * draft, the event it changes ([recordId], none for a new one), the occurrence it was opened on
 * and for which occurrences, and whether the repeat screen was open over it. It is kept as text,
 * one value to a line, in the activity's saved state.
 */
data class EditorState(
    val draft: Draft,
    val recordId: Long?,
    val begin: Long,
    val end: Long,
    val scope: Scope,
    val repeat: Boolean,
) {
    fun write(): String {
        val p = Properties()
        fun put(key: String, value: Any?) {
            if (value != null) p.setProperty(key, value.toString())
        }
        put("title", draft.title)
        put("location", draft.location)
        put("notes", draft.notes)
        put("calendar", draft.calendarId)
        put("allDay", draft.allDay)
        put("startDate", draft.startDate)
        put("startTime", draft.startTime)
        put("endDate", draft.endDate)
        put("endTime", draft.endTime)
        put("rrule", draft.rrule)
        put("reminders", draft.reminders.joinToString(","))
        put("appPackage", draft.appPackage)
        put("appUri", draft.appUri)
        put("record", recordId)
        put("begin", begin)
        put("end", end)
        put("scope", scope.name)
        put("repeat", repeat)
        return StringWriter().also { p.store(it, null) }.toString()
    }

    companion object {
        /** The state [write] wrote, or null for anything else. */
        fun read(text: String): EditorState? = runCatching {
            val p = Properties().apply { load(StringReader(text)) }
            fun get(key: String): String? = p.getProperty(key)
            val blank = Draft()
            EditorState(
                draft = Draft(
                    title = get("title").orEmpty(),
                    location = get("location").orEmpty(),
                    notes = get("notes").orEmpty(),
                    calendarId = get("calendar")?.toLong(),
                    allDay = get("allDay").toBoolean(),
                    startDate = get("startDate")?.let(LocalDate::parse) ?: blank.startDate,
                    startTime = get("startTime")?.let(LocalTime::parse) ?: blank.startTime,
                    endDate = get("endDate")?.let(LocalDate::parse) ?: blank.endDate,
                    endTime = get("endTime")?.let(LocalTime::parse) ?: blank.endTime,
                    rrule = get("rrule"),
                    reminders = get("reminders").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
                    appPackage = get("appPackage"),
                    appUri = get("appUri"),
                ),
                recordId = get("record")?.toLong(),
                begin = get("begin")!!.toLong(),
                end = get("end")!!.toLong(),
                scope = Scope.valueOf(get("scope")!!),
                repeat = get("repeat").toBoolean(),
            )
        }.getOrNull()
    }
}
