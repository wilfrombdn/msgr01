package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The jetpack run in pixel art: a side view of a big old factory hall with parallax (night windows and chimneys far back; boilers,
 * tanks, ducts, a gantry crane and racks in the middle; girders, hanging lamps with light beams, chains and the floor in front),
 * spinning coins, zappers, saw blades, laser fences, rockets, and the courier with a jetpack on his back.
 */
internal class FlyScene(private val world: FlyWorld) : PixelSprites() {
    private val S = 16
    val W = 14 * S
    val H = 20 * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    // the picture handed to the screen: the hall in the middle, more roof above it and more floor below on tall screens
    private var outH = H; private var topPad = 0
    private var out = frame; private var oc = fc
    /** Make the picture this tall (art pixels) so it fills the screen. */
    fun resize(hpx: Int) { val h = maxOf(H, hpx); if (h == outH) return; outH = h; topPad = (h - H) * 3 / 5; out = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888); oc = Canvas(out) }

    fun render(clock: Float): Bitmap {
        val inner = renderHall(clock)
        if (outH == H) return inner
        val c = oc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val sc = world.scroll
        // the roof: dark trusses, skylights onto the night, cable trays, all sliding with the hall
        rect(c, 0, 0, W, topPad, 0xFF04070C.toInt())
        val roofOff = (sc * S * 0.35f).toInt()
        for (i in -1..(W / 80 + 2)) { val x = i * 80 - mod(roofOff, 80)
            for (k in 0 until topPad step 2) { val tx = x + k / 2; px(c, tx, topPad - 1 - k, 0xFF1A2230.toInt()); px(c, x + 80 - k / 2, topPad - 1 - k, 0xFF1A2230.toInt()) }
            rect(c, x + 28, topPad / 3, 24, 10, 0xFF0B2250.toInt()); rect(c, x + 28, topPad / 3, 24, 3, 0xFF1B4A8C.toInt()); px(c, x + 31 + hash(i + roofOff / 80, 1, 2) % 18, topPad / 3 + 5, PX.W) }
        for (y in topPad - 12 until topPad step 6) rect(c, 0, y, W, 1, 0xFF141A24.toInt())
        rect(c, 0, topPad - 3, W, 3, 0xFF222936.toInt())
        // the hall itself
        c.drawBitmap(inner, 0f, topPad.toFloat(), plain)
        // under the floor: the slab, pipes and grates
        val yb = topPad + H
        rect(c, 0, yb, W, outH - yb, 0xFF0B0F14.toInt())
        val nearOff = (sc * S).toInt()
        for (i in -1..(W / 48 + 2)) { val x = i * 48 - mod(nearOff, 48); rect(c, x, yb + 6, 40, 3, 0xFF1A2230.toInt()); for (g in 0 until 40 step 4) rect(c, x + g, yb + 12, 2, 6, 0xFF10161C.toInt()) }
        for (y in yb + 24 until outH step 18) { rect(c, 0, y, W, 4, 0xFF161C24.toInt()); rect(c, 0, y, W, 1, 0xFF2A3544.toInt()) }
        return out
    }

    private val steel = 0xFF6A7890.toInt(); private val steelD = 0xFF3A4458.toInt(); private val steelL = 0xFFA9B4C8.toInt(); private val steelDD = 0xFF222936.toInt()
    private val brick = 0xFF0C1C26.toInt(); private val hazardY = 0xFFFFC82E.toInt(); private val hazardK = 0xFF1B1D22.toInt()
    private val cyan = 0xFF3DE8F5.toInt()
    private val lampBeam = glowBitmap(30, 0xFFFFD28A.toInt(), 46)
    private val redGlow = glowBitmap(16, 0xFFFF3B3B.toInt(), 80)
    private val cyanGlow = glowBitmap(14, 0xFF3DE8F5.toInt(), 80)
    private val coinGlow = glowBitmap(10, 0xFFFFD84D.toInt(), 0x38)
    private val flameGlow = glowBitmap(20, 0xFFFF9A3A.toInt(), 0x70)
    private val moonShaft: Bitmap by lazy {
        // a slanting beam of moonlight from a tall window, dithered
        val w = 70; val h = 200; val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val pxs = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val cx = 8 + y * 0.28f; val half = 14f + y * 0.05f; val ac = abs(x - cx); if (ac > half) continue
            val v = (1f - y / h.toFloat()) * (1f - ac / half); val q = kotlin.math.floor(v * 4f + bayer[(y and 3) * 4 + (x and 3)] / 16f) / 4f; if (q > 0f) pxs[y * w + x] = a(0xFFA8C0F0.toInt(), (q * 0x34).toInt()) }
        bm.setPixels(pxs, 0, w, 0, 0, w, h); bm
    }
    private val vignette: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val pxs = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) { val d = (kotlin.math.hypot((x - W / 2f) / (W / 2f), (y - H / 2f) / (H / 2f)) - 0.78f) / 0.55f; if (d <= 0f) continue
            val q = kotlin.math.floor(d.coerceAtMost(1f) * 4f + bayer[(y and 3) * 4 + (x and 3)] / 16f) / 4f; if (q > 0f) pxs[y * W + x] = a(PX.K, (q * 0xA0).toInt()) }
        bm.setPixels(pxs, 0, W, 0, 0, W, H); bm
    }
    // where the jetpack has been, for the smoke trail
    private val trail = ArrayList<FloatArray>()

    private fun mod(a: Int, b: Int) = ((a % b) + b) % b

    private fun renderHall(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val sc = world.scroll
        rect(c, 0, 0, W, H, 0xFF07101A.toInt())

        // ---- far: brick wall, tall windows onto a night sky with chimneys, a long pipe ----
        val farOff = (sc * S * 0.22f).toInt()
        for (y in 0 until H step 8) { rect(c, 0, y + 7, W, 1, brick); if ((y / 8) % 2 == 0) for (x in 0 until W step 24) rect(c, mod(x + (y / 8) * 7 - farOff / 3, W), y + 3, 1, 4, 0xFF0A1820.toInt()) }
        for (i in -1..(W / 64 + 2)) {
            val wx = i * 64 - mod(farOff, 64)
            val idx = (farOff + i * 64) / 64
            rect(c, wx, 48, 34, 52, PX.K); rect(c, wx + 2, 50, 30, 48, 0xFF0B2250.toInt()); rect(c, wx + 2, 50, 30, 14, 0xFF1B4A8C.toInt())
            // the sky: stars, a moon now and then, chimney stacks with a glow
            for (s in 0..3) px(c, wx + 4 + hash(idx, s, 1) % 26, 52 + hash(idx, s, 2) % 24, if (((clock * 2f).toInt() + s + idx) % 5 == 0) 0xFF8AA6D6.toInt() else PX.W)
            if (hash(idx, 5, 3) % 3 == 0) { rect(c, wx + 22, 54, 7, 7, 0xFFEAF0FF.toInt()); rect(c, wx + 24, 54, 5, 7, 0xFF0B2250.toInt()) }
            rect(c, wx + 6, 78, 5, 20, 0xFF0A1226.toInt()); rect(c, wx + 20, 70, 6, 28, 0xFF0A1226.toInt()); rect(c, wx + 21, 70, 4, 2, 0xFFC03030.toInt())
            val sm = (clock * 0.5f + idx * 0.3f) % 1f; rect(c, wx + 7 - (sm * 4).toInt(), 76 - (sm * 20).toInt(), 4, 4, a(0xFF3A4660.toInt(), ((1f - sm) * 0x90).toInt()))
            // frame bars, sill, and a faint reflection
            rect(c, wx + 16, 50, 2, 48, PX.K); rect(c, wx + 2, 72, 30, 2, PX.K); rect(c, wx - 2, 100, 38, 3, steelD); rect(c, wx - 2, 100, 38, 1, steel)
            rect(c, wx + 4, 54, 3, 36, a(PX.W, 0x10))
        }
        rect(c, 0, 24, W, 8, PX.K); rect(c, 0, 25, W, 6, 0xFF2F6F73.toInt()); rect(c, 0, 25, W, 2, 0xFF8AF0E8.toInt()); rect(c, 0, 30, W, 1, 0xFF1A4A50.toInt())
        for (i in -1..(W / 44 + 2)) { val x = i * 44 - mod(farOff, 44); rect(c, x + 14, 23, 4, 10, PX.K); rect(c, x + 15, 24, 2, 8, 0xFF9AA4B5.toInt()) }
        // moonlight slanting down from the windows across the hall
        for (i in -1..(W / 64 + 2)) { val wx = i * 64 - mod(farOff, 64); c.drawBitmap(moonShaft, wx.toFloat(), 98f, plain) }

        // ---- middle: boilers and tanks with pipes, racks, a gantry crane ----
        val midOff = (sc * S * 0.5f).toInt()
        for (i in -1..(W / 96 + 3)) {
            val idx = (midOff + i * 96) / 96
            val wx = i * 96 - mod(midOff, 96)
            val rk = hash(idx, 3, 5) % 4
            val base = H - 28
            when (rk) {
                0 -> { // a big boiler on legs with a gauge and a pipe up
                    rect(c, wx + 8, base - 70, 48, 62, PX.K); rect(c, wx + 9, base - 69, 46, 60, 0xFF2A3A4C.toInt()); rect(c, wx + 9, base - 69, 46, 6, steelL); rect(c, wx + 9, base - 63, 8, 54, steel)
                    for (b in 0..3) rect(c, wx + 9, base - 56 + b * 14, 46, 2, steelDD)
                    rect(c, wx + 26, base - 52, 14, 14, PX.K); rect(c, wx + 28, base - 50, 10, 10, 0xFFE8EEF8.toInt()); px(c, wx + 33, base - 45, PX.RED); rect(c, wx + 33 - (hash(idx, 1, 2) % 3), base - 46, 3, 1, PX.RED)
                    rect(c, wx + 14, base - 8, 5, 8, steelD); rect(c, wx + 45, base - 8, 5, 8, steelD)
                    rect(c, wx + 28, base - 100, 8, 32, PX.K); rect(c, wx + 29, base - 100, 6, 32, steel); rect(c, wx + 24, base - 104, 16, 5, steelD)
                    val st = (clock * 0.8f + idx * 0.37f) % 1f; rect(c, wx + 29 + (sin(clock * 2f + idx) * 3).toInt(), base - 108 - (st * 24).toInt(), 6, 5, a(0xFFC8D0E0.toInt(), ((1f - st) * 0x90).toInt()))
                }
                1 -> { // racks of boxes
                    rect(c, wx, base - 112, 3, 112, steelD); rect(c, wx + 56, base - 112, 3, 112, steelD)
                    for (sh in 0..2) { val yy = base - 100 + sh * 36; rect(c, wx, yy, 59, 3, steel); rect(c, wx, yy, 59, 1, steelL)
                        for (b in 0..2) if (hash(idx, sh * 3 + b, 9) % 5 != 0) { val bx = wx + 4 + b * 18; rect(c, bx, yy - 15, 15, 15, PX.K); rect(c, bx + 1, yy - 14, 13, 13, 0xFF7A6038.toInt()); rect(c, bx + 1, yy - 14, 13, 2, 0xFFA8884A.toInt()); rect(c, bx + 6, yy - 14, 3, 13, 0xFF5A4626.toInt()) } }
                }
                2 -> { // a round tank with a ladder and pipes
                    rect(c, wx + 6, base - 84, 52, 76, PX.K); rect(c, wx + 8, base - 82, 48, 72, 0xFF3A5A5A.toInt()); rect(c, wx + 8, base - 82, 10, 72, 0xFF5A8A8A.toInt()); rect(c, wx + 48, base - 82, 8, 72, 0xFF244040.toInt())
                    for (b in 0..2) rect(c, wx + 8, base - 70 + b * 22, 48, 2, 0xFF1A3030.toInt())
                    rect(c, wx + 60, base - 78, 3, 70, steel); for (r in 0..8) rect(c, wx + 60, base - 76 + r * 8, 8, 1, steelL)
                    rect(c, wx + 20, base - 92, 24, 8, steelD); rect(c, wx + 22, base - 96, 20, 5, steel)
                }
                else -> { // machine hall: a press with a warning lamp and cable drums
                    rect(c, wx + 4, base - 62, 56, 54, PX.K); rect(c, wx + 6, base - 60, 52, 50, 0xFF4A3A2A.toInt()); rect(c, wx + 6, base - 60, 52, 6, 0xFF7A6040.toInt())
                    rect(c, wx + 14, base - 48, 36, 22, PX.K); rect(c, wx + 16, base - 46, 32, 18, 0xFF121820.toInt())
                    rect(c, wx + 20, base - 70, 24, 10, steelD); rect(c, wx + 28, base - 78, 8, 10, steel)
                    rect(c, wx + 8, base - 24, 48, 3, hazardY); for (s in 0 until 48 step 8) rect(c, wx + 8 + s, base - 24, 4, 3, hazardK)
                    val on = ((clock * 2f).toInt() + idx) % 2 == 0
                    rect(c, wx + 50, base - 68, 8, 8, PX.K); rect(c, wx + 51, base - 67, 6, 6, if (on) PX.RED else 0xFF5A1020.toInt())
                    if (on) c.drawBitmap(redGlow, (wx + 54 - 16).toFloat(), (base - 64 - 16).toFloat(), plain)
                }
            }
        }
        // a gantry crane rail across the hall with a trolley and hook swinging
        run {
            val gy = 40
            rect(c, 0, gy, W, 7, PX.K); rect(c, 0, gy + 1, W, 5, hazardY); rect(c, 0, gy + 1, W, 1, 0xFFFFE9A0.toInt()); for (s in 0 until W step 10) rect(c, s, gy + 3, 4, 3, hazardK)
            val tx = mod((sc * S * 0.5f).toInt() / 2 * 0 + (clock * 20f).toInt(), W + 60) - 30
            rect(c, tx, gy + 6, 20, 8, PX.K); rect(c, tx + 1, gy + 7, 18, 6, steelD); rect(c, tx + 1, gy + 7, 18, 2, steel)
            val sw = (sin(clock * 1.5f) * 4).toInt()
            rect(c, tx + 10 + sw / 2, gy + 14, 2, 22, PX.K); rect(c, tx + 10 + sw / 2, gy + 14, 1, 22, steelL)
            rect(c, tx + 7 + sw, gy + 36, 8, 4, PX.K); rect(c, tx + 8 + sw, gy + 37, 6, 2, steel); rect(c, tx + 12 + sw, gy + 40, 3, 5, PX.K); rect(c, tx + 7 + sw, gy + 44, 6, 2, steel)
        }

        // ---- front: ceiling girder with lamps and light beams, chains, floor with hazard stripes, props ----
        val nearOff = (sc * S).toInt()
        rect(c, 0, 0, W, 18, steelD); rect(c, 0, 0, W, 3, steelL); rect(c, 0, 15, W, 3, PX.K)
        for (i in -1..(W / 32 + 2)) { val x = i * 32 - mod(nearOff, 32); rect(c, x, 3, 2, 12, steel); rect(c, x + 16, 3, 1, 12, steelDD); px(c, x + 1, 4, steelL); px(c, x + 1, 13, steelL) }
        for (i in -1..(W / 128 + 2)) {
            val x = i * 128 - mod(nearOff, 128) + 40
            rect(c, x, 18, 2, 8, PX.K); rect(c, x - 6, 26, 14, 6, PX.K); rect(c, x - 5, 27, 12, 4, PX.W); rect(c, x - 3, 31, 8, 3, a(PX.LAMP, 0x50))
            c.drawBitmap(lampBeam, (x + 1 - 30).toFloat(), 30f, plain)
        }
        for (i in -1..(W / 72 + 2)) {   // hanging chains
            val x = i * 72 - mod((nearOff * 1.0f).toInt(), 72) + 24; val len = 14 + hash(i + (nearOff / 72), 4, 6) % 18
            for (k in 0 until len step 4) { rect(c, x, 18 + k, 3, 3, PX.K); rect(c, x + 1, 19 + k, 1, 1, steelL) }
            rect(c, x - 2, 18 + len, 7, 4, PX.K); rect(c, x - 1, 19 + len, 5, 2, steel)
        }
        val floorTop = (world.floorY * S + 12).roundToInt()
        rect(c, 0, floorTop, W, H - floorTop, 0xFF161C24.toInt()); rect(c, 0, floorTop, W, 3, steelL); rect(c, 0, floorTop + 3, W, 2, PX.K)
        for (i in -1..(W / 16 + 2)) { val x = i * 16 - mod(nearOff, 16); if (i % 2 == 0) { rect(c, x, floorTop + 8, 8, 6, hazardY); rect(c, x + 8, floorTop + 8, 8, 6, hazardK) } }
        // things standing on the floor out front: barrels, crates, a drain, a forklift now and then
        for (i in -1..(W / 90 + 2)) {
            val idx = (nearOff + i * 90) / 90
            val x = i * 90 - mod(nearOff, 90)
            when (hash(idx, 9, 4) % 4) {
                0 -> { rect(c, x + 6, floorTop - 14, 14, 15, PX.K); rect(c, x + 7, floorTop - 13, 12, 13, 0xFFB03A2A.toInt()); rect(c, x + 7, floorTop - 13, 12, 2, 0xFFFF7A5A.toInt()); rect(c, x + 7, floorTop - 7, 12, 1, 0xFF701C10.toInt()); rect(c, x + 22, floorTop - 14, 14, 15, PX.K); rect(c, x + 23, floorTop - 13, 12, 13, 0xFF3A56B8.toInt()); rect(c, x + 23, floorTop - 13, 12, 2, 0xFF6A8AF0.toInt()) }
                1 -> { for (b in 0..1) { rect(c, x + 8 + b * 17, floorTop - 15, 17, 16, PX.K); rect(c, x + 9 + b * 17, floorTop - 14, 15, 14, 0xFFA8803E.toInt()); rect(c, x + 9 + b * 17, floorTop - 14, 15, 2, 0xFFD4AA5E.toInt()); rect(c, x + 15 + b * 17, floorTop - 14, 3, 14, 0xFF6E5226.toInt()) }
                    rect(c, x + 14, floorTop - 28, 17, 13, PX.K); rect(c, x + 15, floorTop - 27, 15, 11, 0xFFA8803E.toInt()); rect(c, x + 15, floorTop - 27, 15, 2, 0xFFD4AA5E.toInt()) }
                2 -> { rect(c, x + 8, floorTop - 3, 20, 4, 0xFF04070A.toInt()); for (k in 0..4) rect(c, x + 10 + k * 4, floorTop - 2, 2, 2, 0xFF1A252D.toInt()) }
                else -> { // a forklift parked
                    rect(c, x + 6, floorTop - 20, 28, 16, PX.K); rect(c, x + 7, floorTop - 19, 26, 14, 0xFFE8A020.toInt()); rect(c, x + 7, floorTop - 19, 26, 3, 0xFFFFD060.toInt()); rect(c, x + 10, floorTop - 30, 14, 12, PX.K); rect(c, x + 11, floorTop - 29, 12, 10, 0xFF2A3A4C.toInt())
                    rect(c, x + 34, floorTop - 28, 3, 26, steelD); rect(c, x + 37, floorTop - 8, 8, 2, steel); rect(c, x + 8, floorTop - 6, 8, 8, PX.K); rect(c, x + 24, floorTop - 6, 8, 8, PX.K); rect(c, x + 10, floorTop - 4, 4, 4, steelD); rect(c, x + 26, floorTop - 4, 4, 4, steelD)
                }
            }
        }
        // a puff of steam from a floor vent now and then
        for (i in -1..(W / 140 + 2)) { val x = i * 140 - mod(nearOff, 140) + 70; val ph = (clock * 0.45f + i * 0.31f + (nearOff / 140) * 0.2f) % 1f
            if (ph < 0.5f) for (k in 0..3) { val q = ph * 2f; rect(c, x + (sin(clock * 3f + k) * 3).toInt(), floorTop - 4 - (q * 26).toInt() - k * 5, 5 + k, 5, a(0xFFC8D0E0.toInt(), ((1f - q) * 0x70).toInt())) } }

        // ---- extra detail: ducts and drooping cables overhead, wall signs, a spinning fan, floor grating and puddles, dust ----
        val ductOff = (sc * S * 0.8f).toInt()
        for (i in -1..(W / 110 + 2)) {
            val idx = (ductOff + i * 110) / 110; val x = i * 110 - mod(ductOff, 110)
            rect(c, x, 36, 100, 10, PX.K); rect(c, x + 1, 37, 98, 8, 0xFF2A3544.toInt()); rect(c, x + 1, 37, 98, 2, steelL); for (s in 0 until 100 step 20) rect(c, x + s, 36, 2, 10, steelDD)
            // a cable draped between two hooks
            for (k in 0..24) { val cx = x + 10 + k * 3; val cy = 47 + (sin(k / 24f * PI.toFloat()) * (6 + hash(idx, 1, 5) % 6)).toInt(); rect(c, cx, cy, 3, 2, PX.K); px(c, cx + 1, cy, 0xFF3C4858.toInt()) }
        }
        for (i in -1..(W / 150 + 2)) {
            val idx = (farOff + i * 150) / 150; val x = i * 150 - mod(farOff, 150) + 60
            when (hash(idx, 7, 9) % 3) {
                0 -> { rect(c, x, 104, 30, 20, PX.K); rect(c, x + 1, 105, 28, 18, hazardY); rect(c, x + 1, 105, 28, 3, 0xFFFFE9A0.toInt()); rect(c, x + 12, 109, 6, 8, PX.K); rect(c, x + 12, 119, 6, 2, PX.K) }   // a DANGER sign
                1 -> { rect(c, x, 104, 30, 14, PX.K); rect(c, x + 1, 105, 28, 12, 0xFF0F5A34.toInt()); rect(c, x + 6, 109, 18, 3, PX.W); rect(c, x + 22, 108, 3, 5, 0xFF3BFF9A.toInt()) }     // an EXIT sign
                else -> { // a wall fan turning
                    rect(c, x + 2, 100, 26, 26, PX.K); rect(c, x + 4, 102, 22, 22, 0xFF2A3544.toInt())
                    val ang = clock * 9f; for (b in 0..3) { val aa = ang + b * PI.toFloat() / 2f; val bx = (x + 15 + cos(aa) * 8f).roundToInt(); val by = (113 + sin(aa) * 8f).roundToInt(); rect(c, bx - 2, by - 2, 5, 5, steelL) }
                    rect(c, x + 13, 111, 4, 4, PX.K)
                }
            }
        }
        // floor grating with a faint reflection of the lamps, and puddles
        for (i in -1..(W / 48 + 2)) { val x = i * 48 - mod(nearOff, 48)
            for (g in 0 until 40 step 4) rect(c, x + g, floorTop + 16, 2, 8, 0xFF10161C.toInt())
            if (hash((nearOff + i * 48) / 48, 3, 2) % 4 == 0) { rect(c, x + 6, floorTop + 6, 24, 3, a(0xFF3A5A8A.toInt(), 0x90)); rect(c, x + 10, floorTop + 6, 8, 1, a(PX.W, 0x80)) }
        }
        for (k in 0 until 14) {   // dust and sparks floating in the lamp light
            val ph = (clock * 0.25f + k * 0.0713f) % 1f
            val x = (W - ((ph + k * 0.37f) % 1f) * (W + 20)).toInt(); val y = 30 + (hash(k, 6, 1) % (H - 90)) + (sin(clock * 1.3f + k) * 4).toInt()
            rect(c, x, y, 1, 1, a(PX.WARM, 0x70))
        }
        // a low haze rolling over the floor
        for (y in floorTop - 26 until floorTop) { val k = (y - (floorTop - 26)) / 26f; val off = (clock * 8f + nearOff * 0.3f).toInt()
            for (x in 0 until W) { val n = (sin((x + off) * 0.05f) + sin((x - off * 0.6f) * 0.11f + y * 0.2f)) * 0.5f + 0.5f; if (bayer[(y and 3) * 4 + (x and 3)] / 16f < k * n * 0.5f) px(c, x, y, a(0xFF8A98B8.toInt(), 0x50)) } }

        // ---- dangers ----
        for (z in world.hazards) {
            val x = ((z.wx - sc) * S).roundToInt()
            if (x < -30 || x > W + 30) continue
            when (z.kind) {
                0 -> saw(c, x, (world.sawY(z) * S).roundToInt(), z, clock)
                2 -> { val e = world.spinEnds(z); zapBar(c, ((e[0] - sc) * S).roundToInt(), (e[1] * S).roundToInt(), ((e[2] - sc) * S).roundToInt(), (e[3] * S).roundToInt(), clock); rect(c, x - 4, (z.y * S).roundToInt() - 4, 9, 9, PX.K); rect(c, x - 3, (z.y * S).roundToInt() - 3, 7, 7, steel) }
                3 -> drone(c, x, (world.droneY(z) * S).roundToInt(), clock)
                else -> gate(c, x, (world.sawY(z) * S).roundToInt(), z, clock)
            }
        }
        for (co in world.coinList) {
            if (co.taken) continue
            val x = ((co.wx - sc) * S).roundToInt(); val y = (co.y * S).roundToInt()
            if (x < -8 || x > W + 8) continue
            c.drawBitmap(coinGlow, x - 10f, y - 10f, plain)
            coin(c, x, y, 5, clock * 4.2f + co.wx * 0.9f)
        }
        for (z in world.zaps) {
            val x1 = ((z.wx - sc) * S).roundToInt(); val x2 = ((z.wx + z.dx - sc) * S).roundToInt()
            if ((x1 < -30 && x2 < -30) || (x1 > W + 30 && x2 > W + 30)) continue
            val y1 = (z.y1 * S).roundToInt(); val y2 = (z.y2 * S).roundToInt()
            zapBar(c, x1, y1, x2, y2, clock)
        }
        rockets(c, clock)

        // the smoke trail from the jetpack: puffs left behind in the air, drifting back with the hall
        val fxT = (world.px * S); val byT = (world.y * S)
        if (world.phase == FlyWorld.Phase.FLY && world.thrust && (trail.isEmpty() || clock - trail.last()[2] > 0.05f)) trail += floatArrayOf(world.scroll * S + fxT - 9f, byT + 10f, clock)
        trail.removeAll { clock - it[2] > 1.1f || clock < it[2] }
        for (tp in trail) { val age = (clock - tp[2]) / 1.1f; val x = (tp[0] - world.scroll * S).roundToInt(); val y = (tp[1] - age * 8f).roundToInt(); val sz = 3 + (age * 6).toInt()
            rect(c, x - sz / 2, y - sz / 2, sz, sz, a(0xFF9AA4B8.toInt(), ((1f - age) * 0x70).toInt())) }

        // ---- me, with a jetpack ----
        val fx = (world.px * S).roundToInt(); val bodyY = (world.y * S).roundToInt()
        val blink = world.invuln > 0f && ((clock * 14f).toInt() % 2 == 0)
        when (world.phase) {
            FlyWorld.Phase.DONE -> {
                val fy = floorTop + 4
                lying(c, fx + 4, fy, clerk, true, true)
                rect(c, fx - 22, fy - 7, 8, 6, 0xFFC03030.toInt()); rect(c, fx - 22, fy - 7, 8, 1, 0xFFFF7070.toInt())
                for (k in 0..4) { val ph = (clock * 0.9f + k * 0.2f) % 1f; rect(c, fx - 20 + (sin(clock * 2f + k) * 3).toInt(), fy - 10 - (ph * 24).toInt(), 3, 3, a(0xFF9AA4B5.toInt(), ((1f - ph) * 0xA0).toInt())) }
            }
            else -> if (!blink) {
                val tumble = world.phase == FlyWorld.Phase.DROP
                c.save()
                if (tumble) c.rotate((world.phaseT * 340f) % 360f, fx.toFloat(), (bodyY - 8).toFloat())
                if (world.flipped) c.scale(1f, -1f, fx.toFloat(), (bodyY - 2).toFloat())   // the flipper: upside down on the ceiling
                val packC = JetPacks.COLOR[world.spec.pack.coerceIn(0, 4)]
                rect(c, fx - 10, bodyY - 12, 6, 13, PX.K); rect(c, fx - 9, bodyY - 11, 4, 11, packC); rect(c, fx - 9, bodyY - 11, 4, 2, if (world.spec.pack == 0) 0xFFFF7070.toInt() else lite0(packC, 70))
                if (world.spec.pack == 4) { rect(c, fx - 12, bodyY - 9, 2, 6, 0xFFC03030.toInt()); rect(c, fx - 12, bodyY - 9, 2, 2, 0xFFE8EEF8.toInt()) }   // the magnet
                rect(c, fx - 9, bodyY, 4, 2, steel)
                folk(c, fx, bodyY + 8, 1, if (world.phase == FlyWorld.Phase.FLY && world.thrust) 1 else 0, clerk, false, true)
                val burning = (world.phase == FlyWorld.Phase.FLY && (if (world.spec.pack == 2 || world.spec.pack == 3) world.vy * (if (world.flipped) 1f else -1f) > 2f else world.thrust)) || (world.phase == FlyWorld.Phase.SPUTTER && (world.phaseT % 0.55f) < 0.16f)
                if (burning) {
                    // a layered flame: white-hot core, gold, orange, a red tip, flickering; a warm glow on everything near
                    val fl = 10 + (clock * 30f).toInt() % 6; val wob = ((clock * 40f).toInt() % 3) - 1
                    c.drawBitmap(flameGlow, (fx - 8 - 20).toFloat(), (bodyY + 8 - 20).toFloat(), plain)
                    rect(c, fx - 10 + wob, bodyY + 2, 6, fl, 0xFFFF3B1A.toInt()); rect(c, fx - 9, bodyY + 2, 4, fl - 2, 0xFFFF7A2A.toInt())
                    rect(c, fx - 9, bodyY + 2, 4, fl - 5, 0xFFFFC04A.toInt()); rect(c, fx - 8, bodyY + 2, 2, fl - 7, 0xFFFFF4C0.toInt())
                    for (k in 0..2) { val ph = (clock * 6f + k * 0.33f) % 1f; px(c, fx - 9 + (k - 1) * 2, bodyY + 2 + fl + (ph * 6).toInt(), a(0xFFFFB04A.toInt(), ((1f - ph) * 0xE0).toInt())) }
                }
                // the lamps catch his back and shoulders
                rect(c, fx - 3, bodyY - 10, 1, 8, a(PX.WARM, 0x70)); px(c, fx - 2, bodyY - 11, a(PX.WARM, 0x90))
                if (world.phase == FlyWorld.Phase.SPUTTER || tumble) for (k in 0..3) {
                    val ph = (clock * 1.4f + k * 0.25f) % 1f
                    rect(c, fx - 12 - (ph * 22).toInt(), bodyY + 4 + (sin(clock * 5 + k) * 3).toInt(), 4, 4, a(0xFF5A6068.toInt(), ((1f - ph) * 0xB0).toInt()))
                }
                c.restore()
            }
        }
        // tall dark columns and pipes passing close to the camera (they move fastest, so the hall feels deep)
        val frontOff = (world.scroll * S * 1.35f).toInt()
        for (i in -1..(W / 170 + 2)) { val idx = (frontOff + i * 170) / 170; val x = i * 170 - mod(frontOff, 170) + 30
            if (hash(idx, 2, 21) % 3 == 0) { rect(c, x, 0, 14, H, 0xFF05080C.toInt()); rect(c, x + 13, 0, 1, H, 0xFF1A2230.toInt()); for (yy in 20 until H step 40) rect(c, x - 2, yy, 18, 4, 0xFF080C12.toInt()) }
            else if (hash(idx, 3, 21) % 2 == 0) { rect(c, x, H - 46, 40, 8, 0xFF05080C.toInt()); rect(c, x + 34, H - 46, 8, 46, 0xFF05080C.toInt()); rect(c, x, H - 46, 40, 1, 0xFF1A2230.toInt()) } }
        if (world.spec.arcade && world.zone != 0) {   // the arcade's parts of the factory: the boiler room glows red, the server vault cold blue, the foundry orange
            val z = world.zone; val col = when (z) { 1 -> 0xFFFF5A2A.toInt(); 2 -> 0xFF2AC8FF.toInt(); else -> 0xFFFFA02A.toInt() }
            p.color = a(col, 0x22 + ((sin(clock * (if (z == 3) 5f else 1.5f)) + 1f) * 8).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
            val off = (world.scroll * S).toInt()
            for (i in -1..(W / 60 + 2)) { val x = i * 60 - mod(off, 60) + 20; val idx = (off + i * 60) / 60
                when (z) {
                    1 -> { val ph = (clock * 1.2f + idx * 0.3f) % 1f; rect(c, x, H - 40 - (ph * 50).toInt(), 6, 6, a(0xFFFF8A3A.toInt(), ((1f - ph) * 0xB0).toInt())) }     // embers rising
                    2 -> for (k in 0..3) px(c, x + k * 3, 60 + (idx % 4) * 30 + k * 5, if ((clock * 5f + k + idx).toInt() % 3 == 0) 0xFF3BFF9A.toInt() else 0xFF2AC8FF.toInt())   // blinking racks
                    else -> { if (hash(idx, 4, 7) % 3 == 0) { rect(c, x, H - 22, 30, 4, 0xFFFFC84A.toInt()); rect(c, x + 2, H - 21, 26, 1, PX.W) } }                  // molten channels
                }
            }
        }
        c.drawBitmap(vignette, 0f, 0f, plain)
        if (world.hitFlash > 0f) { p.color = a(cyan, (world.hitFlash * 0x70).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }

    /** An electric zapper between two posts. */
    private fun zapBar(c: Canvas, x1: Int, y1: Int, x2: Int, y2: Int, clock: Float) {
            val n = 14
            for (k in 0 until n) {
                val t0 = k / n.toFloat(); val t1 = (k + 1) / n.toFloat()
                val ax = x1 + ((x2 - x1) * t0).roundToInt(); val ay = y1 + ((y2 - y1) * t0).roundToInt()
                val bx = x1 + ((x2 - x1) * t1).roundToInt(); val by = y1 + ((y2 - y1) * t1).roundToInt()
                val j = ((hash(k, (clock * 24f).toInt(), 6) % 7) - 3)
                rect(c, minOf(ax, bx) + j, minOf(ay, by), abs(bx - ax) + 3, abs(by - ay) + 3, a(cyan, 0x90))
                rect(c, minOf(ax, bx) + j, minOf(ay, by), abs(bx - ax) + 1, abs(by - ay) + 1, PX.W)
            }
            for ((nx, ny) in listOf(x1 to y1, x2 to y2)) {
                rect(c, nx - 5, ny - 5, 10, 10, PX.K); rect(c, nx - 4, ny - 4, 8, 8, steel); rect(c, nx - 3, ny - 3, 3, 3, steelL); rect(c, nx - 2, ny - 2, 4, 4, a(cyan, 0xC0))
                c.drawBitmap(cyanGlow, (nx - 14).toFloat(), (ny - 14).toFloat(), plain)
            }
            }

    /** A little security drone (arcade): grey body, red eye, spinning rotors. */
    private fun drone(c: Canvas, x: Int, y: Int, clock: Float) {
        rect(c, x - 9, y - 5, 19, 11, PX.K); rect(c, x - 8, y - 4, 17, 9, steelD); rect(c, x - 8, y - 4, 17, 2, steelL)
        rect(c, x - 6, y - 1, 5, 4, PX.K); rect(c, x - 5, y, 3, 2, if ((clock * 8f).toInt() % 2 == 0) PX.RED else 0xFFFFB0B0.toInt())
        val sp = (clock * 30f).toInt() % 2
        rect(c, x - 12 + sp * 2, y - 8, 9, 2, steelL); rect(c, x + 4 - sp * 2, y - 8, 9, 2, steelL); rect(c, x - 1, y - 7, 3, 3, PX.K)
        c.drawBitmap(redGlow, (x - 7 - 16).toFloat(), (y + 1 - 16).toFloat(), plain)
    }

    private fun saw(c: Canvas, x: Int, y: Int, z: FlyWorld.Haz, clock: Float) {
        // a rail it travels along, then the spinning blade
        val top = ((z.y - z.amp) * S).roundToInt(); val bot = ((z.y + z.amp) * S).roundToInt()
        rect(c, x - 1, top, 3, bot - top, PX.K); rect(c, x, top, 1, bot - top, steel)
        c.save(); c.rotate(clock * 600f, x.toFloat(), y.toFloat())
        rect(c, x - 12, y - 12, 24, 24, a(PX.K, 0x00))
        for (k in 0 until 8) {
            val ang = k * PI.toFloat() / 4f
            val tx = (x + cos(ang) * 15f).roundToInt(); val ty = (y + sin(ang) * 15f).roundToInt()
            rect(c, tx - 2, ty - 2, 5, 5, PX.K); rect(c, tx - 1, ty - 1, 3, 3, steelL)
        }
        c.restore()
        rect(c, x - 11, y - 11, 22, 22, PX.K); rect(c, x - 10, y - 10, 20, 20, 0xFF9AA4B5.toInt()); rect(c, x - 10, y - 10, 20, 4, PX.W)
        rect(c, x - 6, y - 6, 12, 12, steelD); rect(c, x - 2, y - 2, 4, 4, PX.K); rect(c, x - 1, y - 1, 2, 2, hazardY)
        if ((clock * 12f).toInt() % 2 == 0) { px(c, x + 14, y - 3, PX.WARM); px(c, x + 16, y - 1, PX.W) }
    }

    private fun gate(c: Canvas, x: Int, gy: Int, z: FlyWorld.Haz, clock: Float) {
        val gap = (z.gap * S).roundToInt(); val top = (world.ceilY * S).roundToInt() - 6; val bot = (world.floorY * S).roundToInt() + 14
        val flick = (clock * 20f).toInt() % 2 == 0
        for ((y0, y1) in listOf(top to gy - gap, gy + gap to bot)) {
            rect(c, x - 2, y0, 5, y1 - y0, a(0xFFFF3B55.toInt(), 0x60)); rect(c, x, y0, 1, y1 - y0, if (flick) PX.W else 0xFFFF9AA8.toInt())
            for (yy in y0 until y1 step 12) rect(c, x - 4, yy, 9, 1, a(0xFFFF3B55.toInt(), 0x90))
        }
        // emitter posts at both ends of each beam and arrows marking the gap
        for (py in intArrayOf(gy - gap, gy + gap)) { rect(c, x - 7, py - 4, 15, 8, PX.K); rect(c, x - 6, py - 3, 13, 6, steel); rect(c, x - 6, py - 3, 13, 2, steelL); rect(c, x - 2, py - 1, 5, 3, 0xFFFF3B55.toInt()) }
        rect(c, x - 7, top, 15, 6, PX.K); rect(c, x - 7, bot - 4, 15, 6, PX.K)
        for (k in 0..2) { px(c, x - 10 - k * 2, gy, 0xFF3BFF9A.toInt()); px(c, x + 10 + k * 2, gy, 0xFF3BFF9A.toInt()) }
        c.drawBitmap(redGlow, (x - 16).toFloat(), (gy - gap - 16).toFloat(), plain); c.drawBitmap(redGlow, (x - 16).toFloat(), (gy + gap - 16).toFloat(), plain)
    }

    private fun rockets(c: Canvas, clock: Float) {
        for (r in world.rockets) {
            val y = (r.y * S).roundToInt()
            when (r.state) {
                1 -> { // a warning at the right edge: a flashing alert with a "!" and a thin sight line across
                    val on = (clock * 8f).toInt() % 2 == 0
                    if (on) { rect(c, W - 30, y - 12, 26, 24, PX.K); rect(c, W - 29, y - 11, 24, 22, 0xFFB02038.toInt()); rect(c, W - 29, y - 11, 24, 3, 0xFFFF6A7A.toInt()); rect(c, W - 18, y - 7, 3, 9, PX.W); rect(c, W - 18, y + 4, 3, 3, PX.W)
                        for (k in 0..2) rect(c, W - 34 - k * 4, y - 3 + k, 3, 7 - k * 2, 0xFFFF3B55.toInt()) }
                    for (xx in 0 until W - 36 step 12) rect(c, xx, y, 5, 1, a(0xFFFF3B55.toInt(), if (on) 0x80 else 0x30))
                    c.drawBitmap(redGlow, (W - 30 - 4).toFloat(), (y - 16).toFloat(), plain)
                }
                2 -> {
                    val x = (r.sx * S).roundToInt()
                    // a smoke and flame trail behind it, then the missile pointing left
                    for (k in 0..7) { val ph = k / 8f; rect(c, x + 22 + k * 7, y - 3 + (sin(clock * 9f + k) * 2).toInt(), 8, 7, a(0xFF8A94AC.toInt(), ((1f - ph) * 0xA0).toInt())) }
                    for (k in 0..2) rect(c, x + 14 + k * 5, y - 3 + k, 6 - k, 6 - k * 2, a(if (k == 0) 0xFFFFE07A.toInt() else 0xFFFF7A2A.toInt(), 0xF0 - k * 0x30))
                    rect(c, x - 14, y - 6, 30, 12, PX.K)
                    rect(c, x - 13, y - 5, 28, 10, 0xFFD8DCE8.toInt()); rect(c, x - 13, y - 5, 28, 3, PX.W); rect(c, x - 13, y + 2, 28, 3, 0xFF8A94AC.toInt())
                    rect(c, x - 20, y - 4, 7, 8, PX.K); rect(c, x - 19, y - 3, 6, 6, 0xFFC03030.toInt()); rect(c, x - 19, y - 3, 6, 2, 0xFFFF7070.toInt())    // nose cone
                    rect(c, x + 8, y - 9, 8, 4, 0xFFC03030.toInt()); rect(c, x + 8, y + 5, 8, 4, 0xFFC03030.toInt())                                              // fins
                    rect(c, x - 2, y - 5, 3, 10, 0xFFC03030.toInt()); rect(c, x + 3, y - 5, 2, 10, hazardY)
                    c.drawBitmap(redGlow, (x + 10 - 16).toFloat(), (y - 16).toFloat(), plain)
                }
            }
        }
    }
}
