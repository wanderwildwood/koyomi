package com.wanderwildwood.davsync.accounts

import com.wanderwildwood.davsync.model.Collection
import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a fresh listing from the server does to the address books already known. */
class RefreshedCollectionsTest {
    private fun book(path: String, id: Long = 0, sync: Boolean = false) =
        Collection(id = id, type = Collection.TYPE_ADDRESSBOOK, url = Url("https://dav.example.org/$path/"), sync = sync)

    private val family = book("family", id = 1, sync = true)
    private val choir = book("choir", id = 2, sync = true)

    @Test
    fun aWholeListingDropsABookTheServerNoLongerHas() {
        val list = AccountStore.refreshed(listOf(family, choir), listOf(book("family")), complete = true)
        assertEquals(listOf(family), list)
    }

    @Test
    fun aPartialListingKeepsABookItDidNotReach() {
        val list = AccountStore.refreshed(listOf(family, choir), listOf(book("family"), book("garden")), complete = false)
        assertEquals(listOf(family, book("garden", id = 3), choir), list)
    }

    @Test
    fun anEmptyListingChangesNothing() {
        assertEquals(listOf(family, choir), AccountStore.refreshed(listOf(family, choir), emptyList(), complete = true))
    }
}
