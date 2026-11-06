package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sewer maze drawn in the same pixel-art style as the rest of the game: corridors of wet stone bounded by fat pipes, a police
 * chamber in the middle with a manhole in its ceiling and a spotlight from above, key fragments instead of dots, the four police in the
 * four ghost colours, and a steel exit door at the top that opens when every fragment is in your pocket.
 */
internal class PacScene(private val world: PacWorld) : PixelSprites() {
    private val S = 16
    val W = world.w * S
    val H = world.h * S
    private val base = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    private val path = Path()

    private val ghostColors = intArrayOf(0xFFFF3B3B.toInt(), 0xFFFF8CE0.toInt(), 0xFF3DE8F5.toInt(), 0xFFFFA23A.toInt())
    private val outfits by lazy { Array(4) { policeIn(ghostColors[it]) } }
    private val auras by lazy { Array(4) { glowBitmap(15, ghostColors[it], 0x66) } }
    // arcade: panicking police (blue, flashing white as it wears off) and the flares that cause it
    private val scared by lazy { policeIn(0xFF2A4AFF.toInt()) }
    private val scaredEnd by lazy { policeIn(0xFFE8ECFF.toInt()) }
    private val scaredAura by lazy { glowBitmap(15, 0xFF4A6AFF.toInt(), 0x66) }
    private val flareGlow by lazy { glowBitmap(14, 0xFFFF4A3A.toInt(), 0x80) }
    private val spotGlow by lazy { glowBitmap(34, PX.LAMP, 0x70) }

    private fun isWall(x: Int, y: Int) = world.tile(x, y).let { it == '#' || it == ' ' }
    private fun isOpen(x: Int, y: Int) = world.tile(x, y).let { it == '.' || it == 'M' || it == '-' || it == 'D' }

    // ---------- the parts that never move ----------
    private fun buildStatic() {
        val c = Canvas(base)
        rect(c, 0, 0, W, H, PX.K)
        for (y in 0 until world.h) for (x in 0 until world.w) {
            when (world.tile(x, y)) {
                '.', 'D' -> floor(c, x, y, false)
                'M' -> floor(c, x, y, true)
                '-' -> { floor(c, x, y, true); gate(c, x, y) }
                else -> wall(c, x, y)
            }
        }
        // pipes along every wall face that borders a corridor
        for (y in 0 until world.h) for (x in 0 until world.w) if (isWall(x, y)) {
            val x0 = x * S; val y0 = y * S
            if (isOpen(x, y - 1)) tubeH(c, x0, y0, true, x)
            if (isOpen(x, y + 1)) tubeH(c, x0, y0 + 11, false, x)
            if (isOpen(x - 1, y)) tubeV(c, x0, y0, true, y)
            if (isOpen(x + 1, y)) tubeV(c, x0 + 11, y0, false, y)
        }
        // couplings where a pipe turns a corner
        for (y in 0 until world.h) for (x in 0 until world.w) if (isWall(x, y)) {
            val x0 = x * S; val y0 = y * S
            val n = isOpen(x, y - 1); val s = isOpen(x, y + 1); val w = isOpen(x - 1, y); val e = isOpen(x + 1, y)
            if ((n || s) && (w || e)) {
                val cx = if (w) x0 else x0 + 11; val cy = if (n) y0 else y0 + 11
                rect(c, cx - 1, cy - 1, 7, 7, PX.K); rect(c, cx, cy, 5, 5, 0xFF3F8A8C.toInt()); rect(c, cx, cy, 5, 1, 0xFF9AF0EA.toInt()); px(c, cx + 2, cy + 2, PX.ICE)
            }
        }
    }

    private fun floor(c: Canvas, x: Int, y: Int, chamber: Boolean) {
        val x0 = x * S; val y0 = y * S
        rect(c, x0, y0, S, S, if (chamber) 0xFF0B1415.toInt() else 0xFF0A1B20.toInt())
        for (yy in 0 until S) for (xx in 0 until S) {
            val v = hash(x0 + xx, y0 + yy, 41) % 100
            if (v < 9) px(c, x0 + xx, y0 + yy, if (chamber) 0xFF132426.toInt() else 0xFF123038.toInt()) else if (v < 12) px(c, x0 + xx, y0 + yy, 0xFF050E10.toInt())
        }
        if (chamber) { for (i in 0 until S step 4) { rect(c, x0 + i, y0, 1, S, 0xFF050E10.toInt()); rect(c, x0, y0 + i, S, 1, 0xFF050E10.toInt()) }; return }
        // wet stone: a faint channel down the middle and the odd puddle
        rect(c, x0, y0 + 7, S, 2, a(0xFF1E5A6A.toInt(), 0x50)); rect(c, x0 + 7, y0, 2, S, a(0xFF1E5A6A.toInt(), 0x30))
        if (hash(x, y, 42) % 11 == 0) { rect(c, x0 + 3, y0 + 5, 9, 5, 0xFF1A4A58.toInt()); rect(c, x0 + 5, y0 + 6, 3, 1, 0xFF6FE8E0.toInt()); px(c, x0 + 9, y0 + 8, 0xFF9AF0EA.toInt()) }
    }

    private fun wall(c: Canvas, x: Int, y: Int) {
        val x0 = x * S; val y0 = y * S
        if (world.tile(x, y) == ' ') return
        rect(c, x0, y0, S, S, 0xFF060F11.toInt())
        for (yy in 0 until S) for (xx in 0 until S) {
            val brow = yy / 4; val offs = if (brow % 2 == 0) 0 else 4
            if (yy % 4 == 3 || (xx + offs) % 8 == 7) px(c, x0 + xx, y0 + yy, PX.K)
            else if (hash(x0 + xx, y0 + yy, 43) % 100 < 14) px(c, x0 + xx, y0 + yy, 0xFF0D1E21.toInt())
        }
    }

    /** A fat horizontal pipe along the top or bottom edge of a wall tile. */
    private fun tubeH(c: Canvas, x0: Int, y0: Int, top: Boolean, seed: Int) {
        rect(c, x0, y0, S, 5, PX.K)
        rect(c, x0, y0 + 1, S, 3, 0xFF2F7378.toInt())
        rect(c, x0, y0 + (if (top) 1 else 2), S, 1, 0xFF8AF0E8.toInt())
        rect(c, x0, y0 + (if (top) 3 else 1), S, 1, 0xFF1A4A4E.toInt())
        px(c, x0 + 3, y0 + 2, PX.ICE); px(c, x0 + 12, y0 + 2, PX.ICE)
        if (seed % 5 == 0) { rect(c, x0 + 7, y0 - 1, 3, 7, PX.K); rect(c, x0 + 8, y0, 1, 5, 0xFF9AA4B5.toInt()) }
    }
    private fun tubeV(c: Canvas, x0: Int, y0: Int, left: Boolean, seed: Int) {
        rect(c, x0, y0, 5, S, PX.K)
        rect(c, x0 + 1, y0, 3, S, 0xFF2F7378.toInt())
        rect(c, x0 + (if (left) 1 else 2), y0, 1, S, 0xFF8AF0E8.toInt())
        rect(c, x0 + (if (left) 3 else 1), y0, 1, S, 0xFF1A4A4E.toInt())
        px(c, x0 + 2, y0 + 3, PX.ICE); px(c, x0 + 2, y0 + 12, PX.ICE)
        if (seed % 5 == 0) { rect(c, x0 - 1, y0 + 7, 7, 3, PX.K); rect(c, x0, y0 + 8, 5, 1, 0xFF9AA4B5.toInt()) }
    }

    /** The chamber door: a pink steel gate (like the arcade's). */
    private fun gate(c: Canvas, x: Int, y: Int) {
        val x0 = x * S; val y0 = y * S
        rect(c, x0, y0 + 5, S, 6, PX.K)
        rect(c, x0, y0 + 6, S, 2, 0xFFFF8CE0.toInt()); rect(c, x0, y0 + 9, S, 1, 0xFF8A3A7A.toInt())
        for (i in 0 until S step 4) rect(c, x0 + i, y0 + 5, 1, 6, 0xFFC860B0.toInt())
    }

    // ---------- every frame ----------
    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, plain)

        // the key fragments
        for (y in 0 until world.h) for (x in 0 until world.w) if (world.frag[y][x]) fragment(c, x * S + 8, y * S + 8, x + y * 3, clock)

        // flares (arcade): a lit red road flare stuck in the muck, spitting sparks
        for (y in 0 until world.h) for (x in 0 until world.w) if (world.power[y][x]) flare(c, x * S + 8, y * S + 8, x * 5 + y, clock)

        // the manhole in the chamber ceiling, with its spotlight
        val hx = (world.hatchX * S).roundToInt(); val hy = (world.hatchY * S).roundToInt()
        hatch(c, hx, hy, world.lidClose)
        if (world.spot > 0.02f) spotlight(c, hx, hy, world.spot)

        // the exit door at the top
        door(c, world.doorX * S, world.doorY * S, world.doorOpen, clock)

        // everyone, back to front
        class Who(val key: Int, val draw: () -> Unit)
        val list = ArrayList<Who>()
        for (g in world.ghosts) {
            if (!g.visible) continue
            val fx = (g.x * S).roundToInt(); val fy = (g.y * S).roundToInt() + 7
            val dirIdx = when (g.dir) { PacWorld.UP -> 0; PacWorld.RIGHT -> 1; PacWorld.DOWN -> 2; else -> 3 }
            val facing = if (g.state == PacWorld.IN_CHAMBER) 2 else dirIdx
            val frame = if (g.state == PacWorld.IN_CHAMBER) 0 else ((clock * 6f).toInt() % 2)
            if (g.state == PacWorld.RETURNING) {   // caught: only his cap and eyes scurry back to the chamber
                list += Who(fy) { rect(c, fx - 4, fy - 15, 9, 3, 0xFF1A2A5A.toInt()); rect(c, fx - 3, fy - 12, 3, 3, PX.W); rect(c, fx + 1, fy - 12, 3, 3, PX.W); px(c, fx - 2 + (if (g.dir == PacWorld.LEFT) -1 else if (g.dir == PacWorld.RIGHT) 1 else 0), fy - 11, PX.K); px(c, fx + 2 + (if (g.dir == PacWorld.LEFT) -1 else if (g.dir == PacWorld.RIGHT) 1 else 0), fy - 11, PX.K) }
                continue
            }
            val panic = g.fright
            val ending = panic && world.frightT < 2f && (clock * 6f).toInt() % 2 == 0
            list += Who(fy) {
                c.drawBitmap(if (panic) scaredAura else auras[g.id], fx - 15f, fy - 15f, plain)
                folk(c, fx, fy, facing, frame, if (panic) (if (ending) scaredEnd else scared) else outfits[g.id], g.id == PacWorld.BLINKY && !panic, false)
                if (panic) { px(c, fx - 6, fy - 22 + ((clock * 8f).toInt() % 2), PX.W); px(c, fx + 6, fy - 21, PX.W) }   // sweat
            }
        }
        val pxx = (world.px * S).roundToInt(); val pyy = (world.py * S).roundToInt() + 7
        val pdir = when (world.dir) { PacWorld.UP -> 0; PacWorld.RIGHT -> 1; PacWorld.DOWN -> 2; else -> 3 }
        list += Who(pyy) { folk(c, pxx, pyy, pdir, if (world.moving) ((clock * 8f).toInt() % 2) else 0, clerk, false, true) }
        list.sortBy { it.key }
        for (w in list) w.draw()

        // a cop climbing down the ladder into the chamber (drawn last, clipped so he goes into the hole)
        if (world.descending >= 0) {
            val p = world.descendP
            val fy = (hy - 26 + (p * 30f)).roundToInt()
            c.save(); c.clipRect(0f, 0f, W.toFloat(), (hy + 3).toFloat())
            folk(c, hx, fy, 2, ((clock * 6f).toInt() % 2), outfits[world.descending], world.descending == PacWorld.BLINKY, false)
            c.restore()
        }
        return frame
    }

    private fun flare(c: Canvas, cx: Int, cy: Int, seed: Int, clock: Float) {
        val pulse = ((clock * 5f + seed).toInt() % 2 == 0)
        c.drawBitmap(flareGlow, cx - 14f, cy - 14f, plain)
        rect(c, cx - 1, cy - 3, 3, 8, PX.K); rect(c, cx, cy - 2, 1, 6, 0xFFC02A2A.toInt()); rect(c, cx, cy + 1, 1, 1, PX.W)
        rect(c, cx - 1, cy - 6, 3, 3, if (pulse) 0xFFFFF0A0.toInt() else 0xFFFF8A3A.toInt())
        for (k in 0..2) { val ph = (clock * 3f + k * 0.33f + seed * 0.1f) % 1f; px(c, cx + ((k - 1) * 3 * ph).toInt(), cy - 6 - (ph * 6).toInt(), a(0xFFFFC04A.toInt(), ((1f - ph) * 255).toInt())) }
    }

    private fun fragment(c: Canvas, cx: Int, cy: Int, seed: Int, clock: Float) {
        val tw = ((clock * 3f + seed) % 3f).toInt()
        val gold = 0xFFFFD84D.toInt()
        rect(c, cx - 1, cy - 2, 3, 1, gold); rect(c, cx - 2, cy - 1, 5, 2, gold); rect(c, cx - 1, cy + 1, 3, 1, 0xFFB88A12.toInt())
        px(c, cx - 2, cy - 1, if (tw == 0) PX.W else 0xFFFFE890.toInt()); px(c, cx + 1, cy, 0xFFB88A12.toInt())
    }

    /** The manhole in the ceiling of the chamber: open (a dark hole with a ladder) until the lid slides across and closes. */
    private fun hatch(c: Canvas, hx: Int, hy: Int, lidClose: Float) {
        for (dy in -9..9) for (dx in -9..9) if (dx * dx + dy * dy <= 81) px(c, hx + dx, hy + dy, PX.K)
        for (dy in -7..7) for (dx in -7..7) if (dx * dx + dy * dy <= 49) px(c, hx + dx, hy + dy, 0xFF02080A.toInt())
        for (i in -2..2) rect(c, hx - 4, hy + i * 3, 9, 1, 0xFF4A6A6A.toInt())
        val lx = hx + ((1f - lidClose) * 22f).roundToInt(); val ly = hy - ((1f - lidClose) * 5f).roundToInt()
        for (dy in -8..8) for (dx in -8..8) if (dx * dx + dy * dy <= 64) px(c, lx + dx, ly + dy, if (dx * dx + dy * dy <= 49) 0xFF4A5E6A.toInt() else 0xFF2A3A44.toInt())
        rect(c, lx - 7, ly, 15, 1, 0xFF2A3A44.toInt()); rect(c, lx, ly - 7, 1, 15, 0xFF2A3A44.toInt())
        px(c, lx - 4, ly - 4, 0xFF9AB0BA.toInt()); px(c, lx + 4, ly + 4, 0xFF1A2830.toInt()); px(c, lx - 4, ly + 4, 0xFF9AB0BA.toInt()); px(c, lx + 4, ly - 4, 0xFF1A2830.toInt())
    }

    /** The spotlight from above: a pool of white light on the hatch and a shaft of it rising. */
    private fun spotlight(c: Canvas, hx: Int, hy: Int, k: Float) {
        c.drawBitmap(spotGlow, hx - 34f, hy - 34f, plain)
        path.reset(); path.moveTo(hx - 4f, hy.toFloat()); path.lineTo(hx + 4f, hy.toFloat()); path.lineTo(hx + 34f, (hy - 70).toFloat()); path.lineTo(hx - 34f, (hy - 70).toFloat()); path.close()
        p.style = Paint.Style.FILL; p.color = a(PX.LAMP, (0x28 * k).toInt()); c.drawPath(path, p)
    }

    private fun door(c: Canvas, x0: Int, y0: Int, open: Boolean, clock: Float) {
        rect(c, x0, y0, S, S, PX.K)
        rect(c, x0 + 1, y0 + 1, 14, 14, 0xFF3A4458.toInt())
        rect(c, x0 + 1, y0 + 1, 14, 2, 0xFF6A7890.toInt())
        if (!open) {
            rect(c, x0 + 3, y0 + 4, 10, 9, 0xFF2A3344.toInt()); rect(c, x0 + 3, y0 + 8, 10, 1, PX.K)
            px(c, x0 + 12, y0 + 7, 0xFFFF3B55.toInt()); rect(c, x0 + 7, y0 + 2, 2, 2, if ((clock * 2f).toInt() % 2 == 0) 0xFFFF3B55.toInt() else 0xFF7A1A28.toInt())
        } else {
            rect(c, x0 + 3, y0 + 3, 10, 12, 0xFF04120C.toInt()); rect(c, x0 + 2, y0 + 3, 2, 12, 0xFF6A7890.toInt())      // the door swung open on to light
            rect(c, x0 + 7, y0 + 2, 2, 2, 0xFF3BFF9A.toInt())
            val k = (sin(clock * 5f) * 0.5f + 0.5f)
            rect(c, x0 + 5, y0 + 6, 6, 4, a(0xFF3BFF9A.toInt(), (0x40 + 0x80 * k).toInt()))
        }
    }

    init { buildStatic() }   // last, so every property above exists first
}
