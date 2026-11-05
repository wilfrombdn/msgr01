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
    @Test fun level1Streets() {
        val spec = StealthLevels.all[0]; val sc = PixelScene(spec, StealthWorld(spec))
        sc.render(0f, emptySet(), 0f)
        save(sc.debugBase(), "l1-streets-unlit", 2)
    }
}
