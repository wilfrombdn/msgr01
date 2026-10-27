package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun person() = Crypto.newKeyset(Crypto.ENC_TEMPLATE) to Crypto.newKeyset(Crypto.SIG_TEMPLATE)

    @Test fun roundTripAndSignature() {
        val (aEnc, aSig) = person()
        val (bEnc, _) = person()
        val bEncPub = Crypto.parsePublic(Crypto.publicBytes(bEnc), Crypto.ENC_TEMPLATE)!!
        val aSigPub = Crypto.parsePublic(Crypto.publicBytes(aSig), Crypto.SIG_TEMPLATE)!!
        val addr = Crypto.addressOf(Crypto.randomHex())

        val blob = Crypto.seal(aSig, bEncPub, addr, "hello".toByteArray())
        val o = Crypto.open(bEnc, addr, blob)
        assertNotNull(o)
        assertArrayEquals("hello".toByteArray(), o!!.inner)
        assertTrue(Crypto.verify(aSigPub, o))
    }

    @Test fun wrongMailboxOrKeyOrSignerFails() {
        val (aEnc, aSig) = person()
        val (bEnc, bSig) = person()
        val addr = Crypto.addressOf(Crypto.randomHex())
        val blob = Crypto.seal(aSig, bEnc.publicKeysetHandle, addr, "x".toByteArray())

        assertNull(Crypto.open(bEnc, Crypto.addressOf(Crypto.randomHex()), blob)) // moved mailbox
        assertNull(Crypto.open(aEnc, addr, blob))                                   // wrong reader
        val o = Crypto.open(bEnc, addr, blob)!!
        assertFalse(Crypto.verify(bSig.publicKeysetHandle, o))                      // wrong signer
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(Crypto.open(bEnc, addr, blob))                                   // tampered
    }

    @Test fun paddingHidesLength() {
        val (_, aSig) = person()
        val (bEnc, _) = person()
        val addr = Crypto.addressOf(Crypto.randomHex())
        val short = Crypto.seal(aSig, bEnc.publicKeysetHandle, addr, "k".toByteArray())
        val long = Crypto.seal(aSig, bEnc.publicKeysetHandle, addr, ByteArray(400) { 'a'.code.toByte() })
        assertEquals(short.size, long.size)
    }

    @Test fun rejectsWrongKeyKinds() {
        val (enc, sig) = person()
        assertNull(Crypto.parsePublic(Crypto.publicBytes(enc), Crypto.SIG_TEMPLATE))
        assertNull(Crypto.parsePublic(Crypto.publicBytes(sig), Crypto.ENC_TEMPLATE))
        assertNull(Crypto.parsePublic(byteArrayOf(1, 2, 3), Crypto.ENC_TEMPLATE))
    }

    @Test fun safetyCodeIsSymmetric() {
        val a = Crypto.publicBytes(person().second)
        val b = Crypto.publicBytes(person().second)
        val code = Crypto.safetyCode(a, b)
        assertEquals(code, Crypto.safetyCode(b, a))
        assertEquals(35, code.length)
    }

    /** Live check of the published update manifest + APK (set UPDATE_CHECK=1; goes direct, not via Tor). */
    @Test fun publishedUpdateIsConsistent() {
        org.junit.Assume.assumeTrue(System.getenv("UPDATE_CHECK") == "1")
        val r = Net.Route("wilfrombdn.github.io", 443, true, null)
        val j = java.io.ByteArrayOutputStream().also { Net.download(r, "/telegramnt/update.json", it) }
        val o = org.json.JSONObject(j.toString("UTF-8"))
        val apk = java.io.ByteArrayOutputStream().also { Net.download(r, o.getString("apk"), it) }.toByteArray()
        org.junit.Assert.assertEquals(o.getString("sha256"), Crypto.sha256(apk).hex())
    }

    /** 4.9 storage: clearing keeps the text + preview, frees the file, skips unsent and recent ones. */
    @Test fun clearMediaKeepsMessages() {
        val core = Core("http://127.0.0.1:1")
        val disk = HashMap<String, ByteArray>()
        core.deleteBlob = { disk.remove(it) }; core.blobSize = { disk[it]?.size?.toLong() ?: 0L }
        val now = System.currentTimeMillis()
        fun photo(id: String, ts: Long, mine: Boolean, uploaded: Boolean) = Msg(id, mine, "caption", ts, Msg.SENT).also {
            disk[id] = ByteArray(1000)
            it.file = org.json.JSONObject().put("local", id).put("th", "abc").put("m", "image/jpeg").apply { if (uploaded) put("id", "x".repeat(32)) }
        }
        val old = photo("a", now - 40L * 86_400_000, false, true)
        val recent = photo("b", now - 1000, false, true)
        val unsent = photo("c", now - 40L * 86_400_000, true, false)
        val msgs = listOf(old, recent, unsent)
        assertEquals(3000L, core.mediaBytes(msgs))
        assertEquals(1, core.clearMedia(msgs, now - 30L * 86_400_000))   // 30-day auto-delete
        assertFalse(old.file!!.has("local")); assertTrue(old.file!!.optBoolean("cleared"))
        assertEquals("caption", old.text); assertEquals("abc", old.file!!.optString("th"))
        assertTrue(recent.file!!.has("local")); assertTrue(unsent.file!!.has("local"))
        assertEquals(2000L, core.mediaBytes(msgs))
    }

    /** The app's exact update check, through a local Tor SOCKS port (set TOR_SOCKS=port). */
    @Test fun updateOverTor() {
        val port = System.getenv("TOR_SOCKS")?.toIntOrNull()
        org.junit.Assume.assumeTrue(port != null)
        val r = Net.Route("wilfrombdn.github.io", 443, true, port)
        val j = java.io.ByteArrayOutputStream().also { Net.download(r, "/telegramnt/update.json?t=1", it) }
        println("UPDATE-OVER-TOR: " + j.toString("UTF-8").take(80).replace("\n", " "))
        assertTrue(org.json.JSONObject(j.toString("UTF-8")).getInt("code") >= 35)
    }
}
