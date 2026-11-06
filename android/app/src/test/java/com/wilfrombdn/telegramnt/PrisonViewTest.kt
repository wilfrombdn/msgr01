package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** The prison as it really looks on a phone: title bar, the words under it, the joystick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class PrisonViewTest {
    private fun shot(v: DeadDropView, name: String): Bitmap {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** At shower time, standing in the showers, the shower room is in view under the title bar and the words, not hidden behind them. */
    @Test fun theShowersAreInViewAtShowerTime() {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 4, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        v.debugLevel(4)
        val w = v.debugPrison()!!
        v.debugMgAdvance(8f)
        w.clock = 1145f; v.debugMgAdvance(0.2f)
        w.px = 11.5f; w.py = 2.5f                                       // right under the shower heads (the top row of the map is 1)
        repeat(60) { v.debugMgAdvance(0.05f); w.px = 11.5f; w.py = 2.5f; shot(v, "full-l5-showers") }
        val bmp = shot(v, "full-l5-showers")
        // the shower heads (row 1) must sit below the words under the title bar: find the top of the map on screen
        var firstLit = -1
        for (y in 0 until 2400) { val c = bmp.getPixel(30, y); val lum = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255); if (y > 200 && lum > 150 && firstLit < 0) { firstLit = y } }
        assertTrue("the shower room shows below the title area (first lit row at $firstLit)", firstLit > 400)
    }
}
