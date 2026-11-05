package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The two short scripted scenes of levels 3 and 4, in the same pixel art. Kind 0: the cop from level 1 is waiting where the jetpack
 * dropped you; he takes the letter, walks you to his cruiser and drives off. Kind 1: the cruiser pulls up outside the town hall,
 * you get out with the cop at gunpoint and walk him to the door.
 */
internal class CineScene(val kind: Int) : PixelSprites() {
    private val S = 16
    val W = if (kind == 0) 14 * S else 16 * S
    val H = if (kind == 0) 20 * S else 24 * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    var t = 0f

    val duration get() = if (kind == 0) 10.5f else 11f
    val fadeStart get() = if (kind == 0) 8.6f else 9.6f
    val done get() = t >= duration

    /** The line shown under the picture right now (or null). */
    fun caption(): String? = if (kind == 0) when {
        t in 2.2f..3.8f -> "I know what the mayor has done."
        t in 3.8f..5.2f -> "I know what you plan to do,"
        t in 5.2f..6.4f -> "and I can get you there."
        t in 6.4f..7.8f -> "Let's end this shit, brother."
        else -> null }
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

    // ---------- kind 0: the cop drags you to his cruiser (a side view, like the jetpack run) ----------
    private val steelC = 0xFF6A7890.toInt(); private val steelCD = 0xFF3A4458.toInt(); private val steelCL = 0xFFA9B4C8.toInt()
    private fun mod(a: Int, b: Int) = ((a % b) + b) % b

    private fun sideCar(c: Canvas, x: Int, floor: Int, doorOpen: Boolean, lights: Boolean, moving: Boolean) {
        // a police cruiser seen from the side, nose to the right
        rect(c, x - 4, floor - 1, 78, 4, a(PX.K, 0x80))
        rect(c, x, floor - 22, 72, 17, PX.K); rect(c, x + 1, floor - 21, 70, 15, 0xFF1A2A66.toInt()); rect(c, x + 1, floor - 21, 70, 3, 0xFF3A56B8.toInt())
        rect(c, x + 6, floor - 34, 44, 14, PX.K); rect(c, x + 7, floor - 33, 42, 13, 0xFF1A2A66.toInt()); rect(c, x + 7, floor - 33, 42, 2, 0xFF3A56B8.toInt())
        rect(c, x + 10, floor - 31, 17, 9, 0xFF0B1230.toInt()); rect(c, x + 29, floor - 31, 17, 9, 0xFF0B1230.toInt()); rect(c, x + 10, floor - 31, 17, 2, 0xFF4C7DFF.toInt()); rect(c, x + 29, floor - 31, 17, 2, 0xFF4C7DFF.toInt())
        rect(c, x + 1, floor - 14, 70, 6, PX.W); rect(c, x + 1, floor - 14, 70, 1, 0xFFD8DCE8.toInt()); rect(c, x + 26, floor - 21, 1, 14, PX.K)
        rect(c, x + 16, floor - 12, 14, 2, PX.K)
        val flip = ((animT * 5f).toInt() % 2 == 0)
        rect(c, x + 20, floor - 38, 9, 4, PX.K); rect(c, x + 21, floor - 37, 7, 3, if (lights && flip) PX.RED else 0xFF5A1020.toInt()); rect(c, x + 29, floor - 38, 9, 4, PX.K); rect(c, x + 30, floor - 37, 7, 3, if (lights && !flip) PX.BLUE else 0xFF102058.toInt())
        rect(c, x + 69, floor - 16, 4, 5, 0xFFFFE9A0.toInt()); rect(c, x - 1, floor - 16, 3, 5, PX.RED)
        for (wx in intArrayOf(x + 12, x + 54)) { rect(c, wx - 1, floor - 11, 14, 14, PX.K); rect(c, wx + 1, floor - 9, 10, 10, 0xFF2A3040.toInt()); rect(c, wx + 4, floor - 6, 4, 4, steelCL) }
        if (doorOpen) { rect(c, x + 27, floor - 20, 9, 16, PX.K); rect(c, x + 28, floor - 19, 7, 14, 0xFF14204E.toInt()) }
        if (moving) for (k in 0..3) rect(c, x - 8 - k * 7, floor - 5 + (sin(animT * 9f + k) * 1.5f).toInt(), 6, 5, a(0xFF8A94AC.toInt(), 0x90 - k * 0x20))
        if (lights) { p.color = a(PX.RED, 0x30); c.drawRect((x - 30).toFloat(), (floor - 40).toFloat(), 130f, 40f, p) }
    }

    private fun exit(c: Canvas) {
        val floorTop = 305
        rect(c, 0, 0, W, H, 0xFF07101A.toInt())
        for (y in 0 until H step 8) rect(c, 0, y + 7, W, 1, 0xFF0C1C26.toInt())
        for (i in 0..3) { val wx = 6 + i * 58; rect(c, wx, 48, 34, 52, PX.K); rect(c, wx + 2, 50, 30, 48, 0xFF0B2250.toInt()); rect(c, wx + 2, 50, 30, 14, 0xFF1B4A8C.toInt()); rect(c, wx + 16, 50, 2, 48, PX.K); rect(c, wx + 2, 72, 30, 2, PX.K); px(c, wx + 6, 56, PX.W); px(c, wx + 24, 62, PX.W); rect(c, wx - 2, 100, 38, 3, steelCD) }
        rect(c, 0, 24, W, 8, PX.K); rect(c, 0, 25, W, 6, 0xFF2F6F73.toInt()); rect(c, 0, 25, W, 2, 0xFF8AF0E8.toInt())
        for (i in 0..1) { val rx = 8 + i * 118; rect(c, rx, floorTop - 112, 3, 112, steelCD); rect(c, rx + 56, floorTop - 112, 3, 112, steelCD)
            for (sh in 0..2) { val yy = floorTop - 100 + sh * 36; rect(c, rx, yy, 59, 3, steelC); rect(c, rx, yy, 59, 1, steelCL)
                for (b in 0..2) if ((b + sh + i) % 4 != 0) { val bx = rx + 4 + b * 18; rect(c, bx, yy - 15, 15, 15, PX.K); rect(c, bx + 1, yy - 14, 13, 13, 0xFF7A6038.toInt()); rect(c, bx + 1, yy - 14, 13, 2, 0xFFA8884A.toInt()); rect(c, bx + 6, yy - 14, 3, 13, 0xFF5A4626.toInt()) } } }
        rect(c, 0, 0, W, 18, steelCD); rect(c, 0, 0, W, 3, steelCL); rect(c, 0, 15, W, 3, PX.K)
        for (i in 0..1) { val x = 40 + i * 128; rect(c, x, 18, 2, 8, PX.K); rect(c, x - 6, 26, 14, 6, PX.K); rect(c, x - 5, 27, 12, 4, PX.W) }
        rect(c, 0, floorTop, W, H - floorTop, 0xFF161C24.toInt()); rect(c, 0, floorTop, W, 3, steelCL); rect(c, 0, floorTop + 3, W, 2, PX.K)
        for (i in 0..14) if (i % 2 == 0) { rect(c, i * 16, floorTop + 8, 8, 6, 0xFFFFC82E.toInt()); rect(c, i * 16 + 8, floorTop + 8, 8, 6, 0xFF1B1D22.toInt()) }
        // a big loading door at the right, open to the night, where the cruiser waits
        rect(c, W - 70, floorTop - 96, 70, 96, PX.K); rect(c, W - 66, floorTop - 92, 66, 92, 0xFF0A1226.toInt()); rect(c, W - 66, floorTop - 92, 66, 8, steelCD)
        for (s in 0..3) px(c, W - 60 + s * 14, floorTop - 70 + s * 3, PX.W)
        val carStop = W - 82
        val drive = ((t - 7.0f) / 2.4f).coerceIn(0f, 1f)
        val carX = carStop + (drive * drive * 300f).toInt()
        val inCar = t > 6.0f
        sideCar(c, carX, floorTop + 4, doorOpen = t in 5.4f..7.0f, lights = t > 5.0f, moving = drive > 0f)
        // you, flung down in front of the cop: the dead jetpack beside you
        val k1 = ((t - 0.3f) / 1.7f).coerceIn(0f, 1f)
        val k2 = ((t - 3.4f) / 2.6f).coerceIn(0f, 1f)
        val playerX = (62 + k2 * 88).toInt()
        val copX = if (t < 3.4f) (250 - (250 - 90) * k1).toInt() else playerX + 26
        if (t < 3.4f) { rect(c, 14, floorTop - 3, 8, 6, 0xFFC03030.toInt()); rect(c, 14, floorTop - 3, 8, 1, 0xFFFF7070.toInt()); for (k in 0..3) { val ph = (animT * 0.9f + k * 0.25f) % 1f; rect(c, 16 + (sin(animT * 2f + k) * 3).toInt(), floorTop - 6 - (ph * 22).toInt(), 3, 3, a(0xFF9AA4B5.toInt(), ((1f - ph) * 0xA0).toInt())) } }
        else { rect(c, 14, floorTop - 3, 8, 6, 0xFFC03030.toInt()); rect(c, 14, floorTop - 3, 8, 1, 0xFFFF7070.toInt()) }
        val letterTaken = t > 3.0f
        if (!inCar) lying(c, playerX, floorTop + 4, clerk, t < 3.4f, !letterTaken)
        val moveFrame = if (t in 0.3f..2.0f || t in 3.4f..6.0f) 1 + ((animT * 8f).toInt() % 2) else 0
        if (t < 6.8f) {
            val fy = floorTop + 10
            rect(c, copX - 5, fy - 1, 11, 3, a(PX.K, 0x70))
            val kneel = if (t in 2.1f..3.4f) 6 else 0
            folk(c, copX, fy, 3, moveFrame, police, false, letterTaken && t < 5.8f, kneel)
            if (t in 2.1f..3.4f) { val lk = ((t - 2.1f) / 1.3f).coerceIn(0f, 1f); rect(c, playerX + 4 - (lk * 10).toInt(), floorTop - 18 - (lk * 10).toInt(), 7, 5, PX.W); px(c, playerX + 6 - (lk * 10).toInt(), floorTop - 16 - (lk * 10).toInt(), PX.RED) }
        }
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
