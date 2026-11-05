package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-art look for a level (level 1 so far): the whole scene is drawn at 16 pixels per tile into a small bitmap, which the
 * game then scales up with hard edges. A night palette of blacks and dark blues, white lamplight and neon from the windows.
 * The floor, walls, bins and fixtures are drawn once; people, light cones and effects are redrawn every frame.
 */
internal class PixelScene(private val spec: LevelSpec, private val world: StealthWorld) {
    private val S = 16
    private val W = world.cols * S
    private val H = world.rows * S
    private val base = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    private val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; isFilterBitmap = false }
    private val path = Path()

    private object C {
        const val K = 0xFF03050A.toInt()     // black
        const val N0 = 0xFF070B18.toInt()
        const val N1 = 0xFF0B1230.toInt()    // street
        const val N2 = 0xFF121D47.toInt()
        const val N3 = 0xFF1B2B66.toInt()
        const val B1 = 0xFF2B4FA8.toInt()
        const val B2 = 0xFF4C7DFF.toInt()
        const val ICE = 0xFFA9CCFF.toInt()
        const val W = 0xFFFFFFFF.toInt()
        const val CYAN = 0xFF29F0FF.toInt()
        const val MAG = 0xFFFF38D6.toInt()
        const val WARM = 0xFFFFE08A.toInt()
        const val RED = 0xFFFF3B55.toInt()
        const val LAMP = 0xFFDCEBFF.toInt()
    }


    // ---------- little helpers ----------
    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) }
    private fun px(c: Canvas, x: Int, y: Int, color: Int) = rect(c, x, y, 1, 1, color)
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun hash(x: Int, y: Int, s: Int): Int {
        var v = x * 374761393 + y * 668265263 + s * 1274126177
        v = (v xor (v ushr 13)) * 1274126177
        return (v xor (v ushr 16)) and 0x7fffffff
    }
    private fun disk(c: Canvas, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r + r / 2) px(c, cx + dx, cy + dy, color)
    }
    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = color
        c.drawCircle(cx.roundToInt().toFloat(), cy.roundToInt().toFloat(), r.roundToInt().toFloat(), p)
        p.style = Paint.Style.FILL
    }

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** A soft pool of light made of dither dots (no smooth gradients in pixel art). */
    private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, maxA: Int) {
        val x0 = max(0, (cx - r).toInt()); val x1 = min(W - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt()); val y1 = min(H - 1, (cy + r).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val d = hypot(x + 0.5f - cx, y + 0.5f - cy) / r
            if (d >= 1f) continue
            val t = (1f - d) * 3.4f
            val q = floor(t + bayer[(y and 3) * 4 + (x and 3)] / 16f).toInt().coerceIn(0, 3)
            if (q > 0) px(c, x, y, a(color, q * maxA / 3))
        }
    }

    // ---------- the parts that never move ----------
    private fun isWall(col: Int, row: Int) = world.tile(col, row).let { it == '#' || it == ' ' }

    private fun buildStatic() {
        val c = Canvas(base)
        rect(c, 0, 0, W, H, C.K)
        for (row in 0 until world.rows) for (col in 0 until world.cols) if (isWall(col, row)) wallTile(c, col, row) else floorTile(c, col, row)
        for (row in 0 until world.rows) for (col in 0 until world.cols) when (world.tile(col, row)) {
            'c' -> bin(c, col, row)
            'd' -> crate(c, col, row)
        }
        // lamp light and window glow on the street
        for ((lx, ly) in world.lamps) {
            glow(c, lx * S, ly * S, StealthWorld.LAMP_R * S + 6, C.LAMP, 0x4A)
            val cx = (lx * S).toInt(); val cy = (ly * S).toInt()
            disk(c, cx, cy, 2, a(C.LAMP, 0xE0)); px(c, cx, cy, C.W)
            ring(c, cx.toFloat(), cy.toFloat(), 4f, a(C.LAMP, 0x70))
        }
        for ((i, pr) in spec.props.withIndex()) if (pr.kind == "window") {
            val tint = windowTint(i)
            glow(c, pr.x * S, pr.y * S, 22f, tint, 0x42)
        }
        for ((i, pr) in spec.props.withIndex()) when (pr.kind) {
            "window" -> window(c, (pr.x * S).toInt(), (pr.y * S).toInt(), i)
            "poster" -> poster(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "news" -> news(c, (pr.x * S).toInt(), (pr.y * S).toInt())
            "stuff" -> stuff(c, (pr.x * S).toInt(), (pr.y * S).toInt())
        }
    }

    private fun floorTile(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        rect(c, x0, y0, S, S, C.N1)
        for (y in 0 until S) for (x in 0 until S) {
            val v = hash(x0 + x, y0 + y, 1) % 100
            if (v < 8) px(c, x0 + x, y0 + y, C.N2) else if (v < 11) px(c, x0 + x, y0 + y, C.N0)
        }
        // cobble seams
        val off = if (row % 2 == 0) 0 else 4
        for (x in 0 until S) if (x % 4 != 3) { px(c, x0 + x, y0 + 7, C.N0); px(c, x0 + x, y0 + 15, C.N0) }
        for (y in 0 until 7) px(c, x0 + (3 + off) % 16, y0 + y, C.N0)
        for (y in 8 until 15) px(c, x0 + (11 + off) % 16, y0 + y, C.N0)
        // a puddle here and there, reflecting the lights
        if (hash(col, row, 5) % 8 == 0 && world.lamps.none { hypot(it.first - (col + 0.5f), it.second - (row + 0.5f)) < 1.4f }) {
            val px0 = x0 + 3 + hash(col, row, 6) % 5; val py0 = y0 + 4 + hash(col, row, 7) % 5
            for (dy in 0..3) for (dx in 0..8) {
                val inside = ((dx - 4) * (dx - 4)) / 16f + ((dy - 1.5f) * (dy - 1.5f)) / 4f <= 1f
                if (inside) px(c, px0 + dx, py0 + dy, if ((dx + dy) % 3 == 0) C.B1 else C.N3)
            }
            px(c, px0 + 2, py0 + 1, C.ICE); px(c, px0 + 6, py0 + 2, C.CYAN)
        }
    }

    private fun wallTile(c: Canvas, col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        for (y in 0 until S) for (x in 0 until S) {
            val brow = y / 4
            val offs = if (brow % 2 == 0) 0 else 4
            val mortar = y % 4 == 3 || (x + offs) % 8 == 7
            val v = hash(x0 + x, y0 + y, 3) % 100
            px(c, x0 + x, y0 + y, if (mortar) C.K else if (v < 18) C.N3 else if (v < 28) C.N1 else C.N2)
        }
        // light from the street catches the edges that face it
        val n = !isWall(col, row - 1); val s = !isWall(col, row + 1); val w = !isWall(col - 1, row); val e = !isWall(col + 1, row)
        if (n) for (x in 0 until S) { px(c, x0 + x, y0, C.B2); px(c, x0 + x, y0 + 1, C.B1) }
        if (s) for (x in 0 until S) { px(c, x0 + x, y0 + 15, C.K); px(c, x0 + x, y0 + 14, C.N0) }
        if (w) for (y in 0 until S) { px(c, x0, y0 + y, C.B1) }
        if (e) for (y in 0 until S) { px(c, x0 + 15, y0 + y, C.B1) }
        // a drainpipe on some walls
        if (hash(col, row, 9) % 6 == 0 && (n || s)) { rect(c, x0 + 7, y0, 2, S, C.N3); rect(c, x0 + 7, y0, 1, S, C.B1) }
    }

    private val binCol = intArrayOf(0xFF08202B.toInt(), 0xFF0F3A47.toInt(), 0xFF1D6477.toInt())
    private fun bin(c: Canvas, col: Int, row: Int) {
        val x = col * S + 1; val y = row * S + 1
        rect(c, x + 1, y + 13, 14, 2, a(C.K, 0xA0))                       // shadow
        rect(c, x, y, 14, 13, C.K)
        rect(c, x + 1, y + 1, 12, 4, binCol[2]); rect(c, x + 1, y + 1, 12, 1, C.CYAN)     // lid, with a neon rim
        rect(c, x + 1, y + 5, 12, 1, C.K)
        rect(c, x + 1, y + 6, 12, 6, binCol[1])
        for (i in 0 until 4) rect(c, x + 2 + i * 3, y + 7, 1, 4, binCol[0])
        px(c, x + 3, y, C.N3); px(c, x + 4, y, C.N3); px(c, x + 8, y, C.N2)       // bin bags poking out
    }

    private fun crate(c: Canvas, col: Int, row: Int) {
        val x = col * S + 1; val y = row * S + 1
        rect(c, x + 1, y + 13, 14, 2, a(C.K, 0xA0))
        rect(c, x, y, 14, 13, C.K); rect(c, x + 1, y + 1, 12, 11, C.N3)
        rect(c, x + 1, y + 1, 12, 1, C.B1)
        for (i in 0 until 12) { px(c, x + 1 + i, y + 1 + i * 11 / 12, C.N1); px(c, x + 12 - i, y + 1 + i * 11 / 12, C.N1) }
        rect(c, x + 1, y + 6, 12, 1, C.N1)
    }

    private fun windowTint(i: Int) = when (i % 3) { 0 -> C.WARM; 1 -> C.CYAN; else -> C.MAG }

    private fun window(c: Canvas, cx: Int, cy: Int, i: Int) {
        val t = windowTint(i)
        rect(c, cx - 6, cy - 7, 12, 14, C.K)
        rect(c, cx - 5, cy - 6, 10, 12, t)
        rect(c, cx - 5, cy - 6, 3, 12, a(C.K, 0x80)); rect(c, cx + 2, cy - 6, 3, 12, a(C.K, 0x80))   // curtains
        rect(c, cx, cy - 6, 1, 12, C.K); rect(c, cx - 5, cy, 10, 1, C.K)
        rect(c, cx - 4, cy + 3, 8, 3, C.K)                                                           // the radio on the sill
        px(c, cx - 3, cy + 4, C.W); px(c, cx - 1, cy + 4, C.W); px(c, cx + 2, cy + 4, C.RED)
        px(c, cx + 3, cy + 3, C.N3)
    }

    private fun poster(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 4, cy - 6, 9, 12, C.K); rect(c, cx - 3, cy - 5, 7, 10, C.ICE)
        rect(c, cx - 3, cy - 5, 7, 2, C.RED)
        disk(c, cx, cy - 1, 1, C.N3); rect(c, cx - 2, cy + 1, 5, 2, C.N3)
        rect(c, cx - 2, cy + 4, 5, 1, C.N2)
    }

    private fun news(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 5, cy - 3, 10, 7, C.K); rect(c, cx - 4, cy - 2, 8, 5, C.ICE)
        rect(c, cx - 4, cy - 2, 8, 1, C.N3); rect(c, cx - 3, cy, 3, 2, C.N2); rect(c, cx + 1, cy, 3, 1, C.N2); rect(c, cx + 1, cy + 2, 3, 1, C.N2)
    }

    private fun stuff(c: Canvas, cx: Int, cy: Int) {
        rect(c, cx - 6, cy - 2, 6, 4, C.N3); rect(c, cx - 6, cy - 2, 6, 1, C.B1)             // a coat
        rect(c, cx + 1, cy - 4, 4, 4, C.MAG); rect(c, cx + 1, cy - 4, 4, 1, C.W)               // a book
        rect(c, cx - 2, cy + 3, 4, 2, C.ICE); rect(c, cx + 3, cy + 2, 3, 2, C.ICE)            // papers
    }

    // ---------- people ----------
    private fun person(c: Canvas, x: Float, y: Float, ang: Float, coat: Int, trim: Int, hair: Int, isGuard: Boolean, letter: Boolean) {
        val cx = (x * S).roundToInt(); val cy = (y * S).roundToInt()
        rect(c, cx - 5, cy - 3, 11, 8, a(C.K, 0x60))                         // shadow
        val quarter = ((ang / (PI / 2)).roundToInt() % 4 + 4) % 4
        c.save(); c.rotate(quarter * 90f, cx.toFloat(), cy.toFloat())
        // shoulders (a long shape across the direction he faces)
        rect(c, cx - 3, cy - 6, 7, 13, C.K)
        rect(c, cx - 2, cy - 5, 5, 11, coat)
        rect(c, cx - 2, cy - 5, 1, 11, trim)
        rect(c, cx + 2, cy - 5, 1, 11, a(C.K, 0x55))
        rect(c, cx - 2, cy - 5, 5, 1, a(C.W, 0x70))
        // head, seen from above
        disk(c, cx + 1, cy, 3, C.K); disk(c, cx + 1, cy, 2, hair)
        px(c, cx + 3, cy - 1, 0xFFE9C7A0.toInt()); px(c, cx + 3, cy, 0xFFE9C7A0.toInt())
        if (isGuard) { px(c, cx + 2, cy, C.W); rect(c, cx + 3, cy + 3, 3, 1, C.W); px(c, cx + 6, cy + 3, C.LAMP) }   // cap badge and torch
        if (letter) { rect(c, cx + 4, cy - 2, 5, 4, C.K); rect(c, cx + 5, cy - 1, 3, 2, C.W); px(c, cx + 6, cy, C.RED) }
        c.restore()
    }

    // ---------- every frame ----------
    fun render(clock: Float, read: Set<Int>, bc: Float): Bitmap {
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.drawBitmap(base, 0f, 0f, null)

        // radio sound rolling out of the windows, and clues you have not read pulsing
        for ((i, pr) in spec.props.withIndex()) {
            val cx = pr.x * S; val cy = pr.y * S
            if (pr.kind == "window") for (k in 0..2) {
                val ph = (clock * 0.7f + k / 3f + i * 0.21f) % 1f
                ring(c, cx, cy, 8f + ph * 20f, a(windowTint(i), (((1f - ph) * 3).toInt() * 0x28 + 0x10)))
            }
            if (pr.text != null && i !in read && pr.kind != "window") ring(c, cx, cy, 6f + ((clock * 0.9f + i * 0.37f) % 1f) * 8f, a(C.CYAN, 0x60))
        }
        // where he walked
        for (f in world.feet) px(c, (f.x * S).toInt(), (f.y * S).toInt(), a(C.W, ((1f - f.age / 2.2f) * 0x50).toInt()))

        // guards' torch beams
        for (g in world.guards) if (!g.dead) cone(c, g.eye(), g.meter, g.chasing, 1f - bc)

        // your contact
        val vx = world.contactX; val vy = world.contactY
        val pulse = (clock * 1.2f) % 1f
        ring(c, vx * S, vy * S, 8f + pulse * 10f, a(C.CYAN, ((1f - pulse) * 0xB0).toInt()))
        glow(c, vx * S, vy * S, 18f, C.CYAN, 0x30)
        val vface = if (world.contactAiming) world.contactAim else if (world.state == StealthWorld.State.WON) Math.atan2((world.py - vy).toDouble(), (world.px - vx).toDouble()).toFloat() else (sin(clock * 0.9f) * 0.5f + 1.2f)
        person(c, vx, vy, vface, 0xFF21B58C.toInt(), C.CYAN, 0xFF0A1A14.toInt(), false, false)
        if (world.contactAiming) {
            c.save(); val q = ((vface / (PI / 2)).roundToInt() % 4 + 4) % 4; c.rotate(q * 90f, vx * S, vy * S)
            rect(c, (vx * S).roundToInt() + 4, (vy * S).roundToInt() - 1, 6, 2, C.K); px(c, (vx * S).roundToInt() + 9, (vy * S).roundToInt() - 1, C.ICE)
            c.restore()
        }

        // guards, standing or fallen
        for (g in world.guards) {
            if (g.dead) {
                val cx = (g.x * S).roundToInt(); val cy = (g.y * S).roundToInt()
                rect(c, cx - 7, cy - 3, 14, 7, a(C.K, 0x80)); rect(c, cx - 6, cy - 3, 12, 6, C.N0); rect(c, cx - 5, cy - 2, 10, 4, 0xFF141C3A.toInt())
                px(c, cx - 1, cy - 1, C.RED); px(c, cx + 1, cy + 1, C.RED); px(c, cx + 1, cy - 1, C.RED); px(c, cx - 1, cy + 1, C.RED)
                continue
            }
            if (g.chasing) ring(c, g.x * S, g.y * S, 7f + ((clock * 3f) % 1f) * 8f, a(C.RED, 0xC0))
            person(c, g.x, g.y, g.face, 0xFF2F58C8.toInt(), C.W, 0xFF0B1230.toInt(), true, false)
            if (g.meter > 0.02f) {   // how close he is to locking on: a ring of pixels
                val n = (g.meter * 24).toInt()
                for (k in 0 until n) { val ang = -PI.toFloat() / 2 + k * 2 * PI.toFloat() / 24; px(c, (g.x * S + cos(ang) * 9f).roundToInt(), (g.y * S + sin(ang) * 9f).roundToInt(), if (g.meter > 0.6f) C.RED else C.WARM) }
            }
        }

        // you
        val px0 = world.px; val py0 = world.py
        if (world.noise > 0.05f) {
            val ph = (clock * 2.4f) % 1f
            ring(c, px0 * S, py0 * S, world.noise * S * (0.35f + 0.65f * ph), a(C.W, ((1f - ph) * 0x90).toInt()))
            ring(c, px0 * S, py0 * S, world.noise * S, a(C.W, 0x30))
        }
        if (world.lightAt(px0, py0) > 0.9f) glow(c, px0 * S, py0 * S, 13f, C.LAMP, 0x50)
        person(c, px0, py0, world.face, 0xFFE6F0FF.toInt(), C.CYAN, 0xFF1A1420.toInt(), false, true)

        // the contact's shots
        for (s in world.shots) {
            val f = 1f - s.age / 0.3f
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = a(C.WARM, (f.coerceIn(0f, 1f) * 255).toInt())
            c.drawLine(s.x1 * S, s.y1 * S, s.x2 * S, s.y2 * S, p); p.style = Paint.Style.FILL
            if (s.age < 0.12f) { val mx = (s.x1 * S).roundToInt(); val my = (s.y1 * S).roundToInt(); rect(c, mx - 2, my - 2, 5, 5, C.WARM); rect(c, mx - 1, my - 1, 3, 3, C.W) }
            if (s.age < 0.2f) { val ix = (s.x2 * S).roundToInt(); val iy = (s.y2 * S).roundToInt(); rect(c, ix - 1, iy - 1, 3, 3, C.RED) }
        }
        return frame
    }

    /** A guard's torch beam: a hard-edged wedge of cold light (a brighter core near him), reddening as he notices you. */
    private fun cone(c: Canvas, e: Eye, meter: Float, chasing: Boolean, fade: Float) {
        if (fade < 0.05f) return
        val tint = if (chasing) C.RED else if (meter > 0.4f) C.WARM else C.LAMP
        val alpha = ((0x30 + meter * 0x30) * fade).toInt()
        for (pass in 0..1) {
            val shrink = if (pass == 0) 1f else 0.55f
            path.reset(); path.moveTo(e.x * S, e.y * S)
            val n = 40
            for (i in 0..n) {
                val ang = e.face - e.half + 2 * e.half * i / n
                val l = world.rayLength(e, ang) * shrink
                path.lineTo(e.x * S + cos(ang) * l * S, e.y * S + sin(ang) * l * S)
            }
            path.close()
            p.style = Paint.Style.FILL; p.color = a(tint, if (pass == 0) alpha else alpha * 3 / 4)
            c.drawPath(path, p)
        }
    }
init { buildStatic() }   // last, so every property above exists first
}
