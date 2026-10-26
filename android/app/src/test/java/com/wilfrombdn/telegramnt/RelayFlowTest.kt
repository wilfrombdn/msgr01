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

    private fun gtexts(g: Group?) = g!!.msgs.filter { it.state != Msg.NOTE }.map { (it.sender?.let { u -> "$u:" } ?: "me:") + it.text }

    @Test fun privateGroupThreeMembersTalk() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server); val ci = Core(server)
        val au = "al_$tag"; val bu = "bo_$tag"; val cu = "ci_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        // al befriends bo and ci (needed to invite them to a private group)
        for ((x, xu, y, yu) in listOf(listOf(al, au, bo, bu), listOf(al, au, ci, cu)).map { it }) {}
        fun befriend(a: Core, au: String, b: Core, bu: String) {
            a.addFriend(bu); b.poll(5); b.accept(b.contact(au)!!); a.poll(5)
        }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)

        val g = al.createGroup("Road Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5)                       // bo and ci receive the invite, auto-join + post their sender keys
        al.poll(5); bo.poll(5); ci.poll(5)           // everyone exchanges sender keys
        assertNotNull(bo.groups.firstOrNull { it.secret == g.secret })
        assertNotNull(ci.groups.firstOrNull { it.secret == g.secret })

        al.sendGroup(g, "morning all")
        bo.poll(5); ci.poll(5)
        val gb = bo.groups.first { it.secret == g.secret }; val gc = ci.groups.first { it.secret == g.secret }
        assertTrue(gtexts(gb).any { it == "$au:morning all" })
        bo.sendGroup(gb, "on my way")
        al.poll(5); ci.poll(5)
        assertTrue(gtexts(al.group(g.id)).any { it == "$bu:on my way" })
        assertTrue(gtexts(gc).any { it == "$bu:on my way" })
    }

    @Test fun publicGroupJoinByLinkAndNoImpersonation() {
        assumeTrue(Relay(server).ping())
        val host = Core(server); val guest = Core(server); val faker = Core(server)
        val hu = "host_$tag"; val gu = "guest_$tag"; val fu = "faker_$tag"
        host.signup(hu, "hosts long password", "Host"); guest.signup(gu, "guests long password", "Guest"); faker.signup(fu, "fakers long password", "Faker")
        val g = host.createGroup("Open Room", public = true)
        val link = host.groupInviteLink(g)
        val gg = guest.joinGroupLink(link)
        host.poll(5); guest.poll(5); host.poll(5)
        host.sendGroup(g, "welcome")
        guest.poll(5)
        assertTrue(gtexts(guest.group(gg.id)).any { it == "$hu:welcome" })

        // faker joins and tries to post a sender key claiming to be the host -> must be rejected
        val gf = faker.joinGroupLink(link)
        val fakeSkdm = org.signal.libsignal.protocol.groups.GroupSessionBuilder(faker.signalFor())
            .create(org.signal.libsignal.protocol.SignalProtocolAddress(hu, 1), gf.distId).serialize()
        faker.postRaw(gf, org.json.JSONObject().put("t", "skdm").put("u", hu).put("d", java.util.Base64.getEncoder().encodeToString(fakeSkdm)).put("sig", java.util.Base64.getEncoder().encodeToString(ByteArray(64))))
        guest.poll(5)
        // the guest already trusts the real host; the forged key is ignored, real messages still work
        host.sendGroup(g, "still me")
        guest.poll(5)
        assertTrue(gtexts(guest.group(gg.id)).any { it == "$hu:still me" })
    }

    @Test fun deleteForEveryoneErasesOnBothSides() {
        assumeTrue(Relay(server).ping())
        val pat = Core(server); val quin = Core(server)
        val pu = "patd_$tag"; val qu = "quind_$tag"
        pat.signup(pu, "pats long password", "Pat"); quin.signup(qu, "quins long password", "Quin")
        pat.addFriend(qu); quin.poll(5); quin.accept(quin.contact(pu)!!); pat.poll(5)
        val toQuin = pat.contact(qu)!!
        val m = pat.queue(toQuin, "oops wrong chat"); pat.deliver(toQuin, m)
        quin.poll(5)
        assertTrue(quin.contact(pu)!!.msgs.any { it.text == "oops wrong chat" })
        pat.deleteMessage(toQuin, m, forEveryone = true)
        assertTrue(toQuin.msgs.none { it.text == "oops wrong chat" }) // gone for me immediately
        quin.poll(5)
        assertTrue("erased on their side too", quin.contact(pu)!!.msgs.none { it.text == "oops wrong chat" })
    }

    @Test fun recoveryBackupRoundTripsAfterReinstall() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "alr_$tag"; val bu = "bor_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo")
        al.addFriend(bu); bo.poll(5); bo.accept(bo.contact(au)!!); al.poll(5)
        al.deliver(al.contact(bu)!!, al.queue(al.contact(bu)!!, "keep this message"))
        val file = al.fullBackup()
        assertEquals(au, al.backupOwner(file))

        // a fresh install: new Core, restore only from the file + password (no server login)
        val fresh = Core(server)
        assertTrue(fresh.restoreFull(file, "als long password"))
        assertEquals(au, fresh.me!!.user)
        assertTrue(fresh.contact(bu)!!.msgs.any { it.text == "keep this message" })
        // wrong password fails cleanly
        assertFalse(Core(server).restoreFull(file, "not the password"))
    }

    /** A large attachment (like a video) must survive pad -> seal -> upload -> download -> open. */
    @Test fun largeAttachmentRoundTrip() {
        assumeTrue(Relay(server).ping())
        val aStore = HashMap<String, ByteArray>()
        val bStore = HashMap<String, ByteArray>()
        val alice = Core(server).apply { saveBlob = { k, v -> aStore[k] = v }; loadBlob = { aStore[it] } }
        val bob = Core(server).apply { saveBlob = { k, v -> bStore[k] = v }; loadBlob = { bStore[it] } }
        val a = "vida_$tag"; val b = "vidb_$tag"
        alice.signup(a, "correct horse battery", "Al")
        bob.signup(b, "another long password", "Bo")
        val toBob = alice.addFriend("@$b")
        bob.poll(5); val fromAlice = bob.contact(a)!!; bob.accept(fromAlice)
        alice.poll(5)
        assertEquals(Contact.FRIENDS, toBob.status)

        val big = ByteArray(18 * 1024 * 1024) { (it * 31 + 7).toByte() }   // ~18 MB "video"
        val msg = alice.queueFile(toBob, "clip.mp4", "video/mp4", big, null)
        alice.deliver(toBob, msg)
        assertEquals(Msg.SENT, msg.state)

        bob.poll(15)   // poll auto-downloads attachments
        val got = bob.contact(a)!!.msgs.last { it.file != null }
        assertEquals("video/mp4", got.file!!.optString("m"))
        assertArrayEquals(big, bob.openFile(got))
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
