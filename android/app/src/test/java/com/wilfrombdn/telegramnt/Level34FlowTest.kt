package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class Level34FlowTest {
    private fun view(): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 2, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        return v
    }
    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 1900, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun level3Stages() {
        val v = view()
        assertEquals(2, v.debugLevelIdx()); assertEquals(0, v.debugMgIdx())
        v.debugMgAdvance(3f); shot(v, "v3-belt")
        v.debugMg(1); v.debugMgAdvance(2f); shot(v, "v3-fly")
        v.debugMg(2); v.debugMgAdvance(4f); shot(v, "v3-cine-a")
        v.debugMgAdvance(6f); shot(v, "v3-cine-car")
        v.debugMgAdvance(14f)
        assertEquals(3, v.debugLevelIdx()); assertEquals(0, v.debugMgIdx())
        v.debugMgAdvance(8f); shot(v, "v4-arrive")
        v.debugMgAdvance(12f)
        assertEquals(1, v.debugMgIdx())
        v.debugMgAdvance(3f); shot(v, "v4-shoot")
    }
}
