package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.repeat.RepeatRule
import com.wanderwildwood.koyomi.repeat.RepeatRule.End
import com.wanderwildwood.koyomi.repeat.RepeatRule.Freq
import com.wanderwildwood.koyomi.repeat.RepeatRule.Monthly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RepeatRuleTest {
    private val friday = LocalDate.of(2026, 9, 25) // the fourth, and last, Friday of September

    private fun roundTrip(rule: RepeatRule, start: LocalDate = friday, allDay: Boolean = false): RepeatRule {
        val rrule = rule.toRrule(start, allDay, DayOfWeek.MONDAY)
        val back = RepeatRule.from(rrule, start, allDay)
        assertNotNull("could not read back $rrule", back)
        return back!!
    }

    @Test fun everyTenDays() {
        val r = RepeatRule.fresh(Freq.DAILY, friday).copy(interval = 10)
        val rrule = r.toRrule(friday, false, DayOfWeek.MONDAY)
        assertTrue(rrule, rrule.contains("FREQ=DAILY"))
        assertTrue(rrule, rrule.contains("INTERVAL=10"))
        assertEquals(10, roundTrip(r).interval)
    }

    @Test fun weeklyOnDays() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val r = RepeatRule.fresh(Freq.WEEKLY, friday).copy(weekdays = days)
        val rrule = r.toRrule(friday, false, DayOfWeek.MONDAY)
        assertTrue(rrule, rrule.contains("BYDAY=MO,WE,FR"))
        assertEquals(days, roundTrip(r).weekdays)
    }

    @Test fun monthlyByDateAndByNthWeekday() {
        val byDate = RepeatRule.fresh(Freq.MONTHLY, friday)
        assertTrue(byDate.toRrule(friday, false, DayOfWeek.MONDAY).contains("BYMONTHDAY=25"))
        assertEquals(Monthly.BY_DATE, roundTrip(byDate).monthly)

        val fourth = byDate.copy(monthly = Monthly.BY_WEEKDAY, nth = 4)
        val rrule = fourth.toRrule(friday, false, DayOfWeek.MONDAY)
        assertTrue(rrule, rrule.contains("BYDAY=4FR"))
        val back = roundTrip(fourth)
        assertEquals(Monthly.BY_WEEKDAY, back.monthly)
        assertEquals(4, back.nth)

        val last = byDate.copy(monthly = Monthly.BY_WEEKDAY, nth = -1)
        assertTrue(last.toRrule(friday, false, DayOfWeek.MONDAY).contains("BYDAY=-1FR"))
        assertEquals(-1, roundTrip(last).nth)
    }

    @Test fun endsAfterCountOrOnDate() {
        val counted = RepeatRule.fresh(Freq.WEEKLY, friday).copy(end = End.COUNT, count = 6)
        assertTrue(counted.toRrule(friday, false, DayOfWeek.MONDAY).contains("COUNT=6"))
        assertEquals(6, roundTrip(counted).count)

        val until = LocalDate.of(2026, 12, 1)
        val dated = RepeatRule.fresh(Freq.WEEKLY, friday).copy(end = End.DATE, until = until)
        val rrule = dated.toRrule(friday, false, DayOfWeek.MONDAY)
        assertTrue(rrule, rrule.contains("UNTIL="))
        assertFalse(rrule, rrule.contains("COUNT"))
        assertEquals(until, roundTrip(dated).until)
        // An all-day series ends at the UTC midnight of its last date.
        val allDay = dated.toRrule(friday, true, DayOfWeek.MONDAY)
        assertTrue(allDay, allDay.contains("UNTIL=20261201T000000Z"))
        assertEquals(until, roundTrip(dated, allDay = true).until)
    }

    @Test fun rulesFromElsewhereThatCannotBeEditedAreLeftAlone() {
        assertFalse(RepeatRule.canEdit("FREQ=MONTHLY;BYMONTHDAY=1,15"))
        assertFalse(RepeatRule.canEdit("FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1"))
        assertFalse(RepeatRule.canEdit("FREQ=HOURLY"))
        assertFalse(RepeatRule.canEdit("FREQ=MONTHLY;BYDAY=FR"))
        assertNull(RepeatRule.from("FREQ=MONTHLY;BYMONTHDAY=1,15", friday, false))
        assertTrue(RepeatRule.canEdit("FREQ=WEEKLY;WKST=MO;BYDAY=TU,TH"))
        assertTrue(RepeatRule.canEdit("FREQ=YEARLY"))
    }

    @Test fun nthOfTheMonth() {
        assertEquals(4, RepeatRule.nthOf(friday))
        assertTrue(RepeatRule.isLastOfMonth(friday))
        assertFalse(RepeatRule.isLastOfMonth(LocalDate.of(2026, 9, 18)))
    }
}
