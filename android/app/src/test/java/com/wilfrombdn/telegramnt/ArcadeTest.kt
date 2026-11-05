package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

// After the game is beaten: the arcade menu, and every mini-game running as an endless game until it's over.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h913dp-xxhdpi")
class ArcadeTest {
    private fun view(): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 5, false, { }, { }, { }, beaten = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        return v
    }
    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        File("build/shots").mkdirs(); FileOutputStream(File("build/shots/$name.png")).use { Bitmap.createScaledBitmap(bmp, 540, 1200, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theMenuAndEveryGame() {
        val v = view()
        assertTrue(v.isChoosing()); shot(v, "arcade-menu")
        for (id in listOf("belt", "fly", "tower", "uplink", "hall", "maze")) {
            v.debugArcade(id)
            assertFalse(v.isChoosing())
            v.debugMgAdvance(3f); if (id == "maze") repeat(60) { v.debugStageTick(0.05f) }
            shot(v, "arcade-$id")
            assertFalse("$id should still be running", v.debugArcadeOver())
        }
    }

    @Test fun standingStillOnTheBeltsEndsTheGame() {
        val v = view()
        v.debugArcade("belt")
        var t = 0f; while (!v.debugArcadeOver() && t < 90f) { v.debugMgAdvance(0.5f); t += 0.5f }
        assertTrue("the belts got you in the end", v.debugArcadeOver())
        shot(v, "arcade-gameover")
    }
}
