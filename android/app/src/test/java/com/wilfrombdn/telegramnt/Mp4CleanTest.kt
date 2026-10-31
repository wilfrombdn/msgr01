package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4CleanTest {
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val inner = body.fold(ByteArray(0)) { a, b -> a + b }
        val n = 8 + inner.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + inner
    }

    /** version 0 full box: version+flags, creation time, modification time, then the rest. */
    private fun header(type: String) = box(type, byteArrayOf(0, 0, 0, 0), byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A), byteArrayOf(0x5B, 0x5B, 0x5B, 0x5B), ByteArray(12) { 7 })

    private val gps = "+51.5007-000.1246/".toByteArray()
    private val mdat = box("mdat", ByteArray(64) { (it * 3).toByte() })

    private fun video(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(), ByteArray(4)))
        out.write(box("moov",
            header("mvhd"),
            box("udta", box("©xyz", gps), box("©mod", "Pixel 9".toByteArray())),
            box("meta", "com.apple.quicktime.location.ISO6709".toByteArray(), gps),
            box("trak", header("tkhd"), box("mdia", header("mdhd"), box("hdlr", ByteArray(8))), box("udta", gps)),
        ))
        out.write(mdat)
        return out.toByteArray()
    }

    private fun has(hay: ByteArray, needle: ByteArray) = String(hay, Charsets.ISO_8859_1).contains(String(needle, Charsets.ISO_8859_1))

    @Test fun removesLocationPhoneAndTimesButKeepsTheVideo() {
        val src = video()
        assertTrue(has(src, gps))
        val out = Mp4Clean.strip(src)
        assertEquals("same size, so every offset in the file still points at the right place", src.size, out.size)
        assertFalse("GPS gone", has(out, gps))
        assertFalse("phone model gone", has(out, "Pixel 9".toByteArray()))
        assertFalse("Apple location key gone", has(out, "location".toByteArray()))
        assertFalse("recording times gone", has(out, byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A)) || has(out, byteArrayOf(0x5B, 0x5B, 0x5B, 0x5B)))
        assertTrue("tag boxes became free space", has(out, "free".toByteArray()))
        assertArrayEquals("the picture and sound data are untouched", mdat, out.copyOfRange(out.size - mdat.size, out.size))
        assertTrue("headers still there", has(out, "mvhd".toByteArray()) && has(out, "tkhd".toByteArray()) && has(out, "mdhd".toByteArray()))
        assertTrue("the original isn't changed", has(src, gps))
    }

    @Test fun notAnMp4ComesBackUnchanged() {
        val junk = ByteArray(100) { 0x7F }
        assertArrayEquals(junk, Mp4Clean.strip(junk))
    }
}
