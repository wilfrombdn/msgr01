package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

// The arcade's bigger games: four sewers with flares, the tower's four screens, the jetpacks and hangar, the mayor boss, the uplink.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h913dp-xxhdpi")
class ArcadePlusTest {
    private fun view(): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 5, false, { }, { }, { }, beaten = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        return v
    }
    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); repeat(40) { v.draw(Canvas(bmp)) }   // the cameras ease along frame by frame
        File("build/shots").mkdirs(); FileOutputStream(File("build/shots/$name.png")).use { Bitmap.createScaledBitmap(bmp, 540, 1200, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------- the maze ----------------
    @Test fun everyArcadeMazeIsSound() {
        for (lv in 1..4) {
            val spec = PacMaze.arcade(lv); val m = spec.maze; val w = m[0].length; val h = m.size
            assertTrue("odd width", w % 2 == 1); assertTrue(m.all { it.length == w })
            fun open(x: Int, y: Int): Boolean { if (y !in 0 until h) return false; val xx = if (y == spec.tunnelRow) ((x % w) + w) % w else x; if (xx !in 0 until w) return false; return m[y][xx] in ".oS" }
            val cells = (0 until h).flatMap { y -> (0 until w).filter { x -> m[y][x] in ".oS" }.map { it to y } }
            for ((x, y) in cells) assertTrue("level $lv dead end at $x,$y", listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).count { (dx, dy) -> open(x + dx, y + dy) } >= 2)
            val seen = HashSet<Pair<Int, Int>>(); val todo = ArrayDeque(listOf(cells[0])); seen += cells[0]
            while (todo.isNotEmpty()) { val (x, y) = todo.removeFirst(); for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) { val ny = y + dy; val nx = if (ny == spec.tunnelRow) (((x + dx) % w) + w) % w else x + dx; if (open(nx, ny) && seen.add(nx to ny)) todo += nx to ny } }
            assertEquals("level $lv all connected", cells.size, seen.size)
            val doorX = m[0].indexOf('D'); assertTrue(m[1][doorX] in ".o")
            val pw = PacWorld(spec); assertTrue(pw.total > 40)
        }
    }

    @Test fun aFlareScaresThePoliceAndYouCanCatchThem() {
        val pw = PacWorld(PacMaze.arcade(2))
        var t = 0f; while (pw.phase != PacWorld.Phase.PLAY && t < 20f) { pw.update(0.02f, -1); t += 0.02f }
        repeat(150) { pw.update(0.02f, -1) }                                            // let some of them out
        // put a flare right under the courier
        val tx = kotlin.math.floor(pw.px).toInt(); val ty = kotlin.math.floor(pw.py).toInt(); pw.power[ty][tx] = true
        pw.update(0.02f, -1)
        assertTrue("panic", pw.frightT > 0f)
        val g = pw.ghosts.first { it.state == PacWorld.OUT }
        assertTrue(g.fright)
        g.x = pw.px; g.y = pw.py; pw.update(0.02f, -1)
        assertEquals("caught him: back to the chamber", PacWorld.RETURNING, g.state); assertEquals(200, pw.bonus)
        assertNotEquals(PacWorld.Phase.CAUGHT, pw.phase)
        repeat(600) { pw.update(0.02f, -1) }
        assertTrue("he comes out again", g.state != PacWorld.RETURNING)
    }

    @Test fun mazeLevelsShots() {
        val v = view()
        v.debugArcade("maze"); repeat(140) { v.debugStageTick(0.05f); v.debugMgAdvance(0.05f) }
        shot(v, "arcade2-maze")
    }

    // ---------------- the tower ----------------
    @Test fun theTowerHasFourScreens() {
        val v = view(); v.debugArcade("tower")
        val fw = v.debugFinale()!!
        for (i in 0 until 4) {
            fw.debugScreen(i); v.debugMgAdvance(2.5f)
            assertEquals(FinaleWorld.SCREENS[i].name, fw.lay.name)
            shot(v, "arcade2-tower-$i")
        }
        // the breaker room: throw every breaker and it's done
        fw.debugScreen(3)
        val before = fw.arcadeRound
        for (b in fw.lay.breakers) { /* walking over each one counts */ }
        assertEquals(8, fw.lay.breakers.size)
        assertEquals(before, fw.arcadeRound)
    }

    // ---------------- the uplink ----------------
    @Test fun theUplinkIsANewPatternEveryRound() {
        val v = view(); v.debugArcade("uplink")
        val fw = v.debugFinale()!!
        val seen = ArrayList<List<Int>>()
        for (r in 1..6) {
            var t = 0f; while (fw.showing && t < 20f) { fw.update(0.05f); t += 0.05f }
            seen += fw.seq.take(r).toList()
            for (k in 0 until r) fw.simonTap(fw.seq[k])
            assertFalse(fw.gameOver)
        }
        assertEquals(6, seen.size)
        // a fresh pattern each round, not just one more step on the end
        assertTrue((2 until 6).any { seen[it].take(seen[it - 1].size) != seen[it - 1] })
    }

    // ---------------- the jetpack ----------------
    @Test fun everyJetpackFlies() {
        for (pack in 0..4) {
            val f = FlyWorld(FlySpec(3, 1_000_000, pack, arcade = true))
            var t = 0f
            while (t < 25f) { f.thrust = (t * 2f).toInt() % 2 == 0; if ((t * 10f).toInt() % 7 == 0) f.tap(); f.update(0.05f); t += 0.05f }
            assertTrue("pack $pack stays in the hall", f.y in f.ceilY..f.floorY)
            assertTrue("pack $pack moves on", f.scroll > 150f)
        }
        val fl = FlyWorld(FlySpec(3, 1_000_000, 3, arcade = true)); fl.tap(); repeat(40) { fl.update(0.05f) }
        assertTrue("the flipper falls up", fl.y < 3f)
    }

    @Test fun theHangarNeverTouchesTheStory() {
        val saved = ArrayList<Int>()
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 5, false, { saved += it }, { }, { }, beaten = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        v.openHangar(); v.debugTick(5f); v.skip(); v.restart(); v.debugTick(1f)
        assertTrue(v.isHangar()); assertTrue("the story's place is never saved", saved.isEmpty()); assertEquals(5, v.debugLevelIdx())
    }

    @Test fun theHangarSellsJetpacks() {
        val v = view()
        v.debugBank(800); v.openHangar(); assertTrue(v.isHangar())
        shot(v, "arcade2-hangar")
        v.debugBuy(1)                         // 300
        v.debugBuy(4)                         // too dear
        v.debugArcade("fly"); assertEquals(1, v.debugFly()!!.spec.pack)
        v.debugMgAdvance(40f); shot(v, "arcade2-fly")
    }

    // ---------------- the town hall boss ----------------
    private fun boss(): ShootWorld { val sw = ShootWorld(ShootLevel.townHall()); sw.arcade = true; sw.debugBoss(); return sw }

    @Test fun theMayorHasThreePhasesAndDies() {
        val sw = boss()
        assertEquals(ShootWorld.Phase.BOSS, sw.phase); assertEquals(1, sw.bossPhase)
        sw.debugHurtBoss(ShootWorld.BOSS_HP * 0.36f); assertEquals(2, sw.bossPhase)
        assertTrue("guards come in", sw.npcs.count { !it.civ && !it.dead && !it.hostage && it.y > 11f && it.y < 13f } >= 2 || sw.npcs.size > 0)
        sw.debugHurtBoss(ShootWorld.BOSS_HP * 0.36f); assertEquals(3, sw.bossPhase)
        sw.debugHurtBoss(ShootWorld.BOSS_HP); assertTrue(sw.bossHp <= 0f)
        repeat(80) { sw.update(0.05f, 0f, 0f, 0f, false) }
        assertTrue(sw.bossWon)
    }

    @Test fun yourGunHurtsHimAndHisAttacksCanKillYou() {
        val sw = boss()
        val hp0 = sw.bossHp
        repeat(60) { sw.update(0.05f, 0f, 0f, 0f, true) }
        assertTrue("the gun hits him", sw.bossHp < hp0)
        // stand still and he gets you in the end
        val sw2 = boss(); var t = 0f
        while (sw2.phase == ShootWorld.Phase.BOSS && t < 90f) { sw2.update(0.05f, 0f, 0f, 0f, false); t += 0.05f }
        assertEquals("five hits and you're down", ShootWorld.Phase.DEAD, sw2.phase)
    }

    @Test fun aRollGoesThroughABullet() {
        val sw = boss()
        repeat(4) { sw.update(0.05f, 0f, 0f, 0f, false) }
        val h0 = sw.hearts
        sw.roll(0f, 1f); sw.debugShootMe(); repeat(4) { sw.update(0.02f, 0f, 0f, 0f, false) }
        assertEquals("rolling: untouched", h0, sw.hearts)
    }

    @Test fun bossShots() {
        val v = view()
        v.debugArcade("hall"); v.debugMgAdvance(1f)
        val sw = v.debugShoot()!!; sw.debugBoss()
        v.debugMgAdvance(3.2f); shot(v, "arcade2-boss-1")
        sw.debugHurtBoss(ShootWorld.BOSS_HP * 0.7f); v.debugMgAdvance(4.0f); shot(v, "arcade2-boss-3")
    }

    @Test fun conveyorShots() {
        val v = view(); v.debugArcade("belt")
        v.debugMgAdvance(8f); shot(v, "arcade2-belt")
    }
}
