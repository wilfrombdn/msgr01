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
internal class PixelScene(private val spec: LevelSpec, private val world: StealthWorld) : PixelSprites() {
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
    private val path = Path()
    private val kmap = Array(world.rows) { IntArray(world.cols) { 99 } }


    private fun isWall(col: Int, row: Int) = world.tile(col, row).let { it == '#' || it == ' ' }
    private val lampGlow by lazy { glowBitmap(88, if (spec.theme == "sewer") PX.WARM else PX.LAMP, 0x48) }
    private val lampHole by lazy { glowBitmap(88, PX.K, 0xE0) }
    private val windowHole by lazy { glowBitmap(40, PX.K, 0xC0) }
    private val windowWarm by lazy { glowBitmap(40, PX.WARM, 0x42) }
    private val windowTv by lazy { glowBitmap(40, PX.CYAN, 0x3A) }
    private val playerHole by lazy { glowBitmap(84, PX.K, 0xF0) }
    private val redGlow by lazy { glowBitmap(96, PX.RED, 0x58) }
    private val blueGlow by lazy { glowBitmap(96, PX.BLUE, 0x58) }

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
        rect(c, 0, 0, W, H, PX.K)
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (!isWall(col, row)) groundTile(c, col, row)
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (isWall(col, row)) wallTile(c, col, row)
        // shadows on the ground at the foot of every wall
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (!isWall(col, row)) {
            val x0 = col * S; val y0 = row * S
            if (isWall(col, row - 1)) for (i in 0 until 5) rect(c, x0, y0 + i, S, 1, a(PX.K, 0x80 - i * 0x18))
            if (isWall(col - 1, row)) for (i in 0 until 3) rect(c, x0 + i, y0, 1, S, a(PX.K, 0x60 - i * 0x1c))
            if (isWall(col + 1, row)) for (i in 0 until 3) rect(c, x0 + S - 1 - i, y0, 1, S, a(PX.K, 0x60 - i * 0x1c))
        }
        // posters and radio windows are part of the facade
        for ((i, pr) in spec.props.withIndex()) when (pr.kind) {
            "window" -> openWindow(c, (pr.x * S).toInt(), (pr.y * S).toInt(), i)
            "poster" -> poster(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "news" -> news(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "stuff" -> stuff(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "streetsign" -> streetSign(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "sign" -> streetSign(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "ladder" -> ladder(c, (pr.x * S).toInt(), (pr.y * S).toInt())
        }
        // light: lamp pools and window glow that never change
        val g = Canvas(glowBase); val h = Canvas(darkBase)
        h.drawColor(a(if (sewer) 0xFF03100D.toInt() else PX.NIGHT, if (sewer) 0x9A else 0xA6))
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

    private val sewer get() = spec.theme == "sewer"

    /** Concrete wall of the maintenance room: big pipes along it, valves, grating; the ceiling above. */
    private fun sewerWall(c: Canvas, col: Int, row: Int, k: Int) {
        val x0 = col * S; val y0 = row * S
        for (y in 0 until S) for (x in 0 until S) {
            val block = y % 8 == 7 || (x + (if ((y / 8) % 2 == 0) 0 else 8)) % 16 == 15
            val v = hash(x0 + x, y0 + y, 21) % 100
            px(c, x0 + x, y0 + y, if (block) PX.K else if (v < 14) 0xFF1B3A38.toInt() else if (v < 22) 0xFF0F2422.toInt() else 0xFF15302F.toInt())
        }
        if (k >= 2) { rect(c, x0, y0, S, S, 0xFF0A1514.toInt()); for (y in 0 until S) for (x in 0 until S) if (hash(x0 + x, y0 + y, 22) % 100 < 10) px(c, x0 + x, y0 + y, 0xFF132624.toInt()); return }
        // a fat horizontal pipe along the wall, with flanges and rivets
        val py = if (k == 0) y0 + 5 else y0 + 4
        rect(c, x0, py, S, 8, PX.K); rect(c, x0, py + 1, S, 6, 0xFF2F6F73.toInt()); rect(c, x0, py + 1, S, 2, 0xFF63C7C2.toInt()); rect(c, x0, py + 5, S, 2, 0xFF1A4A4E.toInt())
        rect(c, x0 + 7, py - 1, 3, 10, PX.K); rect(c, x0 + 8, py, 1, 8, 0xFF9AA4B5.toInt())                                   // a flange
        px(c, x0 + 2, py + 3, PX.ICE); px(c, x0 + 13, py + 3, PX.ICE)
        if (hash(col, row, 23) % 4 == 0) {                                                                                     // a valve wheel and a drip
            rect(c, x0 + 3, py - 4, 1, 4, PX.K); rect(c, x0 + 1, py - 6, 5, 2, 0xFFBF2B3F.toInt()); rect(c, x0 + 2, py - 7, 3, 1, 0xFFFF6A7A.toInt())
        }
        if (k == 1) { pipeV(c, x0 + (hash(col, row, 24) % 9 + 2), y0, S) }
        if (k == 0) { rect(c, x0, y0 + 14, S, 2, 0xFF0A1A1A.toInt()); for (i in 0..3) px(c, x0 + 2 + i * 4, y0 + 15, 0xFF2F6F73.toInt()) }
    }
    private fun pipeV(c: Canvas, x: Int, y: Int, h: Int) { rect(c, x, y, 4, h, PX.K); rect(c, x + 1, y, 2, h, 0xFF2F6F73.toInt()); rect(c, x + 1, y, 1, h, 0xFF63C7C2.toInt()) }

    /** Grating on the maintenance room floor, with the odd greenish puddle. */
    private fun sewerGround(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        rect(c, x0, y0, S, S, 0xFF0B1A19.toInt())
        for (y in 0 until S) for (x in 0 until S) { val v = hash(x0 + x, y0 + y, 31) % 100; if (v < 9) px(c, x0 + x, y0 + y, 0xFF132B29.toInt()) else if (v < 12) px(c, x0 + x, y0 + y, 0xFF050E0D.toInt()) }
        for (i in 0 until S step 4) { rect(c, x0 + i, y0, 1, S, 0xFF050E0D.toInt()); rect(c, x0, y0 + i, S, 1, 0xFF050E0D.toInt()) }
        rect(c, x0, y0, S, 1, 0xFF1D4240.toInt()); rect(c, x0, y0, 1, S, 0xFF1D4240.toInt())
        if (hash(col, row, 32) % 8 == 0) { rect(c, x0 + 3, y0 + 6, 9, 4, 0xFF123A34.toInt()); rect(c, x0 + 5, y0 + 7, 3, 1, 0xFF3FBF9A.toInt()) }
    }

    /** The ladder up to the street: rails and rungs up the wall and into a dark shaft. */
    private fun ladder(c: Canvas, cx: Int, cy: Int) {
        val top = cy - S - 8; val bot = cy + 8
        rect(c, cx - 7, top, 14, S + 4, PX.K)                                                    // the shaft opening above
        rect(c, cx - 4, top, 1, bot - top, 0xFF9AA4B5.toInt()); rect(c, cx + 3, top, 1, bot - top, 0xFF9AA4B5.toInt())
        var y = top + 2; while (y < bot) { rect(c, cx - 4, y, 8, 1, 0xFFC8D0E0.toInt()); y += 4 }
        rect(c, cx - 5, bot - 1, 10, 2, 0xFF3A4458.toInt())
    }

    /** The manhole: an iron lid that the contact flips open. [open] 0 shut .. 1 open (the lid slid aside, the hole dark, a ladder inside). */
    private fun manhole(c: Canvas, mx: Int, my: Int, open: Float) {
        disk(c, mx, my, 8, PX.K)
        if (open > 0.02f) {
            disk(c, mx, my, 7, 0xFF03080A.toInt())
            for (i in -1..1) rect(c, mx - 4, my + i * 3, 8, 1, 0xFF3A5A5A.toInt())          // rungs going down
            glowFill(c, mx, my, 9, a(0xFF5EE6A8.toInt(), (open * 0x40).toInt()))
        }
        val lx = mx + (open * 13f).toInt(); val ly = my - (open * 3f).toInt()
        disk(c, lx, ly, 7, 0xFF2A3A44.toInt()); disk(c, lx, ly, 6, 0xFF4A5E6A.toInt())
        rect(c, lx - 6, ly, 13, 1, 0xFF2A3A44.toInt()); rect(c, lx, ly - 6, 1, 13, 0xFF2A3A44.toInt())
        px(c, lx - 3, ly - 3, 0xFF8AA0AA.toInt()); px(c, lx + 3, ly + 3, 0xFF1A2830.toInt())
    }
    private fun disk(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) { for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r + r / 2) px(c, cx + dx, cy + dy, color) }
    private fun glowFill(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) { for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r && (dx + dy + 100) % 2 == 0) px(c, cx + dx, cy + dy, color) }

    private fun groundTile(c: Canvas, col: Int, row: Int) {
        if (sewer) { sewerGround(c, col, row); return }
        val x0 = col * S; val y0 = row * S
        rect(c, x0, y0, S, S, PX.N1)
        for (y in 0 until S) for (x in 0 until S) {
            val v = hash(x0 + x, y0 + y, 1) % 100
            if (v < 8) px(c, x0 + x, y0 + y, PX.N2) else if (v < 11) px(c, x0 + x, y0 + y, PX.N0)
        }
        val off = if (row % 2 == 0) 0 else 4
        for (x in 0 until S) if (x % 4 != 3) { px(c, x0 + x, y0 + 7, PX.N0); px(c, x0 + x, y0 + 15, PX.N0) }
        for (y in 0 until 7) px(c, x0 + (3 + off) % 16, y0 + y, PX.N0)
        for (y in 8 until 15) px(c, x0 + (11 + off) % 16, y0 + y, PX.N0)
        if (hash(col, row, 5) % 9 == 0) {   // a puddle that catches the lights
            val px0 = x0 + 3 + hash(col, row, 6) % 5; val py0 = y0 + 5 + hash(col, row, 7) % 4
            for (dy in 0..3) for (dx in 0..8) if (((dx - 4) * (dx - 4)) / 16f + ((dy - 1.5f) * (dy - 1.5f)) / 4f <= 1f) px(c, px0 + dx, py0 + dy, if ((dx + dy) % 3 == 0) PX.B1 else PX.N3)
            px(c, px0 + 2, py0 + 1, PX.ICE); px(c, px0 + 6, py0 + 2, PX.CYAN)
        }
    }

    private fun brick(c: Canvas, x0: Int, y0: Int, tone: Int, seed: Int) {
        for (y in 0 until S) for (x in 0 until S) {
            val brow = y / 4
            val offs = if (brow % 2 == 0) 0 else 4
            val mortar = y % 4 == 3 || (x + offs) % 8 == 7
            val v = hash(x0 + x, y0 + y, seed) % 100
            px(c, x0 + x, y0 + y, if (mortar) PX.K else if (v < 16) tone + 0x00080808 else if (v < 26) tone - 0x00080808 else tone)
        }
    }

    private fun wallTile(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        val k = kmap[row][col]
        if (sewer) { sewerWall(c, col, row, k); return }
        val tone = brickTones[hash(col / 3, row / 8, 11) % brickTones.size]
        val n = !isWall(col, row - 1); val w = !isWall(col - 1, row); val e = !isWall(col + 1, row)
        if (k == 0 || k == 1) {
            brick(c, x0, y0, tone, 3)
            val roofAbove = row > 0 && isWall(col, row - 1) && kmap[row - 1][col] >= 2
            if (k == 0) {
                rect(c, x0, y0 + 12, S, 4, PX.N3); rect(c, x0, y0 + 12, S, 1, PX.B1)         // stone base course
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
            if (roofAbove) { rect(c, x0, y0, S, 2, 0xFF6C88C4.toInt()); rect(c, x0, y0 + 2, S, 2, a(PX.K, 0x90)) }           // cornice
            if (w) rect(c, x0, y0, 1, S, PX.B1)
            if (e) rect(c, x0 + S - 1, y0, 1, S, PX.N0)
        } else {
            // roof seen from above and in front: dark gravel, an edge where it is open to the north, vents and boxes on top
            rect(c, x0, y0, S, S, PX.N0)
            for (y in 0 until S) for (x in 0 until S) { val v = hash(x0 + x, y0 + y, 12) % 100; if (v < 12) px(c, x0 + x, y0 + y, PX.N1) else if (v < 16) px(c, x0 + x, y0 + y, PX.N2) }
            if (n) { rect(c, x0, y0, S, 2, PX.B2); rect(c, x0, y0 + 2, S, 1, PX.B1) }
            if (w) rect(c, x0, y0, 1, S, PX.B1)
            if (e) rect(c, x0 + S - 1, y0, 1, S, PX.N3)
            when (hash(col, row, 13) % 7) {
                0 -> { rect(c, x0 + 3, y0 + 5, 9, 7, PX.K); rect(c, x0 + 4, y0 + 6, 7, 5, PX.N3); rect(c, x0 + 4, y0 + 6, 7, 1, PX.B1) }   // a skylight
                1 -> { rect(c, x0 + 6, y0 + 2, 4, 11, PX.K); rect(c, x0 + 7, y0 + 3, 2, 9, PX.N3) }                                    // chimney
                2 -> { rect(c, x0 + 2, y0 + 6, 12, 6, PX.K); rect(c, x0 + 3, y0 + 7, 10, 4, PX.N2); for (i in 0..2) px(c, x0 + 5 + i * 3, y0 + 9, PX.N0) }   // an air unit
                3 -> { rect(c, x0 + 3, y0 + 3, 10, 10, PX.K); rect(c, x0 + 4, y0 + 4, 8, 8, PX.N2); rect(c, x0 + 4, y0 + 4, 8, 1, PX.B1); for (i in 0..3) px(c, x0 + 5 + i * 2, y0 + 8, PX.N0) }   // a water tank
                4 -> { rect(c, x0 + 4, y0 + 5, 8, 6, PX.K); rect(c, x0 + 5, y0 + 6, 6, 4, PX.N2); rect(c, x0 + 7, y0 + 6, 2, 4, PX.N0) }   // a roof hatch
                5 -> { rect(c, x0 + 2, y0 + 8, 12, 4, PX.N3); rect(c, x0 + 2, y0 + 8, 12, 1, PX.B1) }   // a low brick parapet
            }
        }
    }

    private fun door(c: Canvas, x: Int, y: Int) {
        rect(c, x - 1, y - 1, 10, 13, PX.K); rect(c, x, y, 8, 12, 0xFF3A2A4A.toInt()); rect(c, x, y, 8, 1, 0xFF5A4378.toInt())
        rect(c, x + 1, y + 1, 6, 4, 0xFF1A1230.toInt()); px(c, x + 6, y + 7, PX.GOLD)
        rect(c, x - 1, y + 11, 10, 2, PX.N3)                          // step
    }

    private fun shopWindow(c: Canvas, x: Int, y: Int, kind: Int) {
        rect(c, x, y, 12, 8, PX.K)
        val glass = when (kind) { 0 -> 0xFF10244A.toInt(); 1 -> 0xFF3A2A12.toInt(); else -> 0xFF0E2E3A.toInt() }
        rect(c, x + 1, y + 1, 10, 6, glass)
        px(c, x + 2, y + 2, PX.ICE); px(c, x + 3, y + 2, PX.ICE); px(c, x + 8, y + 5, a(PX.ICE, 0x90))     // reflections
        if (kind == 1) { rect(c, x + 2, y + 4, 8, 2, 0xFF9A7A2D.toInt()); px(c, x + 3, y + 3, PX.WARM) }   // goods in the window
        rect(c, x, y - 2, 12, 2, if (kind == 0) 0xFFBF2B3F.toInt() else PX.B1)                               // a small awning
    }

    private fun smallWindow(c: Canvas, x: Int, y: Int, lit: Boolean) {
        rect(c, x - 1, y - 1, 8, 9, PX.K); rect(c, x, y, 6, 7, if (lit) 0xFFCFA350.toInt() else 0xFF0C1A3C.toInt())
        rect(c, x + 3, y, 1, 7, PX.K); rect(c, x, y + 3, 6, 1, PX.K)
        if (!lit) { px(c, x + 1, y + 1, PX.B1); px(c, x + 4, y + 4, PX.N3) }
        rect(c, x - 1, y + 7, 8, 1, PX.N3)
    }

    private fun fireEscape(c: Canvas, x0: Int, y0: Int) {
        for (i in 0..3) rect(c, x0 + 2 + i * 4, y0 + 2, 1, 12, PX.K)
        rect(c, x0 + 1, y0 + 8, 14, 1, PX.N3); rect(c, x0 + 1, y0 + 13, 14, 1, PX.N3); rect(c, x0 + 1, y0 + 2, 14, 1, PX.N3)
        for (i in 0..5) px(c, x0 + 2 + i * 2, y0 + 10 + (i % 2), PX.K)
    }
    private fun pipe(c: Canvas, x: Int, y: Int) { rect(c, x, y, 3, S, PX.K); rect(c, x + 1, y, 1, S, PX.N3); rect(c, x, y + 5, 3, 1, PX.B1); rect(c, x, y + 12, 3, 1, PX.B1) }
    private fun acUnit(c: Canvas, x: Int, y: Int) { rect(c, x, y, 6, 5, PX.K); rect(c, x + 1, y + 1, 4, 3, PX.N3); px(c, x + 2, y + 2, PX.N0); px(c, x + 3, y + 2, PX.N0) }

    /** A radio window: open, lit from inside, curtain stirring, the radio on the sill. Part of the building. */
    private fun openWindow(c: Canvas, cx: Int, cy: Int, i: Int) {
        val tv = i % 4 == 2
        val x = cx - 6; val y = cy - 7
        rect(c, x - 1, y - 1, 14, 15, PX.K)
        rect(c, x, y, 12, 12, 0xFF2A1F3A.toInt())                                            // frame
        rect(c, x + 1, y + 1, 10, 9, if (tv) 0xFF2A9BA8.toInt() else 0xFFE0B060.toInt())      // the room inside
        rect(c, x + 1, y + 1, 10, 3, if (tv) 0xFF55E0F0.toInt() else PX.WARM)                  // light at the top of the room
        rect(c, x + 5, y + 1, 1, 9, a(PX.K, 0xA0))                                             // the open casement
        rect(c, x + 1, y + 1, 3, 9, a(0xFF5A2A44.toInt(), 0xE0)); rect(c, x + 2, y + 5, 1, 5, a(PX.K, 0x50))    // curtain
        rect(c, x + 9, y + 1, 2, 9, a(0xFF5A2A44.toInt(), 0xC0))
        rect(c, x - 1, y + 11, 14, 2, PX.N3); rect(c, x - 1, y + 11, 14, 1, PX.B2)              // the sill
        rect(c, x + 3, y + 7, 6, 4, PX.K); rect(c, x + 4, y + 8, 4, 2, 0xFF3A3F55.toInt())    // the radio
        px(c, x + 5, y + 8, PX.W); px(c, x + 6, y + 9, PX.RED)
        for (k in 0..3) px(c, x + 9 + k / 2, y + 6 - k, PX.ICE)                                // its aerial
    }

    /** A street-name plate on the corner of the passage that leads out of the street to the side street. */
    private fun streetSign(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 6, cy - 3, 13, 7, PX.K); rect(c, cx - 5, cy - 2, 11, 5, 0xFF1E4FB8.toInt()); rect(c, cx - 5, cy - 2, 11, 1, PX.ICE)
        rect(c, cx - 4, cy, 4, 1, PX.W); rect(c, cx + 1, cy, 4, 1, PX.W); rect(c, cx - 3, cy + 2, 7, 1, PX.W)
        rect(c, cx, cy + 4, 1, 4, PX.K)
    }

    private fun poster(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 4, cy - 5, 9, 11, PX.K); rect(c, cx - 3, cy - 4, 7, 9, PX.ICE)
        rect(c, cx - 3, cy - 4, 7, 2, PX.RED)
        rect(c, cx - 1, cy - 1, 3, 3, 0xFF3A3F55.toInt()); rect(c, cx - 2, cy + 2, 5, 1, 0xFF3A3F55.toInt())
        rect(c, cx - 2, cy + 4, 5, 1, PX.N2)
    }
    private fun news(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 5, cy - 2, 10, 6, PX.K); rect(c, cx - 4, cy - 1, 8, 4, PX.ICE)
        rect(c, cx - 4, cy - 1, 8, 1, PX.N3); rect(c, cx - 3, cy + 1, 3, 2, PX.N2); rect(c, cx + 1, cy + 1, 3, 1, PX.N2)
    }
    private fun stuff(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 6, cy - 1, 6, 3, PX.N3); rect(c, cx - 6, cy - 1, 6, 1, PX.B1)
        rect(c, cx + 1, cy - 3, 4, 4, PX.MAG); rect(c, cx + 1, cy - 3, 4, 1, PX.W)
        rect(c, cx - 2, cy + 3, 4, 2, PX.ICE); rect(c, cx + 3, cy + 2, 3, 2, PX.ICE)
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
        rect(c, x, y + 12, 14, 1, a(PX.K, 0x90))
        rect(c, x, y + 3, 14, 9, PX.K); rect(c, x + 1, y + 4, 12, 7, 0xFF145A4F.toInt())              // the front
        for (i in 0 until 5) rect(c, x + 2 + i * 2, y + 5, 1, 5, 0xFF0C3A34.toInt())
        rect(c, x - 1, y, 16, 4, PX.K); rect(c, x, y + 1, 14, 2, 0xFF1E8070.toInt()); rect(c, x, y + 1, 14, 1, PX.CYAN)     // the lid
    }
    private fun crate(c: Canvas, col: Int, row: Int) {
        val x = col * S + 1; val y = row * S + 2
        rect(c, x, y + 13, 14, 1, a(PX.K, 0x90))
        rect(c, x, y + 1, 14, 12, PX.K); rect(c, x + 1, y + 2, 12, 10, 0xFF3A2F58.toInt())
        rect(c, x + 1, y + 2, 12, 1, 0xFF6A5A90.toInt())
        for (i in 0 until 10) { px(c, x + 2 + i, y + 3 + i, 0xFF241B3A.toInt()); px(c, x + 11 - i, y + 3 + i, 0xFF241B3A.toInt()) }
    }
    private fun lampPost(c: Canvas, x: Int, y: Int) {
        rect(c, x - 1, y - 24, 2, 25, PX.K); rect(c, x, y - 24, 1, 25, PX.N3)
        rect(c, x - 3, y - 28, 7, 4, PX.K); rect(c, x - 2, y - 27, 5, 2, PX.W); rect(c, x - 1, y - 26, 3, 1, PX.LAMP)
        rect(c, x - 2, y, 5, 2, PX.K)
    }
    private fun car(c: Canvas, x: Int, y: Int, w: Int) {
        val ww = w * S - 2
        rect(c, x + 1, y + 6, ww, 2, a(PX.K, 0xA0))
        rect(c, x + 3, y - 1, 4, 3, PX.K); rect(c, x + ww - 5, y - 1, 4, 3, PX.K)                       // far wheels
        rect(c, x + 1, y - 6, ww, 12, PX.K); rect(c, x + 2, y - 5, ww - 2, 10, 0xFF16224F.toInt())         // body
        rect(c, x + 2, y - 1, ww - 2, 5, 0xFFEEF0F6.toInt())                                                // white doors
        rect(c, x + 3, y - 5, ww - 4, 4, 0xFF28407A.toInt()); rect(c, x + 4, y - 4, ww - 6, 2, 0xFF08122E.toInt())   // cab and windows
        rect(c, x + 2, y + 1, ww - 2, 1, 0xFF16224F.toInt()); rect(c, x + 5, y + 2, 5, 1, PX.GOLD)
        rect(c, x + 3, y + 5, 4, 3, PX.K); rect(c, x + ww - 5, y + 5, 4, 3, PX.K)                          // near wheels
        val flash = (clockNow * 3f).toInt() % 2 == 0
        rect(c, x + ww / 2 - 4, y - 7, 4, 2, if (flash) PX.RED else 0xFF7A1A28.toInt())
        rect(c, x + ww / 2, y - 7, 4, 2, if (flash) 0xFF1A2E7A.toInt() else PX.BLUE)
    }
    private fun stand(c: Canvas, x: Int, y: Int, w: Int) {
        val ww = w * S - 2
        rect(c, x + 1, y + 6, ww, 2, a(PX.K, 0xA0))
        rect(c, x + 1, y - 4, ww, 10, PX.K); rect(c, x + 2, y - 3, ww - 2, 8, 0xFF4A3A22.toInt())         // the counter
        rect(c, x + 2, y - 3, ww - 2, 1, 0xFF8A6A3A.toInt())
        for (i in 0 until ww / 6) { rect(c, x + 3 + i * 6, y - 8, 5, 5, PX.ICE); rect(c, x + 3 + i * 6, y - 8, 5, 1, PX.N3); px(c, x + 4 + i * 6, y - 6, PX.K); px(c, x + 6 + i * 6, y - 5, PX.K) }   // newspapers
        for (i in 0 until ww / 4) rect(c, x + 1 + i * 4, y - 20, 4, 5, if (i % 2 == 0) PX.RED else PX.W)     // the striped awning
        rect(c, x + 1, y - 15, ww, 1, PX.K)
        rect(c, x + 2, y - 15, 1, 8, PX.K); rect(c, x + ww - 1, y - 15, 1, 8, PX.K)                          // posts
    }

    // ---------- every frame ----------
    private var clockNow = 0f

    fun render(clock: Float, read: Set<Int>, bc: Float): Bitmap {
        clockNow = clock; animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, plain)
        spec.lead?.let { manhole(c, (it.manholeX * S).roundToInt(), (it.manholeY * S).roundToInt(), world.lidOpen) }

        // everything standing in the scene, sorted so people pass in front of and behind things
        val list = ArrayList<Item>(items)
        val pxx = (world.px * S).roundToInt(); val pyy = (world.py * S).roundToInt() + 6
        val walkFrame = if (world.speed > 0.05f) ((clock * (3f + world.speed * 2.2f)).toInt() % 2) else if (world.autoWalk) ((clock * 7f).toInt() % 2) else 0
        val climbing = world.state == StealthWorld.State.WON && spec.worker != null
        val rise = if (climbing) min(30, (world.stateT * 18f).toInt()) else 0
        list += Item(pyy.toFloat() + (if (climbing) 40f else 0f)) { cv -> folk(cv, pxx, pyy - rise, if (climbing) 0 else dirOf(world.face), if (climbing) ((world.stateT * 6f).toInt() % 2) else walkFrame, clerk, false, !world.letterGiven) }
        val vx = (world.contactX * S).roundToInt(); val vy = (world.contactY * S).roundToInt() + 6
        val vFrame = if (world.contactWalking) ((clock * 5f).toInt() % 2) else 0
        val outfit = if (spec.worker != null) worker else vendor
        val wp = if (spec.worker != null) world.workerPose else 0
        list += Item(vy.toFloat()) { cv ->
            folk(cv, vx, vy, dirOf(world.contactFace), vFrame, outfit, false, false, 0, world.crossFrame, wp, world.workerPointDir)
            if (world.letterGiven && spec.worker != null) { rect(cv, vx - 9, vy - 9, 6, 5, PX.K); rect(cv, vx - 8, vy - 8, 4, 3, PX.W); px(cv, vx - 6, vy - 7, PX.RED) }   // he holds your letter
        }
        world.cop?.let { cop ->
            if (cop.visible) {
                val fx = (cop.x * S).roundToInt(); val fy = (cop.y * S).roundToInt() + 6
                list += Item(fy.toFloat()) { cv -> folk(cv, fx, fy, dirOf(cop.face), if (cop.walking) ((clock * 2.2f).toInt() % 2) else 0, police, cop.torch, false, sorrow = cop.headDown) }
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
            p.style = Paint.Style.FILL; p.color = a(PX.K, (world.fog * 255).toInt())
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
        p.style = Paint.Style.FILL; p.color = a(PX.LAMP, alpha); c.drawPath(path, p)
    }

    init { computeK(); buildStatic(); buildItems() }   // last, so every property above exists first
}
