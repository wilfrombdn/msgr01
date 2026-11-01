package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MmsTest {
    @Test
    fun outgoingPictureTextReadsBack() {
        val img = ByteArray(5000) { (it * 7).toByte() }
        val pdu = Mms.sendReq(listOf("+1 (555) 123-4567", "5559876543"), listOf(
            Mms.Part("image/jpeg", img, "image.jpg"),
            Mms.Part("text/plain", "héllo 👋".toByteArray(), "text.txt"),
        ), subject = "Café")
        val p = Mms.parse(pdu)!!
        assertEquals(Mms.SEND_REQ, p.type)
        assertEquals(listOf("+15551234567", "5559876543"), p.to)
        assertEquals("Café", p.subject)
        assertEquals("application/vnd.wap.multipart.related", p.contentType)
        assertEquals(listOf("application/smil", "image/jpeg", "text/plain"), p.parts.map { it.mime })
        assertArrayEquals(img, p.parts[1].data)
        assertEquals("image.jpg", p.parts[1].name)
        assertEquals("image.jpg", p.parts[1].cid)
        assertEquals("héllo 👋", p.parts[2].text)
    }

    @Test
    fun carrierNoticeIsRead() {
        // m-notification-ind as a carrier sends it: type, tid, version, from, class, size, expiry (relative), location
        val b = java.io.ByteArrayOutputStream()
        fun w(vararg x: Int) = x.forEach { b.write(it) }
        fun t(s: String) { b.write(s.toByteArray()); b.write(0) }
        w(0x8C, 0x82); w(0x98); t("abc123"); w(0x8D, 0x92)
        val from = "+15550001111/TYPE=PLMN".toByteArray()
        w(0x89, from.size + 2, 0x80); b.write(from); w(0)
        w(0x8A, 0x80); w(0x8E, 0x02, 0x4E, 0x20)              // size 20000
        w(0x88, 0x05, 0x81, 0x03, 0x01, 0x51, 0x80)           // expiry: in 86400 s
        w(0x83); t("http://mmsc.example/abc123")
        val n = Mms.parse(b.toByteArray())!!
        assertEquals(Mms.NOTIFICATION_IND, n.type)
        assertEquals("abc123", n.tid)
        assertEquals("+15550001111", n.from)
        assertEquals(20000L, n.size)
        assertEquals("http://mmsc.example/abc123", n.location)
        assert(n.expiry > System.currentTimeMillis() / 1000 + 86000)
    }

    @Test
    fun receiptIsWellFormed() {
        val p = Mms.parse(Mms.notifyResp("xyz"))!!
        assertEquals(Mms.NOTIFY_RESP_IND, p.type)
        assertEquals("xyz", p.tid)
    }

    @Test
    fun garbageDoesNotCrash() {
        assertNull(Mms.parse(byteArrayOf()).takeIf { it?.type != 0 })
        for (seed in 0 until 200) {
            val r = java.util.Random(seed.toLong())
            val junk = ByteArray(r.nextInt(300)) { r.nextInt(256).toByte() }
            Mms.parse(junk)   // may be null or nonsense, never a crash
        }
        assertNotNull(Mms.parse(byteArrayOf(0x8C.toByte(), 0x84.toByte())))
    }
}
