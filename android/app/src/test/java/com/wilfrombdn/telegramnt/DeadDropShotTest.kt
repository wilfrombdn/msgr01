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

// Draws Dead Letter scenes to app/build/shots/deadletter-*.png so the look can be checked without a phone.
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

    @Test fun walk() {
        val v = view(); val w = { v.debugWorld() }
        v.debugLevel(0); v.debugAdvance(0.5f); shot(v, "deadletter-1")
        w().px = 6.5f; w().py = 44.5f; v.debugAdvance(2.2f); shot(v, "deadletter-window")
        w().px = 2.5f; w().py = 40.0f; v.debugAdvance(0.5f); shot(v, "deadletter-passage")
        w().px = 3.0f; w().py = 24.5f; v.debugAdvance(0.5f); shot(v, "deadletter-fog-before")
        w().px = 2.5f; w().py = 21.5f; v.debugAdvance(2.0f); shot(v, "deadletter-fog-clearing")
        v.debugAdvance(2.2f); shot(v, "deadletter-cop-stands")
        v.debugAdvance(2.6f); shot(v, "deadletter-cop-dots")
        v.debugAdvance(2.6f); shot(v, "deadletter-cop-headdown")
        v.debugAdvance(3.0f); shot(v, "deadletter-cop-walks")
        v.debugAdvance(3.2f); shot(v, "deadletter-cop-exits")
        v.debugAdvance(3.0f); shot(v, "deadletter-cop-gone")
        v.debugAdvance(3.0f); shot(v, "deadletter-contact-walks")
        w().px = 3.5f; w().py = 14.0f; v.debugAdvance(5.0f); shot(v, "deadletter-contact-waits")
        w().px = 3.5f; w().py = 11.5f; v.debugAdvance(1.0f); shot(v, "deadletter-contact-says")
        v.debugAdvance(1.6f); shot(v, "deadletter-contact-cross1")
        v.debugAdvance(1.0f); shot(v, "deadletter-contact-cross2")
    }

    @Test fun otherLevels() {
        val v = view()
        for (i in 1 until StealthLevels.all.size) { v.debugLevel(i); v.debugAdvance(0.6f); shot(v, "deadletter-${i + 1}") }
        v.debugLevel(5); v.debugWorld().px = 7.5f; v.debugWorld().py = 1.5f; v.debugAdvance(5.0f); shot(v, "deadletter-ending-a")
        v.debugAdvance(4.0f); shot(v, "deadletter-ending-b")
        v.debugAdvance(5f); shot(v, "deadletter-ending-c")
    }
}
