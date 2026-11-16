package com.wanderwildwood.koyomi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

class PickerWeekStartTest {

    private val offered = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)

    @Test
    fun everyDaySettingsOffersIsReached() {
        for (base in listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale.forLanguageTag("es-MX"), Locale.ENGLISH)) {
            for (day in offered) {
                val locale = localeStartingWeekOn(base, day)
                assertEquals("$base -> $day", day, WeekFields.of(locale).firstDayOfWeek)
                assertEquals("$base keeps its language", base.language, locale.language)
            }
        }
    }

    @Test
    fun aLocaleThatAlreadyFitsIsKept() {
        assertSame(Locale.US, localeStartingWeekOn(Locale.US, DayOfWeek.SUNDAY))
        assertSame(Locale.GERMANY, localeStartingWeekOn(Locale.GERMANY, DayOfWeek.MONDAY))
    }
}
