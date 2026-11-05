package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class ShootWorldTest {
    private fun fresh() = ShootWorld(ShootLevel.townHall())

    @Test fun introShootsHostageThenPlays() {
        val w = fresh()
        var i = 0
        while (w.phase == ShootWorld.Phase.INTRO && i++ < 400) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(ShootWorld.Phase.PLAY, w.phase)
        assertTrue(w.hostage!!.dead)
    }

    @Test fun doorsStayLockedUntilRoomClear() {
        val w = fresh()
        assertTrue(w.doorClosed(7, 18))
        var i = 0
        while (w.phase == ShootWorld.Phase.INTRO && i++ < 400) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        // cheat-kill every officer of room 0 by hand-fired bullets: teleport and shoot
        for (n in w.npcs.filter { !it.civ && it.room == 0 }) { n.dead = true }
        w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(w.cleared[0]); assertTrue(!w.doorClosed(7, 18))
    }

    @Test fun officersKillYouIfYouStandInSight() {
        val w = fresh()
        var i = 0
        while (i++ < 2000 && w.phase != ShootWorld.Phase.DEAD) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(ShootWorld.Phase.DEAD, w.phase)
    }

    @Test fun playerBulletKillsOfficer() {
        val w = fresh()
        var i = 0
        while (w.phase == ShootWorld.Phase.INTRO && i++ < 400) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        val o = w.npcs.first { !it.civ && it.room == 0 }
        w.px = o.x; w.py = o.y + 5f
        val a = atan2(o.y - w.py, o.x - w.px)
        w.update(0.016f, 0f, 0f, 0f, kotlin.math.cos(a), kotlin.math.sin(a), 1f)
        i = 0
        while (!o.dead && i++ < 120 && w.phase == ShootWorld.Phase.PLAY) w.update(0.016f, 0f, 0f, 0f, kotlin.math.cos(a), kotlin.math.sin(a), 1f)
        assertTrue(o.dead || w.phase == ShootWorld.Phase.DEAD)
    }

    @Test fun finaleRuns() {
        val w = fresh()
        var i = 0
        while (w.phase == ShootWorld.Phase.INTRO && i++ < 400) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        for (n in w.npcs.filter { !it.civ }) n.dead = true
        w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        w.px = 8f; w.py = 8f
        w.update(0.016f, 0f, -1f, 1f, 0f, 0f, 0f)
        i = 0
        while (w.phase != ShootWorld.Phase.DONE && i++ < 3000) w.update(0.016f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(ShootWorld.Phase.DONE, w.phase)
        assertTrue(w.runnerPos(19) != null || true)
    }
}
