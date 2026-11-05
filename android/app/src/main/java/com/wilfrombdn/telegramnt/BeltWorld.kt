package com.wilfrombdn.telegramnt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The conveyor run of Dead Letter level 3 (no Android code here, so tests can run it). Three belts run side by side, high above the
// warehouse floor with a gap between them. You run down the belt you are on; tap the left or right half of the screen to LEAP to the
// next belt. Things come down the belts at you (crates, tools, swinging cables, presses, sparks, a crane hook, flying barrels). The
// belts start quick and speed up to about double. At the end a kicker throws you hard onto the jetpack platform.

internal class BeltObs(
    val kind: Int, val s: Float, val x: Float, val hw: Float, val hs: Float,
    val amp: Float = 0f, val period: Float = 0f, val phase: Float = 0f, val onFrac: Float = 1f,
) {
    var curS = s; var armed = false; var t0 = 0f
}

internal class BeltSpec(val obstacles: List<BeltObs>, val endS: Float, val laneX: FloatArray)

internal class BeltWorld(val spec: BeltSpec) {
    enum class Phase { INTRO, RIDE, FALL, LAUNCH, PICKUP, DONE }

    val width = 14f
    var phase = Phase.INTRO
    var t = 0f; var phaseT = 0f
    var lane = 1; var x = spec.laneX[1]
    var s = -5f
    var air = 0f                     // 0..1: how high up in a leap
    var lives = 3
    var invuln = 0f; var hitFlash = 0f; var shake = 0f
    var speedNow = 0f
    var fell = false                 // set once the fall has played out: the stage restarts
    var moving = false
    var launchK = 0f                 // 0..1 through the throw at the end
    var leaping = false
    private var leapT = 0f; private var fromX = x; private var queued = 0
    var hits = 0

    fun speedAt(ss: Float) = 5.2f + 5.2f * (ss.coerceIn(0f, spec.endS) / spec.endS)

    /** Obstacle's sideways position right now. */
    fun obsX(o: BeltObs): Float = obsXAt(o, t)
    fun obsXAt(o: BeltObs, tt: Float): Float = if (o.amp != 0f && o.period > 0f) o.x + o.amp * sin(2f * PI.toFloat() * (tt + o.phase) / o.period) else o.x
    /** Is it dangerous right now (presses and sparks pulse)? */
    fun obsLive(o: BeltObs): Boolean = liveAt(o, t)
    fun liveAt(o: BeltObs, tt: Float): Boolean = (o.kind != PRESS && o.kind != ARC) || (((tt + o.phase) % o.period) < o.period * o.onFrac)
    /** Is it a low thing you can jump clean over by being in the air? */
    private fun low(o: BeltObs) = o.kind == BOX || o.kind == TOOL || o.kind == PRESS || o.kind == BARREL

    /** Tap left (-1) or right (+1): leap to the next belt. */
    fun leap(dir: Int) {
        if (phase != Phase.RIDE) return
        if (leaping) { queued = dir; return }
        val to = (lane + dir).coerceIn(0, spec.laneX.size - 1)
        if (to == lane) return
        lane = to; fromX = x; leapT = 0f; leaping = true
    }

    fun update(dt: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) {
        t += dt; phaseT += dt
        invuln = max(0f, invuln - dt); hitFlash = max(0f, hitFlash - dt); shake = max(0f, shake - dt)
        when (phase) {
            Phase.INTRO -> { s += 2.6f * dt; moving = true; speedNow = 2.6f; if (s >= 0.4f) { phase = Phase.RIDE; phaseT = 0f } }
            Phase.RIDE -> ride(dt)
            Phase.FALL -> { if (phaseT > 1.4f) fell = true }
            Phase.LAUNCH -> {
                launchK = (phaseT / 1.0f).coerceIn(0f, 1f)
                s = spec.endS + 0.4f + launchK * 9.6f
                x += (spec.laneX[1] - x) * min(1f, dt * 4f)
                air = sin(launchK * PI.toFloat())
                if (launchK >= 1f) { phase = Phase.PICKUP; phaseT = 0f; air = 0f; shake = 0.5f }
            }
            Phase.PICKUP -> { s = spec.endS + 10f; moving = false; if (phaseT > 2.3f) { phase = Phase.DONE; phaseT = 0f } }
            Phase.DONE -> {}
        }
    }

    private fun ride(dt: Float) {
        speedNow = speedAt(s)
        val prev = s
        s += speedNow * dt
        moving = true
        if (leaping) {
            leapT += dt / 0.26f
            val k = leapT.coerceIn(0f, 1f)
            x = fromX + (spec.laneX[lane] - fromX) * (k * k * (3f - 2f * k))
            air = sin(k * PI.toFloat())
            if (leapT >= 1f) { leaping = false; air = 0f; x = spec.laneX[lane]; if (queued != 0) { val q = queued; queued = 0; leap(q) } }
        }
        for (o in spec.obstacles) {
            if (o.kind == FLYER) {
                if (!o.armed && s >= o.s - 20f) { o.armed = true; o.t0 = t }
                if (o.armed) o.curS = o.s - 7f * max(0f, t - o.t0 - 0.6f) * 1f
            }
            if (invuln > 0f) continue
            if (!obsLive(o)) continue
            if (low(o) && air > 0.55f) continue
            val dx = max(abs(x - obsX(o)) - o.hw, 0f); val ds = max(abs(s - o.curS) - o.hs, 0f)
            if (dx < 0.38f && ds < 0.4f) {
                lives--; hits++; invuln = 1.4f; hitFlash = 0.4f; shake = 0.3f
                if (lives <= 0) { phase = Phase.FALL; phaseT = 0f }
                break
            }
        }
        if (phase == Phase.RIDE && s >= spec.endS) { phase = Phase.LAUNCH; phaseT = 0f; launchK = 0f; shake = 0.8f; leaping = false }
        if (phase == Phase.RIDE && prev < 0f) s = max(s, 0.4f)
    }

    companion object {
        const val BOX = 0; const val TOOL = 1; const val CABLE = 2; const val PRESS = 3; const val ARC = 4; const val HOOK = 5; const val BARREL = 6; const val FLYER = 7
    }
}
