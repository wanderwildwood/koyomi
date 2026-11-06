package com.wanderwildwood.davsync.servicedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMakerTest {
    @Test
    fun colorIsWrittenRgbThenAlpha() {
        assertEquals("#0082C9FF", CollectionMaker.colorHex(0xFF0082C9.toInt()))
    }

    @Test
    fun calendarBodyNamesItAndAsksForEvents() {
        val xml = CollectionMaker.calendarXml("Garden & Bees", 0xFF0082C9.toInt())
        assertTrue(xml.contains("<D:displayname>Garden &amp; Bees</D:displayname>"))
        assertTrue(xml.contains("<A:calendar-color>#0082C9FF</A:calendar-color>"))
        assertTrue(xml.contains("<C:comp name=\"VEVENT\"/>"))
        assertTrue(xml.contains("<C:mkcalendar"))
    }

    @Test
    fun addressBookBodyIsAnExtendedMkcol() {
        val xml = CollectionMaker.addressBookXml("Choir <2026>")
        assertTrue(xml.contains("<CARD:addressbook/>"))
        assertTrue(xml.contains("<D:displayname>Choir &lt;2026&gt;</D:displayname>"))
    }
}
