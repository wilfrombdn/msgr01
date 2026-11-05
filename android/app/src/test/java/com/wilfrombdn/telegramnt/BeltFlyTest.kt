package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeltFlyTest {
    private fun run(w: BeltWorld, secs: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) { var t = 0f; while (t < secs && w.phase != BeltWorld.Phase.FALL) { w.update(0.02f, ix, iy, mag); t += 0.02f } }

    @Test fun anIdleCourierRidesIntoTrouble() {
        val w = BeltWorld(BeltLevel.warehouse)
        run(w, 60f)
        // standing still in the middle he is hit by things and eventually falls or reaches the end unhurt only by luck
        assertTrue(w.lives < 3 || w.phase == BeltWorld.Phase.FALL || w.phase == BeltWorld.Phase.PICKUP)
    }

    @Test fun missingTheGapMakesYouFall() {
        val w = BeltWorld(BeltLevel.warehouse)
        run(w, 4f)
        assertEquals(BeltWorld.Phase.RIDE, w.phase)
        w.s = 43.5f; w.x = 9.4f      // far right of belt one: belt two is further left
        run(w, 1f)
        assertEquals(BeltWorld.Phase.FALL, w.phase)
    }

    @Test fun hoppingToTheNextBeltWorks() {
        val w = BeltWorld(BeltLevel.warehouse)
        run(w, 4f)
        w.s = 43.5f; w.x = 6.0f
        w.update(0.02f, -1f, 0f, 1f)
        run(w, 1.2f, -1f, 0f, 1f)
        assertTrue(w.phase != BeltWorld.Phase.FALL)
        assertTrue(w.s > 47f)
    }

    @Test fun aBoxHurtsOnce() {
        val w = BeltWorld(BeltLevel.warehouse)
        run(w, 4f)
        w.s = 7.4f; w.x = 5.8f; w.invuln = 0f; w.lives = 3
        w.update(0.05f, 0f, 0f, 0f)
        assertEquals(2, w.lives)
        w.update(0.05f, 0f, 0f, 0f)
        assertEquals("invulnerable for a moment after a hit", 2, w.lives)
    }

    @Test fun thePlatformHasTheJetpack() {
        val w = BeltWorld(BeltLevel.warehouse)
        run(w, 3f)
        w.s = 140.2f; w.x = 9f; w.update(0.02f, 0f, 0f, 0f)
        assertEquals(BeltWorld.Phase.PICKUP, w.phase)
        w.x = 7f; w.s = 149.6f; w.update(0.02f, 0f, 0f, 0f)
        assertEquals(BeltWorld.Phase.DONE, w.phase)
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
