package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Sign up -> add friend -> accept -> chat -> sign in on a new phone, against a running server
 *  (start one with: python3 server/relay.py --port 8099 --db /tmp/t.db). Skipped if none is running. */
class RelayFlowTest {
    private val server = System.getenv("RELAY") ?: "http://127.0.0.1:8099"
    private val tag = Crypto.randomHex(3)

    private fun texts(c: Contact?) = c!!.msgs.filter { it.state != Msg.NOTE }.map { it.text }

    @Test fun twoPeopleBecomeFriendsAndTalk() {
        assumeTrue(Relay(server).ping())
        val alice = Core(server)
        val bob = Core(server)
        val a = "alice_$tag"
        val b = "bob_$tag"
        alice.signup(a, "correct horse battery", "Alice")
        bob.signup(b, "another long password", "Bob")
        try { Core(server).signup(a, "whatever12345", "X"); fail("duplicate username") } catch (e: UserError) {
            assertEquals("That username is taken", e.message)
        }
        try { alice.addFriend("nobody_$tag"); fail("unknown user") } catch (e: UserError) {}

        // Alice adds Bob and writes before he accepts.
        val toBob = alice.addFriend("@$b")
        assertEquals(Contact.ASKED, toBob.status)
        val early = alice.queue(toBob, "hi early")
        alice.deliver(toBob, early)
        assertEquals(Msg.SENT, early.state) // goes out as a message request

        bob.poll(5)
        val fromAlice = bob.contact(a)
        assertNotNull(fromAlice)
        assertEquals(Contact.ASKING, fromAlice!!.status)
        assertEquals("Alice", fromAlice.name)
        assertEquals(listOf("hi early"), texts(fromAlice)) // the message request is readable before accepting
        bob.accept(fromAlice)

        alice.poll(5) // gets the accept, sends the waiting message
        assertEquals(Contact.FRIENDS, toBob.status)
        assertEquals("Bob", toBob.name)
        assertEquals(Msg.SENT, early.state)
        bob.deliver(fromAlice, bob.queue(fromAlice, "hey alice"))
        alice.poll(5)
        assertEquals(listOf("hi early", "hey alice"), texts(alice.contact(b)))
        assertEquals(alice.safetyCode(toBob), bob.safetyCode(fromAlice))

        // Profile photos travel sealed, like messages.
        val alicePic = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(3000) { 1 }
        val bobPic = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(2000) { 2 }
        alice.setAvatar(alicePic)
        bob.setAvatar(bobPic)
        bob.poll(5)
        alice.poll(5)
        assertArrayEquals(alicePic, bob.contact(a)!!.avatar)
        assertArrayEquals(bobPic, alice.contact(b)!!.avatar)

        // Alice signs in on a new phone: friends come back from the backup, old phone is signed out.
        alice.uploadBackup()
        val alice2 = Core(server)
        try { alice2.login(a, "wrong password!!"); fail("wrong password") } catch (e: UserError) {}
        alice2.login(a, "correct horse battery")
        assertEquals(Contact.FRIENDS, alice2.contact(b)!!.status)
        assertFalse(alice.sessionOk())
        assertTrue(alice2.sessionOk())
        assertArrayEquals(alicePic, alice2.me!!.avatar) // own photo comes back from the backup
        bob.poll(5) // Bob answers the new phone's "send me your photo"
        bob.deliver(fromAlice, bob.queue(fromAlice, "new phone?"))
        alice2.poll(5)
        // The old phone hadn't confirmed "hey alice" yet, so the new phone gets it too: nothing is lost.
        assertEquals("new phone?", texts(alice2.contact(b)).last())
        assertArrayEquals(bobPic, alice2.contact(b)!!.avatar)

        // Saved-on-phone format round trip.
        val copy = Core("http://unused")
        copy.loadJson(alice2.toJson())
        assertEquals(a, copy.me!!.user)
        assertEquals(texts(alice2.contact(b)), texts(copy.contact(b)))
    }

    @Test fun messageRequestWithoutAddingAsFriend() {
        assumeTrue(Relay(server).ping())
        val dave = Core(server)
        val erin = Core(server)
        val d = "dave_$tag"
        val e = "erin_$tag"
        dave.signup(d, "daves long password", "Dave")
        erin.signup(e, "erins long password", "Erin")
        val toErin = dave.addFriend(e)
        dave.deliver(toErin, dave.queue(toErin, "hello, we met at the shop"))
        erin.poll(5)
        val fromDave = erin.contact(d)!!
        assertEquals(listOf("hello, we met at the shop"), texts(fromDave))

        erin.accept(fromDave, friend = false) // chat, but don't add him
        dave.poll(5)
        assertEquals(Contact.CHATTING, toErin.status)
        assertTrue(toErin.waitingForAdd)
        dave.deliver(toErin, dave.queue(toErin, "thanks!"))
        erin.poll(5)
        assertEquals("thanks!", texts(fromDave).last())

        erin.makeFriend(fromDave) // later she adds him after all
        dave.poll(5)
        assertEquals(Contact.FRIENDS, fromDave.status)
        assertEquals(Contact.FRIENDS, toErin.status)
    }

    private fun withDisk(c: Core): Core {
        val disk = HashMap<String, ByteArray>()
        c.saveBlob = { id, b -> disk[id] = b }
        c.loadBlob = { id -> disk[id] }
        return c
    }

    @Test fun attachmentsArriveAndServerForgetsThem() {
        assumeTrue(Relay(server).ping())
        val fay = withDisk(Core(server))
        val gus = withDisk(Core(server))
        val f = "fay_$tag"
        val g = "gus_$tag"
        fay.signup(f, "fays long password", "Fay")
        gus.signup(g, "guss long password", "Gus")
        val toGus = fay.addFriend(g)
        gus.poll(5)
        gus.accept(gus.contact(f)!!)
        fay.poll(5)

        val data = ByteArray(300_000) { (it * 7).toByte() }
        val msg = fay.queueFile(toGus, "report/../x.pdf", "application/pdf", data, null)
        fay.deliver(toGus, msg)
        assertEquals(Msg.SENT, msg.state)
        val serverId = msg.file!!.getString("id")
        assertFalse("the phone uploads only the locked copy", Relay(server).download(serverId).contentEquals(data))

        gus.poll(5) // gets the message and downloads the attachment
        val got = gus.contact(f)!!.msgs.last { it.file != null }
        assertEquals("report_.._x.pdf", got.file!!.getString("n"))
        assertArrayEquals(data, gus.openFile(got))
        try { Relay(server).download(serverId); fail("server should have forgotten it") } catch (e: Relay.RelayError) {
            assertEquals(404, e.code)
        }
    }

    @Test fun avatarPaddingIsFixedSize() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(1500) { 3 }
        val padded = Core.padAvatar(jpeg)
        assertEquals(Core.AVATAR_PAD, padded.size)
        assertEquals(Core.AVATAR_PAD, Core.padAvatar(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(50)).size)
        assertArrayEquals(jpeg, Core.unpadAvatar(padded))
        assertNull(Core.unpadAvatar(ByteArray(100)))
    }

    @Test fun skippedBackupBlocksNewPhoneLogin() {
        assumeTrue(Relay(server).ping())
        val u = "nolan_$tag"
        val a = Core(server)
        a.signup(u, "nolans long password", "Nolan")
        a.setCloudBackup(false) // keep nothing on the server
        try { Core(server).login(u, "nolans long password"); fail("should refuse with no backup") } catch (e: UserError) {
            assertTrue(e.message!!, e.message!!.lowercase().contains("cloud backup"))
        }
        // turning it back on makes new-phone login work again
        a.setCloudBackup(true)
        val b = Core(server); b.login(u, "nolans long password")
        assertEquals(u, b.me!!.user)
    }

    @Test fun disappearingMessagesDeleteOnBothSides() {
        assumeTrue(Relay(server).ping())
        val pat = Core(server); val quin = Core(server)
        val pu = "pat_$tag"; val qu = "quinn_$tag"
        pat.signup(pu, "pats long password", "Pat"); quin.signup(qu, "quinns long password", "Quinn")
        pat.addFriend(qu); quin.poll(5); quin.accept(quin.contact(pu)!!); pat.poll(5)
        val toQuinn = pat.contact(qu)!!
        pat.setTimer(toQuinn, 1) // 1 second
        quin.poll(5)
        assertEquals(1, quin.contact(pu)!!.ttl) // timer synced to the other side
        pat.deliver(toQuinn, pat.queue(toQuinn, "this vanishes"))
        quin.poll(5)
        assertEquals("this vanishes", quin.contact(pu)!!.msgs.last { it.state != Msg.NOTE }.text)
        Thread.sleep(1100)
        assertTrue(pat.purgeExpired()); assertTrue(quin.purgeExpired())
        assertTrue(pat.contact(qu)!!.msgs.none { it.text == "this vanishes" })
        assertTrue(quin.contact(pu)!!.msgs.none { it.text == "this vanishes" })
    }

    @Test fun readReceiptsMarkMessagesRead() {
        assumeTrue(Relay(server).ping())
        val rex = Core(server); val sky = Core(server)
        val ru = "rex_$tag"; val su = "sky_$tag"
        rex.signup(ru, "rexs long password", "Rex"); sky.signup(su, "skys long password", "Sky")
        rex.addFriend(su); sky.poll(5); sky.accept(sky.contact(ru)!!); rex.poll(5)
        val toSky = rex.contact(su)!!
        val m = rex.queue(toSky, "did you see this?"); rex.deliver(toSky, m)
        sky.poll(5)                       // Sky receives
        sky.markRead(sky.contact(ru)!!)   // Sky reads -> sends a receipt
        rex.poll(5)                       // Rex gets the receipt
        assertTrue(toSky.theirReadUpto >= m.ts)

        // with read receipts off, Sky sends none
        val toSky2 = "sky off"
        sky.readReceipts = false
        val m2 = rex.queue(toSky, "and this one?"); rex.deliver(toSky, m2)
        sky.poll(5); sky.markRead(sky.contact(ru)!!); rex.poll(5)
        assertTrue("no receipt when off", toSky.theirReadUpto < m2.ts)
    }

    @Test fun lockoutAfterFiveWrongPasswords() {
        assumeTrue(Relay(server).ping())
        val u = "carol_$tag"
        Core(server).signup(u, "carols long password", "Carol")
        repeat(5) { try { Core(server).login(u, "nope nope nope $it") } catch (e: UserError) {} }
        try { Core(server).login(u, "carols long password"); fail("should be locked") } catch (e: UserError) {
            assertTrue(e.message!!, e.message!!.startsWith("Too many tries"))
        }
    }
}
