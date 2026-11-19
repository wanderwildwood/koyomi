package com.wanderwildwood.koyomi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLinkTest {
    @Test
    fun shownWithoutTheScheme() {
        assertEquals("ada" to "cloud.example.org", HomeLink.shown(HomeAccount.who("https://cloud.example.org/", "ada")))
        assertEquals("ada" to "example.org/nextcloud", HomeLink.shown(HomeAccount.who("https://example.org/nextcloud", "ada")))
    }

    @Test
    fun inUseOnlyWithAStamp() {
        assertEquals(true, HomeLink.Choice("w", "s1", "ada@cloud.example.org").inUse)
        assertEquals(false, HomeLink.Choice("w", "", "ada@cloud.example.org").inUse)
    }
}
