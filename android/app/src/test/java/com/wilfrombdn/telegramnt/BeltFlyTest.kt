package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeltFlyTest {
    private fun spec() = BeltLevel.warehouse

    /** A bot that looks ahead: it leaps away from trouble, jumps what it cannot leap round, and drifts towards coins. */
    private fun bot(w: BeltWorld) {
        if (w.phase != BeltWorld.Phase.RIDE) return
        val sp = w.speedNow
        val far = 3.0f + sp * 0.55f
        fun threats(lane: Int): List<BeltObs> {
            val out = ArrayList<BeltObs>()
            for (o in w.spec.obstacles) {
                val ahead = o.curS - w.s
                if (ahead < -1.2f || ahead > far) continue
                val rel = if ((o.kind == BeltWorld.FLYER) && o.armed) sp + 7f else if (o.kind == BeltWorld.TRUCK && o.armed) sp + 5f else sp
                val tt = (ahead / rel).coerceAtLeast(0f)
                val half = (o.hs + 0.5f) / rel
                if (listOf(-1f, -0.5f, 0f, 0.5f, 1f).none { w.liveAt(o, w.t + tt + it * half) }) continue
                if (kotlin.math.abs(w.obsXAt(o, w.t + tt) - w.spec.laneX[lane]) < o.hw + 0.95f) out += o
            }
            return out
        }
        val here = threats(w.lane)
        if (here.isNotEmpty()) {
            val best = (0..2).filter { threats(it).isEmpty() }.minByOrNull { kotlin.math.abs(it - w.lane) }
            if (best != null) { if (!w.leaping) w.leap(if (best > w.lane) 1 else -1); return }
            if (!w.jumping && here.all { w.isLow(it) }) {
                val o = here.minByOrNull { it.curS }!!
                val enter = (o.curS - w.s - o.hs - 0.4f) / sp
                if (enter <= 0.14f) w.jump()
            }
            return
        }
        if (w.leaping) return
        // nothing in the way: drift to the belt with the most coins ahead
        fun coinsIn(l: Int) = w.spec.coins.count { !it.taken && !it.high && it.lane == l && it.s > w.s && it.s < w.s + 14f }
        val adj = listOf(w.lane - 1, w.lane + 1).filter { it in 0..2 && threats(it).isEmpty() }
        val pick = adj.maxByOrNull { coinsIn(it) }
        if (pick != null && coinsIn(pick) > coinsIn(w.lane)) w.leap(pick - w.lane)
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

    @Test fun jumpingClearsABarrierButWalkingIntoItHurts() {
        fun at(jump: Boolean): Int {
            val w = BeltWorld(spec()); run(w, 3f); w.spec.extend(450f)
            val b = w.spec.obstacles.first { it.kind == BeltWorld.SMALL && it.x == w.spec.laneX[1] }
            w.s = b.s - 5f; w.x = w.spec.laneX[1]; w.lane = 1; w.invuln = 0f; w.lives = 3
            for (o in w.spec.obstacles) if (o !== b && o.s in (b.s - 8f)..(b.s + 8f)) o.curS = -999f
            val sp = w.speedNow
            var tt = 0f
            var lv = w.lives
            while (tt < 1.5f) { if (w.lives != lv) { lv = w.lives; println("lost life jump=$jump tt=$tt s=${w.s} bs=${b.s} air=${w.air} ja=${w.jumpAir} sp=${w.speedNow}") }; if (jump && tt >= (5f - 0.5f - 0.4f) / sp - 0.2f && !w.jumping && tt < 1f) w.jump(); w.update(0.01f); tt += 0.01f }
            return w.lives
        }
        val a = at(true); val b = at(false); println("barrier: jumped=$a walked=$b"); assertEquals(3, a); assertTrue(b < 3)
    }

    @Test fun aHoleInTheBeltNeedsAJump() {
        val w = BeltWorld(spec()); run(w, 3f); w.spec.extend(450f)
        val g = w.spec.obstacles.first { it.kind == BeltWorld.GAP }
        w.s = g.s - 5f; w.x = g.x; w.lane = w.spec.laneX.indexOfFirst { it == g.x }; w.invuln = 0f; w.lives = 3
        run(w, 2f)
        assertTrue(w.lives < 3)
    }

    @Test fun coinsAreCollectedAndTheEndWaitsForHundred() {
        val w = BeltWorld(spec()); run(w, 3f); w.spec.extend(450f)
        w.s = 200f; w.x = w.spec.laneX[1]; w.lane = 1
        val c = w.spec.coins.first { it.lane == 1 && !it.high && it.s > 205f }
        w.s = c.s - 0.3f; w.invuln = 99f
        run(w, 0.2f)
        assertTrue(w.coins >= 1)
        // short of coins, the end keeps moving away
        w.coins = 10; w.s = w.endS - 20f; run(w, 1.0f)
        assertTrue("belts run on", w.endS > w.spec.endS)
    }

    @Test fun aBotThatLooksAheadFinishesWithHundredCoins() {
        val w = BeltWorld(spec())
        var last = w.lives; var t = 0f
        while (t < 400f && (w.phase == BeltWorld.Phase.INTRO || w.phase == BeltWorld.Phase.RIDE)) {
            bot(w); w.update(0.01f); t += 0.01f
            if (w.lives != last) { last = w.lives; val o = w.spec.obstacles.minByOrNull { kotlin.math.abs(it.curS - w.s) }!!; println("HIT kind=${o.kind} s=${w.s} lane=${w.lane} x=${w.x} ox=${w.obsX(o)} os=${o.curS} sp=${w.speedNow} jump=${w.jumping}") }
        }
        println("belt bot: phase=${w.phase} lives=${w.lives} hits=${w.hits} coins=${w.coins} s=${w.s} endS=${w.endS} time=$t")
        assertTrue("reaches the kicker", w.phase == BeltWorld.Phase.LAUNCH || w.phase == BeltWorld.Phase.PICKUP || w.phase == BeltWorld.Phase.DONE)
        assertTrue(w.coins >= 100)
        assertTrue(w.lives >= 1)
    }

    @Test fun theKickerThrowsYouToTheJetpack() {
        val w = BeltWorld(spec())
        run(w, 3f)
        w.coins = 100; w.s = w.endS - 0.05f; w.invuln = 99f
        run(w, 6f)
        assertEquals(BeltWorld.Phase.DONE, w.phase)
        assertTrue(w.s > w.endS + 8f)
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
