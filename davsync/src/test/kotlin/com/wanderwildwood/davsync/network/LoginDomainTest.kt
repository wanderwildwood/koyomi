package com.wanderwildwood.davsync.network

import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDomainTest {

    @Test
    fun `an ordinary name keeps its last two labels`() {
        assertEquals("icloud.com", LoginDomain.of("p12-caldav.icloud.com"))
        assertEquals("example.org", LoginDomain.of("Cloud.Example.org."))
        assertEquals("example.org", LoginDomain.of("example.org"))
    }

    @Test
    fun `iCloud's sibling hosts still share the login`() {
        assertTrue(LoginDomain.matches("caldav.icloud.com", "p12-caldav.icloud.com"))
    }

    @Test
    fun `a Tailscale name keeps its tailnet`() {
        assertEquals("tail1234.ts.net", LoginDomain.of("box.tail1234.ts.net"))
        assertTrue(LoginDomain.matches("box.tail1234.ts.net", "other.tail1234.ts.net"))
        assertFalse(LoginDomain.matches("box.tail1234.ts.net", "box.tail9999.ts.net"))
        assertFalse(LoginDomain.matches("box.tail1234.ts.net", "ts.net"))
    }

    @Test
    fun `a duckdns name is its own`() {
        assertEquals("me.duckdns.org", LoginDomain.of("me.duckdns.org"))
        assertEquals("me.duckdns.org", LoginDomain.of("dav.me.duckdns.org"))
        assertFalse(LoginDomain.matches("me.duckdns.org", "someone.duckdns.org"))
    }

    @Test
    fun `a co uk name keeps its third label`() {
        assertEquals("example.co.uk", LoginDomain.of("dav.example.co.uk"))
        assertTrue(LoginDomain.matches("example.co.uk", "dav.example.co.uk"))
        assertFalse(LoginDomain.matches("dav.example.co.uk", "dav.another.co.uk"))
    }

    @Test
    fun `an address is matched exactly`() {
        assertEquals("192.168.1.10", LoginDomain.of("192.168.1.10"))
        assertFalse(LoginDomain.matches("192.168.1.10", "192.168.1.11"))
        assertTrue(LoginDomain.matches("[fd7a:115c:a1e0::1]", "fd7a:115c:a1e0::1"))
    }

    @Test
    fun `no server host matches nothing`() {
        assertNull(LoginDomain.of(null))
        assertNull(LoginDomain.of(" "))
        assertFalse(LoginDomain.matches(null, "example.org"))
        assertFalse(LoginDomain.matches("example.org", null))
    }

    private fun requestTo(host: String) = HttpRequestBuilder().apply { url.host = host }

    private val guard = LoginDomainGuard("box.tail1234.ts.net", PreemptiveBasicDigestAuthProvider("ada", "secret"))

    @Test
    fun `the guard gives a neighbor's host no login`() = runBlocking {
        val request = requestTo("box.tail9999.ts.net")
        assertFalse(guard.sendWithoutRequest(request))
        guard.addRequestHeaders(request, null)
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the guard gives the server's own host the login`() = runBlocking {
        val request = requestTo("box.tail1234.ts.net")
        assertTrue(guard.sendWithoutRequest(request))
        guard.addRequestHeaders(request, null)
        assertNotNull(request.headers[HttpHeaders.Authorization])
    }
}
