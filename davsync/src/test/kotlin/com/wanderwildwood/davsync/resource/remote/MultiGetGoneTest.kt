package com.wanderwildwood.davsync.resource.remote

import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A member the server lists but answers 404/410 on multi-get is handed on, not silently skipped. */
class MultiGetGoneTest {

    private val collection = Url("https://dav.example.com/cal/garden/")

    private fun item(name: String, status: HttpStatusCode?) = MultiStatusItem.Response(
        Response(collection, Url("https://dav.example.com/cal/garden/$name"), status, emptyList()),
        Response.HrefRelation.MEMBER
    )

    @Test
    fun goneAndNotFoundAreHandedOnAndDropped() = runBlocking {
        val gone = mutableListOf<String>()
        val kept = flowOf(
            item("a.ics", HttpStatusCode.OK),
            item("split.ics", HttpStatusCode.NotFound),
            item("old.ics", HttpStatusCode.Gone),
            item("b.ics", null),
        ).filterSuccessful { href -> gone += href.toString() }
            .map { it.response.href.toString() }
            .toList()
        assertEquals(listOf("https://dav.example.com/cal/garden/a.ics", "https://dav.example.com/cal/garden/b.ics"), kept)
        assertEquals(listOf("https://dav.example.com/cal/garden/split.ics", "https://dav.example.com/cal/garden/old.ics"), gone)
    }

    @Test
    fun otherFailuresAreOnlySkipped() = runBlocking {
        val gone = mutableListOf<Url>()
        val kept = flowOf(
            item("busy.ics", HttpStatusCode.ServiceUnavailable),
            item("locked.ics", HttpStatusCode.Forbidden),
        ).filterSuccessful { gone += it }.toList()
        assertEquals(0, kept.size)
        assertEquals(0, gone.size)
    }
}
