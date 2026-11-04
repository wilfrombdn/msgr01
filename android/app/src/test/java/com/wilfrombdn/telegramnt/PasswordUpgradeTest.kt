package com.wilfrombdn.telegramnt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/** Accounts made before 9.0 (PBKDF2) move to Argon2id on their next login, and weak new passwords are refused. Needs a running server. */
class PasswordUpgradeTest {
    private val server = System.getenv("RELAY") ?: "http://127.0.0.1:8099"
    private val tag = Crypto.randomHex(3)
    init { Crypto.fastKdfForTests() }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** What an app older than 9.0 does at sign-up: PBKDF2 keys, backup locked with the PBKDF2 backup key, no kdf field. */
    private fun oldStyleAccount(relay: Relay, user: String, password: String): Crypto.PasswordKeys {
        val keys = Crypto.passwordKeys(user, password, Crypto.KDF_PBKDF2)
        val enc = Crypto.newKeyset(Crypto.ENC_TEMPLATE)
        val sig = Crypto.newKeyset(Crypto.SIG_TEMPLATE)
        val me = Me(user, "Old", "", enc, sig, Crypto.randomHex(), keys.backup)
        val backup = JSONObject().put("v", 2).put("name", "Old")
            .put("enc", b64(Crypto.privateBytes(enc))).put("sig", b64(Crypto.privateBytes(sig))).put("inbox", me.inboxToken)
            .put("sident", b64(me.signal.identity.serialize())).put("sreg", me.signal.regId)
            .put("groups", JSONArray()).put("contacts", JSONArray())
        relay.signup(user, keys.auth, Crypto.publicBytes(enc), me.sigPub, me.inboxToken,
            Crypto.lockBackup(keys.backup, user, backup.toString().toByteArray()), kdf = 1)
        return keys
    }

    @Test fun oldAccountMovesToArgon2OnLogin() {
        assumeTrue(Relay(server).ping())
        val relay = Relay(server)
        val u = "olga_$tag"
        val pw = "olgas long password"
        val old = oldStyleAccount(relay, u, pw)
        assertEquals(Crypto.KDF_PBKDF2, relay.kdf(u))

        val phone = Core(server)
        phone.login(u, pw)
        assertEquals(u, phone.me!!.user)
        assertEquals("the server now lists the account as Argon2id", Crypto.KDF_ARGON2, relay.kdf(u))
        assertArrayEquals("the phone now holds the Argon2id backup key", Crypto.passwordKeys(u, pw, Crypto.KDF_ARGON2).backup, phone.me!!.backupKey)
        try { relay.login(u, old.auth); fail("the old PBKDF2 login must stop working") } catch (e: Relay.RelayError) { assertEquals(403, e.code) }

        // a second phone logs in with the new hashing and still gets the account back
        val again = Core(server)
        again.login(u, pw)
        assertEquals(u, again.me!!.user)
        // a wrong password is still refused
        try { Core(server).login(u, "not the password at all"); fail("wrong password") } catch (e: UserError) {}
        // and the account can be deleted with the new key
        again.deleteAccount(pw)
        try { Core(server).login(u, pw); fail("deleted") } catch (e: UserError) {}
    }

    @Test fun recoveryFileFromBeforeTheUpgradeStillRestores() {
        assumeTrue(Relay(server).ping())
        val u = "rita_$tag"
        val pw = "ritas long password"
        val relay = Relay(server)
        oldStyleAccount(relay, u, pw)
        val phone = Core(server)
        phone.login(u, pw)
        // a file written while the account still used PBKDF2 (keyed with the old backup key)
        val oldKey = Crypto.passwordKeys(u, pw, Crypto.KDF_PBKDF2).backup
        val file = "MSGR02\n".toByteArray() + Crypto.gcmSeal(oldKey, "msgr01-device|$u", phone.toJson().toString().toByteArray())
        assertTrue(Core(server).restoreFull(file, pw, u))
        assertFalse(Core(server).restoreFull(file, "wrong password here", u))
    }

    @Test fun weakNewPasswordsAreRefused() {
        assumeTrue(Relay(server).ping())
        for ((pw, word) in listOf("password1234" to "common", "short1!" to "12 characters", "qwertyuiopas" to "common")) {
            try { Core(server).signup("weak_${Crypto.randomHex(3)}", pw, "W"); fail("accepted $pw") } catch (e: UserError) {
                assertTrue("${e.message}", e.message!!.contains(word))
            }
        }
    }
}
