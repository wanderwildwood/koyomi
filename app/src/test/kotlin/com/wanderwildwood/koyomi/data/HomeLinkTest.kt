package com.wanderwildwood.koyomi.data

import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLinkTest {
    @Test
    fun shownWithoutTheScheme() {
        assertEquals("ada" to "cloud.example.org", HomeLink.shown(HomeAccount.who("https://cloud.example.org/", "ada")))
        assertEquals("ada" to "example.org/nextcloud", HomeLink.shown(HomeAccount.who("https://example.org/nextcloud", "ada")))
        // Without the scheme, http or https; a port that is not the default stays.
        assertEquals("ada" to "cloud.example.org", HomeLink.shown(HomeAccount.who("https://Cloud.Example.org:443", "ada")))
        assertEquals("ada" to "10.0.2.2:8080", HomeLink.shown(HomeAccount.who("http://10.0.2.2:8080/", "ada")))
        assertEquals("ada" to "cloud.example.org:8443", HomeLink.shown(HomeAccount.who("https://cloud.example.org:8443", "ada")))
    }

    @Test
    fun inUseOnlyWithAStamp() {
        assertEquals(true, HomeLink.Choice("w", "s1", "ada@cloud.example.org").inUse)
        assertEquals(false, HomeLink.Choice("w", "", "ada@cloud.example.org").inUse)
    }

    @Test
    fun sameServerByHostAndPort() {
        assertEquals(true, HomeLink.sameServer("https://cloud.example.org/remote.php/dav", "https://cloud.example.org"))
        assertEquals(true, HomeLink.sameServer("https://Cloud.Example.org:443/nextcloud/", "https://cloud.example.org"))
        assertEquals(false, HomeLink.sameServer("https://caldav.icloud.com/", "https://cloud.example.org"))
        assertEquals(false, HomeLink.sameServer("https://cloud.example.org:8443/", "https://cloud.example.org"))
        assertEquals(false, HomeLink.sameServer("not a url", "https://cloud.example.org"))
    }

    @Test
    fun homesAccountIsNamedWithOneAtAndThePortWhenNotUsual() {
        assertEquals("ada@cloud.example.org", HomeLink.accountName("ada", Url("https://cloud.example.org/remote.php/dav/")))
        assertEquals("ada@cloud.example.org", HomeLink.accountName("ada", Url("https://cloud.example.org:443/nextcloud/")))
        assertEquals("ada@10.0.2.2:8080", HomeLink.accountName("ada", Url("http://10.0.2.2:8080/remote.php/dav/")))
        assertEquals("ada@nas.local", HomeLink.accountName("ada", Url("http://nas.local/")))
        assertEquals("ada@example.org on 10.0.2.2:8080", HomeLink.accountName("ada@example.org", Url("http://10.0.2.2:8080/")))
        assertEquals("ada@example.org on cloud.example.org", HomeLink.accountName("ada@example.org", Url("https://cloud.example.org/")))
    }
}
