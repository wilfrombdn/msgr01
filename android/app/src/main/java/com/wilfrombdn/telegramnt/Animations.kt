package com.wilfrombdn.telegramnt

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView

// Slide animations between screens and tabs. All of them switch off with Settings → Animations (and with the phone's own
// "remove animations" setting), and then everything changes instantly.

/** Follows only Settings → Animations. (The phone's own animation speed is deliberately ignored: with it at 0 every animation used to be skipped.) */
internal fun MainActivity.animationsOn() = UiPrefs.animations(this)

internal fun MainActivity.contentRoot(): View? = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

/** Holds a leaving view while it slides out, and swallows touches so the stale screen can't be tapped. */
private class Shield(ctx: Context) : FrameLayout(ctx) {
    override fun onInterceptTouchEvent(ev: MotionEvent) = true
}

private const val SLIDE_MS = 240L

/**
 * The whole screen slides: [old] (the screen just replaced) leaves one way while the new one arrives from the other.
 * [dir] 1 = the new screen comes from the right, -1 = from the left.
 */
internal fun MainActivity.slideScreens(old: View, dir: Int) {
    val content = findViewById<ViewGroup>(android.R.id.content)
    val new = content.getChildAt(0) ?: return
    if (new === old) return
    (old.parent as? ViewGroup)?.removeView(old)
    val shield = Shield(this).also { it.addView(old, FrameLayout.LayoutParams(-1, -1)) }
    content.addView(shield, FrameLayout.LayoutParams(-1, -1))
    val w = (content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    val ease = DecelerateInterpolator(1.6f)
    new.translationX = dir * w
    Tween(SLIDE_MS, ease, { f -> new.translationX = dir * w * (1f - f); old.translationX = -dir * w * f }) { new.translationX = 0f; content.removeView(shield) }.start()
}

/**
 * Only the list slides (Contacts / Calls / Texts), starting from wherever the finger let go ([startX]) so the motion carries on.
 * The old list stays on screen, sliding out, until the new one has arrived.
 */
internal fun MainActivity.slideLists(old: View, startX: Float, dir: Int) {
    val frame = chatsFrame ?: return
    val new = chatsScroll ?: return
    if (new === old) return
    (old as? ScrollView)?.setOnScrollChangeListener(null)   // the left-behind list must not move the real scroll position
    (old.parent as? ViewGroup)?.removeView(old)
    val shield = Shield(this).also { it.addView(old, FrameLayout.LayoutParams(-1, -1)) }
    frame.addView(shield, 1, FrameLayout.LayoutParams(-1, -1))   // above the new list, below the round button
    val w = (frame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    val ease = DecelerateInterpolator(1.6f)
    old.translationX = startX
    new.translationX = startX + dir * w
    Tween(SLIDE_MS, ease, { f -> old.translationX = startX + (-dir * w - startX) * f; new.translationX = (startX + dir * w) * (1f - f) }) { new.translationX = 0f; frame.removeView(shield) }.start()
}

/** Contacts, Calls, Texts (and Folders when they have their own tab), left to right. */
internal fun MainActivity.phoneTabOrder(): List<String> {
    val mixed = Texts.canRead(this) && Texts.mixed(this)
    return listOf("contacts", "calls") + (if (!mixed) listOf("texts") else emptyList()) + (if (folderPlace() == "tab") listOf("folders") else emptyList())
}

internal fun MainActivity.canSwipePhoneTab(dir: Int): Boolean {
    val o = phoneTabOrder(); val i = o.indexOf(phoneSub)
    return i >= 0 && o.getOrNull(i + dir) != null
}

/** Show a Phone tab. [dir] 1 = the tab is to the right of this one. [old] is the list to slide away (it may already be dragged part way). */
internal fun MainActivity.gotoPhoneTab(to: String, dir: Int, old: View? = chatsScroll) {
    val oldList = old?.takeIf { animationsOn() }
    val startX = oldList?.translationX ?: 0f
    oldList?.let { (it as? ScrollView)?.setOnScrollChangeListener(null); (it.parent as? ViewGroup)?.removeView(it) }
    phoneSub = to; chatsScrollY = 0; showChats()
    if (oldList != null) slideLists(oldList, startX, dir)
}

/** Move one tab along (1 = the next, to the right; -1 = the previous). Does nothing at either end. */
internal fun MainActivity.swipePhoneTab(dir: Int, old: View? = null) {
    val o = phoneTabOrder(); val i = o.indexOf(phoneSub).takeIf { it >= 0 } ?: return
    gotoPhoneTab(o.getOrNull(i + dir) ?: return, dir, old ?: chatsScroll)
}

/**
 * The list area of the chat screens. A swipe right that starts at the very left edge pulls out the sidebar (any screen).
 * On the Phone tabs ([tabs]) a swipe left or right elsewhere moves between Contacts, Calls and Texts: the list follows the finger
 * (when [follow] is true), springs back if you let go early, and slides on to the next tab if you went far enough.
 */
internal class TabSwipeFrame(
    ctx: Context,
    private val tabs: Boolean,
    private val onEdge: () -> Unit,
    private val canGo: (Int) -> Boolean,
    private val follow: () -> Boolean,
    private val onSwipe: (Int, View?) -> Unit,
) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private val density = ctx.resources.displayMetrics.density
    private val far = 64 * density
    private val edge = 36 * density
    private var x0 = 0f; private var y0 = 0f; private var swiping = false; private var fromEdge = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; swiping = false; fromEdge = e.x < edge }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = e.x - x0; val dy = e.y - y0
                val horizontal = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f
                if (horizontal && ((fromEdge && dx > 0) || tabs)) { swiping = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
        }
        return false
    }

    private fun list(): View? = if (childCount > 0) getChildAt(0) else null
    private fun back(v: View?) { v ?: return; val from = v.translationX; Tween(160, DecelerateInterpolator(), { f -> v.translationX = from * (1f - f) }).start() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (swiping && !(fromEdge && e.x - x0 > 0) && follow()) {
                val dx = e.x - x0
                list()?.translationX = if (canGo(if (dx < 0) 1 else -1)) dx else dx * 0.25f   // at either end it only gives a little
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false; val dx = e.x - x0
                if (fromEdge && dx > 0) { if (dx > far / 2) onEdge() }
                else {
                    val dir = if (dx < 0) 1 else -1
                    val moved = list()?.takeIf { follow() }
                    if (Math.abs(dx) > far && canGo(dir)) onSwipe(dir, moved) else back(moved)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (swiping) { swiping = false; back(list()?.takeIf { follow() }) }
        }
        return swiping
    }
}

/**
 * Our own animation clock, in step with the screen's frames. The system's animators follow the phone's "animation speed"
 * developer/accessibility setting (at 0 they finish instantly), so these slides and the balloon use this instead.
 */
internal class Tween(
    private val ms: Long,
    private val ease: android.animation.TimeInterpolator,
    private val onUpdate: (Float) -> Unit,
    private val onEnd: () -> Unit = {},
) : android.view.Choreographer.FrameCallback {
    private var t0 = 0L
    private var live = true
    fun start(): Tween { android.view.Choreographer.getInstance().postFrameCallback(this); return this }
    fun cancel() { live = false }
    override fun doFrame(frameTimeNanos: Long) {
        if (!live) return
        if (t0 == 0L) t0 = frameTimeNanos
        val f = ((frameTimeNanos - t0) / 1_000_000f / ms).coerceIn(0f, 1f)
        onUpdate(ease.getInterpolation(f))
        if (f < 1f) android.view.Choreographer.getInstance().postFrameCallback(this) else onEnd()
    }
}
