package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The jetpack run in pixel art: a side view of the warehouse with parallax (windows far back, racks in the middle, girders and
 * lamps and floor in front), spinning coins, electric zappers, and the courier with a jetpack on his back.
 */
internal class FlyScene(private val world: FlyWorld) : PixelSprites() {
    private val S = 16
    val W = 14 * S
    val H = 20 * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)

    private val steel = 0xFF6A7890.toInt(); private val steelD = 0xFF3A4458.toInt(); private val steelL = 0xFFA9B4C8.toInt()

    private fun mod(a: Int, b: Int) = ((a % b) + b) % b

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val sc = world.scroll
        rect(c, 0, 0, W, H, 0xFF08121A.toInt())

        // far back: brick wall, tall windows with a night sky, a pipe across
        val farOff = (sc * S * 0.25f).toInt()
        for (y in 0 until H step 8) rect(c, 0, y + 7, W, 1, 0xFF0C1C26.toInt())
        for (i in -1..(W / 56 + 2)) {
            val wx = i * 56 - mod(farOff, 56)
            rect(c, wx, 52, 26, 40, PX.K); rect(c, wx + 2, 54, 22, 36, 0xFF0E2A5A.toInt()); rect(c, wx + 2, 54, 22, 8, 0xFF1B4A8C.toInt())
            rect(c, wx + 12, 54, 2, 36, PX.K); rect(c, wx + 2, 70, 22, 2, PX.K); px(c, wx + 6, 58, PX.W); px(c, wx + 18, 64, PX.ICE)
        }
        rect(c, 0, 30, W, 6, PX.K); rect(c, 0, 31, W, 4, 0xFF2F6F73.toInt()); rect(c, 0, 31, W, 1, 0xFF8AF0E8.toInt())
        for (i in -1..(W / 40 + 2)) { val x = i * 40 - mod(farOff, 40); rect(c, x + 12, 29, 3, 8, PX.K); rect(c, x + 13, 30, 1, 6, 0xFF9AA4B5.toInt()) }

        // the middle: racks of boxes
        val midOff = (sc * S * 0.55f).toInt()
        for (i in -1..(W / 80 + 3)) {
            val idx = (midOff + i * 80) / 80
            val wx = i * 80 - mod(midOff, 80)
            val rk = hash(idx, 3, 5) % 3
            val top = H - 120 - 28 - rk * 8
            rect(c, wx, top, 3, 128 + rk * 8, steelD); rect(c, wx + 56, top, 3, 128 + rk * 8, steelD)
            for (sh in 0..2) {
                val yy = top + 10 + sh * 40
                rect(c, wx, yy, 59, 3, steel); rect(c, wx, yy, 59, 1, steelL)
                for (b in 0..2) if (hash(idx, sh * 3 + b, 9) % 5 != 0) {
                    val bx = wx + 4 + b * 18
                    rect(c, bx, yy - 14, 15, 14, PX.K); rect(c, bx + 1, yy - 13, 13, 12, 0xFF7A6038.toInt()); rect(c, bx + 1, yy - 13, 13, 2, 0xFFA8884A.toInt()); rect(c, bx + 6, yy - 13, 3, 12, 0xFF5A4626.toInt())
                }
            }
        }

        // the front: ceiling girder with lamps, floor with hazard stripes
        val nearOff = (sc * S).toInt()
        rect(c, 0, 0, W, 18, steelD); rect(c, 0, 0, W, 3, steelL); rect(c, 0, 15, W, 3, PX.K)
        for (i in -1..(W / 32 + 2)) { val x = i * 32 - mod(nearOff, 32); rect(c, x, 3, 2, 12, steel) }
        for (i in -1..(W / 128 + 2)) {
            val x = i * 128 - mod(nearOff, 128) + 40
            rect(c, x, 18, 2, 8, PX.K); rect(c, x - 6, 26, 14, 6, PX.K); rect(c, x - 5, 27, 12, 4, PX.W); rect(c, x - 3, 31, 8, 3, a(PX.LAMP, 0x50))
        }
        val floorTop = (world.floorY * S + 12).roundToInt()
        rect(c, 0, floorTop, W, H - floorTop, 0xFF1B2128.toInt()); rect(c, 0, floorTop, W, 3, steelL); rect(c, 0, floorTop + 3, W, 2, PX.K)
        for (i in -1..(W / 16 + 2)) { val x = i * 16 - mod(nearOff, 16); if (i % 2 == 0) { rect(c, x, floorTop + 8, 8, 6, 0xFFFFC82E.toInt()); rect(c, x + 8, floorTop + 8, 8, 6, 0xFF1B2128.toInt()) } }

        // coins
        for (co in world.coinList) {
            if (co.taken) continue
            val x = ((co.wx - sc) * S).roundToInt(); val y = (co.y * S).roundToInt()
            if (x < -8 || x > W + 8) continue
            val spin = ((clock * 9f + co.wx * 1.3f).toInt()) % 4
            val ww = intArrayOf(9, 6, 2, 6)[spin]
            rect(c, x - ww / 2 - 1, y - 5, ww + 2, 11, 0xFFB88A12.toInt())
            rect(c, x - ww / 2, y - 4, ww, 9, 0xFFFFD84D.toInt())
            if (ww > 4) { rect(c, x - ww / 2 + 1, y - 3, 2, 3, PX.W); px(c, x, y + 1, 0xFFB88A12.toInt()) }
        }
        // zappers
        for (z in world.zaps) {
            val x1 = ((z.wx - sc) * S).roundToInt(); val x2 = ((z.wx + z.dx - sc) * S).roundToInt()
            if ((x1 < -30 && x2 < -30) || (x1 > W + 30 && x2 > W + 30)) continue
            val y1 = (z.y1 * S).roundToInt(); val y2 = (z.y2 * S).roundToInt()
            val n = 14
            for (k in 0 until n) {
                val t0 = k / n.toFloat(); val t1 = (k + 1) / n.toFloat()
                val ax = x1 + ((x2 - x1) * t0).roundToInt(); val ay = y1 + ((y2 - y1) * t0).roundToInt()
                val bx = x1 + ((x2 - x1) * t1).roundToInt(); val by = y1 + ((y2 - y1) * t1).roundToInt()
                val j = ((hash(k, (clock * 24f).toInt(), 6) % 7) - 3)
                rect(c, minOf(ax, bx) + j, minOf(ay, by), abs(bx - ax) + 3, abs(by - ay) + 3, a(0xFF3DE8F5.toInt(), 0x90))
                rect(c, minOf(ax, bx) + j, minOf(ay, by), abs(bx - ax) + 1, abs(by - ay) + 1, PX.W)
            }
            for ((nx, ny) in listOf(x1 to y1, x2 to y2)) {
                rect(c, nx - 5, ny - 5, 10, 10, PX.K); rect(c, nx - 4, ny - 4, 8, 8, steel); rect(c, nx - 3, ny - 3, 3, 3, steelL); rect(c, nx - 2, ny - 2, 4, 4, a(0xFF3DE8F5.toInt(), 0xC0))
            }
        }

        // me, with a jetpack
        val fx = (world.px * S).roundToInt(); val bodyY = (world.y * S).roundToInt()
        val blink = world.invuln > 0f && ((clock * 14f).toInt() % 2 == 0)
        when (world.phase) {
            FlyWorld.Phase.DONE -> {   // thrown onto the floor, the dead jetpack smoking beside him
                val fy = floorTop + 4
                lying(c, fx + 4, fy, clerk, true, true)
                rect(c, fx - 22, fy - 7, 8, 6, 0xFFC03030.toInt()); rect(c, fx - 22, fy - 7, 8, 1, 0xFFFF7070.toInt())
                for (k in 0..4) { val ph = (clock * 0.9f + k * 0.2f) % 1f; rect(c, fx - 20 + (sin(clock * 2f + k) * 3).toInt(), fy - 10 - (ph * 24).toInt(), 3, 3, a(0xFF9AA4B5.toInt(), ((1f - ph) * 0xA0).toInt())) }
            }
            else -> if (!blink) {
                val tumble = world.phase == FlyWorld.Phase.DROP
                c.save()
                if (tumble) c.rotate((world.phaseT * 340f) % 360f, fx.toFloat(), (bodyY - 8).toFloat())
                rect(c, fx - 10, bodyY - 12, 6, 13, PX.K); rect(c, fx - 9, bodyY - 11, 4, 11, 0xFFC03030.toInt()); rect(c, fx - 9, bodyY - 11, 4, 2, 0xFFFF7070.toInt())
                rect(c, fx - 9, bodyY, 4, 2, steel)
                folk(c, fx, bodyY + 8, 1, if (world.phase == FlyWorld.Phase.FLY && world.thrust) 1 else 0, clerk, false, true)
                val burning = (world.phase == FlyWorld.Phase.FLY && world.thrust) || (world.phase == FlyWorld.Phase.SPUTTER && (world.phaseT % 0.55f) < 0.16f)
                if (burning) {
                    val fl = 8 + (clock * 30f).toInt() % 5
                    rect(c, fx - 9, bodyY + 2, 4, fl, 0xFFFF7A2A.toInt()); rect(c, fx - 8, bodyY + 2, 2, fl - 3, 0xFFFFE07A.toInt()); px(c, fx - 8, bodyY + 2 + fl, 0xFFFF3B1A.toInt())
                }
                if (world.phase == FlyWorld.Phase.SPUTTER || tumble) for (k in 0..3) {
                    val ph = (clock * 1.4f + k * 0.25f) % 1f
                    rect(c, fx - 12 - (ph * 22).toInt(), bodyY + 4 + (sin(clock * 5 + k) * 3).toInt(), 4, 4, a(0xFF5A6068.toInt(), ((1f - ph) * 0xB0).toInt()))
                }
                c.restore()
            }
        }
        if (world.hitFlash > 0f) { p.color = a(0xFF3DE8F5.toInt(), (world.hitFlash * 0x70).toInt()); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }
}
