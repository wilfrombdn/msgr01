package com.wilfrombdn.telegramnt

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h760dp-xxhdpi")
class PacRestartTest {
    @Test fun tappingAnywhereAfterBeingCaughtRestartsPacMan() {
        val v = DeadDropView(RuntimeEnvironment.getApplication(), 1, false, { }, { }, { })
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 1900)
        v.debugStage(1)
        val before = v.debugPac()!!
        before.phase = PacWorld.Phase.CAUGHT
        repeat(40) { v.debugStageTick(0.05f) }          // the red screen has been showing for 2 seconds
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 900f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 540f, 900f, 0))
        val after = v.debugPac()!!
        assertNotSame("a fresh maze", before, after)
        assertTrue(after.phase != PacWorld.Phase.CAUGHT)
    }
}
