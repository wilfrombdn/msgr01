package com.wilfrombdn.telegramnt

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The warehouse conveyor of Dead Letter level 3 (no Android code here, so tests can run it).
// You are carried at speed along three conveyor belts, one after another. You steer sideways to dodge boxes, tools, hanging
// cables and machinery, and to hop across the gap from one belt to the next (miss it and you fall). At the end is a platform with
// a jetpack on a stand; you walk onto it and throw it on.

internal class BeltSeg(val s0: Float, val s1: Float, val cx: Float, val half: Float, val speed: Float)

/** Something in the way. [kind]: BOX, TOOL, CABLE (hangs down, swings), MACHINE (a press that slams down on a beat). */
internal class BeltObs(val kind: Int, val s: Float, val x: Float, val w: Float, val h: Float, val amp: Float = 0f, val period: Float = 0f, val phase: Float = 0f)

internal class BeltSpec(val segs: List<BeltSeg>, val obstacles: List<BeltObs>, val platformS: Float, val endS: Float, val pickupX: Float)

internal class BeltWorld(val spec: BeltSpec) {
    enum class Phase { INTRO, RIDE, FALL, PICKUP, DONE }

    val width = 14f
    var phase = Phase.INTRO
    var t = 0f; var phaseT = 0f
    var x = 7f; var s = -5f
    var lives = 3
    var stun = 0f; var invuln = 0f
    var speedNow = 0f
    var fell = false                 // true once the fall has played out: the stage restarts
    var hitFlash = 0f
    var moving = false

    private var lastBeltSpeed = spec.segs.first().speed

    fun segAt(ss: Float): BeltSeg? = spec.segs.firstOrNull { ss >= it.s0 && ss <= it.s1 }

    /** Where a moving obstacle is right now (cables sway; the rest stay put). */
    fun obsX(o: BeltObs): Float = if (o.amp != 0f && o.period > 0f) o.x + o.amp * sin(2f * Math.PI.toFloat() * (t + o.phase) / o.period) else o.x
    /** A press is only dangerous while it is slammed down. */
    fun obsLive(o: BeltObs): Boolean = o.kind != MACHINE || (((t + o.phase) % o.period) < o.period * 0.55f)

    fun update(dt: Float, ix: Float, iy: Float, mag: Float) {
        t += dt; phaseT += dt
        stun = max(0f, stun - dt); invuln = max(0f, invuln - dt); hitFlash = max(0f, hitFlash - dt)
        when (phase) {
            Phase.INTRO -> {   // you walk in through the door; then the belt grabs you
                s += 2.4f * dt; moving = true
                if (s >= 0.4f) { phase = Phase.RIDE; phaseT = 0f }
            }
            Phase.RIDE -> ride(dt, ix, iy, mag)
            Phase.FALL -> { if (phaseT > 1.3f) fell = true }
            Phase.PICKUP -> {
                val sp = 3.2f
                if (stun <= 0f) { x += ix * mag * sp * dt; s += -iy * mag * sp * dt }
                moving = mag > 0.05f
                x = x.coerceIn(1.0f, width - 1.0f); s = s.coerceIn(spec.platformS + 0.5f, spec.endS - 0.5f)
                if (hypot(x - spec.pickupX, s - (spec.endS - 2.5f)) < 0.9f) { phase = Phase.DONE; phaseT = 0f }
            }
            Phase.DONE -> {}
        }
    }

    private fun ride(dt: Float, ix: Float, iy: Float, mag: Float) {
        val seg = segAt(s)
        if (seg != null) lastBeltSpeed = seg.speed
        speedNow = lastBeltSpeed
        moving = true
        val lat = if (stun > 0f) 0f else ix * mag * 4.4f
        val fwd = if (stun > 0f) -1.5f else -iy * mag * 1.6f          // push up to run ahead on the belt, down to hang back
        val prevS = s
        s += (speedNow + fwd) * dt
        x += lat * dt
        // landing on the next belt after a gap
        val next = spec.segs.firstOrNull { it.s0 > prevS && it.s0 <= s }
        if (next != null && abs(x - next.cx) > next.half + 0.1f) { phase = Phase.FALL; phaseT = 0f; return }
        val cur = segAt(s)
        if (cur != null) x = x.coerceIn(cur.cx - cur.half + 0.45f, cur.cx + cur.half - 0.45f) else x = x.coerceIn(0.7f, width - 0.7f)
        // the end of the last belt: you are on the platform
        if (s >= spec.platformS) { phase = Phase.PICKUP; phaseT = 0f; return }
        // things in the way
        if (invuln <= 0f) for (o in spec.obstacles) {
            if (abs(o.s - s) > 3f || !obsLive(o)) continue
            val ox = obsX(o)
            val dx = max(abs(x - ox) - o.w / 2f, 0f); val ds = max(abs(s - o.s) - o.h / 2f, 0f)
            if (hypot(dx, ds) < 0.42f) {
                lives--; invuln = 1.5f; stun = 0.55f; hitFlash = 0.35f
                x += (if (x >= ox) 1f else -1f) * 0.9f
                if (lives <= 0) { phase = Phase.FALL; phaseT = 0f }
                break
            }
        }
    }

    companion object {
        const val BOX = 0; const val TOOL = 1; const val CABLE = 2; const val MACHINE = 3
    }
}
