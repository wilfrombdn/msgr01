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

    val duration get() = if (kind == 0) 22.4f else 18.4f
    val fadeStart get() = if (kind == 0) 20.8f else 17.0f
    /** Kind 0 cuts from the hall to inside the cruiser at this moment. */
    private val INSIDE = 7.2f
    val done get() = t >= duration

    /** The line shown under the picture right now (or null). */
    fun caption(): String? = if (kind == 0) when {   // all said in the car, on the way
        t in 7.9f..9.5f -> "I know what the mayor has done."
        t in 9.6f..11.1f -> "I know what you plan to do,"
        t in 11.2f..12.6f -> "and I can get you there."
        t in 12.8f..14.3f -> "Let's end this shit, brother."
        t in 14.6f..16.2f -> "Here. Take my gun."
        t in 16.4f..18.4f -> "Use me as a hostage. It's the only way they let you in close."
        t in 18.6f..20.4f -> "Nobody else gets through those doors."
        else -> null }
        else null

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
        if (t >= INSIDE) { insideCar(c); return }
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
        // moonlight slanting in through the windows, lamp light pooling under the hanging lamps
        for (i in 0..3) { val wx = 6 + i * 58; c.drawBitmap(moonBeam, wx.toFloat() - 4f, 98f, plain) }
        for (i in 0..1) c.drawBitmap(lampCone, 40f + i * 128 - lampCone.width / 2f, 32f, plain)
        // rain falling past the open loading door
        for (k in 0 until 26) { val rx = W - 64 + (k * 37) % 62; val ry = floorTop - 92 + ((animT * 260f + k * 53f) % 92f).toInt(); rect(c, rx, ry, 1, 4, a(0xFF8AA0C8.toInt(), 0x80)) }
        val carStop = W - 82
        val carX = carStop
        val inCar = t > 6.0f
        sideCar(c, carX, floorTop + 4, doorOpen = t in 5.4f..7.0f, lights = t > 5.0f, moving = false)
        // the red and blue spilling across the walls and the floor
        if (t > 5.0f) { val flip = (animT * 5f).toInt() % 2 == 0; c.drawBitmap(if (flip) redWash else blueWash, carX - 60f, floorTop - 120f, plain) }
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
        c.drawBitmap(vig, 0f, 0f, plain)
        if (t > INSIDE - 0.5f) { p.color = a(PX.K, (((t - (INSIDE - 0.5f)) / 0.5f).coerceIn(0f, 1f) * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }   // cut to black...
    }

    private val moonBeam: Bitmap by lazy {
        val w = 70; val h = 200; val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val pxs = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val cx = 6 + y * 0.25f; val half = 15f + y * 0.04f; val ac = kotlin.math.abs(x - cx); if (ac > half) continue
            val v = (1f - y / h.toFloat()) * (1f - ac / half); if (bayer[(y and 3) * 4 + (x and 3)] / 16f < v * 0.55f) pxs[y * w + x] = a(0xFFA8C0F0.toInt(), 0x30) }
        bm.setPixels(pxs, 0, w, 0, 0, w, h); bm
    }
    private val lampCone: Bitmap by lazy {
        val w = 120; val h = 280; val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val pxs = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val half = 4f + y * 0.2f; val ac = kotlin.math.abs(x - w / 2f); if (ac > half) continue
            val v = (1f - y / h.toFloat() * 0.7f) * (1f - ac / half); if (bayer[(y and 3) * 4 + (x and 3)] / 16f < v * 0.45f) pxs[y * w + x] = a(0xFFFFE0A0.toInt(), 0x30) }
        bm.setPixels(pxs, 0, w, 0, 0, w, h); bm
    }
    private val redWash by lazy { glowBitmap(90, PX.RED, 0x40) }
    private val blueWash by lazy { glowBitmap(90, PX.BLUE, 0x40) }
    private val vig: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val pxs = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) { val d = (kotlin.math.hypot((x - W / 2f) / (W / 2f), (y - H / 2f) / (H / 2f)) - 0.75f) / 0.55f; if (d <= 0f) continue
            val q = kotlin.math.floor(d.coerceAtMost(1f) * 4f + bayer[(y and 3) * 4 + (x and 3)] / 16f) / 4f; if (q > 0f) pxs[y * W + x] = a(PX.K, (q * 0xB0).toInt()) }
        bm.setPixels(pxs, 0, W, 0, 0, W, H); bm
    }

    /**
     * Inside the cruiser at night, seen from the back seat: the windscreen with rain and the wipers, the city sliding past (street
     * lights sweeping through the car), the dash and the radio glowing; the cop driving on the left, you on the right. He talks
     * (turning his head to you), and hands you his gun.
     */
    private fun insideCar(c: Canvas) {
        val lt = t - INSIDE
        // the night outside the windscreen: a street, buildings and lights sliding by
        rect(c, 0, 0, W, H, 0xFF05070F.toInt())
        val wsTop = 40; val wsBot = 170
        for (y in wsTop until wsBot) { val k = (y - wsTop) / (wsBot - wsTop).toFloat(); rect(c, 14, y, W - 28, 1, mixC(0xFF0A1028.toInt(), 0xFF2A1A3A.toInt(), k)) }
        val scroll = (lt * 60f).toInt()
        for (i in -1..6) { val bx = 14 + i * 44 - scroll % 44; val bh = 30 + (kotlin.math.abs(i * 7 + scroll / 44) % 5) * 12
            rect(c, bx, wsBot - 20 - bh, 36, bh, 0xFF0C0E1E.toInt())
            for (wy in wsBot - 18 - bh until wsBot - 24 step 5) for (wx in bx + 3 until bx + 33 step 6) if (((wx + wy + scroll / 44) * 7) % 5 == 0) rect(c, wx, wy, 2, 2, if ((wx + wy) % 3 == 0) PX.CYAN else PX.WARM) }
        rect(c, 14, wsBot - 20, W - 28, 20, 0xFF14141E.toInt())                                                          // the road ahead
        for (k in 0..4) { val ry = wsBot - 18 + k * 4; rect(c, W / 2 - 1 - k, ry, 2 + k * 2, 2, a(0xFFE8E0B0.toInt(), 0x60)) }   // the centre line
        // rain on the glass, and the wipers sweeping
        for (k in 0 until 40) { val rx = 16 + (k * 53) % (W - 32); val ry = wsTop + ((k * 31 + (lt * 40f).toInt()) % (wsBot - wsTop)); rect(c, rx, ry, 1, 2, a(0xFFB8C8E8.toInt(), 0x70)) }
        val wa = sin(lt * 3.2f) * 0.9f
        for (wx in intArrayOf(W / 2 - 40, W / 2 + 30)) { c.save(); c.rotate((wa * 57.3f) - 90f, wx.toFloat(), wsBot.toFloat()); rect(c, wx, wsBot - 1, 70, 2, PX.K); c.restore() }
        // the frame of the windscreen and the roof
        rect(c, 0, 0, W, wsTop, 0xFF0A0C14.toInt()); rect(c, 0, 0, 14, H, 0xFF0A0C14.toInt()); rect(c, W - 14, 0, 14, H, 0xFF0A0C14.toInt())
        rect(c, 14, wsTop - 2, W - 28, 2, 0xFF2A3040.toInt()); rect(c, W / 2 - 10, wsTop, 20, 8, 0xFF14161E.toInt())   // the mirror
        // the dash: dials, the radio, the police laptop glowing
        rect(c, 0, wsBot, W, 60, 0xFF0E1018.toInt()); rect(c, 0, wsBot, W, 2, 0xFF2A3040.toInt())
        rect(c, 30, wsBot + 10, 40, 14, 0xFF05060A.toInt()); for (k in 0..1) { rect(c, 34 + k * 18, wsBot + 13, 12, 8, 0xFF0A1A2A.toInt()); px(c, 40 + k * 18, wsBot + 16, PX.CYAN) }
        rect(c, W / 2 - 18, wsBot + 14, 36, 10, 0xFF05060A.toInt()); rect(c, W / 2 - 15, wsBot + 16, 22, 4, 0xFF3BFF9A.toInt()); px(c, W / 2 + 10, wsBot + 18, PX.RED)
        rect(c, W - 78, wsBot - 6, 44, 30, 0xFF14161E.toInt()); rect(c, W - 75, wsBot - 3, 38, 22, 0xFF0A2A3A.toInt()); for (k in 0..3) rect(c, W - 72, wsBot + k * 5, 20 + (k * 7) % 12, 1, PX.CYAN)
        c.drawBitmap(dashGlow, W - 56f - 40f, wsBot + 8f - 40f, plain)
        // the two of you, from behind: the cop driving on the left, you on the right
        val seatY = H - 40
        rect(c, 18, seatY - 60, 80, 100, 0xFF101218.toInt()); rect(c, W - 98, seatY - 60, 80, 100, 0xFF101218.toInt())           // the seat backs
        val talking = caption() != null && lt < 13.2f
        val turn = if (talking && (lt * 2f).toInt() % 3 != 0) 1 else 0
        big3(58, seatY - 44) { folk(c, 58, seatY - 44, if (turn == 1) 1 else 0, 0, police, false, false) }
        big3(W - 58, seatY - 44) { folk(c, W - 58, seatY - 44, if (lt > 7.0f && lt < 9.2f) 3 else 0, 0, clerk, false, false) }
        // his hand on the wheel; then the gun, held out to you
        rect(c, 70, wsBot + 30, 30, 4, 0xFF14161E.toInt())
        if (lt in 7.4f..9.0f) { val k = ((lt - 7.4f) / 0.8f).coerceIn(0f, 1f); val gx = (80 + k * 60).toInt(); rect(c, gx, seatY - 70, 14, 6, PX.K); rect(c, gx + 2, seatY - 69, 10, 2, 0xFF9AA4B5.toInt()); rect(c, gx - 6, seatY - 68, 8, 5, police.skin) }
        // street lights sweeping through the car
        val sweep = ((lt * 0.7f) % 1f)
        p.color = a(0xFFFFB060.toInt(), ((1f - kotlin.math.abs(sweep - 0.5f) * 2f) * 0x30).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        c.drawBitmap(vig, 0f, 0f, plain)
        if (lt < 0.6f) { p.color = a(PX.K, ((1f - lt / 0.6f) * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }   // ...and up inside the car
    }
    private val dashGlow by lazy { glowBitmap(40, PX.CYAN, 0x30) }
    private inline fun big3(x: Int, y: Int, what: () -> Unit) { fc.save(); fc.scale(3f, 3f, x.toFloat(), y.toFloat()); what(); fc.restore() }
    private fun mixC(c1: Int, c2: Int, t: Float): Int {
        val r = (((c1 shr 16) and 0xFF) * (1 - t) + ((c2 shr 16) and 0xFF) * t).toInt(); val g = (((c1 shr 8) and 0xFF) * (1 - t) + ((c2 shr 8) and 0xFF) * t).toInt()
        val b = ((c1 and 0xFF) * (1 - t) + (c2 and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }


    // ---------- the town hall front: drawn once into a picture, then only the flags and chimney smoke move ----------
    private var hallBg: Bitmap? = null
    private val lampX = intArrayOf(16, 224)

    /** Brick / block work with mortar lines and a little tint variation per brick. */
    private fun bricks(c: Canvas, x: Int, y: Int, w: Int, h: Int, tints: IntArray, mortar: Int, seed: Int, bw: Int = 8, bh: Int = 4) {
        for (yy in 0 until h) for (xx in 0 until w) {
            val row = yy / bh; val off = if (row % 2 == 0) 0 else bw / 2
            val gx = xx + off
            val col = if (yy % bh == bh - 1 || gx % bw == bw - 1) mortar else tints[hash(gx / bw, row, seed) % tints.size]
            px(c, x + xx, y + yy, col)
        }
    }

    /** An arched window, lit warm (or dark), with a cross bar, a stone frame and a sill. */
    private fun archWindow(c: Canvas, x: Int, y: Int, w: Int, h: Int, glass: Int, glassTop: Int) {
        val stone = 0xFF8A94AC.toInt(); val stoneD = 0xFF5A6482.toInt()
        rect(c, x - 2, y - 2, w + 4, h + 4, stone)
        rect(c, x - 2, y - 2, 1, h + 4, 0xFFB4BED6.toInt()); rect(c, x + w + 1, y - 2, 1, h + 4, stoneD)
        rect(c, x - 1, y - 1, w + 2, h + 1, PX.K)
        rect(c, x, y, w, h, glass); rect(c, x, y, w, h / 3, glassTop)
        rect(c, x + w / 2, y, 1, h, PX.K); rect(c, x, y + h / 2, w, 1, PX.K)
        rect(c, x - 3, y + h + 1, w + 6, 2, stone); rect(c, x - 3, y + h + 3, w + 6, 1, stoneD)
        px(c, x, y, PX.K); px(c, x + w - 1, y, PX.K); px(c, x + 1, y, PX.K); px(c, x + w - 2, y, PX.K); px(c, x, y + 1, PX.K); px(c, x + w - 1, y + 1, PX.K)
    }

    private fun column(c: Canvas, x: Int, top: Int, bottom: Int) {
        val stoneL = 0xFFE0E6F4.toInt(); val stone = 0xFFC6CFE4.toInt(); val stoneM = 0xFF8A94AC.toInt(); val stoneD = 0xFF4A5470.toInt()
        // shadow it throws on the wall
        rect(c, x + 14, top + 4, 5, bottom - top - 4, a(PX.K, 0x50))
        // shaft: lit on the left, dark on the right, with flutes
        rect(c, x, top + 5, 14, bottom - top - 9, stone)
        rect(c, x, top + 5, 3, bottom - top - 9, stoneL)
        rect(c, x + 8, top + 5, 3, bottom - top - 9, stoneM)
        rect(c, x + 11, top + 5, 3, bottom - top - 9, stoneD)
        for (fx in intArrayOf(3, 6, 9, 12)) rect(c, x + fx, top + 6, 1, bottom - top - 11, a(PX.K, 0x38))
        // capital with two scrolls
        rect(c, x - 3, top, 20, 3, stoneL); rect(c, x - 3, top + 3, 20, 2, stoneM); rect(c, x - 3, top, 20, 1, PX.W)
        rect(c, x - 2, top + 5, 18, 2, stoneM)
        rect(c, x - 3, top + 3, 4, 4, stone); rect(c, x + 13, top + 3, 4, 4, stoneD); px(c, x - 2, top + 5, stoneD); px(c, x + 14, top + 5, PX.K)
        // base
        rect(c, x - 2, bottom - 4, 18, 2, stone); rect(c, x - 3, bottom - 2, 20, 2, stoneM); rect(c, x - 3, bottom - 2, 20, 1, stoneL)
    }

    private fun lamp(c: Canvas, x: Int, glow: Bitmap) {
        val iron = 0xFF1A2030.toInt(); val ironL = 0xFF3A4660.toInt()
        c.drawBitmap(glow, (x - glow.width / 2).toFloat(), 122f - glow.height / 2f + 10f, plain)
        // pole with a fluted base
        rect(c, x - 1, 136, 3, 70, iron); rect(c, x - 1, 136, 1, 70, ironL)
        rect(c, x - 4, 198, 9, 8, iron); rect(c, x - 3, 190, 7, 8, iron); rect(c, x - 3, 190, 1, 16, ironL); rect(c, x - 5, 206, 11, 2, PX.K)
        rect(c, x - 2, 150, 5, 2, ironL); rect(c, x - 2, 168, 5, 2, ironL)
        // the lantern: cage, warm glass, cap and finial
        rect(c, x - 5, 128, 11, 2, iron); rect(c, x - 4, 118, 9, 11, iron)
        rect(c, x - 3, 120, 7, 8, PX.WARM); rect(c, x - 2, 121, 5, 6, 0xFFFFF0C0.toInt()); rect(c, x, 120, 1, 8, iron)
        rect(c, x - 6, 116, 13, 3, iron); rect(c, x - 6, 116, 13, 1, ironL); rect(c, x - 2, 113, 5, 3, iron); px(c, x, 111, PX.GOLD); px(c, x, 112, PX.GOLD)
        // scrolled arm
        rect(c, x - 6, 130, 2, 2, iron); rect(c, x + 5, 130, 2, 2, iron)
    }

    private fun buildHallBg(): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val stoneL = 0xFFB4BED6.toInt(); val stone = 0xFF8A94AC.toInt(); val stoneM = 0xFF6A7490.toInt(); val stoneD = 0xFF5A6482.toInt()
        val wallD = 0xFF2A3050.toInt(); val wallDD = 0xFF1F2440.toInt()
        // ---- night sky, banded with dithered joins, stars and a moon
        val sky = intArrayOf(0xFF050A1E.toInt(), 0xFF081230.toInt(), 0xFF0C1A42.toInt(), 0xFF122456.toInt(), 0xFF1A3068.toInt(), 0xFF223A78.toInt())
        for (i in sky.indices) {
            rect(c, 0, i * 20, W, 20, sky[i])
            if (i > 0) for (x in 0 until W) { val oy = if ((x + (x / 2)) % 2 == 0) 0 else 1; px(c, x, i * 20 - 1 + oy, sky[i - 1]); px(c, x, i * 20 + 2 + oy, sky[i]) }
        }
        for (i in 0 until 46) {
            val sx = hash(i, 1, 7) % W; val sy = hash(i, 2, 7) % 62
            val br = hash(i, 3, 7) % 3
            px(c, sx, sy, if (br == 0) PX.W else if (br == 1) PX.ICE else 0xFF6A86C8.toInt())
            if (br == 0) { px(c, sx - 1, sy, a(PX.ICE, 0x60)); px(c, sx + 1, sy, a(PX.ICE, 0x60)); px(c, sx, sy - 1, a(PX.ICE, 0x60)); px(c, sx, sy + 1, a(PX.ICE, 0x60)) }
        }
        c.drawBitmap(glowBitmap(26, PX.ICE, 0x30), 22f - 26f, 20f - 26f, plain)
        for (dy in -8..8) for (dx in -8..8) if (dx * dx + dy * dy <= 64) {
            val inShadow = (dx + 4) * (dx + 4) + (dy - 2) * (dy - 2) <= 52
            if (!inShadow) px(c, 22 + dx, 20 + dy, if (dx + dy < -2) PX.W else 0xFFDCE6FF.toInt())
        }
        px(c, 17, 19, 0xFFB0BCDC.toInt()); px(c, 18, 23, 0xFFB0BCDC.toInt()); px(c, 19, 14, 0xFFB0BCDC.toInt())
        // thin clouds drifting past the moon
        for (k in 0..11) rect(c, 6 + k * 3 + (k % 3), 29 + (k % 2), 5, 1, a(0xFF3A4C88.toInt(), 0x80))
        for (k in 0..14) rect(c, 168 + k * 4, 44 + (k % 3), 6, 1, a(0xFF3A4C88.toInt(), 0x70))
        // far city skyline behind the building
        var sx = 0
        while (sx < W) {
            val bw = 9 + hash(sx, 4, 9) % 10; val bh = 10 + hash(sx, 5, 9) % 24
            rect(c, sx, 62 - bh, bw, bh + 6, 0xFF0A1330.toInt()); rect(c, sx, 62 - bh, bw, 1, 0xFF16224A.toInt())
            for (wy in 0 until bh / 5) for (wx in 0 until bw / 4) if (hash(sx + wx, wy, 11) % 5 == 0) px(c, sx + 1 + wx * 4, 62 - bh + 3 + wy * 5, 0xFFC8A050.toInt())
            sx += bw + 1
        }
        // ---- the wings: brick, tall windows, a cornice and chimneys
        val brick = intArrayOf(0xFF3A4260.toInt(), 0xFF424B6C.toInt(), 0xFF353D5A.toInt())
        bricks(c, 0, 46, W, 54, brick, 0xFF262C46.toInt(), 3)
        for (wing in intArrayOf(0, 224)) {
            for ((i, wx) in intArrayOf(wing + 3, wing + 17).withIndex()) {
                for ((j, wy) in intArrayOf(54, 78).withIndex()) {
                    val lit = hash(wing + i, j, 5) % 3 != 0
                    archWindow(c, wx, wy, 9, 16, if (lit) 0xFFFFC866.toInt() else 0xFF14204E.toInt(), if (lit) 0xFFFFE6A0.toInt() else 0xFF0B1230.toInt())
                }
            }
        }
        rect(c, 0, 38, 32, 3, 0xFF1A2038.toInt()); rect(c, 224, 38, 32, 3, 0xFF1A2038.toInt())            // roof slab
        rect(c, 0, 41, 32, 5, stone); rect(c, 0, 41, 32, 1, stoneL); rect(c, 0, 45, 32, 1, stoneD)
        rect(c, 224, 41, 32, 5, stone); rect(c, 224, 41, 32, 1, stoneL); rect(c, 224, 45, 32, 1, stoneD)
        for (k in 0..7) { rect(c, k * 4 + 1, 46, 2, 2, stoneM); rect(c, 224 + k * 4 + 1, 46, 2, 2, stoneM) }   // dentils
        for (cx in intArrayOf(8, 240)) { bricks(c, cx - 4, 22, 8, 17, intArrayOf(0xFF4A3A44.toInt(), 0xFF3A2C36.toInt()), 0xFF241A22.toInt(), 6); rect(c, cx - 5, 20, 10, 3, stoneM); rect(c, cx - 5, 20, 10, 1, stoneL) }
        // ---- the portico: recessed wall behind the pillars
        bricks(c, 30, 48, 196, 52, intArrayOf(0xFF2A3050.toInt(), 0xFF2F3658.toInt(), 0xFF262C48.toInt()), 0xFF1B2038.toInt(), 8)
        rect(c, 30, 48, 196, 6, a(PX.K, 0x60))                                                             // shade under the roof
        // tall windows between the pillars, a lantern on each side of the door
        archWindow(c, 66, 58, 10, 30, 0xFFFFC866.toInt(), 0xFFFFE6A0.toInt())
        archWindow(c, 180, 58, 10, 30, 0xFFFFC866.toInt(), 0xFFFFE6A0.toInt())
        for (lx in intArrayOf(103, 152)) {
            rect(c, lx, 56, 1, 4, 0xFF1A2030.toInt()); rect(c, lx - 2, 60, 5, 7, 0xFF1A2030.toInt()); rect(c, lx - 1, 61, 3, 5, PX.WARM); px(c, lx, 62, PX.W)
            c.drawBitmap(glowBitmap(16, PX.WARM, 0x44), (lx - 16).toFloat(), (63 - 16).toFloat(), plain)
        }
        // ---- the door: stone surround, fan-light, two panelled red leaves with gold handles
        rect(c, 108, 46, 40, 56, stone); rect(c, 108, 46, 1, 56, stoneL); rect(c, 147, 46, 1, 56, stoneD); rect(c, 108, 46, 40, 1, stoneL)
        rect(c, 111, 49, 34, 51, PX.K)
        rect(c, 112, 50, 32, 9, 0xFF3A2A18.toInt())                                                         // fan-light
        for (k in 0..7) { rect(c, 112 + k * 4, 50, 3, 8, if (k % 2 == 0) 0xFFFFD27A.toInt() else 0xFFE8A850.toInt()) }
        rect(c, 112, 58, 32, 1, PX.K); px(c, 112, 50, PX.K); px(c, 143, 50, PX.K)
        for (leaf in 0..1) {
            val lx = 112 + leaf * 16
            rect(c, lx, 59, 16, 41, 0xFF6A1020.toInt()); rect(c, lx, 59, 16, 1, 0xFF9A1C34.toInt())
            rect(c, lx + 2, 62, 12, 14, 0xFF8A1830.toInt()); rect(c, lx + 3, 63, 10, 12, 0xFF5A0C1A.toInt())
            rect(c, lx + 2, 80, 12, 16, 0xFF8A1830.toInt()); rect(c, lx + 3, 81, 10, 14, 0xFF5A0C1A.toInt())
            rect(c, lx + 2, 62, 12, 1, 0xFFB02A48.toInt()); rect(c, lx + 2, 80, 12, 1, 0xFFB02A48.toInt())
        }
        rect(c, 127, 59, 2, 41, PX.K)
        rect(c, 124, 76, 3, 5, PX.GOLD); rect(c, 129, 76, 3, 5, PX.GOLD); px(c, 124, 76, PX.W); px(c, 129, 76, PX.W)
        rect(c, 110, 98, 36, 4, stoneD); rect(c, 110, 98, 36, 1, stone)
        // ---- the four pillars
        for (col in intArrayOf(48, 80, 160, 192)) column(c, col, 48, 100)
        // ---- the architrave, frieze and plaque
        rect(c, 28, 38, 200, 10, stone); rect(c, 28, 38, 200, 1, PX.W); rect(c, 28, 39, 200, 1, stoneL); rect(c, 28, 47, 200, 1, PX.K)
        rect(c, 28, 42, 200, 1, stoneD)
        for (k in 0..48) rect(c, 29 + k * 4, 44, 2, 3, stoneM)                                              // dentils
        rect(c, 98, 39, 60, 8, PX.K); rect(c, 99, 40, 58, 6, 0xFF1A2038.toInt())
        p.typeface = android.graphics.Typeface.MONOSPACE; p.isFakeBoldText = true; p.textSize = 7f; p.textAlign = android.graphics.Paint.Align.CENTER; p.color = PX.GOLD
        c.drawText("TOWN HALL", 128f, 45f, p)
        p.isFakeBoldText = false; p.textAlign = android.graphics.Paint.Align.LEFT; p.typeface = null
        // ---- the pediment with a clock in the middle
        for (y in 4..37) {
            val half = (y - 4) * 100 / 34
            rect(c, 128 - half, y, half * 2, 1, if (y < 6) PX.W else stone)
            rect(c, 128 - half, y, 2, 1, stoneL); rect(c, 128 + half - 2, y, 2, 1, stoneM)
        }
        for (y in 11..34) {
            val half = (y - 11) * 90 / 23
            if (half > 1) rect(c, 128 - half + 1, y, half * 2 - 2, 1, if (y % 2 == 0) wallD else wallDD)
        }
        for (k in 0..6) { px(c, 70 + k * 2, 33, stoneM); px(c, 177 - k * 2 + 4, 33, stoneM) }
        for (k in 0..9) { px(c, 108 + k * 4, 34, 0xFF3A4C88.toInt()) }
        // laurel stars either side of the clock
        for (sxx in intArrayOf(96, 104, 152, 160)) { px(c, sxx, 28, PX.GOLD); px(c, sxx - 1, 28, a(PX.GOLD, 0x80)); px(c, sxx + 1, 28, a(PX.GOLD, 0x80)); px(c, sxx, 27, a(PX.GOLD, 0x80)); px(c, sxx, 29, a(PX.GOLD, 0x80)) }
        // the clock (it says two minutes to midnight)
        for (dy in -8..8) for (dx in -8..8) {
            val d2 = dx * dx + dy * dy
            if (d2 <= 64) px(c, 128 + dx, 26 + dy, if (d2 > 49) PX.GOLD else if (d2 > 42) PX.K else 0xFFEDE6CC.toInt())
        }
        for (k in 0..11) { val ang = k * Math.PI / 6; px(c, 128 + Math.round(Math.sin(ang) * 5.2).toInt(), 26 - Math.round(Math.cos(ang) * 5.2).toInt(), PX.K) }
        rect(c, 128, 21, 1, 5, PX.K); rect(c, 127, 23, 1, 3, PX.RED); px(c, 128, 26, PX.K)
        // ---- the steps (with a red carpet and gold edging down the middle)
        for (i in 0..3) {
            val sx0 = (3 - i) * S; val sw = (10 + i * 2) * S; val sy = 96 + i * 6
            rect(c, sx0, sy, sw, 6, stoneM)
            rect(c, sx0, sy, sw, 2, stoneL); rect(c, sx0, sy + 2, sw, 1, stone)
            rect(c, sx0, sy + 5, sw, 1, stoneD)
            for (k in 0 until sw step 3) if (hash(k, i, 13) % 3 == 0) px(c, sx0 + k, sy + 3, stoneD)
            rect(c, sx0, sy, 1, 6, PX.W); rect(c, sx0 + sw - 1, sy, 1, 6, stoneD)
        }
        rect(c, 112, 102, 32, 20, 0xFF8A1830.toInt())
        for (r in 0..3) rect(c, 112, 102 + r * 6 + 4, 32, 2, 0xFF5A0C1A.toInt())
        rect(c, 112, 102, 1, 20, PX.GOLD); rect(c, 143, 102, 1, 20, PX.GOLD); rect(c, 114, 102, 1, 20, 0xFFB02A48.toInt()); rect(c, 141, 102, 1, 20, 0xFFB02A48.toInt())
        c.drawBitmap(glowBitmap(44, PX.WARM, 0x30), 128f - 44f, 100f - 44f, plain)                          // light spilling out of the door
        // urns on plinths either side
        for (ux in intArrayOf(33, 213)) {
            rect(c, ux, 90, 11, 12, stoneM); rect(c, ux, 90, 11, 2, stoneL); rect(c, ux - 1, 100, 13, 2, stoneD)
            rect(c, ux + 1, 78, 9, 12, 0xFF2F6F73.toInt()); rect(c, ux + 1, 78, 3, 12, 0xFF63C7C2.toInt()); rect(c, ux + 7, 78, 3, 12, 0xFF1A4A4E.toInt()); rect(c, ux, 76, 11, 3, 0xFF2F6F73.toInt())
            for (k in 0..4) { px(c, ux + 2 + k * 2, 74 - (k % 2), 0xFF2E8F4E.toInt()); px(c, ux + 3 + k * 2, 73 - (k % 3), 0xFF4EC878.toInt()) }
        }
        // ---- the plaza: paving, a kerb, shrubs, a bench, a bin and bollards
        for (y in 122 until 154) for (x in 0 until W) {
            val row = (y - 122) / 4; val off = if (row % 2 == 0) 0 else 6
            val gx = x + off
            val mortar = (y - 122) % 4 == 3 || gx % 12 == 11
            val tone = hash(gx / 12, row, 17) % 3
            px(c, x, y, if (mortar) 0xFF121830.toInt() else if (tone == 0) 0xFF252E4C.toInt() else if (tone == 1) 0xFF2B3556.toInt() else 0xFF222A46.toInt())
        }
        rect(c, 0, 154, W, 5, 0xFF4A5470.toInt()); rect(c, 0, 154, W, 1, 0xFF8A94AC.toInt()); rect(c, 0, 158, W, 1, PX.K)
        for (k in 0 until W step 8) px(c, k, 156, 0xFF3A4260.toInt())
        for (bx in intArrayOf(6, 232)) {        // shrubs
            for (dy in -8..8) for (dx in -11..11) {
                if (dx * dx * 64 + dy * dy * 121 > 64 * 121) continue
                val sh = hash(bx + dx, dy, 19) % 5
                px(c, bx + 8 + dx, 132 + dy, if (dy < -3 && sh < 2) 0xFF3E9A58.toInt() else if (dy > 3) 0xFF0F3A22.toInt() else if (sh == 0) 0xFF2E8F4E.toInt() else 0xFF1E6A38.toInt())
            }
            rect(c, bx - 2, 139, 21, 3, a(PX.K, 0x70))
        }
        // bench
        rect(c, 176, 134, 30, 3, 0xFF7A5A32.toInt()); rect(c, 176, 134, 30, 1, 0xFFA8804A.toInt()); rect(c, 176, 138, 30, 3, 0xFF5A4226.toInt())
        rect(c, 178, 141, 3, 6, 0xFF1A2030.toInt()); rect(c, 201, 141, 3, 6, 0xFF1A2030.toInt()); rect(c, 176, 130, 30, 3, 0xFF7A5A32.toInt()); rect(c, 176, 130, 30, 1, 0xFFA8804A.toInt())
        rect(c, 177, 130, 2, 10, 0xFF1A2030.toInt()); rect(c, 203, 130, 2, 10, 0xFF1A2030.toInt()); rect(c, 174, 147, 34, 2, a(PX.K, 0x60))
        // bin
        rect(c, 56, 132, 10, 13, PX.K); rect(c, 57, 133, 8, 11, 0xFF2F3D58.toInt()); rect(c, 57, 133, 2, 11, 0xFF5A6E94.toInt()); rect(c, 55, 130, 12, 3, 0xFF1A2030.toInt()); rect(c, 55, 130, 12, 1, 0xFF5A6E94.toInt())
        for (k in 0..2) rect(c, 59, 136 + k * 3, 4, 1, PX.K)
        rect(c, 54, 145, 14, 2, a(PX.K, 0x60))
        // bollards along the kerb
        for (k in 0..5) { val bx = 28 + k * 40; if (bx > 212 || bx < 28) continue; rect(c, bx, 146, 4, 8, 0xFF1A2030.toInt()); rect(c, bx, 146, 4, 2, 0xFFE8E0C8.toInt()); px(c, bx, 148, PX.RED) }
        // ---- the road: dark asphalt, speckle, a zebra crossing, the centre line, a manhole
        for (y in 159 until H) for (x in 0 until W) {
            val n = hash(x, y, 23) % 60
            px(c, x, y, if (n == 0) 0xFF2A3452.toInt() else if (n == 1) 0xFF0F1426.toInt() else if (n < 6) 0xFF181F36.toInt() else 0xFF151B30.toInt())
        }
        rect(c, 0, 159, W, 1, PX.K)
        for (k in 0..11) { rect(c, 22 + k * 18, 170, 9, 20, 0xFF9AA4BC.toInt()); rect(c, 22 + k * 18, 170, 9, 2, PX.W) }
        for (i in 0 until 8) rect(c, i * 2 * S + 6, 17 * S, 18, 3, 0xFFB8B070.toInt())
        for (dy in -6..6) for (dx in -6..6) if (dx * dx + dy * dy <= 36) px(c, 70 + dx, 222 + dy, if (dx * dx + dy * dy > 28) PX.K else if ((dx + dy) % 3 == 0) 0xFF1B2238.toInt() else 0xFF0E1426.toInt())
        // lamp light lying on the wet road
        for (lx in lampX) {
            for (k in 0..14) { val w2 = 4 + (k / 3); rect(c, lx - w2 / 2 + (if (k % 2 == 0) 0 else 1), 162 + k * 7, w2, 2, a(PX.WARM, 0x26 - k * 2)) }
            c.drawBitmap(glowBitmap(34, PX.WARM, 0x22), (lx - 34).toFloat(), (150 - 34).toFloat(), plain)
        }
        for (lx in lampX) lamp(c, lx, glowBitmap(30, PX.WARM, 0x44))
        // flag poles on the roof, with gold balls
        for (fx in intArrayOf(40, 216)) { rect(c, fx, 8, 2, 30, 0xFF8A94AC.toInt()); rect(c, fx, 8, 1, 30, PX.W); rect(c, fx - 1, 5, 4, 4, PX.GOLD); px(c, fx, 5, PX.W) }
        // ---- soft dark corners, so your eye goes to the door
        for (i in 0..13) { val al = (0x58 - i * 6).coerceAtLeast(0); rect(c, 0, H - 1 - i * 2, W, 2, a(PX.K, al)); rect(c, 0, i * 2, W, 2, a(PX.K, (al * 3) / 5)) }
        for (i in 0..9) { val al = (0x48 - i * 7).coerceAtLeast(0); rect(c, i * 2, 0, 2, H, a(PX.K, al)); rect(c, W - 2 - i * 2, 0, 2, H, a(PX.K, al)) }
        return bm
    }

    /** What moves in front of the town hall: the two flags and the smoke from the chimneys. */
    private fun hallLive(c: Canvas) {
        for ((n, fx) in intArrayOf(40, 216).withIndex()) {
            val dir = if (n == 0) 1 else -1
            for (k in 0..14) {
                val wave = (sin(animT * 4f + k * 0.55f + n) * (1f + k * 0.12f)).toInt()
                val x = if (dir > 0) fx + 2 + k else fx - 1 - k
                val y = 9 + wave
                rect(c, x, y, 1, 9, if (n == 0) 0xFFB02038.toInt() else 0xFF2B4FA8.toInt())
                rect(c, x, y + 3, 1, 3, if (n == 0) PX.GOLD else PX.W)
                if (k > 10) rect(c, x, y + 8, 1, 1, a(PX.K, 0x50))
            }
        }
        for (cx in intArrayOf(8, 240)) for (k in 0..2) {
            val ph = (animT * 0.4f + k / 3f + cx * 0.01f) % 1f
            val sz = 3 + (ph * 4).toInt()
            rect(c, cx - 1 + (sin(animT * 1.3f + k + cx) * 3 * ph).toInt() + (ph * 6).toInt(), 18 - (ph * 22).toInt(), sz, sz, a(0xFF9AA4B5.toInt(), ((1f - ph) * 0x70).toInt()))
        }
    }

    // ---------- kind 1 ----------
    private fun hall(c: Canvas) {
        val bg = hallBg ?: buildHallBg().also { hallBg = it }
        c.drawBitmap(bg, 0f, 0f, plain)
        hallLive(c)
        // ---------- the story: the cruiser pulls up, the cop talks, two SWAT officers cover you, then let you in ----------
        val arrive = seg(0.2f, 2.6f)
        val carX = 72
        val cy = (ease(28f, 15f, 1f - (1f - arrive) * (1f - arrive)) * S).roundToInt()
        car(c, carX, cy, t in 2.6f..13f)
        // both get out at 3.0 s, on the car's right side, and walk to the middle
        val toMid = seg(3.0f, 4.4f)
        val walk = seg(14.4f, 17.0f)
        val copX = ease(5.9f, 8.0f, toMid); val copY0 = ease(15.6f, 14.2f, toMid)
        val copY = ease(copY0, 6.3f, walk)
        val cx0 = (copX * S).roundToInt(); val cy0 = (copY * S).roundToInt() + 6
        val plX = (copX * S).roundToInt(); val plY = ((copY + 1.5f) * S).roundToInt() + 6
        val moving = walk in 0.01f..0.99f || toMid in 0.01f..0.99f
        val stepF = if (moving) 1 + ((animT * 6f).toInt() % 2) else 0
        val aimAt = cx0 to (cy0 - 10)

        // the two SWAT officers by the door: lowered until you get out, then rifles up and tracking you
        val raise = seg(3.2f, 3.8f)
        val look = seg(11.8f, 12.1f) * (1f - seg(12.8f, 13.0f))
        val enter = seg(13.6f, 15.4f)
        for (side in 0..1) {
            val homeX = if (side == 0) 92 else 164
            val inX = if (side == 0) 121 else 135
            val sx = ease(homeX.toFloat(), inX.toFloat(), enter).roundToInt()
            val sy = (ease(108f, 82f, enter)).roundToInt()
            val dir = if (look > 0.5f) (if (side == 0) 1 else 3) else 2
            val sf = if (enter in 0.01f..0.99f) 1 + ((animT * 6f).toInt() % 2) else 0
            c.save()
            if (sy < 100) c.clipRect(112f, 56f, 144f, 101f)       // inside the doorway they are only seen through it
            swatman(c, sx, sy, dir, sf)
            val shx = sx + (if (side == 0) 3 else -3); val shy = sy - 10
            val toward = Math.atan2((aimAt.second - shy).toDouble(), (aimAt.first - shx).toDouble()).toFloat()
            val ang = if (raise < 1f) 1.25f + (toward - 1.25f) * raise else toward
            rifle(c, shx, shy, ang, raise >= 1f && enter < 0.6f, aimAt)
            c.restore()
        }
        // the open door: warm light and the lobby beyond, drawn over the shut doors
        val open = seg(12.8f, 13.6f)
        if (open > 0f) {
            val w2 = (30 * open).toInt()
            rect(c, 128 - w2 / 2, 59, w2, 41, 0xFF241626.toInt())
            rect(c, 128 - w2 / 2, 59, w2, 6, 0xFF3A2A44.toInt())
            for (k in 0..5) rect(c, 128 - w2 / 2, 66 + k * 6, w2, 1, a(PX.K, 0x40))
            if (open > 0.6f) { rect(c, 118, 70, 3, 30, 0xFF4A5470.toInt()); rect(c, 135, 70, 3, 30, 0xFF4A5470.toInt()); rect(c, 126, 62, 4, 4, PX.WARM); c.drawBitmap(hallGlow, 128f - 30f, 100f - 30f, plain) }
            rect(c, 112, 59, 3, 41, 0xFF6A1020.toInt()); rect(c, 141, 59, 3, 41, 0xFF6A1020.toInt())        // the leaves swung back
        }
        if (enter > 0f) rect(c, 112, 59, 32, 41, a(PX.K, (seg(14.2f, 15.6f) * 0xC0).toInt()))

        // the cop and you
        if (t >= 3.0f) {
            val cdir = if (t < 11.6f) 2 else 0
            rect(c, cx0 - 5, cy0 - 1, 11, 3, a(PX.K, 0x70))
            folk(c, cx0, cy0, cdir, stepF, police, false, false, handsUp = true)                                                   // hands up from the moment he's out
            if (t >= 3.4f) {
                rect(c, plX - 5, plY - 1, 11, 3, a(PX.K, 0x70))
                folk(c, plX, plY, 0, stepF, clerk, false, false)
                rect(c, plX - 2, plY - 16, 4, 3, PX.K); rect(c, plX - 1, plY - 19, 2, 4, 0xFF9AA4B5.toInt())                          // the gun, on the cop's back
            }
            val line = when {
                t in 4.0f..6.4f -> "HOLD YOUR FIRE! HE'S GOT AN OFFICER!" to (t - 4.0f)
                t in 9.8f..11.4f -> "Easy... nobody move." to (t - 9.8f)
                else -> null
            }
            if (line != null) { val (bx, by) = if (t < 9f) 92 to 84 else cx0 to cy0 - 24; bubble(c, line.first, bx, by, (line.second * 24f).toInt(), W) }
        }
    }

    private val hallGlow: Bitmap by lazy { glowBitmap(30, PX.WARM, 0x40) }
}
