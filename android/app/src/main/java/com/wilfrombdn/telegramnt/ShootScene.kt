package com.wilfrombdn.telegramnt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The town hall in pixel art: marble floor, pillars, desks, locked doors, people and bullets, with the camera following you. */
internal class ShootScene(private val world: ShootWorld) : PixelSprites() {
    private val S = 16
    val VW = 16
    val VH = 24
    val W = VW * S
    val H = VH * S
    private val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val fc = Canvas(frame)

    private val marbleA = 0xFF1B2440.toInt(); private val marbleB = 0xFF222C4E.toInt()
    private val wallC = 0xFF3A2A33.toInt(); private val wallL = 0xFF6A4A52.toInt()
    private val wood = 0xFF6A4524.toInt(); private val woodL = 0xFF8E6234.toInt()
    private val stone = 0xFF8A94AC.toInt(); private val stoneD = 0xFF4A5470.toInt()
    private val carpet = 0xFF5A1624.toInt(); private val carpetL = 0xFF7A2236.toInt()
    private val blood = 0xFF7A0F1E.toInt()

    private var camY = world.py

    private fun inOffice(r: Int) = r in 1..9
    private fun inHall(r: Int) = r in 11..17

    fun render(clock: Float): Bitmap {
        animT = clock
        val c = fc
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val targetY = if (world.phase == ShootWorld.Phase.FINALE || world.phase == ShootWorld.Phase.DONE) 5.5f else world.py
        camY += (targetY - camY) * 0.2f
        val cy = (camY - VH / 2f).coerceIn(0f, (world.rows - VH).toFloat())
        val oy = -(cy * S).roundToInt()
        val shake = if (world.shake > 0f) ((clock * 60f).toInt() % 3 - 1) * 2 else 0
        c.save(); c.translate(shake.toFloat(), (oy + shake).toFloat())
        rect(c, 0, 0, W, world.rows * S, PX.K)

        // floor and walls
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val t = world.tile(col, r); val x = col * S; val y = r * S
            if (t == '#') {
                rect(c, x, y, S, S, wallC)
                if (world.tile(col, r + 1) != '#') { rect(c, x, y + S - 5, S, 5, wallL); rect(c, x, y + S - 1, S, 1, PX.K) }
                continue
            }
            val base = when {
                inOffice(r) -> if ((col + r) % 2 == 0) carpet else carpetL
                inHall(r) -> if ((col + r) % 2 == 0) 0xFF2A2230.toInt() else 0xFF30283A.toInt()
                else -> if ((col + r) % 2 == 0) marbleA else marbleB
            }
            rect(c, x, y, S, S, base)
            when (t) {
                'c' -> {
                    rect(c, x + 1, y + 13, 14, 3, PX.K); rect(c, x + 2, y + 2, 12, 12, stoneD); rect(c, x + 3, y + 1, 10, 13, stone)
                    rect(c, x + 3, y + 1, 3, 13, 0xFFC6CFE4.toInt()); rect(c, x + 12, y + 2, 2, 12, stoneD); rect(c, x + 2, y, 12, 2, 0xFFC6CFE4.toInt())
                }
                'd' -> {
                    rect(c, x, y + 4, S, 11, PX.K); rect(c, x + 1, y + 3, 14, 10, wood); rect(c, x + 1, y + 3, 14, 2, woodL)
                    if (hash(col, r, 2) % 3 == 0) rect(c, x + 4, y + 5, 5, 3, PX.W)
                }
                'D' -> if (world.doorClosed(col, r)) {
                    rect(c, x, y, S, S, PX.K); rect(c, x + 1, y + 1, 14, 14, 0xFF6A1020.toInt()); rect(c, x + 1, y + 1, 14, 2, 0xFFB02038.toInt())
                    rect(c, x + 6, y + 6, 4, 5, PX.GOLD)
                }
            }
        }
        // the mayor's desk is just wood; flag and rug accents
        if (world.cols > 8) { rect(c, 6 * S, 6 * S, 4 * S, 2 * S, a(PX.GOLD, 0x30)) }

        // decals (blood) first
        for ((bx, by) in world.decals) { val x = (bx * S).roundToInt(); val y = (by * S).roundToInt(); rect(c, x - 5, y - 2, 11, 6, blood); rect(c, x - 2, y - 4, 6, 3, blood); rect(c, x + 5, y + 1, 3, 2, blood) }

        // y-sorted people
        class D(val y: Float, val draw: () -> Unit)
        val items = ArrayList<D>()
        val fin = world.phase == ShootWorld.Phase.FINALE || world.phase == ShootWorld.Phase.DONE
        for (n in world.npcs) {
            if (n.hostage && n.dead) { items += D(n.y - 0.3f) { lying(c, (n.x * S).roundToInt(), (n.y * S).roundToInt(), police, true, false) }; continue }
            if (n.hostage && world.phase != ShootWorld.Phase.INTRO) continue
            if (n.dead) { items += D(n.y - 0.3f) { lying(c, (n.x * S).roundToInt(), (n.y * S).roundToInt(), if (n.civ) vendor else police, n.id % 2 == 0, false) }; continue }
            if (fin && !n.civ) continue
            items += D(n.y) {
                val fx = (n.x * S).roundToInt(); val fy = (n.y * S).roundToInt() + 6
                val moving = n.state == ShootWorld.FLEE || (n.state == ShootWorld.ENGAGE && n.path.isNotEmpty()) || n.state == ShootWorld.SEARCH
                rect(c, fx - 5, fy - 1, 11, 3, a(PX.K, 0x70))
                if (n.crouched) { folk(c, fx, fy, dirOf(n.face), 0, vendor, false, false, 6) }
                else {
                    folk(c, fx, fy, dirOf(n.face), if (moving) 1 + ((clock * 8f).toInt() % 2) else 0, if (n.civ) vendor else police, false, false)
                    if (n.hostage) { rect(c, fx - 7, fy - 18, 2, 4, police.skin); rect(c, fx + 6, fy - 18, 2, 4, police.skin) }
                    if (!n.civ && !n.hostage) gun(c, fx, fy - 8, n.face, n.flash > 0f)
                    if (!n.civ && n.state == ShootWorld.ALERT) { rect(c, fx - 1, fy - 28, 3, 6, PX.RED); rect(c, fx - 1, fy - 20, 3, 2, PX.RED) }
                }
            }
        }
        if (world.phase != ShootWorld.Phase.DEAD && !(fin && world.playerDown)) items += D(world.py) {
            val fx = (world.px * S).roundToInt(); val fy = (world.py * S).roundToInt() + 6
            rect(c, fx - 5, fy - 1, 11, 3, a(PX.K, 0x70))
            folk(c, fx, fy, dirOf(world.face), if (world.moving) 1 + ((clock * 9f).toInt() % 2) else 0, clerk, false, true)
            gun(c, fx, fy - 8, world.face, world.muzzle > 0f)
        }
        if (fin) {
            val fx = (world.px * S).roundToInt(); val fy = (world.py * S).roundToInt() + 6
            if (world.playerDown) items += D(world.py - 0.3f) { lying(c, fx, fy, clerk, true, false); if (world.cuffed) { rect(c, fx - 14, fy - 5, 3, 3, PX.GOLD) } }
            for (i in 0 until world.runnerCount) {
                val rp = world.runnerPos(i) ?: continue
                items += D(rp.second) {
                    val x = (rp.first * S).roundToInt(); val y = (rp.second * S).roundToInt() + 6
                    rect(c, x - 5, y - 1, 11, 3, a(PX.K, 0x70))
                    folk(c, x, y, if (rp.first < world.px) 1 else 3, 1 + ((clock * 9f).toInt() % 2), police, false, false)
                }
            }
            // the mayor
            items += D(world.mayorY) {
                val mx = (world.mayorX * S).roundToInt(); val my = (world.mayorY * S).roundToInt() + 6
                rect(c, mx - 5, my - 1, 11, 3, a(PX.K, 0x70))
                when (world.mayorPose) {
                    1 -> { folk(c, mx, my, 2, 0, clerk, false, false); val k = (clock * 7f).toInt() % 2; rect(c, mx - 4 + k * 2, my - 14, 3, 3, clerk.skin); rect(c, mx + 1 - k * 2, my - 14, 3, 3, clerk.skin); if (k == 0) { px(c, mx - 1, my - 17, PX.W); px(c, mx + 1, my - 17, PX.W) } }
                    2 -> folk(c, mx, my, 2, 1 + ((clock * 7f).toInt() % 2), clerk, false, false)
                    3 -> folk(c, mx, my, 2, 0, clerk, false, false, 6)
                    4 -> { folk(c, mx, my, 2, 0, clerk, false, false); rect(c, mx - 3, my - 6, 7, 3, clerk.pants); rect(c, mx + 3, my - 5, 4, 3, clerk.shoes) }
                    else -> { folk(c, mx, my, 2, 0, clerk, false, true); }
                }
            }
        }
        items.sortBy { it.y }
        for (it in items) it.draw()

        // the spilled letters
        for (i in 0 until 6) {
            val l = world.letterPos(i) ?: continue
            val x = (l.first * S).roundToInt(); val y = (l.second * S).roundToInt()
            rect(c, x - 4, y - 3, 9, 6, PX.K); rect(c, x - 3, y - 2, 7, 4, PX.W); px(c, x, y, PX.RED)
        }

        // bullets
        for (b in world.bullets) {
            val x = (b.x * S).roundToInt(); val y = (b.y * S).roundToInt()
            rect(c, x - 2, y - 2, 4, 4, if (b.mine) PX.GOLD else PX.RED); rect(c, x - 1, y - 1, 2, 2, PX.W)
        }
        c.restore()

        // darkness at the edges, so the room feels lit by the lamps
        if (world.flash > 0f) { p.color = a(PX.W, (world.flash * 600f).toInt().coerceAtMost(0xC0)); c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p) }
        return frame
    }

    private fun gun(c: Canvas, fx: Int, fy: Int, face: Float, fire: Boolean) {
        val gx = fx + (cos(face) * 7f).roundToInt(); val gy = fy + (sin(face) * 7f).roundToInt()
        rect(c, gx - 2, gy - 1, 4, 3, PX.K); rect(c, gx - 1, gy, 2, 1, 0xFF9AA4B5.toInt())
        if (fire) { val fx2 = gx + (cos(face) * 4f).roundToInt(); val fy2 = gy + (sin(face) * 4f).roundToInt(); rect(c, fx2 - 2, fy2 - 2, 5, 5, PX.WARM); rect(c, fx2 - 1, fy2 - 1, 3, 3, PX.W) }
    }
}
