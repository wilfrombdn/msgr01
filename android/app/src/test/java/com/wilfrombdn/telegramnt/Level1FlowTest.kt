package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

// Level 1 through the real game screen: the trenchcoat man, the talk in speech bubbles, the ID and the envelope, then you follow him into the dark.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class Level1FlowTest {
    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 1900, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        FileOutputStream(File(File("build/shots").apply { mkdirs() }, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun step(v: DeadDropView, sec: Float) { var t = sec; while (t > 0f) { v.debugAdvance(0.05f); v.debugStageTick(0.05f); t -= 0.05f } }

    @Test fun theAlley() {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 0, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        v.debugLevel(0)
        val w = v.debugWorld()
        w.py = w.spec.scene!!.triggerY - 0.5f
        var g = 0; while (w.sceneStage < 7 && g++ < 3000) { step(v, 0.05f); if (w.sceneStage == 6 && w.px > 6f) { w.px = 2.5f; w.py = 10.0f } }
        assertEquals(7, w.sceneStage)
        w.px = w.contactX - 2.5f; w.py = w.contactY
        g = 0; while (w.sayText == null && g++ < 200) step(v, 0.05f)
        assertTrue("he talks once you're close", w.sayText != null)
        assertEquals("you stand a natural step away", 1.4f, kotlin.math.hypot(w.px - w.contactX, w.py - w.contactY), 0.1f)
        step(v, 1.6f); shot(v, "l1-talk-1")
        step(v, 2.4f); shot(v, "l1-talk-id")
        step(v, 1.6f); shot(v, "l1-talk-id-checked")
        g = 0; while (!w.givingEnvelope && g++ < 400) step(v, 0.05f)
        step(v, 0.5f); shot(v, "l1-talk-envelope")
        g = 0; while (w.leaveT < 0f && g++ < 400) step(v, 0.05f)
        assertTrue("you have the envelope", w.hasEnvelope)
        step(v, 1.2f); shot(v, "l1-follow")
        g = 0; while (v.debugLevelIdx() == 0 && g++ < 400) step(v, 0.05f)
        assertEquals("on to the underground", 1, v.debugLevelIdx())
    }
}
