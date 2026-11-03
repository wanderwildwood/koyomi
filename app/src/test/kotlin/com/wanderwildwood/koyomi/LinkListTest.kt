package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.data.LinkList
import com.wanderwildwood.koyomi.data.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkListTest {

    private val followed = listOf(
        Subscription(11, "https://calendar.example.org/ical/ada-whitlock/private-0a1b2c/basic.ics", "Ada Whitlock", fetchedAt = 5, error = null),
        Subscription(12, "https://outlook.example.com/owa/calendar/tomas-reyes/reachcalendar.ics", "Tomas\tReyes\nwork", fetchedAt = 0, error = "404"),
    )

    private fun refusedAs(kind: LinkList.Refused.Kind, text: String) {
        try {
            LinkList.read(text)
            fail("expected $kind")
        } catch (e: LinkList.Refused) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun theFollowedLinksComeBackWithTheirNames() {
        val text = LinkList.write(followed)
        assertTrue(text.startsWith("datebook-calendar-links 1\n"))
        assertEquals(
            listOf(
                LinkList.Link("Ada Whitlock", followed[0].url),
                LinkList.Link("Tomas Reyes work", followed[1].url),
            ),
            LinkList.read(text),
        )
    }

    @Test
    fun onlyLinksNotFollowedYetAreAddedAndEachOnce() {
        val links = listOf(
            LinkList.Link("Ada Whitlock", followed[0].url),
            LinkList.Link("Choir", "https://calendar.example.org/choir.ics"),
            LinkList.Link("Choir again", "https://calendar.example.org/choir.ics"),
        )
        assertEquals(listOf(links[1]), LinkList.notYetFollowed(links, followed))
        assertEquals(listOf(links[0], links[1]), LinkList.notYetFollowed(links, emptyList()))
    }

    @Test
    fun webcalIsReadAsHttpsAndWindowsLineEndsAreFine() {
        val text = "\uFEFFdatebook-calendar-links 1\r\n# comment\r\nChoir\twebcal://calendar.example.org/choir.ics\r\n\r\n"
        assertEquals(listOf(LinkList.Link("Choir", "https://calendar.example.org/choir.ics")), LinkList.read(text))
    }

    @Test
    fun aWrongNewerOrBrokenFileIsRefusedWhole() {
        refusedAs(LinkList.Refused.Kind.NOT_A_BACKUP, "BEGIN:VCALENDAR\nEND:VCALENDAR\n")
        refusedAs(LinkList.Refused.Kind.NOT_A_BACKUP, "")
        refusedAs(LinkList.Refused.Kind.NEWER, "datebook-calendar-links 2\nChoir\thttps://calendar.example.org/choir.ics\n")
        // One good line and one bad: nothing is taken.
        refusedAs(LinkList.Refused.Kind.DAMAGED, "datebook-calendar-links 1\nChoir\thttps://calendar.example.org/choir.ics\nGym\thttp://insecure.example.org/gym.ics\n")
        refusedAs(LinkList.Refused.Kind.DAMAGED, "datebook-calendar-links 1\nno tab here https://calendar.example.org/x.ics\n")
        refusedAs(LinkList.Refused.Kind.DAMAGED, "datebook-calendar-links 1\n\thttps://calendar.example.org/x.ics\n")
    }

    @Test
    fun anEmptyListIsStillAList() {
        assertEquals(emptyList<LinkList.Link>(), LinkList.read(LinkList.write(emptyList())))
    }
}
