package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The town hall in pixel art, seen from above at a slant: a marble lobby with a fountain, reception desk, coffee corner and a row of
 * cubicles; a panelled hall with three rooms off each side (meeting rooms, offices, storage); and the mayor's wrecked office. The floors,
 * walls and furniture are painted once into one tall picture; each frame adds the people, bullets, grenades, the fog of war (you only see
 * what you have a line of sight to) and the camera that follows you.
 */
internal class ShootScene(private val world: ShootWorld) : PixelSprites() {
    private val S = 16
    val VW = 16
    var VH = 24; private set
    val W = VW * S
    var H = VH * S; private set
    private var frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private var fc = Canvas(frame)
    /** Show more rows so the view fills a tall screen (called with the height in art pixels before drawing). */
    fun resize(hpx: Int) { val vh = maxOf(24, (hpx + S - 1) / S); if (vh == VH) return; VH = vh; H = VH * S; frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); fc = Canvas(frame) }
    private var camY = world.py

    private val Z_LOBBY = 0; private val Z_CUBE = 1; private val Z_HALL = 2; private val Z_MEET = 3; private val Z_OFFICE = 4
    private val Z_STORE = 5; private val Z_MAYOR = 6; private val Z_DOOR = 7; private val Z_NONE = 8

    private fun zone(col: Int, row: Int): Int {
        if (row in 1..10) return Z_MAYOR
        if (row == 63) return Z_DOOR
        if (row in 33..62) return if (col >= 12 && row in 36..54) Z_CUBE else Z_LOBBY
        if (row in 12..31) {
            if (col in 6..9) return Z_HALL
            val g = when (row) { in 12..17 -> 0; in 19..24 -> 1; in 26..31 -> 2; else -> return Z_NONE }
            if (col in 1..4) return when (g) { 0 -> Z_MEET; 1 -> Z_OFFICE; else -> Z_STORE }
            if (col in 11..14) return when (g) { 0 -> Z_OFFICE; 1 -> Z_MEET; else -> Z_STORE }
        }
        return Z_NONE
    }

    // ---------- little colour helpers ----------
    private fun lite(c: Int, d: Int): Int {
        val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255); val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255); val b = ((c and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun K(v: Long) = v.toInt()

    // =====================================================================================================================
    //  the painted map
    // =====================================================================================================================
    private val bg: Bitmap by lazy { buildBg() }
    private lateinit var pix: IntArray
    private fun pset(x: Int, y: Int, c: Int) { if (x in 0 until W && y in 0 until world.rows * S) pix[y * W + x] = c }
    private fun isWall(c: Int, r: Int) = world.tile(c, r) == '#'

    private fun buildBg(): Bitmap {
        val bh = world.rows * S
        val bm = Bitmap.createBitmap(W, bh, Bitmap.Config.ARGB_8888)
        pix = IntArray(W * bh)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            if (isWall(col, r)) wallPix(col, r) else floorPix(col, r, zone(col, r))
        }
        bm.setPixels(pix, 0, W, 0, 0, W, bh)
        val c = Canvas(bm)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val ch = world.tile(col, r)
            val x = col * S; val y = r * S
            when (ch) {
                'c' -> pillar(c, x, y)
                'u' -> statue(c, x, y, col, r)
                'd' -> desk(c, x, y, col, r)
                't' -> table(c, x, y, col, r)
                'f' -> sofa(c, x, y, col, r)
                'k' -> panel(c, x, y, col, r)
                'y' -> cubDesk(c, x, y, col, r)
                'x' -> crate(c, x, y, col, r)
                'r' -> rack(c, x, y, col, r)
            }
        }
        decorate(c)
        return bm
    }

    // ---------- floors ----------
    private fun floorPix(col: Int, row: Int, z: Int) {
        val x0 = col * S; val y0 = row * S
        for (yy in 0 until S) for (xx in 0 until S) {
            val gx = x0 + xx; val gy = y0 + yy
            val n = hash(gx, gy, 5) % 100
            var cl: Int
            val carpetRun = (z == Z_LOBBY || z == Z_HALL) && (col == 7 || col == 8)
            if (carpetRun) {
                val cx = (col - 7) * S + xx
                cl = K(0xFF7A1628)
                if ((cx + gy) % 12 == 0 || (cx - gy + 4000) % 12 == 0) cl = K(0xFF8E2236)
                if (cx < 4 || cx > 27) cl = K(0xFF4A0C18)
                if (cx < 2 || cx > 29) cl = K(0xFFC8A040)
                if (n < 4) cl = lite(cl, -10)
                pset(gx, gy, cl); continue
            }
            when (z) {
                Z_LOBBY -> {
                    val rug = col in 1..3 && row in 41..46
                    if (rug) {
                        cl = K(0xFF4A2638); if ((xx + yy) % 6 == 0) cl = K(0xFF5C3248)
                        if ((col == 1 && xx < 2) || (col == 3 && xx > 13) || (row == 41 && yy < 2) || (row == 46 && yy > 13)) cl = K(0xFFC8A040)
                    } else {
                        cl = if ((col + row) % 2 == 0) K(0xFF2C3659) else K(0xFF37426B)
                        if (hash(gx / 3, gy / 2, 6) % 17 == 0) cl = lite(cl, 14)
                        if (n == 0) cl = lite(cl, 24)
                        if (xx == 0 || yy == 0) cl = lite(cl, -9)
                    }
                }
                Z_CUBE -> { cl = K(0xFF2A3A4E); if (n < 9) cl = lite(cl, 7) else if (n > 93) cl = lite(cl, -7); if (xx == 0 || yy == 0) cl = lite(cl, -4) }
                Z_HALL -> {
                    val pr = gy / 4; val off = (pr % 2) * 8
                    cl = when (hash((gx + off) / 16, pr, 3) % 3) { 0 -> K(0xFF4A3426); 1 -> K(0xFF553B2B); else -> K(0xFF3F2C20) }
                    if (gy % 4 == 3 || (gx + off) % 16 == 15) cl = K(0xFF2A1C14)
                    if (n < 6) cl = lite(cl, 8)
                }
                Z_MEET -> { cl = K(0xFF1C2848); if (n < 12) cl = lite(cl, 7); if ((xx == 0 || yy == 0)) cl = lite(cl, -4) }
                Z_OFFICE -> { cl = K(0xFF1F3A30); if (n < 12) cl = lite(cl, 7); if ((xx == 0 || yy == 0)) cl = lite(cl, -4) }
                Z_STORE -> {
                    cl = K(0xFF2A2E38); if (n < 10) cl = lite(cl, 9) else if (n > 95) cl = lite(cl, -10)
                    if (xx == 0 || yy == 0) cl = lite(cl, -8)
                    if ((col == 1 || col == 14) && (xx == 1 || xx == 14) && row % 2 == 0) cl = K(0xFFB89A2E)
                }
                Z_MAYOR -> {
                    cl = K(0xFF4A1824); if (n < 10) cl = lite(cl, 8)
                    if (hash(gx / 6, gy / 6, 9) % 9 == 0) cl = K(0xFF2C0C16)          // stains
                    if (hash(gx / 9, gy / 7, 12) % 13 == 0 && n < 50) cl = K(0xFF150608) // burns
                }
                Z_DOOR -> { cl = lite(K(0xFF8A94AC), -yy * 3); if (n < 6) cl = lite(cl, -14) }
                else -> cl = K(0xFF0C0F1E)
            }
            pset(gx, gy, cl)
        }
    }

    // ---------- walls ----------
    private fun wallPix(col: Int, row: Int) {
        val x0 = col * S; val y0 = row * S
        val belowOpen = row + 1 < world.rows && !isWall(col, row + 1)
        val zb = zone(col, row + 1)
        val eastOpen = col + 1 < world.cols && !isWall(col + 1, row); val westOpen = col > 0 && !isWall(col - 1, row)
        for (yy in 0 until S) for (xx in 0 until S) {
            val gx = x0 + xx; val gy = y0 + yy
            val n = hash(gx, gy, 8) % 100
            var cl: Int
            if (belowOpen) {
                if (yy < 6) { cl = K(0xFF171B2E); if (yy == 0) cl = K(0xFF222844) }
                else when (zb) {
                    Z_LOBBY, Z_CUBE, Z_DOOR -> {
                        if (yy == 12) cl = K(0xFFB8963C)
                        else if (yy > 12) cl = if (yy == 15) K(0xFF1A0E14) else K(0xFF3B2430)
                        else {
                            val br = (yy - 6) / 3
                            cl = if (hash((gx + (br % 2) * 6) / 12, br, 2) % 2 == 0) K(0xFF6A7696) else K(0xFF5E6A88)
                            if ((yy - 6) % 3 == 2 || (gx + (br % 2) * 6) % 12 == 11) cl = K(0xFF3E4864)
                        }
                    }
                    Z_MAYOR -> {
                        cl = if (xx % 8 < 4) K(0xFF5E1E2C) else K(0xFF4E1824)
                        if (yy >= 13) cl = if (yy == 15) K(0xFF1A0A0E) else K(0xFF2A1810)
                        if (hash(gx / 3, gy / 3, 14) % 11 == 0 && yy < 13) cl = K(0xFF2A0A12)    // rips in the wallpaper
                    }
                    Z_STORE -> { cl = if ((yy - 6) % 4 == 3 || (gx + ((yy - 6) / 4 % 2) * 8) % 16 == 15) K(0xFF2E333F) else K(0xFF4A5060); if (n < 8) cl = lite(cl, 8) }
                    else -> {
                        if (yy >= 13) cl = if (yy == 15) K(0xFF160E0A) else K(0xFF2E1E14)
                        else { cl = if (xx % 6 == 5) K(0xFF3E2A1C) else if ((xx / 6) % 2 == 0) K(0xFF6E4C34) else K(0xFF624330); if (yy == 12) cl = K(0xFF8A6844) }
                    }
                }
            } else {
                cl = K(0xFF0B0E1C)
                if (n < 6) cl = K(0xFF11152A)
                if ((eastOpen && xx >= 13) || (westOpen && xx <= 2)) cl = K(0xFF2A3050)
                if (row > 0 && !isWall(col, row - 1) && yy < 3) cl = K(0xFF1E2440)
            }
            pset(gx, gy, cl)
        }
    }

    // ---------- furniture ----------
    private fun pillar(c: Canvas, x: Int, y: Int) {
        rect(c, x + 1, y + 13, 14, 3, a(PX.K, 0xA0))
        rect(c, x + 2, y + 12, 12, 3, K(0xFF9AA4BC)); rect(c, x + 2, y + 12, 12, 1, K(0xFFD8DEEE))                      // base
        rect(c, x + 3, y + 3, 10, 9, K(0xFFB4BED6)); rect(c, x + 3, y + 3, 3, 9, K(0xFFE8ECF8)); rect(c, x + 10, y + 3, 3, 9, K(0xFF6A7490)); rect(c, x + 8, y + 3, 1, 9, K(0xFF8A94AC))
        rect(c, x + 2, y + 1, 12, 3, K(0xFFD8DEEE)); rect(c, x + 2, y + 1, 12, 1, PX.W); rect(c, x + 2, y + 3, 12, 1, K(0xFF6A7490))   // capital
    }

    private fun statue(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val uL = world.tile(col - 1, row) == 'u'; val uU = world.tile(col, row - 1) == 'u'
        if (uL || uU) return                                   // part of the big fountain, drawn from its top-left tile
        val big = world.tile(col + 1, row) == 'u' && world.tile(col, row + 1) == 'u'
        if (big) { fountain(c, x, y); return }
        rect(c, x + 1, y + 13, 14, 3, a(PX.K, 0xA0))
        rect(c, x + 2, y + 9, 12, 6, K(0xFF7A8498)); rect(c, x + 2, y + 9, 12, 2, K(0xFFB4BED6)); rect(c, x + 2, y + 14, 12, 1, K(0xFF4A5470))     // plinth
        rect(c, x + 5, y + 3, 6, 7, K(0xFFD8DEEE)); rect(c, x + 5, y + 3, 2, 7, PX.W); rect(c, x + 9, y + 3, 2, 7, K(0xFF8A94AC))                      // robed body
        rect(c, x + 6, y, 4, 4, K(0xFFE8ECF8)); rect(c, x + 9, y + 1, 1, 3, K(0xFF9AA4BC))                                                           // head
        rect(c, x + 10, y + 4, 3, 5, K(0xFFC8D0E4)); rect(c, x + 11, y + 4, 1, 5, K(0xFF6A7490))                                                      // a scroll in one hand
    }

    private fun fountain(c: Canvas, x: Int, y: Int) {
        rect(c, x + 1, y + 28, 30, 4, a(PX.K, 0xA0))
        for (dy in 0 until 30) for (dx in 0 until 32) {
            val ex = (dx - 15.5f) / 15.5f; val ey = (dy - 15f) / 14.5f
            val d = ex * ex + ey * ey
            if (d > 1f) continue
            px(c, x + dx, y + dy, if (d > 0.86f) K(0xFFB4BED6) else if (d > 0.78f) K(0xFF6A7490) else if ((dx + dy) % 5 == 0) K(0xFF4C8FD8) else K(0xFF2F6FB8))
        }
        rect(c, x + 12, y + 7, 8, 14, K(0xFF8A94AC)); rect(c, x + 12, y + 7, 3, 14, K(0xFFD8DEEE)); rect(c, x + 17, y + 7, 3, 14, K(0xFF5A6482))
        rect(c, x + 10, y + 18, 12, 4, K(0xFFB4BED6)); rect(c, x + 13, y + 3, 6, 6, K(0xFFE8ECF8))
        for (k in 0..5) { px(c, x + 15 - k * 2, y + 6 + k, K(0xFF8AD0FF)); px(c, x + 16 + k * 2, y + 6 + k, K(0xFF8AD0FF)) }
        for (k in 0..4) rect(c, x + 6 + k * 5, y + 22 + (k % 2), 2, 1, K(0xFFB8E4FF))
    }

    private fun desk(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) == 'd'; val r = world.tile(col + 1, row) == 'd'
        val x0 = x + if (l) 0 else 1; val w = 16 - (if (l) 0 else 1) - (if (r) 0 else 1)
        val mayor = row <= 10
        rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0x80))
        rect(c, x0, y + 3, w, 12, PX.K)
        rect(c, x0, y + 4, w, 8, if (mayor) K(0xFF4A3220) else K(0xFF7A5230)); rect(c, x0, y + 4, w, 2, if (mayor) K(0xFF6A4A32) else K(0xFF9A6C3E))
        rect(c, x0, y + 12, w, 2, K(0xFF3E2818))
        val h = hash(col, row, 2) % 6
        if (mayor) { if (h < 3) { rect(c, x + 4, y + 6, 4, 3, K(0xFFD8D4C0)); px(c, x + 5, y + 7, K(0xFF8A1830)) } }
        else when (h) {
            0 -> { rect(c, x + 3, y + 5, 5, 4, PX.W); rect(c, x + 3, y + 5, 5, 1, K(0xFFC0C8D8)); px(c, x + 5, y + 7, K(0xFF8A94AC)) }
            1 -> { rect(c, x + 9, y + 5, 3, 4, K(0xFFE8E0C8)); rect(c, x + 10, y + 3, 1, 3, K(0xFFFFD27A)); px(c, x + 10, y + 3, PX.WARM) }       // a lamp
            2 -> { rect(c, x + 3, y + 4, 8, 5, PX.K); rect(c, x + 4, y + 5, 6, 3, K(0xFF29F0FF)); rect(c, x + 6, y + 9, 2, 1, K(0xFF2A3040)) }      // a screen
            3 -> { rect(c, x + 10, y + 6, 3, 3, PX.W); px(c, x + 11, y + 7, K(0xFF6A3A1E)) }
            else -> {}
        }
    }

    private fun table(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) == 't'; val r = world.tile(col + 1, row) == 't'
        val u = world.tile(col, row - 1) == 't'; val dn = world.tile(col, row + 1) == 't'
        val x0 = x + if (l) 0 else 1; val x1 = x + 16 - if (r) 0 else 1
        val y0 = y + if (u) 0 else 2; val y1 = y + 16 - if (dn) 0 else 1
        val z = zone(col, row)
        if (row == 55) {                                                      // the reception desk
            rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0x80))
            rect(c, x0, y + 1, x1 - x0, 15, PX.K)
            rect(c, x0, y + 2, x1 - x0, 6, K(0xFF7A5230)); rect(c, x0, y + 2, x1 - x0, 2, K(0xFFA67848))
            rect(c, x0, y + 8, x1 - x0, 7, K(0xFF4A2E1E)); rect(c, x0 + 1, y + 9, x1 - x0 - 2, 1, K(0xFFC8A040))
            for (k in 0..1) rect(c, x0 + 3 + k * 6, y + 10, 4, 4, K(0xFF3A2416))
            if (col == 6) { rect(c, x + 3, y - 1, 6, 5, PX.K); rect(c, x + 4, y, 4, 3, K(0xFF29F0FF)) }
            if (col == 9) { rect(c, x + 4, y + 1, 3, 4, PX.W); rect(c, x + 8, y + 2, 4, 2, K(0xFFC8A040)); px(c, x + 5, y + 2, PX.RED) }
            return
        }
        val coffee = z == Z_LOBBY || z == Z_CUBE
        rect(c, x0, y0 + 1, x1 - x0, y1 - y0, a(PX.K, 0x90))
        if (coffee) {
            rect(c, x0, y0, x1 - x0, y1 - y0, K(0xFF1A2230)); rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, K(0xFFA8C8E0)); rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, 2, K(0xFFD8ECF8))
            if (hash(col, row, 4) % 2 == 0) { rect(c, x + 5, y + 5, 3, 3, PX.W); rect(c, x + 6, y + 6, 1, 1, K(0xFF6A3A1E)) }
            if (hash(col, row, 5) % 2 == 0) rect(c, x + 9, y + 8, 4, 3, K(0xFFC8505A))
        } else {
            rect(c, x0, y0, x1 - x0, y1 - y0, PX.K)
            rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, K(0xFF5A3822)); rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, 2, K(0xFF8A5C38)); rect(c, x0 + 1, y1 - 3, x1 - x0 - 2, 2, K(0xFF3E2616))
            val h = hash(col, row, 7) % 4
            if (h == 0) { rect(c, x + 3, y + 4, 5, 4, PX.W); px(c, x + 5, y + 6, K(0xFF8A94AC)) }
            if (h == 1) { rect(c, x + 8, y + 5, 5, 3, PX.K); rect(c, x + 9, y + 6, 3, 1, K(0xFF29F0FF)) }
            if (h == 2) { rect(c, x + 5, y + 6, 3, 3, PX.W); px(c, x + 6, y + 7, K(0xFF6A3A1E)) }
        }
    }

    private fun sofa(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) == 'f'; val r = world.tile(col + 1, row) == 'f'
        val u = world.tile(col, row - 1) == 'f'; val dn = world.tile(col, row + 1) == 'f'
        val x0 = x + if (l) 0 else 1; val x1 = x + 16 - if (r) 0 else 1
        val y0 = y + if (u) 0 else 1; val y1 = y + 16 - if (dn) 0 else 1
        rect(c, x0, y0 + 2, x1 - x0, y1 - y0, a(PX.K, 0x90))
        if (row <= 10) {                                       // the mayor's sofa, shoved over on its back against the window
            rect(c, x0, y0, x1 - x0, y1 - y0, PX.K); rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, K(0xFF2C5A5C)); rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, 3, K(0xFF3E7A7C))
            rect(c, x0 + 2, y1 - 3, 2, 2, PX.K); rect(c, x1 - 4, y1 - 3, 2, 2, PX.K); rect(c, x0 + 2, y0 + 5, 3, 1, K(0xFF1A3A3C))
            return
        }
        rect(c, x0, y0, x1 - x0, y1 - y0, PX.K)
        rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2, K(0xFF7A2432))
        if (!l) rect(c, x0 + 1, y0 + 1, 2, y1 - y0 - 2, K(0xFF5A1624))           // an arm at the ends
        if (!r) rect(c, x1 - 3, y0 + 1, 2, y1 - y0 - 2, K(0xFF5A1624))
        if (!u) rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, 5, K(0xFF9A3646))           // the back rest
        rect(c, x0 + 2, y0 + 7, x1 - x0 - 4, 1, K(0xFF5A1624))
        rect(c, x0 + 1, y0 + 1, x1 - x0 - 2, 1, K(0xFFB84A5C))
    }

    private fun panel(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val l = world.tile(col - 1, row) == 'k'; val r = world.tile(col + 1, row) == 'k'
        val x0 = x + if (l) 0 else 1; val x1 = x + 16 - if (r) 0 else 1
        rect(c, x0, y + 8, x1 - x0, 8, a(PX.K, 0x90))
        rect(c, x0, y + 2, x1 - x0, 12, K(0xFF2E3648)); rect(c, x0, y + 3, x1 - x0, 10, K(0xFF7C8AA0)); rect(c, x0, y + 3, x1 - x0, 2, K(0xFFA8B4C8))
        rect(c, x0, y + 12, x1 - x0, 1, K(0xFF5A6478))
        val h = hash(col, row, 11) % 5
        if (h < 3) { rect(c, x + 3 + h * 3, y + 6, 4, 4, intArrayOf(K(0xFFFFE680), K(0xFFFF9AA8), K(0xFF9AE0FF))[h]); px(c, x + 4 + h * 3, y + 6, PX.RED) }
    }

    private fun cubDesk(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        val u = world.tile(col, row - 1) == 'y'; val dn = world.tile(col, row + 1) == 'y'
        val y0 = y + if (u) 0 else 1; val y1 = y + 16 - if (dn) 0 else 1
        rect(c, x, y0 + 1, 16, y1 - y0, a(PX.K, 0x70))
        rect(c, x + 1, y0, 15, y1 - y0, K(0xFF2A2218)); rect(c, x + 2, y0 + (if (u) 0 else 1), 13, y1 - y0 - (if (u) 0 else 1) - (if (dn) 0 else 1), K(0xFFB49A74))
        rect(c, x + 2, y0 + (if (u) 0 else 1), 3, y1 - y0, K(0xFFD0B88E))
        if (!u) { rect(c, x + 6, y0 + 3, 7, 5, PX.K); rect(c, x + 7, y0 + 4, 5, 3, K(0xFF29F0FF)); px(c, x + 8, y0 + 5, PX.W); rect(c, x + 8, y0 + 8, 3, 1, K(0xFF2A3040)) }   // a monitor
        else if (!dn) { rect(c, x + 7, y1 - 6, 6, 2, K(0xFF4A5470)); rect(c, x + 5, y1 - 8, 3, 3, PX.W) }                                                                   // keyboard and papers
        else { rect(c, x + 6, y0 + 6, 6, 4, PX.W); rect(c, x + 7, y0 + 7, 4, 1, K(0xFF8A94AC)) }
    }

    private fun crate(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0x80))
        if (row <= 10) {                                       // a steel cabinet dragged against the window
            rect(c, x, y + 1, 16, 14, PX.K); rect(c, x + 1, y + 2, 14, 12, K(0xFF4E5E5A)); rect(c, x + 1, y + 2, 14, 2, K(0xFF7A8E88))
            for (k in 0..2) { rect(c, x + 3, y + 4 + k * 3, 10, 2, K(0xFF3A4844)); rect(c, x + 7, y + 4 + k * 3, 2, 1, K(0xFFC8D0CC)) }
            if (hash(col, row, 3) % 2 == 0) rect(c, x + 2, y + 3, 4, 3, K(0xFFD8D4C0))
            return
        }
        val h = hash(col, row, 9) % 3
        rect(c, x + 1, y + 1, 14, 14, PX.K)
        val base = when (h) { 0 -> K(0xFF8A6838); 1 -> K(0xFF7A5A30); else -> K(0xFF9A7842) }
        rect(c, x + 2, y + 2, 12, 12, base); rect(c, x + 2, y + 2, 12, 2, lite(base, 28)); rect(c, x + 2, y + 12, 12, 2, lite(base, -30))
        rect(c, x + 2, y + 7, 12, 1, lite(base, -36)); rect(c, x + 7, y + 2, 1, 12, lite(base, -36))
        px(c, x + 3, y + 3, K(0xFFD8D4C0)); px(c, x + 12, y + 12, K(0xFFD8D4C0))
        if (h == 1) { rect(c, x + 4, y + 9, 5, 3, K(0xFFD8C890)); px(c, x + 5, y + 10, PX.RED) }
    }

    private fun rack(c: Canvas, x: Int, y: Int, col: Int, row: Int) {
        rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0x80))
        val books = row <= 20 || (col >= 11 && row <= 17)
        rect(c, x, y, 16, 15, PX.K); rect(c, x + 1, y + 1, 14, 13, if (books) K(0xFF3A2416) else K(0xFF3A4458))
        for (shelf in 0..2) {
            val sy = y + 2 + shelf * 4
            rect(c, x + 1, sy + 3, 14, 1, if (books) K(0xFF6A4A2E) else K(0xFF8A94AC))
            var bx = x + 2
            while (bx < x + 14) {
                val w = 2 + hash(bx, sy, 3) % 2
                val clr = if (books) intArrayOf(K(0xFF8A2A3A), K(0xFF2A5A8A), K(0xFF3A7A4A), K(0xFFB8963C), K(0xFF6A3A7A))[hash(bx, sy, 4) % 5]
                          else intArrayOf(K(0xFFB89A5A), K(0xFF8A7A5A), K(0xFFD0C8A0))[hash(bx, sy, 4) % 3]
                rect(c, bx, sy, w, 3, clr); px(c, bx, sy, lite(clr, 30))
                bx += w
            }
        }
    }

    // ---------- the extras that sit on the floor, the wall hangings and the light ----------
    private fun decorate(c: Canvas) {
        val rows = world.rows
        // chairs round the meeting tables, and rugs, notes on the wrecked office floor
        for (r in 0 until rows) for (col in 0 until world.cols) {
            val x = col * S; val y = r * S
            val z = zone(col, r)
            if (z == Z_MEET) {
                for ((dx, dy) in ShootWorld.D4) {
                    if (world.tile(col, r) != 't' && world.tile(col + dx, r + dy) == 't' && world.tile(col, r) == '.') {
                        val cx = x + 8 + dx * 5; val cy = y + 8 + dy * 5
                        rect(c, cx - 3, cy - 3, 6, 6, PX.K); rect(c, cx - 2, cy - 2, 4, 4, K(0xFF2A2A34)); rect(c, cx - 2, cy - 2, 4, 1, K(0xFF4A4A5C))
                    }
                }
            }
            if (z == Z_MAYOR && world.tile(col, r) == '.') {
                val h = hash(col, r, 21) % 14
                if (h == 0) { rect(c, x + 3, y + 5, 5, 4, K(0xFFD8D4C0)); px(c, x + 4, y + 6, K(0xFF8A94AC)) }
                if (h == 1) { rect(c, x + 8, y + 9, 4, 3, K(0xFFD8D4C0)); rect(c, x + 6, y + 4, 3, 2, K(0xFFC8C4B0)) }
                if (h == 2) for (k in 0..4) px(c, x + 3 + (k * 3) % 11, y + 4 + (k * 5) % 9, K(0xFFA8D8F0))              // broken glass
                if (h == 3) { rect(c, x + 4, y + 6, 8, 4, K(0xFF2A1C14)); px(c, x + 6, y + 7, K(0xFF8A5C38)) }               // a smashed drawer
                if (h == 4) { rect(c, x + 2, y + 9, 10, 1, K(0xFF6A0C18)); rect(c, x + 5, y + 8, 5, 3, K(0xFF6A0C18)) }     // a stain
            }
            if (z == Z_STORE && world.tile(col, r) == '.' && hash(col, r, 22) % 9 == 0) { rect(c, x + 4, y + 5, 7, 5, K(0xFFB89A5A)); rect(c, x + 4, y + 5, 7, 1, K(0xFFD8C890)) }
        }
        // the lobby: crest over the hall door, paintings and flags on the walls, and a welcome mat
        for (col in intArrayOf(2, 3, 11, 12)) { val x = col * S; val y = 32 * S + 5; rect(c, x, y, 16, 9, K(0xFF8A6A2C)); rect(c, x + 1, y + 1, 14, 7, K(0xFF1E2A44)); rect(c, x + 3, y + 3, 4, 3, K(0xFF5A8A5A)); rect(c, x + 8, y + 2, 4, 4, K(0xFFB8A070)); px(c, x + 5, y + 4, K(0xFF9AD0A0)) }
        for (col in intArrayOf(5, 10)) { val x = col * S; val y = 32 * S + 4; rect(c, x + 7, y, 2, 11, K(0xFF8A94AC)); rect(c, x + 1, y + 1, 7, 8, K(0xFFB02038)); rect(c, x + 1, y + 4, 7, 2, PX.GOLD) }
        rect(c, 6 * S, 32 * S + 1, 4 * S, 5, K(0xFF8A6A2C)); rect(c, 6 * S + 1, 32 * S + 2, 4 * S - 2, 3, PX.K)
        for (k in 0..8) px(c, 6 * S + 8 + k * 5, 32 * S + 3, PX.GOLD)
        // sconces down the side walls of the lobby and hall
        for (r in intArrayOf(36, 42, 48, 54, 60, 14, 20, 27)) {
            for (col in intArrayOf(1, 14)) {
                if (world.tile(col, r) == '#' ) continue
                val x = if (col == 1) 1 else 14 * S + 11; val y = r * S + 4
                if (r < 33 && col !in intArrayOf(6, 9)) continue
                rect(c, x, y, 4, 6, PX.K); rect(c, x + 1, y + 1, 2, 3, PX.WARM)
            }
        }
        // the street door at the bottom of the lobby
        rect(c, 7 * S - 2, 63 * S - 2, 2 * S + 4, 4, K(0xFF8A94AC))
        rect(c, 7 * S, 64 * S + 1, 2 * S, 12, K(0xFF1A2A4A)); for (k in 0..6) rect(c, 7 * S + 2 + k * 4, 64 * S + 3 + (k % 3), 2, 1, K(0xFF4C7DFF))
        // pools of light: chandeliers in the lobby, ceiling lamps in the hall, lamps in the rooms, moonlight in the mayor's office
        for (row in intArrayOf(38, 50, 58)) c.drawBitmap(glowBitmap(64, PX.WARM, 0x20), 8f * S - 64f, row * S.toFloat() - 64f, plain)
        for (row in intArrayOf(14, 20, 26, 30)) c.drawBitmap(glowBitmap(34, PX.ICE, 0x1C), 8f * S - 34f, row * S.toFloat() - 34f, plain)
        for (rr in intArrayOf(14, 21, 28)) { c.drawBitmap(glowBitmap(28, PX.WARM, 0x20), 2.5f * S - 28f, (rr + 0.5f) * S - 28f, plain); c.drawBitmap(glowBitmap(28, PX.WARM, 0x20), 12.5f * S - 28f, (rr + 0.5f) * S - 28f, plain) }
        for (cx in intArrayOf(3, 7, 12)) c.drawBitmap(glowBitmap(48, K(0xFF9AB8FF), 0x22), (cx + 0.5f) * S - 48f, 3.2f * S - 48f, plain)
        // the window in the north wall of the mayor's office, half blocked by furniture
        for (k in 0..3) { val wx = (2 + k * 3) * S + 2; rect(c, wx, 2, 8, 9, PX.K); rect(c, wx + 1, 3, 6, 7, K(0xFF1B2B66)); rect(c, wx + 1, 3, 6, 2, K(0xFF4C7DFF)); rect(c, wx + 3, 3, 1, 7, PX.K) }
        // cubicle name plates and a coffee machine
        for (r in intArrayOf(36, 41, 46, 51)) rect(c, 12 * S + 1, r * S + 1, 5, 2, PX.GOLD)
        rect(c, 1 * S + 3, 39 * S + 2, 10, 10, PX.K); rect(c, 1 * S + 4, 39 * S + 3, 8, 8, K(0xFF8A94AC)); rect(c, 1 * S + 6, 39 * S + 4, 4, 3, K(0xFF29F0FF)); rect(c, 1 * S + 6, 39 * S + 8, 4, 2, K(0xFF6A3A1E))
    }

    // =====================================================================================================================
    //  each frame
    // =====================================================================================================================
    private fun doorArt(c: Canvas, col: Int, row: Int) {
        // a pair of double doors 32 px wide (the left door's tile gives the position)
        val x = col * S; val y = row * S
        rect(c, x - 1, y - 2, 34, 20, PX.K); rect(c, x, y - 1, 32, 18, K(0xFF2A1810))
        rect(c, x + 1, y, 15, 16, K(0xFF6A1020)); rect(c, x + 16, y, 15, 16, K(0xFF6A1020)); rect(c, x + 1, y, 30, 2, K(0xFFB02038))
        rect(c, x + 3, y + 3, 11, 11, K(0xFF8A1830)); rect(c, x + 18, y + 3, 11, 11, K(0xFF8A1830)); rect(c, x + 4, y + 4, 9, 9, K(0xFF5A0C1A)); rect(c, x + 19, y + 4, 9, 9, K(0xFF5A0C1A))
        rect(c, x + 15, y, 2, 16, PX.K); rect(c, x + 12, y + 7, 2, 3, PX.GOLD); rect(c, x + 18, y + 7, 2, 3, PX.GOLD)
    }

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        val fin = world.phase == ShootWorld.Phase.FINALE || world.phase == ShootWorld.Phase.DONE
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val targetY = if (fin) 11.5f else world.py
        camY += (targetY - camY) * (if (fin) 0.06f else 0.2f)
        val cyT = (camY - VH / 2f).coerceIn(0f, (world.rows - VH).toFloat())
        val oy = -(cyT * S).roundToInt()
        val shake = if (world.shake > 0f) ((clock * 60f).toInt() % 3 - 1) * 2 else 0
        c.save(); c.translate(shake.toFloat(), (oy + shake).toFloat())
        c.drawBitmap(bg, 0f, 0f, plain)
        val r0 = (cyT.toInt() - 1).coerceAtLeast(0); val r1 = (cyT.toInt() + VH + 2).coerceAtMost(world.rows - 1)

        // doors, tables thrown on their side, scorch marks and blood
        for (d in world.spec.hallDoors + world.spec.mayorDoors) if (d.first % 2 == 1 && world.doorClosed(d.first, d.second)) doorArt(c, d.first, d.second)
        for ((tc, tr) in world.tipped) tippedTable(c, tc * S, tr * S)
        for ((bx, by) in world.scorch) { val x = (bx * S).roundToInt(); val y = (by * S).roundToInt(); rect(c, x - 14, y - 9, 28, 18, a(PX.K, 0x70)); rect(c, x - 9, y - 12, 18, 24, a(PX.K, 0x50)) }
        for ((bx, by) in world.decals) { val x = (bx * S).roundToInt(); val y = (by * S).roundToInt(); rect(c, x - 5, y - 2, 11, 6, K(0xFF7A0F1E)); rect(c, x - 2, y - 4, 6, 3, K(0xFF7A0F1E)); rect(c, x + 5, y + 1, 3, 2, K(0xFF7A0F1E)) }

        // y-sorted people
        val items = ArrayList<DItem>()
        for (n in world.npcs) {
            if (n.gone) continue
            if (!fin && world.phase != ShootWorld.Phase.INTRO && !world.canSeeNpc(n) && !n.hostage) continue
            if (n.hostage) {
                if (n.dead) items += DItem(n.y - 0.3f) { lying(c, (n.x * S).roundToInt(), (n.y * S).roundToInt(), police, true, false) }
                else items += DItem(n.y) { person(c, n, clock) }
                continue
            }
            if (n.dead) { items += DItem(n.y - 0.3f) { lying(c, (n.x * S).roundToInt(), (n.y * S).roundToInt(), outfitOf(n), n.id % 2 == 0, false) }; continue }
            if (fin && !n.civ) continue
            items += DItem(n.y) { person(c, n, clock) }
        }
        addPlayer(items, c, clock)
        if (fin) addFinale(items, c, clock)
        items.sortBy { it.y }
        for (it in items) it.draw()

        // grenades, bullets, blasts and sparks
        // aiming a grenade: a dotted lob from you to where it lands, and the blast ring there
        world.aimAt?.let { (ax, ay) ->
            val x0 = world.px * S; val y0 = world.py * S; val x1 = ax * S; val y1 = ay * S; val d = hypot(x1 - x0, y1 - y0)
            val n = (d / 6f).toInt().coerceAtLeast(2)
            for (i in 1 until n) { val k = i / n.toFloat(); val hx = x0 + (x1 - x0) * k; val hy = y0 + (y1 - y0) * k - sin(k * PI.toFloat()) * d * 0.25f; if ((i + (clock * 8f).toInt()) % 2 == 0) rect(c, hx.roundToInt() - 1, hy.roundToInt() - 1, 2, 2, a(PX.W, 0xC0)) }
            val rr = ShootWorld.BLAST * S
            for (i in 0 until 40) { val an = i * PI.toFloat() * 2f / 40f; px(c, (x1 + cos(an) * rr).roundToInt(), (y1 + sin(an) * rr * 0.8f).roundToInt(), a(PX.RED, 0xC0)) }
            rect(c, x1.roundToInt() - 2, y1.roundToInt(), 5, 1, PX.RED); rect(c, x1.roundToInt(), y1.roundToInt() - 2, 1, 5, PX.RED)
        }
        // you, pressed to the wall: out of sight
        if (world.hidden) { val x = (world.px * S).roundToInt(); val y = (world.py * S).roundToInt() - 22; rect(c, x - 9, y, 19, 7, a(PX.K, 0xA0)); for (k in 0 until 3) rect(c, x - 6 + k * 5, y + 3, 3, 1, a(PX.W, 0xC0)) }
        for (g in world.grenadesAir) { val (gx, gy, h) = world.grenadePos(g); val x = (gx * S).roundToInt(); val y = (gy * S).roundToInt(); rect(c, x - 2, y, 5, 2, a(PX.K, 0x90)); val up = (h * 22f).roundToInt(); rect(c, x - 2, y - up - 4, 5, 5, PX.K); rect(c, x - 1, y - up - 3, 3, 3, K(0xFF3E7A4A)); px(c, x, y - up - 5, PX.RED) }
        for (b in world.bullets) {
            val x = (b.x * S).roundToInt(); val y = (b.y * S).roundToInt()
            rect(c, x - 2, y - 2, 4, 4, if (b.mine) PX.GOLD else PX.RED); rect(c, x - 1, y - 1, 2, 2, PX.W)
        }
        for (s in world.sparks) { val x = (s.x * S).roundToInt(); val y = (s.y * S).roundToInt(); val k = s.life / 0.25f; rect(c, x - 3, y - 3, 6, 6, a(PX.WARM, (k * 0xC0).toInt())); rect(c, x - 1, y - 1, 2, 2, PX.W) }
        for (b in world.blasts) {
            val x = (b.x * S).roundToInt(); val y = (b.y * S).roundToInt(); val k = 1f - b.life / 0.55f
            val rad = (ShootWorld.BLAST * S * (0.35f + 0.65f * k)).roundToInt()
            for (a0 in 0 until 24) { val an = a0 * PI.toFloat() / 12f; val rr = rad * (0.75f + 0.25f * ((a0 * 7) % 3) / 2f); rect(c, x + (cos(an) * rr).roundToInt() - 3, y + (sin(an) * rr * 0.8f).roundToInt() - 3, 6, 6, a(K(0xFFFF8A2A), ((1f - k) * 0xE0).toInt())) }
            rect(c, x - rad / 2, y - rad / 3, rad, rad * 2 / 3, a(PX.WARM, ((1f - k) * 0xA0).toInt())); rect(c, x - rad / 4, y - rad / 6, rad / 2, rad / 3, a(PX.W, ((1f - k) * 0xD0).toInt()))
        }

        // fog of war: only what you can see (from where you stand) shows; the rest is dark
        if (!fin && world.phase != ShootWorld.Phase.INTRO) {
            for (r in r0..r1) for (col in 0 until world.cols) if (!world.visibleTile(col, r)) rect(c, col * S, r * S, S, S, a(K(0xFF03040A), 0xC8))
        }
        c.restore()
        if (world.flash > 0f) { p.color = a(PX.W, (world.flash * 600f).toInt().coerceAtMost(0xC0)); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }

    private fun tippedTable(c: Canvas, x: Int, y: Int) {
        rect(c, x, y, S, S, K(0xFF2A2E38)); rect(c, x + 1, y + 14, 14, 2, a(PX.K, 0xA0))
        rect(c, x, y + 1, 16, 14, PX.K); rect(c, x + 1, y + 2, 14, 11, K(0xFF6A4528)); rect(c, x + 1, y + 2, 14, 2, K(0xFF9A6C3E)); rect(c, x + 1, y + 12, 14, 2, K(0xFF3E2616))
        rect(c, x + 2, y - 3, 2, 5, K(0xFF2A1C14)); rect(c, x + 12, y - 3, 2, 5, K(0xFF2A1C14))                           // the legs, in the air
    }

    private fun outfitOf(n: ShootWorld.Npc): PixelSprites.Outfit = when {
        n.hostage -> police
        n.civ -> civs[n.id % civs.size]
        n.swat -> swatOutfit
        else -> police
    }
    private val civs = listOf(
        PixelSprites.Outfit(PX.SKIN, 0xFF3A2A1E.toInt(), null, null, 0xFFE8E0C8.toInt(), null, 0xFF3A4058.toInt(), PX.K, null, glasses = true, tie = true),
        PixelSprites.Outfit(0xFFC89A78.toInt(), 0xFF1A1420.toInt(), null, null, 0xFFB0C8E8.toInt(), null, 0xFF2A3350.toInt(), PX.K, null),
        PixelSprites.Outfit(0xFF8A6048.toInt(), 0xFF0E0A0A.toInt(), null, null, 0xFFE8B0B8.toInt(), null, 0xFF4A3A58.toInt(), PX.K, null, glasses = true),
        PixelSprites.Outfit(PX.SKIN, 0xFF8A8A92.toInt(), null, null, 0xFFC8E0C8.toInt(), null, 0xFF3A3A44.toInt(), PX.K, null, tie = true),
    )
    private val mayorOutfit = PixelSprites.Outfit(PX.SKIN, 0xFFB8B8C0.toInt(), null, null, PX.W, 0xFF3A3A4E.toInt(), 0xFF22222E.toInt(), PX.K, null, tie = true)

    private fun pistol(c: Canvas, fx: Int, fy: Int, face: Float, fire: Boolean) {
        val gx = fx + (cos(face) * 7f).roundToInt(); val gy = fy + (sin(face) * 7f).roundToInt()
        rect(c, gx - 2, gy - 1, 4, 3, PX.K); rect(c, gx - 1, gy, 2, 1, 0xFF9AA4B5.toInt())
        if (fire) muzzleFlash(c, gx, gy, face)
    }
    private fun muzzleFlash(c: Canvas, gx: Int, gy: Int, face: Float) {
        val fx2 = gx + (cos(face) * 5f).roundToInt(); val fy2 = gy + (sin(face) * 5f).roundToInt()
        rect(c, fx2 - 2, fy2 - 2, 5, 5, PX.WARM); rect(c, fx2 - 1, fy2 - 1, 3, 3, PX.W)
    }

    private fun person(c: Canvas, n: ShootWorld.Npc, clock: Float) {
        val fx = (n.x * S).roundToInt(); val fy = (n.y * S).roundToInt() + 6
        val moving = (n.civ && n.state == ShootWorld.EVAC) || (!n.civ && n.path.isNotEmpty() && n.state != ShootWorld.AIMED && !n.crouch) || n.state == ShootWorld.SEARCH
        val dir = dirOf(n.face)
        rect(c, fx - 5, fy - 1, 11, 3, a(PX.K, 0x70))
        val fr = if (moving) 1 + ((clock * 8f).toInt() % 2) else 0
        if (n.hostage) {
            folk(c, fx, fy, 0, fr, police, false, false, handsUp = true)
            world.hostageLine()?.let { (line, shown) -> val jit = if ((clock * 24f).toInt() % 3 == 0) 1 else 0; bubble(c, line, fx + jit, fy - 30, shown, W) }
            return
        }
        if (n.civ) {
            world.civLine(n)?.let { bubble(c, it, fx, fy - 26, 99, W) }
            if (n.state == ShootWorld.COWER) { folk(c, fx, fy, if (dir == 0) 2 else dir, 0, outfitOf(n), false, false, 6); rect(c, fx - 6, fy - 17, 2, 4, outfitOf(n).skin); rect(c, fx + 5, fy - 17, 2, 4, outfitOf(n).skin) }
            else folk(c, fx, fy, dir, fr, outfitOf(n), false, false)
            return
        }
        if (n.crouch) { folk(c, fx, fy + 2, if (dir == 0) 2 else dir, 0, outfitOf(n), false, false, 5); return }
        if (n.swat) {
            swatman(c, fx, fy, dir, fr)
            val aim = n.state == ShootWorld.AIMED || n.state == ShootWorld.ENGAGE || n.state == ShootWorld.ALERT
            val ang = n.face
            rifle(c, fx + (if (dir == 3) -3 else 3), fy - 10, if (aim) ang else (PI.toFloat() / 2f), false, 0 to 0)
            if (n.flash > 0f) muzzleFlash(c, fx + (cos(ang) * 16f).roundToInt(), fy - 10 + (sin(ang) * 16f).roundToInt(), ang)
        } else {
            folk(c, fx, fy, dir, fr, police, false, false)
            pistol(c, fx, fy - 8, n.face, n.flash > 0f)
        }
        if (n.state == ShootWorld.ALERT) { rect(c, fx - 1, fy - 28, 3, 6, PX.RED); rect(c, fx - 1, fy - 20, 3, 2, PX.RED) }
    }

    private fun addPlayer(items: ArrayList<DItem>, c: Canvas, clock: Float) {
        val w = world
        if (w.phase == ShootWorld.Phase.FINALE || w.phase == ShootWorld.Phase.DONE) return
        val fx = (w.px * S).roundToInt(); val fy = (w.py * S).roundToInt() + 6
        val draw: () -> Unit = {
            rect(c, fx - 5, fy - 1, 11, 3, a(PX.K, 0x70))
            if (w.phase == ShootWorld.Phase.DEAD) lying(c, fx, fy, clerk, true, false)
            else if (w.diving) folk(c, fx, fy + 2, dirOf(w.face), 0, clerk, false, false, 4)                                    // crouched behind the table
            else if (w.kickingTable) { folk(c, fx, fy, dirOf(w.face), 0, clerk, false, false); val kx = fx + (cos(w.face) * 7f).roundToInt(); val ky = fy - 4 + (sin(w.face) * 5f).roundToInt(); rect(c, kx - 2, ky - 1, 5, 3, clerk.pants); rect(c, kx - 1, ky, 3, 2, clerk.shoes) }   // the kick
            else if (w.rollT > 0.04f) {
                c.save(); c.rotate((clock * 1100f) % 360f, fx.toFloat(), (fy - 6).toFloat()); folk(c, fx, fy, 2, 1, clerk, false, false, 5); c.restore()
            } else {
                folk(c, fx, fy, dirOf(w.face), if (w.moving) 1 + ((clock * 9f).toInt() % 2) else 0, clerk, false, true)
                pistol(c, fx, fy - 8, w.face, w.muzzle > 0f)
            }
        }
        items.add(DItem(w.py, draw))
    }
    private class DItem(val y: Float, val draw: () -> Unit)

    private fun addFinale(items: ArrayList<DItem>, c: Canvas, clock: Float) {
        val w = world
        val list = ArrayList<DItem>()
        // you: kicking the door, then standing, then pinned down
        val px0 = (w.px * S).roundToInt(); val py0 = (w.py * S).roundToInt() + 6
        list += DItem(w.py) {
            rect(c, px0 - 5, py0 - 1, 11, 3, a(PX.K, 0x70))
            if (w.playerDown) {
                // your copies of the letters burst out of your coat and slide across the floor as they pile on you
                val k = ((w.ft - 9.8f) / 0.7f).coerceIn(0f, 1f); val e = 1f - (1f - k) * (1f - k)
                for (i in 0 until 14) {
                    val ang = i * 2.4f + 0.3f; val dist = (10 + (i * 37) % 26) * e
                    val lx = px0 + (cos(ang) * dist * 1.3f).roundToInt(); val ly = py0 - 3 + (sin(ang) * dist * 0.7f).roundToInt()
                    val wide = i % 3 != 0
                    rect(c, lx - 1, ly - 1, if (wide) 7 else 5, if (wide) 5 else 7, a(PX.K, 0x90))
                    rect(c, lx, ly, if (wide) 5 else 3, if (wide) 3 else 5, if (i % 4 == 0) K(0xFFD8C8A0) else K(0xFFF0ECE0))
                    if (i % 2 == 0) px(c, lx + 1, ly + 1, PX.RED)                                     // the wax seal
                }
                lying(c, px0, py0, clerk, true, false)
            }
            else {
                folk(c, px0, py0, 0, if (w.moving) 1 + ((clock * 8f).toInt() % 2) else 0, clerk, false, false)
                if (w.kicking) { rect(c, px0 - 2, py0 - 13, 5, 3, PX.W); rect(c, px0 - 2, py0 - 12, 5, 1, K(0xFFD8DCE8)) }
            }
        }
        // the twenty officers, in uniform and in SWAT gear
        for (i in 0 until w.runnerCount) {
            val rp = w.runnerPos(i) ?: continue
            list += DItem(rp.second) {
                val x = (rp.first * S).roundToInt(); val y = (rp.second * S).roundToInt() + 6
                val running = w.ft < 10.2f
                val ang = atan2(w.py - rp.second, w.px - rp.first)
                val dir = if (running && rp.second > 11f) 0 else dirOf(ang)
                rect(c, x - 5, y - 1, 11, 3, a(PX.K, 0x70))
                if (i % 2 == 0) { swatman(c, x, y, dir, if (running) 1 + ((clock * 10f).toInt() % 2) else 0); if (!running) rifle(c, x + (if (dir == 3) -3 else 3), y - 10, ang, false, 0 to 0) }
                else { folk(c, x, y, dir, if (running) 1 + ((clock * 10f).toInt() % 2) else 0, police, false, false); pistol(c, x, y - 8, ang, false) }
            }
        }
        // the paper he'll pick up, lying on the floor until he does
        if (w.playerDown && w.ft < ShootWorld.PAPER_UP) { val (qx, qy) = w.paperSpot; val ppx = (qx * S).roundToInt(); val ppy = (qy * S).roundToInt(); list += DItem(qy - 0.3f) { rect(c, ppx - 4, ppy - 3, 9, 6, a(PX.K, 0x90)); rect(c, ppx - 3, ppy - 2, 7, 4, K(0xFFF0ECE0)); px(c, ppx, ppy - 1, PX.RED) } }
        // the mayor
        val mx = (w.mayorX * S).roundToInt(); val my = (w.mayorY * S).roundToInt() + 6
        list += DItem(w.mayorY) {
            rect(c, mx - 5, my - 1, 11, 3, a(PX.K, 0x70))
            val pacing = w.ft < 5.0f
            val toward = atan2(w.py - w.mayorY, w.px - w.mayorX)
            val dir = if (pacing) (if (cos(w.ft * 1.25f + 0.8f) > 0) 1 else 3) else dirOf(toward)
            folk(c, mx, my, dir, if (pacing || w.mayorWalking) 1 + ((clock * (if (pacing) 9f else 3.5f)).toInt() % 2) else 0, mayorOutfit, false, false, if (w.mayorBending) 3 else 0)
            if (w.mayorBending) { rect(c, mx + 3, my - 6, 2, 5, mayorOutfit.coat ?: mayorOutfit.shirt); rect(c, mx + 3, my - 2, 2, 1, mayorOutfit.skin) }                       // reaching down
            if (w.mayorReading) { rect(c, mx - 4, my - 16, 9, 6, K(0xFFF0ECE0)); rect(c, mx - 3, my - 15, 7, 1, K(0xFF8A8678)); rect(c, mx - 3, my - 13, 5, 1, K(0xFF8A8678)); px(c, mx + 3, my - 12, PX.RED) }   // your paper, held up to read
            if (w.mayorKicking) { rect(c, mx - 2, my - 8, 6, 3, PX.K); rect(c, mx - 2, my - 9, 6, 1, K(0xFF6A6A7A)) }
            if (w.ft < ShootWorld.PAPER_AT) {
                // his gun, waving about
                val ga = w.mayorGun
                rect(c, mx + (cos(ga) * 6f).roundToInt() - 2, my - 9 + (sin(ga) * 6f).roundToInt() - 1, 5, 3, PX.K)
                rect(c, mx + (cos(ga) * 8f).roundToInt(), my - 9 + (sin(ga) * 8f).roundToInt(), 2, 2, K(0xFF9AA4B5))
            }
            w.mayorLine()?.let { line ->
                val jitter = if (w.mayorLaughing) ((clock * 20f).toInt() % 3 - 1) else 0
                bubble(c, line, mx + jitter, my - 28, 999, W)
            }
        }
        items.addAll(list)
    }
}
