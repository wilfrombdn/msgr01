package com.wilfrombdn.telegramnt

import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The jetpack run of Dead Letter level 3 (no Android code here, so tests can run it). A side-scroller: hold to fly up, let go to
// fall. Collect 100 coins; electric zappers cost you a few of them. When you have 100 the jetpack coughs, gives out, and drops you.

internal class FlySpec(val seed: Int = 7, val coinGoal: Int = 100)

internal class FlyWorld(val spec: FlySpec) {
    enum class Phase { FLY, SPUTTER, DROP, DONE }

    class Coin(val wx: Float, val y: Float) { var taken = false }
    /** An electric zapper: a bar from (wx, y1) to (wx + dx, y2). */
    class Zap(val wx: Float, val y1: Float, val y2: Float, val dx: Float = 0f)

    val w = 14f                       // how much of the world is on screen across
    val h = 20f                       // and down
    val px = 3.6f                     // where I am on screen, across
    var y = 10f; var vy = 0f
    var scroll = 0f                   // how far the world has gone past
    var coins = 0
    var phase = Phase.FLY
    var t = 0f; var phaseT = 0f
    var thrust = false
    var invuln = 0f; var hitFlash = 0f
    var finished = false
    var landed = false
    val coinList = ArrayList<Coin>()
    val zaps = ArrayList<Zap>()
    private var chunks = 0
    private var speed = 6.5f
    val floorY get() = h - 1.7f
    val ceilY = 1.3f

    init { generate(0f + 30f) }

    /** The world position of me (across). */
    val wx get() = scroll + px

    /** Lays out more coins and zappers ahead, a chunk at a time, in the same order every run. */
    private fun generate(upTo: Float) {
        while (chunks * 13f + 13f < upTo) {
            val k = chunks++
            val r = Random(spec.seed * 1000L + k)
            val x0 = 18f + k * 13f
            when (if (k == 0) 0 else r.nextInt(6)) {
                0 -> { val yy = 5f + r.nextInt(10); for (i in 0 until 10) coinList += Coin(x0 + 1f + i * 0.95f, yy) }
                1 -> { val mid = 8f + r.nextInt(4); for (i in 0 until 14) coinList += Coin(x0 + i * 0.95f, mid + 4f * sin(i * 0.55f)) }
                2 -> { val y0 = 4f + r.nextInt(8); for (cx in 0 until 5) for (cy in 0 until 4) coinList += Coin(x0 + 2f + cx * 0.95f, y0 + cy * 0.95f) }
                3 -> { zaps += Zap(x0 + 7f, 2f, 11f); for (i in 0 until 12) coinList += Coin(x0 + 1f + i * 1.0f, 14.5f); for (i in 0 until 5) coinList += Coin(x0 + 5f + i * 0.95f, 6f - 0f) }
                4 -> { zaps += Zap(x0 + 4f, 2f, 10f); zaps += Zap(x0 + 11f, 10f, 17f); for (i in 0 until 14) coinList += Coin(x0 + i * 0.95f, 10.5f + 4f * sin(i * 0.5f)) }
                else -> { zaps += Zap(x0 + 6f, 3f, 12f, 3f); for (i in 0 until 8) coinList += Coin(x0 + 1f + i * 0.95f, 14f - i * 0.5f); for (i in 0 until 6) coinList += Coin(x0 + 9f + i * 0.9f, 5f + i * 0.4f) }
            }
        }
    }

    fun update(dt: Float) {
        t += dt; phaseT += dt
        invuln = max(0f, invuln - dt); hitFlash = max(0f, hitFlash - dt)
        when (phase) {
            Phase.FLY -> {
                scroll += speed * dt
                physics(dt, thrust, 30f, 20f)
                generate(scroll + 40f)
                collect()
                if (coins >= spec.coinGoal) { phase = Phase.SPUTTER; phaseT = 0f }
            }
            Phase.SPUTTER -> {   // the engine coughs: little bursts, and the world slows
                speed = max(2.2f, speed - 2.2f * dt)
                scroll += speed * dt
                val burst = (phaseT % 0.55f) < 0.16f
                physics(dt, burst, 24f, 21f)
                collect()
                if (phaseT > 2.4f) { phase = Phase.DROP; phaseT = 0f }
            }
            Phase.DROP -> {
                speed = max(0f, speed - 3f * dt); scroll += speed * dt
                vy = min(11f, vy + 24f * dt); y += vy * dt
                if (y >= floorY) { y = floorY; vy = 0f; phase = Phase.DONE; phaseT = 0f; landed = true }
            }
            Phase.DONE -> { speed = 0f; if (phaseT > 1.6f) finished = true }
        }
    }

    private fun physics(dt: Float, up: Boolean, thrustA: Float, gravityA: Float) {
        vy += (if (up) -thrustA else gravityA) * dt
        vy = vy.coerceIn(-8.5f, 9.5f)
        y += vy * dt
        if (y < ceilY) { y = ceilY; vy = max(vy, 0f) }
        if (y > floorY) { y = floorY; vy = min(vy, 0f) }
    }

    private fun collect() {
        val x = wx
        for (c in coinList) {
            if (c.taken || c.wx < x - 2f || c.wx > x + 2f) continue
            if (hypot(c.wx - x, c.y - y) < 0.85f) { c.taken = true; coins++ }
        }
        coinList.removeAll { it.wx < scroll - 3f }
        zaps.removeAll { it.wx + it.dx < scroll - 3f }
        if (invuln <= 0f) for (z in zaps) {
            if (z.wx > x + 3f || z.wx + z.dx < x - 3f) continue
            if (distToSegment(x, y, z.wx, z.y1, z.wx + z.dx, z.y2) < 0.55f) {
                coins = max(0, coins - 8); invuln = 1.7f; hitFlash = 0.45f; vy = 4f
                break
            }
        }
    }

    private fun distToSegment(x: Float, yy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val u = if (l2 == 0f) 0f else (((x - ax) * dx + (yy - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (ax + u * dx), yy - (ay + u * dy))
    }
}
