package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-art look for a level: the scene is drawn at 16 pixels per tile into a bitmap which the game scales up with hard edges.
 * Three-quarter view (like an old top-down adventure): buildings show their fronts with windows and doors, roofs are above
 * them, people are drawn standing. Night: dark blues and blacks, white lamplight, warm windows, red and blue police lights.
 * The ground and buildings are drawn once; people, props that people can walk behind, lights and effects are redrawn each frame.
 */
internal class PixelScene(private val spec: LevelSpec, private val world: StealthWorld) {
    private val S = 16
    val W = world.cols * S
    val H = world.rows * S
    private val base = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)       // ground and buildings
    private val glowBase = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)   // lamp and window light that never changes
    private val darkBase = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)   // the night, with the fixed lights cut out of it
    private val dark = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    private val dc = Canvas(dark)
    private val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; isFilterBitmap = false }
    private val punch = Paint().apply { isAntiAlias = false; isFilterBitmap = false; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val plain = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val path = Path()
    private val kmap = Array(world.rows) { IntArray(world.cols) { 99 } }

    private object C {
        const val K = 0xFF03050A.toInt()
        const val N0 = 0xFF070B18.toInt()
        const val N1 = 0xFF0B1230.toInt()
        const val N2 = 0xFF121D47.toInt()
        const val N3 = 0xFF1B2B66.toInt()
        const val B1 = 0xFF2B4FA8.toInt()
        const val B2 = 0xFF4C7DFF.toInt()
        const val ICE = 0xFFA9CCFF.toInt()
        const val W = 0xFFFFFFFF.toInt()
        const val CYAN = 0xFF29F0FF.toInt()
        const val MAG = 0xFFFF38D6.toInt()
        const val WARM = 0xFFFFD27A.toInt()
        const val RED = 0xFFFF3B55.toInt()
        const val BLUE = 0xFF3B6BFF.toInt()
        const val LAMP = 0xFFDCEBFF.toInt()
        const val GOLD = 0xFFFFD84D.toInt()
        const val SKIN = 0xFFE2B590.toInt()
        const val NIGHT = 0xFF040824.toInt()
    }

    // ---------- helpers ----------
    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) }
    private fun px(c: Canvas, x: Int, y: Int, color: Int) = rect(c, x, y, 1, 1, color)
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun hash(x: Int, y: Int, s: Int): Int {
        var v = x * 374761393 + y * 668265263 + s * 1274126177
        v = (v xor (v ushr 13)) * 1274126177
        return (v xor (v ushr 16)) and 0x7fffffff
    }
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private fun isWall(col: Int, row: Int) = world.tile(col, row).let { it == '#' || it == ' ' }

    /** A soft pool of dither dots as a small bitmap (used as a glow, or as a cut-out of the night). */
    private fun glowBitmap(r: Int, color: Int, maxA: Int): Bitmap {
        val bm = Bitmap.createBitmap(r * 2, r * 2, Bitmap.Config.ARGB_8888)
        val pix = IntArray(r * 2 * r * 2)
        for (y in 0 until r * 2) for (x in 0 until r * 2) {
            val d = hypot(x + 0.5f - r, y + 0.5f - r) / r
            if (d >= 1f) continue
            val t = (1f - d) * 3.4f
            val q = floor(t + bayer[(y and 3) * 4 + (x and 3)] / 16f).toInt().coerceIn(0, 3)
            if (q > 0) pix[y * r * 2 + x] = a(color, q * maxA / 3)
        }
        bm.setPixels(pix, 0, r * 2, 0, 0, r * 2, r * 2)
        return bm
    }
    private val lampGlow by lazy { glowBitmap(88, C.LAMP, 0x48) }
    private val lampHole by lazy { glowBitmap(88, C.K, 0xE0) }
    private val windowHole by lazy { glowBitmap(40, C.K, 0xC0) }
    private val windowWarm by lazy { glowBitmap(40, C.WARM, 0x42) }
    private val windowTv by lazy { glowBitmap(40, C.CYAN, 0x3A) }
    private val playerHole by lazy { glowBitmap(84, C.K, 0xF0) }
    private val redGlow by lazy { glowBitmap(96, C.RED, 0x58) }
    private val blueGlow by lazy { glowBitmap(96, C.BLUE, 0x58) }

    // ---------- the parts that never move ----------
    private fun computeK() {
        for (r in world.rows - 1 downTo 0) for (c in 0 until world.cols) {
            if (!isWall(c, r)) continue
            kmap[r][c] = if (r == world.rows - 1) 99 else if (!isWall(c, r + 1)) 0 else min(99, kmap[r + 1][c] + 1)
        }
    }

    private val brickTones = intArrayOf(0xFF1E2C66.toInt(), 0xFF2A2352.toInt(), 0xFF1D3B55.toInt(), 0xFF352445.toInt())

    private fun buildStatic() {
        val c = Canvas(base)
        rect(c, 0, 0, W, H, C.K)
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (!isWall(col, row)) groundTile(c, col, row)
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (isWall(col, row)) wallTile(c, col, row)
        // shadows on the ground at the foot of every wall
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (!isWall(col, row)) {
            val x0 = col * S; val y0 = row * S
            if (isWall(col, row - 1)) for (i in 0 until 5) rect(c, x0, y0 + i, S, 1, a(C.K, 0x80 - i * 0x18))
            if (isWall(col - 1, row)) for (i in 0 until 3) rect(c, x0 + i, y0, 1, S, a(C.K, 0x60 - i * 0x1c))
            if (isWall(col + 1, row)) for (i in 0 until 3) rect(c, x0 + S - 1 - i, y0, 1, S, a(C.K, 0x60 - i * 0x1c))
        }
        // posters and radio windows are part of the facade
        for ((i, pr) in spec.props.withIndex()) when (pr.kind) {
            "window" -> openWindow(c, (pr.x * S).toInt(), (pr.y * S).toInt(), i)
            "poster" -> poster(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "news" -> news(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "stuff" -> stuff(c, (pr.x * S).toInt(), (pr.y * S).toInt())
        }
        // light: lamp pools and window glow that never change
        val g = Canvas(glowBase); val h = Canvas(darkBase)
        h.drawColor(a(C.NIGHT, 0xA6))
        for ((lx, ly) in world.lamps) {
            g.drawBitmap(lampGlow, lx * S - 88, ly * S - 88, plain)
            h.drawBitmap(lampHole, lx * S - 88, ly * S - 88, punch)
        }
        for ((i, pr) in spec.props.withIndex()) if (pr.kind == "window") {
            val tv = i % 4 == 2
            g.drawBitmap(if (tv) windowTv else windowWarm, pr.x * S - 40, pr.y * S + 6 - 40, plain)
            h.drawBitmap(windowHole, pr.x * S - 40, pr.y * S + 6 - 40, punch)
        }
    }

    private fun groundTile(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        rect(c, x0, y0, S, S, C.N1)
        for (y in 0 until S) for (x in 0 until S) {
            val v = hash(x0 + x, y0 + y, 1) % 100
            if (v < 8) px(c, x0 + x, y0 + y, C.N2) else if (v < 11) px(c, x0 + x, y0 + y, C.N0)
        }
        val off = if (row % 2 == 0) 0 else 4
        for (x in 0 until S) if (x % 4 != 3) { px(c, x0 + x, y0 + 7, C.N0); px(c, x0 + x, y0 + 15, C.N0) }
        for (y in 0 until 7) px(c, x0 + (3 + off) % 16, y0 + y, C.N0)
        for (y in 8 until 15) px(c, x0 + (11 + off) % 16, y0 + y, C.N0)
        if (hash(col, row, 5) % 9 == 0) {   // a puddle that catches the lights
            val px0 = x0 + 3 + hash(col, row, 6) % 5; val py0 = y0 + 5 + hash(col, row, 7) % 4
            for (dy in 0..3) for (dx in 0..8) if (((dx - 4) * (dx - 4)) / 16f + ((dy - 1.5f) * (dy - 1.5f)) / 4f <= 1f) px(c, px0 + dx, py0 + dy, if ((dx + dy) % 3 == 0) C.B1 else C.N3)
            px(c, px0 + 2, py0 + 1, C.ICE); px(c, px0 + 6, py0 + 2, C.CYAN)
        }
    }

    private fun brick(c: Canvas, x0: Int, y0: Int, tone: Int, seed: Int) {
        for (y in 0 until S) for (x in 0 until S) {
            val brow = y / 4
            val offs = if (brow % 2 == 0) 0 else 4
            val mortar = y % 4 == 3 || (x + offs) % 8 == 7
            val v = hash(x0 + x, y0 + y, seed) % 100
            px(c, x0 + x, y0 + y, if (mortar) C.K else if (v < 16) tone + 0x00080808 else if (v < 26) tone - 0x00080808 else tone)
        }
    }

    private fun wallTile(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        val k = kmap[row][col]
        val tone = brickTones[hash(col / 3, row / 8, 11) % brickTones.size]
        val n = !isWall(col, row - 1); val w = !isWall(col - 1, row); val e = !isWall(col + 1, row)
        if (k == 0 || k == 1) {
            brick(c, x0, y0, tone, 3)
            val roofAbove = row > 0 && isWall(col, row - 1) && kmap[row - 1][col] >= 2
            if (k == 0) {
                rect(c, x0, y0 + 12, S, 4, C.N3); rect(c, x0, y0 + 12, S, 1, C.B1)         // stone base course
                when (hash(col, row, 2) % 8) {
                    0 -> door(c, x0 + 4, y0 + 2)
                    1, 2 -> shopWindow(c, x0 + 2, y0 + 3, hash(col, row, 4) % 3)
                    3 -> pipe(c, x0 + 7, y0)
                    else -> smallWindow(c, x0 + 5, y0 + 3, hash(col, row, 5) % 4 == 0)
                }
            } else {
                when (hash(col, row, 6) % 7) {
                    0 -> fireEscape(c, x0, y0)
                    1 -> pipe(c, x0 + 7, y0)
                    else -> smallWindow(c, x0 + 5, y0 + 5, hash(col, row, 8) % 4 == 0)
                }
                if (hash(col, row, 9) % 9 == 0) acUnit(c, x0 + 10, y0 + 9)
            }
            if (roofAbove) { rect(c, x0, y0, S, 2, 0xFF6C88C4.toInt()); rect(c, x0, y0 + 2, S, 2, a(C.K, 0x90)) }           // cornice
            if (w) rect(c, x0, y0, 1, S, C.B1)
            if (e) rect(c, x0 + S - 1, y0, 1, S, C.N0)
        } else {
            // roof seen from above and in front: dark gravel, an edge where it is open to the north, vents and boxes on top
            rect(c, x0, y0, S, S, C.N0)
            for (y in 0 until S) for (x in 0 until S) { val v = hash(x0 + x, y0 + y, 12) % 100; if (v < 12) px(c, x0 + x, y0 + y, C.N1) else if (v < 16) px(c, x0 + x, y0 + y, C.N2) }
            if (n) { rect(c, x0, y0, S, 2, C.B2); rect(c, x0, y0 + 2, S, 1, C.B1) }
            if (w) rect(c, x0, y0, 1, S, C.B1)
            if (e) rect(c, x0 + S - 1, y0, 1, S, C.N3)
            when (hash(col, row, 13) % 7) {
                0 -> { rect(c, x0 + 3, y0 + 5, 9, 7, C.K); rect(c, x0 + 4, y0 + 6, 7, 5, C.N3); rect(c, x0 + 4, y0 + 6, 7, 1, C.B1) }   // a skylight
                1 -> { rect(c, x0 + 6, y0 + 2, 4, 11, C.K); rect(c, x0 + 7, y0 + 3, 2, 9, C.N3) }                                    // chimney
                2 -> { rect(c, x0 + 2, y0 + 6, 12, 6, C.K); rect(c, x0 + 3, y0 + 7, 10, 4, C.N2); for (i in 0..2) px(c, x0 + 5 + i * 3, y0 + 9, C.N0) }   // an air unit
                3 -> { rect(c, x0 + 3, y0 + 3, 10, 10, C.K); rect(c, x0 + 4, y0 + 4, 8, 8, C.N2); rect(c, x0 + 4, y0 + 4, 8, 1, C.B1); for (i in 0..3) px(c, x0 + 5 + i * 2, y0 + 8, C.N0) }   // a water tank
                4 -> { rect(c, x0 + 4, y0 + 5, 8, 6, C.K); rect(c, x0 + 5, y0 + 6, 6, 4, C.N2); rect(c, x0 + 7, y0 + 6, 2, 4, C.N0) }   // a roof hatch
                5 -> { rect(c, x0 + 2, y0 + 8, 12, 4, C.N3); rect(c, x0 + 2, y0 + 8, 12, 1, C.B1) }   // a low brick parapet
            }
        }
    }

    private fun door(c: Canvas, x: Int, y: Int) {
        rect(c, x - 1, y - 1, 10, 13, C.K); rect(c, x, y, 8, 12, 0xFF3A2A4A.toInt()); rect(c, x, y, 8, 1, 0xFF5A4378.toInt())
        rect(c, x + 1, y + 1, 6, 4, 0xFF1A1230.toInt()); px(c, x + 6, y + 7, C.GOLD)
        rect(c, x - 1, y + 11, 10, 2, C.N3)                          // step
    }

    private fun shopWindow(c: Canvas, x: Int, y: Int, kind: Int) {
        rect(c, x, y, 12, 8, C.K)
        val glass = when (kind) { 0 -> 0xFF10244A.toInt(); 1 -> 0xFF3A2A12.toInt(); else -> 0xFF0E2E3A.toInt() }
        rect(c, x + 1, y + 1, 10, 6, glass)
        px(c, x + 2, y + 2, C.ICE); px(c, x + 3, y + 2, C.ICE); px(c, x + 8, y + 5, a(C.ICE, 0x90))     // reflections
        if (kind == 1) { rect(c, x + 2, y + 4, 8, 2, 0xFF9A7A2D.toInt()); px(c, x + 3, y + 3, C.WARM) }   // goods in the window
        rect(c, x, y - 2, 12, 2, if (kind == 0) 0xFFBF2B3F.toInt() else C.B1)                               // a small awning
    }

    private fun smallWindow(c: Canvas, x: Int, y: Int, lit: Boolean) {
        rect(c, x - 1, y - 1, 8, 9, C.K); rect(c, x, y, 6, 7, if (lit) 0xFFCFA350.toInt() else 0xFF0C1A3C.toInt())
        rect(c, x + 3, y, 1, 7, C.K); rect(c, x, y + 3, 6, 1, C.K)
        if (!lit) { px(c, x + 1, y + 1, C.B1); px(c, x + 4, y + 4, C.N3) }
        rect(c, x - 1, y + 7, 8, 1, C.N3)
    }

    private fun fireEscape(c: Canvas, x0: Int, y0: Int) {
        for (i in 0..3) rect(c, x0 + 2 + i * 4, y0 + 2, 1, 12, C.K)
        rect(c, x0 + 1, y0 + 8, 14, 1, C.N3); rect(c, x0 + 1, y0 + 13, 14, 1, C.N3); rect(c, x0 + 1, y0 + 2, 14, 1, C.N3)
        for (i in 0..5) px(c, x0 + 2 + i * 2, y0 + 10 + (i % 2), C.K)
    }
    private fun pipe(c: Canvas, x: Int, y: Int) { rect(c, x, y, 3, S, C.K); rect(c, x + 1, y, 1, S, C.N3); rect(c, x, y + 5, 3, 1, C.B1); rect(c, x, y + 12, 3, 1, C.B1) }
    private fun acUnit(c: Canvas, x: Int, y: Int) { rect(c, x, y, 6, 5, C.K); rect(c, x + 1, y + 1, 4, 3, C.N3); px(c, x + 2, y + 2, C.N0); px(c, x + 3, y + 2, C.N0) }

    /** A radio window: open, lit from inside, curtain stirring, the radio on the sill. Part of the building. */
    private fun openWindow(c: Canvas, cx: Int, cy: Int, i: Int) {
        val tv = i % 4 == 2
        val x = cx - 6; val y = cy - 7
        rect(c, x - 1, y - 1, 14, 15, C.K)
        rect(c, x, y, 12, 12, 0xFF2A1F3A.toInt())                                            // frame
        rect(c, x + 1, y + 1, 10, 9, if (tv) 0xFF2A9BA8.toInt() else 0xFFE0B060.toInt())      // the room inside
        rect(c, x + 1, y + 1, 10, 3, if (tv) 0xFF55E0F0.toInt() else C.WARM)                  // light at the top of the room
        rect(c, x + 5, y + 1, 1, 9, a(C.K, 0xA0))                                             // the open casement
        rect(c, x + 1, y + 1, 3, 9, a(0xFF5A2A44.toInt(), 0xE0)); rect(c, x + 2, y + 5, 1, 5, a(C.K, 0x50))    // curtain
        rect(c, x + 9, y + 1, 2, 9, a(0xFF5A2A44.toInt(), 0xC0))
        rect(c, x - 1, y + 11, 14, 2, C.N3); rect(c, x - 1, y + 11, 14, 1, C.B2)              // the sill
        rect(c, x + 3, y + 7, 6, 4, C.K); rect(c, x + 4, y + 8, 4, 2, 0xFF3A3F55.toInt())    // the radio
        px(c, x + 5, y + 8, C.W); px(c, x + 6, y + 9, C.RED)
        for (k in 0..3) px(c, x + 9 + k / 2, y + 6 - k, C.ICE)                                // its aerial
    }

    private fun poster(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 4, cy - 5, 9, 11, C.K); rect(c, cx - 3, cy - 4, 7, 9, C.ICE)
        rect(c, cx - 3, cy - 4, 7, 2, C.RED)
        rect(c, cx - 1, cy - 1, 3, 3, 0xFF3A3F55.toInt()); rect(c, cx - 2, cy + 2, 5, 1, 0xFF3A3F55.toInt())
        rect(c, cx - 2, cy + 4, 5, 1, C.N2)
    }
    private fun news(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 5, cy - 2, 10, 6, C.K); rect(c, cx - 4, cy - 1, 8, 4, C.ICE)
        rect(c, cx - 4, cy - 1, 8, 1, C.N3); rect(c, cx - 3, cy + 1, 3, 2, C.N2); rect(c, cx + 1, cy + 1, 3, 1, C.N2)
    }
    private fun stuff(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 6, cy - 1, 6, 3, C.N3); rect(c, cx - 6, cy - 1, 6, 1, C.B1)
        rect(c, cx + 1, cy - 3, 4, 4, C.MAG); rect(c, cx + 1, cy - 3, 4, 1, C.W)
        rect(c, cx - 2, cy + 3, 4, 2, C.ICE); rect(c, cx + 3, cy + 2, 3, 2, C.ICE)
    }

    // ---------- things people can walk behind (drawn front-to-back together with the people) ----------
    private class Item(val key: Float, val draw: (Canvas) -> Unit)
    private val items = ArrayList<Item>()

    private fun buildItems() {
        // tiles taken by a car or the stand are drawn by those props, not as plain crates
        val covered = HashSet<Pair<Int, Int>>()
        for (pr in spec.props) if (pr.kind == "car" || pr.kind == "stand") {
            val c0 = (pr.x - pr.w / 2f + 0.001f).toInt(); val r0 = floor(pr.y - 0.001f).toInt()
            for (i in 0 until pr.w) covered += (c0 + i) to r0
        }
        for (row in 0 until world.rows) for (col in 0 until world.cols) {
            val t = world.tile(col, row)
            if ((col to row) in covered) continue
            if (t == 'c') items += Item(row * S + 15f) { c -> bin(c, col, row) }
            if (t == 'd') items += Item(row * S + 15f) { c -> crate(c, col, row) }
        }
        for ((lx, ly) in world.lamps) items += Item(ly * S + 8f) { c -> lampPost(c, (lx * S).toInt(), (ly * S).toInt() + 8) }
        for (pr in spec.props) {
            val c0 = pr.x - pr.w / 2f
            if (pr.kind == "car") items += Item(pr.y * S + 8f) { c -> car(c, (c0 * S).toInt(), (pr.y * S).toInt() + 8, pr.w) }
            if (pr.kind == "stand") items += Item(pr.y * S + 8f) { c -> stand(c, (c0 * S).toInt(), (pr.y * S).toInt() + 8, pr.w) }
        }
    }

    private fun bin(c: Canvas, col: Int, row: Int) {
        val x = col * S + 1; val y = row * S + 3
        rect(c, x, y + 12, 14, 1, a(C.K, 0x90))
        rect(c, x, y + 3, 14, 9, C.K); rect(c, x + 1, y + 4, 12, 7, 0xFF145A4F.toInt())              // the front
        for (i in 0 until 5) rect(c, x + 2 + i * 2, y + 5, 1, 5, 0xFF0C3A34.toInt())
        rect(c, x - 1, y, 16, 4, C.K); rect(c, x, y + 1, 14, 2, 0xFF1E8070.toInt()); rect(c, x, y + 1, 14, 1, C.CYAN)     // the lid
    }
    private fun crate(c: Canvas, col: Int, row: Int) {
        val x = col * S + 1; val y = row * S + 2
        rect(c, x, y + 13, 14, 1, a(C.K, 0x90))
        rect(c, x, y + 1, 14, 12, C.K); rect(c, x + 1, y + 2, 12, 10, 0xFF3A2F58.toInt())
        rect(c, x + 1, y + 2, 12, 1, 0xFF6A5A90.toInt())
        for (i in 0 until 10) { px(c, x + 2 + i, y + 3 + i, 0xFF241B3A.toInt()); px(c, x + 11 - i, y + 3 + i, 0xFF241B3A.toInt()) }
    }
    private fun lampPost(c: Canvas, x: Int, y: Int) {
        rect(c, x - 1, y - 24, 2, 25, C.K); rect(c, x, y - 24, 1, 25, C.N3)
        rect(c, x - 3, y - 28, 7, 4, C.K); rect(c, x - 2, y - 27, 5, 2, C.W); rect(c, x - 1, y - 26, 3, 1, C.LAMP)
        rect(c, x - 2, y, 5, 2, C.K)
    }
    private fun car(c: Canvas, x: Int, y: Int, w: Int) {
        val ww = w * S - 2
        rect(c, x + 1, y + 6, ww, 2, a(C.K, 0xA0))
        rect(c, x + 3, y - 1, 4, 3, C.K); rect(c, x + ww - 5, y - 1, 4, 3, C.K)                       // far wheels
        rect(c, x + 1, y - 6, ww, 12, C.K); rect(c, x + 2, y - 5, ww - 2, 10, 0xFF16224F.toInt())         // body
        rect(c, x + 2, y - 1, ww - 2, 5, 0xFFEEF0F6.toInt())                                                // white doors
        rect(c, x + 3, y - 5, ww - 4, 4, 0xFF28407A.toInt()); rect(c, x + 4, y - 4, ww - 6, 2, 0xFF08122E.toInt())   // cab and windows
        rect(c, x + 2, y + 1, ww - 2, 1, 0xFF16224F.toInt()); rect(c, x + 5, y + 2, 5, 1, C.GOLD)
        rect(c, x + 3, y + 5, 4, 3, C.K); rect(c, x + ww - 5, y + 5, 4, 3, C.K)                          // near wheels
        val flash = (clockNow * 3f).toInt() % 2 == 0
        rect(c, x + ww / 2 - 4, y - 7, 4, 2, if (flash) C.RED else 0xFF7A1A28.toInt())
        rect(c, x + ww / 2, y - 7, 4, 2, if (flash) 0xFF1A2E7A.toInt() else C.BLUE)
    }
    private fun stand(c: Canvas, x: Int, y: Int, w: Int) {
        val ww = w * S - 2
        rect(c, x + 1, y + 6, ww, 2, a(C.K, 0xA0))
        rect(c, x + 1, y - 4, ww, 10, C.K); rect(c, x + 2, y - 3, ww - 2, 8, 0xFF4A3A22.toInt())         // the counter
        rect(c, x + 2, y - 3, ww - 2, 1, 0xFF8A6A3A.toInt())
        for (i in 0 until ww / 6) { rect(c, x + 3 + i * 6, y - 8, 5, 5, C.ICE); rect(c, x + 3 + i * 6, y - 8, 5, 1, C.N3); px(c, x + 4 + i * 6, y - 6, C.K); px(c, x + 6 + i * 6, y - 5, C.K) }   // newspapers
        for (i in 0 until ww / 4) rect(c, x + 1 + i * 4, y - 20, 4, 5, if (i % 2 == 0) C.RED else C.W)     // the striped awning
        rect(c, x + 1, y - 15, ww, 1, C.K)
        rect(c, x + 2, y - 15, 1, 8, C.K); rect(c, x + ww - 1, y - 15, 1, 8, C.K)                          // posts
    }

    // ---------- people ----------
    private class Outfit(
        val skin: Int, val hair: Int, val hat: Int?, val brim: Int?, val shirt: Int, val coat: Int?, val pants: Int, val shoes: Int,
        val belt: Int?, val badge: Boolean = false, val glasses: Boolean = false, val tie: Boolean = false, val apron: Int? = null,
        val radio: Boolean = false,
    )
    private val police = Outfit(C.SKIN, 0xFF1A1420.toInt(), 0xFF111B45.toInt(), 0xFF05081A.toInt(), 0xFF2F58C8.toInt(), null, 0xFF0E1736.toInt(), C.K, C.K, badge = true, radio = true)
    private val clerk = Outfit(C.SKIN, 0xFF4A2E1B.toInt(), null, null, C.W, 0xFF8AA0C4.toInt(), 0xFF2A3350.toInt(), 0xFF150F10.toInt(), null, glasses = true, tie = true)
    private val vendor = Outfit(C.SKIN, 0xFF59647A.toInt(), 0xFF59647A.toInt(), 0xFF3A4458.toInt(), 0xFFE8E0C8.toInt(), null, 0xFF3A4458.toInt(), C.K, null, apron = 0xFF2E8F66.toInt())

    /** Draws one person standing with their feet at (fx, fy). [dir]: 0 away from you, 1 right, 2 toward you, 3 left. */
    private fun folk(c: Canvas, fx: Int, fy: Int, dir: Int, frame: Int, o: Outfit, torch: Boolean, letter: Boolean) {
        rect(c, fx - 5, fy - 1, 11, 3, a(C.K, 0x70))     // shadow
        c.save()
        if (dir == 3) c.scale(-1f, 1f, fx.toFloat() + 0.5f, 0f)
        val side = dir == 1 || dir == 3
        val coatBottom = if (o.coat != null) 4 else 0
        if (!side) {
            // legs
            val lyL = if (frame == 1) 1 else 0
            rect(c, fx - 3, fy - 6 + lyL, 3, 5 - lyL, o.pants); rect(c, fx + 1, fy - 6, 3, 5, o.pants)
            rect(c, fx - 3, fy - 1, 3, 1, o.shoes); rect(c, fx + 1, fy - 1, 3, 1, o.shoes)
            // torso (and a long coat)
            rect(c, fx - 4, fy - 12, 9, 6 + coatBottom, o.coat ?: o.shirt)
            if (o.coat != null) {
                rect(c, fx - 4, fy - 12, 1, 6 + coatBottom, o.coat - 0x00181818); rect(c, fx, fy - 8, 1, 2 + coatBottom, o.coat - 0x00202020)
                if (dir == 2) { rect(c, fx - 1, fy - 12, 3, 3, o.shirt); if (o.tie) rect(c, fx, fy - 11, 1, 4, C.RED) }
            }
            if (o.apron != null && dir == 2) rect(c, fx - 3, fy - 10, 7, 6, o.apron)
            if (o.belt != null) rect(c, fx - 4, fy - 7, 9, 1, o.belt)
            if (o.badge && dir == 2) { px(c, fx + 2, fy - 10, C.GOLD); px(c, fx + 2, fy - 9, C.GOLD) }
            if (o.radio) { rect(c, fx - 4, fy - 12, 2, 3, C.K); px(c, fx - 3, fy - 12, C.RED) }
            // arms
            val sleeve = o.coat ?: o.shirt
            val swing = if (frame == 1) 1 else 0
            rect(c, fx - 6, fy - 12 + swing, 2, 6, sleeve); rect(c, fx + 5, fy - 12 - swing, 2, 6, sleeve)
            rect(c, fx - 6, fy - 6 + swing, 2, 1, o.skin); rect(c, fx + 5, fy - 6 - swing, 2, 1, o.skin)
            if (letter && dir == 2) { rect(c, fx + 4, fy - 8, 5, 4, C.K); rect(c, fx + 5, fy - 7, 3, 2, C.W); px(c, fx + 6, fy - 7, C.RED) }
            // head
            if (dir == 0) rect(c, fx - 3, fy - 18, 7, 7, o.hair) else {
                rect(c, fx - 3, fy - 18, 7, 7, o.skin)
                rect(c, fx - 3, fy - 18, 7, 2, o.hair); rect(c, fx - 3, fy - 18, 1, 4, o.hair); rect(c, fx + 3, fy - 18, 1, 4, o.hair)
                px(c, fx - 2, fy - 15, C.K); px(c, fx + 2, fy - 15, C.K)
                if (o.glasses) { rect(c, fx - 3, fy - 15, 3, 1, C.ICE); rect(c, fx + 1, fy - 15, 3, 1, C.ICE); px(c, fx - 2, fy - 15, C.K); px(c, fx + 2, fy - 15, C.K) }
                px(c, fx, fy - 13, o.skin - 0x00301818)
            }
            if (o.hat != null) {
                rect(c, fx - 4, fy - 20, 9, 3, o.hat); rect(c, fx - 4, fy - 20, 9, 1, o.hat + 0x00101820)
                if (dir == 2 && o.brim != null) rect(c, fx - 4, fy - 17, 9, 1, o.brim)
                if (o.badge && dir == 2) px(c, fx, fy - 19, C.GOLD)
            }
            if (torch && dir == 2) { rect(c, fx + 5, fy - 7, 2, 3, C.K); px(c, fx + 5, fy - 4, C.W); px(c, fx + 6, fy - 4, C.LAMP) }
        } else {
            val step = frame == 1
            // legs, striding
            if (step) { rect(c, fx - 4, fy - 6, 3, 5, o.pants); rect(c, fx + 1, fy - 6, 3, 5, o.pants); rect(c, fx - 5, fy - 1, 3, 1, o.shoes); rect(c, fx + 2, fy - 1, 3, 1, o.shoes) }
            else { rect(c, fx - 2, fy - 6, 5, 5, o.pants); rect(c, fx - 2, fy - 1, 6, 1, o.shoes) }
            // torso
            rect(c, fx - 3, fy - 12, 7, 6 + coatBottom, o.coat ?: o.shirt)
            if (o.coat != null) rect(c, fx - 3, fy - 8, 1, 2 + coatBottom, o.coat - 0x00202020)
            if (o.apron != null) rect(c, fx - 1, fy - 10, 5, 6, o.apron)
            if (o.belt != null) rect(c, fx - 3, fy - 7, 7, 1, o.belt)
            if (o.radio) { rect(c, fx - 3, fy - 12, 2, 3, C.K); px(c, fx - 3, fy - 12, C.RED) }
            // the near arm, forward
            rect(c, fx - 1, fy - 12, 2, 6, (o.coat ?: o.shirt) - 0x00101010); rect(c, fx - 1, fy - 6, 2, 1, o.skin)
            if (torch) { rect(c, fx + 1, fy - 8, 4, 2, C.K); rect(c, fx + 4, fy - 8, 2, 2, C.W); px(c, fx + 6, fy - 8, C.LAMP) }
            if (letter) { rect(c, fx + 1, fy - 9, 5, 4, C.K); rect(c, fx + 2, fy - 8, 3, 2, C.W); px(c, fx + 3, fy - 8, C.RED) }
            // head, in profile
            rect(c, fx - 2, fy - 18, 7, 7, o.skin)
            rect(c, fx - 3, fy - 18, 5, 7, o.hair); rect(c, fx - 2, fy - 18, 7, 2, o.hair)
            px(c, fx + 3, fy - 15, C.K); px(c, fx + 5, fy - 14, o.skin - 0x00301818)
            if (o.glasses) { px(c, fx + 4, fy - 15, C.ICE); px(c, fx + 5, fy - 15, C.ICE) }
            if (o.hat != null) {
                rect(c, fx - 3, fy - 20, 8, 3, o.hat); rect(c, fx - 3, fy - 20, 8, 1, o.hat + 0x00101820)
                if (o.brim != null) rect(c, fx + 2, fy - 17, 5, 1, o.brim)
                if (o.badge) px(c, fx + 3, fy - 19, C.GOLD)
            }
        }
        c.restore()
    }

    private fun dirOf(face: Float): Int {
        val dx = cos(face); val dy = sin(face)
        return if (abs(dx) > abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0)
    }

    /** The speech bubble of dots over the cop's head, one dot at a time. */
    private fun dotsBubble(c: Canvas, cx: Int, topY: Int, n: Int) {
        val x = cx - 10; val y = topY - 12
        rect(c, x - 1, y - 1, 22, 11, C.K); rect(c, x, y, 20, 9, C.W)
        rect(c, cx - 2, y + 9, 5, 1, C.K); rect(c, cx - 1, y + 9, 3, 1, C.W); rect(c, cx - 1, y + 10, 2, 2, C.K); px(c, cx, y + 10, C.W)
        for (i in 0 until n.coerceIn(0, 3)) rect(c, x + 4 + i * 5, y + 4, 3, 3, C.K)
    }

    // ---------- every frame ----------
    private var clockNow = 0f

    fun render(clock: Float, read: Set<Int>, bc: Float): Bitmap {
        clockNow = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, plain)

        // everything standing in the scene, sorted so people pass in front of and behind things
        val list = ArrayList<Item>(items)
        val pxx = (world.px * S).roundToInt(); val pyy = (world.py * S).roundToInt() + 6
        val walkFrame = if (world.speed > 0.05f) ((clock * (3f + world.speed * 2.2f)).toInt() % 2) else 0
        list += Item(pyy.toFloat()) { cv -> folk(cv, pxx, pyy, dirOf(world.face), walkFrame, clerk, false, true) }
        val vx = (world.contactX * S).roundToInt(); val vy = (world.contactY * S).roundToInt() + 6
        list += Item(vy.toFloat()) { cv -> folk(cv, vx, vy, 2, 0, vendor, false, false) }
        world.cop?.let { cop ->
            if (cop.visible) {
                val fx = (cop.x * S).roundToInt(); val fy = (cop.y * S).roundToInt() + 6
                list += Item(fy.toFloat()) { cv -> folk(cv, fx, fy, dirOf(cop.face), if (cop.walking) ((clock * 4f).toInt() % 2) else 0, police, cop.torch, false) }
            }
        }
        for (g in world.guards) if (!g.dead) {
            val fx = (g.x * S).roundToInt(); val fy = (g.y * S).roundToInt() + 6
            list += Item(fy.toFloat()) { cv -> folk(cv, fx, fy, dirOf(g.face), 0, police, true, false) }
        }
        list.sortBy { it.key }
        for (item in list) item.draw(c)

        // the night, with lights cut out of it
        dc.drawColor(0, PorterDuff.Mode.CLEAR)
        dc.drawBitmap(darkBase, 0f, 0f, plain)
        dc.drawBitmap(playerHole, world.px * S - 84, world.py * S - 84, punch)
        world.cop?.let { if (it.visible && it.torch) dc.drawBitmap(windowHole, it.x * S - 40, it.y * S - 40, punch) }
        c.drawBitmap(dark, 0f, 0f, plain)
        c.drawBitmap(glowBase, 0f, 0f, plain)

        // police lights, flashing red and blue off the walls
        for ((i, f) in spec.flashers.withIndex()) {
            val ph = (clock * 2.6f + i * 0.43f) % 1f
            val bm = if (ph < 0.42f) redGlow else if (ph in 0.5f..0.92f) blueGlow else null
            if (bm != null && abs(f.second * S - world.py * S) < 300f) c.drawBitmap(bm, f.first * S - 96, f.second * S - 96, plain)
        }
        // torch beams sweeping about in the distance
        for (b in spec.beams) {
            val ang = (b.baseDeg + b.sweepDeg * sin(2 * PI.toFloat() * clock / b.period + b.phase)) * PI.toFloat() / 180f
            beam(c, Eye(b.x, b.y, ang, b.half, b.range), 0x34)
        }
        world.cop?.let { cop -> if (cop.visible && cop.torch) beam(c, Eye(cop.x, cop.y, cop.face, 0.3f, 4.2f), 0x30) }
        for (g in world.guards) if (!g.dead) beam(c, g.eye(), 0x34)

        // the cop's dots
        world.cop?.let { cop -> if (cop.visible && cop.dots > 0) dotsBubble(c, (cop.x * S).roundToInt(), (cop.y * S).roundToInt() + 6 - 20, cop.dots) }

        // fog of war: the part of the map you have not been near yet
        val sc = spec.scene
        if (sc != null && world.fog > 0.01f) {
            val f = sc.fog
            p.style = Paint.Style.FILL; p.color = a(C.K, (world.fog * 255).toInt())
            c.drawRect((f[0] * S).toFloat(), (f[1] * S).toFloat(), ((f[2] + 1) * S).toFloat(), ((f[3] + 1) * S).toFloat(), p)
        }
        return frame
    }

    /** A torch beam: a hard-edged wedge of cold light that stops at walls. */
    private fun beam(c: Canvas, e: Eye, alpha: Int) {
        path.reset(); path.moveTo(e.x * S, e.y * S)
        val n = 40
        for (i in 0..n) {
            val ang = e.face - e.half + 2 * e.half * i / n
            val l = world.rayLength(e, ang)
            path.lineTo(e.x * S + cos(ang) * l * S, e.y * S + sin(ang) * l * S)
        }
        path.close()
        p.style = Paint.Style.FILL; p.color = a(C.LAMP, alpha); c.drawPath(path, p)
    }

    init { computeK(); buildStatic(); buildItems() }   // last, so every property above exists first
}
