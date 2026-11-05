package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The warehouse conveyor in pixel art: seen from above and in front, the belts scroll past (chevrons running forward), crates,
 * tools, swaying cables and slamming presses come down them, and the courier runs and sidesteps. 14 tiles wide, 24 tall.
 */
internal class BeltScene(private val world: BeltWorld) : PixelSprites() {
    private val S = 16
    val W = 14 * S
    val ROWS = 24
    val H = ROWS * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)
    private val floorTile = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val floorTile2 = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)

    private val steel = 0xFF6A7890.toInt(); private val steelD = 0xFF3A4458.toInt(); private val steelL = 0xFFA9B4C8.toInt()
    private val hazard = 0xFFFFC82E.toInt()

    init {
        for ((bm, seed) in listOf(floorTile to 1, floorTile2 to 2)) {
            val c = Canvas(bm)
            rect(c, 0, 0, S, S, 0xFF0F1A1E.toInt())
            for (y in 0 until S) for (x in 0 until S) { val v = hash(x, y, seed) % 100; if (v < 9) px(c, x, y, 0xFF16262B.toInt()) else if (v < 12) px(c, x, y, 0xFF070E10.toInt()) }
            rect(c, 0, 0, S, 1, 0xFF0A1316.toInt()); rect(c, 0, 0, 1, S, 0xFF0A1316.toInt())
            if (seed == 2) { for (i in 0 until S step 4) { px(c, i, 7, 0xFF050B0D.toInt()); px(c, 7, i, 0xFF050B0D.toInt()) } }
        }
    }

    private var camWy = 0
    /** Screen y (art pixels) of world progress [sw] (tiles). */
    private fun sy(sw: Float): Int = H - ((sw * S).roundToInt() - camWy)
    private fun sx(xw: Float): Int = (xw * S).roundToInt()

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val ps = world.s
        camWy = ((ps - 7f) * S).roundToInt()

        // the warehouse floor and the racks down both sides
        val topS = (camWy + H) / S.toFloat(); val botS = camWy / S.toFloat()
        val r0 = floor(botS).toInt() - 1; val r1 = floor(topS).toInt() + 1
        for (r in r0..r1) {
            val y = sy(r.toFloat() + 1f)
            for (cx in 0 until 14) c.drawBitmap(if ((cx + r) % 2 == 0) floorTile else floorTile2, (cx * S).toFloat(), y.toFloat(), plain)
            rack(c, 0, y, r, false); rack(c, 13 * S, y, r, true)
        }
        // the entrance you walked in through
        run {
            val y = sy(-3f)
            if (y > -40 && y < H + 40) {
                rect(c, 4 * S, y - 6, 6 * S, 10, 0xFF050B0D.toInt())
                rect(c, 4 * S, y - 14, 6 * S, 8, steelD); rect(c, 4 * S, y - 14, 6 * S, 2, steelL)
                for (i in 0 until 6 * S step 8) { rect(c, 4 * S + i, y - 14, 4, 8, hazard); }
                rect(c, 5 * S, y + 4, 4 * S, 3, a(0xFFFFE9B0.toInt(), 0x40))
            }
        }
        // the belts, the gaps between them, the platform at the end
        for (seg in world.spec.segs) belt(c, seg, clock)
        platform(c)

        // everything in the way, far ones first
        val obs = world.spec.obstacles.filter { it.s > ps - 9f && it.s < ps + 20f }.sortedByDescending { it.s }
        for (o in obs) obstacle(c, o)

        // me
        val pxx = sx(world.x); val pyy = sy(world.s) + 6
        val frameN = if (world.moving) ((clock * 9f).toInt() % 2) else 0
        val blink = world.invuln > 0f && ((clock * 14f).toInt() % 2 == 0)
        when (world.phase) {
            BeltWorld.Phase.FALL -> {   // dropping into the gap
                val k = (world.phaseT / 1.2f).coerceIn(0f, 1f)
                c.save(); c.clipRect(0f, 0f, W.toFloat(), (pyy - 2 + (k * 8)).toFloat())
                folk(c, pxx, (pyy + k * 22f).roundToInt(), 0, frameN, clerk, false, true)
                c.restore()
            }
            else -> if (!blink) folk(c, pxx, pyy, 0, frameN, clerk, false, world.phase != BeltWorld.Phase.DONE)
        }
        if (world.hitFlash > 0f) { p.color = a(0xFFFF3B55.toInt(), (world.hitFlash * 0x90).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        // speed streaks
        if (world.phase == BeltWorld.Phase.RIDE) for (i in 0 until 14) {
            val xx = (hash(i, 9, 3) % W); val off = ((clock * world.speedNow * 22f + hash(i, 5, 7) % H) % H).toInt()
            rect(c, xx, off, 1, 6 + hash(i, 1, 2) % 6, a(PX.ICE, 0x28))
        }
        return frame
    }

    private fun rack(c: Canvas, x0: Int, y: Int, r: Int, right: Boolean) {
        rect(c, x0, y, S, S, 0xFF0B1316.toInt())
        rect(c, x0 + (if (right) 0 else S - 2), y, 2, S, steelD)
        val v = hash(r, if (right) 5 else 3, 4) % 4
        if (v != 0) { rect(c, x0 + 2, y + 2, 11, 12, 0xFF4A3A22.toInt()); rect(c, x0 + 2, y + 2, 11, 2, 0xFF7A6038.toInt()); rect(c, x0 + 6, y + 2, 3, 12, 0xFF3A2C18.toInt()) }
        rect(c, x0, y + S - 2, S, 2, steelD)
    }

    private fun belt(c: Canvas, seg: BeltSeg, clock: Float) {
        val yTop = sy(seg.s1); val yBot = sy(seg.s0)
        if (yBot < -8 || yTop > H + 8) return
        val x0 = sx(seg.cx - seg.half); val x1 = sx(seg.cx + seg.half)
        val y0 = maxOf(yTop, -4); val y1 = minOf(yBot, H + 4)
        rect(c, x0, y0, x1 - x0, y1 - y0, 0xFF1B2128.toInt())
        // chevrons running along the belt (the way it moves)
        val off = ((clock * seg.speed * S) % 16f).toInt()
        var yy = y1 - 16 + off - 16
        while (yy > y0 - 16) {
            if (yy + 14 >= y0 && yy <= y1) {
                for (cxp in x0 + 6 until x1 - 6 step 16) {
                    for (k in 0..4) { rect(c, cxp + 2 + k, yy + 5 - k, 1, 2, 0xFF2D3844.toInt()); rect(c, cxp + 11 - k, yy + 5 - k, 1, 2, 0xFF2D3844.toInt()) }
                }
            }
            yy -= 16
        }
        // rails and rivets
        rect(c, x0, y0, 4, y1 - y0, steel); rect(c, x0, y0, 1, y1 - y0, steelL); rect(c, x0 + 3, y0, 1, y1 - y0, steelD)
        rect(c, x1 - 4, y0, 4, y1 - y0, steel); rect(c, x1 - 1, y0, 1, y1 - y0, steelD); rect(c, x1 - 4, y0, 1, y1 - y0, steelL)
        var ry = y0 - (y0 % 12); while (ry < y1) { px(c, x0 + 1, ry, PX.ICE); px(c, x1 - 3, ry, PX.ICE); ry += 12 }
        // the rollers at each end, and a pit with warning stripes where the belt stops
        if (yTop > -8 && yTop < H) { rect(c, x0 - 2, yTop - 4, x1 - x0 + 4, 6, steelD); rect(c, x0 - 2, yTop - 4, x1 - x0 + 4, 2, steelL); pit(c, x0, x1, yTop - 4 - 16, 16) }
        if (yBot > 0 && yBot < H + 8) { rect(c, x0 - 2, yBot - 2, x1 - x0 + 4, 6, steelD); rect(c, x0 - 2, yBot - 2, x1 - x0 + 4, 2, steelL) }
    }

    /** The gap you have to jump across. */
    private fun pit(c: Canvas, x0: Int, x1: Int, y: Int, h: Int) {
        rect(c, x0 - 40, y, (x1 - x0) + 80, h, 0xFF03080A.toInt())
        for (i in 0 until ((x1 - x0) + 80) step 8) rect(c, x0 - 40 + i, y, 4, 2, hazard)
    }

    private fun platform(c: Canvas) {
        val yTop = sy(world.spec.endS); val yBot = sy(world.spec.platformS)
        if (yBot < 0 || yTop > H) return
        rect(c, 1 * S, yTop, 12 * S, yBot - yTop, 0xFF2A333C.toInt())
        for (r in 0 until ((yBot - yTop) / S + 1)) for (cx in 1 until 13) { rect(c, cx * S, yTop + r * S, 1, S, steelD); rect(c, cx * S, yTop + r * S, S, 1, steelD) }
        for (i in 0 until 12 * S step 8) { rect(c, S + i, yBot - 4, 4, 4, hazard); rect(c, S + i, yTop, 4, 3, hazard) }
        // the jetpack on its stand
        val jx = sx(world.spec.pickupX); val jy = sy(world.spec.endS - 2.5f)
        val glow = (sin(animT * 4f) * 0.5f + 0.5f)
        rect(c, jx - 10, jy - 8, 20, 18, a(PX.WARM, (0x20 + 0x30 * glow).toInt()))
        rect(c, jx - 6, jy + 5, 12, 4, steelD)
        rect(c, jx - 6, jy - 8, 5, 14, 0xFFC03030.toInt()); rect(c, jx + 1, jy - 8, 5, 14, 0xFFC03030.toInt())         // two red tanks
        rect(c, jx - 6, jy - 8, 5, 2, 0xFFFF7070.toInt()); rect(c, jx + 1, jy - 8, 5, 2, 0xFFFF7070.toInt())
        rect(c, jx - 6, jy + 6, 5, 3, steel); rect(c, jx + 1, jy + 6, 5, 3, steel)
        rect(c, jx - 1, jy - 4, 2, 8, 0xFF8A5A2A.toInt())                                                               // straps
    }

    private fun obstacle(c: Canvas, o: BeltObs) {
        val x = sx(world.obsX(o)); val y = sy(o.s)
        val ww = (o.w * S).roundToInt(); val hh = (o.h * S).roundToInt()
        when (o.kind) {
            BeltWorld.BOX -> {
                rect(c, x - ww / 2 + 2, y - hh / 2 + 3, ww, hh, a(PX.K, 0x70))
                rect(c, x - ww / 2, y - hh / 2, ww, hh, PX.K); rect(c, x - ww / 2 + 1, y - hh / 2 + 1, ww - 2, hh - 2, 0xFF9A7A3A.toInt())
                rect(c, x - ww / 2 + 1, y - hh / 2 + 1, ww - 2, 2, 0xFFC8A458.toInt())
                rect(c, x - 2, y - hh / 2 + 1, 4, hh - 2, 0xFF6A5026.toInt())
                for (i in 0 until ww - 4 step 6) rect(c, x - ww / 2 + 3 + i, y - hh / 2 + 3, 1, hh - 6, 0xFF7A5E2C.toInt())
            }
            BeltWorld.TOOL -> {
                if (ww >= hh) { rect(c, x - ww / 2, y - 3, ww, 6, PX.K); rect(c, x - ww / 2 + 1, y - 2, ww - 2, 3, steel); rect(c, x - ww / 2 + 1, y - 2, ww - 2, 1, steelL); rect(c, x + ww / 2 - 4, y - 5, 5, 10, steelD) }
                else { rect(c, x - 3, y - hh / 2, 6, hh, PX.K); rect(c, x - 2, y - hh / 2 + 1, 3, hh - 2, steel); rect(c, x - 2, y - hh / 2 + 1, 1, hh - 2, steelL); rect(c, x - 6, y - hh / 2, 12, 7, steelD); rect(c, x - 3, y - hh / 2 + 2, 6, 3, 0xFF03080A.toInt()) }   // a big spanner
                rect(c, x - ww / 2 + 2, y + 3, ww, 2, a(PX.K, 0x60))
            }
            BeltWorld.CABLE -> {   // hangs down from the ceiling: a hook at the bottom, the cable going up
                rect(c, x - 6, y + 4, 14, 4, a(PX.K, 0x70))                                  // its shadow on the belt
                rect(c, x - 1, y - 30, 3, 34, PX.K); rect(c, x, y - 30, 1, 34, 0xFF3A3A44.toInt())
                rect(c, x - 3, y - 2, 7, 5, steelD); rect(c, x - 2, y - 1, 5, 3, steel); rect(c, x - 1, y + 1, 3, 4, PX.K)  // the hook
                px(c, x, y + 4, steelL)
            }
            BeltWorld.MACHINE -> {
                val live = world.obsLive(o)
                rect(c, x - ww / 2, y - hh / 2, ww, hh, a(PX.K, if (live) 0xA0 else 0x50))                     // the floor under it
                val lift = if (live) 0 else 12
                rect(c, x - ww / 2 - 3, y - hh / 2 - 6 - lift, ww + 6, hh + 8, PX.K)
                rect(c, x - ww / 2 - 2, y - hh / 2 - 5 - lift, ww + 4, hh + 6, steelD)
                rect(c, x - ww / 2 - 2, y - hh / 2 - 5 - lift, ww + 4, 3, steelL)
                var i = 0; while (i < ww + 4) { rect(c, x - ww / 2 - 2 + i, y + hh / 2 - 3 - lift, 4, 3, hazard); i += 8 }
                if (live) { px(c, x - ww / 2 - 4, y + hh / 2, PX.WARM); px(c, x + ww / 2 + 3, y + hh / 2, PX.W); px(c, x, y + hh / 2 + 2, PX.WARM) }
                else rect(c, x - 3, y - hh / 2 - 1, 6, 4, 0xFFFF9A52.toInt())                                   // a light that says it is about to come down
            }
        }
    }
}
