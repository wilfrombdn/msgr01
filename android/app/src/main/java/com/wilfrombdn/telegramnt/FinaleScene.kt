package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the last level (see [FinaleWorld]) as 256 x 384 pixel art, in the same night palette and ordered dither as the rest of Dead Letter:
 * the beach under the radio tower, the data-centre climb inside it, the slow door, the control room over the burning city, the uplink
 * panel, the broadcast going out across the state, and the end.
 */
internal class FinaleScene(private val world: FinaleWorld) : PixelSprites() {
    val W = FinaleWorld.W
    val H = FinaleWorld.H
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)

    private fun K(v: Long) = v.toInt()
    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = (((c1 shr 16) and 0xFF) * (1 - tt) + ((c2 shr 16) and 0xFF) * tt).toInt(); val g = (((c1 shr 8) and 0xFF) * (1 - tt) + ((c2 shr 8) and 0xFF) * tt).toInt()
        val b = ((c1 and 0xFF) * (1 - tt) + (c2 and 0xFF) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun lite(c: Int, d: Int): Int {
        val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255); val b = ((c and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun dith(x: Int, y: Int) = bayer[(y and 3) * 4 + (x and 3)] / 16f
    private inline fun big(x: Int, y: Int, s: Float = 2f, what: () -> Unit) { fc.save(); fc.scale(s, s, x.toFloat(), y.toFloat()); what(); fc.restore() }

    // the cast
    private val you = Outfit(PX.SKIN, 0xFFE8C860.toInt(), null, null, 0xFF6E96CC.toInt(), null, 0xFF8A8670.toInt(), 0xFF1A1410.toInt(), null, glasses = true)
    private val boatman = Outfit(0xFFC89A78.toInt(), 0xFF2A1A10.toInt(), 0xFF3A2A3A.toInt(), null, 0xFF2A3A2A.toInt(), 0xFF3A4436.toInt(), 0xFF22261E.toInt(), PX.K, null)
    private val patrol get() = police
    private val mayorSkin = PX.SKIN

    /** A dithered vertical gradient through a list of colours, painted into [px] for rows y0 until y1. */
    private fun gradient(px: IntArray, w: Int, y0: Int, y1: Int, cols: IntArray, steps: Float = 12f) {
        for (y in y0 until y1) for (x in 0 until w) {
            val t = (y - y0) / (y1 - y0).toFloat()
            val q = (floor(t * steps + dith(x, y)) / steps).coerceIn(0f, 1f) * (cols.size - 1)
            val i = q.toInt().coerceAtMost(cols.size - 2)
            px[y * w + x] = mix(cols[i], cols[i + 1], q - i)
        }
    }
    private fun stars(px: IntArray, w: Int, y1: Int, seed: Int) { for (y in 0 until y1) for (x in 0 until w) if (hash(x, y, seed) % 240 == 0) px[y * w + x] = if (hash(x, y, seed + 1) % 3 == 0) PX.W else K(0xFF8A9AC8) }
    private fun skyline(c: Canvas, baseY: Int, x0: Int, x1: Int, seed: Int, tall: Int, col: Int = K(0xFF0C0E22), lit: Float = 0.2f) {
        var x = x0; var i = 0
        while (x < x1) {
            val bw = 6 + hash(i, 1, seed) % 14; val bh = 10 + hash(i, 2, seed) % tall
            rect(c, x, baseY - bh, bw, bh, col)
            for (wy in baseY - bh + 2 until baseY - 1 step 3) for (wx in x + 1 until x + bw - 1 step 2) if (hash(wx, wy, seed + 3) % 100 < lit * 100) px(c, wx, wy, if (hash(wx, wy, seed + 4) % 4 == 0) PX.CYAN else PX.WARM)
            x += bw; i++
        }
    }

    // =====================================================================================================================
    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        when (world.stage) {
            FinaleWorld.Stage.BEACH -> beach(c, clock)
            FinaleWorld.Stage.INTRO, FinaleWorld.Stage.CLIMB, FinaleWorld.Stage.EXECUTE -> tower(c, clock)
            FinaleWorld.Stage.DOOR -> door(c, clock)
            FinaleWorld.Stage.CONTROL, FinaleWorld.Stage.END -> control(c, clock)
            FinaleWorld.Stage.SIMON -> simon(c, clock)
            FinaleWorld.Stage.BROADCAST -> broadcast(c, clock)
        }
        c.drawBitmap(vignette, 0f, 0f, plain)
        world.bubble()?.let { (who, what) -> speech(c, who, what) }
        if (world.fade > 0f) { p.style = Paint.Style.FILL; p.color = a(PX.K, (world.fade * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }
    private val vignette: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val d = (hypot((x - W / 2f) / (W / 2f), (y - H / 2f) / (H / 2f) * 0.9f) - 0.74f) / 0.6f
            if (d <= 0f) continue
            val q = floor(d.coerceAtMost(1f) * 4f + dith(x, y)) / 4f
            if (q > 0f) px[y * W + x] = a(K(0xFF02040C), (q * 0xB0).toInt())
        }
        bm.setPixels(px, 0, W, 0, 0, W, H); bm
    }

    // where the speaker's head is, for the bubbles
    private var heads = HashMap<String, Pair<Int, Int>>()
    private fun speech(c: Canvas, who: String, what: String) {
        val (hx, hy) = heads[who] ?: return
        val stT = when (world.stage) { FinaleWorld.Stage.BEACH -> when (who) { "boat" -> if (world.t < 7f) 4.0f else 8.6f; else -> 6.6f }; FinaleWorld.Stage.INTRO -> if (world.t < 7f) 4.7f else 7.9f; FinaleWorld.Stage.CLIMB -> (world.t / 9f).toInt() * 9f + 3f; else -> 0.4f }
        val shown = ((world.t - stT) * 30f).toInt().coerceAtLeast(1)
        if (what == "!") { rect(c, hx - 2, hy - 20, 5, 12, PX.K); rect(c, hx - 1, hy - 19, 3, 7, PX.RED); rect(c, hx - 1, hy - 11, 3, 2, PX.RED); return }
        bubble(c, what, hx, hy, shown, W)
    }

    // =====================================================================================================================
    //  the beach
    // =====================================================================================================================
    // A wide island scene the camera pans across, everything at the game's normal pixel size (nothing scaled up):
    // the sky, a moonlit bay with the burning city across it, a wooden dock, the beach, a path up the hill past a fence,
    // and the broadcast station with its mast climbing out of the top of the picture. Widescreen bars top and bottom.
    private val BW = 640                                   // the island is this wide; the screen shows 256 of it
    private val HZ = 236                                   // the horizon
    private val DECK = 318                                 // the top of the dock
    private val DOOR_X = 500
    private fun ground(x: Float) = when { x < 214f -> 400f; x < 330f -> 321f + (x - 214f) / 116f * 3f; x < 470f -> 324f - (x - 330f) / 140f * 38f; else -> 286f }

    /** The sky, the stars, the moon and its halo: fixed to the screen. */
    private val skyBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, HZ, intArrayOf(K(0xFF03050C), K(0xFF0A1028), K(0xFF1C1C44), K(0xFF3E2450), K(0xFF7A3046)), 16f)
        stars(px, W, 150, 61)
        gradient(px, W, HZ, H, intArrayOf(K(0xFF241E48), K(0xFF10122C), K(0xFF070814), K(0xFF04050C)), 10f)
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        c.drawBitmap(glowBitmap(44, K(0xFFB8C8F0), 0x30), 196f - 44f, 62f - 44f, plain)
        for (yy in -9..9) for (xx in -9..9) { val d2 = xx * xx + yy * yy; if (d2 > 81) continue; val shade = (xx + 4) * (xx + 4) + (yy - 3) * (yy - 3) < 28
            px(c, 196 + xx, 62 + yy, if (d2 > 70) K(0xFFC8D4EC) else if (shade) K(0xFFD4DCF0) else K(0xFFF2F6FF)) }
        px(c, 192, 60, K(0xFFB8C4DC)); px(c, 199, 66, K(0xFFB8C4DC)); px(c, 194, 67, K(0xFFC4CEE4))       // craters
        bm
    }
    /** The city across the bay, wider than the screen (it slides a little as the camera pans), burning in places. */
    private val bayCity: Bitmap by lazy {
        val w = 400
        val bm = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        skyline(c, HZ, 0, w, 171, 46, K(0xFF120E24), 0.12f)                 // far towers, hazy
        skyline(c, HZ, 0, w, 172, 26, K(0xFF0A0A18), 0.28f)                 // nearer blocks
        for (x in 0 until w) if (hash(x, 0, 9) % 5 == 0) px(c, x, HZ - 1, if (hash(x, 1, 9) % 3 == 0) PX.WARM else K(0xFFE8E0C0))   // the waterfront
        bm
    }
    /** The island: dock, beach, path, fence, grass, the station and its mast. Transparent where the sea and sky show through. */
    private val islandBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(BW, H, Bitmap.Config.ARGB_8888); val px = IntArray(BW * H)
        // sand and earth: wind ripples, a darker wet band that shines where the sea has just been, pebbles and shells,
        // the beach blending into the stony hill, everything falling off into shadow with the ordered dither
        val sandHi = K(0xFF7A6E70); val sandLo = K(0xFF4E4450); val earthHi = K(0xFF423A40); val earthLo = K(0xFF1A161E)
        for (x in 200 until BW) {
            val gy = ground(x.toFloat()).toInt()
            if (gy >= H) continue
            val hillK = ((x - 316f) / 44f).coerceIn(0f, 1f)
            for (y in gy until H) {
                val edge = x - (214 + (y - gy) / 2)                       // distance in from the waterline
                if (edge < 0) continue
                val depth = ((y - gy) / 64f).coerceIn(0f, 1f)
                val dune = (sin(x * 0.045f + y * 0.02f) + sin(x * 0.11f - y * 0.05f) * 0.5f) * 0.5f + 0.5f
                var sand = mix(sandHi, sandLo, (depth * 0.8f + dune * 0.25f).coerceIn(0f, 1f))
                if (((y * 3 + (sin(x * 0.2f) * 3f).toInt()) % 7) == 0) sand = lite(sand, 7)          // wind ripples
                val wet = (1f - edge / 26f).coerceIn(0f, 1f)
                if (wet > 0f) { sand = mix(sand, K(0xFF2E2C44), wet * 0.7f); if (hash(x, y, 71) % 9 == 0) sand = mix(sand, K(0xFFB8C4E8), wet * 0.6f) }   // wet, catching the moon
                val earth = mix(earthHi, earthLo, depth)
                var col = if (hillK <= 0f) sand else if (hillK >= 1f) earth else (if (bayer[(y and 3) * 4 + (x and 3)] / 16f < hillK) earth else sand)
                val n = hash(x, y, 7) % 100
                if (n < 6) col = lite(col, 9) else if (n > 94) col = lite(col, -11)
                if (hillK > 0.5f && hash(x / 3, y / 2, 8) % 17 == 0) col = lite(col, -16)               // stones in the earth
                px[y * BW + x] = col
            }
            if (x > 236 && gy + 1 < H) { px[gy * BW + x] = if (x >= 340) K(0xFF5A5258) else K(0xFF9A8E8E); px[(gy + 1) * BW + x] = if (x >= 340) K(0xFF4A4248) else K(0xFF867A7C) }   // the moonlit top edge
        }
        // pebbles, shells, a piece of driftwood, a tangle of seaweed at the tide line
        for (k in 0 until 40) { val sx = 230 + hash(k, 1, 81) % 110; val sy = ground(sx.toFloat()).toInt() + 3 + hash(k, 2, 81) % 50; if (sy < H && sx - (214 + (sy - ground(sx.toFloat()).toInt()) / 2) > 2) { px[sy * BW + sx] = if (k % 4 == 0) K(0xFFD8CCC0) else K(0xFF2E2830); if (k % 3 == 0 && sx + 1 < BW) px[sy * BW + sx + 1] = K(0xFF3A3440) } }
        for (i in 0 until 22) { val dx = 268 + i; val dy = ground(dx.toFloat()).toInt() + 14 + i / 7; px[dy * BW + dx] = K(0xFF6A5444); px[(dy + 1) * BW + dx] = K(0xFF3A2C24) }
        for (i in 0 until 30) { val wx = 236 + i + (sin(i * 0.7f) * 2f).toInt(); val wy = ground(wx.toFloat()).toInt() + 10 + i / 2; if (wx - (214 + (wy - ground(wx.toFloat()).toInt()) / 2) > 0) px[wy * BW + wx] = if (i % 3 == 0) K(0xFF2E4A36) else K(0xFF1E3226) }
        bm.setPixels(px, 0, BW, 0, 0, BW, H)
        val c = Canvas(bm)
        // rocks along the shoreline under the hill
        for (k in 0 until 18) { val rx = 300 + hash(k, 1, 33) % 300; val ry = ground(rx.toFloat()).toInt() + 8 + hash(k, 2, 33) % 60; val rw = 5 + hash(k, 3, 33) % 9; val rh = 3 + hash(k, 4, 33) % 5
            rect(c, rx, ry, rw, rh, K(0xFF1A181E)); rect(c, rx + 1, ry, rw - 2, 1, K(0xFF4A4650)) }
        // wooden steps up the steep part of the path
        for (x in 380 until 470 step 6) { val y = ground(x.toFloat()).toInt(); rect(c, x, y - 1, 5, 2, K(0xFF5A4030)); rect(c, x, y - 1, 5, 1, K(0xFF8A6A48)) }
        // grass tufts and bushes on the hill
        for (k in 0 until 70) { val gx = 320 + hash(k, 1, 41) % (BW - 320); val gy = ground(gx.toFloat()).toInt(); val col = if (k % 3 == 0) K(0xFF2E4A36) else K(0xFF3A5A40)
            for (b in 0 until 3) { rect(c, gx + b * 2, gy - 2 - (hash(k, b, 42) % 3), 1, 3 + hash(k, b, 42) % 3, col) } }
        for ((bx, bs) in listOf(352 to 9, 396 to 7, 612 to 11)) { val by = ground(bx.toFloat()).toInt(); for (yy in -bs until 0) for (xx in -bs until bs) if (xx * xx + yy * yy * 2 < bs * bs && hash(bx + xx, yy, 43) % 4 != 0) px(c, bx + xx, by + yy, if (yy < -bs / 2) K(0xFF3A5A44) else K(0xFF243A2C)) }
        // the fence along the hilltop, with razor wire and a sign
        for (x in 430 until 486 step 10) { val y = ground(x.toFloat()).toInt(); rect(c, x, y - 22, 2, 22, K(0xFF4A4E58)); rect(c, x, y - 22, 1, 22, K(0xFF7A808A)) }
        for (x in 430 until 486) { val y = ground(x.toFloat()).toInt(); for (yy in y - 20 until y step 3) if ((x + yy) % 3 == 0) px(c, x, yy, K(0xFF3A3E48)); if (x % 5 < 3) px(c, x, y - 24 + (x % 5), K(0xFF8A929E)) }
        val sy = ground(452f).toInt() - 16; rect(c, 444, sy, 20, 10, K(0xFFE8E4D4)); rect(c, 444, sy, 20, 3, PX.RED); for (i in 0..2) rect(c, 446, sy + 4 + i * 2, 12 + (i % 2) * 3, 1, K(0xFF3A3A3A))
        // a floodlight pole by the path
        rect(c, 420, ground(420f).toInt() - 44, 2, 44, K(0xFF3A3E48)); rect(c, 416, ground(420f).toInt() - 48, 8, 4, K(0xFF2A2E38))
        // the station: concrete, vents, cable trays, lit windows, an air conditioner, the dish
        val bx0 = 486; val bx1 = 620; val by0 = 206; val by1 = 286
        rect(c, bx0, by0, bx1 - bx0, by1 - by0, K(0xFF2A2E38))
        for (y in by0 until by1) for (x in bx0 until bx1) { val n = hash(x, y, 51) % 100; if (n < 6) px(c, x, y, K(0xFF323642)) else if (n > 96) px(c, x, y, K(0xFF22252E)) }
        for (y in by0 + 10 until by1 step 10) rect(c, bx0, y, bx1 - bx0, 1, K(0xFF22262E))                              // the concrete pours
        rect(c, bx0, by0, bx1 - bx0, 2, K(0xFF6A7488)); rect(c, bx0 - 2, by0 - 3, bx1 - bx0 + 4, 3, K(0xFF3A3E4A))      // roof edge
        for ((wx, lit) in listOf(530 to true, 560 to false, 590 to true)) { rect(c, wx, by0 + 18, 14, 12, K(0xFF14161C)); rect(c, wx + 1, by0 + 19, 12, 10, if (lit) K(0xFFE0C47A) else K(0xFF1E2A40)); rect(c, wx + 1, by0 + 24, 12, 1, K(0xFF14161C)); rect(c, wx + 7, by0 + 19, 1, 10, K(0xFF14161C)) }
        rect(c, 540, by0 + 44, 22, 16, K(0xFF3A3E48)); for (i in 0..3) rect(c, 542, by0 + 46 + i * 3, 18, 1, K(0xFF22252E)); rect(c, 558, by0 + 50, 3, 3, K(0xFF14161C))   // an AC unit
        rect(c, bx0 + 4, by0 + 6, bx1 - bx0 - 8, 3, K(0xFF1A1C22)); for (x in bx0 + 6 until bx1 - 6 step 8) rect(c, x, by0 + 9, 1, 4, K(0xFF1A1C22))                            // a cable tray
        rect(c, 600, by0 - 14, 14, 11, K(0xFF2A2E38)); for (i in 0..4) rect(c, 596 + i, by0 - 22 - i, 18 - i * 2, 1, K(0xFF9AA0AC))                                          // the dish
        // the door, a lamp over it, a sign
        rect(c, DOOR_X - 2, 260, 20, 26, K(0xFF14161A)); rect(c, DOOR_X, 262, 16, 24, K(0xFF4A525C)); rect(c, DOOR_X, 262, 16, 1, K(0xFF7A848E)); rect(c, DOOR_X + 12, 273, 2, 3, K(0xFFB0A060))
        for (i in 0..2) rect(c, DOOR_X + 2, 266 + i * 6, 12, 1, K(0xFF3E464E))
        rect(c, DOOR_X + 4, 254, 8, 3, K(0xFF14161A)); rect(c, DOOR_X + 5, 256, 6, 1, K(0xFFFFE8A0))
        rect(c, DOOR_X + 22, 264, 18, 9, K(0xFFE8E4D4)); rect(c, DOOR_X + 22, 264, 18, 2, PX.RED); rect(c, DOOR_X + 24, 268, 14, 1, K(0xFF3A3A3A)); rect(c, DOOR_X + 24, 270, 10, 1, K(0xFF3A3A3A))
        // the mast: a steel lattice rising from the roof out of the top of the picture
        val mx = 560
        for (y in -10 until by0) {
            val k = (by0 - y) / by0.toFloat(); val half = (9 - k * 5).toInt()
            px(c, mx - half, y, K(0xFF4A5068)); px(c, mx + half, y, K(0xFF4A5068)); px(c, mx - half + 1, y, K(0xFF2A2E40))
            if ((y + 400) % 12 == 0) rect(c, mx - half, y, half * 2 + 1, 1, K(0xFF3A3E58))
            val ph = ((y + 400) % 12) / 12f
            px(c, (mx - half + ph * half * 2).toInt(), y, K(0xFF32364C)); px(c, (mx + half - ph * half * 2).toInt(), y, K(0xFF32364C))
        }
        for (k in 0..3) { val cy = by0 - 10 - k * 3; rect(c, mx + 9 + k * 5, cy, 1, by0 - cy, K(0xFF2A2E40)) }                 // guy wires
        // the dock: planks on a frame, posts down into the water, bollards, a coil of rope, a lamp post
        rect(c, 66, DECK, 150, 3, K(0xFF7A5A3C)); rect(c, 66, DECK, 150, 1, K(0xFFA88A62)); rect(c, 66, DECK + 3, 150, 2, K(0xFF3A2818))
        for (x in 66 until 216 step 4) rect(c, x, DECK, 1, 3, K(0xFF5A4028))
        for (x in 70 until 216 step 24) { rect(c, x, DECK + 5, 4, 34, K(0xFF2A1E14)); rect(c, x, DECK + 5, 1, 34, K(0xFF4A3622)); rect(c, x, DECK + 18, 4, 3, K(0xFF1E3A30)) }   // posts, with weed at the waterline
        for (x in intArrayOf(80, 150, 204)) { rect(c, x, DECK - 4, 4, 4, K(0xFF1A1C20)); rect(c, x, DECK - 4, 4, 1, K(0xFF5A5E66)) }
        for (i in 0..2) rect(c, 160 + i, DECK - 3 + i, 8 - i * 2, 1, K(0xFFB8A070))
        rect(c, 118, DECK - 34, 2, 34, K(0xFF2A2420)); rect(c, 114, DECK - 40, 10, 6, K(0xFF1A1816)); rect(c, 116, DECK - 38, 6, 3, K(0xFFFFD890))
        bm
    }
    private val lampWarm by lazy { glowBitmap(30, PX.WARM, 0x50) }
    private val floodGlow by lazy { glowBitmap(50, PX.LAMP, 0x34) }
    private val cityFire by lazy { glowBitmap(16, K(0xFFFF7A2A), 0x60) }
    private val skyBeam: Bitmap by lazy {
        val len = 260; val wid = 50; val bm = Bitmap.createBitmap(len, wid, Bitmap.Config.ARGB_8888); val pxs = IntArray(len * wid)
        for (y in 0 until wid) for (x in 0 until len) { val half = 1.5f + x * 0.09f; val ac = abs(y - wid / 2f); if (ac > half) continue; val v = (1f - x / len.toFloat()) * 0.7f + 0.3f * (1f - ac / half); val q = floor(v * 4f + dith(x, y)) / 4f; if (q > 0f) pxs[y * len + x] = a(K(0xFFDCEBFF), (q * 0x40).toInt()) }
        bm.setPixels(pxs, 0, len, 0, 0, len, wid); bm
    }

    private fun ss(a: Float, b: Float, t: Float): Float { val k = ((t - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
    /** Where the camera is (the island x at the left edge of the screen) at time t: smooth moves between held shots. */
    private fun camAt(t: Float): Float {
        val keys = listOf(0f to 0f, 4.4f to 0f, 7.4f to 96f, 9.4f to 176f, 12.2f to 196f, 15.2f to 384f)
        for (i in 0 until keys.size - 1) { val (t0, x0) = keys[i]; val (t1, x1) = keys[i + 1]; if (t <= t1) return x0 + (x1 - x0) * ss(t0, t1, t) }
        return keys.last().second
    }

    private fun beach(c: Canvas, clock: Float) {
        val t = world.t
        val cam = camAt(t).roundToInt()
        heads.clear()
        // ---- far away: the sky, drifting cloud, the burning city, the sea
        c.drawBitmap(skyBg, 0f, 0f, plain)
        for (k in 0 until 3) {   // long thin clouds, their bellies lit by the city
            val cy = 96 + k * 34; val cx = ((clock * (3f + k) + k * 120f) % 420f) - 120f - cam * 0.1f
            for (yy in 0 until 6) for (xx in 0 until 150) { val e = (1f - abs(xx - 75f) / 75f) * (1f - abs(yy - 3f) / 3.5f); if (e > 0f && bayer[(yy and 3) * 4 + (xx and 3)] < (e * 9f).toInt()) px(c, (cx + xx).toInt(), cy + yy, if (yy > 3) a(K(0xFF8A3A4A), 0x60) else a(K(0xFF3A3A5A), 0x70)) }
        }
        val cityX = -(cam * 0.25f)
        c.drawBitmap(bayCity, cityX, 0f, plain)
        for ((fx, fy) in listOf(60 to HZ - 10, 170 to HZ - 16, 300 to HZ - 8)) { val sx = fx + cityX; c.drawBitmap(cityFire, sx - 16f, fy - 16f, plain); if ((clock * 9f + fx).toInt() % 3 != 0) px(c, sx.toInt(), fy, K(0xFFFFC060)) }
        val flip = (clock * 3f).toInt() % 2 == 0
        for (fx in intArrayOf(110, 240)) { val sx = fx + cityX; c.drawBitmap(if (flip xor (fx == 240)) sirenR else sirenB, sx - 30f, HZ - 34f, plain) }
        // a searchlight from the city sweeping the low clouds
        c.save(); c.rotate(-90f + sin(clock * 0.3f) * 30f, 150f + cityX, HZ.toFloat()); c.drawBitmap(skyBeam, 150f + cityX, HZ - 25f, plain); c.restore()
        // the bay: rolling wave lines that drift as the camera pans, and the moon's road on the water
        for (y in HZ + 2 until H step 3) {
            val k = (y - HZ) / (H - HZ).toFloat(); val gap = 9 + (k * 26).toInt(); val len = 2 + (k * 10).toInt()
            val off = (clock * (5f + k * 14f) - cam * (0.3f + k * 0.7f)).toInt()
            var x = -gap + ((off % gap) + gap) % gap
            while (x < W) { if (hash(((x - off) / gap), y, 2) % 3 != 0) rect(c, x, y, len, 1, a(K(0xFF7080C0), (0x22 + k * 0x50).toInt())); x += gap }
        }
        for (y in HZ + 3 until H step 2) { val k = (y - HZ) / (H - HZ).toFloat(); val w = 2 + (k * 16).toInt(); val wob = (sin(clock * 2.6f + y * 0.37f) * (1 + k * 7)).toInt()
            if (hash(y, (clock * 5).toInt(), 3) % 4 != 0) rect(c, 196 - w / 2 + wob, y, w, 1, a(K(0xFFDCE6FF), (0x80 - k * 0x48).toInt())) }
        // ---- the island, panned
        c.save(); c.translate(-cam.toFloat(), 0f)
        // the dock posts shimmer in the water
        for (x in 70 until 216 step 24) for (yy in 0 until 20 step 2) { val wob = (sin(clock * 3f + yy * 0.6f + x) * 1.5f).toInt(); rect(c, x + wob, DECK + 40 + yy, 4, 1, a(K(0xFF2A1E14), 0xA0 - yy * 6)) }
        c.drawBitmap(islandBg, 0f, 0f, plain)
        // foam where the sea meets the sand, running up and sliding back
        val surge = sin(clock * 1.1f) * 5f
        for (y in 322 until H step 2) { val sx = 214 + (y - 322) / 2 + surge.toInt(); rect(c, sx - 4, y, 6, 1, a(PX.W, 0x50)); if (hash(y, (clock * 4).toInt(), 5) % 3 == 0) px(c, sx - 8, y, a(PX.W, 0x80)) }
        // lamp light: the dock lantern, the floodlight on the path, the lamp over the door
        c.drawBitmap(lampWarm, 119f - 30f, DECK - 37f - 30f, plain)
        c.drawBitmap(floodGlow, 420f - 50f, ground(420f) - 70f, plain)
        c.drawBitmap(torchGlow, DOOR_X + 8f - 18f, 252f - 18f, plain)
        // the mast's red lights, blinking out of step, one of them out of frame above
        for ((i, ly) in listOf(150, 70).withIndex()) if (((clock + i * 0.5f) * 1.2f).toInt() % 2 == 0) { px(c, 560, ly, PX.RED); c.drawBitmap(redGlow, 550f, ly - 10f, plain) }
        // ---- the boat comes in alongside the dock
        val arrive = ss(0f, 4.2f, t)
        val boatX = (-80 + 228 * arrive).roundToInt(); val bob = (sin(clock * 1.7f) * 1.2f).roundToInt(); val by = 330 + bob
        // its wake
        if (arrive < 1f) for (i in 0 until 24) { val wx = boatX - 40 - i * 4; rect(c, wx, by + 1 + (i % 3) / 2, 4, 1, a(PX.W, (0x90 - i * 4).coerceAtLeast(0))); if (i % 4 == 0) px(c, wx, by - 1, a(PX.W, 0x60)) }
        boatSide(c, boatX, by, clock, arrive < 1f)
        // the boatman at the wheel, pointing at the tower, then shouting after you
        val pointing = t in 5.0f..7.2f || t in 11.0f..12.6f
        folk(c, boatX - 14, by - 8, if (pointing) 2 else 1, 0, boatman, false, false, pose = if (pointing) 3 else 0, pdir = PacWorld.RIGHT)
        heads["boat"] = (boatX - 14 - cam).coerceIn(30, W - 30) to by - 30
        // ---- you: in the bow, up onto the dock, along it to the beach, then the sprint up the hill and the kick
        var yx: Float; var yy: Float; var dir = 1; var fr = 0; var kick = false
        when {
            t < 4.2f -> { yx = boatX + 14f; yy = by - 8f; dir = 1 }
            t < 5.0f -> { val k = ss(4.2f, 5.0f, t); yx = boatX + 14f + 6f * k; yy = by - 8f + (DECK - (by - 8f)) * k - sin(k * PI.toFloat()) * 8f; fr = 1 }
            t < 7.4f -> { yx = boatX + 20f; yy = DECK.toFloat(); dir = if (t < 6f) 2 else 1 }
            t < 9.2f -> { val k = ss(7.4f, 9.2f, t); yx = boatX + 20f + (250f - boatX - 20f) * k; yy = if (yx < 214f) DECK.toFloat() else ground(yx); fr = 1 + ((clock * 7f).toInt() % 2) }
            t < 12.4f -> { yx = 250f; yy = ground(250f) }
            t < 15.4f -> { val k = ((t - 12.4f) / 3f).coerceIn(0f, 1f); val e = k * (2 - k); yx = 250f + (DOOR_X - 6f - 250f) * e; yy = ground(yx); fr = 1 + ((clock * 13f).toInt() % 2) }
            else -> { yx = DOOR_X - 6f; yy = ground(yx); kick = t in KICK0..KICK0 + 0.35f }
        }
        val ix = yx.roundToInt(); val iy = yy.roundToInt()
        if (t in 12.4f..15.4f && (clock * 13f).toInt() % 2 == 0) c.drawBitmap(dustPuff, ix - 14f, iy - 6f, plain)      // kicked-up sand
        folk(c, ix, iy, dir, fr, you, false, false)
        if (kick) { rect(c, ix + 3, iy - 6, 8, 2, you.pants); rect(c, ix + 10, iy - 7, 2, 4, you.shoes) }
        heads["you"] = (ix - cam) to iy - 22
        // ---- the patrol: coming down the path with a torch, he sees you, points, and runs for the door
        if (t < 11.8f) {
            val run = ss(10.2f, 11.6f, t)
            val gxp = if (t < 8.6f) 410f - (t / 8.6f) * 14f else if (t < 10.2f) 396f else 396f + (DOOR_X + 8f - 396f) * run
            val gyp = ground(gxp)
            val point = t in 9.0f..10.2f
            val gDir = if (t < 10.2f) 3 else 1
            val gfr = if (t < 8.6f) 1 + ((clock * 4f).toInt() % 2) else if (t >= 10.2f) 1 + ((clock * 12f).toInt() % 2) else 0
            if (gxp < DOOR_X + 6f) {
                folk(c, gxp.roundToInt(), gyp.roundToInt(), if (point) 2 else gDir, gfr, patrol, t < 8.6f, false, pose = if (point) 3 else 0, pdir = PacWorld.LEFT)
                if (t < 8.6f) { c.save(); c.rotate(180f + sin(clock * 1.4f) * 12f, gxp - 4f, gyp - 7f); c.drawBitmap(torchBeam, gxp - 4f, gyp - 7f - torchBeam.height / 2f, plain); c.restore() }
            }
            heads["patrol"] = (gxp - cam).roundToInt() to gyp.roundToInt() - 22
        }
        // the door: opens for him and slams; then your kick sends it in
        if (t in 11.3f..11.8f) rect(c, DOOR_X, 262, 16, 24, K(0xFF050608))
        if (t > KICK0) {
            val k2 = ((t - KICK0) / 0.35f).coerceIn(0f, 1f)
            rect(c, DOOR_X, 262, 16, 24, K(0xFF050608)); c.drawBitmap(torchGlow, DOOR_X + 8f - 18f, 274f - 18f, plain)
            c.save(); c.rotate(k2 * 75f, DOOR_X + 16f, 286f); rect(c, DOOR_X, 262, 16, 24, K(0xFF4A525C)); rect(c, DOOR_X, 262, 16, 1, K(0xFF7A848E)); c.restore()
            if (k2 < 1f) for (i in 0 until 4) c.drawBitmap(dustPuff, DOOR_X - 6f + i * 6f, 276f + (i % 2) * 6f, plain)
        }
        c.restore()
        if (t in KICK0..KICK0 + 0.12f) { p.style = Paint.Style.FILL; p.color = a(PX.W, 0x60); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        // widescreen bars slide in, like a film
        val bar = (ss(0f, 1.2f, t) * 30f).toInt()
        rect(c, 0, 0, W, bar, PX.K); rect(c, 0, H - bar, W, bar, PX.K)
    }
    private val KICK0 = FinaleWorld.KICK_T
    private val torchBeam: Bitmap by lazy {
        val len = 64; val wid = 24; val bm = Bitmap.createBitmap(len, wid, Bitmap.Config.ARGB_8888); val pxs = IntArray(len * wid)
        for (y in 0 until wid) for (x in 0 until len) { val half = 1f + x * 0.17f; val ac = abs(y - wid / 2f); if (ac > half) continue; val v = (1f - x / len.toFloat()) * 0.6f + 0.4f * (1f - ac / half); val q = floor(v * 4f + dith(x, y)) / 4f; if (q > 0f) pxs[y * len + x] = a(K(0xFFFFF4D0), (q * 0x60).toInt()) }
        bm.setPixels(pxs, 0, len, 0, 0, len, wid); bm
    }

    /** The boat, side on, facing right: white hull with a stripe, a windscreen, the console, an outboard churning at the stern. */
    private fun boatSide(c: Canvas, x: Int, y: Int, clock: Float, running: Boolean) {
        // its reflection
        for (yy in 0 until 8 step 2) { val wob = (sin(clock * 3f + yy) * 2f).toInt(); rect(c, x - 30 + wob, y + 2 + yy, 62, 1, a(K(0xFFD8DCE4), 0x30 - yy * 3)) }
        // the hull: a long low wedge, lighter on top
        for (i in 0 until 64) {
            val hx = x - 32 + i; val bow = if (i > 50) (i - 50) / 2 else 0
            val top = y - 9 + bow / 2; val bot = y - (if (i > 54) (i - 54) else 0)
            if (bot <= top) continue
            rect(c, hx, top, 1, bot - top, K(0xFFD0D4DC)); px(c, hx, top, PX.W); rect(c, hx, bot - 2, 1, 2, K(0xFF8A90A0))
            if (bot - top > 5) px(c, hx, top + 3, K(0xFF2A4A8A)); if (bot - top > 6) px(c, hx, top + 4, K(0xFF2A4A8A))
        }
        rect(c, x - 32, y - 1, 56, 1, a(PX.K, 0x60))
        // the console and windscreen
        rect(c, x - 6, y - 15, 12, 6, K(0xFF3A404C)); rect(c, x - 4, y - 19, 8, 4, K(0xFF8AB8D8)); px(c, x - 3, y - 18, PX.W); rect(c, x - 5, y - 20, 10, 1, K(0xFF2A2E36))
        // running lights and the bow rail
        px(c, x + 24, y - 10, if ((clock * 2f).toInt() % 2 == 0) K(0xFF3BFF9A) else K(0xFF1A6A4A)); px(c, x - 31, y - 10, PX.RED)
        for (i in 0 until 14) px(c, x + 12 + i, y - 12 + i / 5, K(0xFF9AA4B4))
        // the outboard at the stern
        rect(c, x - 38, y - 12, 6, 8, K(0xFF1A1C22)); rect(c, x - 37, y - 11, 4, 2, K(0xFF4A4E58)); rect(c, x - 36, y - 4, 2, 6, K(0xFF1A1C22))
        if (running) for (i in 0 until 6) { val ph = (clock * 8f + i * 0.3f) % 1f; px(c, x - 40 - (ph * 10).toInt(), y + (i % 2), a(PX.W, ((1f - ph) * 0xC0).toInt())) }
    }
    private val redGlow by lazy { glowBitmap(10, PX.RED, 0x90) }
    private val torchGlow by lazy { glowBitmap(18, PX.LAMP, 0x50) }
    private val dustPuff by lazy { glowBitmap(8, K(0xFFB8A8B8), 0x80) }

    // =====================================================================================================================
    //  the tower: a data centre six catwalks high
    // =====================================================================================================================
    private val towerBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, H, intArrayOf(K(0xFF060912), K(0xFF0B1226), K(0xFF0A0F1E), K(0xFF05070E)), 10f)
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        // server racks along the back of every level
        for (f in 0 until FinaleWorld.FLOORS) {
            val yb = FinaleWorld.base(f).toInt() - 6; val yt = FinaleWorld.base(f + 1).toInt() + 8
            var x = 4
            while (x < W - 16) {
                rect(c, x, yt, 18, yb - yt, K(0xFF0E1424)); rect(c, x + 1, yt + 1, 16, yb - yt - 2, K(0xFF141C30)); rect(c, x + 1, yt + 1, 16, 1, K(0xFF2A3A5A))
                for (u in yt + 4 until yb - 3 step 4) { rect(c, x + 2, u, 14, 3, K(0xFF1C2640)); rect(c, x + 2, u, 14, 1, K(0xFF26324E)) }
                x += 22
            }
        }
        // cable bundles hanging from the ceiling
        for (k in 0 until 7) { val x0 = 10 + k * 36; for (xx in 0 until 30) { val y = 8 + (sin(xx / 30f * PI.toFloat()) * (10 + k % 3 * 4)).toInt(); px(c, x0 + xx, y, if (k % 2 == 0) K(0xFF2A3A6A) else K(0xFF3A2A5A)); px(c, x0 + xx, y + 1, K(0xFF141A30)) } }
        // the door at the bottom left, and the terminal at the top
        rect(c, 0, 318, 8, 34, K(0xFF020304)); rect(c, 0, 318, 8, 1, K(0xFF4A5C86))
        rect(c, 92, FinaleWorld.base(FinaleWorld.TOP).toInt() - 18, 20, 18, K(0xFF1A1E26)); rect(c, 94, FinaleWorld.base(FinaleWorld.TOP).toInt() - 16, 16, 9, K(0xFF0A2A3A))
        // a stack of spools on the top platform
        for ((sx, sy) in listOf(12 to 0, 20 to 0, 16 to -8)) spool(c, sx.toFloat(), FinaleWorld.base(FinaleWorld.TOP) - 6 + sy, 0f)
        bm
    }
    private val coldGlow by lazy { glowBitmap(42, K(0xFF6A9AFF), 0x30) }
    private val ledGlow by lazy { glowBitmap(5, PX.CYAN, 0x60) }

    private fun spool(c: Canvas, x: Float, y: Float, spin: Float) {
        val cx = x.roundToInt(); val cy = y.roundToInt()
        for (dy in -6..6) for (dx in -6..6) { val d = dx * dx + dy * dy; if (d > 36) continue; px(c, cx + dx, cy + dy, if (d > 25) K(0xFF6A4424) else if (d > 9) (if ((dx + dy + 40) % 3 == 0) K(0xFFD8742A) else K(0xFFB85A1E)) else K(0xFF8A5A30)) }
        for (k in 0 until 3) { val an = spin + k * 2.094f; px(c, cx + (cos(an) * 2.5f).roundToInt(), cy + (sin(an) * 2.5f).roundToInt(), K(0xFF3A2410)); px(c, cx + (cos(an) * 4.5f).roundToInt(), cy + (sin(an) * 4.5f).roundToInt(), K(0xFF3A2410)) }
        px(c, cx, cy, K(0xFF2A1A0A)); px(c, cx - 3, cy - 5, K(0xFFF09A50))
    }

    private fun catwalk(c: Canvas, i: Int) {
        val l = FinaleWorld.spanL(i).toInt(); val r = FinaleWorld.spanR(i).toInt()
        for (x in l..r) {
            val y = world.floorY(i, x.toFloat()).roundToInt()
            rect(c, x, y, 1, 1, K(0xFFB8C4D8)); rect(c, x, y + 1, 1, 1, K(0xFF6A7488))
            rect(c, x, y + 2, 1, 3, if ((x + y) % 4 < 2) K(0xFF2A3044) else K(0xFF3A4258))                // the mesh
            rect(c, x, y + 5, 1, 1, K(0xFF141822))
            if (x - l < 6 || r - x < 6) rect(c, x, y + 2, 1, 3, if (((x + y) / 3) % 2 == 0) K(0xFFE8C030) else K(0xFF141414))   // hazard ends
            if (x % 24 == 0) { rect(c, x, y + 6, 1, 6, K(0xFF2A3044)) }                                                           // struts
        }
    }
    private fun ladder(c: Canvas, f: Int, lx: Float) {
        val x = lx.roundToInt(); val y0 = world.floorY(f, lx).roundToInt(); val y1 = world.floorY(f + 1, lx).roundToInt() + 2
        rect(c, x - 5, y1, 1, y0 - y1, K(0xFF8A94A8)); rect(c, x + 4, y1, 1, y0 - y1, K(0xFF8A94A8))
        for (y in y1 + 2 until y0 step 4) rect(c, x - 4, y, 8, 1, K(0xFF5A6478))
    }

    private fun tower(c: Canvas, clock: Float) {
        val t = world.t
        val st = world.stage
        c.save()
        // the intro zooms out from the door you just kicked in
        if (st == FinaleWorld.Stage.INTRO) { val k = (t / 1.8f).coerceIn(0f, 1f); val e = 1f - (1f - k) * (1f - k) * (1f - k); val s = 2.6f - 1.6f * e; c.scale(s, s, 14f, 340f) }
        if (st == FinaleWorld.Stage.INTRO && t in 6f..7.5f) { val sh = ((clock * 50f).toInt() % 3 - 1) * 2; c.translate(sh.toFloat(), 0f) }
        if (world.shotFlash) c.translate(2f, 1f)
        c.drawBitmap(towerBg, 0f, 0f, plain)
        // blinking lights on the racks
        for (f in 0 until FinaleWorld.FLOORS) { val yb = FinaleWorld.base(f).toInt() - 6; val yt = FinaleWorld.base(f + 1).toInt() + 8; var x = 4; var n = 0; while (x < W - 16) { for (u in yt + 4 until yb - 3 step 4) { val on = hash(x + n, u, (clock * 3f).toInt() + n) % 5 != 0; px(c, x + 14, u + 1, if (on) (if ((x + u) % 3 == 0) K(0xFF3BFF9A) else PX.CYAN) else K(0xFF0A2A2A)); if (hash(x, u, (clock * 7f).toInt()) % 9 == 0) px(c, x + 12, u + 1, PX.WARM) }; x += 22; n++ } }
        for (f in 0 until FinaleWorld.FLOORS) c.drawBitmap(coldGlow, 128f - 42f + (f % 2) * 60f - 30f, FinaleWorld.base(f + 1) - 10f, plain)
        // catwalks and ladders
        for ((f, lx) in FinaleWorld.LADDERS) ladder(c, f, lx)
        for (i in 0..FinaleWorld.TOP) catwalk(c, i)
        // sparks as the catwalks clank over
        if (st == FinaleWorld.Stage.INTRO && t in 6f..7.6f) for (i in 1 until FinaleWorld.FLOORS) for (e in 0..1) { val x = if (e == 0) FinaleWorld.spanL(i) else FinaleWorld.spanR(i); val y = world.floorY(i, x); for (k in 0 until 3) { val ph = (clock * 9f + k * 0.3f + i) % 1f; px(c, (x + (k - 1) * 4 * ph).toInt(), (y + ph * 10f).toInt(), if (k == 1) PX.W else PX.WARM) } }
        // the terminal screen (typing the code)
        val ty = FinaleWorld.base(FinaleWorld.TOP).toInt() - 16
        if (st == FinaleWorld.Stage.INTRO && t in 4.7f..6.1f) { for (i in 0 until ((t - 4.7f) * 5).toInt().coerceAtMost(4)) rect(c, 96 + i * 3, ty + 3, 2, 3, K(0xFF3BFF9A)) } else rect(c, 96, ty + 3, 12, 1, a(PX.CYAN, 0x80))
        // spools, and a sparkle where you cleared one
        for (s in world.spools) spool(c, s.x, s.y, s.spin)
        if (st == FinaleWorld.Stage.CLIMB && world.clearT < 0.6f) { val k = world.clearT / 0.6f; for (i in 0 until 6) { val an = i * 1.047f + k; val r = 4f + k * 10f; px(c, (world.clearX + cos(an) * r).roundToInt(), (world.clearY + sin(an) * r - k * 6f).roundToInt(), a(PX.GOLD, ((1f - k) * 255).toInt())) } }
        heads.clear()
        // the patrol
        val gxI = world.gx.roundToInt(); val gyI = world.gy.roundToInt()
        when (world.gPose) {
            1 -> folk(c, gxI, gyI, 0, 1 + ((clock * 14f).toInt() % 2), patrol, false, false)
            2 -> folk(c, gxI, gyI, 0, 0, patrol, false, false)
            3 -> {
                folk(c, gxI, gyI, 1, 0, patrol, false, false)
                if (st == FinaleWorld.Stage.CLIMB && world.throwT < 0.35f) { rect(c, gxI + 2, gyI - 14, 6, 2, patrol.shirt); spool(c, gxI + 10f, gyI - 6f, clock * 10f) }
                else spool(c, gxI - 1f, gyI - 22f, 0f).also { rect(c, gxI - 6, gyI - 20, 2, 6, patrol.shirt); rect(c, gxI + 5, gyI - 20, 2, 6, patrol.shirt) }
            }
            4 -> { folk(c, gxI, gyI + 4, 2, 0, patrol, false, false, 4); rect(c, gxI - 7, gyI - 18, 2, 6, patrol.skin); rect(c, gxI + 6, gyI - 18, 2, 6, patrol.skin) }
            5 -> { lying(c, gxI, gyI, patrol, true, false); rect(c, gxI - 16, gyI - 2, 5, 3, K(0xFF7A0F1E)) }
            else -> folk(c, gxI, gyI, if (world.gFace) 1 else 3, 0, patrol, false, false)
        }
        heads["patrol"] = gxI to gyI - 20
        // you
        val pxI = world.px.roundToInt(); val pyI = (world.py - world.jumpH).roundToInt()
        if (st == FinaleWorld.Stage.CLIMB && world.hitT >= 0f) lying(c, pxI, world.py.roundToInt(), you, world.faceRight, false)
        else if (world.onLadder != null) folk(c, pxI, pyI, 0, 1 + ((world.py / 4f).toInt() % 2), you, false, false)
        else {
            folk(c, pxI, pyI, if (world.faceRight) 1 else 3, if (world.walking) 1 + ((clock * 10f).toInt() % 2) else if (world.jumpT >= 0f) 1 else 0, you, false, false)
            if (world.pistolUp) {
                val dir = if (world.faceRight) 1 else -1
                rect(c, pxI + dir * 2 - (if (dir < 0) 8 else 0), pyI - 11, 8, 2, you.shirt); rect(c, pxI + dir * 10 - (if (dir < 0) 3 else 0), pyI - 12, 4, 3, PX.K)
                if (world.shotFlash) { rect(c, pxI + dir * 15 - 3, pyI - 14, 6, 6, PX.WARM); rect(c, pxI + dir * 15 - 1, pyI - 12, 3, 3, PX.W) }
            }
        }
        heads["you"] = pxI to pyI - 20
        c.restore()
        if (world.shotFlash) { p.style = Paint.Style.FILL; p.color = a(PX.W, 0x60); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
    }

    // =====================================================================================================================
    //  the door
    // =====================================================================================================================
    private val doorBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, 300, intArrayOf(K(0xFF0A0E18), K(0xFF121A2C), K(0xFF0A0E18)), 8f)
        gradient(px, W, 300, H, intArrayOf(K(0xFF141A28), K(0xFF06080E)), 6f)
        for (y in 0 until 300) for (x in 0 until W) if ((x % 32 == 0) || (y % 48 == 0)) px[y * W + x] = K(0xFF0A0E16)
        bm.setPixels(px, 0, W, 0, 0, W, H); bm
    }
    private fun door(c: Canvas, clock: Float) {
        val t = world.t
        c.drawBitmap(doorBg, 0f, 0f, plain)
        val open = ((t - 0.8f) / 3.6f).coerceIn(0f, 1f); val e = open * open * (3 - 2 * open)
        val dx0 = 70; val dx1 = 186; val dy0 = 60; val dy1 = 300
        // the light behind the door
        rect(c, dx0, dy0, dx1 - dx0, dy1 - dy0, K(0xFF020306))
        if (e > 0f) {
            val gap = ((dx1 - dx0) * e).toInt()
            for (y in dy0 until dy1) for (x in dx1 - gap until dx1) { val k = (x - (dx1 - gap)) / max(1f, gap.toFloat()); val q = floor((0.55f + 0.45f * k) * e * 5f + dith(x, y)) / 5f; px(c, x, y, mix(K(0xFF2A4A6A), K(0xFFE8F2FF), q)) }
            // the light spills onto the floor in a widening wedge
            for (y in dy1 until H) { val spread = (y - dy1) * 0.9f; val x0 = (dx1 - gap - spread * 0.4f).toInt(); val x1 = (dx1 + spread * 0.2f).toInt(); for (x in x0 until x1) if (bayer[(y and 3) * 4 + (x and 3)] < (10 * e).toInt() + 2) px(c, x, y, a(K(0xFFA8C8F0), 0x90)) }
            for (k in 0 until 14) { val mx = dx1 - (gap * ((hash(k, 1, 5) % 100) / 100f)).toInt(); val my = dy0 + (hash(k, 2, 5) % (dy1 - dy0)) + (sin(clock + k) * 6f).toInt(); px(c, mx, my, a(PX.W, 0xA0)) }
        }
        // the door itself, swinging in (seen narrower as it turns)
        val dw = ((dx1 - dx0) * (1f - e)).toInt()
        if (dw > 0) {
            rect(c, dx0, dy0, dw, dy1 - dy0, K(0xFF3A424E)); rect(c, dx0 + 2, dy0 + 2, max(0, dw - 4), dy1 - dy0 - 4, K(0xFF4E5866))
            if (dw > 30) { rect(c, dx0 + dw / 2 - 18, dy0 + 40, 36, 8, K(0xFF14181E)); rect(c, dx0 + dw / 2 - 16, dy0 + 42, 32, 4, K(0xFF3A6AA0)) }   // CONTROL plate
            rect(c, dx0 + dw - 8, dy0 + 120, 4, 14, K(0xFFB0A060))
        }
        rect(c, dx0 - 6, dy0 - 6, dx1 - dx0 + 12, 6, K(0xFF2A303C)); rect(c, dx0 - 6, dy0 - 6, 6, dy1 - dy0 + 6, K(0xFF2A303C)); rect(c, dx1, dy0 - 6, 6, dy1 - dy0 + 6, K(0xFF2A303C))
        // your hand on it
        if (t < 3.4f) { val hx = dx0 + (dw * 0.85f).toInt(); rect(c, hx - 6, 186, 14, 10, PX.SKIN); rect(c, hx - 30, 190, 24, 8, you.shirt) }
    }

    // =====================================================================================================================
    //  the control room over the city
    // =====================================================================================================================
    private val WX0 = 100; private val WX1 = 250; private val WY0 = 26; private val WY1 = 238; private val FLOOR = 304
    private val cityBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, H, intArrayOf(K(0xFF0A0614), K(0xFF2A0E26), K(0xFF5A1A26), K(0xFF8A3A2A)), 12f)
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        skyline(c, 236, WX0, WX1, 81, 90, K(0xFF120A16), 0.3f)
        skyline(c, 238, WX0 - 10, WX1, 82, 50, K(0xFF080610), 0.15f)
        bm
    }
    private val roomBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, FLOOR, intArrayOf(K(0xFF0C1020), K(0xFF121830), K(0xFF0A0E1C)), 8f)
        gradient(px, W, FLOOR, H, intArrayOf(K(0xFF1A2236), K(0xFF070910)), 8f)
        for (y in FLOOR until H) for (x in 0 until W) if ((x + (y - FLOOR) * 2) % 40 == 0) px[y * W + x] = K(0xFF242E46)
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        // the console at the right
        rect(c, 196, 262, 58, 42, K(0xFF14181E)); rect(c, 196, 262, 58, 3, K(0xFF4A5C86)); rect(c, 200, 268, 50, 10, K(0xFF0A1424))
        rect(c, 230, 240, 3, 22, K(0xFF2A2E38)); rect(c, 228, 236, 7, 5, K(0xFF14181E))                      // a microphone
        bm
    }
    private val fireGlow by lazy { glowBitmap(24, K(0xFFFF7A2A), 0x70) }
    private val sirenR by lazy { glowBitmap(30, PX.RED, 0x60) }
    private val sirenB by lazy { glowBitmap(30, PX.BLUE, 0x60) }
    private val bigRed by lazy { glowBitmap(140, PX.RED, 0x70) }
    private val bigBlue by lazy { glowBitmap(140, PX.BLUE, 0x70) }
    private val spotCone: Bitmap by lazy {
        val len = 200; val wid = 60
        val bm = Bitmap.createBitmap(len, wid, Bitmap.Config.ARGB_8888); val px = IntArray(len * wid)
        for (y in 0 until wid) for (x in 0 until len) { val half = 2f + x * 0.13f; val ac = abs(y - wid / 2f); if (ac > half) continue; val v = (1f - x / len.toFloat()) * 0.6f + 0.4f * (1f - ac / half); val q = floor(v * 4f + dith(x, y)) / 4f; if (q > 0f) px[y * len + x] = a(K(0xFFDCEBFF), (q * 0x50).toInt()) }
        bm.setPixels(px, 0, len, 0, 0, len, wid); bm
    }

    private fun cityNow(c: Canvas, clock: Float, bombT: Float, sirens: Float) {
        c.save(); c.clipRect(WX0, WY0, WX1, WY1)
        c.drawBitmap(cityBg, 0f, 0f, plain)
        // searchlights from the streets
        for (k in 0 until 3) { c.save(); val ang = -90f + sin(clock * 0.4f + k * 2f) * 35f; c.rotate(ang, 120f + k * 50f, 238f); c.drawBitmap(spotCone, 120f + k * 50f, 238f - 30f, plain); c.restore() }
        // fires and smoke
        for ((fx, fy) in listOf(130 to 220, 205 to 214, 236 to 226)) {
            c.drawBitmap(fireGlow, fx - 24f, fy - 24f, plain)
            for (i in 0 until 5) { val ph = (clock * 0.3f + i * 0.2f) % 1f; rect(c, fx - 4 + (sin(ph * 6f + i) * 4).toInt(), fy - (ph * 60).toInt(), 6 + (ph * 8).toInt(), 4, a(K(0xFF1A1418), ((1f - ph) * 0xA0).toInt())) }
            if ((clock * 10f).toInt() % 3 != 0) { rect(c, fx - 2, fy - 4, 4, 4, K(0xFFFFB04A)); px(c, fx, fy - 6, K(0xFFFFE8A0)) }
        }
        // red and blue down on the streets
        val flip = (clock * 4f).toInt() % 2 == 0
        for ((sx, sy) in listOf(140 to 234, 176 to 236, 220 to 233)) c.drawBitmap(if (flip xor (sx % 2 == 0)) sirenR else sirenB, sx - 30f, sy - 30f, plain)
        // lasers sweeping from the rooftops
        for (k in 0 until 3) { val bx = 150 + k * 34; val by = 160 + k * 10; val ang = -PI.toFloat() / 2 + sin(clock * 0.7f + k) * 0.7f; for (i in 0 until 90) px(c, bx + (cos(ang) * i).toInt(), by + (sin(ang) * i).toInt(), a(if (k == 1) PX.CYAN else PX.RED, 0xB0 - i)) }
        // drones
        for (k in 0 until 4) { val dx = ((clock * (8f + k * 3f) + k * 60f) % (WX1 - WX0 + 40)).toInt() + WX0 - 20; val dy = 60 + k * 26 + (sin(clock * 2f + k) * 4f).toInt(); rect(c, dx - 3, dy, 7, 2, K(0xFF14141A)); px(c, dx - 3, dy - 1, K(0xFF14141A)); px(c, dx + 3, dy - 1, K(0xFF14141A)); if ((clock * 3f + k).toInt() % 2 == 0) px(c, dx, dy + 2, if (k % 2 == 0) PX.RED else K(0xFF3BFF9A)) }
        // the bomb
        if (bombT >= 0f && bombT < 6f) {
            val bx = 182; val by = 200
            if (bombT < 0.25f) { p.color = a(PX.W, 0xE0); c.drawRect(WX0.toFloat(), WY0.toFloat(), WX1.toFloat(), WY1.toFloat(), p) }
            val r = (bombT * 40f).coerceAtMost(46f)
            for (k in 0 until 30) { val an = k * 0.21f; val rr = r * (0.7f + 0.3f * ((k * 7) % 3) / 2f); rect(c, bx + (cos(an) * rr).toInt() - 3, by - (abs(sin(an)) * rr).toInt() - 3, 6, 6, a(K(0xFFFF8A2A), ((1f - bombT / 6f) * 0xE0).toInt())) }
            rect(c, bx - (r / 2).toInt(), by - (r * 0.9f).toInt(), r.toInt(), (r * 0.5f).toInt(), a(K(0xFFFFE0A0), ((1f - bombT / 3f).coerceAtLeast(0f) * 0xC0).toInt()))
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = a(PX.W, ((1f - bombT / 2f).coerceAtLeast(0f) * 0xA0).toInt()); c.drawOval(bx - bombT * 70f, by - bombT * 14f, bx + bombT * 70f, by + bombT * 14f, p); p.style = Paint.Style.FILL
        }
        c.restore()
        // the end: the red and blue outside grow until they fill the room
        if (sirens > 0f) { val flip2 = (clock * 3f).toInt() % 2 == 0; val g = if (flip2) bigRed else bigBlue; p.alpha = (sirens * 255).toInt(); c.drawBitmap(g, 175f - 140f, 150f - 140f, p); p.alpha = 255 }
        // the window frame
        rect(c, WX0 - 4, WY0 - 4, WX1 - WX0 + 8, 4, K(0xFF1A2030)); rect(c, WX0 - 4, WY1, WX1 - WX0 + 8, 6, K(0xFF1A2030))
        for (mx in intArrayOf(WX0 - 4, 150, 200, WX1)) rect(c, mx, WY0, 4, WY1 - WY0, K(0xFF1A2030))
        rect(c, WX0 - 4, WY0 - 4, WX1 - WX0 + 8, 1, K(0xFF4A5C86))
    }

    /** The wall of screens on the left: CCTV of the city, or the broadcast, or static, or black. */
    private fun screens(c: Canvas, clock: Float, mode: Int) {   // 0 cctv, 1 broadcast, 2 static, 3 off
        for (row in 0 until 6) for (col in 0 until 3) {
            val x = 8 + col * 30; val y = 30 + row * 34
            rect(c, x - 1, y - 1, 28, 24, K(0xFF05070C)); rect(c, x, y, 26, 22, K(0xFF0A1220))
            when (mode) {
                0 -> {
                    val k = row * 3 + col
                    rect(c, x, y + 14, 26, 8, K(0xFF141E2E))
                    for (i in 0 until 3) { val mx = x + ((clock * (6 + i * 3 + k) + k * 9 + i * 7) % 26).toInt(); rect(c, mx, y + 13 + i % 2 * 3, 2, 3, if ((k + i) % 3 == 0) K(0xFFE8A8A8) else K(0xFF6A8AA8)) }
                    rect(c, x + 2, y + 2, 10, 6 + k % 3 * 2, K(0xFF1A2638)); rect(c, x + 15, y + 4, 8, 9, K(0xFF1A2638))
                    if ((clock * 2f + k).toInt() % 2 == 0) px(c, x + 23, y + 2, PX.RED)
                    for (sl in y until y + 22 step 2) rect(c, x, sl, 26, 1, a(PX.K, 0x40))
                    if (hash(k, (clock * 4f).toInt(), 3) % 23 == 0) rect(c, x, y, 26, 22, a(PX.W, 0x50))
                }
                1 -> { rect(c, x, y, 26, 22, K(0xFF2A0A12)); rect(c, x + 2, y + 2, 22, 4, PX.RED); mayorHead(c, x + 8, y + 10); for (i in 0 until 3) rect(c, x + 16, y + 9 + i * 3, 8, 1, K(0xFFE8E4D4)) }
                2 -> for (yy in y until y + 22) for (xx in x until x + 26) if (hash(xx, yy, (clock * 30f).toInt()) % 3 == 0) px(c, xx, yy, K(0xFF8A90A0))
                else -> {}
            }
        }
    }
    private fun mayorHead(c: Canvas, x: Int, y: Int) {
        rect(c, x - 3, y + 4, 8, 6, K(0xFF3A3A4E)); rect(c, x, y + 4, 2, 4, PX.RED)
        rect(c, x - 2, y - 2, 6, 6, mayorSkin); rect(c, x - 2, y - 3, 6, 2, K(0xFFB8B8C0)); px(c, x - 1, y, PX.K); px(c, x + 2, y, PX.K)
    }

    private fun control(c: Canvas, clock: Float) {
        val t = world.t
        val end = world.stage == FinaleWorld.Stage.END
        val bombT = if (!end) t - 6.5f else -1f
        val shake = if (bombT in 0f..0.8f) ((clock * 50f).toInt() % 3 - 1) * 2 else 0
        c.save(); c.translate(shake.toFloat(), 0f)
        c.drawBitmap(roomBg, 0f, 0f, plain)
        cityNow(c, clock, bombT, world.sirens)
        screens(c, clock, if (!end) 0 else if (t < 2.4f) 1 else if (t < 2.9f) 2 else 3)
        // the window's glow on the floor
        for (y in FLOOR + 2 until H step 2) { val k = (y - FLOOR) / (H - FLOOR).toFloat(); val x0 = WX0 + (k * 20).toInt(); for (x in x0 until WX1 - (k * 10).toInt()) if (bayer[(y and 3) * 4 + (x and 3)] < 3) px(c, x, y, a(K(0xFF8A3A3A), (0x60 * (1f - k)).toInt())) }
        // console lights
        for (i in 0 until 10) px(c, 202 + i * 5, 272, if ((clock * 3f + i).toInt() % 3 == 0) K(0xFF3BFF9A) else PX.CYAN)
        // you: walk in past the window, stop and look, then on to the panel
        val x: Float; val dir: Int; val fr: Int
        if (end) { x = 214f; dir = 0; fr = 0 }
        else when {
            t < 5f -> { x = 10f + (150f - 10f) * (t / 5f); dir = 1; fr = 1 + ((clock * 7f).toInt() % 2) }
            t < 10.2f -> { x = 150f; dir = if (t < 5.4f) 1 else 0; fr = 0 }
            t < 14f -> { x = 150f + (214f - 150f) * ((t - 10.2f) / 3.8f); dir = 1; fr = 1 + ((clock * 7f).toInt() % 2) }
            else -> { x = 214f; dir = 0; fr = 0 }
        }
        val xi = x.roundToInt()
        rect(c, xi - 12, FLOOR - 2, 24, 4, a(PX.K, 0x80))
        big(xi, FLOOR) { folk(c, xi, FLOOR, dir, fr, you, false, false) }
        c.restore()
        // the end: the screens go dark, then the words come back to just you
        if (end && t > 3.6f) {
            p.typeface = android.graphics.Typeface.MONOSPACE; p.textSize = 8f; p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
            rect(c, 12, 120, W - 24, 70, a(K(0xFF02040A), 0xD0)); rect(c, 12, 120, W - 24, 1, a(K(0xFF4A5C86), 0xC0)); rect(c, 12, 189, W - 24, 1, a(K(0xFF4A5C86), 0xC0))
            var y = 140
            for ((line, at) in world.endLines()) {
                if (t >= at) {
                    val shown = ((t - at) * 26f).toInt().coerceAtMost(line.length)
                    p.color = if (line.startsWith("TRANSMISSION")) K(0xFF3BFF9A) else PX.W
                    c.drawText(line.take(shown) + (if (shown < line.length && (clock * 4f).toInt() % 2 == 0) "_" else ""), W / 2f, y.toFloat(), p)
                }
                y += 18
            }
            p.typeface = null
        }
        if (world.blackout > 0f) { p.style = Paint.Style.FILL; p.color = a(PX.K, (world.blackout * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
    }

    // =====================================================================================================================
    //  the uplink: Simon
    // =====================================================================================================================
    val simonRects = listOf(intArrayOf(22, 96, 102, 102), intArrayOf(132, 96, 102, 102), intArrayOf(22, 206, 102, 102), intArrayOf(132, 206, 102, 102))
    private val simonCols = intArrayOf(K(0xFF29F0FF), K(0xFFFF3B55), K(0xFFFFD84D), K(0xFFB06AFF))
    /** Which button (0..3) is at this point of the picture, or -1. */
    fun simonButtonAt(x: Float, y: Float): Int { for ((i, r) in simonRects.withIndex()) if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return i; return -1 }
    private val simonBg: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        gradient(px, W, 0, H, intArrayOf(K(0xFF060912), K(0xFF0C1428), K(0xFF060912)), 8f)
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        // circuit traces
        for (k in 0 until 26) { var x = hash(k, 1, 9) % W; var y = hash(k, 2, 9) % H; repeat(6) { val horiz = hash(k, it, 10) % 2 == 0; val len = 10 + hash(k, it, 11) % 30; if (horiz) rect(c, x, y, len, 1, K(0xFF14223A)) else rect(c, x, y, 1, len, K(0xFF14223A)); if (horiz) x += len else y += len; px(c, x, y, K(0xFF2A4A6A)) } }
        rect(c, 10, 18, W - 20, 54, K(0xFF0A0E18)); rect(c, 10, 18, W - 20, 1, K(0xFF4A5C86)); rect(c, 10, 71, W - 20, 1, K(0xFF4A5C86))
        bm
    }
    private val btnGlow by lazy { simonCols.map { glowBitmap(70, it, 0x70) } }
    private fun simon(c: Canvas, clock: Float) {
        c.drawBitmap(simonBg, 0f, 0f, plain)
        p.typeface = android.graphics.Typeface.MONOSPACE; p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        p.textSize = 9f; p.color = PX.CYAN; c.drawText("UPLINK  //  EMERGENCY BROADCAST", W / 2f, 38f, p)
        p.textSize = 7f; p.color = a(PX.W, 0x99); c.drawText("ALL CHANNELS  ·  ALL FREQUENCIES  ·  STATEWIDE", W / 2f, 52f, p)
        // signal bars
        for (i in 0 until 5) rect(c, 206 + i * 6, 64 - i * 2, 4, 2 + i * 2, if (i < 1 + world.round / 2) K(0xFF3BFF9A) else K(0xFF1A2A2A))
        for ((i, r) in simonRects.withIndex()) {
            val on = world.lit == i
            val col = simonCols[i]
            if (on) c.drawBitmap(btnGlow[i], r[0] + r[2] / 2f - 70f, r[1] + r[3] / 2f - 70f, plain)
            rect(c, r[0], r[1], r[2], r[3], PX.K)
            rect(c, r[0] + 2, r[1] + 2, r[2] - 4, r[3] - 4, if (on) col else mix(col, K(0xFF06080E), 0.78f))
            rect(c, r[0] + 2, r[1] + 2, r[2] - 4, 2, if (on) PX.W else mix(col, K(0xFF06080E), 0.6f))
            for (yy in r[1] + 6 until r[1] + r[3] - 4 step 6) rect(c, r[0] + 6, yy, r[2] - 12, 1, a(PX.K, if (on) 0x30 else 0x50))
        }
        // progress: ten lights
        for (i in 0 until FinaleWorld.SIMON_GOAL) { val x = 38 + i * 19; val done = i < world.round - 1 || (i == world.round - 1 && !world.showing && world.inputAt > 0 && false); rect(c, x, 330, 12, 6, PX.K); rect(c, x + 1, 331, 10, 4, if (i < world.round) (if (done) K(0xFF3BFF9A) else K(0xFF2A6A4A)) else K(0xFF14181E)) }
        for (i in 0 until world.inputAt) rect(c, 38 + i * 19 + 3, 338, 6, 2, PX.W)
        p.typeface = null
    }

    // =====================================================================================================================
    //  the broadcast going out
    // =====================================================================================================================
    private val vignettes: List<Bitmap> by lazy { (0 until FinaleWorld.VIGNETTES).map { buildVignette(it) } }
    private fun buildVignette(i: Int): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val px = IntArray(W * H)
        when (i) {
            0, 1, 2 -> gradient(px, W, 0, H, intArrayOf(K(0xFF0A0C16), K(0xFF141828), K(0xFF07080E)), 8f)
            else -> { gradient(px, W, 0, 260, intArrayOf(K(0xFF05070F), K(0xFF1A1A3A), K(0xFF4A2A48)), 10f); stars(px, W, 120, 70 + i); gradient(px, W, 260, H, intArrayOf(K(0xFF1A1A28), K(0xFF07080E)), 6f) }
        }
        bm.setPixels(px, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        when (i) {
            0 -> {   // a living room: a family on the sofa in front of the TV
                rect(c, 20, 40, 60, 80, K(0xFF101830)); skyline(c, 120, 20, 80, 90, 40, K(0xFF080A18), 0.2f); for (x in intArrayOf(20, 49, 79)) rect(c, x, 40, 2, 80, K(0xFF2A2420))
                rect(c, 150, 150, 92, 66, K(0xFF0A0A0E)); rect(c, 146, 216, 100, 30, K(0xFF2A1E16))                       // TV and its stand
                rect(c, 0, 280, W, 104, K(0xFF2A2030))                                                                      // the floor
                rect(c, 196, 40, 40, 90, K(0xFF2A1E16)); for (k in 0 until 4) for (b in 0 until 6) rect(c, 199 + b * 6, 44 + k * 22, 4, 16, BOOK[(k * 6 + b) % BOOK.size])   // a bookshelf
                rect(c, 0, 300, W, 3, K(0xFF3A2E40))
            }
            1 -> {   // a diner at night, the radio on the shelf
                rect(c, 0, 250, W, 30, K(0xFF5A2A2A)); rect(c, 0, 250, W, 3, K(0xFFB8B0A0)); rect(c, 0, 280, W, 104, K(0xFF2A2A34))
                for (x in 20 until W step 40) { rect(c, x, 292, 14, 4, K(0xFF8A2A2A)); rect(c, x + 6, 296, 2, 24, K(0xFF8A929A)) }
                rect(c, 150, 120, 80, 4, K(0xFF4A3220)); rect(c, 170, 96, 40, 24, K(0xFF6A4A2E)); rect(c, 174, 100, 20, 16, K(0xFF2A1A10)); for (k in 0..2) px(c, 200, 102 + k * 5, K(0xFFE8C880))
                rect(c, 10, 150, 110, 60, K(0xFF0A0A10)); rect(c, 12, 152, 106, 56, K(0xFF2A2018)); rect(c, 30, 170, 20, 12, K(0xFF8A8A92)); rect(c, 70, 166, 30, 4, K(0xFF5A5A62))   // the kitchen hatch
                p.typeface = android.graphics.Typeface.MONOSPACE; p.textSize = 16f; p.textAlign = Paint.Align.CENTER; p.color = K(0xFFFF38D6); c.drawText("DINER", 70f, 70f, p); p.typeface = null
            }
            2 -> {   // the police station: desks, officers stopped in front of the wall TV
                rect(c, 70, 60, 120, 76, K(0xFF0A0A0E)); rect(c, 0, 260, W, 124, K(0xFF1A2030))
                for (x in intArrayOf(20, 140)) { rect(c, x, 230, 90, 30, K(0xFF3A3020)); rect(c, x, 230, 90, 3, K(0xFF5A4A30)) }
                rect(c, 214, 40, 34, 60, K(0xFF101830))
                for (k in 0 until 3) { rect(c, 10 + k * 70, 150, 50, 30, K(0xFF242C3E)); rect(c, 12 + k * 70, 152, 46, 4, K(0xFFE8E4D4)) }   // notice boards
            }
            3 -> {   // downtown: the big screens, a crowd with phones up, rain
                skyline(c, 270, 0, W, 91, 160, K(0xFF0A0A16), 0.3f)
                rect(c, 40, 90, 120, 70, K(0xFF050508)); rect(c, 170, 130, 70, 50, K(0xFF050508))
                rect(c, 0, 270, W, 114, K(0xFF14141E))
            }
            else -> {   // the state capitol
                rect(c, 40, 190, 176, 70, K(0xFF14141E)); rect(c, 40, 190, 176, 2, K(0xFF4A4A6A))
                for (yy in 0 until 40) { val half = (sqrt01(1f - (yy / 40f) * (yy / 40f)) * 40).toInt(); rect(c, 128 - half, 190 - yy, half * 2, 1, K(0xFF1A1A28)) }
                rect(c, 126, 130, 4, 20, K(0xFF1A1A28)); rect(c, 130, 130, 12, 7, K(0xFF8A2A2A))
                for (x in 50 until 210 step 14) rect(c, x, 196, 4, 60, K(0xFF242436))
                for ((vx, vy) in listOf(20 to 330, 200 to 336)) { rect(c, vx, vy - 20, 40, 20, K(0xFFD8D8E0)); rect(c, vx + 26, vy - 34, 2, 14, K(0xFF8A8A9A)); rect(c, vx + 20, vy - 40, 12, 4, K(0xFFB8B8C8)) }
            }
        }
        return bm
    }
    private val BOOK = intArrayOf(K(0xFF6A2A2A), K(0xFF2A3A6A), K(0xFF3A5A3A), K(0xFF6A5A2A), K(0xFF4A2A5A))
    private val folks = listOf(
        Outfit(PX.SKIN, 0xFF3A2A1E.toInt(), null, null, 0xFFE8E0C8.toInt(), null, 0xFF3A4058.toInt(), PX.K, null),
        Outfit(0xFFC89A78.toInt(), 0xFF1A1420.toInt(), null, null, 0xFF8A3A4A.toInt(), 0xFF3A3040.toInt(), 0xFF2A3350.toInt(), PX.K, null),
        Outfit(0xFF8A6048.toInt(), 0xFF0E0A0A.toInt(), null, null, 0xFFB0C8E8.toInt(), null, 0xFF4A3A58.toInt(), PX.K, null, glasses = true),
        Outfit(PX.SKIN, 0xFF8A8A92.toInt(), null, null, 0xFF5A7A5A.toInt(), 0xFF4A4A3A.toInt(), 0xFF3A3A44.toInt(), PX.K, null),
        Outfit(0xFFA87858.toInt(), 0xFF6A3A1A.toInt(), null, null, 0xFFC8B048.toInt(), null, 0xFF2A2A3A.toInt(), PX.K, null),
    )
    private val cook = Outfit(PX.SKIN, 0xFF2A1A10.toInt(), PX.W, null, PX.W, null, 0xFF3A3A44.toInt(), PX.K, null, apron = PX.W)
    private val waitress = Outfit(0xFFC89A78.toInt(), 0xFF8A3A1A.toInt(), null, null, 0xFF7AB8C8.toInt(), null, 0xFF7AB8C8.toInt(), PX.K, null, apron = PX.W)
    private val copNoHat = Outfit(PX.SKIN, 0xFF1A1420.toInt(), null, null, 0xFF2F58C8.toInt(), null, 0xFF0E1736.toInt(), PX.K, PX.K, badge = true)
    /** Tints whatever is drawn next by a light colour (TV blue, neon pink...). Call again with 0 to stop. */
    private fun tint(col: Int) { p.colorFilter = if (col == 0) null else android.graphics.PorterDuffColorFilter(col, PorterDuff.Mode.MULTIPLY) }

    private fun sqrt01(v: Float) = kotlin.math.sqrt(max(0f, v))
    private val tvGlow by lazy { glowBitmap(90, K(0xFF6A9AFF), 0x50) }
    private val flashBurst by lazy { glowBitmap(12, PX.W, 0xE0) }
    private val neonGlow by lazy { glowBitmap(40, PX.MAG, 0x40) }

    private fun broadcast(c: Canvas, clock: Float) {
        val i = (world.t / FinaleWorld.VIGNETTE_S).toInt().coerceAtMost(FinaleWorld.VIGNETTES - 1)
        val lt = world.t - i * FinaleWorld.VIGNETTE_S
        c.drawBitmap(vignettes[i], 0f, 0f, plain)
        // what's on the screens: the mayor, the documents, BREAKING
        fun tv(x: Int, y: Int, w: Int, h: Int) {
            rect(c, x, y, w, h, K(0xFF2A0A12)); rect(c, x, y, w, h / 6, PX.RED)
            p.typeface = android.graphics.Typeface.MONOSPACE; p.textSize = max(5f, h / 8f); p.textAlign = Paint.Align.LEFT; p.color = PX.W; c.drawText("BREAKING", x + 3f, y + h / 6f - 2f, p); p.typeface = null
            c.save(); c.scale(w / 30f, w / 30f, x.toFloat(), y.toFloat()); mayorHead(c, x + 8, y + 10); c.restore()
            for (k in 0 until 4) rect(c, x + w / 2 + 2, y + h / 3 + k * (h / 8), w / 2 - 6, max(1, h / 20), K(0xFFE8E4D4))
            if ((clock * 2f).toInt() % 2 == 0) { rect(c, x + w / 2 + 2, y + h - h / 4, w / 2 - 6, h / 7, PX.RED) }
            for (sl in y until y + h step 2) rect(c, x, sl, w, 1, a(PX.K, 0x30))
        }
        when (i) {
            0 -> {
                c.drawBitmap(tvGlow, 196f - 90f, 183f - 90f, plain); tv(154, 154, 84, 58)
                // the family on the sofa, seen from behind, lit blue by the TV; the kid leans forward
                tint(K(0xFF8AA0E0))
                for ((k, hx) in listOf(44, 82, 120).withIndex()) { val lean = if (k == 1 && lt > 1.2f) 3 else 0; big(hx, 280 - lean) { folk(c, hx, 280 - lean, 0, 0, folks[k], false, false) } }
                tint(0)
                rect(c, 16, 262, 142, 40, K(0xFF3A2A3A)); rect(c, 16, 262, 142, 3, K(0xFF5A4A6A)); rect(c, 10, 256, 12, 46, K(0xFF2E2030)); rect(c, 152, 256, 12, 46, K(0xFF2E2030))   // the sofa back
                for (x in 18 until 156) if (hash(x, 0, 3) % 4 == 0) px(c, x, 262, K(0xFF7A8AC0))                                                          // TV light on its edge
            }
            1 -> {
                // the cook stops at the hatch; at the counter they all turn to the radio; the waitress stands with the pot
                big(60, 196) { folk(c, 60, 196, 2, 0, cook, false, false) }
                // the waitress behind the counter with the coffee pot, frozen; the customers on our side turn to the radio
                tint(K(0xFFE0B0D0)); big(150, 262) { folk(c, 150, 262, 1, 0, waitress, false, false) }; tint(0)
                rect(c, 0, 250, W, 30, K(0xFF5A2A2A)); rect(c, 0, 250, W, 3, K(0xFFB8B0A0)); rect(c, 0, 253, W, 1, K(0xFF7A3A3A))
                rect(c, 172, 238, 4, 12, K(0xFF2A2A2A)); rect(c, 170, 236, 8, 4, K(0xFF8A6A3A)); for (k in 0..2) px(c, 173 + k, 232 - ((clock * 8f).toInt() + k) % 5, a(PX.W, 0x80))   // coffee, steaming
                tint(K(0xFFE0B0D0))
                for ((k, hx) in listOf(26, 100, 220).withIndex()) big(hx, 306) { folk(c, hx, 306, if (lt > 0.8f + k * 0.3f) (if (hx < 184) 1 else 3) else 0, 0, folks[k + 1], false, false) }
                tint(0)
                c.drawBitmap(neonGlow, 70f - 40f, 64f - 40f, plain)
 for (k in 0 until 3) { val r = ((clock * 30f + k * 14f) % 42f); p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = a(K(0xFFE8C880), ((1f - r / 42f) * 0xC0).toInt()); c.drawCircle(184f, 108f, r, p); p.style = Paint.Style.FILL }; rect(c, 150, 200, 6, 4, a(PX.W, 0x60)) }
            2 -> {
                c.drawBitmap(tvGlow, 130f - 90f, 98f - 90f, plain); tv(74, 64, 112, 68)
                if ((clock * 3f).toInt() % 2 == 0) rect(c, 216, 42, 30, 56, a(PX.RED, 0x50)) else rect(c, 216, 42, 30, 56, a(PX.BLUE, 0x50))
                // the officers stop and watch; one takes his hat off
                tint(K(0xFFA0B4E8))
                for ((k, hx) in listOf(52, 98, 150, 198).withIndex()) big(hx, 262) { folk(c, hx, 262, 0, 0, if (k == 2 && lt > 1.4f) copNoHat else police, false, false) }
                tint(0)
                if (lt > 1.4f) { rect(c, 162, 246, 10, 4, police.hat!!); rect(c, 161, 249, 12, 1, police.brim!!) }   // the hat in his hand
            }
            3 -> {
                tv(44, 94, 112, 62); tv(174, 134, 62, 42)
                // the crowd, from behind, phones up to film the screens
                val rows = listOf(300 to 22, 322 to 18, 346 to 14)
                for ((ri, rr) in rows.withIndex()) { val (ry, n) = rr; for (k in 0 until n) { val hx = (k * (W / n) + hash(k, ri, 6) % 8 + (if (ri % 2 == 1) 6 else 0)) % W; val bob = if (hash(k, ri, 7) % 3 == 0 && (clock * 2f + k).toInt() % 2 == 0) 1 else 0
                    big(hx, ry - bob, 1f + ri * 0.25f) { tint(K(0xFF9AA0C8)); folk(c, hx, ry - bob, 0, 0, folks[(k + ri) % folks.size], false, false); tint(0)
                        if (hash(k, ri, 8) % 3 == 0) { rect(c, hx + 4, ry - bob - 24, 2, 8, folks[(k + ri) % folks.size].shirt); rect(c, hx + 3, ry - bob - 28, 4, 5, PX.K); rect(c, hx + 4, ry - bob - 27, 2, 3, K(0xFFDCEBFF)) } } } }
                for (k in 0 until 60) { val rx = (hash(k, 1, 7) % W + (clock * 30f).toInt()) % W; val ry = (hash(k, 2, 7) % H + (clock * 220f).toInt() + k * 13) % H; rect(c, rx, ry, 1, 4, a(K(0xFF8AA0C8), 0x60)) }   // rain
            }
            else -> {
                for (k in 0 until 11) { val on = lt > 0.3f + k * 0.18f; rect(c, 50 + k * 14 + 1, 204, 2, 10, if (on) PX.WARM else K(0xFF1A1A28)); rect(c, 50 + k * 14 + 1, 228, 2, 10, if (on && k % 2 == 0) PX.WARM else K(0xFF1A1A28)) }
                // reporters on the steps, cameras flashing
                for (k in 0 until 9) { val hx = 30 + k * 24 + hash(k, 1, 9) % 8; big(hx, 300 + (k % 2) * 6, 1.5f) { folk(c, hx, 300 + (k % 2) * 6, 0, 0, folks[k % folks.size], false, false) }
                    if (hash(k, (clock * 5f).toInt(), 10) % 7 == 0) { c.drawBitmap(flashBurst, hx - 12f, 262f - 12f, plain) } }
            }
        }
        // cross-fade at the start and end of each place
        val edge = min(lt, FinaleWorld.VIGNETTE_S - lt)
        if (edge < 0.3f) { p.style = Paint.Style.FILL; p.color = a(PX.K, ((1f - edge / 0.3f) * 255).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
    }
}
