package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
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

// The skip arrow goes scene by scene through the whole game; the level banner sits in the middle of the screen.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class SkipTest {
    private fun view(level: Int = 0): DeadDropView {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), level, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        return v
    }

    @Test fun skipGoesSceneByScene() {
        val v = view(0)
        fun where() = "L${v.debugLevelIdx()} stage${v.debugStage()} mg${v.debugMgIdx()} ${v.debugFinale()?.stage ?: ""}".trim()
        val seen = ArrayList<String>()
        seen += where()
        repeat(18) { v.skip(); seen += where() }
        assertEquals(listOf(
            "L0 stage0 mg-1",
            "L1 stage1 mg-1",           // level 1 > straight to the sewer maze
            "L1 stage2 mg-1",           // > the maintenance room
            "L2 stage0 mg0",            // level 3: belts
            "L2 stage0 mg1",            // jetpack
            "L2 stage0 mg2",            // cop cutscene
            "L3 stage0 mg0",            // level 4: arrival
            "L3 stage0 mg1",            // shoot-out
            "L3 stage0 mg1",            // > straight to the mayor's office
            "L4 stage0 mg0",            // level 5: the prison
            "L5 stage0 mg0 BEACH",      // level 6
            "L5 stage0 mg0 INTRO",
            "L5 stage0 mg0 CLIMB",
            "L5 stage0 mg0 CONTROL",    // from the climb straight to the uplink room
            "L5 stage0 mg0 SIMON",
            "L5 stage0 mg0 BROADCAST",
            "L5 stage0 mg0 END",
            "L5 stage0 mg0 END",        // nothing after the end
            "L5 stage0 mg0 END",
        ), seen)
    }

    @Test fun theBannerIsCentredAndYouCantMoveUnderIt() {
        val v = view(0)
        assertTrue(v.debugBannerUp())
        v.debugAdvance(1.5f)
        val bmp = Bitmap.createBitmap(1080, 1900, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        File("build/shots").mkdirs(); FileOutputStream(File("build/shots/deadletter-banner.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        v.debugAdvance(3.0f)
        assertFalse(v.debugBannerUp())
    }

    @Test fun afterBeatingTheGameYouChooseALetter() {
        var saved = -1
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 5, false, { saved = it }, { }, { }, beaten = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        assertTrue(v.isChoosing())
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
        FileOutputStream(File("build/shots/deadletter-chooser.png")).use { Bitmap.createScaledBitmap(bmp, 540, 1200, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        // tap the third row (letter III)
        val y = 2400f * 0.5f
        fun tap(x: Float, yy: Float) { v.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, yy, 0)); v.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, x, yy, 0)) }
        var picked = -1
        for (yy in 300 until 2300 step 20) { tap(540f, yy.toFloat()); if (!v.isChoosing()) { picked = v.debugLevelIdx(); break } }
        assertFalse(v.isChoosing()); assertEquals(picked, saved); assertTrue(picked in 0..5)
    }
}
