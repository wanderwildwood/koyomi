package com.wanderwildwood.davsync.sync

import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A wrong phone clock is told as a clock problem; a rejected certificate is never a quiet success. */
class SyncExceptionHandlerClockTest {

    private val handler = SyncExceptionHandler(Logger.getLogger("test"))

    private fun handshake(cause: Throwable) = SSLHandshakeException("handshake").apply { initCause(cause) }

    @Test
    fun certificateNotYetValidIsTheClock() {
        val e = handshake(CertificateException("chain", CertificateNotYetValidException("not valid before")))
        assertTrue(handler.classifySyncException(e) is SyncExceptionHandler.SyncErrorAction.ClockError)
    }

    @Test
    fun certificateExpiredIsTheClock() {
        val e = handshake(CertificateException(CertificateExpiredException("expired")))
        assertTrue(handler.classifySyncException(e) is SyncExceptionHandler.SyncErrorAction.ClockError)
    }

    @Test
    fun otherRejectedCertificateIsAFailureNotASuccess() {
        val e = handshake(CertificateException("Trust anchor for certification path not found."))
        assertTrue(handler.classifySyncException(e) is SyncExceptionHandler.SyncErrorAction.HardError)
    }

    @Test
    fun noNetworkIsNotTheClock() {
        assertFalse(SyncExceptionHandler.isClockProblem(IOException("no route")))
        assertTrue(handler.classifySyncException(IOException("no route")) is SyncExceptionHandler.SyncErrorAction.SoftError)
    }
}
