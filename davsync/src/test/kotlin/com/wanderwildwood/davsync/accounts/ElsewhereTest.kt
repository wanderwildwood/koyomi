package com.wanderwildwood.davsync.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElsewhereTest {

    @Test
    fun davx5AndMuditasBuildAreBothLookedAt() {
        assertTrue("at.bitfire.davdroid.address_book" in Elsewhere.DAVX5_ADDRESS_BOOKS)
        assertTrue("at.bitfire.davdroid.address_book.mudita" in Elsewhere.DAVX5_ADDRESS_BOOKS)
    }

    @Test
    fun theTitleIsTakenFromTheAccountName() {
        assertEquals("choir", Elsewhere.bookTitle("Choir (ada@dav.example.com) #2"))
        assertEquals("family (old)", Elsewhere.bookTitle("Family (old) (ada@dav.example.com)"))
    }
}
