package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.roundToInt

/**
 * The two short scripted scenes of levels 3 and 4, in the same pixel art. Kind 0: the cop from level 1 is waiting where the jetpack
 * dropped you; he takes the letter, walks you to his cruiser and drives off. Kind 1: the cruiser pulls up outside the town hall,
 * you get out with the cop at gunpoint and walk him to the door.
 */
internal class CineScene(val kind: Int) : PixelSprites() {
    private val S = 16
    val W = 16 * S
    val H = 24 * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    var t = 0f

    val duration get() = if (kind == 0) 10.5f else 11f
    val fadeStart get() = if (kind == 0) 8.6f else 9.6f
    val done get() = t >= duration

    /** The line shown under the picture right now (or null). */
    fun caption(): String? = if (kind == 0) { if (t in 2.4f..5.4f) "FORMER OFFICER: They took the others." else null }
        else { if (t in 6.8f..9.0f) "Nobody move. Walk." else null }

    private fun ease(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)
    private fun seg(t0: Float, t1: Float) = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)

    private fun car(c: Canvas, cx: Int, cy: Int, lights: Boolean) {
        rect(c, cx - 14, cy + 22, 29, 4, a(PX.K, 0x70))
        rect(c, cx - 13, cy - 24, 27, 47, PX.K)
        rect(c, cx - 12, cy - 23, 25, 45, 0xFF1A2A66.toInt()); rect(c, cx - 12, cy - 23, 25, 3, 0xFF3A56B8.toInt())
        rect(c, cx - 12, cy - 4, 25, 10, PX.W)                                          // white doors
        rect(c, cx - 10, cy - 17, 21, 9, 0xFF0B1230.toInt()); rect(c, cx - 9, cy - 16, 19, 3, 0xFF4C7DFF.toInt())  // windscreen
        rect(c, cx - 10, cy + 10, 21, 7, 0xFF0B1230.toInt())                           // back window
        rect(c, cx - 12, cy + 18, 4, 3, PX.RED); rect(c, cx + 9, cy + 18, 4, 3, PX.RED)
        rect(c, cx - 8, cy - 2, 17, 2, PX.K)
        val flip = ((animT * 5f).toInt() % 2 == 0)
        rect(c, cx - 9, cy - 8, 8, 3, if (lights && flip) PX.RED else 0xFF5A1020.toInt()); rect(c, cx + 1, cy - 8, 8, 3, if (lights && !flip) PX.BLUE else 0xFF102058.toInt())
    }

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        if (kind == 0) exit(c) else hall(c)
        return frame
    }

    // ---------- kind 0 ----------
    private fun exit(c: Canvas) {
        rect(c, 0, 0, W, H, 0xFF14181F.toInt())
        for (r in 0 until 24) for (col in 0 until 16) rect(c, col * S, r * S, S, S, if ((col + r) % 2 == 0) 0xFF1B2128.toInt() else 0xFF20272F.toInt())
        for (i in 0 until 16) { rect(c, i * S, 0, S, 6 * S, 0xFF0C1218.toInt()) }
        for (col in 0 until 16 step 4) { rect(c, col * S + 2, 10, 3, 80, 0xFF3A4458.toInt()); for (sh in 0..2) { rect(c, col * S + 2, 24 + sh * 28, 56, 3, 0xFF6A7890.toInt()); rect(c, col * S + 8, 8 + sh * 28, 14, 14, 0xFF7A6038.toInt()) } }
        rect(c, 0, 6 * S, W, 4, 0xFFFFC82E.toInt())
        // a loading-bay door the cruiser stands by
        val carX = 12 * S; val carY0 = 17 * S
        val drive = seg(7.0f, 9.6f)
        val cy = (carY0 - drive * drive * 26 * S).roundToInt()
        car(c, carX, cy, drive > 0f || t > 5.6f)
        // the people
        val copX0 = 7.5f; val copY0 = 11f
        val plX = 7.5f; val plY = 15f
        // cop walks to the car's far door, then the player follows; both vanish into the car at 6.2 / 6.9
        val k1 = seg(5.4f, 6.4f); val k2 = seg(5.9f, 6.9f)
        val copX = ease(copX0, 10.3f, k1); val copY = ease(copY0, 17.5f, k1)
        val plXx = ease(plX, 13.8f, k2); val plYy = ease(plY, 17.5f, k2)
        if (t < 6.4f) {
            val fx = (copX * S).roundToInt(); val fy = (copY * S).roundToInt()
            rect(c, fx - 5, fy + 5, 11, 3, a(PX.K, 0x70))
            folk(c, fx, fy + 6, if (k1 > 0f) 2 else 2, if (k1 in 0.01f..0.99f) 1 + ((animT * 8f).toInt() % 2) else 0, police, false, t > 3.6f)
        }
        val px0 = (plXx * S).roundToInt(); val py0 = (plYy * S).roundToInt()
        if (t < 1.4f) lying(c, px0, py0 + 6, clerk, true, true)
        else if (t < 6.9f) {
            rect(c, px0 - 5, py0 + 5, 11, 3, a(PX.K, 0x70))
            val up = seg(1.4f, 2.4f)
            if (up < 1f) folk(c, px0, py0 + 6, 0, 0, clerk, false, true, 7 - (up * 7).toInt())
            else folk(c, px0, py0 + 6, 0, if (k2 in 0.01f..0.99f) 1 + ((animT * 8f).toInt() % 2) else 0, clerk, false, t < 3.4f)
        }
        // the letter passes from you to him
        if (t in 2.8f..3.6f) { val k = seg(2.8f, 3.6f); rect(c, (ease(7.5f, 7.5f, k) * S).roundToInt() - 3, (ease(14f, 11.6f, k) * S).roundToInt() - 14, 7, 5, PX.W) }
    }

    // ---------- kind 1 ----------
    private fun hall(c: Canvas) {
        rect(c, 0, 0, W, H, 0xFF0A1226.toInt())
        // the town hall: wall, pillars, steps, door
        rect(c, 0, 0, W, 6 * S, 0xFF3A4260.toInt()); rect(c, 0, 5 * S, W, S, 0xFF2A3050.toInt())
        rect(c, 2 * S, 0, 12 * S, 3 * S, 0xFF8A94AC.toInt()); rect(c, 2 * S, 3 * S - 4, 12 * S, 4, PX.K)
        for (col in intArrayOf(3, 5, 10, 12)) { rect(c, col * S, 3 * S, 14, 3 * S + 4, 0xFFC6CFE4.toInt()); rect(c, col * S + 10, 3 * S, 4, 3 * S + 4, 0xFF4A5470.toInt()) }
        rect(c, 7 * S, 3 * S, 2 * S, 3 * S + 4, PX.K); rect(c, 7 * S + 3, 3 * S + 3, 2 * S - 6, 3 * S, 0xFF6A1020.toInt()); rect(c, 8 * S - 1, 4 * S, 2, 2 * S, PX.GOLD)
        rect(c, 6 * S, 0, 4 * S, 12, 0xFFB02038.toInt()); rect(c, 6 * S, 12, 4 * S, 2, PX.GOLD)
        for (i in 0..3) rect(c, (3 - i) * S, 6 * S + i * 6, (10 + i * 2) * S, 6, if (i % 2 == 0) 0xFF8A94AC.toInt() else 0xFF6A7490.toInt())
        // the street
        rect(c, 0, 8 * S, W, 16 * S, 0xFF1A2038.toInt())
        for (i in 0 until 8) rect(c, i * 2 * S + 6, 17 * S, 18, 3, 0xFFB8B070.toInt())
        for (x in intArrayOf(1, 14)) { rect(c, x * S, 8 * S, 3, 5 * S, PX.K); rect(c, x * S - 4, 8 * S - 4, 11, 5, PX.WARM) }
        // the car arrives
        val arrive = seg(0.2f, 2.6f)
        val cy = (ease(28f, 15f, 1f - (1f - arrive) * (1f - arrive)) * S).roundToInt()
        val carX = 8 * S
        car(c, carX, cy, false)
        // both get out at 3.0 s
        val out = seg(3.0f, 3.8f)
        if (t >= 3.0f) {
            // the cop with his hands up, you behind him with a gun, walking to the door from 4.4 s
            val walk = seg(4.4f, 9.4f)
            val cx = ease(10.4f, 8.5f, seg(3.0f, 4.4f)); val cyy = ease(15.6f, 14.2f, seg(3.0f, 4.4f))
            val copX = cx; val copY = ease(cyy, 7.2f, walk)
            val plY = copY + 1.5f; val plX = ease(copX + (if (t < 4.4f) 0.2f else 0f), 8.5f, 1f)
            val cx0 = (copX * S).roundToInt(); val cy0 = (copY * S).roundToInt() + 6
            rect(c, cx0 - 5, cy0 - 1, 11, 3, a(PX.K, 0x70))
            folk(c, cx0, cy0, 0, if (walk in 0.01f..0.99f) 1 + ((animT * 6f).toInt() % 2) else 0, police, false, false)
            rect(c, cx0 - 8, cy0 - 20, 2, 5, police.skin); rect(c, cx0 + 7, cy0 - 20, 2, 5, police.skin)       // hands up
            val px0 = (plX * S).roundToInt(); val py0 = (plY * S).roundToInt() + 6
            if (t >= 3.4f) {
                rect(c, px0 - 5, py0 - 1, 11, 3, a(PX.K, 0x70))
                folk(c, px0, py0, 0, if (walk in 0.01f..0.99f) 1 + ((animT * 6f).toInt() % 2) else 0, clerk, false, true)
                rect(c, px0 - 2, py0 - 16, 4, 3, PX.K); rect(c, px0 - 1, py0 - 19, 2, 4, 0xFF9AA4B5.toInt())           // the gun, aimed at him
            }
        }
    }
}
