package com.wilfrombdn.telegramnt

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView

// Slide animations between screens and tabs. They switch off with Settings → Animations, and then everything changes instantly.
//
// Why they are smooth: the screen that leaves is a flat picture of itself (taken just before the change), not a live copy of
// all its rows that would have to be laid out again; the moving views are lifted onto the graphics chip for the slide
// (so moving them costs next to nothing); and the clock never lets one slow frame (building the new screen) jump the motion ahead.

/** Follows only Settings → Animations. (The phone's own animation speed is deliberately ignored: with it at 0 every animation used to be skipped.) */
internal fun MainActivity.animationsOn() = UiPrefs.animations(this)

internal fun MainActivity.contentRoot(): View? = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

/** A flat picture of [v] as it looks right now (null if it can't be taken: then the change simply happens without a slide). */
internal fun snapshotOf(v: View): android.graphics.Bitmap? {
    if (v.width <= 0 || v.height <= 0) return null
    return runCatching { android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) } }.getOrNull()
}

internal fun View.lift() = setLayerType(View.LAYER_TYPE_HARDWARE, null)
internal fun View.drop() = setLayerType(View.LAYER_TYPE_NONE, null)

/** Holds the leaving picture while it slides out, and swallows touches so the stale screen can't be tapped. */
private class Shield(ctx: Context) : FrameLayout(ctx) {
    override fun onInterceptTouchEvent(ev: MotionEvent) = true
}

private const val SLIDE_MS = 300L

/** One soft curve for every slide and spring-back: it leaves gently and settles slowly, so motion never snaps or stops dead. */
internal val SMOOTH: android.animation.TimeInterpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

private fun MainActivity.pictureView(bmp: android.graphics.Bitmap) = android.widget.ImageView(this).apply {
    setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY
}

/**
 * The whole screen slides: [bmp] (a picture of the screen just replaced) leaves one way while the new one arrives from the other.
 * [dir] 1 = the new screen comes from the right, -1 = from the left.
 */
internal fun MainActivity.slideScreens(bmp: android.graphics.Bitmap, dir: Int) {
    val content = findViewById<ViewGroup>(android.R.id.content)
    val new = content.getChildAt(0) ?: return
    val pic = pictureView(bmp)
    val shield = Shield(this).also { it.addView(pic, FrameLayout.LayoutParams(-1, -1)) }
    content.addView(shield, FrameLayout.LayoutParams(-1, -1))
    val w = (content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    new.translationX = dir * w
    new.lift(); pic.lift()
    val tween = Tween(SLIDE_MS, SMOOTH, { f -> new.translationX = dir * w * (1f - f); pic.translationX = -dir * w * f }) { slideEnd?.invoke() }
    slideEnd = { tween.cancel(); new.translationX = 0f; new.drop(); content.removeView(shield); pic.setImageDrawable(null); slideEnd = null }
    tween.start()
}

/**
 * Only the list slides (Contacts / Calls / Texts), starting from wherever the finger let go ([startX]) so the motion carries on.
 * [bmp] is a picture of the old list; it slides out while the new list arrives.
 */
internal fun MainActivity.slideLists(bmp: android.graphics.Bitmap, startX: Float, dir: Int) {
    val frame = chatsFrame ?: return
    val new = chatsScroll ?: return
    val pic = pictureView(bmp)
    val shield = Shield(this).also { it.addView(pic, FrameLayout.LayoutParams(-1, -1)) }
    frame.addView(shield, 1, FrameLayout.LayoutParams(-1, -1))   // above the new list, below the round button
    val w = (frame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
    pic.translationX = startX
    new.translationX = startX + dir * w
    new.lift(); pic.lift()
    val tween = Tween(SLIDE_MS, SMOOTH, { f -> pic.translationX = startX + (-dir * w - startX) * f; new.translationX = (startX + dir * w) * (1f - f) }) { slideEnd?.invoke() }
    slideEnd = { tween.cancel(); new.translationX = 0f; new.drop(); frame.removeView(shield); pic.setImageDrawable(null); slideEnd = null }
    tween.start()
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
    finishSlide()
    val startX = old?.translationX ?: 0f
    val bmp = if (animationsOn()) old?.let { snapshotOf(it) } else null
    old?.drop()
    phoneSub = to; chatsScrollY = 0; flipAnim = true; showChats()
    if (bmp != null) slideLists(bmp, startX, dir)
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
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; swiping = false; fromEdge = e.x < edge; (context as? MainActivity)?.finishSlide() }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = e.x - x0; val dy = e.y - y0
                val horizontal = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f
                if (horizontal && ((fromEdge && dx > 0) || tabs)) { swiping = true; parent?.requestDisallowInterceptTouchEvent(true); if (follow() && !fromEdge) list()?.lift(); return true }
            }
        }
        return false
    }

    private fun list(): View? = if (childCount > 0) getChildAt(0) else null
    private fun back(v: View?) { v ?: return; val from = v.translationX; Tween(260, SMOOTH, { f -> v.translationX = from * (1f - f) }) { v.drop() }.start() }

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
 * setting (at 0 they finish instantly), so these slides and the balloon use this instead. Each frame moves time on by at most
 * 20 ms, so one slow frame (building the next screen) shows as a short pause, never as a jump.
 */
internal class Tween(
    private val ms: Long,
    private val ease: android.animation.TimeInterpolator,
    private val onUpdate: (Float) -> Unit,
    private val onEnd: () -> Unit = {},
) : android.view.Choreographer.FrameCallback {
    private var last = 0L
    private var elapsed = 0f
    private var live = true
    fun start(): Tween { android.view.Choreographer.getInstance().postFrameCallback(this); return this }
    fun cancel() { live = false }
    override fun doFrame(frameTimeNanos: Long) {
        if (!live) return
        if (last != 0L) elapsed += ((frameTimeNanos - last) / 1_000_000f).coerceIn(0f, 20f)
        last = frameTimeNanos
        val f = (elapsed / ms).coerceIn(0f, 1f)
        onUpdate(ease.getInterpolation(f))
        if (f < 1f) android.view.Choreographer.getInstance().postFrameCallback(this) else onEnd()
    }
}

/**
 * Finish a slide that is still running, right now. Called before anything that starts another change, so tapping quickly
 * never stacks slides on top of each other or snapshots a screen that is half way across.
 */
internal fun MainActivity.finishSlide() { slideEnd?.invoke() }

/** The name at the top of the Phone window: whichever of Contacts, Calls, Texts or Folders you are on. */
internal fun MainActivity.phoneTitle() = when (phoneSub) { "contacts" -> "Contacts"; "calls" -> "Calls"; "folders" -> "Folders"; else -> "Texts" }
