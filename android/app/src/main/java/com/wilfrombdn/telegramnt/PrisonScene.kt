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
 * The prison in pixel art, in the same moody, dithered style as the back alleys of level 1.
 *
 * How it's made:
 *  1. ALBEDO: the plain colours of everything (floors, painted walls, bars, bunks, tables...) are painted once into one big picture.
 *  2. LIGHT: that picture is lit twice, once for the day and once for the night. Every lamp, window shaft and skylight adds coloured
 *     light that falls off with distance; the total is snapped to a few steps with a 4x4 ordered dither, which gives the soft
 *     dotted pools of light the rest of Dead Letter has. Wall tops are never lit; they get a cold rim line instead.
 *  3. GLOW: things that make their own light are drawn on top unlit: lamp tubes, windows, monitors, stove flames, rim lines.
 *  4. EACH FRAME: the camera (both ways), the doors and laundry, the people, then the moving things: dust in the light shafts, steam,
 *     shower water, a flickering tube, fog rolling over the yard, the tower's searchlight at night, and a dithered vignette.
 */
internal class PrisonScene(private val world: PrisonWorld) : PixelSprites() {
    private val S = 16
    var VW = 16; private set
    var VH = 24; private set
    var W = VW * S; private set
    var H = VH * S; private set
    private var frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private var fc = Canvas(frame)
    /** Where the small map sits from the top (art pixels), below the title bar. */
    var hudTop = 5
    /** Size the view to the screen (in art pixels), before the first picture. */
    fun resize(wpx: Int, hpx: Int) {
        val vw = maxOf(12, (wpx + S - 1) / S); val vh = maxOf(18, (hpx + S - 1) / S)
        if (vw == VW && vh == VH) return
        VW = vw; VH = vh; W = VW * S; H = VH * S; frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); fc = Canvas(frame)
    }
    private var camX = world.px
    private var camY = world.py

    private fun K(v: Long) = v.toInt()
    private fun lite(c: Int, d: Int): Int {
        val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255); val b = ((c and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun mix(c1: Int, c2: Int, t: Float): Int {
        val r = (((c1 shr 16) and 0xFF) * (1 - t) + ((c2 shr 16) and 0xFF) * t).toInt(); val g = (((c1 shr 8) and 0xFF) * (1 - t) + ((c2 shr 8) and 0xFF) * t).toInt()
        val b = ((c1 and 0xFF) * (1 - t) + (c2 and 0xFF) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun dith(x: Int, y: Int) = bayer[(y and 3) * 4 + (x and 3)] / 16f

    // ---------------------------------------------------------------------------------------------------------------------
    //  where things are
    // ---------------------------------------------------------------------------------------------------------------------
    private enum class Z { CELLHOUSE, CELL, DINING, KITCHEN, SHOWER, INFIRMARY, LIBRARY, OFFICE, LAUNDRY, YARD, PIPES, NONE }
    private fun zoneOf(c: Int, r: Int): Z {
        val ch = world.tile(c, r)
        if (ch == 'p') return Z.PIPES
        if (ch == ',' || (r >= 38 && c < 59)) return Z.YARD
        val name = PrisonLevel.zoneName(world.spec, c, r)
        return when {
            name.startsWith("YOUR CELL") -> Z.CELL
            name == "A BLOCK" || name == "B BLOCK" || name == "C BLOCK" || name == "D BLOCK" -> if (ch == '=' || ch == 'o' || ch == 'O') Z.CELLHOUSE else Z.CELL
            name == "DINING HALL" -> Z.DINING
            name == "KITCHEN" -> Z.KITCHEN
            name == "LAUNDRY" -> Z.LAUNDRY
            name == "SHOWERS" -> Z.SHOWER
            name == "INFIRMARY" -> Z.INFIRMARY
            name == "LIBRARY" -> Z.LIBRARY
            c >= 62 -> Z.OFFICE
            else -> Z.CELLHOUSE
        }
    }
    private val zones: Array<Z> by lazy { Array(world.cols * world.rows) { i -> zoneOf(i % world.cols, i / world.cols) } }
    private fun zone(c: Int, r: Int): Z = if (c < 0 || r < 0 || c >= world.cols || r >= world.rows) Z.NONE else zones[r * world.cols + c]
    /** The zone a wall's painted face belongs to (the room below it). */
    private fun faceZone(c: Int, r: Int) = zone(c, r + 1)

    private fun solidWall(ch: Char) = ch == '#' || ch == 'v' || ch == 'L' || ch == 'X' || ch == 'W'
    private fun isWall(c: Int, r: Int) = solidWall(world.tile(c, r))
    private fun hasFace(c: Int, r: Int) = isWall(c, r) && r + 1 < world.rows && !isWall(c, r + 1)
    private val myX = PrisonLevel.CX0 + 3 * PrisonLevel.MY_CELL

    /** The tall barred windows: along the top of the building (looking out from inside) and along the yard side of the cellhouse. */
    private val windows: List<Pair<Int, Int>> by lazy {
        val out = ArrayList<Pair<Int, Int>>()
        for (c in 0 until world.cols) {
            if (hasFace(c, 0) && c % 3 == 1) out += c to 0
            if (hasFace(c, 37) && zone(c, 38) == Z.YARD && c % 4 == 2 && c !in 24..27) out += c to 37
        }
        out
    }

    // ---------------------------------------------------------------------------------------------------------------------
    //  1. the albedo: plain colours
    // ---------------------------------------------------------------------------------------------------------------------
    private val BW get() = world.cols * S
    private val BH get() = world.rows * S
    private lateinit var pix: IntArray
    private lateinit var noLight: BooleanArray
    private fun pset(x: Int, y: Int, c: Int) { if (x in 0 until BW && y in 0 until BH) pix[y * BW + x] = c }

    private val albedo: IntArray by lazy { buildAlbedo() }

    private fun buildAlbedo(): IntArray {
        pix = IntArray(BW * BH); noLight = BooleanArray(BW * BH)
        for (r in 0 until world.rows) for (col in 0 until world.cols) if (isWall(col, r)) wallPix(col, r) else floorPix(col, r, zone(col, r))
        val bm = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888)
        bm.setPixels(pix, 0, BW, 0, 0, BW, BH)
        val c = Canvas(bm)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val x = col * S; val y = r * S
            when (world.tile(col, r)) {
                '=' -> bars(c, x, y, 0f, true)
                'o' -> bars(c, x, y, 0.85f, true)
                'b' -> bunk(c, x, y, col, r)
                't' -> toilet(c, x, y, r)
                'v' -> vent(c, x, y, r)
                'T' -> diningTable(c, x, y, col, r)
                'k' -> if (zone(col, r) == Z.SHOWER) bench(c, x, y, col, r) else counter(c, x, y, col, r)
                'K' -> stove(c, x, y)
                'r' -> shelf(c, x, y, col, r)
                'z' -> steps(c, x, y, col, r)
                'h' -> shower(c, x, y)
                'd' -> desk(c, x, y, col, r)
                'c' -> chair(c, x, y, col, r)
                'G' -> glass(c, x, y, col, r)
                'L' -> lockedDoor(c, x, y)
                'X' -> gate(c, x, y)
                'n' -> bed(c, x, y, col)
                'W' -> tower(c, x, y, col, r)
                'M', 'Y' -> machine(c, x, y, world.tile(col, r) == 'Y')
                'F' -> foldTable(c, x, y, col, r)
                'U', 'C' -> cart(c, x, y, world.tile(col, r) == 'U')
                'u' -> uniformShelf(c, x, y)
            }
        }
        cellLife(c)
        decorate(c)
        val out = IntArray(BW * BH)
        bm.getPixels(out, 0, BW, 0, 0, BW, BH)
        return out
    }

    // ----- floors -----
    private fun floorPix(col: Int, row: Int, z: Z) {
        val x0 = col * S; val y0 = row * S
        val wallN = isWall(col, row - 1); val wallW = isWall(col - 1, row); val wallE = isWall(col + 1, row)
        for (yy in 0 until S) for (xx in 0 until S) {
            val gx = x0 + xx; val gy = y0 + yy
            val n = hash(gx, gy, 5) % 100
            var cl: Int
            when (z) {
                Z.CELLHOUSE -> {   // polished concrete, a paler worn path down the middle, expansion joints, the odd stain
                    cl = K(0xFF4C5662)
                    val wornRow = (row in 18..19) || row == 6 || row == 31 || col in 25..26 || col == 59
                    if (wornRow) cl = K(0xFF56606C)
                    if (n < 7) cl = lite(cl, 7) else if (n > 95) cl = lite(cl, -7)
                    if (hash(gx / 6, gy / 4, 7) % 29 == 0) cl = lite(cl, 5)
                    if (gy % 48 == 0 || gx % 48 == 0) cl = lite(cl, -9)
                    if (hypot(((gx % 64) - 20f) * 0.6f, (gy % 80) - 50f) < 5f && hash(gx / 64, gy / 80, 21) % 3 == 0) cl = lite(cl, -6)   // old stains
                }
                Z.CELL -> {
                    cl = K(0xFF3A414C); if (n < 9) cl = lite(cl, 5) else if (n > 94) cl = lite(cl, -6)
                    if ((gx + gy * 3) % 13 == 0 && n < 40) cl = lite(cl, -4)
                }
                Z.DINING -> {      // terrazzo in big squares: chips of stone in two colours
                    val sq = ((gx / 16) + (gy / 16)) % 2 == 0
                    cl = if (sq) K(0xFF66605A) else K(0xFF5A5650)
                    if (n < 3) cl = lite(cl, 14) else if (n > 97) cl = lite(cl, -10)
                    if (gx % 16 == 0 || gy % 16 == 0) cl = K(0xFF7A7266)
                    if (gx % 16 == 1 || gy % 16 == 1) cl = lite(cl, -6)
                }
                Z.KITCHEN, Z.LAUNDRY -> {   // white tiles, grey grout, wear
                    cl = if (z == Z.KITCHEN) K(0xFF9EA2A0) else K(0xFF98A2A4)
                    if (hash(gx / 8, gy / 8, 3) % 7 == 0) cl = lite(cl, -10)
                    if (gx % 8 == 0 || gy % 8 == 0) cl = K(0xFF5E6466)
                    if (n < 5) cl = lite(cl, -14)
                    if (z == Z.LAUNDRY && hash(gx / 5, gy / 3, 9) % 23 == 0 && gx % 8 != 0 && gy % 8 != 0) cl = lite(cl, 12)
                }
                Z.SHOWER -> {      // little teal tiles, puddles, a drain line
                    cl = if (hash(gx / 4, gy / 4, 2) % 5 == 0) K(0xFF4E7A82) else K(0xFF5A8890)
                    if (gx % 4 == 0 || gy % 4 == 0) cl = K(0xFF2E4C52)
                    val puddle = hash(gx / 10, gy / 7, 13) % 4 == 0
                    if (puddle && gx % 4 != 0 && gy % 4 != 0) cl = lite(cl, 18)
                    if (gy == y0 + 11 && xx in 6..9) cl = K(0xFF22343A)
                }
                Z.INFIRMARY -> {
                    cl = if (((gx / 8) + (gy / 8)) % 2 == 0) K(0xFFB4BEBC) else K(0xFF9AA6A6)
                    if (n < 4) cl = lite(cl, -10)
                }
                Z.LIBRARY -> {     // old wooden boards
                    val pr = gy / 4; val off = (pr % 3) * 5
                    cl = when (hash((gx + off) / 14, pr, 3) % 3) { 0 -> K(0xFF5A3E2C); 1 -> K(0xFF664632); else -> K(0xFF4E3626) }
                    if (gy % 4 == 3) cl = K(0xFF2E1E14)
                    else if ((gx + off) % 14 == 13) cl = K(0xFF3A2818)
                    if (n < 6) cl = lite(cl, 9)
                }
                Z.OFFICE -> {      // dark green linoleum squares with a sheen
                    cl = K(0xFF34443E); if ((gx / 16 + gy / 16) % 2 == 0) cl = K(0xFF3A4C46)
                    if (n < 8) cl = lite(cl, 6)
                    if (gx % 16 == 0 || gy % 16 == 0) cl = K(0xFF26302C)
                }
                Z.YARD -> {        // old asphalt: grain, cracks with weeds, faded paint, puddles
                    cl = K(0xFF4A4E56); if (n < 15) cl = lite(cl, 6) else if (n > 88) cl = lite(cl, -7)
                    val crackA = hash(gx / 22, gy, 11) % 89 == 0; val crackB = hash(gx, gy / 22, 12) % 89 == 0
                    if (crackA || crackB) { cl = K(0xFF2E3036); if (hash(gx, gy, 31) % 9 == 0) cl = K(0xFF3E6A3A) }
                    val inCourt = gx in 34 * S..52 * S && gy in 41 * S..45 * S
                    val line = inCourt && (gx == 34 * S || gx == 52 * S || gy == 41 * S || gy == 45 * S || gx == 43 * S || hypot((gx - 43f * S), (gy - 43f * S)) in 30f..31f)
                    if (line && n > 22) cl = K(0xFFB8B8AC)
                    val puddle = hypot(gx - 18f * S, (gy - 43.5f * S) * 1.8f) < 22f || hypot(gx - 29f * S, (gy - 45.2f * S) * 1.8f) < 16f
                    if (puddle) cl = if ((gx + gy) % 3 == 0) K(0xFF6A7A90) else K(0xFF5A687C)
                }
                Z.PIPES -> {
                    cl = K(0xFF1E2228); if ((gx + gy) % 4 == 0) cl = K(0xFF2A2F36)
                    if (yy in 2..3 || yy in 12..13) cl = K(0xFF5A4A3A) else if (yy == 4 || yy == 14) cl = K(0xFF3A2E24)
                }
                Z.NONE -> cl = K(0xFF0C0E12)
            }
            // contact shadows: the floor darkens where it meets a wall
            if (z != Z.NONE) {
                if (wallN && yy < 4) cl = lite(cl, -(4 - yy) * 7)
                if (wallW && xx < 2) cl = lite(cl, -(2 - xx) * 6)
                if (wallE && xx > 13) cl = lite(cl, -(xx - 13) * 6)
            }
            pset(gx, gy, cl)
        }
    }

    // ----- walls: a dark top with a cold rim; where the room below is open, the painted face (cream above, green below) -----
    private fun wallPix(col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        val face = hasFace(col, row)
        val fz = faceZone(col, row)
        for (yy in 0 until S) for (xx in 0 until S) {
            val gx = x0 + xx; val gy = y0 + yy
            val n = hash(gx, gy, 8) % 100
            var cl: Int
            val top = !face || yy < 5
            if (top) {
                cl = K(0xFF141A24); if (n < 8) cl = K(0xFF19202C); if ((gx / 3 + gy / 3) % 7 == 0) cl = K(0xFF11161F)
                noLight[gy * BW + gx] = true
            } else if (fz == Z.YARD) {           // the outside of the building: big weathered concrete blocks, rust streaks
                val br = (yy - 5) / 4
                cl = if (hash((gx + (br % 2) * 8) / 16, br + row * 3, 2) % 2 == 0) K(0xFF7A786E) else K(0xFF6E6C62)
                if ((yy - 5) % 4 == 3 || (gx + (br % 2) * 8) % 16 == 15) cl = K(0xFF4E4C46)
                if (hash(gx / 2, gy / 6, 4) % 13 == 0) cl = lite(cl, -14)
                if (hash(gx, 0, 44) % 37 == 0 && yy > 6) cl = K(0xFF6A4A36)                // rust running down
                if (yy == 15) cl = K(0xFF2A2C2C)
            } else if (fz == Z.SHOWER || fz == Z.KITCHEN || fz == Z.LAUNDRY || fz == Z.INFIRMARY) {   // tiled walls
                cl = when (fz) { Z.SHOWER -> K(0xFF6A9AA2); Z.INFIRMARY -> K(0xFFC8D0CC); else -> K(0xFFBCC0BA) }
                if (xx % 4 == 0 || (yy - 5) % 4 == 0) cl = lite(cl, -34)
                if (yy == 5) cl = lite(cl, 14)
                if (yy == 15) cl = K(0xFF2A3030)
                if (n < 3) cl = lite(cl, -16)
            } else if (fz == Z.LIBRARY || fz == Z.OFFICE) {   // wood panelling under a cream band
                cl = if (yy < 9) K(0xFFCFC6A6) else if (xx % 5 == 4) K(0xFF3A2618) else K(0xFF5E4030)
                if (yy == 9) cl = K(0xFF8A6A48)
                if (yy == 15) cl = K(0xFF1E140E)
            } else {                               // the cellhouse paint: cream over institutional green, chipped
                cl = if (yy < 10) K(0xFFCDC6A8) else K(0xFF4C6A58)
                if (yy == 10) cl = K(0xFF2E4A3A)
                if (yy == 9) cl = K(0xFFB8B092)
                if (yy == 15) cl = K(0xFF1E2620)
                val chip = hash(gx / 2, gy / 2, 17) % 31 == 0
                if (chip) cl = if (yy < 10) K(0xFF9A9480) else K(0xFF8A8E80)
                if (n < 4) cl = lite(cl, -9)
                if (hash(gx, gy / 3, 23) % 53 == 0 && yy in 6..14) cl = lite(cl, -16)     // drips and grime
            }
            pset(gx, gy, cl)
        }
    }

    // ----- furniture -----
    private val STEEL = K(0xFF6A7480); private val STEEL_D = K(0xFF2E343E); private val STEEL_L = K(0xFFB0BAC6); private val STEEL_M = K(0xFF4A525E)

    /** A strip of cell bars with their shadow on the floor. [open] slides them aside (0 shut, 1 open). */
    private fun bars(c: Canvas, x: Int, y: Int, open: Float, withShadow: Boolean) {
        val off = (open * 12f).roundToInt()
        if (withShadow) for (i in 0 until 4) if (i * 4 + off < 14) rect(c, x + 2 + i * 4 + off, y + 14, 2, 2, a(PX.K, 0x50))
        rect(c, x, y + 1, S, 3, STEEL_D); rect(c, x, y + 1, S, 1, STEEL); px(c, x + 3, y + 2, STEEL_L); px(c, x + 11, y + 2, STEEL_L)   // the top rail, riveted
        rect(c, x, y + 13, S, 2, STEEL_D); rect(c, x, y + 13, S, 1, STEEL_M)                                                      // the floor track
        c.save(); c.clipRect(x, y, x + S, y + S)
        for (i in 0 until 4) {
            val bx = x + 1 + i * 4 + off
            rect(c, bx, y + 4, 2, 9, STEEL_D); rect(c, bx, y + 4, 1, 9, STEEL); px(c, bx, y + 5, STEEL_L); px(c, bx, y + 9, STEEL_L)
        }
        rect(c, x + off, y + 8, S, 1, STEEL_D); rect(c, x + off, y + 7, S, 1, STEEL_M)                                            // the cross band
        c.restore()
        if (open > 0.5f) { for (i in 0 until 3) { rect(c, x + 12 + (i % 2) * 2, y + 4, 1, 9, STEEL_D); px(c, x + 12 + (i % 2) * 2, y + 6, STEEL) } }
    }
    private fun bunk(c: Canvas, x: Int, y: Int, col: Int, r: Int) {
        val backUp = solidWall(world.tile(col, r - 1))
        rect(c, x + 1, y + 2, 14, 13, a(PX.K, 0x60))                                                             // shadow under it
        rect(c, x + 1, y + 1, 14, 12, STEEL_D); rect(c, x + 1, y + 1, 14, 1, STEEL)                                  // the frame
        rect(c, x + 2, y + 2, 12, 10, K(0xFF8A8676))                                                              // the mattress ticking
        for (i in 0..2) rect(c, x + 2, y + 3 + i * 3, 12, 1, K(0xFF7A766A))
        val by = if (backUp) y + 5 else y + 2
        val blanket = if (col == myX + 1) K(0xFF5A6A8C) else when (hash(col, r, 7) % 3) { 0 -> K(0xFF5E6878); 1 -> K(0xFF66605A); else -> K(0xFF4E5A6A) }
        rect(c, x + 2, by, 12, 7, blanket); rect(c, x + 2, by, 12, 1, lite(blanket, 26)); rect(c, x + 2, by + 6, 12, 1, lite(blanket, -18))
        rect(c, x + 5, by + 2, 1, 4, lite(blanket, -12)); rect(c, x + 9, by + 1, 1, 5, lite(blanket, 10))          // folds
        val py = if (backUp) y + 2 else y + 9
        rect(c, x + 3, py, 10, 3, K(0xFFE2DED0)); rect(c, x + 3, py + 2, 10, 1, K(0xFFB0AC9E)); px(c, x + 4, py, PX.W)
        if (hash(col, r, 3) % 4 == 0) { rect(c, x + 10, by + 2, 3, 4, K(0xFF7A2A2A)); px(c, x + 10, by + 2, K(0xFFB04040)) }     // a book left on the bed
    }
    private fun toilet(c: Canvas, x: Int, y: Int, r: Int) {
        val backUp = solidWall(world.tile(x / S, r - 1))
        val by = if (backUp) y + 1 else y + 6
        rect(c, x + 8, by, 7, 6, K(0xFF9EA6A8)); rect(c, x + 8, by, 7, 1, PX.W); rect(c, x + 9, by + 2, 5, 3, K(0xFF5E7A86)); px(c, x + 11, by + 1, STEEL_L)   // sink + tap
        rect(c, x + 1, by + 1, 6, 8, K(0xFF2E3238)); rect(c, x + 2, by + 1, 5, 7, K(0xFFD8DAD4)); rect(c, x + 3, by + 3, 3, 3, K(0xFF6A8690))
        rect(c, x + 2, by + 1, 5, 1, PX.W); rect(c, x + 2, by + 7, 5, 1, K(0xFF9AA0A0))
    }
    private fun vent(c: Canvas, x: Int, y: Int, r: Int) {
        if (!hasFace(x / S, r)) return
        rect(c, x + 9, y + 10, 6, 5, K(0xFF1E2622)); rect(c, x + 9, y + 10, 6, 1, K(0xFF5A6A60))
        for (i in 0..1) rect(c, x + 10, y + 12 + i * 2, 4, 1, K(0xFF6E7E74))
        px(c, x + 9, y + 10, STEEL_L); px(c, x + 14, y + 14, STEEL_L)
    }
    private fun diningTable(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) != 'T'; val rr = world.tile(col + 1, row) != 'T'
        val x0 = x + (if (l) 2 else 0); val x1 = x + S - (if (rr) 2 else 0)
        // round steel stools bolted to the floor on both sides
        for (sx in intArrayOf(x + 4, x + 11)) { rect(c, sx - 2, y - 3, 5, 3, STEEL_D); rect(c, sx - 2, y - 3, 5, 1, STEEL); rect(c, sx - 2, y + 15, 5, 3, STEEL_D); rect(c, sx - 2, y + 15, 5, 1, STEEL) }
        rect(c, x0, y + 13, x1 - x0, 2, a(PX.K, 0x70))
        rect(c, x0, y + 2, x1 - x0, 11, STEEL_D); rect(c, x0, y + 2, x1 - x0, 9, K(0xFF8A939E)); rect(c, x0, y + 2, x1 - x0, 1, STEEL_L)
        if (l) rect(c, x0, y + 2, 1, 9, STEEL_L)
        val h = hash(col, row, 4) % 4
        if (h != 0) {   // a tray: beans, bread, a cup
            rect(c, x + 3, y + 4, 9, 6, K(0xFF5E666E)); rect(c, x + 4, y + 5, 3, 3, K(0xFF8A4A2A)); rect(c, x + 8, y + 5, 3, 2, K(0xFFD8C08A)); px(c, x + 9, y + 8, K(0xFFC8C8C0))
            if (h == 2) { rect(c, x + 12, y + 3, 2, 3, K(0xFFD8D8D0)); px(c, x + 12, y + 3, K(0xFF4A2A1A)) }
        }
    }
    private fun counter(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x, y + 13, S, 2, a(PX.K, 0x70))
        rect(c, x, y + 1, S, 13, STEEL_D); rect(c, x, y + 1, S, 10, STEEL); rect(c, x, y + 1, S, 1, STEEL_L)
        rect(c, x + 1, y + 11, S - 2, 2, K(0xFF262A30)); px(c, x + 4, y + 12, STEEL_L); px(c, x + 11, y + 12, STEEL_L)
        when (hash(col, row, 9) % 4) {
            0 -> { rect(c, x + 3, y + 3, 8, 6, K(0xFF1E2024)); rect(c, x + 4, y + 4, 6, 4, K(0xFFB89A50)); rect(c, x + 11, y + 5, 3, 1, K(0xFF1E2024)) }   // a pan
            1 -> { rect(c, x + 3, y + 3, 9, 6, K(0xFFB08A5A)); rect(c, x + 3, y + 3, 9, 1, K(0xFFD0AA78)); rect(c, x + 5, y + 5, 4, 1, K(0xFFC8CCD0)) }      // a board and a knife
            2 -> { rect(c, x + 4, y + 2, 7, 7, K(0xFF8A929A)); rect(c, x + 5, y + 3, 5, 5, K(0xFF3A3E44)); rect(c, x + 5, y + 3, 5, 1, K(0xFFC8B070)) }      // a pot
            else -> {}
        }
    }
    private fun bench(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x + 1, y + 11, 14, 2, a(PX.K, 0x70))
        rect(c, x + 1, y + 5, 14, 6, K(0xFF3A2A1C)); rect(c, x + 1, y + 5, 14, 4, K(0xFF8A6A48)); rect(c, x + 1, y + 5, 14, 1, K(0xFFB08A60))
        rect(c, x + 2, y + 11, 2, 3, STEEL_D); rect(c, x + 12, y + 11, 2, 3, STEEL_D)
        if (hash(col, row, 3) % 2 == 0) { rect(c, x + 4, y + 4, 7, 3, PX.W); rect(c, x + 4, y + 6, 7, 1, K(0xFFB8C8D8)) }        // a folded towel
        if (hash(col, row, 4) % 3 == 0) { rect(c, x + 11, y + 6, 3, 2, K(0xFFE8D8A0)) }                                          // a bar of soap
    }
    private fun stove(c: Canvas, x: Int, y: Int) {
        rect(c, x, y, S, S, K(0xFF16181C)); rect(c, x + 1, y + 1, 14, 12, K(0xFF2A2E34)); rect(c, x + 1, y + 1, 14, 1, K(0xFF4A5058))
        for ((bx, by) in listOf(4 to 4, 11 to 4, 4 to 10, 11 to 10)) { rect(c, bx + x - 2, by + y - 2, 5, 5, K(0xFF0A0B0D)); rect(c, bx + x - 1, by + y - 1, 3, 3, K(0xFF2A1A14)) }
        rect(c, x + 2, y + 13, 12, 2, K(0xFF0A0B0D)); for (i in 0..3) px(c, x + 3 + i * 3, y + 14, K(0xFF8A8E94))
    }
    private val BOOKS = intArrayOf(K(0xFF8A2A2A), K(0xFF2A4A7A), K(0xFF3A6A3A), K(0xFF8A7A3A), K(0xFF5A3A6A), K(0xFFB8A888), K(0xFF2A5A5A), K(0xFF7A4A2A))
    private fun shelf(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0x80))
        rect(c, x + 1, y, 14, 15, K(0xFF2A1C12)); rect(c, x + 1, y, 14, 1, K(0xFF6A4A30)); rect(c, x + 1, y, 1, 15, K(0xFF4A3220))
        for (sh in 0..1) {
            val sy = y + 2 + sh * 6
            var bx = x + 2
            var i = 0
            while (bx < x + 14) {
                val bw = 1 + hash(col * 7 + i, row * 3 + sh, 2) % 2; val bh = 4 + hash(col + i, row + sh, 5) % 2
                val bc = BOOKS[hash(col * 7 + i, row * 3 + sh, 2) % BOOKS.size]
                if (hash(col, i + sh * 9, 8) % 9 == 0) { bx += 2; i++; continue }               // a gap
                rect(c, bx, sy + 5 - bh, bw, bh, bc); px(c, bx, sy + 5 - bh, lite(bc, 40))
                bx += bw; i++
            }
            rect(c, x + 1, sy + 5, 14, 1, K(0xFF4A3220))
        }
    }
    private fun steps(c: Canvas, x: Int, y: Int, col: Int, r: Int) {
        val k = r - 38
        val base = lite(K(0xFF7E7A6E), k * 7)
        rect(c, x, y, S, S, base)
        for (yy in 0 until 12) for (xx in 0 until S) if (hash(x + xx, y + yy, 6) % 11 == 0) px(c, x + xx, y + yy, lite(base, -10))
        rect(c, x, y + 11, S, 1, lite(base, 18)); rect(c, x, y + 12, S, 4, lite(K(0xFF4E4C44), k * 6)); rect(c, x, y + 15, S, 1, K(0xFF2E2C28))
        if (hash(col, r, 3) % 4 == 0) rect(c, x + 5, y + 5, 4, 2, lite(base, -18))       // where men sit, worn dark
    }
    private fun shower(c: Canvas, x: Int, y: Int) {
        rect(c, x + 7, y, 2, 4, STEEL_D); rect(c, x + 7, y, 1, 4, STEEL)
        rect(c, x + 4, y + 3, 8, 3, STEEL_D); rect(c, x + 5, y + 3, 6, 2, STEEL_L); for (i in 0..2) px(c, x + 6 + i * 2, y + 5, K(0xFF2A3438))
        rect(c, x + 6, y + 11, 4, 2, K(0xFF1E2A2E)); px(c, x + 7, y + 11, STEEL)
    }
    private fun desk(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) != 'd'; val rr = world.tile(col + 1, row) != 'd'
        val x0 = x + (if (l) 1 else 0); val x1 = x + S - (if (rr) 1 else 0)
        rect(c, x0, y + 13, x1 - x0, 2, a(PX.K, 0x80))
        rect(c, x0, y + 2, x1 - x0, 12, K(0xFF2A1A10)); rect(c, x0, y + 2, x1 - x0, 9, K(0xFF6A4A2E)); rect(c, x0, y + 2, x1 - x0, 1, K(0xFF9A7448))
        for (gx in x0 until x1) if (hash(gx, row, 3) % 5 == 0) rect(c, gx, y + 4, 1, 6, K(0xFF5E4028))                 // wood grain
        when (hash(col, row, 5) % 3) {
            0 -> { rect(c, x + 3, y + 4, 6, 5, K(0xFFE8E4D6)); rect(c, x + 4, y + 5, 4, 1, K(0xFF8A8A8A)); rect(c, x + 4, y + 7, 3, 1, K(0xFF8A8A8A)) }   // papers
            1 -> { rect(c, x + 3, y + 3, 7, 5, K(0xFF1A1E22)); rect(c, x + 4, y + 4, 5, 3, K(0xFF2A5A6A)) }                                          // a monitor
            else -> { rect(c, x + 9, y + 3, 4, 4, K(0xFF1A1A1A)); px(c, x + 10, y + 4, K(0xFF6A6A6A)) }                                               // a phone
        }
    }
    private fun chair(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x + 3, y + 12, 10, 3, a(PX.K, 0x70))
        rect(c, x + 7, y + 10, 2, 4, STEEL_D)
        rect(c, x + 4, y + 2, 8, 9, K(0xFF1A1C20)); rect(c, x + 5, y + 3, 6, 6, K(0xFF7A2A2A)); rect(c, x + 5, y + 3, 6, 1, K(0xFFA04040))
        if (PrisonLevel.zoneName(world.spec, col, row) == "BARBER") { rect(c, x + 3, y + 5, 1, 4, STEEL_L); rect(c, x + 12, y + 5, 1, 4, STEEL_L) }   // armrests
    }
    private fun glass(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val vertical = world.tile(col, row - 1) == 'G' || world.tile(col, row + 1) == 'G'
        if (vertical) {
            rect(c, x + 5, y, 6, S, K(0xFF1E2830)); rect(c, x + 6, y, 4, S, K(0xFF3A5A6E)); rect(c, x + 7, y, 1, S, K(0xFF8AB8D0))
            if (row % 2 == 0) { px(c, x + 8, y + 3, PX.W); px(c, x + 8, y + 4, K(0xFFB8D8F0)) }
            if (world.tile(col, row - 1) != 'G') rect(c, x + 5, y, 6, 1, STEEL)
        } else { rect(c, x, y + 5, S, 6, K(0xFF1E2830)); rect(c, x, y + 6, S, 4, K(0xFF3A5A6E)); rect(c, x, y + 7, S, 1, K(0xFF8AB8D0)) }
    }
    private fun lockedDoor(c: Canvas, x: Int, y: Int) {
        rect(c, x, y, S, S, K(0xFF1A1E22))
        rect(c, x + 1, y + 1, 14, 15, K(0xFF3E464C)); rect(c, x + 2, y + 2, 12, 13, K(0xFF56606A)); rect(c, x + 2, y + 2, 12, 1, K(0xFF7E8890))
        for (i in 0..3) { rect(c, x + 3, y + 4 + i * 3, 10, 1, K(0xFF465058)); px(c, x + 3, y + 4 + i * 3, K(0xFF8A949C)) }
        rect(c, x + 10, y + 7, 3, 4, K(0xFF14161A)); px(c, x + 11, y + 8, K(0xFFB0A060))                                         // the big lock
        rect(c, x + 3, y + 3, 4, 2, K(0xFF2A3A44)); px(c, x + 4, y + 3, K(0xFF7AA0B8))                                            // a little window
    }
    private fun gate(c: Canvas, x: Int, y: Int) {
        rect(c, x, y, S, S, K(0xFF16120C))
        for (i in 0 until 5) { rect(c, x + 1 + i * 3, y, 2, S, K(0xFF6A5A34)); rect(c, x + 1 + i * 3, y, 1, S, K(0xFFA8925A)) }
        rect(c, x, y + 6, S, 3, K(0xFF3A3020)); rect(c, x, y + 6, S, 1, K(0xFF8A7848))
    }
    private fun bed(c: Canvas, x: Int, y: Int, col: Int) {
        rect(c, x + 1, y + 2, 14, 14, a(PX.K, 0x60))
        rect(c, x + 1, y + 1, 14, 14, K(0xFF8A9298)); rect(c, x + 2, y + 2, 12, 12, K(0xFFE6E8E4)); rect(c, x + 3, y + 3, 10, 3, PX.W)
        rect(c, x + 2, y + 8, 12, 6, K(0xFFA8BCD0)); rect(c, x + 2, y + 8, 12, 1, K(0xFFC8D8E8))
        if (col % 2 == 0) { rect(c, x + 14, y - 4, 1, 12, STEEL_L); rect(c, x + 12, y - 5, 4, 3, K(0xFFC8E0E8)) }                   // an IV stand
    }
    private fun tower(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x, y, S, S, K(0xFF2E3034))
        if (world.tile(col - 1, row) != 'W' && world.tile(col, row - 1) != 'W') {
            rect(c, x + 1, y + 1, 30, 30, K(0xFF1E2024)); rect(c, x + 3, y + 3, 26, 26, K(0xFF4A4E56)); rect(c, x + 3, y + 3, 26, 2, K(0xFF7A808A))
            rect(c, x + 7, y + 7, 18, 18, K(0xFF2A3640)); rect(c, x + 8, y + 8, 16, 16, K(0xFF3A5468))                               // the glass cab
            for (i in 0..3) rect(c, x + 8 + i * 5, y + 8, 1, 16, K(0xFF1E2024))
            rect(c, x + 13, y + 13, 6, 6, K(0xFF1A1C20)); rect(c, x + 14, y + 14, 4, 4, K(0xFFD8E0E8))                               // the searchlight
            rect(c, x + 1, y + 12, 2, 18, STEEL_D); for (i in 0..5) rect(c, x, y + 13 + i * 3, 4, 1, STEEL)                           // a ladder
        }
    }
    private fun machine(c: Canvas, x: Int, y: Int, dryer: Boolean) {
        rect(c, x, y + 14, S, 2, a(PX.K, 0x80))
        rect(c, x + 1, y, 14, 15, K(0xFF5E666E)); rect(c, x + 2, y + 1, 12, 13, if (dryer) K(0xFFCAC2B2) else K(0xFFD6DCE0))
        rect(c, x + 2, y + 1, 12, 3, K(0xFF9AA0A6)); rect(c, x + 2, y + 1, 12, 1, PX.W); px(c, x + 4, y + 2, PX.K); px(c, x + 6, y + 2, PX.K)
        rect(c, x + 4, y + 5, 8, 8, K(0xFF2A3038)); rect(c, x + 5, y + 6, 6, 6, K(0xFF233240)); rect(c, x + 5, y + 6, 6, 1, K(0xFF7A98B0)); px(c, x + 6, y + 7, K(0xFFB8D0E0))
        rect(c, x + 12, y + 8, 1, 3, K(0xFF2A3038))
    }
    private fun foldTable(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) != 'F'; val rr = world.tile(col + 1, row) != 'F'
        val x0 = x + (if (l) 1 else 0); val x1 = x + S - (if (rr) 1 else 0)
        rect(c, x0, y + 13, x1 - x0, 2, a(PX.K, 0x80))
        rect(c, x0, y + 2, x1 - x0, 12, K(0xFF3A2A1C)); rect(c, x0, y + 2, x1 - x0, 9, K(0xFF9A7A54)); rect(c, x0, y + 2, x1 - x0, 1, K(0xFFC0A07A))
        if (col % 2 == 0) for (i in 0..1) { rect(c, x + 4, y + 7 - i * 2, 8, 2, if (i == 0) K(0xFFE8E4DA) else K(0xFFB8C8D8)) }               // folded sheets
    }
    private fun cart(c: Canvas, x: Int, y: Int, dirty: Boolean) {
        rect(c, x + 1, y + 13, 14, 3, a(PX.K, 0x80))
        rect(c, x + 1, y + 2, 14, 12, K(0xFF1E2226)); rect(c, x + 2, y + 3, 12, 10, if (dirty) K(0xFF8A7E62) else K(0xFFA8A898)); rect(c, x + 2, y + 3, 12, 1, if (dirty) K(0xFFAA9E80) else K(0xFFC8C8B8))
        for (i in 0..2) rect(c, x + 3 + i * 4, y + 4, 1, 8, if (dirty) K(0xFF6E6248) else K(0xFF8A8A7C))                                       // canvas seams
        px(c, x + 2, y + 14, STEEL_L); px(c, x + 13, y + 14, STEEL_L)
    }
    private fun uniformShelf(c: Canvas, x: Int, y: Int) {
        rect(c, x + 1, y, 14, S, K(0xFF2A1C12)); rect(c, x + 1, y, 1, S, K(0xFF4A3220))
        for (i in 0..2) { rect(c, x + 2, y + 2 + i * 5, 12, 4, K(0xFF5A6070)); rect(c, x + 2, y + 2 + i * 5, 12, 1, K(0xFF8A90A0)); px(c, x + 8, y + 3 + i * 5, PX.GOLD) }
    }

    /** The life in the cells: on the back walls you can see, photos, a calendar, a shelf with a cup, a pin-up, tally marks. */
    private fun cellLife(c: Canvas) {
        for (r in listOf(1, 13, 26)) for (k in 0..9) {
            val x = PrisonLevel.CX0 + 3 * k + 1
            if (!hasFace(x, r) || !hasFace(x + 1, r)) continue
            if (x == myX + 1 && r == 13) continue        // yours is bare (your tally marks are drawn as the days go by)
            val px0 = x * S; val py0 = r * S
            when (hash(k, r, 41) % 5) {
                0 -> { rect(c, px0 + 3, py0 + 6, 6, 5, K(0xFFE8E0C8)); rect(c, px0 + 4, py0 + 7, 4, 3, K(0xFF6A8AA8)); rect(c, px0 + 4, py0 + 9, 4, 1, K(0xFF4A6A3A)) }   // a postcard of somewhere
                1 -> { rect(c, px0 + 2, py0 + 6, 7, 6, PX.W); for (i in 0..2) rect(c, px0 + 3, py0 + 7 + i * 2, 5, 1, K(0xFF8A8A8A)); px(c, px0 + 7, py0 + 7, PX.RED) }  // a calendar
                2 -> { rect(c, px0 + 2, py0 + 9, 10, 1, K(0xFF5A3E28)); rect(c, px0 + 3, py0 + 6, 2, 3, K(0xFFD8D8D0)); rect(c, px0 + 7, py0 + 7, 3, 2, K(0xFF8A2A2A)) } // a shelf, a cup, a book
                3 -> { rect(c, px0 + 4, py0 + 5, 5, 7, K(0xFFE0C8A8)); rect(c, px0 + 5, py0 + 6, 3, 2, K(0xFF4A2A1A)); rect(c, px0 + 5, py0 + 8, 3, 3, K(0xFFC84A4A)) }  // a pin-up
                else -> for (i in 0 until 4 + k % 3) rect(c, px0 + 3 + i * 2, py0 + 6, 1, 4, K(0xFF3A3A34))                                                       // someone's tally
            }
        }
    }

    /** The finishing touches: the dining-hall clock, corridor signs, the west gun gallery, the hoop, benches, a drain, notices. */
    private fun decorate(c: Canvas) {
        // the dining-hall clock over the door (Times Square)
        val cx = 23 * S + 8; val cy = 16 * S + 6
        rect(c, cx - 6, cy - 6, 13, 13, K(0xFF14161A)); rect(c, cx - 5, cy - 5, 11, 11, K(0xFFE8E2CC)); rect(c, cx - 5, cy - 5, 11, 1, PX.W)
        rect(c, cx, cy - 4, 1, 5, PX.K); rect(c, cx, cy, 3, 1, PX.K); px(c, cx - 4, cy, PX.K); px(c, cx + 4, cy, PX.K); px(c, cx, cy + 4, PX.K)
        // corridor name plates
        for ((r, len) in listOf(5 to 8, 17 to 8, 30 to 6)) {
            rect(c, 29 * S, r * S - 7, len * 3 + 5, 7, K(0xFF0E1014)); rect(c, 29 * S + 1, r * S - 6, len * 3 + 3, 5, K(0xFF1E2A40))
            for (i in 0 until len) rect(c, 29 * S + 3 + i * 3, r * S - 5, 2, 3, K(0xFFE8E4D4))
        }
        // the west gun gallery: a caged steel walkway along the end wall
        for (r in 1..36) {
            val y = r * S
            rect(c, 24 * S, y, 3, S, K(0xFF1A1E24)); rect(c, 24 * S + 2, y, 1, S, STEEL)
            for (yy in 0 until S step 3) px(c, 24 * S + 3, y + yy, a(STEEL_L, 0x90))
        }
        // the basketball hoop on the yard wall
        rect(c, 43 * S + 2, 46 * S + 2, 12, 2, PX.W); rect(c, 43 * S + 4, 46 * S + 4, 8, 4, a(PX.W, 0x80)); rect(c, 43 * S + 7, 46 * S + 8, 2, 7, STEEL_D)
        // benches in the yard
        for (bx in intArrayOf(30, 36, 48)) { rect(c, bx * S, 39 * S + 4, 32, 5, K(0xFF5A4030)); rect(c, bx * S, 39 * S + 4, 32, 1, K(0xFF8A6A48)); rect(c, bx * S + 2, 39 * S + 9, 2, 3, STEEL_D); rect(c, bx * S + 28, 39 * S + 9, 2, 3, STEEL_D) }
        // a drain in the yard
        rect(c, 22 * S + 4, 44 * S + 6, 8, 5, K(0xFF1A1C20)); for (i in 0..3) rect(c, 22 * S + 5 + i * 2, 44 * S + 7, 1, 3, STEEL)
        // corridor lamp fixtures hang from above (the tubes glow later)
        for ((lx, ly) in lampSpots()) { rect(c, lx - 5, ly - 2, 11, 4, K(0xFF1A1E24)); px(c, lx - 5, ly - 2, STEEL) }
        // notices on the walls
        for ((c0, r0) in listOf(25 to 0, 59 to 0, 9 to 7, 17 to 7)) if (hasFace(c0, r0)) {
            val x = c0 * S + 4; val y = r0 * S + 6
            rect(c, x, y, 8, 6, K(0xFFE8E4D4)); rect(c, x + 1, y + 1, 6, 1, PX.K); rect(c, x + 1, y + 3, 4, 1, K(0xFF7A7A7A)); px(c, x + 6, y + 4, PX.RED)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------
    //  2. the light
    // ---------------------------------------------------------------------------------------------------------------------
    private class Light(val x: Float, val y: Float, val r: Float, val cr: Float, val cg: Float, val cb: Float, val s: Float)
    private class Shaft(val x: Float, val y: Float, val dx: Float, val dy: Float, val len: Float, val w: Float, val cr: Float, val cg: Float, val cb: Float, val s: Float)

    private fun lampSpots(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (col in 30..56 step 4) { out += col * S + 8 to 6 * S + 8; out += col * S + 8 to 19 * S; out += col * S + 8 to 31 * S + 8 }
        for (r in 3..35 step 5) { out += 26 * S to r * S + 8; out += 60 * S to r * S + 8 }
        return out
    }
    private val flickerLamp get() = 46 * S + 8 to 31 * S + 8

    private fun lights(night: Boolean): Pair<List<Light>, List<Shaft>> {
        val L = ArrayList<Light>(); val SH = ArrayList<Shaft>()
        val lamp = Triple(0.86f, 0.92f, 1.0f); val warm = Triple(1.0f, 0.80f, 0.52f); val teal = Triple(0.55f, 0.95f, 1.0f)
        fun add(cx: Float, cy: Float, r: Float, c: Triple<Float, Float, Float>, s: Float) { L += Light(cx, cy, r, c.first, c.second, c.third, s) }
        // the corridors
        for ((i, sp) in lampSpots().withIndex()) {
            if (night && i % 2 == 1) continue
            add(sp.first.toFloat(), sp.second.toFloat() + 4f, if (night) 54f else 66f, lamp, if (night) 0.5f else 0.55f)
        }
        // windows: daylight (or moonlight) falling into the room below at a slant
        for ((wc, wr) in windows) {
            val x = wc * S + 8f; val y = wr * S + 15f
            if (wr == 0) {
                if (night) SH += Shaft(x, y, 0.42f, 1f, 70f, 12f, 0.45f, 0.62f, 1.0f, 0.42f)
                else SH += Shaft(x, y, 0.42f, 1f, 96f, 14f, 1.0f, 0.98f, 0.9f, 0.62f)
            } else if (night) add(x, y + 10f, 30f, Triple(0.95f, 0.88f, 0.7f), 0.3f)     // the cellhouse windows glow onto the yard at night
        }
        // skylights over Broadway (day): bright slanted patches down the middle
        if (!night) for (col in 31..55 step 6) SH += Shaft(col * S + 8f, 17 * S + 2f, 0.35f, 1f, 58f, 20f, 1.0f, 0.98f, 0.9f, 0.5f)
        // the dining hall: warm lamps over the tables
        for (cx in floatArrayOf(12.5f, 18.5f)) for (cy in floatArrayOf(11.5f, 14.5f, 23.5f, 19f)) if (!night || cy == 19f) add(cx * S, cy * S, 52f, warm, if (night) 0.22f else 0.42f)
        // the kitchen and the stoves
        if (!night) for (cy in floatArrayOf(11f, 17f, 23f)) add(3.5f * S, cy * S, 46f, warm, 0.45f)
        if (!night) for (r in 9..27 step 2) add(1.5f * S, r * S + 8f, 14f, Triple(1f, 0.45f, 0.15f), 0.5f)
        // the showers
        if (!night) for (cx in floatArrayOf(5f, 11f, 17f)) add(cx * S, 3.5f * S, 50f, teal, 0.45f)
        // the infirmary (a night light stays on)
        if (!night) for (cx in floatArrayOf(5f, 11f, 17f)) add(cx * S, 33f * S, 56f, lamp, 0.55f) else add(11f * S, 33f * S, 40f, Triple(0.6f, 1f, 0.75f), 0.25f)
        // the library's reading lamp
        add(32.5f * S, 33.6f * S, 48f, Triple(1f, 0.9f, 0.55f), if (night) 0.2f else 0.5f)
        // the offices: monitors, the warden's desk lamp, the barber, the gate
        add(64.5f * S, 3.8f * S, 40f, Triple(0.4f, 0.95f, 1f), 0.45f)
        add(65.5f * S, 26.8f * S, 40f, warm, 0.5f)
        if (!night) { add(66f * S, 14f * S, 60f, lamp, 0.42f); add(66f * S, 21f * S, 50f, warm, 0.45f); add(66f * S, 5f * S, 60f, lamp, 0.35f) }
        add(70f * S, 34.5f * S, 36f, warm, 0.4f)
        // the laundry
        if (!night) for ((cx, cy) in listOf(63f to 40f, 67f to 40f, 65f to 44f)) add(cx * S, cy * S, 50f, warm, 0.4f)
        // red exit lights over the doors
        if (night) for ((cx, cy) in listOf(23.5f to 17f, 25.5f to 37f, 59f to 42f)) add(cx * S, cy * S, 24f, Triple(1f, 0.25f, 0.3f), 0.45f)
        // the yard: lamps on the cellhouse wall, and the tower's flood
        if (night) {
            for (cx in floatArrayOf(10f, 30f, 50f)) add(cx * S, 38.5f * S, 64f, lamp, 0.38f)
            add(56.5f * S, 45f * S, 120f, lamp, 0.3f)
        }
        // a few cells with a reading light on at night
        if (night) for (k in 0..9) for (fr in intArrayOf(9, 15, 22, 28)) if (hash(k, fr, 77) % 5 == 0) add((PrisonLevel.CX0 + 3 * k + 2f) * S, fr * S + 4f, 18f, warm, 0.3f)
        return L to SH
    }

    private fun ambient(z: Z, night: Boolean): Float = if (!night) when (z) {
        Z.YARD -> 0.88f; Z.CELL -> 0.46f; Z.PIPES -> 0.22f; Z.INFIRMARY -> 0.6f; Z.KITCHEN, Z.LAUNDRY -> 0.56f; Z.LIBRARY -> 0.46f; Z.NONE -> 0.2f
        else -> 0.54f
    } else when (z) {
        Z.YARD -> 0.26f; Z.CELL -> 0.15f; Z.PIPES -> 0.08f; Z.NONE -> 0.06f
        else -> 0.2f
    }

    private fun bake(night: Boolean): Bitmap {
        val alb = albedo
        val n = BW * BH
        val lr = FloatArray(n); val lg = FloatArray(n); val lb = FloatArray(n)
        val tr = if (night) 0.55f else 0.94f; val tg = if (night) 0.70f else 0.98f; val tb = if (night) 1.15f else 1.06f
        for (y in 0 until BH) for (x in 0 until BW) {
            val col = x / S; val row = y / S
            val z = if (isWall(col, row)) faceZone(col, row) else zone(col, row)
            val a0 = ambient(z, night)
            val i = y * BW + x
            lr[i] = a0 * tr; lg[i] = a0 * tg; lb[i] = a0 * tb
        }
        val (ls, shafts) = lights(night)
        for (l in ls) {
            val x0 = max(0, (l.x - l.r).toInt()); val x1 = min(BW - 1, (l.x + l.r).toInt())
            val y0 = max(0, (l.y - l.r).toInt()); val y1 = min(BH - 1, (l.y + l.r).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x - l.x, (y - l.y) * 1.15f) / l.r
                if (d >= 1f) continue
                val f = (1f - d) * (1f - d) * l.s
                val i = y * BW + x
                lr[i] += f * l.cr; lg[i] += f * l.cg; lb[i] += f * l.cb
            }
        }
        for (s in shafts) {
            val ln = hypot(s.dx, s.dy); val ux = s.dx / ln; val uy = s.dy / ln
            val ex = s.x + ux * s.len; val ey = s.y + uy * s.len
            val pad = s.w * 1.5f
            val x0 = max(0, (min(s.x, ex) - pad).toInt()); val x1 = min(BW - 1, (max(s.x, ex) + pad).toInt())
            val y0 = max(0, (min(s.y, ey) - 2).toInt()); val y1 = min(BH - 1, (max(s.y, ey) + 2).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                if (isWall(x / S, y / S)) continue
                val rx = x - s.x; val ry = y - s.y
                val along = rx * ux + ry * uy
                if (along < 0f || along > s.len) continue
                val across = abs(-rx * uy + ry * ux)
                val half = s.w / 2f * (1f + along / s.len * 0.5f)
                if (across > half) continue
                val edge = min(1f, (half - across) / 3f)
                val f = s.s * (1f - along / s.len) * edge
                val i = y * BW + x
                lr[i] += f * s.cr; lg[i] += f * s.cg; lb[i] += f * s.cb
            }
        }
        // snap the light to a few steps with the ordered dither and multiply it in
        val out = IntArray(n)
        val steps = 6f
        for (y in 0 until BH) for (x in 0 until BW) {
            val i = y * BW + x
            val c = alb[i]
            if (noLight[i]) { out[i] = if (night) mix(c, K(0xFF080C18), 0.35f) else c; continue }
            val th = dith(x, y)
            val qr = floor(lr[i] * steps + th) / steps; val qg = floor(lg[i] * steps + th) / steps; val qb = floor(lb[i] * steps + th) / steps
            val r = (((c shr 16) and 0xFF) * qr * 1.3f).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * qg * 1.3f).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * qb * 1.3f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bm = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888)
        bm.setPixels(out, 0, BW, 0, 0, BW, BH)
        glowPass(Canvas(bm), night)
        return bm
    }

    // ---------------------------------------------------------------------------------------------------------------------
    //  3. things that make their own light, and the rims
    // ---------------------------------------------------------------------------------------------------------------------
    private fun glowPass(c: Canvas, night: Boolean) {
        // cold rim lines along the wall tops where they meet the rooms (the same blue edge light as the back alleys)
        val rim = K(0xFF4A5C86); val rimHi = K(0xFF7E96C8)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            if (!isWall(col, r)) continue
            val x = col * S; val y = r * S
            val face = hasFace(col, r)
            val topH = if (face) 5 else S
            if (face) { rect(c, x, y + 4, S, 1, rimHi); rect(c, x, y + 3, S, 1, rim) }
            if (r > 0 && !isWall(col, r - 1)) rect(c, x, y, S, 1, rim)
            if (col > 0 && !isWall(col - 1, r)) { rect(c, x, y, 1, topH, rim); px(c, x, y + topH - 1, rimHi) }
            if (col + 1 < world.cols && !isWall(col + 1, r)) { rect(c, x + S - 1, y, 1, topH, rim); px(c, x + S - 1, y + topH - 1, rimHi) }
            // razor wire along the yard's walls
            if ((r == 37 || r == 47 || col == 0) && (zone(col, r - 1) == Z.YARD || zone(col, r + 1) == Z.YARD || zone(col + 1, r) == Z.YARD)) {
                for (i in 0 until 4) { val wx = x + 2 + i * 4; val wy = y + (if (face) 1 else 6); rect(c, wx, wy, 3, 1, K(0xFF8A929E)); px(c, wx + 1, wy - 1, K(0xFF8A929E)); px(c, wx + 1, wy + 1, K(0xFF5A626E)) }
            }
        }
        // the windows
        for ((wc, wr) in windows) {
            val x = wc * S; val y = wr * S
            val inside = wr == 0
            rect(c, x + 3, y + 5, 10, 10, K(0xFF14181E))
            val g1: Int; val g2: Int
            if (inside) { if (night) { g1 = K(0xFF1A2650); g2 = K(0xFF2A3C78) } else { g1 = K(0xFFB8D4EC); g2 = K(0xFFE8F2FA) } }
            else { if (night) { g1 = K(0xFFC8BE98); g2 = K(0xFFF0E6C0) } else { g1 = K(0xFF4A5A70); g2 = K(0xFF6A7E98) } }
            for (yy in 0 until 9) rect(c, x + 4, y + 6 + yy, 8, 1, mix(g2, g1, yy / 8f))
            for (i in 0..3) rect(c, x + 5 + i * 2, y + 6, 1, 9, K(0xFF202630))
            rect(c, x + 4, y + 10, 8, 1, K(0xFF202630))
            if (inside && night) px(c, x + 10, y + 7, K(0xFFE8F0FF))                                   // the moon
            if (!inside && !night) px(c, x + 5, y + 7, K(0xFFB8C8D8))                                  // a glint
        }
        // corridor lamp tubes, and their reflection in the polished floor
        for ((i, sp) in lampSpots().withIndex()) {
            val on = !night || i % 2 == 0
            val (lx, ly) = sp
            rect(c, lx - 4, ly - 1, 9, 2, if (on) K(0xFFF0F6FF) else K(0xFF3A4048))
            if (on) { px(c, lx - 4, ly - 1, PX.LAMP); rect(c, lx - 3, ly + 12, 7, 1, a(PX.LAMP, if (night) 0x30 else 0x48)); rect(c, lx - 1, ly + 13, 3, 1, a(PX.LAMP, 0x30)) }
        }
        // stove flames
        if (!night) for (r in 9..27 step 2) { val x = S; val y = r * S; for ((bx, by) in listOf(4 to 4, 11 to 4, 4 to 10, 11 to 10)) if (hash(bx, r + by, 2) % 2 == 0) { px(c, x + bx, y + by, K(0xFF3A7AFF)); px(c, x + bx - 1, y + by, K(0xFF2A5ACF)); px(c, x + bx + 1, y + by, K(0xFF2A5ACF)) } }
        // monitors in the control centre, the alarm light
        rect(c, 64 * S + 4, 3 * S + 4, 5, 3, K(0xFF5AE8FF)); rect(c, 65 * S + 4, 3 * S + 4, 5, 3, K(0xFF3AC8E0))
        px(c, 69 * S + 8, 2 * S + 3, PX.RED); px(c, 69 * S + 9, 2 * S + 3, K(0xFFFF8A8A))
        // the reading lamp in the library, the warden's lamp
        rect(c, 32 * S + 8, 33 * S + 2, 4, 2, K(0xFF2A6A3A)); px(c, 32 * S + 9, 33 * S + 4, K(0xFFFFE8A0))
        rect(c, 65 * S + 12, 26 * S + 2, 3, 2, K(0xFF2A6A3A)); px(c, 65 * S + 13, 26 * S + 4, K(0xFFFFE8A0))
        // red exit signs (always on)
        for ((ex, ey) in listOf(23 to 16, 25 to 36, 59 to 41)) { rect(c, ex * S + 3, ey * S + 1, 10, 4, K(0xFF2A0A0E)); rect(c, ex * S + 4, ey * S + 2, 8, 2, if (night) K(0xFFFF3B55) else K(0xFFB83040)) }
        // the washers' and dryers' panel lights
        for (m in world.machines) px(c, m.c * S + 9, m.r * S + 2, K(0xFF3BFF9A))
        // the bulbs of the cells with a reading light on
        if (night) for (k in 0..9) for (fr in intArrayOf(9, 15, 22, 28)) if (hash(k, fr, 77) % 5 == 0) px(c, (PrisonLevel.CX0 + 3 * k + 2) * S, fr * S + 4, K(0xFFFFE8A0))
    }

    private val bgDay: Bitmap by lazy { bake(false) }
    private val bgNight: Bitmap by lazy { bake(true) }


    // ---------------------------------------------------------------------------------------------------------------------
    //  4. each frame
    // ---------------------------------------------------------------------------------------------------------------------
    private val inmates = listOf(
        Outfit(PX.SKIN, 0xFF3A2A1E.toInt(), null, null, 0xFF5A7FB0.toInt(), null, 0xFF8A8670.toInt(), 0xFF1A1410.toInt(), null),
        Outfit(0xFFC89A78.toInt(), 0xFF1A1420.toInt(), null, null, 0xFF6A8CBC.toInt(), null, 0xFF7A7664.toInt(), PX.K, null),
        Outfit(0xFF8A6048.toInt(), 0xFF0E0A0A.toInt(), null, null, 0xFF4E72A4.toInt(), null, 0xFF8A8670.toInt(), PX.K, null),
        Outfit(PX.SKIN, 0xFF8A8A92.toInt(), null, null, 0xFF5A7FB0.toInt(), null, 0xFF6E6A58.toInt(), PX.K, null, glasses = true),
        Outfit(0xFFA87858.toInt(), 0xFF2A1A10.toInt(), null, null, 0xFF6A8CBC.toInt(), null, 0xFF8A8670.toInt(), PX.K, null),
        Outfit(0xFF6A4434.toInt(), 0xFF0A0808.toInt(), null, null, 0xFF52769E.toInt(), null, 0xFF7E7A66.toInt(), PX.K, null),
    )
    private val you = Outfit(PX.SKIN, 0xFF4A2E1B.toInt(), null, null, 0xFF6E96CC.toInt(), null, 0xFF8A8670.toInt(), 0xFF1A1410.toInt(), null, glasses = true)
    private val guardOutfit = Outfit(PX.SKIN, 0xFF1A1420.toInt(), 0xFF2A303C.toInt(), 0xFF101418.toInt(), 0xFF5E6676.toInt(), null, 0xFF2A303C.toInt(), PX.K, PX.K, badge = true)

    private class DItem(val y: Float, val draw: () -> Unit)
    private val outfitCache = HashMap<Int, Outfit>()
    private fun outfitOf(n: PrisonWorld.Npc): Outfit = outfitCache.getOrPut(n.id) {
        val pr = n.persona ?: return@getOrPut inmates[n.id % inmates.size]
        Outfit(pr.skin, if (pr.bald) lite(pr.skin, -24) else pr.hair, null, null, pr.shirt, null, 0xFF8A8670.toInt(), 0xFF1A1410.toInt(), null, glasses = pr.glasses)
    }

    /** Light a sprite by the light where it stands: compare the lit floor with its plain colour a few pixels around the feet. */
    private var lit: Bitmap? = null
    private fun tintAt(x: Int, y: Int) {
        val bg = lit ?: return
        var sr = 0f; var sg = 0f; var sb = 0f; var k = 0
        for ((dx, dy) in listOf(-4 to -2, 4 to -2, -2 to 2, 2 to 2, 0 to 0)) {
            val px0 = (x + dx).coerceIn(0, BW - 1); val py0 = (y + dy).coerceIn(0, BH - 1)
            val b = bg.getPixel(px0, py0); val al = albedo[py0 * BW + px0]
            fun ch(v: Int, sh: Int) = ((v shr sh) and 0xFF).toFloat()
            if (ch(al, 16) + ch(al, 8) + ch(al, 0) < 30f) continue
            sr += ch(b, 16) / max(12f, ch(al, 16)); sg += ch(b, 8) / max(12f, ch(al, 8)); sb += ch(b, 0) / max(12f, ch(al, 0)); k++
        }
        if (k == 0) { p.colorFilter = null; return }
        fun q(v: Float) = ((v / k).coerceIn(0.3f, 1f) * 255).toInt()
        p.colorFilter = android.graphics.PorterDuffColorFilter((0xFF shl 24) or (q(sr) shl 16) or (q(sg) shl 8) or q(sb), PorterDuff.Mode.MULTIPLY)
    }
    private fun noTint() { p.colorFilter = null }
    private val miniMap: Bitmap by lazy {
        val bm = Bitmap.createBitmap(world.cols, world.rows, Bitmap.Config.ARGB_8888)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val ch = world.tile(col, r)
            val cl = when {
                isWall(col, r) -> if (!isWall(col, r + 1) || !isWall(col - 1, r) || !isWall(col + 1, r) || !isWall(col, r - 1)) a(K(0xFF4A5C86), 0xD0) else 0
                ch == '=' || ch == 'o' -> a(STEEL, 0xC0)
                zone(col, r) == Z.YARD -> a(K(0xFF5A6070), 0xC0)
                zone(col, r) == Z.CELL -> a(K(0xFF3A4458), 0xC0)
                else -> a(K(0xFF8A96AE), 0xC0)
            }
            bm.setPixel(col, r, cl)
        }
        bm
    }
    private val vignette: Bitmap by lazy {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val dx = (x - W / 2f) / (W / 2f); val dy = (y - H / 2f) / (H / 2f)
            val d = (hypot(dx, dy * 0.9f) - 0.72f) / 0.6f
            if (d <= 0f) continue
            val q = floor(d.coerceAtMost(1f) * 4f + dith(x, y)) / 4f
            if (q > 0f) px[y * W + x] = a(K(0xFF02040C), (q * 0xB0).toInt())
        }
        bm.setPixels(px, 0, W, 0, 0, W, H); bm
    }
    /** A tile of rolling sea fog: soft blobs, dithered. Drawn twice over the yard, drifting at different speeds. */
    private val fogTile: Bitmap by lazy {
        val n = 128
        val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val px = IntArray(n * n)
        val blobs = (0 until 9).map { Triple((hash(it, 1, 3) % n).toFloat(), (hash(it, 2, 3) % n).toFloat(), 18f + hash(it, 3, 3) % 22) }
        for (y in 0 until n) for (x in 0 until n) {
            var v = 0f
            for ((bx, by, br) in blobs) for (ox in -1..1) for (oy in -1..1) {
                val d = hypot(x - bx - ox * n, (y - by - oy * n) * 1.6f) / br
                if (d < 1f) v += (1f - d) * 0.55f
            }
            val q = floor(v.coerceAtMost(1f) * 3f + dith(x, y)) / 3f
            if (q > 0f) px[y * n + x] = a(K(0xFFC8D4E4), (q * 0x22).toInt())
        }
        bm.setPixels(px, 0, n, 0, 0, n, n); bm
    }
    private val searchCone: Bitmap by lazy {
        val len = 210; val wid = 90
        val bm = Bitmap.createBitmap(len, wid, Bitmap.Config.ARGB_8888)
        val px = IntArray(len * wid)
        for (y in 0 until wid) for (x in 0 until len) {
            val half = 3f + x * 0.2f
            val across = abs(y - wid / 2f)
            if (across > half) continue
            val v = (1f - x / len.toFloat()) * 0.5f + 0.5f * (1f - across / half)
            val q = floor(v.coerceIn(0f, 1f) * 4f + dith(x, y)) / 4f
            if (q > 0f) px[y * len + x] = a(K(0xFFDCEBFF), (q * 0x70).toInt())
        }
        bm.setPixels(px, 0, len, 0, 0, len, wid); bm
    }
    private val lampHole by lazy { glowBitmap(30, PX.K, 0xC8) }

    // dust in the light (day): drifting specks inside the window shafts and under the skylights
    private class Mote(val x0: Float, val y0: Float, val ph: Float, val sp: Float)
    private val motes: List<Mote> by lazy {
        val out = ArrayList<Mote>()
        var i = 0
        for ((wc, wr) in windows) if (wr == 0) repeat(4) { out += Mote(wc * S + 8f + (hash(i, 1, 9) % 30), wr * S + 18f + (hash(i, 2, 9) % 70), (hash(i, 3, 9) % 100) / 16f, 0.3f + (hash(i, 4, 9) % 10) / 20f); i++ }
        for (col in 31..55 step 6) repeat(5) { out += Mote(col * S + 8f + (hash(i, 1, 9) % 24) - 6f, 17 * S + 6f + (hash(i, 2, 9) % 46), (hash(i, 3, 9) % 100) / 16f, 0.3f + (hash(i, 4, 9) % 10) / 20f); i++ }
        out
    }

    fun render(clock: Float): Bitmap {
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        if (world.phase == PrisonWorld.Phase.SIGNAL || world.phase == PrisonWorld.Phase.ESCAPE) { sea(c, clock); c.drawBitmap(vignette, 0f, 0f, plain); return frame }
        camX += (world.px - camX) * 0.2f; camY += (world.py - camY) * 0.2f
        val cxT = (camX - VW / 2f).coerceIn(0f, (world.cols - VW).toFloat())
        val cyT = (camY - VH / 2f).coerceIn(0f, (world.rows - VH).toFloat())
        val ox = -(cxT * S).roundToInt(); val oy = -(cyT * S).roundToInt()
        val wake = world.phase == PrisonWorld.Phase.WAKE
        val dark = if (wake) world.lights < 1f && !(world.lights > 0.05f && sin(clock * 70f) > 0.2f) else world.night
        c.save(); c.translate(ox.toFloat(), oy.toFloat())
        lit = if (dark) bgNight else bgDay
        c.drawBitmap(lit!!, 0f, 0f, plain)

        // your door, sliding open after the count; your tally marks on the wall
        val (dc, dr) = world.spec.myDoor
        tintAt(dc * S + 8, dr * S + 15); bars(c, dc * S, dr * S, world.doorOpen, true); noTint()
        val tx = (myX + 1) * S + 3; val ty = 13 * S + 6
        for (i in 0 until min(world.day, 15)) { val g = i / 5; val k = i % 5; if (k < 4) rect(c, tx + g * 6 + k, ty, 1, 4, K(0xFF2A2A26)) else rect(c, tx + g * 6 - 1, ty + 2, 5, 1, K(0xFF2A2A26)) }

        laundryNow(c, clock)
        showerWater(c, clock)
        // the broken mirror on the shower-room wall catches the light
        if (!world.signalSent) { val mx = 4 * S + 4; val my = S + 5; rect(c, mx, my, 7, 6, K(0xFF1A2228)); rect(c, mx + 1, my + 1, 5, 4, K(0xFF8AB0C8)); px(c, mx + 2, my + 1, PX.W); px(c, mx + 4, my + 3, K(0xFF5A7A90)); if ((clock * 1.5f).toInt() % 3 == 0) { px(c, mx + 3, my - 1, PX.W); px(c, mx + 1, my + 2, PX.W) } }
        steam(c, clock, dark)

        // the people, sorted so the lower ones stand in front
        val c0 = cxT.toInt() - 2; val c1 = cxT.toInt() + VW + 2; val r0 = cyT.toInt() - 2; val r1 = cyT.toInt() + VH + 3
        val items = ArrayList<DItem>()
        for (n in world.npcs) {
            if (n.x < c0 || n.x > c1 || n.y < r0 || n.y > r1) continue
            items += DItem(n.y) {
                val fx = (n.x * S).roundToInt(); val fy = (n.y * S).roundToInt() + 6
                val fr = if (n.moving) 1 + ((clock * 7f + n.id).toInt() % 2) else 0
                val o = if (n.guard) guardOutfit else outfitOf(n)
                tintAt(fx, fy)
                when (val act = world.activityOf(n)) {
                    "pushups" -> { val up = (clock * 1.6f + n.id).toInt() % 2 == 0; lying(c, fx, fy - (if (up) 2 else 0), o, n.id % 2 == 0, false); rect(c, fx + (if (n.id % 2 == 0) -9 else 7), fy - 4, 2, if (up) 4 else 2, o.skin) }
                    "weights" -> { val up = (clock * 1.2f + n.id).toInt() % 2 == 0; folk(c, fx, fy, 2, 0, o, false, false); val by = fy - (if (up) 24 else 16); rect(c, fx - 10, by, 21, 2, STEEL_D); rect(c, fx - 12, by - 2, 3, 6, PX.K); rect(c, fx + 10, by - 2, 3, 6, PX.K); rect(c, fx - 6, by + 2, 2, if (up) 8 else 2, o.skin); rect(c, fx + 5, by + 2, 2, if (up) 8 else 2, o.skin) }
                    "reader" -> { folk(c, fx, fy, 0, 0, o, false, false); rect(c, fx - 3, fy - 12, 7, 5, K(0xFF7A2A2A)); rect(c, fx - 2, fy - 11, 5, 3, K(0xFFE8E4D4)) }
                    "cards" -> { folk(c, fx, fy, 0, 0, o, false, false); if ((clock * 0.8f + n.id).toInt() % 3 == 0) { rect(c, fx - 4, fy - 20, 4, 5, PX.W); px(c, fx - 3, fy - 19, PX.RED) } }
                    "sleeper" -> { val (hc, hr) = n.home; lying(c, hc * S + 9, (hr - 1) * S + 12, o, true, false); if ((clock * 0.7f).toInt() % 2 == 0) rect(c, hc * S + 12, (hr - 1) * S - 2, 3, 1, a(PX.W, 0xB0)) }
                    else -> {
                        folk(c, fx, fy, dirOf(n.face), fr, o, false, false)
                        if (act == "sweeper" || (!n.guard && n.persona?.activity == "sweeper")) { rect(c, fx + 6, fy - 12, 1, 12, K(0xFF8A6A3A)); rect(c, fx + 4, fy - 1, 6, 2, K(0xFFC8B070)) }   // a broom
                    }
                }
                // the little things that make each man himself: a beard, tattoos
                val pr = n.persona
                if (pr != null && world.activityOf(n) !in setOf("pushups", "sleeper")) {
                    val d0 = dirOf(n.face)
                    if (pr.beard && d0 == 2) { rect(c, fx - 3, fy - 14, 7, 3, pr.hair); px(c, fx, fy - 14, pr.skin) }
                    if (pr.beard && (d0 == 1 || d0 == 3)) rect(c, fx + (if (d0 == 1) 1 else -4), fy - 14, 4, 3, pr.hair)
                    if (pr.tattoo) { px(c, fx - 6, fy - 9, K(0xFF2A4A6A)); px(c, fx + 6, fy - 10, K(0xFF2A4A6A)) }
                }
                if (n.guard) rect(c, fx - 6, fy - 8, 2, 6, K(0xFF2A1C14))                                       // the baton
                noTint()
                if (n.guard && dark) { val d0 = dirOf(n.face); val tx0 = fx + (if (d0 == 1) 6 else if (d0 == 3) -6 else 5); px(c, tx0, fy - 7, PX.W); px(c, tx0, fy - 6, PX.LAMP) }   // a torch at night
                // a guard keeping watch shows what he's doing: an open eye when he's watching you, dots when he isn't
                if (n.guard && (n.post != null || n.escorting) && world.phase == PrisonWorld.Phase.PLAY) {
                    val ix0 = fx - 4; val iy0 = fy - 32
                    if (n.attn == PrisonWorld.WATCH) { rect(c, ix0, iy0, 9, 5, PX.K); rect(c, ix0 + 1, iy0 + 1, 7, 3, PX.W); rect(c, ix0 + 3, iy0 + 1, 3, 3, if (n.id in world.watchers) PX.RED else K(0xFF1A2A4A)) }
                    else if (n.attn == PrisonWorld.DISTRACT) { rect(c, ix0, iy0 + 1, 9, 3, a(PX.K, 0xA0)); for (k in 0..2) px(c, ix0 + 2 + k * 2, iy0 + 2, PX.W) }
                }
                if (n.id in world.watchers && !(n.post != null || n.escorting)) { rect(c, fx - 2, fy - 31, 5, 8, PX.K); rect(c, fx - 1, fy - 30, 3, 5, PX.RED); rect(c, fx - 2, fy - 23, 5, 4, PX.K); rect(c, fx - 1, fy - 22, 3, 2, PX.RED) }
            }
        }
        items += DItem(world.py) {
            val fx = (world.px * S).roundToInt(); val fy = (world.py * S).roundToInt() + 6
            if (world.lyingDown) {
                val (bc, br) = world.spec.bunk
                tintAt(bc * S + 9, br * S + 14); lying(c, bc * S + 9, br * S + 12, you, true, false); noTint()
                if (world.t > 0.4f && world.t < 1.6f) for (i in 0..2) { val zt = (world.t * 1.5f + i * 0.33f) % 1f; rect(c, bc * S + 4 + (zt * 8).toInt(), br * S - (zt * 14).toInt(), 3, 1, a(PX.W, ((1f - zt) * 0xC0).toInt())) }   // z z z
            } else {
                tintAt(fx, fy)
                folk(c, fx, fy, dirOf(world.face), if (world.moving) 1 + ((clock * 9f).toInt() % 2) else 0, if (world.disguised) guardOutfit else you, false, false)
                if (dirOf(world.face) != 0) bag(c, fx, fy, world.carry)
                noTint()
                if (world.phase == PrisonWorld.Phase.PLAY && (world.t < PrisonWorld.WAKE_END + 6f || world.idle > 3f)) {
                    val bob = if ((clock * 3f).toInt() % 2 == 0) 0 else 1
                    rect(c, fx - 4, fy - 29 + bob, 9, 3, PX.K); rect(c, fx - 3, fy - 28 + bob, 7, 1, PX.GOLD); rect(c, fx - 2, fy - 27 + bob, 5, 1, PX.GOLD); px(c, fx, fy - 26 + bob, PX.GOLD)
                }
            }
        }
        items.sortBy { it.y }
        for (it in items) it.draw()

        // what people are saying
        for (n in world.npcs) if (n.sayT > 0f && n.say.isNotEmpty() && n.x >= c0 && n.x <= c1 && n.y >= r0 && n.y <= r1) {
            val shown = ((3.2f - n.sayT + n.say.length * 0.035f) * 30f).toInt().coerceAtLeast(1)
            bubble(c, n.say, (n.x * S).roundToInt(), (n.y * S).roundToInt() - 26, shown, (cxT * S).roundToInt() + W)
        }
        // a bouncing arrow over what to do next
        world.nextStep().second?.let { (tc, tr) ->
            if (world.phase == PrisonWorld.Phase.PLAY && (clock * 2f).toInt() % 3 != 2) {
                val ax = tc * S + 8; val ay = tr * S - 10 - ((sin(clock * 6f) + 1f) * 2f).toInt()
                rect(c, ax - 4, ay - 3, 9, 3, PX.K); rect(c, ax - 3, ay - 2, 7, 1, PX.GOLD); rect(c, ax - 3, ay, 7, 2, PX.K); rect(c, ax - 2, ay, 5, 1, PX.GOLD); rect(c, ax - 1, ay + 1, 3, 2, PX.K); px(c, ax, ay + 1, PX.GOLD); px(c, ax, ay + 3, PX.K)
            }
        }

        // the atmosphere
        if (!dark) { dust(c, clock, cxT, cyT); flicker(c, clock) }
        fog(c, clock, cxT, cyT)
        if (dark && !wake) searchlight(c, clock)
        c.restore()

        // before dawn on day one it's pitch dark
        if (wake && world.lights <= 0f) { p.color = a(K(0xFF02040A), 0xA0); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        c.drawBitmap(vignette, 0f, 0f, plain)

        // the small map in the corner, with you as a blinking dot (and the place you're due at in green)
        if (world.phase == PrisonWorld.Phase.PLAY) {
            val mx = W - world.cols - 5; val my = hudTop
            rect(c, mx - 3, my - 3, world.cols + 6, world.rows + 6, a(K(0xFF050812), 0xC8))
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = a(K(0xFF4A5C86), 0xE0)
            c.drawRect(mx - 2.5f, my - 2.5f, mx + world.cols + 2.5f, my + world.rows + 2.5f, p); p.style = Paint.Style.FILL
            c.drawBitmap(miniMap, mx.toFloat(), my.toFloat(), plain)
            p.style = Paint.Style.STROKE; p.color = a(PX.W, 0x70)
            c.drawRect(mx + cxT, my + cyT, mx + cxT + VW, my + cyT + VH, p); p.style = Paint.Style.FILL
            world.dueAt?.let { due -> placeOf(due)?.let { (pc, pr) -> if ((clock * 2f).toInt() % 2 == 0) rect(c, mx + pc - 1, my + pr - 1, 3, 3, K(0xFF3BFF9A)) } }
            if ((clock * 3f).toInt() % 2 == 0) rect(c, mx + floor(world.px).toInt() - 1, my + floor(world.py).toInt() - 1, 3, 3, PX.GOLD)
        }
        return frame
    }

    // ---------------------------------------------------------------------------------------------------------------------
    //  the water: the mirror signal, and the escape (picked up by the boat, or not)
    // ---------------------------------------------------------------------------------------------------------------------
    private val seaBg: Bitmap by lazy { buildSeaBg() }
    private val boatGlow by lazy { glowBitmap(10, PX.WARM, 0x70) }
    private val towerGlow by lazy { glowBitmap(6, PX.RED, 0x80) }
    private val flashGlow by lazy { glowBitmap(60, K(0xFFFFF4D0), 0x90) }
    private val HORIZON = 168

    /** Dusk over the bay: the sky, stars, the moon, and the city across the water with the radio tower (the last level) on its hill. */
    private fun buildSeaBg(): Bitmap {
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val px0 = IntArray(W * H)
        val skyTop = K(0xFF050816); val skyMid = K(0xFF1A1D46); val skyLow = K(0xFF4A2A58); val skyHor = K(0xFF8A3A4E)
        for (y in 0 until H) for (x in 0 until W) {
            val th = dith(x, y)
            val col: Int = if (y < HORIZON) {
                val t = y / HORIZON.toFloat()
                val steps = 10f; val q = floor(t * steps + th) / steps
                when { q < 0.5f -> mix(skyTop, skyMid, q / 0.5f); q < 0.85f -> mix(skyMid, skyLow, (q - 0.5f) / 0.35f); else -> mix(skyLow, skyHor, ((q - 0.85f) / 0.15f).coerceAtMost(1f)) }
            } else {
                val t = (y - HORIZON) / (H - HORIZON).toFloat()
                val q = floor(t * 6f + th) / 6f
                mix(K(0xFF1E1E44), K(0xFF04060E), q.coerceIn(0f, 1f))
            }
            px0[y * W + x] = col
            if (y < 110 && hash(x, y, 91) % 260 == 0) px0[y * W + x] = if (hash(x, y, 92) % 3 == 0) PX.W else K(0xFF8A9AC8)       // stars
        }
        bm.setPixels(px0, 0, W, 0, 0, W, H)
        val c = Canvas(bm)
        // the moon
        c.drawBitmap(glowBitmap(26, K(0xFFB8C8F0), 0x40), 196f - 26f, 46f - 26f, plain)
        for (yy in -8..8) for (xx in -8..8) if (xx * xx + yy * yy <= 64) px(c, 196 + xx, 46 + yy, if ((xx + 3) * (xx + 3) + (yy - 2) * (yy - 2) < 20) K(0xFFD0DAF0) else K(0xFFEEF2FF))
        // the city across the water
        var x = 0
        var i = 0
        while (x < W) {
            val bw = 6 + hash(i, 1, 51) % 12; val bh = 8 + hash(i, 2, 51) % 30 + (if (x in 70..150) 14 else 0)
            rect(c, x, HORIZON - bh, bw, bh, K(0xFF0C0E22))
            for (wy in HORIZON - bh + 2 until HORIZON - 1 step 3) for (wx in x + 1 until x + bw - 1 step 2) if (hash(wx, wy, 53) % 5 == 0) px(c, wx, wy, if (hash(wx, wy, 54) % 4 == 0) PX.CYAN else PX.WARM)
            x += bw; i++
        }
        // the radio tower on its hill
        rect(c, 30, HORIZON - 16, 40, 16, K(0xFF0C0E22)); rect(c, 38, HORIZON - 22, 24, 6, K(0xFF0C0E22))
        for (yy in 0 until 70) { val half = 1 + (70 - yy) / 10; rect(c, 50 - half, HORIZON - 22 - 70 + yy, 1, 1, K(0xFF2A2E48)); rect(c, 50 + half, HORIZON - 22 - 70 + yy, 1, 1, K(0xFF2A2E48)); if (yy % 8 == 0) rect(c, 50 - half, HORIZON - 22 - 70 + yy, half * 2 + 1, 1, K(0xFF2A2E48)) }
        // haze on the horizon
        for (yy in HORIZON - 6 until HORIZON + 8) for (xx in 0 until W) if (bayer[(yy and 3) * 4 + (xx and 3)] < 4 - abs(yy - HORIZON) / 2) px(c, xx, yy, a(K(0xFFB88AA0), 0x50))
        return bm
    }

    private fun waves(c: Canvas, clock: Float) {
        for (y in HORIZON + 2 until H step 3) {
            val k = (y - HORIZON) / (H - HORIZON).toFloat()
            val len = 3 + (k * 9).toInt(); val gap = 10 + (k * 22).toInt()
            val off = ((clock * (6f + k * 18f)) + hash(0, y, 7) % 40).toInt()
            var x = -gap + (off % gap)
            while (x < W) { if (hash(x / gap, y, 8) % 3 != 0) rect(c, x, y, len, 1, a(K(0xFF5A6AA8), (0x30 + k * 0x50).toInt())); x += gap }
        }
        // the moon on the water
        for (y in HORIZON + 4 until H step 2) {
            val k = (y - HORIZON) / (H - HORIZON).toFloat()
            val w = 3 + (k * 14).toInt(); val wob = (sin(clock * 3f + y * 0.4f) * (2 + k * 6)).toInt()
            if (hash(y, (clock * 6).toInt(), 3) % 4 != 0) rect(c, 196 - w / 2 + wob, y, w, 1, a(K(0xFFDCE6FF), (0x90 - k * 0x50).toInt()))
        }
    }

    private fun boat(c: Canvas, x: Int, y: Int, scale: Int, lamp: Boolean, withYou: Boolean, clock: Float) {
        c.save(); c.translate(x.toFloat(), y.toFloat()); if (scale > 1) c.scale(scale.toFloat(), scale.toFloat())
        rect(c, -16, 0, 32, 2, a(K(0xFFDCE6FF), 0x40))                                                       // the wake
        rect(c, -14, -6, 28, 6, K(0xFFD8DCE4)); rect(c, -12, -1, 24, 2, K(0xFF8A90A0)); rect(c, -14, -4, 28, 1, K(0xFF2A4A8A))   // hull, stripe
        rect(c, -14, -6, 2, 3, K(0xFFD8DCE4)); rect(c, 12, -8, 3, 4, K(0xFFD8DCE4))                          // the bow lifts
        rect(c, -6, -11, 10, 5, K(0xFF3A404C)); rect(c, -5, -10, 4, 2, K(0xFF8AB8D8))                        // the cabin
        rect(c, 6, -13, 2, 7, K(0xFF1A1C22)); rect(c, 5, -16, 4, 3, PX.SKIN); rect(c, 5, -17, 4, 1, K(0xFF2A1A10))   // the one who came for you
        if (withYou) { rect(c, -11, -12, 3, 6, K(0xFF6E96CC)); rect(c, -11, -15, 3, 3, PX.SKIN); rect(c, -11, -16, 3, 1, K(0xFF4A2E1B)) }
        if (lamp && (clock * 2.5f).toInt() % 2 == 0) { px(c, 0, -13, PX.WARM); c.restore(); c.drawBitmap(boatGlow, x - 10f, y - 13f * scale - 10f, plain); return }
        c.restore()
    }

    /** Draws [what] twice as big, grown around the feet at (x, y), to match the boat in the water scenes. */
    private inline fun big(x: Int, y: Int, what: () -> Unit) { fc.save(); fc.scale(2f, 2f, x.toFloat(), y.toFloat()); what(); fc.restore() }

    private fun sea(c: Canvas, clock: Float) {
        val t = world.sceneT
        c.drawBitmap(seaBg, 0f, 0f, plain)
        // a blinking red light on top of the radio tower
        if ((clock * 1.2f).toInt() % 2 == 0) { px(c, 50, HORIZON - 93, PX.RED); c.drawBitmap(towerGlow, 44f, HORIZON - 99f, plain) }
        waves(c, clock)
        if (world.phase == PrisonWorld.Phase.SIGNAL) { signalScene(c, clock, t); return }

        // the island: rocks in front, the prison wall above them with its windows lit, a searchlight sweeping the water
        val rockTop = IntArray(W) { xx -> if (xx < 150) 250 + (sin(xx * 0.13f) * 5f + sin(xx * 0.37f) * 3f).toInt() + (if (xx > 120) (xx - 120) * 2 else 0) else H }
        rect(c, 0, 196, 112, 56, K(0xFF0C0E16)); for (wx in 6 until 108 step 9) rect(c, wx, 206, 3, 6, if (hash(wx, 1, 4) % 3 == 0) K(0xFF3A3424) else K(0xFFC8B880))
        rect(c, 0, 196, 112, 1, K(0xFF4A5C86))
        for (xx in 0 until W) if (rockTop[xx] < H) { rect(c, xx, rockTop[xx], 1, H - rockTop[xx], K(0xFF14161C)); px(c, xx, rockTop[xx], K(0xFF4A5468)); if (hash(xx, 0, 5) % 3 == 0) px(c, xx, rockTop[xx] + 1, K(0xFF2E3442)) }
        for (k in 0 until 40) { val rx = hash(k, 1, 6) % 140; val ry = rockTop[rx] + 6 + hash(k, 2, 6) % 90; if (ry < H) rect(c, rx, ry, 4, 2, K(0xFF262A34)) }
        c.save(); c.rotate(-8f + sin(clock * 0.5f) * 14f, 96f, 200f); c.drawBitmap(searchCone, 96f, 200f - searchCone.height / 2f, plain); c.restore()

        val edgeX = 136; val edgeY = rockTop[edgeX]
        if (world.signalSent) {
            // the boat glides in, you jump aboard, and it heads off toward the city
            val arrive = (t / 3f).coerceIn(0f, 1f); val ease = 1f - (1f - arrive) * (1f - arrive)
            val leave = ((t - 4.2f) / 5f).coerceIn(0f, 1f)
            val bx = (300 - 125 * ease + leave * 150).toInt(); val by = (300 - leave * 70).toInt()
            val jumped = t > 3.9f
            boat(c, bx, by, if (leave > 0.6f) 1 else 2, true, jumped, clock)
            if (!jumped) {
                val jk = ((t - 3.2f) / 0.7f).coerceIn(0f, 1f)
                val pxx = (edgeX + (bx - 22 - edgeX) * jk).toInt(); val pyy = (edgeY + (by - 12 - edgeY) * jk - sin(jk * PI.toFloat()) * 26f).toInt()
                big(pxx, pyy) { folk(c, pxx, pyy, 1, if (t < 3.2f) ((clock * 8f).toInt() % 2) else 1, if (world.disguised) guardOutfit else you, false, false) }
            }
        } else {
            // nobody comes. you look around... and dive.
            if (t < 1.7f) big(edgeX, edgeY) { folk(c, edgeX, edgeY, if ((t * 1.6f).toInt() % 2 == 0) 1 else 3, 0, if (world.disguised) guardOutfit else you, false, false) }
            else if (t < 2.4f) {
                val k = (t - 1.7f) / 0.7f
                val dx = (edgeX + 44 * k).toInt(); val dy = (edgeY - sin(k * PI.toFloat()) * 30f + k * 52f).toInt()
                c.save(); c.rotate(k * 150f, dx.toFloat(), dy - 16f); big(dx, dy) { folk(c, dx, dy, 1, 1, if (world.disguised) guardOutfit else you, false, false) }; c.restore()
            } else {
                val k = t - 2.4f
                val sx = edgeX + 44; val sy = edgeY + 52
                if (k < 0.6f) for (i in 0 until 10) { val an = i * 0.6f + 0.3f; val r = k * 40f; rect(c, sx + (cos(an) * r * 0.6f).toInt(), sy - (sin(an) * r).toInt().coerceAtLeast(0) + (k * k * 60).toInt(), 2, 2, a(PX.W, ((0.6f - k) / 0.6f * 0xE0).toInt())) }
                for (ring in 0..2) { val rk = k - ring * 0.6f; if (rk > 0f && rk < 3f) { p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = a(K(0xFFA8B8E0), ((1f - rk / 3f) * 0x90).toInt()); c.drawOval(sx - rk * 14f, sy - rk * 3.5f, sx + rk * 14f, sy + rk * 3.5f, p); p.style = Paint.Style.FILL } }
                val bubbles = (6 - (k * 2).toInt()).coerceAtLeast(0)
                for (i in 0 until bubbles) { val bt = (clock * 1.5f + i * 0.37f) % 1f; px(c, sx - 4 + (i * 3) % 9, sy - (bt * 4).toInt(), a(PX.W, ((1f - bt) * 0xC0).toInt())) }
                if (k > 2f) { p.style = Paint.Style.FILL; p.color = a(K(0xFF02030A), ((k - 2f) / 1.6f * 0xB0).toInt().coerceAtMost(0xB0)); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
            }
        }
    }

    /** Through the shower-room window: you flash the mirror; out on the water a boat's lamp answers and it comes in to wait. */
    private fun signalScene(c: Canvas, clock: Float, t: Float) {
        // the flashes: short, short, long... (you hold the mirror at the bottom of the window)
        val pattern = floatArrayOf(0.2f, 0.45f, 0.65f, 0.9f, 1.1f, 1.7f, 1.9f, 2.15f)
        var on = false; var i = 0
        while (i + 1 < pattern.size) { if (t in pattern[i]..pattern[i + 1]) on = true; i += 2 }
        // the answer: far out, a lamp blinks back, then the boat comes in closer and waits
        if (t > 2.4f) {
            val k = ((t - 2.6f) / 2.6f).coerceIn(0f, 1f)
            val bx = (230 - k * 70).toInt(); val by = (HORIZON + 14 + k * 50).toInt()
            boat(c, bx, by, 1, true, false, clock)
        }
        // the window: thick frame and bars over everything
        val frameC = K(0xFF0E1218)
        rect(c, 0, 0, W, 24, frameC); rect(c, 0, H - 30, W, 30, frameC); rect(c, 0, 0, 20, H, frameC); rect(c, W - 20, 0, 20, H, frameC)
        for (bx in 60 until W - 20 step 44) { rect(c, bx, 24, 6, H - 54, K(0xFF1A2028)); rect(c, bx + 1, 24, 1, H - 54, K(0xFF4A5468)) }
        rect(c, 20, 190, W - 40, 6, K(0xFF1A2028)); rect(c, 20, 190, W - 40, 1, K(0xFF4A5468))
        rect(c, 20, 23, W - 40, 1, K(0xFF4A5C86)); rect(c, 19, 24, 1, H - 54, K(0xFF4A5C86))
        // a hand holding the glass up to the window
        rect(c, 114, 306, 20, 54, PX.SKIN); rect(c, 114, 306, 3, 54, K(0xFFC89470)); rect(c, 132, 312, 4, 14, PX.SKIN)
        rect(c, 110, 290, 26, 18, K(0xFF1A2228)); rect(c, 112, 292, 22, 14, K(0xFF8AB0C8)); rect(c, 113, 293, 8, 2, PX.W); px(c, 130, 302, K(0xFF5A7A90))
        if (on) { c.drawBitmap(flashGlow, 123f - 60f, 298f - 60f, plain); for (k in 0 until 8) { val an = k * PI.toFloat() / 4f; rect(c, 123 + (cos(an) * 20f).toInt(), 298 + (sin(an) * 20f).toInt(), 2, 2, PX.W) }; rect(c, 116, 293, 14, 11, PX.W) }
    }

    /** Where to point on the small map for a place you're due at. */
    private fun placeOf(place: String): Pair<Int, Int>? = when (place) {
        "YOUR CELL" -> myX + 1 to 15
        "DINING HALL" -> 15 to 18
        "LAUNDRY" -> 65 to 42
        "SHOWERS" -> 11 to 3
        else -> null
    }

    // ----- the moving bits -----
    private fun dust(c: Canvas, clock: Float, cxT: Float, cyT: Float) {
        val x0 = cxT * S - 8; val x1 = x0 + W + 16; val y0 = cyT * S - 8; val y1 = y0 + H + 16
        for (m in motes) {
            val x = m.x0 + sin(clock * m.sp + m.ph) * 6f; val y = m.y0 + ((clock * m.sp * 3f + m.ph * 10f) % 20f) - 10f
            if (x < x0 || x > x1 || y < y0 || y > y1) continue
            val al = ((sin(clock * 1.3f + m.ph * 3f) + 1f) * 0.5f * 0xA0).toInt()
            px(c, x.toInt(), y.toInt(), a(K(0xFFFFF8E0), al))
        }
    }
    private fun flicker(c: Canvas, clock: Float) {
        // one tube on Seedy Street is on its way out
        val k = sin(clock * 9f) + sin(clock * 23f) * 0.6f + sin(clock * 2.1f)
        if (k > 0.9f) { val (lx, ly) = flickerLamp; c.drawBitmap(lampHole, lx - 30f, ly - 26f, plain); rect(c, lx - 4, ly - 1, 9, 2, K(0xFF3A4048)) }
    }
    private fun steam(c: Canvas, clock: Float, dark: Boolean) {
        val srcs = ArrayList<Pair<Int, Int>>()
        if (!dark) for (r in intArrayOf(9, 13, 17, 21, 25)) srcs += S + 8 to r * S + 2
        for (m in world.machines) if (m.running && m.dryer) srcs += m.c * S + 8 to m.r * S
        for ((sx, sy) in srcs) for (i in 0 until 4) {
            val t = (clock * 0.5f + i * 0.25f + (sx + sy) * 0.013f) % 1f
            val x = sx + (sin(t * 6f + sx) * 3f).toInt() + (t * 6).toInt(); val y = sy - (t * 22f).toInt()
            val sz = 2 + (t * 4).toInt()
            rect(c, x - sz / 2, y - sz / 2, sz, sz, a(K(0xFFE8EEF4), ((1f - t) * 0x50).toInt()))
        }
    }
    private fun showerWater(c: Canvas, clock: Float) {
        if (world.period != PrisonWorld.Period.SHOWERS || world.phase != PrisonWorld.Phase.PLAY) return
        for (col in 2..21 step 2) {
            val x = col * S + 8
            for (i in 0 until 9) {
                val t = (clock * 2.4f + i / 9f + col * 0.17f) % 1f
                rect(c, x - 3 + (i % 3) * 3, S + 6 + (t * 24).toInt(), 1, 3, a(K(0xFFD8F0FF), ((1f - t * 0.6f) * 0xE0).toInt()))
            }
            val sp = (clock * 6f).toInt() % 2
            rect(c, x - 5, S + 29, 11, 2, a(K(0xFFC8E8F8), 0x60)); px(c, x - 6 + sp * 12, S + 28, a(PX.W, 0xC0)); px(c, x + 6 - sp * 12, S + 28, a(PX.W, 0xC0))
        }
    }
    private fun fog(c: Canvas, clock: Float, cxT: Float, cyT: Float) {
        // rolls in off the bay over the yard
        val yx0 = S; val yy0 = 38 * S; val yx1 = 59 * S; val yy1 = 47 * S
        val vx0 = (cxT * S).toInt(); val vy0 = (cyT * S).toInt()
        if (vy0 + H < yy0) return
        c.save(); c.clipRect(max(yx0, vx0), max(yy0, vy0), min(yx1, vx0 + W), min(yy1, vy0 + H))
        for ((sp, off) in listOf(6f to 0, 11f to 47)) {
            val sx = ((clock * sp + off) % 128f).toInt()
            var y = yy0 - 30 + off % 40
            while (y < yy1) { var x = yx0 - 128 + sx; while (x < yx1) { c.drawBitmap(fogTile, x.toFloat(), y.toFloat(), plain); x += 128 }; y += 128 }
        }
        c.restore()
    }
    private fun searchlight(c: Canvas, clock: Float) {
        // the tower's searchlight sweeps the yard
        val cx = 56.5f * S; val cy = 45f * S
        val ang = PI.toFloat() + 0.55f * sin(clock * 0.35f) - 0.3f
        c.save(); c.clipRect(S, 38 * S, 59 * S, 47 * S)
        c.rotate((ang * 180f / PI.toFloat()), cx, cy)
        c.drawBitmap(searchCone, cx, cy - searchCone.height / 2f, plain)
        c.restore()
    }

    // ----- the laundry as it is right now -----
    private fun laundryNow(c: Canvas, clock: Float) {
        for (m in world.machines) {
            val x = m.c * S; val y = m.r * S
            if (m.running) {
                val a0 = (clock * 12f).toInt() % 4
                rect(c, x + 5, y + 6, 6, 6, if (m.dryer) K(0xFF8A6A3A) else K(0xFF2E5E8C))
                rect(c, x + 5 + (a0 % 2) * 3, y + 6 + (a0 / 2) * 3, 3, 3, if (m.dryer) K(0xFFE8C890) else K(0xFFA8D0F0))
                px(c, x + 6, y + 6, PX.W)
                px(c, x + 11, y + 2, PX.GOLD); px(c, x + 12, y + 2, PX.GOLD)
                if (!m.dryer && (clock * 8f).toInt() % 2 == 0) rect(c, x + 1, y + 15, 14, 1, a(K(0xFF7AA8D0), 0x60))   // a little water on the floor
            }
            if (m.ready) {
                px(c, x + 11, y + 2, K(0xFF3BFF9A)); px(c, x + 12, y + 2, K(0xFF3BFF9A))
                if ((clock * 3f).toInt() % 2 == 0) rect(c, x + 5, y + 6, 6, 6, if (m.dryer) K(0xFFE8E4D8) else K(0xFF7A98B8))
            }
        }
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val ch = world.tile(col, r)
            if (ch != 'U' && ch != 'C') continue
            val n = if (ch == 'U') world.dirtyBags else world.delivered
            for (i in 0 until n.coerceAtMost(3)) {
                val bx = col * S + 3 + i * 4; val by = r * S + 2 - (i % 2)
                if (ch == 'U') { rect(c, bx, by, 4, 6, K(0xFF5A4A34)); rect(c, bx, by, 4, 1, K(0xFF8A7A5A)); px(c, bx + 1, by - 1, K(0xFF5A4A34)) }
                else { rect(c, bx, by + 1, 4, 5, PX.W); rect(c, bx, by + 3, 4, 1, K(0xFFB8C8D8)) }
            }
        }
        world.station()?.let { st ->
            if (world.prompt().isNotEmpty() && (clock * 2.5f).toInt() % 2 == 0) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = PX.GOLD
                c.drawRect(st.c * S + 0.5f, st.r * S + 0.5f, st.c * S + S - 0.5f, st.r * S + S - 0.5f, p); p.style = Paint.Style.FILL
            }
        }
        if (world.folding >= 0f) world.station()?.let { st -> rect(c, st.c * S, st.r * S - 4, S, 3, PX.K); rect(c, st.c * S, st.r * S - 4, (S * world.folding / PrisonWorld.FOLD_S).toInt(), 3, PX.GOLD) }
    }

    /** The bag you're carrying, held in front of you. */
    private fun bag(c: Canvas, fx: Int, fy: Int, carry: PrisonWorld.Carry) {
        val (body, top) = when (carry) {
            PrisonWorld.Carry.DIRTY -> K(0xFF8A7A5A) to K(0xFF6A5A40)
            PrisonWorld.Carry.WET -> K(0xFF7A98B8) to K(0xFF5A7898)
            PrisonWorld.Carry.DRY -> K(0xFFE8E4D8) to K(0xFFC8C4B8)
            PrisonWorld.Carry.FOLDED -> PX.W to K(0xFFB8C8D8)
            PrisonWorld.Carry.NONE -> return
        }
        if (carry == PrisonWorld.Carry.FOLDED) { rect(c, fx - 6, fy - 16, 12, 8, PX.K); for (i in 0..2) rect(c, fx - 5, fy - 10 - i * 2, 10, 2, if (i % 2 == 0) body else top); return }
        rect(c, fx - 5, fy - 13, 11, 9, PX.K); rect(c, fx - 4, fy - 12, 9, 7, body); rect(c, fx - 4, fy - 12, 9, 1, lite(body, 24)); rect(c, fx - 1, fy - 14, 3, 2, top)
        if (carry == PrisonWorld.Carry.WET) { px(c, fx - 3, fy - 3, K(0xFF9AC0E0)); px(c, fx + 2, fy - 1, K(0xFF9AC0E0)) }
    }

    /** Draws the whole prison at once (for the tests: the way to check the layout and the lighting). */
    fun renderWholeMap(night: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(if (night) bgNight else bgDay, 0f, 0f, plain)
        val (dc, dr) = world.spec.myDoor
        bars(c, dc * S, dr * S, world.doorOpen, true)
        for (n in world.npcs) folk(c, (n.x * S).roundToInt(), (n.y * S).roundToInt() + 6, dirOf(n.face), 0, if (n.guard) guardOutfit else inmates[n.id % inmates.size], false, false)
        folk(c, (world.px * S).roundToInt(), (world.py * S).roundToInt() + 6, 2, 0, you, false, false)
        return out
    }

    // light both versions now, while the screen is still black from the fade into the level, so lights-out never stutters.
    // (Not on a background thread: the drawing helpers share one Paint with the frame drawing.)
    init { bgDay; bgNight }
}
