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
}
