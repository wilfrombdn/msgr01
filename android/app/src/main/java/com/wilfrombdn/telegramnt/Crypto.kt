package com.wilfrombdn.telegramnt

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message sealing. All primitives come from Google Tink; nothing here is
 * home-made crypto, only the envelope layout.
 *
 * A sealed message (what the relay stores) is
 *   HPKE(recipient's X25519 key, context = "telegramnt-v1|<mailbox address>",
 *        signature(64) || padded inner)
 * where signature = Ed25519(sender, context || padded inner). Binding the
 * mailbox address into both stops a message being moved to another mailbox.
 * The padded inner is a 2-byte length, the JSON, then zeros up to a multiple
 * of PAD bytes, so the relay can't tell a "k" from a paragraph.
 */
object Crypto {
    const val ENC_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305_RAW"
    const val SIG_TEMPLATE = "ED25519_RAW"
    const val AEAD_TEMPLATE = "AES256_GCM"
    const val PAD = 512
    const val MAX_INNER = 28 * 1024 // big enough for a padded profile picture double-base64d in the envelope
    private const val SIG_LEN = 64

    private val rng = SecureRandom()

    init {
        HybridConfig.register()
        SignatureConfig.register()
        AeadConfig.register()
    }

    fun ensureInit() = Unit

    fun randomHex(bytes: Int = 32): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.hex()
    }

    /** Mailbox address = sha256(token); senders only ever learn the address. */
    fun addressOf(tokenHex: String): String = sha256(tokenHex.unhex()).hex()

    fun newKeyset(template: String): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(template))

    fun publicBytes(private: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeysetWithoutSecret(private.publicKeysetHandle)

    /** Parse a contact's public key, accepting only exactly one key of the expected kind. */
    fun parsePublic(bytes: ByteArray, template: String): KeysetHandle? = try {
        val h = TinkProtoKeysetFormat.parseKeysetWithoutSecret(bytes)
        val want = KeyTemplates.get(template).toParameters()
        if (h.size() == 1 && h.getAt(0).key.parameters == want) h else null
    } catch (e: Exception) {
        null
    }

    private fun context(address: String) = "telegramnt-v1|$address".toByteArray()

    fun pad(inner: ByteArray): ByteArray {
        require(inner.size <= MAX_INNER) { "message too long" }
        val total = ((inner.size + 2 + PAD - 1) / PAD) * PAD
        return ByteBuffer.allocate(total).putShort(inner.size.toShort()).put(inner).array()
    }

    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < 2 || padded.size % PAD != 0) return null
        val n = ByteBuffer.wrap(padded).short.toInt() and 0xFFFF
        if (n > padded.size - 2) return null
        return padded.copyOfRange(2, 2 + n)
    }

    fun seal(mySig: KeysetHandle, theirEnc: KeysetHandle, address: String, inner: ByteArray): ByteArray {
        val ctx = context(address)
        val padded = pad(inner)
        val sig = mySig.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java).sign(ctx + padded)
        check(sig.size == SIG_LEN)
        return theirEnc.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
            .encrypt(sig + padded, ctx)
    }

    /** First step of opening: decrypt. The signature is checked separately in [verify]
     *  because for a first "hello" the sender's key is inside the message. */
    class Opened(val address: String, val signature: ByteArray, val padded: ByteArray) {
        val inner: ByteArray? get() = unpad(padded)
    }

    fun open(myEnc: KeysetHandle, address: String, blob: ByteArray): Opened? = try {
        val pt = myEnc.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            .decrypt(blob, context(address))
        if (pt.size <= SIG_LEN) null
        else Opened(address, pt.copyOfRange(0, SIG_LEN), pt.copyOfRange(SIG_LEN, pt.size))
    } catch (e: Exception) {
        null
    }

    fun verify(theirSig: KeysetHandle, o: Opened): Boolean = try {
        theirSig.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
            .verify(o.signature, context(o.address) + o.padded)
        true
    } catch (e: Exception) {
        false
    }

    /** Same 30 digits on both phones if nobody swapped keys in between. */
    fun safetyCode(sigPubA: ByteArray, sigPubB: ByteArray): String {
        val (x, y) = listOf(sigPubA, sigPubB).sortedWith { p, q -> compareBytes(p, q) }
        val h = sha256("telegramnt-safety".toByteArray() + x + y)
        val digits = StringBuilder()
        var i = 0
        while (digits.length < 30) {
            val v = ((h[i].toInt() and 0xFF) shl 16) or ((h[i + 1].toInt() and 0xFF) shl 8) or (h[i + 2].toInt() and 0xFF)
            digits.append((v % 100000).toString().padStart(5, '0'))
            i += 3
        }
        return digits.chunked(5).joinToString(" ")
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    // ---- password, account backup ----

    const val KDF_ROUNDS = 600_000

    class PasswordKeys(val auth: ByteArray, val backup: ByteArray)

    /** Password -> two keys. Only [PasswordKeys.auth] is sent to the server; the
     *  backup key never leaves the phone, so the server can't open the backup. */
    fun passwordKeys(user: String, password: String, rounds: Int = KDF_ROUNDS): PasswordKeys {
        val spec = PBEKeySpec(password.toCharArray(), "telegramnt-v2|$user".toByteArray(), rounds, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        fun derive(label: String) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(master, "HmacSHA256"))
            doFinal(label.toByteArray())
        }
        return PasswordKeys(derive("auth"), derive("backup"))
    }

    fun lockBackup(key: ByteArray, user: String, data: ByteArray) = gcmSeal(key, "telegramnt-backup|$user", data)
    fun unlockBackup(key: ByteArray, user: String, blob: ByteArray) = gcmOpen(key, "telegramnt-backup|$user", blob)

    /** AES-256-GCM with a random nonce in front. Used for backups and attachments. */
    fun gcmSeal(key: ByteArray, label: String, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(label.toByteArray())
        return iv + c.doFinal(data)
    }

    fun gcmOpen(key: ByteArray, label: String, blob: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(label.toByteArray())
        c.doFinal(blob, 12, blob.size - 12)
    } catch (e: Exception) {
        null
    }

    fun privateBytes(h: KeysetHandle): ByteArray = TinkProtoKeysetFormat.serializeKeyset(h, InsecureSecretKeyAccess.get())

    fun parsePrivate(b: ByteArray, template: String): KeysetHandle? = try {
        val h = TinkProtoKeysetFormat.parseKeyset(b, InsecureSecretKeyAccess.get())
        val want = KeyTemplates.get(template).toParameters()
        if (h.size() == 1 && h.getAt(0).key.parameters == want) h else null
    } catch (e: Exception) {
        null
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
