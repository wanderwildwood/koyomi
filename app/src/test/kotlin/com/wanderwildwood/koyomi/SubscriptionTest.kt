package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.CalendarLink
import com.wanderwildwood.koyomi.data.CalendarLink.Result
import com.wanderwildwood.koyomi.data.Fetched
import com.wanderwildwood.koyomi.data.IcsEvent
import com.wanderwildwood.koyomi.data.Refreshed
import com.wanderwildwood.koyomi.data.Subscription
import com.wanderwildwood.koyomi.data.SubscriptionStore
import com.wanderwildwood.koyomi.data.SubscriptionSync
import com.wanderwildwood.koyomi.data.SubscriptionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SubscriptionTest {
    private val phone = ZoneId.of("America/New_York")

    // ---- link normalising

    @Test fun httpsIsKept() {
        assertEquals(Result.Ok("https://example.org/ada.ics"), CalendarLink.normalise("https://example.org/ada.ics"))
    }

    @Test fun webcalBecomesHttps() {
        assertEquals(Result.Ok("https://example.org/ada.ics"), CalendarLink.normalise("webcal://example.org/ada.ics"))
        assertEquals(Result.Ok("https://example.org/ada.ics"), CalendarLink.normalise("WEBCAL://example.org/ada.ics"))
        assertEquals(Result.Ok("https://example.org/ada.ics"), CalendarLink.normalise("webcals://example.org/ada.ics"))
    }

    @Test fun plainHttpIsRefused() {
        assertEquals(Result.PlainHttp, CalendarLink.normalise("http://example.org/ada.ics"))
        assertEquals(Result.PlainHttp, CalendarLink.normalise("HTTP://example.org/ada.ics"))
    }

    @Test fun whitespaceAroundIsDropped() {
        assertEquals(Result.Ok("https://example.org/tomas.ics"), CalendarLink.normalise("  \n https://example.org/tomas.ics \r\n"))
    }

    @Test fun whitespaceInsideIsNotALink() {
        assertEquals(Result.NotALink, CalendarLink.normalise("https://example.org/a b.ics"))
    }

    @Test fun aQueryAndPathSurviveUntouched() {
        val link = "webcal://example.org/cal/feed.ics?private=Ab12Cd&x=1%202#top"
        assertEquals(Result.Ok("https://example.org/cal/feed.ics?private=Ab12Cd&x=1%202#top"), CalendarLink.normalise(link))
    }

    @Test fun otherInputIsRefused() {
        assertEquals(Result.Empty, CalendarLink.normalise("   "))
        assertEquals(Result.NotALink, CalendarLink.normalise("example.org/ada.ics"))
        assertEquals(Result.NotALink, CalendarLink.normalise("ftp://example.org/ada.ics"))
        assertEquals(Result.NotALink, CalendarLink.normalise("https://"))
        assertEquals(Result.NotALink, CalendarLink.normalise("just some words"))
    }

    @Test fun onlyTheHostIsShown() {
        assertEquals("example.org", CalendarLink.hostOf("https://example.org/cal/feed.ics?private=Ab12Cd"))
        assertEquals("example.org:8443", CalendarLink.hostOf("https://ada:pw@example.org:8443/x"))
    }

    // ---- refresh: replace wholesale, failure keeps the old copy

    private fun feed(vararg titles: String) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Ada's rota\r\n")
        titles.forEachIndexed { i, t ->
            append("BEGIN:VEVENT\r\nUID:e$i@example.org\r\nSUMMARY:$t\r\nDTSTART:202611${10 + i}T140000Z\r\nDTEND:202611${10 + i}T150000Z\r\nEND:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    /** A calendar store with one calendar in it, as the real one: a swap replaces everything. */
    private class FakeStore(var titles: List<String> = emptyList(), var accept: Boolean = true) : SubscriptionTarget {
        var swaps = 0
        override fun replaceEvents(calendarId: Long, events: List<IcsEvent>): Boolean {
            swaps++
            if (!accept) return false
            titles = events.map { it.title }
            return true
        }
    }

    private val sub = Subscription(7, "https://example.org/ada.ics", "Ada's rota")

    @Test fun eachFetchReplacesWholesale() {
        val store = FakeStore()
        val first = SubscriptionSync.refresh(sub, { Fetched.Body(feed("Rota A", "Rota B", "Rota C")) }, store, phone, 1000)
        assertTrue(first is Refreshed.Done)
        assertEquals(listOf("Rota A", "Rota B", "Rota C"), store.titles)
        // The next copy has one of them changed and one gone: nothing of the old copy is merged in.
        val second = SubscriptionSync.refresh(sub, { Fetched.Body(feed("Rota A", "Rota Z")) }, store, phone, 2000)
        assertEquals(listOf("Rota A", "Rota Z"), store.titles)
        val done = second as Refreshed.Done
        assertEquals(2, done.events)
        assertEquals(2000L, done.subscription.fetchedAt)
        assertNull(done.subscription.error)
    }

    @Test fun anEmptyCalendarEmptiesTheCopy() {
        val store = FakeStore(listOf("Old"))
        val out = SubscriptionSync.refresh(sub, { Fetched.Body(feed()) }, store, phone, 5)
        assertTrue(out is Refreshed.Done)
        assertTrue(store.titles.isEmpty())
    }

    @Test fun aFailedFetchKeepsTheOldCopyAndTheServersWords() {
        val store = FakeStore(listOf("Old"))
        val out = SubscriptionSync.refresh(
            sub.copy(fetchedAt = 900),
            { Fetched.Failed(Fetched.Kind.SERVER, "403 Forbidden: This calendar is private") },
            store, phone, 5000,
        )
        val kept = out as Refreshed.Kept
        assertEquals(0, store.swaps)
        assertEquals(listOf("Old"), store.titles)
        assertEquals("403 Forbidden: This calendar is private", kept.subscription.error)
        assertEquals(900L, kept.subscription.fetchedAt)
    }

    @Test fun aFetcherThatThrowsKeepsTheOldCopy() {
        val store = FakeStore(listOf("Old"))
        val out = SubscriptionSync.refresh(sub, { error("boom") }, store, phone, 5)
        assertTrue(out is Refreshed.Kept)
        assertEquals(listOf("Old"), store.titles)
    }

    @Test fun aLoginPageIsNotACalendarAndKeepsTheOldCopy() {
        val store = FakeStore(listOf("Old"))
        val out = SubscriptionSync.refresh(sub, { Fetched.Body("<html><body>Sign in</body></html>") }, store, phone, 5, notACalendar = "not a calendar")
        assertEquals("not a calendar", (out as Refreshed.Kept).subscription.error)
        assertEquals(0, store.swaps)
        assertEquals(listOf("Old"), store.titles)
    }

    @Test fun aStoreThatRefusesLeavesTheCopyAndSaysSo() {
        val store = FakeStore(listOf("Old"), accept = false)
        val out = SubscriptionSync.refresh(sub, { Fetched.Body(feed("New")) }, store, phone, 5, notSaved = "not saved")
        assertEquals("not saved", (out as Refreshed.Kept).subscription.error)
        assertEquals(listOf("Old"), store.titles)
    }

    @Test fun aGoodFetchClearsAnEarlierError() {
        val store = FakeStore()
        val out = SubscriptionSync.refresh(sub.copy(error = "403"), { Fetched.Body(feed("A")) }, store, phone, 5)
        assertNull((out as Refreshed.Done).subscription.error)
    }

    // ---- the server's words, never the address

    @Test fun describeUsesStatusAndTheStartOfTheReply() {
        assertEquals("404 Not Found", SubscriptionSync.describe(404, "Not Found", ""))
        assertEquals("404 Not Found", SubscriptionSync.describe(404, "Not Found", "Not Found"))
        assertEquals("403 Forbidden: Calendar is private", SubscriptionSync.describe(403, "Forbidden", "<html><p>Calendar</p>\n <p>is private</p></html>"))
        assertTrue(SubscriptionSync.describe(500, null, "x".repeat(5000)).length < 260)
    }

    @Test fun theLinkIsStoredButNeverLeaksIntoTheErrorLine() {
        val s = Subscription(3, "https://example.org/tomas.ics?k=s3cret", "Tomas", 10, "403 Forbidden")
        val line = SubscriptionStore.encode(s)
        assertEquals(s, SubscriptionStore.decode(3, line))
        assertFalse(SubscriptionSync.describe(404, "Not Found", null).contains("s3cret"))
    }

    @Test fun aDamagedLineIsSkipped() {
        assertNull(SubscriptionStore.decode(1, "just one field"))
    }
}
