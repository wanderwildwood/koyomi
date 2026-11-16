package com.wanderwildwood.davsync.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayCarryLoginTest {

    @Test
    fun `https always carries the login`() {
        assertTrue(mayCarryLogin("https://cloud.example.org/remote.php/dav/"))
        assertTrue(mayCarryLogin("HTTPS://cloud.example.org/"))
    }

    @Test
    fun `plain http on the internet never does`() {
        assertFalse(mayCarryLogin("http://cloud.example.org/remote.php/dav/calendars/ada/"))
        assertFalse(mayCarryLogin("http://203.0.113.7/dav/"))
    }

    @Test
    fun `plain http at home or on a VPN does`() {
        assertTrue(mayCarryLogin("http://192.168.1.10/dav/"))
        assertTrue(mayCarryLogin("http://nextcloud.local/remote.php/dav/"))
        assertTrue(mayCarryLogin("http://box.tail1234.ts.net/dav/"))
    }

    @Test
    fun `anything else does not`() {
        assertFalse(mayCarryLogin("ftp://192.168.1.10/"))
        assertFalse(mayCarryLogin(""))
    }
}
