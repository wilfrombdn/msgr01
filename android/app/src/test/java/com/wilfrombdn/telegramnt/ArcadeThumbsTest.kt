package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Makes the action shots on the arcade menu (res/drawable-nodpi/dl_thumb_<game>.png): each game is played for a few seconds and a
 * slice of the screen is saved. Only runs when asked: THUMBS=1 ./gradlew testDebugUnitTest --tests '*ArcadeThumbsTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h913dp-xxhdpi")
class ArcadeThumbsTest {
    @Test fun makeThumbs() {
        assumeTrue(System.getenv("THUMBS") == "1")
        val out = File("src/main/res/drawable-nodpi").apply { mkdirs() }
        // which game, how long to play it, and the slice of the 1080 x 2400 screen to keep (top, height)
        val plan = listOf(Triple("maze", 9f, 700 to 1060), Triple("belt", 12f, 1250 to 810), Triple("fly", 22f, 760 to 810),
            Triple("hall", 0f, 200 to 900), Triple("tower", 7f, 640 to 1000), Triple("uplink", 4f, 560 to 1240))
        for ((id, sec, crop) in plan) {
            val v = DeadDropView(RuntimeEnvironment.getApplication(), 5, false, { }, { }, { }, beaten = true)
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
            v.thumbMode = true; v.debugArcade(id)
            val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
            var t = 0f
            fun step(s: Float) { val end = t + s; while (t < end) { if (id == "maze") v.debugStageTick(0.05f); v.debugMgAdvance(0.05f); t += 0.05f; v.draw(c) } }
            when (id) {
                "hall" -> { step(1f); val sw = v.debugShoot()!!; sw.debugBoss(); step(2.4f) }
                "uplink" -> { val fw = v.debugFinale()!!; step(sec); var k = 0; while (fw.showing && k < 400) { step(0.05f); k++ }; fw.simonTap(fw.seq[0]); step(0.1f) }
                "tower" -> { val fw = v.debugFinale()!!; fw.debugScreen(2); step(sec) }
                "fly" -> { var k = 0f; while (k < sec) { val f = v.debugFly()!!; v.debugThrust(f.y > 9.5f); v.debugMgAdvance(0.05f); k += 0.05f; v.draw(c) } }
                else -> step(sec)
            }
            repeat(30) { v.draw(c) }
            val (top, hh) = crop
            // the slice, fitted into a 4:3 picture (dark at the sides when it's taller than that)
            val small = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); val sc = Canvas(small); sc.drawColor(0xFF03050A.toInt())
            val dw = (1080f * 240f / hh).toInt().coerceAtMost(320); val dh = if (dw == 320) (hh * 320f / 1080f).toInt() else 240
            val dl = (320 - dw) / 2; val dt = (240 - dh) / 2
            sc.drawBitmap(bmp, Rect(0, top, 1080, top + hh), Rect(dl, dt, dl + dw, dt + dh), android.graphics.Paint().apply { isFilterBitmap = true })
            FileOutputStream(File(out, "dl_thumb_$id.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
