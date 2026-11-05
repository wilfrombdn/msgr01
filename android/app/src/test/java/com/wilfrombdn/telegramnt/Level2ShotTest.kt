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

// Draws the three parts of level 2 to app/build/shots/l2-*.png.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class Level2ShotTest {
    private fun view(): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 1, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        return v
    }

    private fun shot(v: DeadDropView, name: String) {
        val bmp = Bitmap.createBitmap(1080, 1900, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun surface() {
        val v = view(); val w = { v.debugWorld() }
        v.debugLevel(1); v.debugAdvance(0.5f); shot(v, "l2-start")
        // follow the contact along his route to the manhole
        var guard = 0
        while (w().leadStage < 1 && guard++ < 400) { w().px = w().contactX; w().py = w().contactY + 1.2f; v.debugAdvance(0.25f) }
        v.debugAdvance(0.1f); shot(v, "l2-at-manhole")
        w().px = w().contactX + 0.2f; w().py = w().contactY + 1.5f
        v.debugAdvance(1.0f); shot(v, "l2-lid-opening")
        v.debugAdvance(1.0f); shot(v, "l2-lid-open")
        v.debugAdvance(2.6f); shot(v, "l2-farewell")
        v.debugAdvance(2.2f)
        assertTrue("the contact reached the manhole and spoke", w().leadStage >= 2)
        w().px = 8.5f; w().py = 9.5f; v.debugAdvance(0.2f)
        assertTrue(w().enteredManhole)
    }

    @Test fun maze() {
        val v = view()
        v.debugLevel(1); v.debugStage(1)
        v.debugPacAdvance(1.0f); shot(v, "l2-pac-intro-a")
        v.debugPacAdvance(1.6f); shot(v, "l2-pac-intro-b")
        v.debugPacAdvance(2.2f); shot(v, "l2-pac-intro-c")
        v.debugPacAdvance(2.4f); shot(v, "l2-pac-lid-closed")
        v.debugPacAdvance(2.4f); shot(v, "l2-pac-ready")
        v.debugPacWant(PacWorld.LEFT); v.debugPacAdvance(4.5f, PacWorld.LEFT); shot(v, "l2-pac-play")
        val pw = v.debugPac()!!
        for (y in 0 until pw.h) for (x in 0 until pw.w) pw.frag[y][x] = false
        pw.left = 0; pw.doorOpen = true
        v.debugPacAdvance(0.3f); shot(v, "l2-pac-door-open")
    }

    @Test fun room() {
        val v = view(); val w = { v.debugWorld() }
        v.debugLevel(1); v.debugStage(2)
        v.debugAdvance(0.5f); shot(v, "l2-room-start")
        w().px = 6.5f; w().py = 8.0f; v.debugAdvance(1.0f); shot(v, "l2-room-turns")
        v.debugAdvance(2.2f); shot(v, "l2-room-smokes")
        w().px = 6.5f; w().py = 4.5f; v.debugAdvance(0.6f); shot(v, "l2-room-letter")
        v.debugAdvance(3.2f); shot(v, "l2-room-points")
        w().px = 12.5f; w().py = 3.6f; v.debugAdvance(0.7f); shot(v, "l2-room-climb")
        v.debugAdvance(1.5f); shot(v, "l2-room-won")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class Level2FlowTest {
    @Test fun walkThenMazeThenRoom() {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 1, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        // part 1: follow the contact to the manhole and go down
        val w = v.debugWorld()
        var guard = 0
        while (!w.enteredManhole && guard++ < 600) {
            if (w.leadStage < 3) { w.px = w.contactX; w.py = w.contactY + 1.2f } else { w.px = 8.5f; w.py = 9.5f }
            v.debugAdvance(0.25f)
        }
        assertTrue("walked down the manhole", w.enteredManhole)
        var t = 0f; while (v.debugStage() == 0 && t < 5f) { v.debugStageTick(0.1f); t += 0.1f }
        assertTrue("moved on to the maze", v.debugStage() == 1)
        // part 2: the maze; win it
        while (!v.debugFadeDone() && t < 12f) { v.debugStageTick(0.1f); t += 0.1f }
        val pw = v.debugPac()!!
        pw.phase = PacWorld.Phase.WON; pw.phaseT = 1f
        t = 0f; while (v.debugStage() == 1 && t < 5f) { v.debugStageTick(0.1f); t += 0.1f }
        assertTrue("moved on to the room", v.debugStage() == 2)
        // part 3: the room; the worker takes the letter and you climb
        val r = v.debugWorld()
        assertTrue("a new world for the room", r !== w)
        r.px = 6.5f; r.py = 4.5f
        repeat(120) { v.debugAdvance(0.1f) }
        assertTrue("the worker took the letter", r.letterGiven)
        r.px = 12.5f; r.py = 3.6f; v.debugAdvance(0.3f)
        assertTrue("climbing the ladder finishes the level", r.state == StealthWorld.State.WON)
    }
}
