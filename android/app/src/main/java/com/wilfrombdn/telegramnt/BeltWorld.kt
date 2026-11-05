package com.wilfrombdn.telegramnt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The conveyor run of Dead Letter level 3 (no Android code here, so tests can run it). Three belts run side by side, high above the
// warehouse floor with a gap between them. SWIPE left or right to LEAP to the next belt, swipe up to JUMP.
// Collect 100 coins (high ones only when you are in the air; a jump takes every coin hanging over what you jump). The belts start quick and speed up to about double. Once you have
// 100 coins the belt runs out at a kicker that throws you hard onto the jetpack platform; if you have fewer, the belts keep going.

internal class BeltObs(
    val kind: Int, val s: Float, val x: Float, val hw: Float, val hs: Float,
    val amp: Float = 0f, val period: Float = 0f, val phase: Float = 0f, val onFrac: Float = 1f,
) {
    var curS = s; var armed = false; var t0 = 0f
}

internal class BeltCoin(val s: Float, val lane: Int, val high: Boolean) { var taken = false; var claimed = false }

/** The level: obstacles and coins are made ahead of you on demand by [extend], so the belts can run on until you have your coins. */
internal class BeltSpec(
    val obstacles: MutableList<BeltObs>, val coins: MutableList<BeltCoin>, val endS: Float, val laneX: FloatArray,
    val coinGoal: Int = 100, val extend: (Float) -> Unit = {},
)

internal class BeltWorld(val spec: BeltSpec) {
    enum class Phase { INTRO, RIDE, FALL, LAUNCH, PICKUP, DONE }

    val width = 14f
    var phase = Phase.INTRO
    var t = 0f; var phaseT = 0f
    var lane = 1; var x = spec.laneX[1]
    var s = -5f
    var air = 0f                     // 0..1: how high up in a leap sideways
    var jumpAir = 0f                 // 0..1: how high up in a jump
    var lives = 3
    var coins = 0
    var endS = spec.endS             // where the kicker is (it moves on while you are short of coins)
    var invuln = 0f; var hitFlash = 0f; var shake = 0f; var coinFlash = 0f
    var speedNow = 0f
    var fellInHole = false           // the last life was lost to a hole in the belt (shows YOU FELL), otherwise YOU DIED
    var fell = false                 // set once the fall has played out: the stage restarts
    var moving = false
    var launchK = 0f                 // 0..1 through the throw at the end
    var leaping = false
    private var leapT = 0f; private var fromX = x; private var queued = 0
    private var jumpK = -1f
    val jumping get() = jumpK >= 0f
    var hits = 0

    init { spec.extend(60f) }

    fun speedAt(ss: Float) = 5.2f + 5.2f * (ss.coerceIn(0f, spec.endS) / spec.endS)

    fun obsX(o: BeltObs): Float = obsXAt(o, t)
    fun obsXAt(o: BeltObs, tt: Float): Float = if (o.amp != 0f && o.period > 0f) o.x + o.amp * sin(2f * PI.toFloat() * (tt + o.phase) / o.period) else o.x
    fun obsLive(o: BeltObs): Boolean = liveAt(o, t)
    /** Pulsing things (press, sparks, falling crates) are only dangerous for part of each cycle. */
    fun liveAt(o: BeltObs, tt: Float): Boolean = (o.kind != PRESS && o.kind != ARC && o.kind != DROP) || (((tt + o.phase) % o.period) < o.period * o.onFrac)
    /** Low things (and holes in the belt) you can clear by being in the air. */
    fun isLow(o: BeltObs) = o.kind == BOX || o.kind == TOOL || o.kind == PRESS || o.kind == BARREL || o.kind == SMALL || o.kind == GAP
    private fun inAir() = air > 0.55f || jumpAir > 0.2f

    /** Swipe left (-1) or right (+1): leap to the next belt. */
    fun leap(dir: Int) {
        if (phase != Phase.RIDE) return
        if (leaping) { queued = dir; return }
        val to = (lane + dir).coerceIn(0, spec.laneX.size - 1)
        if (to == lane) return
        lane = to; fromX = x; leapT = 0f; leaping = true
    }

    /** Swipe up: jump (over crates, barriers and holes in the belt). */
    fun jump() {
        if (phase != Phase.RIDE || jumpK >= 0f) return
        jumpK = 0f
        // every coin hanging in the air over the stretch you'll be in the air for is yours (they fly to you as you pass)
        val reach = speedAt(s) * 0.8f + 3.5f
        for (c in spec.coins) if (!c.taken && c.high && c.s >= s - 0.8f && c.s <= s + reach) c.claimed = true
    }

    fun update(dt: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) {
        t += dt; phaseT += dt
        invuln = max(0f, invuln - dt); hitFlash = max(0f, hitFlash - dt); shake = max(0f, shake - dt); coinFlash = max(0f, coinFlash - dt)
        when (phase) {
            Phase.INTRO -> { s += 2.6f * dt; moving = true; speedNow = 2.6f; if (s >= 0.4f) { phase = Phase.RIDE; phaseT = 0f } }
            Phase.RIDE -> ride(dt)
            Phase.FALL -> { if (phaseT > 1.4f) fell = true }
            Phase.LAUNCH -> {
                launchK = (phaseT / 1.0f).coerceIn(0f, 1f)
                s = endS + 0.4f + launchK * 9.6f
                x += (spec.laneX[1] - x) * min(1f, dt * 4f)
                air = sin(launchK * PI.toFloat()); jumpAir = 0f
                if (launchK >= 1f) { phase = Phase.PICKUP; phaseT = 0f; air = 0f; shake = 0.5f }
            }
            Phase.PICKUP -> { s = endS + 10f; moving = false; if (phaseT > 2.3f) { phase = Phase.DONE; phaseT = 0f } }
            Phase.DONE -> {}
        }
    }

    private fun ride(dt: Float) {
        speedNow = speedAt(s)
        val prev = s
        s += speedNow * dt
        moving = true
        // make more of the level ahead, and keep the end away while you are short of coins
        spec.extend(s + 45f)
        if (coins < spec.coinGoal && s + 30f > endS) { endS += 60f; spec.extend(endS + 10f) }
        if (leaping) {
            leapT += dt / 0.26f
            val k = leapT.coerceIn(0f, 1f)
            x = fromX + (spec.laneX[lane] - fromX) * (k * k * (3f - 2f * k))
            air = sin(k * PI.toFloat())
            if (leapT >= 1f) { leaping = false; air = 0f; x = spec.laneX[lane]; if (queued != 0) { val q = queued; queued = 0; leap(q) } }
        }
        if (jumpK >= 0f) {
            jumpK += dt / 0.8f
            jumpAir = if (jumpK >= 1f) 0f else sin(jumpK * PI.toFloat())
            if (jumpK >= 1f) jumpK = -1f
        }
        // coins
        for (c in spec.coins) {
            if (c.claimed && !c.taken && c.s <= s + 0.7f) { c.taken = true; coins++; coinFlash = 0.25f; continue }
            if (c.taken || abs(c.s - s) > 0.7f) continue
            if (abs(spec.laneX[c.lane] - x) > 0.95f) continue
            if (c.high && !(jumpAir > 0.3f || air > 0.5f)) continue
            c.taken = true; coins++; coinFlash = 0.25f
        }
        for (o in spec.obstacles) {
            if (o.kind == FLYER || o.kind == TRUCK) {
                if (!o.armed && s >= o.s - 20f) { o.armed = true; o.t0 = t }
                if (o.armed) o.curS = o.s - (if (o.kind == TRUCK) 5f else 7f) * max(0f, t - o.t0 - 0.6f)
            }
            if (invuln > 0f) continue
            if (!obsLive(o)) continue
            if (isLow(o) && inAir()) continue
            val dx = max(abs(x - obsX(o)) - o.hw, 0f); val ds = max(abs(s - o.curS) - o.hs, 0f)
            if (dx < 0.38f && ds < 0.4f) {
                lives--; hits++; invuln = 1.4f; hitFlash = 0.4f; shake = 0.3f
                if (lives <= 0) { phase = Phase.FALL; phaseT = 0f; fellInHole = o.kind == GAP }
                break
            }
        }
        if (phase == Phase.RIDE && s >= endS && coins >= spec.coinGoal) { phase = Phase.LAUNCH; phaseT = 0f; launchK = 0f; shake = 0.8f; leaping = false; jumpK = -1f }
        if (phase == Phase.RIDE && prev < 0f) s = max(s, 0.4f)
    }

    companion object {
        const val BOX = 0; const val TOOL = 1; const val CABLE = 2; const val PRESS = 3; const val ARC = 4; const val HOOK = 5; const val BARREL = 6; const val FLYER = 7
        const val SMALL = 8; const val GAP = 9; const val TRUCK = 10; const val SAWB = 11; const val DROP = 12
    }
}
