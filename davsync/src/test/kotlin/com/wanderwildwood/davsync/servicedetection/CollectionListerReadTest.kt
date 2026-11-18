package com.wanderwildwood.davsync.servicedetection

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.PropStat
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.property.carddav.CardDAV
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import com.wanderwildwood.davsync.model.Collection
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One home set's answers: a book is read, and an answer that cannot be read makes the listing partial. */
class CollectionListerReadTest {

    private val homeSet = Url("https://dav.example.org/books/")

    private fun answer(path: String, status: HttpStatusCode? = null, types: Set<Property.Name>? = setOf(WebDAV.Collection)) =
        Response(
            homeSet,
            Url("https://dav.example.org/books/$path"),
            status,
            listOf(PropStat(listOfNotNull(types?.let { ResourceType(it) }), HttpStatusCode.OK))
        )

    private fun book(path: String) = answer(path, types = setOf(WebDAV.Collection, CardDAV.Addressbook))

    @Test
    fun booksAreReadAndPlainFoldersLeftOut() {
        val found = CollectionLister.read(listOf(answer(""), book("family/"), answer("notes/")), Collection.TYPE_ADDRESSBOOK)
        assertTrue(found.whole)
        assertEquals(listOf(Url("https://dav.example.org/books/family/")), found.collections.map { it.url })
    }

    @Test
    fun aFailedAnswerMakesTheListingPartial() {
        val found = CollectionLister.read(
            listOf(answer(""), book("family/"), answer("choir/", status = HttpStatusCode.InternalServerError)),
            Collection.TYPE_ADDRESSBOOK
        )
        assertFalse(found.whole)
        assertEquals(listOf(Url("https://dav.example.org/books/family/")), found.collections.map { it.url })
    }

    @Test
    fun anAnswerWithoutAResourceTypeMakesTheListingPartial() {
        val found = CollectionLister.read(listOf(book("family/"), answer("choir/", types = null)), Collection.TYPE_ADDRESSBOOK)
        assertFalse(found.whole)
    }
}
