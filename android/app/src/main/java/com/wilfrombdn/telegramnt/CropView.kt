package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.ByteArrayOutputStream

/**
 * Shows a photo behind a round window. Drag to move, pinch to zoom; [crop] returns
 * the part inside the window as a small square JPEG.
 */
class CropView(context: Context, private val photo: Bitmap) : View(context) {
    private val m = Matrix()
    private val window = RectF()
    private val shade = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2 * resources.displayMetrics.density
    }
    private val draw = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f
    private var minScale = 1f
    private var dx = 0f
    private var dy = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = (scale * d.scaleFactor).coerceIn(minScale, minScale * 8) / scale
            // zoom around the fingers
            dx = d.focusX - (d.focusX - dx) * f
            dy = d.focusY - (d.focusY - dy) * f
            scale *= f
            settle()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val side = minOf(w, h) * 0.86f
        window.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        // start with the photo just covering the window, centred
        minScale = maxOf(side / photo.width, side / photo.height)
        scale = minScale
        dx = window.centerX() - photo.width * scale / 2
        dy = window.centerY() - photo.height * scale / 2
        settle()
    }

    /** Never leave a gap inside the window. */
    private fun settle() {
        dx = dx.coerceIn(minOf(window.right - photo.width * scale, window.left), window.left)
        dy = dy.coerceIn(minOf(window.bottom - photo.height * scale, window.top), window.top)
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = if (e.actionIndex == 0 && e.pointerCount > 1) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> if (!pinch.isInProgress && e.pointerCount == 1) {
                dx += e.x - lastX
                dy += e.y - lastY
                lastX = e.x; lastY = e.y
                settle()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        c.drawBitmap(photo, m, draw)
        val hole = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addOval(window, Path.Direction.CW)
        }
        c.drawPath(hole, shade)
        c.drawOval(window, ring)
    }

    /** The square under the window, [size] px, as a JPEG small enough to send. */
    fun crop(size: Int = 160): ByteArray {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val k = size / window.width()
        val mm = Matrix(m).apply { postTranslate(-window.left, -window.top); postScale(k, k) }
        Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(photo, mm, draw) }
        var q = 85
        while (true) {
            val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            if (bytes.size <= Core.MAX_AVATAR || q <= 30) return bytes
            q -= 10
        }
    }
}
