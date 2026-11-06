package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

// Close-ups of the people (you as the clerk from every side, the cop with his head bowed) and frames of the climb down the manhole.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SpriteSheetTest {
    private class Sheet : PixelSprites() {
        fun draw(c: Canvas) {
            c.drawColor(0xFF1A2030.toInt())
            for (d in 0..3) folk(c, 12 + d * 22, 30, d, 0, clerk, false, false)
            folk(c, 12 + 4 * 22, 30, 2, 1, clerk, false, false)
            folk(c, 12, 62, 2, 0, police, false, false)
            folk(c, 34, 62, 2, 0, police, false, false, sorrow = true)
            folk(c, 56, 62, 0, 0, police, false, false, sorrow = true)
            folk(c, 78, 62, 1, 0, police, false, false, sorrow = true)
        }
    }
    private fun save(b: Bitmap, name: String, k: Int) {
        val big = Bitmap.createScaledBitmap(b, b.width * k, b.height * k, false)
        File("build/shots").mkdirs(); FileOutputStream(File("build/shots/$name.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun people() { val b = Bitmap.createBitmap(112, 72, Bitmap.Config.ARGB_8888); Sheet().draw(Canvas(b)); save(b, "sprites-people", 8) }
    @Test fun descent() { val d = DescentScene(); for (t in listOf(0.6f, 1.6f, 2.8f, 3.4f)) save(d.render(t, t), "l2-descent-${(t * 10).toInt()}", 3) }
    /** The level 1 stranger steps out of the dark, looks at your ID and hands you the envelope; the sewer worker does the same. */
    @Test fun contacts() {
        val spec = StealthLevels.all[0]; val w = StealthWorld(spec); val sc = PixelScene(spec, w)
        var t = 0f; fun step(s: Float) { val end = t + s; while (t < end) { w.update(0.05f, 0f, 0f, 0f); t += 0.05f } }
        w.py = spec.scene!!.triggerY - 0.5f
        var g = 0; while (w.sceneStage < 7 && g++ < 2000) { step(0.05f); if (w.sceneStage == 6 && w.px > 6f) { w.px = 2.5f; w.py = 10.0f }; if (w.sceneStage == 6 && g % 20 == 0) save(sc.render(t, emptySet(), t), "l1-stranger-out-${g / 20}", 2) }
        save(sc.render(t, emptySet(), t), "l1-stranger-0-waits", 2)
        w.px = w.contactX - 2.5f; w.py = w.contactY
        g = 0; while (w.sayText == null && g++ < 200) step(0.05f)
        for ((i, s) in listOf(1.0f, 3.0f, 1.6f, 3.4f, 0.9f, 2.0f).withIndex()) { step(s); save(sc.render(t, emptySet(), t), "l1-stranger-${i + 1}", 2) }
        val room = StealthLevels.all[1].room!!; val r = StealthWorld(room); val rs = PixelScene(room, r)
        t = 0f; r.px = r.contactX; r.py = r.contactY + 1.5f
        fun rstep(s: Float) { val end = t + s; while (t < end) { r.update(0.05f, 0f, 0f, 0f); t += 0.05f } }
        g = 0; while (r.sayText == null && g++ < 400) rstep(0.05f)
        for ((i, s) in listOf(1.0f, 3.0f, 1.6f, 3.4f, 0.9f, 3.0f).withIndex()) { rstep(s); save(rs.render(t, emptySet(), t), "l2-worker-${i + 1}", 2) }
        org.junit.Assert.assertTrue("the worker handed over the envelope", r.hasEnvelope)
    }
    @Test fun level1Streets() {
        val spec = StealthLevels.all[0]; val sc = PixelScene(spec, StealthWorld(spec))
        sc.render(0f, emptySet(), 0f)
        save(sc.debugBase(), "l1-streets-unlit", 2)
    }
}
