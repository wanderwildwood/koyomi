package com.wanderwildwood.koyomi.ui

import com.wanderwildwood.koyomi.data.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackNavTest {
    @Test fun dayWeekAndAgendaGoBackToMonth() {
        assertEquals(View.MONTH, backTarget(View.DAY))
        assertEquals(View.MONTH, backTarget(View.WEEK))
        assertEquals(View.MONTH, backTarget(View.AGENDA))
    }

    @Test fun monthLeavesTheApp() {
        assertNull(backTarget(View.MONTH))
    }
}
