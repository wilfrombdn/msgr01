package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeltFlyTest {
    private fun spec() = BeltLevel.warehouse

    /** A bot that looks ahead and leaps away from trouble. */
    private fun bot(w: BeltWorld) {
        if (w.phase != BeltWorld.Phase.RIDE || w.leaping) return
        val sp = w.speedNow
        fun danger(lane: Int, far: Float): Boolean {
            for (o in w.spec.obstacles) {
                val ahead = o.curS - w.s
                if (ahead < -1.2f || ahead > far) continue
                val rel = if (o.kind == BeltWorld.FLYER && o.armed) sp + 7f else sp
                val tt = (ahead / rel).coerceAtLeast(0f)
                val half = (o.hs + 0.5f) / rel
                if (listOf(-1f, -0.5f, 0f, 0.5f, 1f).none { w.liveAt(o, w.t + tt + it * half) }) continue
                if (kotlin.math.abs(w.obsXAt(o, w.t + tt) - w.spec.laneX[lane]) < o.hw + 0.95f) return true
            }
            return false
        }
        val far = 3.0f + sp * 0.55f
        if (!danger(w.lane, far)) return
        val best = (0..2).filter { !danger(it, far) }.minByOrNull { kotlin.math.abs(it - w.lane) } ?: return
        w.leap(if (best > w.lane) 1 else -1)
    }

    private fun run(w: BeltWorld, secs: Float, useBot: Boolean = false) {
        var t = 0f
        while (t < secs && w.phase != BeltWorld.Phase.FALL && w.phase != BeltWorld.Phase.DONE) { if (useBot) bot(w); w.update(0.01f); t += 0.01f }
    }

    @Test fun anIdleCourierGetsHurt() {
        val w = BeltWorld(spec())
        run(w, 60f)
        assertTrue(w.hits > 0)
    }

    @Test fun leapingChangesBelt() {
        val w = BeltWorld(spec())
        run(w, 3f)
        assertEquals(BeltWorld.Phase.RIDE, w.phase)
        assertEquals(1, w.lane)
        w.leap(-1)
        run(w, 0.4f)
        assertEquals(0, w.lane)
        assertEquals(w.spec.laneX[0], w.x, 0.01f)
        w.leap(-1); run(w, 0.4f)
        assertEquals("cannot leap off the edge", 0, w.lane)
    }

    @Test fun theBeltsSpeedUpToAboutDouble() {
        val w = BeltWorld(spec())
        assertEquals(2.0f, w.speedAt(w.spec.endS) / w.speedAt(0f), 0.05f)
    }

    @Test fun aBoxHurtsOnce() {
        val w = BeltWorld(spec())
        run(w, 1f)
        val box = w.spec.obstacles.first { it.kind == BeltWorld.BOX }
        w.s = box.s - 0.5f; w.x = box.x; w.invuln = 0f; w.lives = 3
        run(w, 0.2f)
        assertEquals(2, w.lives)
        run(w, 0.1f)
        assertEquals("invulnerable for a moment after a hit", 2, w.lives)
    }

    @Test fun aBotThatLooksAheadFinishesWithLivesLeft() {
        val w = BeltWorld(spec())
        var last = w.lives; var t = 0f
        while (t < 80f && (w.phase == BeltWorld.Phase.INTRO || w.phase == BeltWorld.Phase.RIDE)) { bot(w); w.update(0.01f); t += 0.01f; if (w.lives != last) { last = w.lives; val o = w.spec.obstacles.minByOrNull { kotlin.math.abs(it.curS - w.s) }!!; println("HIT kind=${o.kind} s=${w.s} lane=${w.lane} x=${w.x} ox=${w.obsX(o)} os=${o.curS} sp=${w.speedNow} leaping=${w.leaping}") } }
        println("belt bot: phase=${w.phase} lives=${w.lives} hits=${w.hits} s=${w.s}")
        assertTrue("reaches the kicker", w.phase == BeltWorld.Phase.LAUNCH || w.phase == BeltWorld.Phase.PICKUP || w.phase == BeltWorld.Phase.DONE)
        assertTrue(w.lives >= 1)
    }

    @Test fun theKickerThrowsYouToTheJetpack() {
        val w = BeltWorld(spec())
        run(w, 1f)
        w.s = w.spec.endS - 0.05f; w.invuln = 5f
        run(w, 6f)
        assertEquals(BeltWorld.Phase.DONE, w.phase)
        assertTrue(w.s > w.spec.endS + 8f)
    }

    @Test fun flyingCollectsCoinsAndTheJetpackGivesOut() {
        val w = FlyWorld(FlySpec())
        // a simple bot: climb when below the next coin, otherwise fall
        var t = 0f
        while (w.phase == FlyWorld.Phase.FLY && t < 240f) {
            val next = w.coinList.filter { !it.taken && it.wx > w.wx - 0.3f }.minByOrNull { it.wx }
            w.thrust = next != null && next.y < w.y - 0.3f
            w.update(0.02f); t += 0.02f
        }
        println("FLY bot collected ${w.coins} coins in ${"%.1f".format(t)} s")
        assertTrue("the bot gets 100 coins", w.phase != FlyWorld.Phase.FLY)
        var u = 0f; while (!w.finished && u < 12f) { w.update(0.02f); u += 0.02f }
        assertTrue("it ends with the player on the floor", w.landed && w.finished)
    }

    @Test fun aZapperCostsCoins() {
        val w = FlyWorld(FlySpec())
        w.coins = 50
        w.zaps += FlyWorld.Zap(w.wx + 0.1f, 2f, 18f)
        w.update(0.02f)
        assertTrue(w.coins < 50)
    }
}
