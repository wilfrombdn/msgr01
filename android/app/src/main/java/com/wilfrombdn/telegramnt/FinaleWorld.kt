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
 *  SIMON    the uplink: repeat the light sequence, one longer each round, up to 8. Each miss slows it down and drops you back one.
 *  BROADCAST you pick up the mic and plead, live, everywhere (the plea: [PLEA]): cut between you in the tower (picking up the
 *           mic, pacing, the THUD on the door, breaking down) and the people listening (a living room, a diner, a police
 *           station, downtown screens, the state capitol). At "I fucking tri-" it cuts.
 *  END      black; "I FUCKING TRI-" in red; gunfire over the radio, static, dead air. Back in the tower: you kneel in your own
 *           blood clutching the mic, breathing hard. The door is kicked off its hinges, guards rush in, a masked man walks over,
 *           slowly pushes you over with his boot, pulls a shotgun, aims at your head. A second. BLAM. Black. The title.
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
        const val BEACH_S = 17f
        const val KICK_T = 15.6f
        const val INTRO_S = 9.2f
        const val EXECUTE_S = 7.8f
        const val GUN_T = 1.6f       // the pistol comes up: the music cuts
        const val SHOT_T = 3.5f      // ...a held breath, then the shot
        const val TOWER_DOOR = 110f  // the door to the next room, at the end of the top platform
        const val DOOR_S = 5.0f
        const val CONTROL_S = 15.5f
        const val VIGNETTE_S = 3.4f
        const val VIGNETTES = 5
        const val END_TITLE = 22.6f
        const val END_TAP = 25.6f
        /** How long the plea runs before it's cut off. */
        const val PLEA_S = 46.0f
        /** The plea's shots: when each starts, and what's on screen (-1 you take the mic, -2 pacing, -3 the thud on the door, -4 breaking down; 0..4 the places listening). */
        val PLEA_SHOTS = listOf(0f to -1, 5.0f to 0, 9.4f to 1, 13.4f to -2, 19.0f to 2, 23.4f to 3, 29.6f to -3, 35.6f to 4, 40.6f to -4)
        /** What you say into the mic, and when. */
        val PLEA = listOf(
            Triple(1.4f, 3.1f, "Is— fuck, is this thing on?"),
            Triple(3.2f, 4.9f, "Is anyone hearing this?!"),
            Triple(5.2f, 7.1f, "Listen to me, please—"),
            Triple(7.2f, 9.3f, "don't, don't turn it off... I'm about to die."),
            Triple(9.6f, 11.2f, "My name? I was a clerk!"),
            Triple(11.3f, 13.3f, "In the tower!"),
            Triple(13.6f, 15.5f, "We're not... we aren't terrorists!"),
            Triple(15.6f, 16.7f, "Right?"),
            Triple(16.8f, 18.9f, "We never— none of us did nothing!"),
            Triple(19.2f, 21.2f, "Every blackout, every red alert..."),
            Triple(21.3f, 23.3f, "he made it up!"),
            Triple(23.6f, 25.8f, "The Mayor! Covering up his plans"),
            Triple(25.9f, 27.8f, "to sell out this city, and other mayors too,"),
            Triple(27.9f, 29.5f, "for wealth and power!"),
            Triple(29.7f, 30.6f, "There's more—"),
            Triple(31.4f, 33.1f, "Oh god... they're right outside."),
            Triple(33.2f, 35.4f, "They're right fucking outside."),
            Triple(35.8f, 37.4f, "Sarah's dead... Chen..."),
            Triple(37.5f, 40.4f, "they shot the cop in cold blood right in front of me,"),
            Triple(40.8f, 42.9f, "I got his blood all over—"),
            Triple(43.3f, 44.7f, "I tried..."),
            Triple(44.9f, PLEA_S, "I fucking tri—"),
        )
        // the end, after the cut
        const val RED_OFF = 3.4f        // the red words go
        const val FADE_IN = 4.6f        // back into the tower
        const val DOOR_OFF = 10.0f      // the door is kicked off its hinges
        const val MASK_IN = 11.6f       // the masked man walks in
        const val MASK_AT = 14.8f       // ...and stands over you
        const val PUSH = 15.8f          // his boot pushes you over
        const val DOWN = 17.0f          // you're on the floor
        const val GUN_OUT = 17.6f       // the shotgun
        const val AIM = 19.0f           // aimed at your head; a second
        const val BLAM = 20.0f
        const val SIMON_GOAL = 8

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
        val TAUNTS = listOf("NOBODY'S LISTENING, TELLEM.", "THE MAYOR SENDS HIS REGARDS.", "YOU'RE ALREADY DEAD. IT'S ON FILE.", "GO HOME. OH WAIT.", "THEY'LL NEVER BELIEVE YOU.")
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
    /** Where you last cleared a spool, and how long ago (for the sparkle). */
    var clearX = 0f; var clearY = 0f; var clearT = 9f; private set
    private val cleared = HashSet<Spool>()
    private var nextThrow = 1.2f

    // ---------------------------------------------------------------------------------------------------------------- simon
    val seq = ArrayList<Int>().apply { repeat(SIMON_GOAL) { add(rnd.nextInt(4)) } }
    // ---- arcade mode (after the game is beaten): the climb repeats faster and faster; the uplink never ends
    var arcade = false
    var arcadeRound = 0; private set
    var jumpsCleared = 0; private set
    /** The arcade game is over (out of lives on the climb, or a miss on the uplink). */
    var gameOver = false; private set
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
    val silent get() = stage == Stage.END || (stage == Stage.EXECUTE && t >= GUN_T) || stage == Stage.DOOR || stage == Stage.CONTROL
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
            Stage.BEACH -> if (at(KICK_T)) events += "kick"
            Stage.INTRO -> { if (t in 4.7f..6.0f && (t * 4f).toInt() != (tPrev * 4f).toInt()) events += "type"; if (at(6.0f) || at(6.8f) || at(7.5f)) events += "clank" }
            Stage.EXECUTE -> { if (at(SHOT_T)) events += "shot"; if (at(5.2f)) events += "keys" }
            Stage.CONTROL -> if (at(6.5f)) events += "boom"
            Stage.BROADCAST -> { if (at(1.2f)) events += "mic"; if (at(30.4f)) events += "thud"; if (at(32.2f)) events += "thud" }
            Stage.END -> {
                if (at(0.35f)) events += "radiofire"; if (at(2.0f)) events += "static"
                for (b in floatArrayOf(5.0f, 6.6f, 8.1f, 9.5f, 12.0f, 13.6f, 18.2f)) if (at(b)) events += "breath"
                if (at(7.3f)) events += "cough"
                if (at(DOOR_OFF)) events += "doorfall"
                if (at(GUN_OUT + 0.8f)) events += "pump"
                if (at(BLAM)) events += "shotgun"
            }
            else -> {}
        }
        when (stage) {
            Stage.BEACH -> if (t >= BEACH_S) go(Stage.INTRO)
            Stage.INTRO -> introTick(dt)
            Stage.CLIMB -> if (!gameOver) climbTick(dt, ix, iy, mag)
            Stage.EXECUTE -> { executeTick(); if (t >= EXECUTE_S) go(Stage.DOOR) }
            Stage.DOOR -> if (t >= DOOR_S) go(Stage.CONTROL)
            Stage.CONTROL -> if (t >= CONTROL_S) go(Stage.SIMON)
            Stage.SIMON -> simonTick(dt)
            Stage.BROADCAST -> if (t >= PLEA_S) enter(Stage.END)          // a hard cut, no fade
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
    /** The skip arrow: on to the next scene (from the climb straight to the control room, past the shot and the door). */
    fun skip() {
        val to = when (stage) {
            Stage.BEACH -> Stage.INTRO; Stage.INTRO -> Stage.CLIMB
            Stage.CLIMB, Stage.EXECUTE, Stage.DOOR -> Stage.CONTROL
            Stage.CONTROL -> Stage.SIMON; Stage.SIMON -> Stage.BROADCAST; Stage.BROADCAST -> Stage.END
            Stage.END -> return
        }
        debugEnter(to)
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

    private fun spoolSpeed() = if (arcade) 54f * (1f + 0.14f * arcadeRound) else max(30f, 54f * (1f - 0.12f * fails))
    private fun throwGap() = if (arcade) 2.3f / (1f + 0.15f * arcadeRound) else 2.3f * (1f + 0.18f * min(fails, 5))

    private fun climbTick(dt: Float, ix: Float, iy: Float, mag: Float) {
        throwT += dt; clearT += dt
        if (hitT >= 0f) {
            hitT += dt
            if (hitT > 1.6f) { lives--; if (lives <= 0) { if (arcade) { gameOver = true; hitT = -1f; return }; lives = 3; fails++ }; resetClimb() }
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
            if (dx < 5f && jumpH > 8f && s.floor == floor && s.fallTo < 0 && s !in cleared) { cleared += s; clearX = px; clearY = py - 24f; clearT = 0f; events += "over"; jumpsCleared++ }
        }
        if (floor == TOP) { if (arcade) { arcadeRound++; events += "ok"; resetClimb() } else go(Stage.EXECUTE) }
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
    /** He kneels, hands up; you walk up and raise the pistol (the music cuts); a held breath; the shot. You take his keys and walk to the door. */
    private fun executeTick() {
        gPose = if (t < SHOT_T) 4 else 5
        py = base(TOP); gFace = true
        when {
            t < GUN_T -> { px = min(70f, 46f + t * 30f); faceRight = false; walking = px < 70f }
            t < 4.3f -> { px = 70f; faceRight = false; walking = false }
            t < 4.9f -> { px = 70f - (t - 4.3f) / 0.6f * 26f; faceRight = false; walking = true }          // over to the body
            t < 5.7f -> { px = 44f; faceRight = false; walking = false }                                     // crouch, take the keys
            else -> { px = min(TOWER_DOOR, 44f + (t - 5.7f) * 40f); faceRight = true; walking = px < TOWER_DOOR }   // to the door
        }
    }
    val pistolUp get() = stage == Stage.EXECUTE && t > GUN_T && t < 4.3f
    val shotFlash get() = stage == Stage.EXECUTE && t in SHOT_T..SHOT_T + 0.15f
    /** You're crouched over him taking his keys. */
    val takingKeys get() = stage == Stage.EXECUTE && t in 4.9f..5.7f
    val hasKeys get() = stage == Stage.EXECUTE && t >= 5.3f

    // ---------------------------------------------------------------------------------------------------------------- simon
    private fun simonTick(dt: Float) {
        simonMsgT = max(0f, simonMsgT - dt)
        if (lit >= 0) { litT += dt; if (!showing && litT > 0.3f) lit = -1 }
        if (!showing) return
        showT += dt
        if (showT < 0f) { if (litT > 0.35f) lit = -1; return }      // let your last press stay lit for a moment
        val i = (showT / stepS).toInt()
        val within = showT - i * stepS
        if (i >= round) { showing = false; lit = -1; inputAt = 0; return }
        if (within < stepS * 0.7f) { if (lit != seq[i] || litT > stepS) { lit = seq[i]; litT = 0f; events += "simon${seq[i]}" } } else lit = -1
    }

    /** You press one of the four buttons (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right). */
    fun simonTap(b: Int) {
        if (stage != Stage.SIMON || showing || next != null || gameOver) return
        lit = b; litT = 0f
        if (seq[inputAt] != b) {
            events += "wrong"
            if (arcade) { gameOver = true; simonMsg = "SIGNAL LOST."; simonMsgT = 99f; return }
            simonFails++
            round = max(1, round - 1)
            simonMsg = "SIGNAL LOST. AGAIN, SLOWER."; simonMsgT = 1.6f
            showing = true; showT = -1.2f; inputAt = 0
            return
        }
        events += "simon$b"
        inputAt++
        if (inputAt >= round) {
            if (!arcade && round >= SIMON_GOAL) { simonMsg = "UPLINK ESTABLISHED."; simonMsgT = 2f; events += "ok"; go(Stage.BROADCAST); return }
            round++
            while (seq.size < round) seq += rnd.nextInt(4)
            showing = true; showT = -0.7f; inputAt = 0
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- words
    /** Who says what in the cutscenes (drawn as speech bubbles), and the captions at the bottom. */
    fun bubble(): Pair<String, String>? = when (stage) {   // (who, what): who = "boat", "patrol", "you"
        Stage.BEACH -> when {
            t in 5.0f..7.2f -> "boat" to "THE TOWER. PUT IT ON THE AIR."
            t in 8.6f..9.6f -> "patrol" to "!"
            t in 11.0f..12.6f -> "boat" to "GO! FAST!"
            else -> null
        }
        Stage.INTRO -> if (t in 4.7f..6.0f) "patrol" to "4 · 7 · 1 · 1" else if (t in 7.9f..9.2f) "patrol" to "COME ON UP." else null
        Stage.EXECUTE -> if (t in 0.4f..GUN_T + 0.4f) "patrol" to "WAIT! WAIT! PLEASE!" else null
        Stage.CLIMB -> if (hitT < 0f && t % 9f in 3f..5.2f) "patrol" to TAUNTS[(t / 9f).toInt() % TAUNTS.size] else null
        else -> null
    }
    fun caption(): String = when (stage) {
        Stage.BEACH -> if (t < 3.8f) "THE BOAT COMES IN UNDER THE TOWER." else ""
        Stage.CLIMB -> if (t < 5f) "CLIMB. JUMP THE SPOOLS." else ""
        Stage.DOOR -> ""
        Stage.CONTROL -> when { t < 4.5f -> ""; t < 9.5f -> ""; else -> "" }
        Stage.SIMON -> if (simonMsgT > 0f) simonMsg else if (showing) "WATCH..." else if (arcade) "REPEAT IT.  ROUND $round" else "REPEAT IT.  ${round.coerceAtMost(SIMON_GOAL)} / $SIMON_GOAL"
        Stage.BROADCAST -> PLEA.firstOrNull { t >= it.first && t < it.second }?.third ?: ""
        Stage.END -> ""
        else -> ""
    }
    /** Which shot of the plea is on screen: what it shows (see PLEA_SHOTS), seconds into it, and how long it lasts. */
    fun pleaShot(): Triple<Int, Float, Float> {
        val i = PLEA_SHOTS.indexOfLast { t >= it.first }.coerceAtLeast(0)
        val start = PLEA_SHOTS[i].first; val end = PLEA_SHOTS.getOrNull(i + 1)?.first ?: PLEA_S
        return Triple(PLEA_SHOTS[i].second, t - start, end - start)
    }
    /** The big red words on black right after the cut. */
    val redText get() = if (stage == Stage.END && t < RED_OFF) "I FUCKING TRI—" else ""
    /** How strong the red and blue in the window are (they close in as the plea goes on). */
    val sirens get() = when (stage) { Stage.BROADCAST -> (t / PLEA_S * 0.7f).coerceIn(0f, 0.7f); Stage.END -> 0.55f; else -> 0f }
    /** Black over the tower at the end: the cut, the fade back in, and the shot. */
    val blackout get() = if (stage != Stage.END) 0f else if (t < FADE_IN) 1f else if (t < FADE_IN + 1.6f) 1f - (t - FADE_IN) / 1.6f else if (t >= BLAM + 0.07f) 1f else 0f
}
