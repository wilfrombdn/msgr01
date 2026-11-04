package com.wilfrombdn.telegramnt

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

// Tellem's own scroll indicator. Android's scrollbar is switched off for every ScrollView in the theme (res/values/styles.xml),
// because Android flashes it each time a screen appears, so it sat on the right edge during every screen and tab slide.
// This one is a slim bar in the app's quiet grey that only appears while a list is actually moving: it fades in, then fades out
// shortly after the scrolling stops. With Settings → Animations off it simply appears and disappears with no fade.

private const val IDLE_MS = 900L       // how long it stays after the list stops moving
private const val FADE_IN_MS = 140L
private const val FADE_OUT_MS = 260L
private const val SHOWN_ALPHA = 150    // of 255: visible but never louder than the content

/** One indicator, kept on its ScrollView (as a tag), drawn on the view's overlay so it never changes the layout. */
private class ScrollIndicator(val act: MainActivity, val sv: ScrollView) {
    val bar = GradientDrawable().apply { setColor(act.dim); cornerRadius = act.dp(2).toFloat(); alpha = 0 }
    var lastY = sv.scrollY
    var anim: ValueAnimator? = null
    val hide = Runnable { fadeTo(0, FADE_OUT_MS) }

    init { sv.overlay.add(bar) }

    /** The list moved: put the bar where it belongs and make sure it's showing. */
    fun moved() {
        val content = sv.getChildAt(0)?.height ?: return
        val h = sv.height
        if (content <= h || h <= 0) { fadeTo(0, 0); return }       // nothing to scroll: no bar
        val w = act.dp(3); val edge = act.dp(3)
        val len = maxOf(act.dp(36), h * h / content)
        val top = sv.scrollY + ((h - len).toLong() * sv.scrollY / (content - h)).toInt()   // overlay draws in scrolled coordinates
        bar.setBounds(sv.width - edge - w, top, sv.width - edge, top + len)
        if (bar.alpha < SHOWN_ALPHA && anim?.isRunning != true) fadeTo(SHOWN_ALPHA, FADE_IN_MS)
        sv.invalidate()
        sv.removeCallbacks(hide); sv.postDelayed(hide, IDLE_MS)
    }

    fun fadeTo(target: Int, ms: Long) {
        anim?.cancel(); anim = null
        if (!act.animationsOn() || ms == 0L) { bar.alpha = target; sv.invalidate(); return }
        anim = ValueAnimator.ofInt(bar.alpha, target).setDuration(ms).apply {
            addUpdateListener { bar.alpha = it.animatedValue as Int; sv.invalidate() }
            start()
        }
    }
}

/**
 * Called on every scroll anywhere in the window: finds the vertical lists on screen and shows the bar on the one that moved.
 * It only looks at the screen's frame, not inside the lists, so it stays cheap even in a long chat.
 */
internal fun MainActivity.onAnyScroll() {
    fun walk(v: View) {
        if (v is ScrollView) {
            val ind = (v.getTag(R.id.scroll_indicator) as? ScrollIndicator) ?: ScrollIndicator(this, v).also { v.setTag(R.id.scroll_indicator, it) }
            if (v.scrollY != ind.lastY) { ind.lastY = v.scrollY; ind.moved() }
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(window.decorView)
}
