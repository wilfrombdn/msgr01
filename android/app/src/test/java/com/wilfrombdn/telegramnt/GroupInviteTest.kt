package com.wilfrombdn.telegramnt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** A group invitation is a request: nothing is joined, and no sender key goes into the group, until the person accepts. */
class GroupInviteTest {
    private val server = System.getenv("RELAY") ?: "http://127.0.0.1:8099"
    private val tag = Crypto.randomHex(3)

    @Before fun noAutoJoin() { Crypto.fastKdfForTests(); Core.testAutoJoin = false }
    @After fun restore() { Core.testAutoJoin = false }

    private fun friends(a: Core, au: String, b: Core, bu: String) {
        a.addFriend(bu); b.poll(5); b.accept(b.contact(au)!!); a.poll(5)
    }

    @Test fun invitationWaitsForAYes() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "ivy_$tag"; val bu = "ben_$tag"
        al.signup(au, "ivys long password", "Ivy"); bo.signup(bu, "bens long password", "Ben")
        friends(al, au, bo, bu)
        val g = al.createGroup("Road Crew", public = false, invite = listOf(bu))

        bo.poll(5)
        assertTrue("not joined by itself", bo.groups.isEmpty())
        val inv = bo.pendingInvites().single()
        assertEquals(au, inv.from); assertEquals("Road Crew", inv.name)
        assertFalse("nobody has seen Ben in the group", al.group(g.id)!!.members.contains(bu) && al.group(g.id)!!.msgs.any { it.text.contains("$bu joined") })
        assertTrue("the invitation is explained in the chat", bo.contact(au)!!.msgs.any { it.text.contains("invited you") })

        // survives a restart
        val reloaded = Core(server); reloaded.loadJson(bo.toJson())
        assertEquals(1, reloaded.pendingInvites().size)

        bo.acceptInvite(inv)
        assertEquals(0, bo.pendingInvites().size)
        val gb = bo.groups.single { it.secret == g.secret }
        al.sendGroup(al.group(g.id)!!, "hello crew"); bo.poll(5)
        assertTrue(gb.msgs.any { it.text.endsWith("hello crew") })
    }

    @Test fun decliningJoinsNothingAndTellsNobody() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "dee_$tag"; val bu = "bo_$tag"
        al.signup(au, "dees long password", "Dee"); bo.signup(bu, "bos long password", "Bo")
        friends(al, au, bo, bu)
        al.createGroup("Nope", public = false, invite = listOf(bu))
        bo.poll(5)
        bo.declineInvite(bo.pendingInvites().single())
        assertEquals(0, bo.pendingInvites().size)
        assertTrue(bo.groups.isEmpty())
        bo.poll(5)
        assertTrue("the same secret doesn't come back by itself", bo.pendingInvites().isEmpty())
    }

    @Test fun onlyFriendsCanInviteAndTheQueueIsBounded() {
        assumeTrue(Relay(server).ping())
        val al = Core(server); val bo = Core(server)
        val au = "fay_$tag"; val bu = "gus_$tag"
        al.signup(au, "fays long password", "Fay"); bo.signup(bu, "guss long password", "Gus")
        friends(al, au, bo, bu)
        for (i in 1..Core.MAX_INVITES + 5) al.createGroup("G$i", public = false, invite = listOf(bu))
        bo.poll(8)
        assertTrue(bo.pendingInvites().size <= Core.MAX_INVITES)
        assertNull(bo.groups.firstOrNull())
    }
}
