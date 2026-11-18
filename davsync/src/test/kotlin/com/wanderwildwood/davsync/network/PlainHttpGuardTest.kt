package com.wanderwildwood.davsync.network

import com.wanderwildwood.davsync.signin.Problem
import com.wanderwildwood.davsync.signin.SignIn
import io.ktor.client.request.get
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An account's client sends nothing over plain http to a host away from home: the request stops
 * before it is made, with [PlainHttpRefused], and sign-in can tell that reason. A host at home is
 * still asked, and handed the login once it asks for it. Sign-in tells it as its own reason. (".invalid" never resolves: had the request been made, it would
 * have failed as an unknown host instead.)
 */
class PlainHttpGuardTest {

    private val credentials = Credentials("ada", "app-password-for-tests")

    @Test
    fun plainHttpAwayFromHomeIsNotAsked() {
        val client = HttpClientBuilder.build("test", credentials, "cloud.invalid")
        val e = runCatching { runBlocking { client.get("http://cloud.invalid/remote.php/dav/") } }.exceptionOrNull()
        assertTrue(e.toString(), e is PlainHttpRefused)
    }

    @Test
    fun aClientWithoutALoginIsNotHeldBack() {
        val client = HttpClientBuilder.build("test", null, null)
        val e = runCatching { runBlocking { client.get("http://cloud.invalid/") } }.exceptionOrNull()
        assertTrue(e.toString(), e != null && e !is PlainHttpRefused)
    }

    @Test
    fun signInTellsItAsAPlainHttpAddress() {
        // The full path (the finder's PROPFIND) needs Android's XML parser: seen on the emulator.
        assertEquals(Problem.PLAIN_HTTP_LINK, SignIn.problemFor(PlainHttpRefused("cloud.invalid")))
    }

    /** Answers each connection on [socket] in turn with [answers], and returns the request heads heard. */
    private fun serve(socket: ServerSocket, vararg answers: String): Pair<Thread, MutableList<String>> {
        val heard = mutableListOf<String>()
        val thread = Thread {
            for (answer in answers) {
                runCatching {
                    socket.accept().use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        heard += generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                        c.getOutputStream().write(answer.toByteArray())
                    }
                }
            }
        }.apply { start() }
        return thread to heard
    }

    private val challenge =
        "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"dav\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    private val ok = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    @Test
    fun plainHttpAtHomeSendsTheLoginOnlyWhenAsked() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { socket ->
            val (answer, heard) = serve(socket, challenge, ok)
            val client = HttpClientBuilder.build("test", credentials, "127.0.0.1")
            runCatching { runBlocking { client.get("http://127.0.0.1:${socket.localPort}/remote.php/dav/") } }
            answer.join(5000)
            assertEquals(heard.toString(), 2, heard.size)
            assertTrue(heard[0], heard[0].startsWith("GET /remote.php/dav/") && !heard[0].contains("Authorization"))
            assertTrue(heard[1], heard[1].startsWith("GET /remote.php/dav/") && heard[1].contains("Authorization: Basic "))
        }
    }

    @Test
    fun plainHttpAtHomeThatNeverAsksIsNeverHandedTheLogin() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val (answer, heard) = serve(socket, ok)
            val client = HttpClientBuilder.build("test", credentials, "127.0.0.1")
            runCatching { runBlocking { client.get("http://127.0.0.1:${socket.localPort}/remote.php/dav/") } }
            answer.join(5000)
            assertEquals(heard.toString(), 1, heard.size)
            assertTrue(heard[0], !heard[0].contains("Authorization"))
        }
    }
}
