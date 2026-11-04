package com.wilfrombdn.telegramnt

import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView

// The scroll bar. Android's own scrollbars are off for every list in the theme (res/values/styles.xml): Android flashes
// them each time a screen appears, so they sat on the right edge during every screen and tab slide.
//
// A list gets its bar only once you drag it up or down with your finger. From then on it is Android's own scrollbar
// (smooth, drawn with the list, no extra work per frame) in Tellem's look: a slim grey bar that shows while the list moves
// and fades shortly after. With Settings → Animations off it disappears at once instead of fading.
// (10.3 drew its own bar from a whole-window scroll listener; that redrew every frame and also lit up when a list moved by
// itself, such as a tab reopening where you left it. That made scrolling jumpy and still showed during tab switches.)

private var downX = 0f
private var downY = 0f
private var decided = false

/** Called from MainActivity.dispatchTouchEvent for every touch, before the screen handles it. Cheap: work happens once per drag. */
internal fun MainActivity.scrollbarTouch(ev: MotionEvent) {
    when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; decided = false }
        MotionEvent.ACTION_MOVE -> if (!decided) {
            val dx = Math.abs(ev.rawX - downX); val dy = Math.abs(ev.rawY - downY)
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            if (dx > slop && dx >= dy) decided = true                    // a sideways swipe (tabs, reply): no bar
            else if (dy > slop) { decided = true; listAt(window.decorView, downX, downY)?.let { giveBar(it) } }
        }
    }
}

/** The innermost vertical list under the point where the finger went down. */
private fun listAt(v: View, x: Float, y: Float): ScrollView? {
    if (v.visibility != View.VISIBLE) return null
    val loc = IntArray(2); v.getLocationOnScreen(loc)
    if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return null
    if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) listAt(v.getChildAt(i), x, y)?.let { return it }
    return v as? ScrollView
}

/** Switches Android's scrollbar on for [sv] in Tellem's style (once per list; a rebuilt screen starts without one again). */
private fun MainActivity.giveBar(sv: ScrollView) {
    if (sv.getTag(R.id.scroll_indicator) == true) return
    sv.setTag(R.id.scroll_indicator, true)
    if (android.os.Build.VERSION.SDK_INT < 29) return   // the bar's look can only be set from Android 10 on; older phones go without
    sv.isScrollbarFadingEnabled = true
    sv.scrollBarDefaultDelayBeforeFade = 700
    sv.scrollBarFadeDuration = if (animationsOn()) 250 else 0
    sv.scrollBarSize = dp(6)
    sv.verticalScrollbarThumbDrawable = android.graphics.drawable.InsetDrawable(
        GradientDrawable().apply { setColor((dim and 0x00FFFFFF) or (0xA0 shl 24)); cornerRadius = dp(2).toFloat() }, 0, 0, dp(2), 0)
    sv.verticalScrollbarTrackDrawable = null
    sv.isVerticalScrollBarEnabled = true
}
