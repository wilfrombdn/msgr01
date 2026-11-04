package com.wilfrombdn.telegramnt

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

// Pinch to zoom in a chat: spread two fingers to make the message text bigger, pinch to make it smaller.

/** A chat's scroller. Two fingers change the message text size live; the size is saved when they lift. */
internal class PinchScroll(ctx: Context, private val onScale: (Float) -> Unit, private val onDone: () -> Unit) : ScrollView(ctx) {
    private var pinching = false
    private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinching = true
            // drop whatever the first finger was doing (scroll, long-press) so it doesn't fight the pinch
            val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super@PinchScroll.dispatchTouchEvent(cancel); cancel.recycle()
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean { onScale(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { onDone() }
    })

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        if (pinching) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
            return true   // keep swallowing until every finger is up, so the last one doesn't tap or scroll by accident
        }
        return super.dispatchTouchEvent(e)
    }
}

/** Marks a message's text so a pinch can resize all of them at once. */
internal fun TextView.markMessageText() = apply { setTag(R.id.msg_text, true) }

internal fun MainActivity.setMessageTextSize(root: View?, sp: Float) {
    if (root is TextView && root.getTag(R.id.msg_text) == true) root.textSize = sp
    if (root is ViewGroup) for (i in 0 until root.childCount) setMessageTextSize(root.getChildAt(i), sp)
}
