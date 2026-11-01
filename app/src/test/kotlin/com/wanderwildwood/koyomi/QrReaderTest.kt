package com.wanderwildwood.koyomi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wanderwildwood.koyomi.ui.QrReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scanner's reading step on a made frame: a QR code drawn as the camera's grey plane, with a row padded as a camera pads it. */
class QrReaderTest {
    private fun frame(text: String, padding: Int): Triple<ByteArray, Int, Int> {
        val side = 300
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, mapOf(EncodeHintType.MARGIN to 4))
        val stride = side + padding
        val luma = ByteArray(stride * side) { 0xFF.toByte() }
        for (y in 0 until side) for (x in 0 until side) if (matrix[x, y]) luma[y * stride + x] = 0
        return Triple(luma, stride, side)
    }

    @Test fun readsALinkFromAFrame() {
        val (luma, stride, side) = frame("webcal://example.org/ada.ics?private=Ab12Cd", 0)
        assertEquals("webcal://example.org/ada.ics?private=Ab12Cd", QrReader.decode(luma, stride, side, side))
    }

    @Test fun readsThroughRowPadding() {
        val (luma, stride, side) = frame("https://example.org/tomas.ics", 32)
        assertEquals("https://example.org/tomas.ics", QrReader.decode(luma, stride, side, side))
    }

    @Test fun anEmptyFrameIsNothing() {
        assertNull(QrReader.decode(ByteArray(100 * 100) { 0x80.toByte() }, 100, 100, 100))
    }
}
