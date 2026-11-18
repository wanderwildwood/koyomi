package com.wanderwildwood.davsync.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A card's photo is fetched only from the server the card came from, and only up to a size:
 * a PHOTO naming another host is never asked for.
 */
class ResourceRetrieverTest {

    private val logger = Logger.getLogger("ResourceRetrieverTest")

    /** Serves one connection with [body], and returns how many requests were heard. */
    private fun serveOnce(socket: ServerSocket, body: ByteArray): Pair<Thread, IntArray> {
        val heard = IntArray(1)
        socket.soTimeout = 3000
        val thread = Thread {
            try {
                socket.accept().use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.count()
                    heard[0]++
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    runCatching { out.write(body) }
                }
            } catch (_: SocketTimeoutException) {
            }
        }.apply { start() }
        return thread to heard
    }

    @Test
    fun aPhotoOnTheAccountsServerIsFetched() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val photo = byteArrayOf(1, 2, 3, 4)
            val (thread, heard) = serveOnce(socket, photo)
            val got = HttpClient(OkHttp).use { client ->
                runBlocking {
                    ResourceRetriever(client, logger, "127.0.0.1").retrieve("http://127.0.0.1:${socket.localPort}/photo.jpg")
                }
            }
            thread.join(5000)
            assertEquals(1, heard[0])
            assertArrayEquals(photo, got)
        }
    }

    @Test
    fun aPhotoOnAnotherHostIsNeverAskedFor() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val (thread, heard) = serveOnce(socket, byteArrayOf(1))
            val got = HttpClient(OkHttp).use { client ->
                runBlocking {
                    // "localhost" reaches the same socket, but it is not the account's host.
                    ResourceRetriever(client, logger, "127.0.0.1").retrieve("http://localhost:${socket.localPort}/photo.jpg")
                }
            }
            thread.join(5000)
            assertEquals(0, heard[0])
            assertNull(got)
        }
    }

    @Test
    fun anOversizedPhotoIsDropped() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            val (thread, _) = serveOnce(socket, ByteArray((ResourceRetriever.MAX_BYTES + 1024).toInt()))
            val got = HttpClient(OkHttp).use { client ->
                runBlocking {
                    ResourceRetriever(client, logger, "127.0.0.1").retrieve("http://127.0.0.1:${socket.localPort}/photo.jpg")
                }
            }
            thread.join(5000)
            assertNull(got)
        }
    }

    @Test
    fun aDataUriIsReadInPlace() {
        val got = HttpClient(OkHttp).use { client ->
            runBlocking { ResourceRetriever(client, logger, "cloud.invalid").retrieve("data:image/jpeg;base64,AQID") }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), got)
    }
}
