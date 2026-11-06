package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ShootWorldTest {
    private fun fresh() = ShootWorld(ShootLevel.townHall())
    private fun step(w: ShootWorld, secs: Float, fire: Boolean = false) { var t = 0f; while (t < secs) { w.update(0.016f, 0f, 0f, 0f, fire); t += 0.016f } }
    private fun intoPlay(w: ShootWorld) { var i = 0; while (w.phase == ShootWorld.Phase.INTRO && i++ < 600) w.update(0.016f, 0f, 0f, 0f, false) }

    @Test fun everyoneStartsOnFreeFloor() {
        val w = fresh()
        for (n in w.npcs) {
            val ch = w.tile(n.x.toInt(), n.y.toInt())
            assertTrue("npc ${n.id} at ${n.x},${n.y} stands on '$ch'", ch == '.')
        }
    }

    @Test fun ambushShootsTheHostageAndYouDiveBehindATable() {
        val w = fresh()
        step(w, 1.4f)
        assertEquals(ShootWorld.Phase.INTRO, w.phase)
        assertTrue("officers aim at you", w.npcs.filter { !it.civ && !it.hostage }.all { it.state == ShootWorld.AIMED })
        assertFalse(w.hostage!!.dead)
        assertTrue("he's screaming for help", w.hostageLine() != null)
        step(w, ShootWorld.SHOT_T - 1.4f + 0.05f)       // everyone aiming while he screams, then the shot cuts him off
        assertTrue(w.hostage!!.dead); assertEquals(null, w.hostageLine())
        intoPlay(w)
        assertEquals(ShootWorld.Phase.PLAY, w.phase)
        assertEquals(1, w.tipped.size)
        val tb = w.tipped.first()
        assertTrue("you land next to the table", hypot(w.px - (tb.first + 0.5f), w.py - (tb.second + 0.5f)) < 1.6f)
        assertTrue(w.npcs.filter { !it.civ && !it.hostage }.all { it.state == ShootWorld.ENGAGE })
    }

    @Test fun lobbyClearOpensTheHallAndTheClerksRunOut() {
        val w = fresh(); intoPlay(w)
        assertTrue(w.doorClosed(7, 32))
        for (n in w.npcs.filter { !it.civ && it.room == ShootLevel.LOBBY }) n.dead = true
        step(w, 0.05f)
        assertTrue(w.lobbyClear); assertFalse(w.doorClosed(7, 32))
        step(w, 25f)
        assertTrue("every living clerk got out", w.npcs.filter { it.civ && !it.dead }.all { it.gone })
    }

    @Test fun rollMovesYouAndMakesYouHardToHit() {
        val w = fresh(); intoPlay(w)
        val y0 = w.py
        w.roll(0f, 1f)
        step(w, 0.1f)
        assertTrue(w.invulnerable || w.rollT > 0f)
        step(w, 0.5f)
        assertTrue("moved", w.py != y0 || w.px != w.px)
        w.roll(1f, 0f); assertTrue("roll is on cooldown", w.rollT == 0f)
    }

    @Test fun grenadeNeedsATargetInRange() {
        val w = fresh(); intoPlay(w)
        for (n in w.npcs) if (!n.civ && !n.hostage) n.dead = true
        assertEquals("NO TARGET", w.throwGrenade())
        // a pair of officers far across the lobby: out of range
        val a = w.npcs.first { !it.civ && !it.hostage }; val b = w.npcs.last { !it.civ && !it.hostage }
        a.dead = false; b.dead = false
        a.x = w.px; a.y = w.py - 13.0f; b.x = w.px + 0.5f; b.y = w.py - 13.0f
        // make sure there is a clear line: put the player in the open middle row of the lobby
        w.px = 5.5f; w.py = 60.5f; a.x = 5.5f; a.y = 47.5f - 0f; b.x = 6.0f; b.y = 47.5f
        val r = w.throwGrenade()
        assertTrue("got $r", r == "OUT OF RANGE" || r == "NO TARGET")
    }

    @Test fun grenadeKillsAGroupInRange() {
        val w = fresh(); intoPlay(w)
        for (n in w.npcs) if (!n.civ && !n.hostage) n.dead = true
        val offs = w.npcs.filter { !it.civ && !it.hostage }.take(3)
        w.px = 5.5f; w.py = 60.5f
        offs[0].dead = false; offs[0].x = 5.5f; offs[0].y = 54.0f
        offs[1].dead = false; offs[1].x = 6.3f; offs[1].y = 54.2f
        offs[2].dead = false; offs[2].x = 5.0f; offs[2].y = 53.8f
        val why = w.throwGrenade(); assertNull("why: $why", why)
        assertEquals(4, w.grenades)
        w.diving = true; step(w, 3f); w.diving = false
        assertTrue("whole group down", offs.all { it.dead })
        assertTrue(w.phase == ShootWorld.Phase.PLAY)
    }

    @Test fun gunKillsTheClosestOfficerYouCanSee() {
        val w = fresh(); intoPlay(w)
        for (n in w.npcs) if (!n.civ && !n.hostage) n.dead = true
        val o = w.npcs.first { !it.civ && !it.hostage }
        w.px = 5.5f; w.py = 60.5f; o.dead = false; o.x = 5.5f; o.y = 56.5f; o.state = ShootWorld.IDLE
        assertNotNull(w.nearestTarget())
        var i = 0
        while (!o.dead && i++ < 200 && w.phase == ShootWorld.Phase.PLAY) w.update(0.016f, 0f, 0f, 0f, true)
        assertTrue(o.dead || w.phase == ShootWorld.Phase.DEAD)
    }

    @Test fun coverSoaksUpBulletsAimedAtYouBehindFurniture() {
        var soaked = 0; var hits = 0
        for (seed in 0 until 60) {
            val w = ShootWorld(ShootSpec(ShootLevel.townHall().map, emptyList(), emptyList(), emptyList(), emptyList(), 7.5f to 63.5f, seed * 7919 + 1234567))
            w.phase = ShootWorld.Phase.PLAY
            // you crouch behind the reception desk (row 55), a shooter fires from the north
            w.px = 7.5f; w.py = 56.4f
            w.bullets += ShootWorld.Bullet(7.5f, 52f, 0f, 12f, false)
            var i = 0
            while (w.bullets.isNotEmpty() && i++ < 100) w.update(0.016f, 0f, 0f, 0f, true)    // peeking out to shoot
            if (w.phase == ShootWorld.Phase.DEAD) hits++ else soaked++
        }
        assertTrue("peeking from cover, about 9 in 10 miss ($soaked saved, $hits hit)", soaked in 48..60 && hits > 0)
    }

    @Test fun clearingEverythingAndKickingTheDoorRunsTheEnding() {
        val w = fresh(); intoPlay(w)
        for (n in w.npcs.filter { !it.civ && !it.hostage }) n.dead = true
        step(w, 0.05f)
        assertTrue(w.mayorUnlocked)
        w.px = 7.5f; w.py = 12.6f
        w.update(0.016f, 0f, -1f, 1f, false)
        var i = 0
        while (w.phase != ShootWorld.Phase.DONE && i++ < 3000) w.update(0.016f, 0f, 0f, 0f, false)
        assertEquals(ShootWorld.Phase.DONE, w.phase)
        assertFalse(w.doorClosed(7, 11))
    }

    @Test fun pressedToAWallWithoutShootingYouCantBeHit() {
        val w = fresh(); intoPlay(w)
        w.px = 1.4f; w.py = 45.5f                         // against the west wall of the lobby
        assertTrue("against the wall", w.nearWall)
        var i = 0
        while (i++ < 600) w.update(0.016f, 0f, 0f, 0f, false)
        assertTrue("hidden", w.hidden); assertEquals(ShootWorld.Phase.PLAY, w.phase)
        w.update(0.016f, 0f, 0f, 0f, true); assertFalse("firing gives you away", w.hidden)
    }

    @Test fun aGrenadeGoesWhereYouDragIt() {
        val w = fresh(); intoPlay(w)
        w.px = 5.5f; w.py = 60.5f
        w.aimGrenade(0f, -1f, 0.5f)
        val at = w.aimAt!!
        assertEquals(5.5f, at.first, 0.05f); assertEquals(60.5f - (ShootWorld.THROW_MIN + (ShootWorld.THROW_MAX - ShootWorld.THROW_MIN) * 0.5f), at.second, 0.3f)
        assertNull(w.throwAimed()); assertEquals(4, w.grenades); assertNull(w.aimAt)
    }

    @Test fun mostOfficersHoldTheirGround() {
        val w = fresh(); intoPlay(w)
        w.px = 7.5f; w.py = 45.5f; w.face = 0f
        val campers = w.npcs.filter { !it.civ && !it.hostage && it.camper }
        assertTrue("most of them", campers.size * 10 >= w.npcs.count { !it.civ && !it.hostage } * 6)
        var i = 0
        while (i++ < 400 && w.phase == ShootWorld.Phase.PLAY) w.update(0.016f, 0f, 0f, 0f, false)
        for (n in campers) if (!n.dead) assertTrue("${n.id} stayed near his post", kotlin.math.hypot(n.x - n.homeX, n.y - n.homeY) < 5f)
    }

    @Test fun officersKillYouIfYouStandInTheOpen() {
        val w = fresh(); intoPlay(w)
        w.px = 7.5f; w.py = 45.5f; w.face = 0f
        var i = 0
        while (i++ < 4000 && w.phase != ShootWorld.Phase.DEAD) w.update(0.016f, 0f, 0f, 0f, false)
        assertEquals(ShootWorld.Phase.DEAD, w.phase)
    }
}
