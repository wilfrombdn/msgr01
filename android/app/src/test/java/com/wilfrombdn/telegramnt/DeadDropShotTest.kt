package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

// Draws each Dead Letter level to app/build/shots/deadletter-*.png so the look can be checked without a phone.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class DeadDropShotTest {
    private fun view(): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 0, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        return v
    }

    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 1900, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun draw() {
        val v = view()
        for (i in StealthLevels.all.indices) { v.debugLevel(i); v.debugAdvance(0.6f); shot(v, "deadletter-${i + 1}") }
        v.debugLevel(0); v.debugIntro(); shot(v, "deadletter-intro")
        // walk toward the first clue to see the caption, and the win and ending screens
        v.debugLevel(0); v.debugWorld().px = 1.5f; v.debugWorld().py = 11.6f; v.debugAdvance(0.3f); shot(v, "deadletter-clue")
        v.debugLevel(0); v.debugWorld().px = 4.1f; v.debugWorld().py = 1.5f; v.debugAdvance(3.0f); shot(v, "deadletter-win")
        v.debugLevel(5); v.debugWorld().px = 7.5f; v.debugWorld().py = 1.5f; v.debugAdvance(5.0f); shot(v, "deadletter-ending-a")
        v.debugAdvance(4.0f); shot(v, "deadletter-ending-b")
        v.debugAdvance(5f); shot(v, "deadletter-ending-c")
        // level 1: a radio window being read, then a chase that the vendor ends
        v.debugLevel(0); v.debugWorld().px = 1.5f; v.debugWorld().py = 4.5f; v.debugAdvance(1.6f); shot(v, "deadletter-window")
        v.debugLevel(0); val w = v.debugWorld(); w.px = 2.5f; w.py = 1.5f
        w.guards[2].x = 6.5f; w.guards[2].y = 1.5f; w.guards[2].lockOn(); w.guards[1].x = 7.5f; w.guards[1].y = 1.5f; w.guards[1].lockOn()
        v.debugAdvance(0.35f); shot(v, "deadletter-aim")
        v.debugAdvance(0.8f); shot(v, "deadletter-shot")
        v.debugAdvance(1.2f); shot(v, "deadletter-dead")
    }
}
