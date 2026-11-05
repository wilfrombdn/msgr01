package com.wilfrombdn.telegramnt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The rules of Dead Letter (no Android code here, so tests can run it). A top-down stealth game: sneak through a dark
// city past guards, cameras and lasers to your contact. Light and loud steps get you spotted; once a guard locks on,
// he chases you, and only a contact with a gun (anyone but the priest) can stop a chase.

/**
 * A patrolling guard. He walks between the waypoints (column, row). At each one he stops for [pauses] (or [pause]) and, if
 * [faces] has an angle in degrees for it, stares that way (an officer checking behind a bin); otherwise he looks around.
 */
internal class GuardSpec(
    val path: List<Pair<Int, Int>>, val speed: Float = 1.5f, val pause: Float = 1.0f, val range: Float = 4.2f, val half: Float = 0.55f,
    val faces: List<Float?> = emptyList(), val pauses: List<Float> = emptyList(),
)
internal class CamSpec(val x: Float, val y: Float, val baseDeg: Float, val sweepDeg: Float, val period: Float, val phase: Float = 0f, val range: Float = 4.6f, val half: Float = 0.5f)
internal class LaserSpec(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val on: Float, val off: Float, val phase: Float = 0f)
internal class Palette(val floor: Int, val floorLine: Int, val wall: Int, val wallTop: Int, val accent: Int, val lamp: Int, val cone: Int)

/** Something to look at in the world (a poster, a radio, a police car...). With [text] it becomes a clue: walk close to read it. */
internal class Prop(val kind: String, val x: Float, val y: Float, val w: Int = 1, val text: String? = null, val rot: Float = 0f)

internal class LevelSpec(
    val building: String,        // the place, e.g. "The Backstreets"
    val objective: String,       // "Deliver letter I to the newspaper vendor."
    val situation: String,       // one line about how hot the city is
    val tip: String,             // how to play, shown on a first try
    val contact: String,         // who you are delivering to
    val line: String,            // what they say when you hand it over
    val reveal: String,          // what the letter reveals
    val heat: Int,               // 0..5: how hunted you are (drives alert label, colours, music speed)
    val pal: Palette, val map: List<String>,
    val guards: List<GuardSpec> = emptyList(), val cams: List<CamSpec> = emptyList(), val lasers: List<LaserSpec> = emptyList(),
    val props: List<Prop> = emptyList(),
    val armed: Boolean = true,   // does the contact shoot the guards chasing you? (the priest won't)
    val cover: String = "cabinet",   // what the tall cover blocks look like: "cabinet" or "bin"
    val pixel: Boolean = false,      // draw this level as night-time pixel art (see DeadDropPixel.kt)
    val lead: LeadSpec? = null,      // a walking level where the contact leads you to a manhole
    val worker: WorkerSpec? = null,  // a walking level where a worker takes your letter and points you to a ladder
    val pac: PacSpec? = null,        // a maze (played like Pac-Man) that follows the walk
    val room: LevelSpec? = null,     // the walking scene that follows the maze
    val theme: String = "street",    // "street" or "sewer"
    val pacTip: String = "",         // how to play the maze
    val walk: Boolean = false,       // a walking story: nobody can catch you, things just happen as you pass
    val scene: SceneSpec? = null,    // the scripted moment for a walking level
    val flashers: List<Pair<Float, Float>> = emptyList(),   // flashing red and blue lights (police cars out of sight)
    val beams: List<CamSpec> = emptyList(),                 // torch beams sweeping about (for show; they do not see you)
)

/** The contact walks ahead of you to a manhole, flips the lid open, says farewell, and waits while you go down. */
internal class LeadSpec(val path: List<Pair<Float, Float>>, val manholeX: Float, val manholeY: Float, val farewell: String, val speaker: String = "NEWSPAPER VENDOR")

/** A worker wrenching on a pipe: he turns, lights a cigarette, takes your letter, says [line] and points to the ladder. */
internal class WorkerSpec(val ladderX: Float, val ladderY: Float, val line: String, val speaker: String = "MAINTENANCE WORKER")

/**
 * The scripted moment of a walking level: a cop stands at a fork, hidden in fog. When you get past [triggerY] the fog clears,
 * he hesitates (dots, one at a time), turns around, then walks away along [exit].
 */
internal class SceneSpec(val copX: Float, val copY: Float, val triggerY: Float, val fog: IntArray, val exit: List<Pair<Float, Float>>, val contactLine: String = "")

/** Something that can see: a guard or a camera at one moment. */
internal class Eye(val x: Float, val y: Float, val face: Float, val half: Float, val range: Float)

internal class StealthWorld(val spec: LevelSpec, private val speedMul: Float = 1f, val ghost: Boolean = false) {
    enum class State { PLAY, CAUGHT, WON }

    class Key(val x: Float, val y: Float, val ch: Char, var taken: Boolean = false)
    class Foot(val x: Float, val y: Float, var age: Float)
    /** A shot from the contact's gun, kept for a moment so it can be drawn. */
    class Shot(val x1: Float, val y1: Float, val x2: Float, val y2: Float, var age: Float = 0f)

    val cols = spec.map[0].length
    val rows = spec.map.size
    private val grid = Array(rows) { r -> spec.map[r].toCharArray() }
    val lamps = ArrayList<Pair<Float, Float>>()
    val keys = ArrayList<Key>()
    var startX = 1.5f; var startY = 1.5f
    var contactX = 1.5f; var contactY = 1.5f
    val opened = HashSet<Char>()      // doors that are open ('D', 'E')
    val held = HashSet<Char>()        // keys in my pocket ('k', 'j')

    var state = State.PLAY
    var t = 0f
    var px = 0f; var py = 0f; var face = -PI.toFloat() / 2
    var speed = 0f                    // tiles per second right now
    var noise = 0f                    // how far my steps carry, in tiles
    var spotter = ""                  // who caught me
    var stateT = 0f
    var wonDelay = 0f                 // after winning: how long the contact takes to deal with anyone still chasing me
    val feet = ArrayList<Foot>()
    val shots = ArrayList<Shot>()
    private var stepDist = 0f
    private var shotCd = 0f
    private var alarmCd = 0f
    var contactAim = 0f               // angle the contact is pointing his gun, when he is
    var contactAiming = false

    init {
        for (r in 0 until rows) for (c in 0 until cols) when (grid[r][c]) {
            'S' -> { startX = c + 0.5f; startY = r + 0.5f; grid[r][c] = '.' }
            'C' -> { contactX = c + 0.5f; contactY = r + 0.5f; grid[r][c] = '.' }
            'L' -> { lamps += (c + 0.5f) to (r + 0.5f); grid[r][c] = '.' }
            'k', 'j' -> { keys += Key(c + 0.5f, r + 0.5f, grid[r][c]); grid[r][c] = '.' }
        }
        px = startX; py = startY
    }

    // ---------- the map ----------
    fun tile(c: Int, r: Int): Char = if (c in 0 until cols && r in 0 until rows) grid[r][c] else '#'
    fun solidAt(c: Int, r: Int): Boolean = when (val ch = tile(c, r)) { '#', ' ', 'd', 'c' -> true; 'D', 'E' -> ch !in opened; else -> false }
    fun blocksSight(c: Int, r: Int): Boolean = when (val ch = tile(c, r)) { '#', ' ', 'c' -> true; 'D', 'E' -> ch !in opened; else -> false }
    private fun blocksSightAt(x: Float, y: Float) = blocksSight(floor(x).toInt(), floor(y).toInt())

    private fun blockedAt(x: Float, y: Float): Boolean {
        val r = PR
        return solidAt(floor(x - r).toInt(), floor(y - r).toInt()) || solidAt(floor(x + r).toInt(), floor(y - r).toInt()) ||
            solidAt(floor(x - r).toInt(), floor(y + r).toInt()) || solidAt(floor(x + r).toInt(), floor(y + r).toInt())
    }

    /** Is there a clear line of sight between two points (walls and cover in the way)? */
    fun lineClear(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dx = x2 - x1; val dy = y2 - y1; val d = hypot(dx, dy)
        var s = 0.3f
        while (s < d) { if (blocksSightAt(x1 + dx / d * s, y1 + dy / d * s)) return false; s += 0.12f }
        return true
    }

    /** Can [eye] see the point (x, y)? Needs a clear line through the walls, being inside the cone, and being close enough. */
    fun sees(e: Eye, x: Float, y: Float): Boolean {
        val dx = x - e.x; val dy = y - e.y
        val d = hypot(dx, dy)
        if (d > e.range) return false
        if (d > NEAR) {
            var diff = atan2(dy, dx) - e.face
            while (diff > PI) diff -= 2 * PI.toFloat(); while (diff < -PI) diff += 2 * PI.toFloat()
            if (abs(diff) > e.half) return false
        }
        return lineClear(e.x, e.y, x, y)
    }

    /** How far a ray from [e] along [ang] gets before a wall (for drawing the cone). */
    fun rayLength(e: Eye, ang: Float): Float {
        var s = 0.2f
        val cx = cos(ang); val sy = sin(ang)
        while (s < e.range) { if (blocksSightAt(e.x + cx * s, e.y + sy * s)) return s; s += 0.08f }
        return e.range
    }

    fun lightAt(x: Float, y: Float): Float = if (lamps.any { hypot(it.first - x, it.second - y) < LAMP_R }) 1f else 0.45f

    // ---------- walking routes (guards) ----------
    fun route(from: Pair<Int, Int>, to: Pair<Int, Int>): List<Pair<Int, Int>> {
        if (from == to) return emptyList()
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val q = ArrayDeque<Pair<Int, Int>>(); q.add(from); prev[from] = from
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            if (cur == to) break
            for ((dx, dy) in DIRS4) {
                val n = (cur.first + dx) to (cur.second + dy)
                if (n in prev || solidAt(n.first, n.second)) continue
                prev[n] = cur; q.add(n)
            }
        }
        if (to !in prev) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var c = to
        while (c != from) { out += c; c = prev[c]!! }
        out.reverse()
        return out
    }

    // ---------- guards, cameras, lasers ----------
    inner class Guard(val spec: GuardSpec) {
        var x = spec.path[0].first + 0.5f
        var y = spec.path[0].second + 0.5f
        var face = 0f
        var meter = 0f
        var mode = PATROL
        var wp = if (spec.path.size > 1) 1 else 0
        val path = ArrayDeque<Pair<Int, Int>>()
        var pause = 0f
        var scanT = 0f
        var baseFace = 0f
        var scanAmp = 0.75f
        var hearCool = 0f
        var pauseKind = PATROL
        var seenNow = false            // did he see me on the last step?
        var body = false               // true once I have silenced him: he lies where he fell and others can find him
        var discovered = false         // has somebody found that body?
        var alertT = 0f                // on edge after a body was found: sees wider and further, walks faster
        var lastX = 0f; var lastY = 0f // where he last saw me
        var loseT = 0f
        var repath = 0f
        val dead get() = mode == DEAD
        val chasing get() = mode == CHASE

        init {
            if (spec.path.size > 1) {
                path.addAll(route(spec.path[0], spec.path[1]))
                path.firstOrNull()?.let { face = atan2(it.second + 0.5f - y, it.first + 0.5f - x) }
            } else { pause = spec.pause; baseFace = face }
        }

        fun eye() = if (alertT > 0f) Eye(x, y, face, spec.half + 0.2f, spec.range + 1.2f) else Eye(x, y, face, spec.half, spec.range)
        private fun tileOf() = floor(x).toInt() to floor(y).toInt()

        private fun turn(target: Float, dt: Float, rate: Float) {
            var diff = target - face
            while (diff > PI) diff -= 2 * PI.toFloat(); while (diff < -PI) diff += 2 * PI.toFloat()
            val m = rate * dt
            face += diff.coerceIn(-m, m)
        }

        fun lockOn() {
            mode = CHASE; lastX = px; lastY = py; loseT = 0f; repath = 0f; pause = 0f; path.clear()
        }

        fun hear(): Boolean {
            if (ghost || noise <= 0f || hearCool > 0f) return false
            var eff = hypot(px - x, py - y)
            if (eff >= noise) return false
            // walls muffle sound: each wall between us counts as extra distance
            var s = 0.3f; var walls = 0; var inWall = false
            val d = hypot(px - x, py - y)
            while (s < d) {
                val w = solidAt(floor(x + (px - x) / d * s).toInt(), floor(y + (py - y) / d * s).toInt())
                if (w && !inWall) walls++
                inWall = w; s += 0.25f
            }
            eff += walls * 1.6f
            return eff < noise
        }

        /** Walk along [path]; returns true if he moved this step. */
        private fun follow(dt: Float, sp: Float, turnRate: Float): Boolean {
            if (path.isEmpty()) return false
            val tgt = path.first()
            val tx = tgt.first + 0.5f; val ty = tgt.second + 0.5f
            val dx = tx - x; val dy = ty - y
            val d = hypot(dx, dy)
            if (d > 0.01f) turn(atan2(dy, dx), dt, turnRate)
            val mv = min(sp * dt, d)
            if (d > 0.001f) { x += dx / d * mv; y += dy / d * mv }
            if (d - mv < 0.02f) path.removeFirst()
            return true
        }

        fun step(dt: Float) {
            if (dead) return
            hearCool -= dt; alertT = max(0f, alertT - dt)
            if (mode == CHASE) {
                if (seenNow) { lastX = px; lastY = py; loseT = 0f } else loseT += dt
                if (loseT > 5f) { mode = INVESTIGATE; meter = 0f; path.clear(); path.addAll(route(tileOf(), floor(lastX).toInt() to floor(lastY).toInt())); return }
                repath -= dt
                if (repath <= 0f || path.isEmpty()) {
                    repath = 0.25f
                    path.clear(); path.addAll(route(tileOf(), floor(lastX).toInt() to floor(lastY).toInt()))
                }
                if (path.isEmpty()) turn(atan2(lastY - y, lastX - x), dt, 8f)
                follow(dt, max(CHASE_SPEED, spec.speed * 1.9f) * speedMul, 9f)
                return
            }
            if (meter > 0.25f) { turn(atan2(py - y, px - x), dt, 6f); return }   // something's off: stop and stare
            if (hear()) {
                mode = INVESTIGATE; hearCool = 0.7f; pause = 0f
                path.clear(); path.addAll(route(tileOf(), floor(px).toInt() to floor(py).toInt()))
            }
            if (pause > 0f) {
                pause -= dt; scanT += dt
                turn(baseFace + sin(scanT * 2.4f) * scanAmp, dt, 5f)
                if (pause <= 0f) endPause()
                return
            }
            if (follow(dt, spec.speed * speedMul * (if (mode == INVESTIGATE) 1.5f else 1f) * (if (alertT > 0f) 1.25f else 1f), 7f)) return
            // arrived somewhere
            when (mode) {
                PATROL -> {
                    val look = spec.faces.getOrNull(wp)
                    pause = spec.pauses.getOrNull(wp) ?: spec.pause
                    scanT = 0f; pauseKind = PATROL
                    if (look != null) { baseFace = look * PI.toFloat() / 180f; scanAmp = 0.3f } else { baseFace = face; scanAmp = 0.75f }
                }
                INVESTIGATE -> { mode = LOOK; pause = 1.8f; baseFace = face; scanAmp = 0.75f; scanT = 0f; pauseKind = LOOK }
                else -> { mode = PATROL; path.addAll(route(tileOf(), spec.path[wp])) }
            }
        }

        private fun endPause() {
            if (pauseKind == LOOK) { mode = PATROL; path.addAll(route(tileOf(), spec.path[wp])); return }
            if (spec.path.size > 1) { wp = (wp + 1) % spec.path.size; path.addAll(route(tileOf(), spec.path[wp])) } else { pause = spec.pause; baseFace = face }
        }
    }

    inner class Cam(val spec: CamSpec) {
        var meter = 0f
        fun angle(tt: Float) = (spec.baseDeg + spec.sweepDeg * sin(2 * PI.toFloat() * tt / spec.period + spec.phase)) * PI.toFloat() / 180f
        fun eye() = Eye(spec.x, spec.y, angle(t), spec.half, spec.range)
    }

    inner class Laser(val spec: LaserSpec) {
        fun on(tt: Float) = ((tt + spec.phase) % (spec.on + spec.off)) < spec.on
        /** True in the last moments before it switches on (drawn flickering as a warning). */
        fun warning(tt: Float): Boolean { val ph = (tt + spec.phase) % (spec.on + spec.off); return ph >= spec.on + spec.off - 0.4f }
        fun near(x: Float, y: Float, r: Float): Boolean {
            val ax = spec.x1; val ay = spec.y1; val bx = spec.x2; val by = spec.y2
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val u = if (l2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
            return hypot(x - (ax + u * dx), y - (ay + u * dy)) < r
        }
    }

    /** A person in a scripted scene (the cop at the fork). */
    class Actor(var x: Float, var y: Float, var face: Float) {
        var dots = 0                 // how many dots of "..." are showing over his head
        var torch = true             // is his torch on
        var visible = true
        var walking = false
        var headDown = false         // eyes on the ground
    }
    val cop: Actor? = spec.scene?.let { Actor(it.copX, it.copY, PI.toFloat() / 2) }   // he starts facing south: straight down the passage at you
    var fog = if (spec.scene != null) 1f else 0f     // 1 = the fogged part of the map is still hidden
    var sceneStage = 0
    private var sceneT = 0f
    private var exitIdx = 0

    // the contact in a walking scene: watches the cop leave, comes out to meet me, waits, and says something when I get close
    var contactFace = PI.toFloat() / 2
    var contactWalking = false
    var sayText: String? = null
    /** I'm being walked into place by a scripted moment (the drawing shows my legs moving). */
    var autoWalk = false
    var sayT = 0f
    private val contactPath = ArrayDeque<Pair<Int, Int>>()
    private var contactTravel = 0f
    private var contactRepath = 0f
    /** Which step of crossing himself the contact is on (0 forehead, 1 chest, 2 left shoulder, 3 right shoulder), or -1. */
    val crossFrame: Int get() { if (sayText == null) return -1; val u = sayT - 1.8f; return if (u < 0f || u > 2.8f) -1 else (u / 0.7f).toInt().coerceIn(0, 3) }

    // ---------- the walk to the manhole (level 2, first part) ----------
    var lidOpen = 0f                 // 0 shut .. 1 open
    var enteredManhole = false
    var leadStage = 0
    private var leadIdx = 0
    private var leadT = 0f
    private val leadPath = ArrayDeque<Pair<Int, Int>>()
    private var leadPathFor = -1

    /** The contact leads you to a manhole, opens it, says farewell and crosses himself, then waits for you to go down. */
    private fun leadUpdate(dt: Float, l: LeadSpec) {
        leadT += dt
        if (sayText != null) sayT += dt
        when (leadStage) {
            0 -> {
                val tgt = l.path.getOrNull(leadIdx)
                val near = hypot(px - contactX, py - contactY) < 5.5f
                contactWalking = false
                if (tgt == null) { leadStage = 1; leadT = 0f; return }
                if (leadPathFor != leadIdx) {
                    leadPathFor = leadIdx; leadPath.clear()
                    leadPath.addAll(route(floor(contactX).toInt() to floor(contactY).toInt(), floor(tgt.first).toInt() to floor(tgt.second).toInt()))
                }
                if (!near) { turnContact(atan2(py - contactY, px - contactX), dt, 3f); return }   // he waits for me, watching
                val step = leadPath.firstOrNull()
                if (step == null) { leadIdx++; return }   // this waypoint is reached: on to the next
                val dx = step.first + 0.5f - contactX; val dy = step.second + 0.5f - contactY; val d = hypot(dx, dy)
                turnContact(atan2(dy, dx), dt, 6f)
                val mv = min(2.3f * dt, d)
                if (d > 0.001f) { contactX += dx / d * mv; contactY += dy / d * mv }
                if (d - mv < 0.02f) leadPath.removeFirst()
                contactWalking = true
            }
            1 -> {   // at the manhole: he flips the lid open
                contactWalking = false
                turnContact(atan2(l.manholeY - contactY, l.manholeX - contactX), dt, 4f)
                lidOpen = (lidOpen + dt / 1.6f).coerceAtMost(1f)
                if (lidOpen >= 1f) { leadStage = 4; leadT = 0f }
            }
            4 -> {   // lid open: he turns to face the way you're coming and waits for you to walk up
                turnContact(PI.toFloat() / 2, dt, 4f)
                if (hypot(px - contactX, py - contactY) < 2.6f) { leadStage = 5; leadT = 0f }
            }
            5 -> {   // you step up in front of him and face him (he faces you, and the screen), then he speaks and crosses himself
                turnContact(PI.toFloat() / 2, dt, 6f)
                val tx = contactX; val ty = contactY + 1.25f
                val dx = tx - px; val dy = ty - py; val d = hypot(dx, dy)
                if (d > 0.05f) { val mv = min(2.2f * dt, d); px += dx / d * mv; py += dy / d * mv; face = atan2(dy, dx); autoWalk = true }
                else { px = tx; py = ty; face = -PI.toFloat() / 2; autoWalk = false; leadStage = 2; leadT = 0f; sayText = l.farewell; sayT = 0f }
            }
            2 -> { turnContact(PI.toFloat() / 2, dt, 6f); face = -PI.toFloat() / 2; if (sayT > 5.4f) { leadStage = 3; leadT = 0f } }
            3 -> { turnContact(atan2(py - contactY, px - contactX), dt, 3f); if (hypot(px - l.manholeX, py - l.manholeY) < 0.75f) { enteredManhole = true; sayText = null } }
        }
    }

    // ---------- the maintenance room (level 2, last part) ----------
    var workerStage = 0
    var workerPose = 0               // 0 none, 1 wrenching on the pipe, 2 smoking, 3 pointing
    var workerPointDir = 3           // which way he points (UP/LEFT/DOWN/RIGHT)
    var letterGiven = false
    private var workerT = 0f

    /** He wrenches on a pipe, turns, lights a cigarette, takes your letter, says his piece, and points to the ladder. */
    private fun workerUpdate(dt: Float, wk: WorkerSpec) {
        workerT += dt
        if (sayText != null) sayT += dt
        when (workerStage) {
            0 -> { workerPose = 1; contactFace = -PI.toFloat() / 2; if (hypot(px - contactX, py - contactY) < 7f) { workerStage = 1; workerT = 0f } }
            1 -> { workerPose = 0; turnContact(atan2(py - contactY, px - contactX), dt, 1.6f); if (workerT > 1.4f) { workerStage = 2; workerT = 0f } }
            2 -> { workerPose = 2; turnContact(atan2(py - contactY, px - contactX), dt, 3f); if (workerT > 2.6f) { workerStage = 3; workerT = 0f } }
            3 -> {
                workerPose = 2; turnContact(atan2(py - contactY, px - contactX), dt, 3f)
                if (hypot(px - contactX, py - contactY) < 2.0f) { letterGiven = true; sayText = wk.line; sayT = 0f; workerStage = 4; workerT = 0f }
            }
            4 -> { workerPose = 2; turnContact(atan2(py - contactY, px - contactX), dt, 3f); if (workerT > 2.4f) { workerStage = 5; workerT = 0f } }
            5 -> {
                workerPose = 3; workerPointDir = if (wk.ladderX >= contactX) PacWorld.RIGHT else PacWorld.LEFT
                contactFace = PI.toFloat() / 2
                if (hypot(px - wk.ladderX, py - wk.ladderY) < 0.9f) { state = State.WON; stateT = 0f; wonDelay = 0f }
            }
        }
    }

    private fun turnTo(a: Actor, target: Float, dt: Float, rate: Float): Boolean {
        var diff = target - a.face
        while (diff > PI) diff -= 2 * PI.toFloat(); while (diff < -PI) diff += 2 * PI.toFloat()
        val m = rate * dt
        a.face += diff.coerceIn(-m, m)
        return abs(diff) <= m
    }

    private fun turnContact(target: Float, dt: Float, rate: Float) {
        var diff = target - contactFace
        while (diff > PI) diff -= 2 * PI.toFloat(); while (diff < -PI) diff += 2 * PI.toFloat()
        val m = rate * dt
        contactFace += diff.coerceIn(-m, m)
    }

    /**
     * The cop at the fork waits in the fog already looking down the passage at me. When the fog clears he says "...", lowers his
     * head and his torch, and walks slowly out of the street to the left. The contact watches him go, then watches me, comes
     * out to meet me, waits, and (when I get close) says something and crosses himself.
     */
    private fun sceneUpdate(dt: Float) {
        spec.lead?.let { leadUpdate(dt, it); return }
        spec.worker?.let { workerUpdate(dt, it); return }
        val s = spec.scene ?: return
        val a = cop ?: return
        sceneT += dt
        if (sayText != null) sayT += dt
        when (sceneStage) {
            0 -> { a.face = PI.toFloat() / 2 + 0.05f * sin(t * 0.7f); if (py < s.triggerY) { sceneStage = 1; sceneT = 0f } }     // I rounded the corner
            1 -> { fog = (1f - sceneT / 2.5f).coerceIn(0f, 1f); if (sceneT > 3.2f) { sceneStage = 2; sceneT = 0f } }              // the fog clears
            2 -> { a.dots = min(3, 1 + (sceneT / 1.3f).toInt()); if (sceneT > 4.4f) { sceneStage = 3; sceneT = 0f } }              // . .. ...  one at a time
            3 -> { a.dots = 0; a.headDown = true; a.torch = false; if (sceneT > 1.5f) { sceneStage = 4; sceneT = 0f } }            // his head goes down
            4 -> {
                val tgt = s.exit.firstOrNull()
                val ang = if (tgt != null) atan2(tgt.second - a.y, tgt.first - a.x) else PI.toFloat()
                if (turnTo(a, ang, dt, 1.2f)) { sceneStage = 5; sceneT = 0f; a.walking = true }
            }
            5 -> {
                val tgt = s.exit.getOrNull(exitIdx)
                if (tgt == null) { a.visible = false; a.walking = false; sceneStage = 6; sceneT = 0f } else {
                    val dx = tgt.first - a.x; val dy = tgt.second - a.y; val d = hypot(dx, dy)
                    a.face = atan2(dy, dx)
                    val mv = min(0.85f * dt, d)                                                                                   // slowly
                    if (d > 0.001f) { a.x += dx / d * mv; a.y += dy / d * mv }
                    if (d - mv < 0.05f) exitIdx++
                }
            }
            6 -> {   // the contact comes out from behind his stand toward me, but only part of the way
                contactRepath -= dt
                if (contactTravel < CONTACT_WALK && hypot(px - contactX, py - contactY) > 3f) {
                    if (contactRepath <= 0f || contactPath.isEmpty()) {
                        contactRepath = 0.4f
                        contactPath.clear(); contactPath.addAll(route(floor(contactX).toInt() to floor(contactY).toInt(), floor(px).toInt() to floor(py).toInt()))
                    }
                    val tgt = contactPath.firstOrNull()
                    if (tgt != null) {
                        val dx = tgt.first + 0.5f - contactX; val dy = tgt.second + 0.5f - contactY; val d = hypot(dx, dy)
                        turnContact(atan2(dy, dx), dt, 6f)
                        val mv = min(1.5f * dt, d)
                        if (d > 0.001f) { contactX += dx / d * mv; contactY += dy / d * mv; contactTravel += mv }
                        if (d - mv < 0.02f) contactPath.removeFirst()
                        contactWalking = true
                    } else contactWalking = false
                } else { contactWalking = false; sceneStage = 7; sceneT = 0f }
            }
            7 -> {   // he waits for me, watching me come, and speaks when I am near
                contactWalking = false
                if (sayText == null && hypot(px - contactX, py - contactY) < 6.5f) { sayText = s.contactLine; sayT = 0f }
            }
        }
        // while the cop leaves, the contact watches him; after that, he watches me
        if (sceneStage in 3..5 && a.visible) turnContact(atan2(a.y - contactY, a.x - contactX), dt, 3f)
        else if (sceneStage >= 6 && !contactWalking) turnContact(atan2(py - contactY, px - contactX), dt, 3f)
    }

    val guards = spec.guards.map { Guard(it) }
    val cams = spec.cams.map { Cam(it) }
    val lasers = spec.lasers.map { Laser(it) }

    /** Everything that can see right now (used by the level-checking test). Guards who have been shot see nothing. */
    fun eyes(): List<Eye> = guards.filter { !it.dead }.map { it.eye() } + cams.map { it.eye() }

    /** Highest "being noticed" value, 0..1 (drives the red edge glow). */
    fun alert(): Float = max(guards.filter { !it.dead }.maxOfOrNull { it.meter } ?: 0f, cams.maxOfOrNull { it.meter } ?: 0f)

    /** How many guards are locked onto me right now. */
    fun chasers(): Int = guards.count { it.chasing }

    var bodyFoundT = 0f               // counts down after somebody finds a body (for the warning on screen)

    /**
     * The guard I could silence right now: close, behind him (outside the direction he faces), in a clear line, and he hasn't
     * noticed me. Null if there isn't one.
     */
    fun takedownTarget(): Guard? {
        if (state != State.PLAY) return null
        var best: Guard? = null; var bd = TAKEDOWN_REACH
        for (g in guards) {
            if (g.dead || g.chasing || g.meter > 0.2f) continue
            val dx = px - g.x; val dy = py - g.y
            val d = hypot(dx, dy)
            if (d >= bd) continue
            var diff = atan2(dy, dx) - g.face
            while (diff > PI) diff -= 2 * PI.toFloat(); while (diff < -PI) diff += 2 * PI.toFloat()
            if (abs(diff) < 2.2f) continue                        // he is facing me, or nearly
            if (!lineClear(g.x, g.y, px, py)) continue
            best = g; bd = d
        }
        return best
    }

    /** I drew the symbol: he goes down without a sound, and lies there until someone finds him. */
    fun takedown(g: Guard) {
        g.mode = DEAD; g.body = true; g.discovered = false; g.meter = 0f; g.path.clear()
    }

    /** I fumbled it: he knows something is wrong. */
    fun failTakedown(g: Guard) {
        if (g.dead) return
        g.meter = 0.85f
    }

    /** A guard (or a camera) saw a body: everyone near goes to look, and they are on edge for a while. */
    private fun bodyDiscovered(b: Guard) {
        b.discovered = true; bodyFoundT = 4f
        val bt = floor(b.x).toInt() to floor(b.y).toInt()
        for (g in guards) {
            if (g.dead || g.chasing) continue
            if (hypot(g.x - b.x, g.y - b.y) > BODY_RADIO) continue
            g.alertT = 30f; g.mode = INVESTIGATE; g.pause = 0f; g.hearCool = 1f
            g.path.clear(); g.path.addAll(route(floor(g.x).toInt() to floor(g.y).toInt(), bt))
        }
    }

    private fun checkBodies() {
        for (b in guards) {
            if (!b.body || b.discovered) continue
            val finder = guards.any { !it.dead && !it.chasing && sees(it.eye(), b.x, b.y) } || cams.any { sees(it.eye(), b.x, b.y) }
            if (finder) bodyDiscovered(b)
        }
    }

    /** An alarm (a camera or a laser): every guard knows where I am and comes running. */
    private fun alarm() {
        if (alarmCd > 0f) return
        alarmCd = 2f
        for (g in guards) if (!g.dead && !g.chasing) { g.lockOn(); g.meter = 0.6f }
    }

    /** The contact (if armed) shoots guards who are chasing me, once I'm close to him (or have reached him). */
    private fun contactShoot(dt: Float) {
        contactAiming = false
        if (!spec.armed) return
        val chasing = guards.filter { it.chasing }
        if (chasing.isEmpty()) { shotCd = max(shotCd, 0.15f); return }
        val inZone = state == State.WON || (hypot(px - contactX, py - contactY) < ZONE && lineClear(contactX, contactY, px, py))
        if (!inZone) return
        val tgt = chasing.minByOrNull { hypot(it.x - contactX, it.y - contactY) } ?: return
        contactAim = atan2(tgt.y - contactY, tgt.x - contactX); contactAiming = true
        shotCd -= dt
        if (shotCd <= 0f) {
            tgt.mode = DEAD; tgt.meter = 0f; tgt.path.clear()
            shots += Shot(contactX, contactY, tgt.x, tgt.y)
            shotCd = if (state == State.WON) 0.4f else 0.6f
        }
    }

    // ---------- one step of the game ----------
    fun update(dt: Float, ix: Float, iy: Float, mag: Float) {
        stateT += dt
        for (f in feet) f.age += dt
        feet.removeAll { it.age > 2.2f }
        for (s in shots) s.age += dt
        shots.removeAll { it.age > 0.6f }
        if (state == State.WON) { contactShoot(dt); speed = 0f; noise = 0f; return }
        if (state != State.PLAY) { speed = 0f; noise = 0f; return }
        t += dt
        alarmCd -= dt
        bodyFoundT = max(0f, bodyFoundT - dt)

        // my feet: a gentle drag is a quiet sneak, a full drag is a loud run
        val frozen = (spec.scene != null && sceneStage in 1..6) || (spec.lead != null && leadStage in setOf(2, 5))   // scripted moments: I can't walk
        speed = if (frozen) 0f else if (mag < 0.5f) SNEAK * (mag / 0.5f) else SNEAK + (mag - 0.5f) / 0.5f * (RUN - SNEAK)
        noise = if (speed > QUIET) (speed - QUIET) / (RUN - QUIET) * NOISE_MAX else 0f
        if (speed > 0.01f && mag > 0.02f) {
            face = atan2(iy, ix)
            val mv = speed * dt
            val nx = px + ix * mv; val ny = py + iy * mv
            if (!blockedAt(nx, py)) px = nx
            if (!blockedAt(px, ny)) py = ny
            stepDist += mv
            if (stepDist > 0.55f && !ghost) { stepDist = 0f; feet += Foot(px, py, 0f) }
        }

        for (g in guards) g.step(dt)

        // pick up keys, open doors
        for (k in keys) if (!k.taken && hypot(px - k.x, py - k.y) < 0.55f) { k.taken = true; held += k.ch }
        for (k in held.toList()) {
            val door = if (k == 'k') 'D' else 'E'
            var near = false
            for (r in 0 until rows) for (c in 0 until cols) if (grid[r][c] == door && hypot(px - (c + 0.5f), py - (r + 0.5f)) < 1.05f) near = true
            if (near) { opened += door; held -= k }
        }

        if (spec.walk) {   // a walking story: nobody can catch you
            sceneUpdate(dt)
            if (spec.lead == null && spec.worker == null && hypot(px - contactX, py - contactY) < 0.85f) { state = State.WON; stateT = 0f; wonDelay = 0f }
            return
        }

        if (ghost) return

        // being seen: a guard who has watched me long enough locks on and chases; his radio calls the ones nearby
        val lf = lightAt(px, py)
        for (g in guards) {
            if (g.dead) continue
            val seen = sees(g.eye(), px, py)
            g.seenNow = seen
            g.meter = (g.meter + (if (seen) DETECT * lf * dt else -RECOVER * dt)).coerceIn(0f, 1f)
            if (g.meter >= 1f && !g.chasing) {
                g.lockOn()
                for (o in guards) if (!o.dead && !o.chasing && hypot(o.x - g.x, o.y - g.y) < RADIO) { o.lockOn(); o.meter = 0.6f }
            }
        }
        for (c in cams) {
            val seen = sees(c.eye(), px, py)
            c.meter = (c.meter + (if (seen) DETECT * lf * dt else -RECOVER * dt)).coerceIn(0f, 1f)
            if (c.meter >= 1f) { c.meter = 0.35f; alarm() }
        }
        for (l in lasers) if (l.on(t) && l.near(px, py, PR + 0.07f)) alarm()

        checkBodies()
        // the contact helps, if he can
        contactShoot(dt)

        // a guard who gets hold of me ends it
        for (g in guards) if (g.chasing && hypot(g.x - px, g.y - py) < CATCH) { state = State.CAUGHT; spotter = "A guard caught you."; stateT = 0f }

        if (state == State.PLAY && hypot(px - contactX, py - contactY) < 0.85f) {
            state = State.WON; stateT = 0f
            wonDelay = if (spec.armed && chasers() > 0) 0.6f + 0.4f * chasers() else 0f
            shotCd = 0.2f
        }
    }

    companion object {
        const val PR = 0.27f            // my size
        const val NEAR = 0.9f           // a guard notices you this close even from the side
        const val LAMP_R = 2.1f
        const val SNEAK = 1.5f
        const val RUN = 3.7f
        const val QUIET = 2.0f
        const val NOISE_MAX = 3.4f
        const val DETECT = 2.4f
        const val RECOVER = 1.1f
        const val CHASE_SPEED = 3.4f
        const val CATCH = 0.55f
        const val RADIO = 6f            // a guard who locks on calls others this close
        const val CONTACT_WALK = 6.5f       // how far the contact walks out to meet me in a walking scene
        const val TAKEDOWN_REACH = 1.25f   // how close behind a guard I must be to silence him
        const val BODY_RADIO = 14f         // who goes to look when a body is found
        const val ZONE = 7f             // how far from his contact you can be and still be covered
        const val PATROL = 0; const val INVESTIGATE = 1; const val LOOK = 2; const val CHASE = 3; const val DEAD = 4
        val DIRS4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
