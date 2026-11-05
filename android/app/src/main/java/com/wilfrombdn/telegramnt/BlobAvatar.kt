package com.wilfrombdn.telegramnt

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The picture shown for someone with no photo: a soft blob in greys with a faint tint, drawn from a seed
 * (their username), so it is the same on every phone, never changes and needs nothing stored or sent.
 * A normal text contact's seed starts with "sms:" (their phone number) and gets a plain black-and-grey glow instead.
 * Keep the drawing rules below exactly as they are: changing them changes everyone's picture.
 */
internal class BlobAvatar(private val seed: String) : Drawable() {
    private val sms = seed.startsWith("sms:")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // seeded random numbers (FNV hash, then mulberry32)
    private var h = run { var x = -2128831035; for (c in seed) { x = x xor c.code; x *= 16777619 }; x }
    private fun next(): Float {
        h += 0x6D2B79F5
        var t = (h xor (h ushr 15)) * (1 or h)
        t = t xor (t + (t xor (t ushr 7)) * (61 or t))
        return (((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0).toFloat()
    }

    private fun hsl(hue: Float, s: Float, l: Float): Int {   // s, l in 0..1
        val v = l + s * minOf(l, 1 - l)
        val sv = if (v == 0f) 0f else 2 * (1 - l / v)
        return Color.HSVToColor(floatArrayOf(hue, sv, v))
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val k = w / 48f
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(k, k)
        paint.shader = null; paint.color = if (sms) 0xFF1A1A1C.toInt() else 0xFF111113.toInt()
        canvas.drawRect(0f, 0f, 48f, 48f, paint)
        if (sms) drawGlow(canvas) else drawBlob(canvas)
        canvas.restore()
    }

    /** A clearly visible grey disc: a mid-grey base that never matches the dark background, a bright soft light, a shadow, and a thin rim. */
    private fun drawGlow(c: Canvas) {
        c.save(); c.clipPath(Path().apply { addCircle(24f, 24f, 21.2f, Path.Direction.CW) })   // fill stays inside, leaving a small gap to the rim
        val base = (88 + next() * 40).toInt()
        val x0 = next() * 20f; val y0 = next() * 20f
        paint.shader = LinearGradient(x0, y0, 48f - x0, 48f - y0, Color.rgb(base + 55, base + 55, base + 57), Color.rgb(base - 20, base - 20, base - 18), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 48f, 48f, paint)
        paint.shader = RadialGradient(6f + next() * 36f, 6f + next() * 36f, 24f, 0x66FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 48f, 48f, paint)
        paint.shader = RadialGradient(6f + next() * 36f, 6f + next() * 36f, 22f, 0x44000000, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 48f, 48f, paint)
        drawStreak(c)
        c.restore()
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = 0x55FFFFFF
        c.drawCircle(24f, 24f, 23.4f, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * One soft colourful streak (a line or a blob) across the disc, for a marble look. Its numbers come from their own 64-bit hash of the seed,
     * apart from the random numbers above, so the grey look stays exactly as it was and two contacts essentially never share a streak.
     */
    private fun drawStreak(c: Canvas) {
        var x = -0x340d631b7bdddcdbL   // FNV-1a, 64 bit
        for (ch in seed) { x = x xor ch.code.toLong(); x *= 0x100000001b3L }
        x = x xor (x ushr 29); x *= -0x61c8864680b583ebL; x = x xor (x ushr 32)
        fun bits(shift: Int, n: Int) = ((x ushr shift) and ((1L shl n) - 1)).toInt()
        val angle = (bits(0, 9) % 360).toFloat()
        val offset = (bits(9, 6) / 63f - 0.5f) * 22f
        val thick = 4f + bits(15, 5) / 31f * 8f
        val hueA = (bits(20, 9) % 360).toFloat()
        val hueB = (hueA + 70f + bits(29, 8) % 160) % 360f
        val blob = bits(37, 1) == 1
        val second = bits(38, 1) == 1
        fun band(deg: Float, off: Float, t: Float, alpha: Int) {
            c.save(); c.rotate(deg, 24f, 24f); c.translate(0f, off)
            paint.shader = LinearGradient(0f, 0f, 48f, 0f,
                Color.HSVToColor(alpha, floatArrayOf(hueA, 0.6f, 1f)), Color.HSVToColor(alpha, floatArrayOf(hueB, 0.6f, 1f)), Shader.TileMode.CLAMP)
            if (blob) c.drawOval(4f, 24f - t, 44f, 24f + t, paint) else c.drawRect(-10f, 24f - t / 2, 58f, 24f + t / 2, paint)
            c.restore()
        }
        band(angle, offset, thick, 0x60)
        if (second) band(angle + 25f + bits(46, 6), -offset * 0.8f - 2f, thick * 0.45f, 0x40)
        paint.shader = null
    }

    /** A rounded organic shape in greys with the faintest colour, and a glow behind it. */
    private fun drawBlob(c: Canvas) {
        val hue = next() * 360f
        val n = 7; val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) { val a = i.toFloat() / n * 6.283f; val rad = 11f + next() * 8f; px[i] = 24f + Math.cos(a.toDouble()).toFloat() * rad; py[i] = 24f + Math.sin(a.toDouble()).toFloat() * rad }
        val path = Path()
        for (i in 0 until n) {
            val p0 = (i + n - 1) % n; val p2 = (i + 1) % n
            if (i == 0) path.moveTo((px[p0] + px[i]) / 2, (py[p0] + py[i]) / 2)
            path.quadTo(px[i], py[i], (px[i] + px[p2]) / 2, (py[i] + py[p2]) / 2)
        }
        path.close()
        val light = 0.30f + next() * 0.50f
        val gx = next() * 48f; val gy = next() * 48f
        val glow = hsl((hue + 40f) % 360f, 0.22f, 0.80f)
        paint.shader = RadialGradient(gx, gy, 26f, (glow and 0x00FFFFFF) or (0x80 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 48f, 48f, paint)
        paint.shader = LinearGradient(0f, 0f, 48f, 48f, hsl(hue, 0.14f, minOf(0.92f, light + 0.22f)), hsl(hue, 0.14f, maxOf(0.14f, light - 0.18f)), Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
