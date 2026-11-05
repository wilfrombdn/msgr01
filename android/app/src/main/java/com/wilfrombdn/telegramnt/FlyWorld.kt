package com.wilfrombdn.telegramnt

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The jetpack run of Dead Letter level 3 (no Android code here, so tests can run it). A side-scroller: hold to fly up, let go to
// fall. Collect 100 coins. Dangers: electric zappers, spinning saw blades, laser fences with a moving gap, and ROCKETS (a warning
// mark appears at the right edge, then the rocket streaks across at that height). Every hit costs coins. When you have 100 the
// jetpack coughs, gives out, and throws you down onto the floor.

internal class FlySpec(val seed: Int = 7, val coinGoal: Int = 100)

internal class FlyWorld(val spec: FlySpec) {
    enum class Phase { FLY, SPUTTER, DROP, DONE }

    class Coin(val wx: Float, val y: Float) { var taken = false }
    /** An electric zapper: a bar from (wx, y1) to (wx + dx, y2). */
    class Zap(val wx: Float, val y1: Float, val y2: Float, val dx: Float = 0f)
    /** A rocket: waits until you pass [trigX], shows a warning at the right edge for a moment, then flies across at height [y]. */
    class Rocket(val trigX: Float, val y: Float) { var state = 0; var t = 0f; var sx = 0f }   // 0 waiting, 1 warning, 2 flying, 3 gone
    /** SAW: a spinning blade bobbing up and down around [y]. GATE: a laser fence from ceiling to floor with a gap that moves. */
    class Haz(val kind: Int, val wx: Float, val y: Float, val amp: Float, val period: Float, val phase: Float, val gap: Float = 2.0f)

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
    var rocketsSeen = 0
    val coinList = ArrayList<Coin>()
    val zaps = ArrayList<Zap>()
    val rockets = ArrayList<Rocket>()
    val hazards = ArrayList<Haz>()
    private var chunks = 0
    private var speed = 6.5f
    val floorY get() = h - 1.7f
    val ceilY = 1.3f

    init { generate(0f + 30f) }

    /** The world position of me (across). */
    val wx get() = scroll + px

    fun sawY(z: Haz) = z.y + z.amp * sin(2f * PI.toFloat() * (t + z.phase) / z.period)

    /** Lays out more coins and dangers ahead, a chunk at a time, in the same order every run. */
    private fun generate(upTo: Float) {
        while (chunks * 13f + 13f < upTo) {
            val k = chunks++
            val r = Random(spec.seed * 1000L + k)
            val x0 = 18f + k * 13f
            val kind = when (k) { 0 -> 0; 1 -> 3; 2 -> 4; 3, 4 -> 6; else -> listOf(0, 1, 2, 3, 4, 5, 6, 6, 7, 8, 9)[r.nextInt(11)] }
            when (kind) {
                0 -> { val yy = 5f + r.nextInt(10); for (i in 0 until 10) coinList += Coin(x0 + 1f + i * 0.95f, yy) }
                1 -> { val mid = 8f + r.nextInt(4); for (i in 0 until 14) coinList += Coin(x0 + i * 0.95f, mid + 4f * sin(i * 0.55f)) }
                2 -> { val y0 = 4f + r.nextInt(8); for (cx in 0 until 5) for (cy in 0 until 4) coinList += Coin(x0 + 2f + cx * 0.95f, y0 + cy * 0.95f) }
                3 -> { zaps += Zap(x0 + 7f, 2f, 11f); for (i in 0 until 12) coinList += Coin(x0 + 1f + i * 1.0f, 14.5f); for (i in 0 until 5) coinList += Coin(x0 + 5f + i * 0.95f, 6f) }
                4 -> { zaps += Zap(x0 + 4f, 2f, 10f); zaps += Zap(x0 + 11f, 10f, 17f); for (i in 0 until 14) coinList += Coin(x0 + i * 0.95f, 10.5f + 4f * sin(i * 0.5f)) }
                5 -> { zaps += Zap(x0 + 6f, 3f, 12f, 3f); for (i in 0 until 8) coinList += Coin(x0 + 1f + i * 0.95f, 14f - i * 0.5f); for (i in 0 until 6) coinList += Coin(x0 + 9f + i * 0.9f, 5f + i * 0.4f) }
                6 -> { // a volley of rockets, one after another, each warned at the right edge
                    val ys = listOf(4f + r.nextInt(12), 4f + r.nextInt(12), 4f + r.nextInt(12))
                    for ((i, yy) in ys.withIndex()) rockets += Rocket(x0 + i * 4.2f, yy)
                    for (i in 0 until 13) coinList += Coin(x0 + 0.5f + i * 0.95f, 10f + 3f * sin(i * 0.7f + r.nextInt(3)))
                }
                7 -> { // saw blades bobbing up and down
                    hazards += Haz(0, x0 + 4f, 6f + r.nextInt(4), 3.6f, 2.2f, r.nextFloat() * 2f)
                    hazards += Haz(0, x0 + 9f, 12f - r.nextInt(3), 3.6f, 2.5f, r.nextFloat() * 2f)
                    for (i in 0 until 10) coinList += Coin(x0 + 1f + i * 1.1f, 9f + 5f * sin(i * 0.6f))
                }
                8 -> { // a laser fence with a gap that drifts
                    val gy = 6f + r.nextInt(8)
                    hazards += Haz(1, x0 + 7f, gy, 2.8f, 3.4f, r.nextFloat() * 2f, 2.1f)
                    for (i in 0 until 8) coinList += Coin(x0 + 2f + i * 0.8f, gy + (if (i < 4) 0f else 1.5f * (i - 4)))
                }
                else -> { // rockets and a zapper together
                    zaps += Zap(x0 + 8f, 2f, 9f)
                    rockets += Rocket(x0 + 0.5f, 13f + r.nextInt(4)); rockets += Rocket(x0 + 5f, 10f + r.nextInt(3))
                    for (i in 0 until 10) coinList += Coin(x0 + 1f + i * 1.1f, 15f - 0.5f * i)
                }
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
                rocketsStep(dt)
                collect()
                hurt()
                if (coins >= spec.coinGoal) { phase = Phase.SPUTTER; phaseT = 0f; zaps.clear(); rockets.clear(); hazards.clear() }
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
                vy = min(13f, vy + 30f * dt); y += vy * dt
                if (y >= floorY) { y = floorY; vy = 0f; phase = Phase.DONE; phaseT = 0f; landed = true }
            }
            Phase.DONE -> { speed = 0f; if (phaseT > 1.7f) finished = true }
        }
    }

    private fun rocketsStep(dt: Float) {
        for (r in rockets) when (r.state) {
            0 -> if (wx >= r.trigX) { r.state = 1; r.t = 0f; rocketsSeen++ }
            1 -> { r.t += dt; if (r.t >= 1.25f) { r.state = 2; r.sx = w + 1f } }
            2 -> { r.sx -= 17f * dt; if (r.sx < -3f) r.state = 3 }
        }
        rockets.removeAll { it.state == 3 }
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
        hazards.removeAll { it.wx < scroll - 3f }
    }

    private fun hit() { coins = max(0, coins - 8); invuln = 1.7f; hitFlash = 0.45f; vy = 4f }

    private fun hurt() {
        if (invuln > 0f) return
        val x = wx
        for (z in zaps) {
            if (z.wx > x + 3f || z.wx + z.dx < x - 3f) continue
            if (distToSegment(x, y, z.wx, z.y1, z.wx + z.dx, z.y2) < 0.55f) { hit(); return }
        }
        for (z in hazards) {
            if (abs(z.wx - x) > 2f) continue
            if (z.kind == 0) { if (hypot(z.wx - x, sawY(z) - y) < 1.05f) { hit(); return } }
            else if (abs(z.wx - x) < 0.4f) { val gy = sawY(z); if (abs(y - gy) > z.gap - 0.35f) { hit(); return } }
        }
        for (r in rockets) if (r.state == 2 && abs(r.sx - px) < 1.0f && abs(r.y - y) < 0.6f) { hit(); return }
    }

    private fun distToSegment(x: Float, yy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val u = if (l2 == 0f) 0f else (((x - ax) * dx + (yy - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (ax + u * dx), yy - (ay + u * dy))
    }
}
