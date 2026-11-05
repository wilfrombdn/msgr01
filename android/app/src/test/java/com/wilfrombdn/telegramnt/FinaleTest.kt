package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FinaleTest {
    private fun save(b: Bitmap, name: String) {
        val big = Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, false)
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun run(w: FinaleWorld, sec: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) { var t = sec; while (t > 0f) { w.update(0.02f, ix, iy, mag); t -= 0.02f } }
    private fun until(w: FinaleWorld, s: FinaleWorld.Stage, max: Float = 60f) { var t = 0f; while (w.stage != s && t < max) { w.update(0.02f); t += 0.02f }; assertEquals(s, w.stage) }

    @Test fun theCutscenesLeadIntoTheClimb() {
        val w = FinaleWorld()
        until(w, FinaleWorld.Stage.INTRO)
        until(w, FinaleWorld.Stage.CLIMB)
        assertEquals(1f, w.tilt, 0.001f)
        assertEquals(FinaleWorld.TOP.toFloat(), 6f, 0f)
        assertEquals(FinaleWorld.base(FinaleWorld.TOP), w.gy, 0.5f)       // he made it to the top
    }

    @Test fun youCanClimbALadderAndJump() {
        val w = FinaleWorld(); w.debugEnter(FinaleWorld.Stage.CLIMB)
        // walk right to the first ladder (x 204), then climb
        while (w.px < 203.5f) w.update(0.02f, 1f, 0f, 1f)
        run(w, 2.5f, 0f, -1f, 1f)
        assertTrue("climbed to floor 1, got ${w.floor} (hit ${w.hitT})", w.floor == 1 || w.hitT >= 0f)
        val w2 = FinaleWorld(); w2.debugEnter(FinaleWorld.Stage.CLIMB)
        w2.jump(); run(w2, 0.3f); assertTrue(w2.jumpH > 10f)
        run(w2, 0.5f); assertEquals(0f, w2.jumpH, 0.01f)
    }

    @Test fun spoolsRollDownAndHitYouUnlessYouJump() {
        val w = FinaleWorld(); w.debugEnter(FinaleWorld.Stage.CLIMB)
        var hit = false; var t = 0f
        while (t < 40f && !hit) { w.update(0.02f); t += 0.02f; if (w.hitT >= 0f) hit = true }
        assertTrue("a spool should reach you on the bottom catwalk if you stand still", hit)
        run(w, 2f)
        assertEquals(2, w.lives)
    }

    @Test fun reachingTheTopEndsItThenSimonThenTheEnd() {
        val w = FinaleWorld(); w.debugEnter(FinaleWorld.Stage.EXECUTE)
        until(w, FinaleWorld.Stage.DOOR); until(w, FinaleWorld.Stage.CONTROL); until(w, FinaleWorld.Stage.SIMON)
        // a wrong press slows it down and drops you back
        run(w, 2.5f)
        w.simonTap((w.seq[0] + 1) % 4); assertEquals(1, w.simonFails)
        for (round in 1..FinaleWorld.SIMON_GOAL) {
            var g = 0f; while (w.showing && g < 30f) { w.update(0.02f); g += 0.02f }
            assertFalse(w.showing)
            for (i in 0 until w.round) w.simonTap(w.seq[i])
        }
        until(w, FinaleWorld.Stage.BROADCAST)
        until(w, FinaleWorld.Stage.END)
        run(w, 3f); assertTrue(w.silent)
        run(w, FinaleWorld.END_TAP); assertTrue(w.over)
    }

    @Test fun shots() {
        val w = FinaleWorld(); val s = FinaleScene(w)
        var t = 0f
        fun step(sec: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) { val end = t + sec; while (t < end) { w.update(0.02f, ix, iy, mag); t += 0.02f; s.render(t) } }
        step(1.5f); save(s.render(t), "l6-beach-boat")
        step(3.5f); save(s.render(t), "l6-beach-points")
        step(2.0f); save(s.render(t), "l6-beach-spotted")
        step(3.0f); save(s.render(t), "l6-beach-go")
        step(2.4f); save(s.render(t), "l6-beach-kick")
        step(1.6f); save(s.render(t), "l6-intro-zoom")
        step(3.0f); save(s.render(t), "l6-intro-climbs")
        step(2.0f); save(s.render(t), "l6-intro-tilt")
        step(3.5f); save(s.render(t), "l6-climb-start")
        step(4.0f, 1f, 0f, 1f); save(s.render(t), "l6-climb-run")
        w.debugEnter(FinaleWorld.Stage.EXECUTE); step(1.0f); save(s.render(t), "l6-execute-kneel")
        step(1.95f); save(s.render(t), "l6-execute-shot")
        w.debugEnter(FinaleWorld.Stage.DOOR); step(2.6f); save(s.render(t), "l6-door")
        w.debugEnter(FinaleWorld.Stage.CONTROL); step(3f); save(s.render(t), "l6-control-walk")
        step(3.7f); save(s.render(t), "l6-control-bomb")
        step(2.5f); save(s.render(t), "l6-control-look")
        w.debugEnter(FinaleWorld.Stage.SIMON); step(1.0f); save(s.render(t), "l6-simon")
        w.debugEnter(FinaleWorld.Stage.BROADCAST)
        for (i in 0 until FinaleWorld.VIGNETTES) { step(1.7f); save(s.render(t), "l6-broadcast-$i"); step(FinaleWorld.VIGNETTE_S - 1.7f) }
        w.debugEnter(FinaleWorld.Stage.END); step(1.5f); save(s.render(t), "l6-end-broadcast")
        step(8.5f); save(s.render(t), "l6-end-words")
        step(3.0f); save(s.render(t), "l6-end-sirens")
    }
}
