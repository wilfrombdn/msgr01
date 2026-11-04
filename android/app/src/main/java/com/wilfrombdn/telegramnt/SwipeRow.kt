package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A chat-list row you can swipe left a little to reveal a trash button, and (when [onPin] is given) swipe
 * right to reveal a pin button. Tapping the row opens the chat; tapping the trash calls [onDelete]
 * (which shows the permanent-delete confirmation); tapping the pin calls [onPin].
 */
class SwipeRow(
    context: Context,
    content: View,
    trashIcon: Int,
    private val onOpen: () -> Unit,
    private val onDelete: () -> Unit,
    private val onPin: (() -> Unit)? = null,
    pinLabel: String = "Pin",
    pinEmoji: String = "📌",
    private val directRight: Boolean = false,   // swiping right far enough runs onPin at once, no second tap
    private val holdToSwipe: Boolean = false,   // the row only slides after a half-second hold; a quick swipe is left to the screen (Phone tabs)
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val reveal = (84 * density)          // how far the row slides to show a button
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val front = content
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var side = 0                          // -1 trash showing, 1 pin showing, 0 closed
    private var armed = false                     // held long enough (holdToSwipe): the row now follows the finger
    private val armRun = Runnable {
        if (!isAttachedToWindow) return@Runnable
        armed = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)   // the screen must not take this as a tab swipe
    }

    // red delete button at the right edge, hidden until a swipe begins
    private val deleteBtn = LinearLayout(context).apply {
        setBackgroundColor(Color.parseColor("#C0392B"))
        gravity = Gravity.CENTER
        visibility = View.INVISIBLE
        addView(ImageView(context).apply {
            setImageResource(trashIcon)
            setColorFilter(Color.WHITE)
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { onDelete() }
        })
    }

    // blue pin button at the left edge
    private val pinBtn = LinearLayout(context).apply {
        setBackgroundColor(Color.parseColor("#2B7DE0"))
        gravity = Gravity.CENTER
        visibility = View.INVISIBLE
        addView(TextView(context).apply {
            text = "$pinEmoji\n$pinLabel"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setOnClickListener { settle(0); onPin?.invoke() }
        })
    }

    init {
        addView(deleteBtn, LayoutParams(reveal.toInt(), LayoutParams.MATCH_PARENT, Gravity.END))
        if (onPin != null) addView(pinBtn, LayoutParams(reveal.toInt(), LayoutParams.MATCH_PARENT, Gravity.START))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        front.setOnClickListener { if (side != 0) settle(0) else onOpen() }
        if (holdToSwipe) front.isLongClickable = false   // the hold belongs to swiping; the row's menu opens when you let go without moving
    }

    private fun beginDrag(dx: Float) {
        // show only the button that belongs to the direction being dragged
        if (dx < 0 || side == -1) { deleteBtn.visibility = View.VISIBLE; pinBtn.visibility = View.INVISIBLE }
        else { pinBtn.visibility = View.VISIBLE; deleteBtn.visibility = View.INVISIBLE }
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun minX() = -reveal
    private fun maxX() = if (onPin != null) reveal else 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false; armed = false
                if (holdToSwipe) postDelayed(armRun, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (holdToSwipe && !armed && side == 0) {
                    // moved before the hold finished: not ours (the screen reads it as a swipe between tabs, or the list scrolls)
                    if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(armRun)
                    return false
                }
                // only take over for a clear horizontal drag, so the list still scrolls vertically
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && (dx < 0 || side != 0 || onPin != null)) {
                    dragging = true
                    beginDrag(dx)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(armRun)
                if (holdToSwipe && armed && !dragging) { armed = false; front.performLongClick(); return true }   // held, then let go: the row's menu
                armed = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(armRun); armed = false }
        }
        return false
    }

    override fun onDetachedFromWindow() { removeCallbacks(armRun); super.onDetachedFromWindow() }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; return true }
            MotionEvent.ACTION_MOVE -> {
                val base = side * (if (side == -1) reveal else reveal)
                front.translationX = (base + (ev.x - downX)).coerceIn(minX(), maxX())
                beginDrag(front.translationX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val x = front.translationX; armed = false
                if (directRight && x > reveal * 0.75f) { settle(0); onPin?.invoke(); return dragging }
                settle(if (x < -reveal / 2) -1 else if (x > reveal / 2) 1 else 0)
                return dragging
            }
        }
        return dragging
    }

    private fun settle(to: Int) {
        side = to
        front.animate().translationX(to * reveal).setDuration(140)
            .withEndAction { if (to == 0) { deleteBtn.visibility = View.INVISIBLE; pinBtn.visibility = View.INVISIBLE } }.start()
    }
}
