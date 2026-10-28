package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** A group attachment must reach every member (no delete-on-first-pickup). */
    @Test fun groupFileReachesAllMembers() {
        assumeTrue(Relay(server).ping())
        val al = withDisk(Core(server)); val bo = withDisk(Core(server)); val ci = withDisk(Core(server))
        val au = "gfa_$tag"; val bu = "gfb_$tag"; val cu = "gfc_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        fun befriend(a: Core, aun: String, b: Core, bun: String) { a.addFriend(bun); b.poll(5); b.accept(b.contact(aun)!!); a.poll(5) }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)
        val g = al.createGroup("Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5); al.poll(5); bo.poll(5); ci.poll(5)

        val data = ByteArray(200_000) { (it * 13 + 5).toByte() }
        val msg = al.queueGroupFile(g, "clip.mp4", "video/mp4", data, null)
        al.deliverGroupFile(g, msg)
        assertEquals(Msg.SENT, msg.state)

        bo.poll(8)
        val mb = bo.groups.first { it.secret == g.secret }.msgs.last { it.file != null }
        assertArrayEquals(data, bo.openFile(mb))
        ci.poll(8)   // second member still gets it (the server didn't delete on bo's pickup)
        val mc = ci.groups.first { it.secret == g.secret }.msgs.last { it.file != null }
        assertArrayEquals(data, ci.openFile(mc))
    }

    /** Edits change the text on the other phone (only the writer can); forwarded messages say who first wrote them. */
    @Test fun editAndForward() {
        assumeTrue(Relay(server).ping())
        val al = withDisk(Core(server)); val bo = withDisk(Core(server)); val ci = withDisk(Core(server))
        val au = "efa_$tag"; val bu = "efb_$tag"; val cu = "efc_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        fun befriend(a: Core, aun: String, b: Core, bun: String) { a.addFriend(bun); b.poll(5); b.accept(b.contact(aun)!!, friend = true); a.poll(5) }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)

        // 1:1 edit
        val toBo = al.contact(bu)!!
        val sent = al.queue(toBo, "see you at 6").also { al.deliver(toBo, it) }
        bo.poll(5)
        al.editMessage(toBo, sent, "see you at 7"); bo.poll(5)
        val got = bo.contact(au)!!.msgs.first { it.id == sent.id }
        assertEquals("see you at 7", got.text); assertTrue(got.edited)
        assertTrue(Msg.from(got.json()).edited)   // kept

        // forward Bo's text to Ci as al: Ci sees "forwarded from bo"
        val fw = al.queue(al.contact(cu)!!, "hello from bo").also { it.fwd = bu; al.deliver(al.contact(cu)!!, it) }
        ci.poll(5)
        assertEquals(bu, ci.contact(au)!!.msgs.first { it.id == fw.id }.fwd)

        // group: forwarded file + edit; a member can't edit someone else's message
        val g = al.createGroup("Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5); al.poll(5); bo.poll(5); ci.poll(5)
        val data = ByteArray(5_000) { it.toByte() }
        val gf = al.queueGroupFile(g, Voice.NAME, Voice.MIME, data, null).also { it.fwd = cu; al.deliverGroupFile(g, it) }
        val gm = al.sendGroup(g, "6am start", fwd = null)
        bo.poll(8)
        val bg = bo.groups.first { it.secret == g.secret }
        assertEquals(cu, bg.msgs.first { it.id == gf.id }.fwd)
        al.editGroupMessage(g, gm, "7am start"); bo.poll(8)
        assertEquals("7am start", bg.msgs.first { it.id == gm.id }.text)
        // ci forges an edit of al's message: ignored (the gctl proves ci sent it)
        val cg = ci.groups.first { it.secret == g.secret }; ci.poll(8)
        val fake = Msg(gm.id, true, "cancelled", 0, Msg.SENT)
        ci.editGroupMessage(cg, fake, "no work today"); bo.poll(8)
        assertEquals("7am start", bg.msgs.first { it.id == gm.id }.text)
    }

    /** Block, pinned messages (1:1 + admin-only in groups), group typing, and Saved Messages staying local. */
    @Test fun everydayFeatures() {
        assumeTrue(Relay(server).ping())
        val al = withDisk(Core(server)); val bo = withDisk(Core(server)); val ci = withDisk(Core(server))
        val au = "eda_$tag"; val bu = "edb_$tag"; val cu = "edc_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        fun befriend(a: Core, aun: String, b: Core, bun: String) { a.addFriend(bun); b.poll(5); b.accept(b.contact(aun)!!, friend = true); a.poll(5) }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)
        val toBo = al.contact(bu)!!; val alOnBo = bo.contact(au)!!

        // pin a message for both
        val m1 = al.queue(toBo, "gate code 4411").also { al.deliver(toBo, it) }
        bo.poll(5)
        al.pinMessage(toBo, m1); bo.poll(5)
        assertEquals(m1.id, alOnBo.pinned)
        al.pinMessage(toBo, null); bo.poll(5)
        assertNull(alOnBo.pinned)

        // block: nothing from them gets through (and survives a save/load)
        bo.block(au, true)
        val before = alOnBo.msgs.size
        al.queue(toBo, "you there?").also { al.deliver(toBo, it) }
        al.sendTyping(toBo); bo.poll(5)
        assertEquals(before, alOnBo.msgs.size); assertEquals(0L, alOnBo.theirTypingUntil)
        assertTrue(au in Core(server).also { it.loadJson(bo.toJson()) }.blocked)
        bo.block(au, false)
        al.queue(toBo, "now?").also { al.deliver(toBo, it) }; bo.poll(5)
        assertEquals("now?", alOnBo.msgs.last().text)

        // group: typing shows; a stale typing is ignored; only admins pin
        val g = al.createGroup("Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5); al.poll(5); bo.poll(5); ci.poll(5)
        val bg = bo.groups.first { it.secret == g.secret }; val cg = ci.groups.first { it.secret == g.secret }
        al.sendGroupTyping(g); bo.poll(8)
        assertTrue((bg.typing[au] ?: 0) > System.currentTimeMillis())
        val gm = al.sendGroup(g, "6am"); bo.poll(8); ci.poll(8)
        assertNull(bg.typing[au])   // the message arrived
        al.pinGroupMessage(g, gm); bo.poll(8)
        assertEquals(gm.id, bg.pinned)
        assertFalse(ci.canPin(cg))
        runCatching { ci.pinGroupMessage(cg, null) }
        bo.poll(8); assertEquals(gm.id, bg.pinned)

        // Saved Messages: sent instantly, nothing goes to the server
        val saved = al.savedChat()
        assertSame(saved, al.savedChat())
        val note = al.queue(saved, "buy filters").also { al.deliver(saved, it) }
        assertEquals(Msg.SENT, note.state)
        assertTrue(al.contacts.none { it.status == Contact.SAVED && it.user != au })
    }

    /** 5.3 extras over the wire: silent send, scheduled send, location, link card, polls + votes, @mention flag. */
    @Test fun niceExtras() {
        assumeTrue(Relay(server).ping())
        val al = withDisk(Core(server)); val bo = withDisk(Core(server)); val ci = withDisk(Core(server))
        val au = "nxa_$tag"; val bu = "nxb_$tag"; val cu = "nxc_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        fun befriend(a: Core, aun: String, b: Core, bun: String) { a.addFriend(bun); b.poll(5); b.accept(b.contact(aun)!!, friend = true); a.poll(5) }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)
        val toBo = al.contact(bu)!!; val alOnBo = bo.contact(au)!!
        var notified = 0
        bo.onNew = { notified++ }

        // silent: arrives, but no notification
        al.queue(toBo, "shh").also { it.silent = true; al.deliver(toBo, it) }; bo.poll(5)
        assertEquals("shh", alOnBo.msgs.last().text); assertEquals(0, notified)
        al.queue(toBo, "loud").also { al.deliver(toBo, it) }; bo.poll(5)
        assertEquals(1, notified)

        // scheduled: waits until due, then goes out at the end of the chat
        val later = al.queue(toBo, "later").also { al.schedule(it, System.currentTimeMillis() + 3_600_000) }
        assertTrue(al.dueScheduled().isEmpty())
        val due = al.dueScheduled(System.currentTimeMillis() + 7_200_000)
        assertEquals(1, due.size); assertEquals(later.id, due[0].second.id)
        al.deliver(toBo, due[0].second); bo.poll(5)
        assertEquals("later", alOnBo.msgs.last().text)

        // location + link card travel with the message (and bad values are dropped)
        al.queue(toBo, "").also { it.loc = org.json.JSONObject().put("la", 45.68).put("lo", -111.04).put("acc", 12.0); al.deliver(toBo, it) }
        al.queue(toBo, "see https://example.com", lp = org.json.JSONObject().put("u", "https://example.com").put("s", "Example").put("t", "Example Domain").put("d", "x"))
            .also { al.deliver(toBo, it) }
        al.queue(toBo, "").also { it.loc = org.json.JSONObject().put("la", 999).put("lo", 0); al.deliver(toBo, it) }
        bo.poll(5)
        val got = alOnBo.msgs.takeLast(3)
        assertEquals(45.68, got[0].loc!!.getDouble("la"), 1e-9)
        assertEquals("Example Domain", got[1].lp!!.getString("t"))
        assertNull(got[2].loc)

        // group poll + votes (each member's vote counted once, retractable); @mention flag
        val g = al.createGroup("Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5); al.poll(5); bo.poll(5); ci.poll(5)
        val p = al.sendPoll(g, "Lunch?", listOf("Tacos", "Pizza", "Skip"))
        assertEquals(Msg.SENT, p.state)
        bo.poll(8); ci.poll(8)
        val bg = bo.groups.first { it.secret == g.secret }; val cg = ci.groups.first { it.secret == g.secret }
        val bp = bg.msgs.first { it.id == p.id }; val cp = cg.msgs.first { it.id == p.id }
        assertEquals("Lunch?", bp.poll!!.getString("q"))
        bo.vote(bg, bp, 0); ci.vote(cg, cp, 1); ci.vote(cg, cp, 0)
        al.poll(8)
        assertEquals(mapOf(bu to 0, cu to 0), p.votes.toMap())
        bo.vote(bg, bp, -1); al.poll(8)
        assertEquals(mapOf(cu to 0), p.votes.toMap())
        al.sendGroup(g, "@$bu can you open up?"); bo.poll(8)
        assertTrue(bg.mentioned)
    }

    /** A reaction reaches the other side and survives a save/load. */
    @Test fun reactionsPropagateAndPersist() {
        assumeTrue(Relay(server).ping())
        val rex = Core(server); val sky = Core(server)
        val ru = "rxa_$tag"; val su = "rxb_$tag"
        rex.signup(ru, "rex long password", "Rex"); sky.signup(su, "sky long password", "Sky")
        rex.addFriend(su); sky.poll(5); sky.accept(sky.contact(ru)!!); rex.poll(5)
        val m = rex.queue(rex.contact(su)!!, "nice one"); rex.deliver(rex.contact(su)!!, m)
        sky.poll(5)
        val skyMsg = sky.contact(ru)!!.msgs.last { it.text == "nice one" }
        sky.react(sky.contact(ru)!!, skyMsg, "👍")
        rex.poll(5)
        val rexMsg = rex.contact(su)!!.msgs.last { it.text == "nice one" }
        assertEquals("👍", rexMsg.reactions[su])
        assertEquals("👍", Msg.from(rexMsg.json()).reactions[su])   // persists
    }

    /** 5.0 call room: two phones join with the same room id, get paired, and bytes flow both ways. */
    @Test fun callRoomPairsTwoPhones() {
        assumeTrue(Relay(server).ping())
        val room = Crypto.randomHex(32)
        val a = Relay(server).pipe(room)
        var b: java.net.Socket? = null
        val t = Thread { b = Relay(server).pipe(room) }.apply { start() }
        assertEquals(1, a.getInputStream().read())
        t.join(10_000)
        assertEquals(1, b!!.getInputStream().read())
        a.getOutputStream().apply { write("hello".toByteArray()); flush() }
        val got = ByteArray(5); java.io.DataInputStream(b!!.getInputStream()).readFully(got)
        assertEquals("hello", String(got))
        a.close()
        assertEquals(-1, b!!.getInputStream().read())   // one hangs up: the other's line closes
    }

    /** Typing and online/last seen go friend to friend; switching them off stops them. */
    @Test fun typingAndLastSeen() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "tya_$tag"; val bu = "tyb_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo")
        al.addFriend(bu); bo.poll(5); bo.accept(bo.contact(au)!!, friend = true); al.poll(5)
        val alOnBo = bo.contact(au)!!
        val now = System.currentTimeMillis()

        al.sendTyping(al.contact(bu)!!); bo.poll(5)
        assertTrue(alOnBo.theirTypingUntil > now)
        al.contact(bu)!!.let { al.deliver(it, al.queue(it, "hi")) }; bo.poll(5)
        assertEquals(0L, alOnBo.theirTypingUntil)   // the message arrived: no more "typing…"

        al.sendPresence(true); bo.poll(5)
        assertTrue(alOnBo.theirOnlineUntil > now); assertTrue(alOnBo.theirSeen >= now)
        al.sendPresence(false); bo.poll(5)
        assertEquals(0L, alOnBo.theirOnlineUntil); assertTrue(alOnBo.theirSeen > 0)
        assertEquals(alOnBo.theirSeen, Contact.from(alOnBo.json(withMsgs = false)).theirSeen)   // kept

        // switched off: nothing goes out, and "hide" clears what they had
        al.showTyping = false; al.showSeen = false
        alOnBo.theirTypingUntil = 0
        al.sendTyping(al.contact(bu)!!); al.sendPresence(true); bo.poll(5)
        assertEquals(0L, alOnBo.theirTypingUntil); assertEquals(0L, alOnBo.theirOnlineUntil)
        al.sendPresence(false, hide = true); bo.poll(5)
        assertEquals(0L, alOnBo.theirSeen)
    }

    /** 5.0 calls: offer/ring/answer/end, cancel -> missed, decline; each phone writes its own summary. */
    @Test fun callSignalingAndSummaries() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "cla_$tag"; val bu = "clb_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo")
        al.addFriend(bu); bo.poll(5); bo.accept(bo.contact(au)!!, friend = true); al.poll(5)
        fun last(c: Core, u: String) = c.contact(u)!!.msgs.last { it.call != null }.call!!

        // answered call
        val k = al.startCall(al.contact(bu)!!)
        bo.poll(5)
        val inc = bo.call!!
        assertEquals(Core.Call.INCOMING, inc.phase); assertEquals(k.room, inc.room); assertEquals(k.key, inc.key)
        Thread.sleep(4000); al.poll(5)
        assertEquals(Core.Call.RINGING, al.call!!.phase)
        bo.answerCall(); bo.callConnected(); al.callConnected()
        Thread.sleep(1200)
        al.hangUp()
        assertNull(al.call)
        assertEquals("ok", last(al, bu).getString("s")); assertEquals("out", last(al, bu).getString("d"))
        bo.poll(5)
        assertNull(bo.call)
        assertEquals("ok", last(bo, au).getString("s")); assertEquals("in", last(bo, au).getString("d"))

        // caller gives up before an answer: they see "cancelled", the other phone "missed"
        al.startCall(al.contact(bu)!!); bo.poll(5)
        al.hangUp(); bo.poll(5)
        assertEquals("cancelled", last(al, bu).getString("s")); assertEquals("missed", last(bo, au).getString("s"))

        // declined
        al.startCall(al.contact(bu)!!); bo.poll(5)
        bo.hangUp(); al.poll(5)
        assertEquals("declined", last(bo, au).getString("s")); assertEquals("declined", last(al, bu).getString("s"))
        assertNull(al.call); assertNull(bo.call)
        assertEquals("declined", Msg.from(al.contact(bu)!!.msgs.last { it.call != null }.json()).call!!.getString("s"))   // persists
    }

    /** 4.8 GIF search goes through our server (skipped where the server has no GIPHY key, e.g. CI). */
    @Test fun gifSearchThroughRelay() {
        assumeTrue(Relay(server).ping())
        val r = Relay(server)
        val found = try { r.gifs("thumbs up", 0) } catch (e: Relay.RelayError) { assumeTrue(e.code != 503); throw e }
        assertTrue(found.isNotEmpty())
        assertTrue(found[0].preview.isNotEmpty())
        val full = r.gif(found[0].id)
        assertEquals("GIF8", String(full.copyOfRange(0, 4)))
    }

    /** 4.4 replies: the quote reference travels with the message in 1:1 chats and groups, and persists. */
    @Test fun repliesCarryTheQuote() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "rpa_$tag"; val bu = "rpb_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo")
        al.addFriend(bu); bo.poll(5); bo.accept(bo.contact(au)!!); al.poll(5)
        val q = al.queue(al.contact(bu)!!, "dinner at 6?"); al.deliver(al.contact(bu)!!, q)
        bo.poll(5)
        val got = bo.contact(au)!!.msgs.last { it.text == "dinner at 6?" }
        val r = bo.queue(bo.contact(au)!!, "yes!", org.json.JSONObject().put("id", got.id).put("u", au).put("p", got.text))
        bo.deliver(bo.contact(au)!!, r)
        al.poll(5)
        val back = al.contact(bu)!!.msgs.last { it.text == "yes!" }
        assertEquals(q.id, back.reply?.optString("id"))
        assertEquals(au, back.reply?.optString("u"))
        assertEquals(q.id, Msg.from(back.json()).reply?.optString("id"))   // persists

        val g = al.createGroup("Fam", public = false, invite = listOf(bu))
        bo.poll(5); al.poll(5); bo.poll(5)
        val gq = al.sendGroup(al.group(g.id)!!, "who's driving?")
        bo.poll(5); bo.poll(5)
        val gb = bo.groups.first { it.secret == g.secret }
        val seen = gb.msgs.last { it.text == "who's driving?" }
        bo.sendGroup(gb, "me", org.json.JSONObject().put("id", seen.id).put("u", au).put("p", seen.text))
        al.poll(5); al.poll(5)
        assertEquals(gq.id, al.group(g.id)!!.msgs.last { it.text == "me" }.reply?.optString("id"))
    }

    /** Read-only groups, admin roles, forged-settings rejection, and role persistence. */
    @Test fun readOnlyGroupAndRoles() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "roa_$tag"; val bu = "rob_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo")
        al.addFriend(bu); bo.poll(5); bo.accept(bo.contact(au)!!); al.poll(5)
        val g = al.createGroup("Crew", public = false, invite = listOf(bu))
        bo.poll(5); al.poll(5); bo.poll(5)
        assertEquals(au, bo.groups.first { it.secret == g.secret }.owner)   // owner carried via the invite

        // owner turns on read-only
        al.setGroup(al.group(g.id)!!, "Crew", readOnly = true, leaders = setOf(au))
        bo.poll(5)
        assertTrue(bo.groups.first { it.secret == g.secret }.readOnly)

        // a non-admin can't post: their own client marks it failed and the owner never accepts it
        val before = gtexts(al.group(g.id)).size
        val blocked = bo.sendGroup(bo.groups.first { it.secret == g.secret }, "can i talk?")
        assertEquals(Msg.FAILED, blocked.state)
        al.poll(5)
        assertEquals(before, gtexts(al.group(g.id)).size)

        // a forged 'set' from a non-admin is ignored by the owner
        val gbo = bo.groups.first { it.secret == g.secret }
        gbo.leaders.add(bu)   // bo lies locally so its own client will send the set
        bo.setGroup(gbo, "Hacked", readOnly = false, leaders = setOf(bu))
        al.poll(5)
        assertEquals("Crew", al.group(g.id)!!.name)
        assertFalse(bu in al.group(g.id)!!.leaders)

        // owner can still post; roles survive a save/load
        al.sendGroup(al.group(g.id)!!, "admins only"); bo.poll(5)
        assertTrue(gtexts(bo.groups.first { it.secret == g.secret }).any { it.endsWith("admins only") })
        val restored = Group.from(al.group(g.id)!!.json(true))
        assertTrue(restored.readOnly && restored.owner == au && au in restored.leaders)
    }

    /** Helper: three friends in a private group, all sender keys exchanged. */
    private fun threeInAGroup(prefix: String): Triple<Core, Core, Core> {
        val al = Core(server); val bo = Core(server); val ci = Core(server)
        val au = "${prefix}a_$tag"; val bu = "${prefix}b_$tag"; val cu = "${prefix}c_$tag"
        al.signup(au, "als long password", "Al"); bo.signup(bu, "bos long password", "Bo"); ci.signup(cu, "cis long password", "Ci")
        fun befriend(a: Core, aun: String, b: Core, bun: String) { a.addFriend(bun); b.poll(5); b.accept(b.contact(aun)!!); a.poll(5) }
        befriend(al, au, bo, bu); befriend(al, au, ci, cu)
        al.createGroup("Crew", public = false, invite = listOf(bu, cu))
        bo.poll(5); ci.poll(5); al.poll(5); bo.poll(5); ci.poll(5)
        return Triple(al, bo, ci)
    }

    /** The owner removes a member: it re-keys, the removed member loses access, the kept member keeps talking. */
    @Test fun ownerRemovesMemberWhoLosesAccess() {
        assumeTrue(Relay(server).ping())
        val (al, bo, ci) = threeInAGroup("rk")
        val au = "rka_$tag"; val cu = "rkc_$tag"
        val g = al.groups.first()
        val oldEpoch = g.epoch

        val oldSecret = g.secret                     // (groups have different local ids per device; match by secret)

        al.rekeyGroup(g, setOf(cu))                  // owner removes Ci: new secret, Ci gets no piece
        assertEquals(oldEpoch + 1, g.epoch)
        bo.poll(5)                                   // Bo reads the re-key from the old mailbox and migrates
        ci.poll(5)                                   // Ci reads it too, finds no piece -> drops the group
        al.poll(5)                                   // Al collects Bo's fresh sender key in the new mailbox

        assertTrue("Ci should have left the group", ci.groups.none { it.secret == oldSecret || it.secret == g.secret })

        al.sendGroup(al.group(g.id)!!, "after removal")   // al.group(g.id) is fine: it's Al's own group
        bo.poll(5); bo.poll(5)                        // Bo (migrated) reads it from the new mailbox
        val bg = bo.groups.first { it.secret == g.secret }
        assertTrue(gtexts(bg).any { it.endsWith("after removal") })
        assertEquals(au, bg.owner)                    // still the same group, same owner
    }

    /** An authenticated leave makes the owner re-key, so the departed member is really gone. */
    @Test fun leaveTriggersOwnerRekey() {
        assumeTrue(Relay(server).ping())
        val (al, bo, ci) = threeInAGroup("lv")
        val bu = "lvb_$tag"
        val g = al.groups.first()

        bo.leaveGroup(bo.groups.first())             // sends an authenticated gctl leave
        al.poll(5)                                   // owner sees it and re-keys Bo out
        assertEquals(1, al.group(g.id)!!.epoch)
        assertFalse(bu in al.group(g.id)!!.members)

        ci.poll(5); al.poll(5)                        // Ci migrates to the new key; Al gets Ci's new sender key
        al.sendGroup(al.group(g.id)!!, "just us now")
        ci.poll(5); ci.poll(5)
        val cg = ci.groups.first { it.secret == g.secret }
        assertTrue(gtexts(cg).any { it.endsWith("just us now") })
    }

    /** Only the owner may re-key; a member can't. */
    @Test fun nonOwnerCannotRekey() {
        assumeTrue(Relay(server).ping())
        val (al, bo, ci) = threeInAGroup("no")
        val cu = "noc_$tag"
        try { bo.rekeyGroup(bo.groups.first(), setOf(cu)); fail("non-owner re-key should be refused") }
        catch (e: UserError) { assertTrue(e.message!!.contains("owner")) }
    }

    /** A forged (unauthenticated) outer "leave" can't remove anyone from an owner-anchored group. */
    @Test fun forgedOuterLeaveCannotKick() {
        assumeTrue(Relay(server).ping())
        val (al, bo, ci) = threeInAGroup("fk")
        val cu = "fkc_$tag"
        val g = al.groups.first()

        bo.postRaw(bo.groups.first(), org.json.JSONObject().put("t", "leave").put("u", cu))  // forge "Ci left"
        bo.leaveGroup(bo.groups.first())             // Bo genuinely leaves (authenticated)
        al.poll(5)                                   // owner ignores the forgery, re-keys only Bo out
        ci.poll(5); al.poll(5)                        // Ci migrates to the new key
        al.sendGroup(al.group(g.id)!!, "ci still here")
        ci.poll(5); ci.poll(5)
        val cg = ci.groups.first { it.secret == g.secret }
        assertTrue("Ci must still be in the group after a forged leave", gtexts(cg).any { it.endsWith("ci still here") })
    }

    /** Dev-only: proves the raw-socket SOCKS5 transport reaches a .onion relay over Tor.
     *  Run with ONION=<addr> and a Tor SOCKS proxy on 127.0.0.1:9050. Skipped otherwise. */
    @Test fun torTransportReachesOnionOverSocks() {
        val onion = System.getenv("ONION") ?: return
        val route = Net.Route(onion, 80, tls = false, socksPort = 9050)
        val resp = Net.request(route, "POST", "/lookup", "application/json",
            "{\"user\":\"nonexistent_probe_$tag\"}".toByteArray(), readMs = 60_000)
        assertEquals(404, resp.code)
        assertTrue(String(resp.body).contains("no such user"))
    }

    /** Dev-only: the long-poll /fetch works over Tor (transport check, no accounts). ONION=<addr>, tor SOCKS 9050. */
    @Test fun onionFetchLongPoll() {
        val onion = System.getenv("ONION") ?: return
        val route = Net.Route(onion, 80, tls = false, socksPort = 9050)
        val body = "{\"tokens\":[\"${"a".repeat(64)}\"],\"ack\":[],\"wait\":0}".toByteArray()
        val r0 = Net.request(route, "POST", "/fetch", "application/json", body, readMs = 30_000)
        assertEquals(200, r0.code)
        assertTrue(String(r0.body).contains("msgs"))
        // wait=4 should hold ~4s then return (verifies a held long-poll survives over Tor)
        val t = System.currentTimeMillis()
        val r4 = Net.request(route, "POST", "/fetch", "application/json",
            "{\"tokens\":[\"${"b".repeat(64)}\"],\"ack\":[],\"wait\":4}".toByteArray(), readMs = 30_000)
        assertEquals(200, r4.code)
        assertTrue("long-poll returned too fast: ${System.currentTimeMillis() - t}ms", System.currentTimeMillis() - t >= 3_000)
    }

    /** Dev-only: full 1:1 send+receive over Tor to the live onion. Creates throwaway accounts on prod. */
    @Test fun onionFullMessagingFlow() {
        val onion = (System.getenv("ONION") ?: return).let { if (it.startsWith("http")) it else "http://$it" }
        val socks = { 9050 as Int? }
        val a = Core(onion, socks); val b = Core(onion, socks)
        val au = "ota_$tag"; val bu = "otb_$tag"
        a.signup(au, "a solid long password", "A"); b.signup(bu, "b solid long password", "B")
        a.addFriend(bu); b.poll(30); b.accept(b.contact(au)!!); a.poll(30)
        val m = a.queue(a.contact(bu)!!, "hello over tor"); a.deliver(a.contact(bu)!!, m)
        assertEquals(Msg.SENT, m.state)
        b.poll(30)
        assertTrue("B did not receive A's message over Tor", b.contact(au)!!.msgs.any { it.text == "hello over tor" })
    }

    /** Reproduces the account-switch bug: logging back in (as when switching accounts on one phone)
     *  must not discard the prekeys a pending friend request was encrypted to. */
    @Test fun reloginKeepsInflightRequest() {
        assumeTrue(Relay(server).ping())
        val a = Core(server); val b = Core(server)
        val au = "rla_$tag"; val bu = "rlb_$tag"
        a.signup(au, "a solid long password", "A")
        b.signup(bu, "b solid long password", "B")        // B publishes prekeys P_B1
        val kept = SignalStore.fromJson(b.me!!.signal.toJson())   // what logOut archives on this phone
        a.addFriend(bu)                                    // A sends a request to B, encrypted to P_B1
        // B "switches away and back" on the same phone: a fresh client logs in, keeping B's archived keys
        val b2 = Core(server)
        b2.login(bu, "b solid long password", kept)        // same-phone switch: keys preserved
        b2.poll(8)
        assertTrue("B should still receive A's pending request after logging back in", b2.contact(au) != null)
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
