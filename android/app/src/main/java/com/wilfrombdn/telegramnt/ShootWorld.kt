package com.wilfrombdn.telegramnt

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The town hall shoot-out of Dead Letter level 4 (no Android code here, so tests can run it). Top-down, fast, one hit kills you
// and one hit kills them. Three rooms: the main hall, the hallway (with offices off it), and the mayor's office. The door out of a
// room stays locked until everyone in it is down. In the mayor's office there is nothing to shoot: he claps, twenty officers run
// in and take you down, your letters spill, and he picks them up.

/** Where somebody starts: officers and civilians are placed by these. */
internal class Spawn(val x: Float, val y: Float, val face: Float, val room: Int)

/** The doors (tiles) that stay locked until the room's officers are all down. */
internal class ShootRoom(val doors: List<Pair<Int, Int>>)

internal class ShootSpec(
    val map: List<String>,           // # wall, . floor, c pillar (stops bullets and sight), d desk (stops walking only), D locked door, o doorway, S you, H the hostage, M the mayor
    val officers: List<Spawn>,
    val civilians: List<Spawn>,
    val rooms: List<ShootRoom>,
    val finaleTriggerRow: Float,     // walking above this row (once the hallway is clear) starts the mayor scene
    val seed: Int = 11,
)

internal class ShootWorld(val spec: ShootSpec) {
    enum class Phase { INTRO, PLAY, FINALE, DEAD, DONE }

    class Npc(val id: Int, var x: Float, var y: Float, var face: Float, val civ: Boolean, val room: Int) {
        var state = IDLE
        var dead = false
        var react = 0f
        var cd = 0.6f
        var lastX = 0f; var lastY = 0f
        var lostT = 0f; var searchT = 0f; var repath = 0f
        var baseFace = face
        var flash = 0f
        var deadT = 0f
        var hostage = false
        val path = ArrayDeque<Pair<Int, Int>>()
        val crouched get() = civ && state == COWER
    }
    class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float, val mine: Boolean) { var life = 1.4f }

    val cols = spec.map[0].length
    val rows = spec.map.size
    private val grid = Array(rows) { r -> spec.map[r].toCharArray() }
    private val closed = HashSet<Pair<Int, Int>>()
    private val rnd = Random(spec.seed.toLong())

    var phase = Phase.INTRO
    var t = 0f; var phaseT = 0f
    var px = 0f; var py = 0f; var face = -PI.toFloat() / 2
    var fireCd = 0f; var muzzle = 0f
    var moving = false
    var startX = 0f; var startY = 0f
    var mayorX = 7.5f; var mayorY = 2.4f
    val npcs = ArrayList<Npc>()
    val bullets = ArrayList<Bullet>()
    val decals = ArrayList<Pair<Float, Float>>()
    val cleared = BooleanArray(spec.rooms.size)
    var clearedT = 0f                 // for the "ROOM CLEAR" message
    var kills = 0
    var hostage: Npc? = null
    private var shotTheHostage = false
    var flash = 0f                    // the white flash when the mayor kicks you
    var shake = 0f

    init {
        for (r in 0 until rows) for (c in 0 until cols) when (grid[r][c]) {
            'S' -> { startX = c + 0.5f; startY = r + 0.5f; grid[r][c] = '.' }
            'M' -> { mayorX = c + 0.5f; mayorY = r + 0.5f; grid[r][c] = '.' }
            'D' -> closed += c to r
        }
        px = startX; py = startY
        var id = 0
        for (o in spec.officers) npcs += Npc(id++, o.x, o.y, o.face, false, o.room).also { it.baseFace = o.face }
        for (o in spec.civilians) npcs += Npc(id++, o.x, o.y, o.face, true, o.room)
        // the hostage cop, held in front of you
        hostage = Npc(id++, startX, startY - 1.6f, -PI.toFloat() / 2, false, -1).also { it.hostage = true; npcs += it }
    }

    // ---------- the map ----------
    fun tile(c: Int, r: Int): Char = if (c in 0 until cols && r in 0 until rows) grid[r][c] else '#'
    fun doorClosed(c: Int, r: Int) = (c to r) in closed
    private fun solid(c: Int, r: Int): Boolean = when (tile(c, r)) { '#', ' ', 'c', 'd' -> true; 'D' -> doorClosed(c, r); else -> false }
    private fun blocksSight(c: Int, r: Int): Boolean = when (tile(c, r)) { '#', ' ', 'c' -> true; 'D' -> doorClosed(c, r); else -> false }
    private fun blocksBullet(c: Int, r: Int): Boolean = blocksSight(c, r)

    fun clearLine(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dx = x2 - x1; val dy = y2 - y1; val d = hypot(dx, dy)
        var s = 0.25f
        while (s < d) { if (blocksSight(floor(x1 + dx / d * s).toInt(), floor(y1 + dy / d * s).toInt())) return false; s += 0.2f }
        return true
    }

    private fun blockedAt(x: Float, y: Float, r: Float) =
        solid(floor(x - r).toInt(), floor(y - r).toInt()) || solid(floor(x + r).toInt(), floor(y - r).toInt()) ||
            solid(floor(x - r).toInt(), floor(y + r).toInt()) || solid(floor(x + r).toInt(), floor(y + r).toInt())

    private fun route(from: Pair<Int, Int>, to: Pair<Int, Int>): List<Pair<Int, Int>> {
        if (from == to) return emptyList()
        val prev = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val q = ArrayDeque<Pair<Int, Int>>(); q.add(from); prev[from] = from
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            if (cur == to) break
            for ((dx, dy) in D4) {
                val n = (cur.first + dx) to (cur.second + dy)
                if (n in prev || solid(n.first, n.second)) continue
                prev[n] = cur; q.add(n)
            }
        }
        if (to !in prev) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(); var c = to
        while (c != from) { out += c; c = prev[c]!! }
        out.reverse(); return out
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= 2 * PI.toFloat(); while (d < -PI) d += 2 * PI.toFloat()
        return d
    }

    // ---------- the game ----------
    /** Move with ([ix],[iy]) scaled by [mag]; aim with ([ax],[ay]) scaled by [amag] (firing when pushed out). */
    fun update(dt: Float, ix: Float, iy: Float, mag: Float, ax: Float, ay: Float, amag: Float) {
        t += dt; phaseT += dt
        muzzle = max(0f, muzzle - dt); clearedT = max(0f, clearedT - dt)
        flash = max(0f, flash - dt); shake = max(0f, shake - dt)
        fireCd = max(0f, fireCd - dt)
        when (phase) {
            Phase.INTRO -> intro(dt)
            Phase.PLAY -> play(dt, ix, iy, mag, ax, ay, amag)
            Phase.FINALE -> finale(dt)
            else -> {}
        }
        if (phase != Phase.FINALE) { npcsUpdate(dt); bulletsUpdate(dt) }
        for (n in npcs) { n.flash = max(0f, n.flash - dt); if (n.dead) n.deadT += dt }
    }

    // ----- the start: you walk a hostage in, and shoot him in front of everyone -----
    private fun intro(dt: Float) {
        face = -PI.toFloat() / 2
        if (t >= 0.9f && !shotTheHostage) {
            shotTheHostage = true
            val h = hostage!!
            bullets += Bullet(px, py - 0.4f, 0f, -14f, true)
            muzzle = 0.12f; flash = 0.12f
            h.dead = true; h.deadT = 0f; decals += h.x to h.y
            // everyone reacts: the officers aim, the others run
            for (n in npcs) if (!n.dead) {
                if (n.civ) { n.state = FLEE; flee(n) }
                else { n.state = ALERT; n.react = 0.45f + rnd.nextFloat() * 0.5f; n.lastX = px; n.lastY = py }
            }
        }
        if (t >= 1.6f) { phase = Phase.PLAY; phaseT = 0f }
    }

    private fun play(dt: Float, ix: Float, iy: Float, mag: Float, ax: Float, ay: Float, amag: Float) {
        // walking
        moving = mag > 0.05f
        if (moving) {
            val sp = 4.3f * mag * dt
            val nx = px + ix * sp; val ny = py + iy * sp
            if (!blockedAt(nx, py, 0.32f)) px = nx
            if (!blockedAt(px, ny, 0.32f)) py = ny
        }
        // aiming and firing
        if (amag > 0.3f) {
            face = atan2(ay, ax)
            if (fireCd <= 0f) fire(assist(face))
        } else if (moving) face = atan2(iy, ix)
        // rooms: clear them one at a time
        for ((i, room) in spec.rooms.withIndex()) {
            if (cleared[i]) continue
            if (npcs.none { !it.civ && !it.hostage && it.room == i && !it.dead }) {
                cleared[i] = true; clearedT = 2.4f
                for (d in room.doors) closed.remove(d)
            }
        }
        // the mayor's office
        if (cleared.size > 1 && cleared[1] && py < spec.finaleTriggerRow) { phase = Phase.FINALE; phaseT = 0f; ft = 0f; bullets.clear(); moving = false }
    }

    /** A little help: if an officer is nearly in line with where you aim, the shot goes at him. */
    private fun assist(angle: Float): Float {
        var best = angle; var bd = 0.17f
        for (n in npcs) {
            if (n.civ || n.dead || n.hostage) continue
            val a = atan2(n.y - py, n.x - px)
            val dd = abs(angleDiff(a, angle))
            if (dd < bd && clearLine(px, py, n.x, n.y)) { bd = dd; best = a }
        }
        return best
    }

    private fun fire(angle: Float) {
        fireCd = 0.2f; muzzle = 0.08f
        bullets += Bullet(px + cos(angle) * 0.5f, py + sin(angle) * 0.5f, cos(angle) * 14f, sin(angle) * 14f, true)
        // the shot is heard: officers nearby come to look
        for (n in npcs) if (!n.civ && !n.dead && !n.hostage && hypot(n.x - px, n.y - py) < 11f && (n.state == IDLE || n.state == SEARCH)) {
            n.state = ALERT; n.react = 0.35f + rnd.nextFloat() * 0.4f; n.lastX = px; n.lastY = py
        }
    }

    // ----- the officers and the people in the room -----
    private fun canSee(n: Npc): Boolean {
        val d = hypot(px - n.x, py - n.y)
        if (d > 10f) return false
        if (d > 1.8f && abs(angleDiff(atan2(py - n.y, px - n.x), n.face)) > 0.95f) return false
        return clearLine(n.x, n.y, px, py)
    }

    private fun flee(n: Npc) {
        val corners = listOf(1.5f to 24.5f, 14.5f to 24.5f, 1.5f to 43.5f, 14.5f to 43.5f)
        val far = corners.maxByOrNull { hypot(it.first - px, it.second - py) }!!
        n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), floor(far.first).toInt() to floor(far.second).toInt()))
    }

    private fun follow(n: Npc, dt: Float, speed: Float, turn: Boolean): Boolean {
        val tgt = n.path.firstOrNull() ?: return false
        val dx = tgt.first + 0.5f - n.x; val dy = tgt.second + 0.5f - n.y; val d = hypot(dx, dy)
        if (turn && d > 0.01f) n.face = turnToward(n.face, atan2(dy, dx), 8f * dt)
        val mv = min(speed * dt, d)
        if (d > 0.001f) { n.x += dx / d * mv; n.y += dy / d * mv }
        if (d - mv < 0.02f) n.path.removeFirst()
        return true
    }

    private fun turnToward(a: Float, target: Float, maxStep: Float): Float = a + angleDiff(target, a).coerceIn(-maxStep, maxStep)

    private fun npcsUpdate(dt: Float) {
        if (phase == Phase.DEAD) return
        for (n in npcs) {
            if (n.dead || n.hostage) continue
            if (n.civ) {
                if (n.state == FLEE) { if (!follow(n, dt, 3.4f, true)) n.state = COWER }
                continue
            }
            n.cd = max(0f, n.cd - dt)
            val see = phase != Phase.INTRO && canSee(n)
            when (n.state) {
                IDLE -> {
                    n.face = n.baseFace + 0.4f * sin(t * 0.7f + n.id)
                    if (see) { n.state = ALERT; n.react = 0.4f + rnd.nextFloat() * 0.3f; n.lastX = px; n.lastY = py }
                }
                ALERT -> {
                    n.face = turnToward(n.face, atan2(n.lastY - n.y, n.lastX - n.x), 9f * dt)
                    n.react -= dt
                    if (see) { n.lastX = px; n.lastY = py }
                    if (n.react <= 0f) { n.state = ENGAGE; n.lostT = 0f }
                }
                ENGAGE -> {
                    if (see) {
                        n.lostT = 0f; n.lastX = px; n.lastY = py
                        n.face = turnToward(n.face, atan2(py - n.y, px - n.x), 7f * dt)
                        val dist = hypot(px - n.x, py - n.y)
                        if (dist > 6.5f) {   // close the distance
                            n.repath -= dt
                            if (n.repath <= 0f || n.path.isEmpty()) { n.repath = 0.5f; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), floor(px).toInt() to floor(py).toInt())) }
                            follow(n, dt, 2.4f, false)
                        }
                        if (n.cd <= 0f && abs(angleDiff(n.face, atan2(py - n.y, px - n.x))) < 0.22f && phase == Phase.PLAY) {
                            val ang = atan2(py - n.y, px - n.x) + (rnd.nextFloat() - 0.5f) * 0.14f
                            bullets += Bullet(n.x + cos(ang) * 0.5f, n.y + sin(ang) * 0.5f, cos(ang) * 12f, sin(ang) * 12f, false)
                            n.cd = 0.85f + rnd.nextFloat() * 0.55f; n.flash = 0.1f
                        }
                    } else {
                        n.lostT += dt
                        if (n.lostT > 0.5f) { n.state = SEARCH; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), floor(n.lastX).toInt() to floor(n.lastY).toInt())); n.searchT = 0f }
                    }
                }
                SEARCH -> {
                    if (see) { n.state = ENGAGE; n.lostT = 0f; n.react = 0f }
                    else if (!follow(n, dt, 2.2f, true)) { n.searchT += dt; n.face += 1.4f * dt; if (n.searchT > 2.2f) { n.state = IDLE } }
                }
            }
        }
    }

    // ----- bullets -----
    private fun bulletsUpdate(dt: Float) {
        val it = bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            var steps = ((hypot(b.vx, b.vy) * dt) / 0.2f).toInt() + 1
            val sx = b.vx * dt / steps; val sy = b.vy * dt / steps
            var gone = false
            while (steps-- > 0 && !gone) {
                b.x += sx; b.y += sy
                if (blocksBullet(floor(b.x).toInt(), floor(b.y).toInt())) { gone = true; break }
                if (b.mine) {
                    for (n in npcs) if (!n.dead && !n.hostage && hypot(n.x - b.x, n.y - b.y) < 0.4f) {
                        n.dead = true; n.deadT = 0f; kills++; decals += n.x to n.y; gone = true
                        if (!n.civ) for (o in npcs) if (!o.civ && !o.dead && !o.hostage && o !== n && hypot(o.x - n.x, o.y - n.y) < 9f && (o.state == IDLE || o.state == SEARCH)) { o.state = ALERT; o.react = 0.3f + rnd.nextFloat() * 0.3f; o.lastX = px; o.lastY = py }
                        break
                    }
                } else if (phase == Phase.PLAY && hypot(px - b.x, py - b.y) < 0.36f) {
                    phase = Phase.DEAD; phaseT = 0f; gone = true; decals += px to py
                }
            }
            b.life -= dt
            if (gone || b.life <= 0f) it.remove()
        }
    }

    // ---------- the mayor's office ----------
    var ft = 0f
    val runnerCount = 20

    /** Where runner [i] starts (a doorway in the side wall). */
    fun runnerStart(i: Int): Pair<Float, Float> = (if (i % 2 == 0) 1.6f else cols - 1.6f) to (4.6f + (i / 2 % 5) * 0.7f)
    private fun runnerSlot(i: Int, radius: Float): Pair<Float, Float> {
        val a = i * 2f * PI.toFloat() / runnerCount
        return (finaleX + cos(a) * radius) to (finaleY + sin(a) * radius * 0.8f)
    }
    private var finaleX = 0f; private var finaleY = 0f
    private fun runnerT0(i: Int) = 1.4f + i * 0.07f
    private fun runnerDur(i: Int): Float { val s = runnerStart(i); val e = runnerSlot(i, 1.5f); return hypot(e.first - s.first, e.second - s.second) / 6.5f }
    /** Where runner [i] is right now (running in, then standing round you, then stepping back). */
    fun runnerPos(i: Int): Pair<Float, Float>? {
        if (ft < runnerT0(i)) return null
        val s = runnerStart(i); val e = runnerSlot(i, 1.5f)
        val k = ((ft - runnerT0(i)) / runnerDur(i)).coerceIn(0f, 1f)
        var x = s.first + (e.first - s.first) * k; var y = s.second + (e.second - s.second) * k
        if (ft > 6.0f) { val b = runnerSlot(i, 2.7f); val m = ((ft - 6.0f) / 0.8f).coerceIn(0f, 1f); x += (b.first - e.first) * m; y += (b.second - e.second) * m }
        return x to y
    }
    val playerDown get() = ft >= 3.2f
    val cuffed get() = ft >= 4.3f
    val mayorClapping get() = ft in 0.2f..3.0f
    /** The six letters spilled: where letter [i] is (they fly out of your coat from 4.8 s). */
    fun letterPos(i: Int): Triple<Float, Float, Boolean>? {
        if (ft < 4.8f || i >= 6) return null
        if (ft > 9.4f + i * 0.4f) return null                         // picked up
        val k = ((ft - 4.8f) / 0.9f).coerceIn(0f, 1f)
        val a = i * 1.05f + 0.4f; val r = 0.8f + (i % 3) * 0.45f
        val lift = sin(k * PI.toFloat()) * 0.9f
        return Triple(finaleX + cos(a) * r * k, finaleY + sin(a) * r * 0.7f * k - lift, k >= 1f)
    }
    /** 0 stand, 1 clap, 2 walk, 3 bend (picking up), 4 kick, 5 read */
    val mayorPose: Int get() = when {
        ft < 0.2f -> 0
        ft < 3.0f -> 1
        ft < 6.2f -> 0
        ft < arrive -> 2
        ft < 11.6f -> 3
        ft < 12.4f -> 4
        else -> 5
    }
    private val arrive get() = 9.0f
    var mayorFaceX = 0f; var mayorFaceY = 1f
    val finaleDone get() = ft >= 15.4f
    val fade get() = ((ft - 14.0f) / 1.4f).coerceIn(0f, 1f)

    private fun finale(dt: Float) {
        ft += dt
        if (ft - dt <= 0f) { finaleX = px; finaleY = py; mayorFaceX = px - mayorX; mayorFaceY = py - mayorY }
        // the mayor walks over to you
        if (ft >= 6.2f && ft < arrive) {
            val k = ((ft - 6.2f) / (arrive - 6.2f)).coerceIn(0f, 1f)
            val tx = finaleX; val ty = finaleY - 1.0f
            mayorX += (tx - mayorX) * min(1f, dt / max(0.05f, (1f - k) * (arrive - 6.2f) + dt))
            mayorY += (ty - mayorY) * min(1f, dt / max(0.05f, (1f - k) * (arrive - 6.2f) + dt))
        }
        if (ft >= 12.1f && ft - dt < 12.1f) { flash = 0.25f; shake = 0.5f }
        if (finaleDone) phase = Phase.DONE
    }

    companion object {
        const val IDLE = 0; const val ALERT = 1; const val ENGAGE = 2; const val SEARCH = 3; const val FLEE = 4; const val COWER = 5
        val D4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
