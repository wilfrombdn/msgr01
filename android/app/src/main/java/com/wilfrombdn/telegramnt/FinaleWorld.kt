package com.wilfrombdn.telegramnt

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dead Letter level 6, the last one: the broadcast. All in one place, played straight through (no Android code, so tests can drive it).
 *
 *  BEACH    the boat lands below the radio tower; the boatman points at it; a patrol on the beach spots you, points, and runs for the
 *           tower; "GO! FAST!"; you sprint up and kick the door in.
 *  INTRO    inside, a data centre stacked six catwalks high. The patrol scrambles up the ladders to the top, types a code into the
 *           terminal, and every catwalk clanks over into a slant. He grabs a cable spool.
 *  CLIMB    you play: he rolls spools down the slanted catwalks; jump them (the jump button) and climb the ladders (the stick).
 *           3 lives; every time you lose them all, the spools come slower and further apart.
 *  EXECUTE  at the top he drops to his knees with his hands up. You raise the pistol the beach crew gave you. One shot.
 *  DOOR     the door to the last room opens, slowly.
 *  CONTROL  not in your hands: you walk past the great window over the burning city (fire, red and blue lights, lasers, drones,
 *           searchlights), stop and turn to it, a bomb goes off out there, then you walk on to the panel.
 *  SIMON    the uplink: repeat the light sequence, one longer each round, up to 10. Each miss slows it down and drops you back one.
 *  BROADCAST the evidence goes out: a living room, a diner, a police station, downtown screens, the state capitol.
 *  END      back in the room. The screens cut out. Silence. "TRANSMISSION RECEIVED." "THANK YOU FOR YOUR ASSISTANCE, TELLEM.
 *           YOUR SERVICES ARE NO LONGER REQUIRED." The red and blue in the window grow and grow, then black. The title.
 *
 * Everything is measured in the pixels of the 256 x 384 picture the scene draws.
 */
internal class FinaleWorld(seed: Int = 6) {
    enum class Stage { BEACH, INTRO, CLIMB, EXECUTE, DOOR, CONTROL, SIMON, BROADCAST, END }
    var stage = Stage.BEACH; private set
    /** Seconds into the current stage. */
    var t = 0f; private set
    var clock = 0f; private set
    private val rnd = Random(seed)
    /** Set when the screen should fade before the next stage (the view draws the fade). */
    var fade = 0f; private set
    private var next: Stage? = null
    /** Sounds to play (the view drains this each frame and plays them if sound is on). */
    val events = ArrayList<String>()
    private var tPrev = 0f
    private fun at(sec: Float) = tPrev < sec && t >= sec

    companion object {
        const val W = 256
        const val H = 384
        const val BEACH_S = 12.6f
        const val INTRO_S = 9.2f
        const val EXECUTE_S = 5.2f
        const val DOOR_S = 5.0f
        const val CONTROL_S = 15.5f
        const val VIGNETTE_S = 3.4f
        const val VIGNETTES = 5
        const val END_TITLE = 17.5f
        const val END_TAP = 19.5f
        const val SIMON_GOAL = 10

        // the tower: six slanted catwalks (0 at the bottom) and a flat top platform (6)
        const val FLOORS = 6
        const val TOP = 6
        fun base(i: Int) = if (i >= TOP) 78f else 352f - i * 46f
        /** The catwalk's span: odd ones roll spools to the right, even ones to the left; each drops onto the one below. */
        fun spanL(i: Int) = when { i == 0 -> 8f; i >= TOP -> 8f; i % 2 == 1 -> 8f; else -> 20f }
        fun spanR(i: Int) = when { i == 0 -> 248f; i >= TOP -> 112f; i % 2 == 1 -> 236f; else -> 248f }
        fun slope(i: Int) = when { i == 0 || i >= TOP -> 0f; i % 2 == 1 -> 0.05f; else -> -0.05f }
        /** Ladders: (from floor, x). Each goes from floor i up to floor i + 1. */
        val LADDERS = listOf(0 to 204f, 1 to 56f, 1 to 150f, 2 to 186f, 3 to 76f, 3 to 128f, 4 to 214f, 5 to 96f)
        const val WALK = 48f
        const val CLIMB_SP = 36f
        const val JUMP_S = 0.62f
        const val JUMP_H = 17f
        const val SPOOL_R = 6f
    }

    // ---------------------------------------------------------------------------------------------------------------- the tower
    /** How far the catwalks have tilted into place (0 flat, 1 slanted). */
    var tilt = 0f; private set
    fun floorY(i: Int, x: Float) = base(i) + slope(i) * (x - 128f) * tilt

    // you
    var px = 22f; var py = base(0)
    var floor = 0; private set
    var onLadder: Pair<Int, Float>? = null; private set
    var jumpT = -1f; private set
    private var jumpDir = 0f
    var faceRight = true; private set
    var walking = false; private set
    val jumpH get() = if (jumpT < 0f) 0f else sin(jumpT / JUMP_S * Math.PI.toFloat()) * JUMP_H
    var lives = 3; private set
    /** How many times you've lost all your lives (the spools get slower and rarer). */
    var fails = 0; private set
    var hitT = -1f; private set

    // the patrol
    var gx = 30f; var gy = base(0); var gFace = true
    var gPose = 0; private set        // 0 stand, 1 climbing, 2 typing, 3 throwing, 4 kneeling hands up, 5 dead
    private var gPath = ArrayList<Pair<Float, Float>>()
    var throwT = 0f; private set      // time since the last throw (for the arm swing)

    class Spool(var x: Float, var y: Float, var floor: Int, var dir: Float) {
        var fallTo = -1          // falling to this floor (or -1)
        var vy = 0f
        var spin = 0f
        var ladderX = -1f        // going down a ladder
        var gone = false
    }
    val spools = ArrayList<Spool>()
    private var nextThrow = 1.2f

    // ---------------------------------------------------------------------------------------------------------------- simon
    val seq = IntArray(SIMON_GOAL) { rnd.nextInt(4) }
    var round = 1; private set              // how many of the sequence to repeat this round
    var simonFails = 0; private set
    var showing = true; private set
    var showT = 0f; private set
    var inputAt = 0; private set
    /** The button lit right now (-1 none) and how long it has been lit. */
    var lit = -1; private set
    var litT = 0f; private set
    var simonMsg = ""; private set
    var simonMsgT = 0f; private set
    private val stepS get() = 0.62f * (1f + 0.22f * min(simonFails, 5))

    // ---------------------------------------------------------------------------------------------------------------- end
    /** True while the room is silent after the transmission cuts out (the music stops). */
    val silent get() = stage == Stage.END && t > 2.6f
    val over get() = stage == Stage.END && t >= END_TAP

    fun update(dt: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) {
        clock += dt
        if (next != null) {
            fade += dt / 0.6f
            if (fade >= 1f) { fade = 1f; enter(next!!); next = null }
            return
        }
        if (fade > 0f) fade = max(0f, fade - dt / 0.6f)
        tPrev = t
        t += dt
        when (stage) {
            Stage.BEACH -> if (at(11.9f)) events += "kick"
            Stage.INTRO -> { if (t in 4.7f..6.0f && (t * 4f).toInt() != (tPrev * 4f).toInt()) events += "type"; if (at(6.0f) || at(6.8f) || at(7.5f)) events += "clank" }
            Stage.EXECUTE -> if (at(2.9f)) events += "shot"
            Stage.CONTROL -> if (at(6.5f)) events += "boom"
            Stage.END -> if (at(2.4f)) events += "static"
            else -> {}
        }
        when (stage) {
            Stage.BEACH -> if (t >= BEACH_S) go(Stage.INTRO)
            Stage.INTRO -> introTick(dt)
            Stage.CLIMB -> climbTick(dt, ix, iy, mag)
            Stage.EXECUTE -> { executeTick(); if (t >= EXECUTE_S) go(Stage.DOOR) }
            Stage.DOOR -> if (t >= DOOR_S) go(Stage.CONTROL)
            Stage.CONTROL -> if (t >= CONTROL_S) go(Stage.SIMON)
            Stage.SIMON -> simonTick(dt)
            Stage.BROADCAST -> if (t >= VIGNETTE_S * VIGNETTES + 1.2f) go(Stage.END)
            Stage.END -> {}
        }
    }

    private fun go(s: Stage) { if (next == null) next = s }
    private fun enter(s: Stage) {
        stage = s; t = 0f
        when (s) {
            Stage.INTRO -> { tilt = 0f; px = 22f; py = base(0); floor = 0; gx = 34f; gy = base(0); gPose = 1; gPath = patrolPath() }
            Stage.CLIMB -> resetClimb()
            Stage.SIMON -> { round = 1; inputAt = 0; showing = true; showT = -0.8f; lit = -1 }
            Stage.EXECUTE -> { spools.clear(); onLadder = null; jumpT = -1f; hitT = -1f; gx = 30f; gy = base(TOP) }
            else -> {}
        }
    }
    /** Skip straight to a stage (for tests and the skip arrow). */
    internal fun debugEnter(s: Stage) { next = null; fade = 0f; enter(s) }

    // ---------------------------------------------------------------------------------------------------------------- intro
    /** The patrol's way up with the catwalks still flat: walk to each ladder, climb it, on to the top. */
    private fun patrolPath(): ArrayList<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val route = listOf(0 to 204f, 1 to 56f, 2 to 186f, 3 to 76f, 4 to 214f, 5 to 96f)
        for ((f, lx) in route) { out += lx to base(f); out += lx to base(f + 1) }
        out += 100f to base(TOP)          // to the terminal
        return out
    }
    private fun introTick(dt: Float) {
        // he scrambles up (fast), types the code, the catwalks tilt into place, he picks up a spool
        if (gPath.isNotEmpty()) {
            val (tx, ty) = gPath[0]
            val dx = tx - gx; val dy = ty - gy; val d = hypot(dx, dy); val sp = 270f * dt
            gPose = if (abs(dy) > 0.5f) 1 else 0
            if (dx != 0f) gFace = dx > 0
            if (d <= sp) { gx = tx; gy = ty; gPath.removeAt(0) } else { gx += dx / d * sp; gy += dy / d * sp }
        }
        if (t > 4.7f && t < 6.0f) { gPose = 2; gFace = true }
        if (t > 6.0f) { gPose = 0; tilt = ((t - 6.0f) / 1.5f).coerceIn(0f, 1f); if (gx > 30f) { gx = max(30f, gx - 60f * dt); gFace = false } }
        if (t > 7.8f) { gPose = 3; gFace = true }
        py = floorY(0, px)
        if (t >= INTRO_S) go(Stage.CLIMB)
    }

    // ---------------------------------------------------------------------------------------------------------------- climb
    private fun resetClimb() {
        tilt = 1f; px = 22f; floor = 0; py = floorY(0, px); onLadder = null; jumpT = -1f; hitT = -1f; faceRight = true
        gx = 30f; gy = base(TOP); gPose = 3; gFace = true
        spools.clear(); nextThrow = 1.0f
    }
    fun jump() {
        if (stage != Stage.CLIMB || jumpT >= 0f || onLadder != null || hitT >= 0f) return
        jumpT = 0f; jumpDir = if (walking) (if (faceRight) 1f else -1f) else 0f
        events += "jump"
    }

    private fun spoolSpeed() = max(30f, 54f * (1f - 0.12f * fails))
    private fun throwGap() = 2.3f * (1f + 0.18f * min(fails, 5))

    private fun climbTick(dt: Float, ix: Float, iy: Float, mag: Float) {
        throwT += dt
        if (hitT >= 0f) {
            hitT += dt
            if (hitT > 1.6f) { lives--; if (lives <= 0) { lives = 3; fails++ }; resetClimb() }
            return
        }
        // the patrol throws
        nextThrow -= dt
        if (nextThrow <= 0f) { spools += Spool(46f, floorY(5, 46f) - SPOOL_R, 5, 1f); nextThrow = throwGap() * (0.8f + rnd.nextFloat() * 0.5f); throwT = 0f; events += "throw" }
        moveSpools(dt)
        movePlayer(dt, ix, iy, mag)
        // hit?
        for (s in spools) {
            if (s.gone) continue
            val sy = s.y; val feet = py - jumpH
            val dx = abs(s.x - px); val dy = abs((sy + SPOOL_R) - feet)
            if (dx < 7f && dy < 9f) { hitT = 0f; events += "hit"; return }
        }
        if (floor == TOP) go(Stage.EXECUTE)
    }

    private fun movePlayer(dt: Float, ix: Float, iy: Float, mag: Float) {
        val lad = onLadder
        if (lad != null) {
            val (f, lx) = lad
            px = lx; walking = false
            val up = if (mag > 0.2f && abs(iy) > 0.35f) -iy else 0f
            py -= up * CLIMB_SP * dt
            val bottom = floorY(f, lx); val top = floorY(f + 1, lx)
            if (py <= top) { py = top; floor = f + 1; onLadder = null }
            else if (py >= bottom) { py = bottom; floor = f; onLadder = null }
            return
        }
        // start climbing?
        if (jumpT < 0f && mag > 0.2f && abs(iy) > abs(ix) && abs(iy) > 0.35f) {
            for ((f, lx) in LADDERS) {
                if (abs(px - lx) > 6f) continue
                if (iy < 0f && f == floor) { onLadder = f to lx; px = lx; return }
                if (iy > 0f && f + 1 == floor) { onLadder = f to lx; px = lx; py += 1f; return }
            }
        }
        // walk (or keep going in the air)
        val vx = if (jumpT >= 0f) jumpDir * WALK else if (mag > 0.2f && abs(ix) > 0.3f) (if (ix > 0) WALK else -WALK) else 0f
        walking = vx != 0f && jumpT < 0f
        if (vx != 0f) faceRight = vx > 0
        px = (px + vx * dt).coerceIn(spanL(floor) + 3f, spanR(floor) - 3f)
        py = floorY(floor, px)
        if (jumpT >= 0f) { jumpT += dt; if (jumpT >= JUMP_S) jumpT = -1f }
    }

    private fun moveSpools(dt: Float) {
        val sp = spoolSpeed()
        for (s in spools) {
            if (s.gone) continue
            s.spin += dt * sp / SPOOL_R * s.dir
            if (s.fallTo >= 0) {
                s.vy += 420f * dt; s.y += s.vy * dt
                val land = floorY(s.fallTo, s.x) - SPOOL_R
                if (s.y >= land) {
                    s.y = land; s.floor = s.fallTo; s.fallTo = -1; s.vy = 0f; s.ladderX = -1f
                    s.dir = if (slope(s.floor) > 0f) 1f else if (slope(s.floor) < 0f) -1f else -1f
                }
                continue
            }
            s.x += s.dir * sp * dt
            s.y = floorY(s.floor, s.x) - SPOOL_R
            // down a ladder now and then
            for ((f, lx) in LADDERS) if (f + 1 == s.floor && abs(s.x - lx) < sp * dt + 0.5f && s.ladderX != lx && rnd.nextFloat() < 0.3f) { s.ladderX = lx; s.x = lx; s.fallTo = f; s.vy = 30f }
            if (s.fallTo >= 0) continue
            // off the end: drop to the next catwalk down (or out of the tower at the bottom)
            if (s.x > spanR(s.floor) || s.x < spanL(s.floor)) {
                if (s.floor == 0) s.gone = true else { s.fallTo = s.floor - 1; s.vy = 0f }
            }
        }
        spools.removeAll { it.gone }
    }

    // ---------------------------------------------------------------------------------------------------------------- execute
    private fun executeTick() {
        gPose = if (t < 2.9f) 4 else 5
        px = min(76f, 40f + t * 30f); py = base(TOP); faceRight = false; gFace = true
    }
    val pistolUp get() = stage == Stage.EXECUTE && t > 1.6f
    val shotFlash get() = stage == Stage.EXECUTE && t in 2.9f..3.05f

    // ---------------------------------------------------------------------------------------------------------------- simon
    private fun simonTick(dt: Float) {
        simonMsgT = max(0f, simonMsgT - dt)
        if (lit >= 0) { litT += dt; if (!showing && litT > 0.3f) lit = -1 }
        if (!showing) return
        showT += dt
        if (showT < 0f) { lit = -1; return }
        val i = (showT / stepS).toInt()
        val within = showT - i * stepS
        if (i >= round) { showing = false; lit = -1; inputAt = 0; return }
        if (within < stepS * 0.7f) { if (lit != seq[i] || litT > stepS) { lit = seq[i]; litT = 0f; events += "simon${seq[i]}" } } else lit = -1
    }

    /** You press one of the four buttons (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right). */
    fun simonTap(b: Int) {
        if (stage != Stage.SIMON || showing || next != null) return
        lit = b; litT = 0f
        if (seq[inputAt] != b) {
            events += "wrong"
            simonFails++
            round = max(1, round - 1)
            simonMsg = "SIGNAL LOST. AGAIN, SLOWER."; simonMsgT = 1.6f
            showing = true; showT = -1.2f; inputAt = 0
            return
        }
        events += "simon$b"
        inputAt++
        if (inputAt >= round) {
            if (round >= SIMON_GOAL) { simonMsg = "UPLINK ESTABLISHED."; simonMsgT = 2f; events += "ok"; go(Stage.BROADCAST); return }
            round++
            showing = true; showT = -0.7f; inputAt = 0
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- words
    /** Who says what in the cutscenes (drawn as speech bubbles), and the captions at the bottom. */
    fun bubble(): Pair<String, String>? = when (stage) {   // (who, what): who = "boat", "patrol", "you"
        Stage.BEACH -> when {
            t in 4.0f..6.2f -> "boat" to "THE TOWER. PUT IT ON THE AIR."
            t in 6.6f..7.9f -> "patrol" to "!"
            t in 8.6f..10.6f -> "boat" to "GO! FAST!"
            else -> null
        }
        Stage.INTRO -> if (t in 4.7f..6.0f) "patrol" to "4 · 7 · 1 · 1" else if (t in 7.9f..9.2f) "patrol" to "COME ON UP." else null
        Stage.EXECUTE -> if (t in 0.4f..2.4f) "patrol" to "WAIT! WAIT!" else null
        else -> null
    }
    fun caption(): String = when (stage) {
        Stage.BEACH -> if (t > 11f) "" else if (t < 3.6f) "THE BOAT RUNS UP ONTO THE SAND BELOW THE TOWER." else ""
        Stage.CLIMB -> if (t < 5f) "CLIMB. JUMP THE SPOOLS." else ""
        Stage.DOOR -> ""
        Stage.CONTROL -> when { t < 4.5f -> ""; t < 9.5f -> ""; else -> "" }
        Stage.SIMON -> if (simonMsgT > 0f) simonMsg else if (showing) "WATCH..." else "REPEAT IT.  ${round.coerceAtMost(SIMON_GOAL)} / $SIMON_GOAL"
        Stage.BROADCAST -> when ((t / VIGNETTE_S).toInt()) {
            0 -> "IN EVERY HOME."
            1 -> "ON EVERY RADIO."
            2 -> "IN EVERY STATION HOUSE."
            3 -> "ON EVERY SCREEN DOWNTOWN."
            else -> "ALL THE WAY TO THE STATE CAPITOL."
        }
        Stage.END -> when {
            t < 2.6f -> ""
            t < 4.6f -> ""
            else -> ""
        }
        else -> ""
    }
    /** The lines typed on the big screen at the very end. */
    fun endLines(): List<Pair<String, Float>> = listOf("TRANSMISSION RECEIVED." to 4.4f, "THANK YOU FOR YOUR ASSISTANCE, TELLEM." to 6.8f, "YOUR SERVICES ARE NO LONGER REQUIRED." to 9.4f)
    /** How strong the red and blue in the window are at the end (grows until black). */
    val sirens get() = if (stage == Stage.END) ((t - 10.8f) / 4f).coerceIn(0f, 1f) else 0f
    val blackout get() = if (stage == Stage.END) ((t - 14.8f) / 1.8f).coerceIn(0f, 1f) else 0f
}
