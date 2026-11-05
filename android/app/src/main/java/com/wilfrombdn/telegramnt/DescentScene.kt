package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Level 2, between the street and the sewer maze: you climb down the manhole. A black shaft, a cone of street light falling from the
 * open lid above, the ladder, you going down rung by rung with dust drifting in the light; then the lid slides shut over the hole,
 * the light goes, and it's pitch black (the maze then glows up). 160 x 320 art pixels.
 */
internal class DescentScene : PixelSprites() {
    val W = 160
    val H = 320
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    private fun K(v: Long) = v.toInt()
    private val HX = 80; private val HY = 26             // the manhole up top
    companion object {
        const val CLIMB0 = 0.35f; const val CLIMB1 = 2.5f // you climb down between these
        const val LID0 = 2.6f; const val LID1 = 3.15f     // the lid slides shut
        const val BLACK = 3.7f                            // the maze takes over
        const val END = 4.6f
    }
    /** How much light still comes down the shaft (1 open .. 0 shut). */
    private fun open(t: Float) = 1f - ((t - LID0) / (LID1 - LID0)).coerceIn(0f, 1f)

    fun render(t: Float, clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0xFF000000.toInt(), PorterDuff.Mode.SRC)
        val op = open(t)
        // the cone of light down the shaft, dithered, with the brick walls showing only where it falls
        for (y in HY until H) for (x in 0 until W) {
            val k = (y - HY) / (H - HY).toFloat()
            val half = 16f + k * 46f
            val ac = abs(x - HX) / half
            if (ac >= 1f) continue
            val v = (1f - ac) * (1f - k * 0.55f) * op
            val q = floor(v * 5f + bayer[(y and 3) * 4 + (x and 3)] / 16f) / 5f
            if (q <= 0f) continue
            val brick = ((y / 6) % 2 == 0 && x % 14 == 0) || ((y / 6) % 2 == 1 && (x + 7) % 14 == 0) || y % 6 == 0
            val base = if (brick) K(0xFF1A2026) else K(0xFF2E3A40)
            px(c, x, y, a(base, (q * 255).toInt().coerceAtMost(255)))
            if (q > 0.6f && !brick && hash(x, y, 4) % 9 == 0) px(c, x, y, a(K(0xFF4A6A5A), (q * 200).toInt()))     // damp moss
        }
        // the ladder: two rails and the rungs, lit where the light reaches
        for (y in HY + 4 until H - 18) {
            val k = (y - HY) / (H - HY).toFloat(); val lit = ((1f - k * 0.6f) * op).coerceIn(0f, 1f)
            val rail = a(K(0xFF9AA4B4), (0x40 + lit * 0xB0).toInt())
            px(c, HX - 11, y, rail); px(c, HX + 10, y, rail); px(c, HX - 10, y, a(PX.K, 0x80)); px(c, HX + 11, y, a(PX.K, 0x80))
            if ((y - HY) % 10 == 0) rect(c, HX - 10, y, 20, 1, a(K(0xFF7A848E), (0x30 + lit * 0xA0).toInt()))
        }
        // water at the bottom, catching a little light
        for (y in H - 16 until H step 2) for (x in 0 until W step 3) if (hash(x / 6, y, (clock * 3f).toInt()) % 4 == 0) px(c, x, y, a(K(0xFF5A7A8A), (0x60 * op).toInt() + 0x10))
        // dust drifting in the light
        for (k in 0 until 18) {
            val ph = (clock * 0.12f + k * 0.055f) % 1f
            val x = HX + ((hash(k, 1, 7) % 60) - 30) + (sin(clock + k) * 3f).toInt(); val y = HY + 10 + (ph * (H - HY - 30)).toInt()
            px(c, x, y, a(PX.W, ((1f - ph) * 0xA0 * op).toInt()))
        }
        // you, climbing down: from behind, hands up on the rungs
        if (t < LID1 + 0.3f) {
            val k = ((t - CLIMB0) / (CLIMB1 - CLIMB0)).coerceIn(0f, 1f)
            val fy = (HY + 30 + k * (H - HY - 70)).roundToInt()
            val rung = ((fy - HY) / 5) % 2
            val lit = (1f - k * 0.55f) * op + 0.15f
            if (lit > 0.1f) {
                folk(c, HX, fy, 0, if (t in CLIMB0..CLIMB1) rung else 0, clerk, false, false)
                rect(c, HX - 7, fy - 19 - rung * 2, 2, 7, clerk.shirt); rect(c, HX + 6, fy - 19 - (1 - rung) * 2, 2, 7, clerk.shirt)   // arms up to the rungs
                rect(c, HX - 7, fy - 20 - rung * 2, 2, 1, clerk.skin); rect(c, HX + 6, fy - 20 - (1 - rung) * 2, 2, 1, clerk.skin)
                if (lit < 0.9f) rect(c, HX - 9, fy - 22, 19, 24, a(PX.K, ((1f - lit) * 0xC0).toInt()))
            }
        }
        // the opening above: night sky and a street lamp through the hole, then the lid slides over it
        val ow = 20; val oh = 7
        for (dy in -oh..oh) { val hw = (ow * kotlin.math.sqrt(1f - (dy * dy) / (oh * oh).toFloat())).roundToInt(); rect(c, HX - hw, HY + dy, hw * 2, 1, if (op > 0f) mix(K(0xFF1A2A50), K(0xFFDCEBFF), (1f - abs(dy) / oh.toFloat()) * 0.7f) else PX.K) }
        rect(c, HX - ow - 2, HY - 1, 2, 3, K(0xFF3A3E48)); rect(c, HX + ow, HY - 1, 2, 3, K(0xFF3A3E48))
        val lidX = (-40 + (HX + 40) * (1f - op)).roundToInt()
        if (op < 1f) for (dy in -oh..oh) { val hw = (ow * kotlin.math.sqrt(1f - (dy * dy) / (oh * oh).toFloat())).roundToInt(); rect(c, lidX - hw, HY + dy, hw * 2, 1, K(0xFF2A2E36)); if (dy == -oh + 1) rect(c, lidX - hw, HY + dy, hw * 2, 1, K(0xFF5A606A)) }
        // pitch black once the lid is on
        if (t > LID1) { val k = ((t - LID1) / 0.35f).coerceIn(0f, 1f); p.style = android.graphics.Paint.Style.FILL; p.color = a(PX.K, (k * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }
    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (((c1 shr 16) and 0xFF) * (1 - tt) + ((c2 shr 16) and 0xFF) * tt).toInt(); val g = (((c1 shr 8) and 0xFF) * (1 - tt) + ((c2 shr 8) and 0xFF) * tt).toInt()
        val b = ((c1 and 0xFF) * (1 - tt) + (c2 and 0xFF) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
