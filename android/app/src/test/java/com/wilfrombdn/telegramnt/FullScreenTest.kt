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

// Every scene on a tall phone screen (1080 x 2400), to check the game fills it edge to edge.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h913dp-xxhdpi")
class FullScreenTest {
    private val W = 1080; private val H = 2400
    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        val small = Bitmap.createScaledBitmap(bmp, W / 2, H / 2, true)
        File("build/shots").mkdirs(); FileOutputStream(File("build/shots/full-$name.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun everyScene() {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 0, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)); v.layout(0, 0, W, H)
        val names = listOf("l1", "l2-maze", "l2-room", "l3-belt", "l3-fly", "l3-cine", "l4-cine", "l4-shoot", "l4-mayor", "l5-prison", "l6-beach", "l6-intro", "l6-climb", "l6-control", "l6-simon", "l6-broadcast", "l6-end")
        for ((i, n) in names.withIndex()) {
            if (i > 0) v.skip()
            v.debugMgAdvance(if (n == "l5-prison") 5f else 2.5f)
            shot(v, n)
        }
    }
}
