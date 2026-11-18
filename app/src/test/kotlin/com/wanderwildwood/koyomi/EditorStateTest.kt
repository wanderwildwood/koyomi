package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.Draft
import com.wanderwildwood.koyomi.data.EditorState
import com.wanderwildwood.koyomi.data.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EditorStateTest {

    @Test fun anEventHalfWrittenComesBackAsItWas() {
        val draft = Draft(
            title = "Lunch with Ada Whitlock = \\ and Tomas",
            location = "Café Örtlich, 12 Elm Row",
            notes = "Bring the map.\nSecond line: #not a comment",
            calendarId = 4,
            allDay = false,
            startDate = LocalDate.of(2026, 11, 20),
            startTime = LocalTime.of(12, 30),
            endDate = LocalDate.of(2026, 11, 20),
            endTime = LocalTime.of(13, 45),
            rrule = "FREQ=WEEKLY;BYDAY=FR",
            reminders = listOf(10, 60),
            appPackage = "com.example.wallet",
            appUri = "wallet://card/7",
        )
        val states = listOf(
            EditorState(draft, recordId = 42, begin = 1_795_000_000_000, end = 1_795_004_500_000, scope = Scope.ONE, repeat = true),
            EditorState(Draft(title = "", startDate = LocalDate.of(2026, 1, 1), endDate = LocalDate.of(2026, 1, 1)), null, 0, 0, Scope.ALL, false),
            EditorState(draft.copy(allDay = true, rrule = null, reminders = emptyList(), calendarId = null, appPackage = null, appUri = null), 7, 1, 2, Scope.FOLLOWING, false),
        )
        for (s in states) assertEquals(s, EditorState.read(s.write()))
    }

    @Test fun anythingElseReadsAsNothing() {
        assertNull(EditorState.read(""))
        assertNull(EditorState.read("title=only a title"))
    }
}
