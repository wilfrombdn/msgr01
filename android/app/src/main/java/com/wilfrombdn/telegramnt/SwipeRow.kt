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
import kotlin.math.abs

/**
 * A chat-list row you can swipe left a little to reveal a trash button. Tapping the row opens the
 * chat; tapping the trash calls [onDelete] (which shows the permanent-delete confirmation).
 */
class SwipeRow(
    context: Context,
    content: View,
    trashIcon: Int,
    private val onOpen: () -> Unit,
    private val onDelete: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val reveal = (84 * density)          // how far the row slides to show the trash
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val front = content
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var open = false

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

    init {
        addView(deleteBtn, LayoutParams(reveal.toInt(), LayoutParams.MATCH_PARENT, Gravity.END))
        addView(front, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        front.setOnClickListener { if (open) settle(false) else onOpen() }
    }

    private fun beginDrag() {
        deleteBtn.visibility = View.VISIBLE
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // only take over for a clear horizontal drag, so the list still scrolls vertically
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && (dx < 0 || open)) {
                    dragging = true
                    beginDrag()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; return true }
            MotionEvent.ACTION_MOVE -> {
                val base = if (open) -reveal else 0f
                front.translationX = (base + (ev.x - downX)).coerceIn(-reveal, 0f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                settle(front.translationX < -reveal / 2)
                return dragging
            }
        }
        return dragging
    }

    private fun settle(toOpen: Boolean) {
        open = toOpen
        front.animate().translationX(if (toOpen) -reveal else 0f).setDuration(140)
            .withEndAction { if (!toOpen) deleteBtn.visibility = View.INVISIBLE }.start()
    }
}
