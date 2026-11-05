package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// An easter egg (tap the version in Settings 7 times): sneak a sealed message across a room to the drop box.
// Hold your finger down to hide, let go to run, swipe up/down to change lane. It's all offline and stores only a best score.
//
// Dangers: searchlights and cameras can't see you while you hide; drones hear you move but not when you're still;
// laser tripwires blink, so time them; guards patrol across the lanes and hiding does nothing against them.
// The message has a self-destruct fuse (like disappearing messages): run out of time and it's gone.

internal fun MainActivity.showDeadDrop() {
    screen = Screen.DEAD_DROP
    val prefs = getSharedPreferences("dead_drop", Context.MODE_PRIVATE)
    val game = DeadDropView(this, prefs.getInt("best", 0)) { prefs.edit().putInt("best", it).apply() }
    setContentView(page(topBar("Dead Drop", "Hold to hide · let go to run · swipe to change lane", left = icon(R.drawable.ic_back) { showSettings() }), game))
}

internal class DeadDropView(ctx: Context, private var best: Int, private val saveBest: (Int) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private class Wander(val cx: Float, val cy: Float, val ax: Float, val ay: Float, val w1: Float, val w2: Float, val ph: Float, val size: Float)
    private class Cam(val x: Float, val y: Float, val base: Float, val amp: Float, val w: Float, val ph: Float, val half: Float, val range: Float)
    private class Laser(val x: Float, val period: Float, val on: Float, val ph: Float)
    private class Guard(val x: Float, val speed: Float, val ph: Float)

    private var lights = listOf<Wander>()
    private var drones = listOf<Wander>()
    private var cams = listOf<Cam>()
    private var lasers = listOf<Laser>()
    private var guards = listOf<Guard>()

    private var level = 0
    private var score = 0
    private var t = 0f            // seconds in this level
    private var fuse = 0f
    private var fuseMax = 0f
    private var px = 0f
    private var lane = 1
    private var hidden = false
    private var moving = false
    private var dead: String? = null
    private var deadAt = 0L
    private var bannerUntil = 0L
    private var downY = 0f
    private var last = 0L
    private var running = false

    private fun laneY(i: Int) = height * (0.22f + 0.28f * i)
    private val margin get() = 28f * d
    private val goalX get() = width - margin
    private val speed get() = width / 9f * (1f + level * 0.03f).coerceAtMost(1.4f)

    private fun rnd(a: Float, b: Float) = a + (b - a) * Math.random().toFloat()

    private fun newLevel() {
        val w = width.toFloat(); val h = height.toFloat()
        fun x() = rnd(0.2f, 0.85f) * w
        fun y() = rnd(0.12f, 0.88f) * h
        lights = List(min(1 + level / 2, 4)) { Wander(x(), y(), rnd(20f, 70f) * d, rnd(30f, 90f) * d, rnd(0.6f, 1.4f), rnd(0.5f, 1.2f), rnd(0f, 6f), rnd(48f, 70f) * d) }
        drones = if (level >= 1) List(min(level, 3)) { Wander(x(), y(), rnd(30f, 80f) * d, rnd(30f, 90f) * d, rnd(0.8f, 1.6f), rnd(0.7f, 1.5f), rnd(0f, 6f), rnd(60f, 80f) * d) } else emptyList()
        cams = if (level >= 2) List(min(level - 1, 3)) { Cam(x(), if (Math.random() < 0.5) 0f else h, if (it % 2 == 0) 1.57f else -1.57f, 0.7f, rnd(0.6f, 1.2f), rnd(0f, 6f), 0.32f, h * 0.8f) } else emptyList()
        lasers = if (level >= 3) List(min(level - 2, 4)) { Laser(rnd(0.25f, 0.8f) * w, rnd(1.6f, 2.8f), rnd(0.9f, 1.5f), rnd(0f, 3f)) } else emptyList()
        guards = if (level >= 4) List(min(level - 3, 3)) { Guard(rnd(0.3f, 0.8f) * w, rnd(0.5f, 1.2f), rnd(0f, 6f)) } else emptyList()
        px = margin; lane = 1; t = 0f; hidden = false
        fuseMax = max(26f - level * 1.2f, 14f); fuse = fuseMax
    }

    private fun lightPos(l: Wander, tt: Float) = (l.cx + l.ax * sin(l.w1 * tt + l.ph)) to (l.cy + l.ay * sin(l.w2 * tt + l.ph * 1.7f))
    private fun laserOn(l: Laser, tt: Float) = ((tt + l.ph) % l.period) < l.on
    private fun guardY(g: Guard, tt: Float): Float { val s = (sin(g.speed * tt * 1.8f + g.ph) + 1f) / 2f; return height * (0.12f + 0.76f * s) }
    private fun camAngle(c: Cam, tt: Float) = c.base + c.amp * sin(c.w * tt + c.ph)

    private fun caught(): String? {
        val py = laneY(lane)
        if (!hidden) {
            for (l in lights) { val (x, y) = lightPos(l, t); if (hypot(px - x, py - y) < l.size) return "A searchlight found you." }
            for (c in cams) {
                val a = camAngle(c, t); val dx = px - c.x; val dy = py - c.y
                var diff = atan2(dy, dx) - a
                while (diff > Math.PI) diff -= (2 * Math.PI).toFloat(); while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
                if (abs(diff) < c.half && hypot(dx, dy) < c.range) return "A camera saw you."
            }
        }
        if (moving) for (dr in drones) { val (x, y) = lightPos(dr, t); if (hypot(px - x, py - y) < dr.size) return "A drone heard you." }
        for (l in lasers) if (laserOn(l, t) && abs(px - l.x) < 5f * d) return "You tripped a laser."
        for (g in guards) if (abs(px - g.x) < 14f * d && abs(py - guardY(g, t)) < 22f * d) return "A guard caught you."
        return null
    }

    private fun step(dt: Float) {
        if (dead != null || System.currentTimeMillis() < bannerUntil) return
        t += dt
        fuse -= dt
        moving = !hidden
        if (moving) px += speed * dt
        caught()?.let { return die(it) }
        if (fuse <= 0f) return die("The message self-destructed.")
        if (px >= goalX) {
            score++; level++
            if (score > best) { best = score; saveBest(best) }
            bannerUntil = System.currentTimeMillis() + 900
            newLevel()
        }
    }

    private fun die(why: String) { dead = why; deadAt = System.currentTimeMillis(); hidden = false }

    private fun restart() { dead = null; level = 0; score = 0; newLevel() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (w > 0 && h > 0) newLevel() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0; postOnAnimation(tick) }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (last != 0L && width > 0) step(((now - last) / 1e9f).coerceAtMost(0.05f))
            last = now
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dead != null) { if (System.currentTimeMillis() - deadAt > 600) restart(); return true }
                hidden = true; downY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dead == null && hidden) {
                    val dy = e.y - downY
                    if (abs(dy) > 40f * d) lane = (lane + if (dy < 0) -1 else 1).coerceIn(0, 2)
                }
                hidden = false
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF0E1216.toInt())
        // lanes
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0xFF1B242C.toInt()
        for (i in 0..2) c.drawLine(0f, laneY(i), w, laneY(i), p)
        // drop box
        p.style = Paint.Style.FILL; p.color = 0xFF2E7D5B.toInt()
        c.drawRoundRect(RectF(goalX - 10 * d, laneY(0) - 14 * d, w - 6 * d, laneY(2) + 14 * d), 6 * d, 6 * d, p)
        // lasers
        for (l in lasers) {
            p.style = Paint.Style.FILL
            p.color = if (laserOn(l, t)) 0xFFFF3B3B.toInt() else 0x33FF3B3B
            c.drawRect(l.x - (if (laserOn(l, t)) 2 else 1) * d, 0f, l.x + (if (laserOn(l, t)) 2 else 1) * d, h, p)
        }
        // cameras
        for (cm in cams) {
            val a = camAngle(cm, t)
            path.reset(); path.moveTo(cm.x, cm.y)
            path.lineTo(cm.x + cm.range * Math.cos((a - cm.half).toDouble()).toFloat(), cm.y + cm.range * Math.sin((a - cm.half).toDouble()).toFloat())
            path.lineTo(cm.x + cm.range * Math.cos((a + cm.half).toDouble()).toFloat(), cm.y + cm.range * Math.sin((a + cm.half).toDouble()).toFloat())
            path.close()
            p.style = Paint.Style.FILL; p.color = 0x33FFB347; c.drawPath(path, p)
            p.color = 0xFFFFB347.toInt(); c.drawCircle(cm.x, cm.y, 5 * d, p)
        }
        // searchlights
        for (l in lights) {
            val (x, y) = lightPos(l, t)
            p.shader = RadialGradient(x, y, l.size, intArrayOf(0x66FFF3B0, 0x22FFF3B0, 0x00FFF3B0), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL; c.drawCircle(x, y, l.size, p); p.shader = null
        }
        // drones (they hear you move)
        for (dr in drones) {
            val (x, y) = lightPos(dr, t)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0x4478B7FF; c.drawCircle(x, y, dr.size, p)
            p.style = Paint.Style.FILL; p.color = 0xFF78B7FF.toInt(); c.drawCircle(x, y, 6 * d, p)
        }
        // guards
        for (g in guards) {
            val y = guardY(g, t)
            p.style = Paint.Style.FILL; p.color = 0xFFD9534F.toInt()
            c.drawCircle(g.x, y - 12 * d, 6 * d, p); c.drawRoundRect(RectF(g.x - 8 * d, y - 6 * d, g.x + 8 * d, y + 14 * d), 4 * d, 4 * d, p)
        }
        // the courier: an envelope (dim while hidden)
        val py = laneY(lane)
        p.style = Paint.Style.FILL; p.color = if (hidden) 0x66FFFFFF else Color.WHITE
        c.drawRoundRect(RectF(px - 11 * d, py - 8 * d, px + 11 * d, py + 8 * d), 3 * d, 3 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0xFF0E1216.toInt()
        c.drawLine(px - 11 * d, py - 8 * d, px, py + 1 * d, p); c.drawLine(px + 11 * d, py - 8 * d, px, py + 1 * d, p)
        // fuse bar and score
        p.style = Paint.Style.FILL; p.color = 0xFF1B242C.toInt(); c.drawRect(0f, 0f, w, 4 * d, p)
        p.color = if (fuse < 5f) 0xFFFF3B3B.toInt() else 0xFFFFB347.toInt(); c.drawRect(0f, 0f, w * (fuse / fuseMax).coerceIn(0f, 1f), 4 * d, p)
        p.color = 0xFFAAB4BE.toInt(); p.textSize = 14f * d; p.textAlign = Paint.Align.LEFT
        c.drawText("Delivered $score   ·   Best $best", 12 * d, 28 * d, p)
        p.textAlign = Paint.Align.CENTER
        if (System.currentTimeMillis() < bannerUntil) { p.color = Color.WHITE; p.textSize = 22f * d; c.drawText("Delivered!", w / 2, h / 2, p) }
        dead?.let {
            p.color = 0xCC0E1216.toInt(); c.drawRect(0f, 0f, w, h, p)
            p.color = Color.WHITE; p.textSize = 22f * d; c.drawText(it, w / 2, h / 2 - 12 * d, p)
            p.color = 0xFFAAB4BE.toInt(); p.textSize = 15f * d; c.drawText("You delivered $score. Tap to try again.", w / 2, h / 2 + 18 * d, p)
        }
    }
}
