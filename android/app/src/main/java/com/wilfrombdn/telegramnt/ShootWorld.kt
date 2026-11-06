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
import kotlin.math.sqrt

// The town hall of Dead Letter level 4 (no Android code here, so tests can run it). Top-down, Hotline Miami style, one hit kills you
// and one hit kills them.
//   1. INTRO: you walk a few steps into the lobby with a cop as your hostage. Everything stops and every officer aims at you. After a second
//      and a half one of them shoots the hostage. You throw up the nearest table and dive behind it, and the fight starts.
//   2. PLAY: the joystick walks, the gun button fires at the closest officer you can see, the grenade button throws at the biggest group in
//      range, and a swipe with a second finger while you move rolls. Low furniture is cover: a bullet that passes over it on its way to
//      someone standing right behind it hits the furniture instead 60-90% of the time. Clear the lobby (the clerks run out), then the hall
//      and its six rooms, then kick in the mayor's door.
//   3. FINALE: the mayor is crazed. Twenty officers run in and pin you down, he kicks you in the head and laughs. Fade to black.

/** Where somebody starts: [kind] 0 is an officer, 1 is SWAT. */
internal class Spawn(val x: Float, val y: Float, val face: Float, val room: Int, val kind: Int = 0)

internal class ShootSpec(
    val map: List<String>,
    val officers: List<Spawn>,
    val civilians: List<Spawn>,
    val hallDoors: List<Pair<Int, Int>>,      // (col, row) doors that open when the lobby is clear
    val mayorDoors: List<Pair<Int, Int>>,     // (col, row) doors you kick open once everything else is clear
    val exit: Pair<Float, Float>,             // where the clerks run out
    val seed: Int = 11,
)

internal class ShootWorld(val spec: ShootSpec) {
    enum class Phase { INTRO, PLAY, FINALE, DEAD, DONE }


    class Npc(val id: Int, var x: Float, var y: Float, var face: Float, val civ: Boolean, val room: Int, val swat: Boolean) {
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
        var gone = false                       // a clerk who has run out of the building
        var spot: Pair<Int, Int>? = null       // the cover this officer is using
        var spotT = 0f
        var crouch = false; var crouchT = 0f
        var burst = 0
        val path = ArrayDeque<Pair<Int, Int>>()
        val crouched get() = (civ && state == COWER) || (!civ && crouch)
        val homeX = x; val homeY = y
        /** Most of them hold their ground: they take cover near where they were posted and wait for you to come to them. */
        val camper = id % 10 < 7
    }
    class Bullet(var x: Float, var y: Float, val vx: Float, val vy: Float, val mine: Boolean, val harmless: Boolean = false) {
        var life = 1.6f
        var travel = 0f
        var cover = 0f; var coverP = 0f      // it has just flown over furniture: for [cover] tiles it may be soaked up by it
    }
    class Grenade(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val flight: Float) { var t = 0f; val fuse get() = flight + 0.7f }
    class Spark(val x: Float, val y: Float) { var life = 0.25f }
    class Blast(val x: Float, val y: Float) { var life = 0.55f }

    val cols = spec.map[0].length
    val rows = spec.map.size
    private val grid = Array(rows) { r -> spec.map[r].toCharArray() }
    private val closed = HashSet<Pair<Int, Int>>()
    val tipped = HashSet<Pair<Int, Int>>()          // tables you have thrown on their side
    private val rnd = Random(spec.seed.toLong())

    var phase = Phase.INTRO
    var t = 0f; var phaseT = 0f
    var px = 0f; var py = 0f; var face = -PI.toFloat() / 2
    var fireCd = 0f; var muzzle = 0f
    var moving = false
    var startX = 0f; var startY = 0f
    var mayorX = 7.5f; var mayorY = 5f
    val npcs = ArrayList<Npc>()
    val bullets = ArrayList<Bullet>()
    val grenadesAir = ArrayList<Grenade>()
    val sparks = ArrayList<Spark>()
    val blasts = ArrayList<Blast>()
    val decals = ArrayList<Pair<Float, Float>>()
    val scorch = ArrayList<Pair<Float, Float>>()
    val cleared = BooleanArray(8)
    var hostage: Npc? = null
    var flash = 0f
    var shake = 0f
    var kills = 0
    var innocents = 0                  // clerks who died
    var grenades = 5
    var grenCd = 0f
    fun grCd() = grenCd
    /** Seconds since you last fired or threw: pressed to a wall without doing either, you're out of sight. */
    var sinceFire = 9f; private set
    /** Right up against a wall or something solid. */
    val nearWall: Boolean get() {
        val c0 = floor(px).toInt(); val r0 = floor(py).toInt()
        for (r in r0 - 1..r0 + 1) for (c in c0 - 1..c0 + 1) {
            if (!solid(c, r)) continue
            val nx = px.coerceIn(c.toFloat(), c + 1f); val ny = py.coerceIn(r.toFloat(), r + 1f)
            if (hypot(px - nx, py - ny) < 0.62f) return true
        }
        return false
    }
    /** Hiding: against a wall, not shooting or throwing. Nothing can hit you. */
    val hidden get() = phase == Phase.PLAY && nearWall && sinceFire > 0.6f
    /** Where a grenade would land while you're dragging from the grenade button (null when you aren't). */
    var aimAt: Pair<Float, Float>? = null; private set
    var rollT = 0f; var rollCd = 0f; private var rdx = 0f; private var rdy = 0f
    var diving = false
    var msg = ""; var msgT = 0f; var msgGood = true
    var evacuating = false
    var mayorUnlocked = false
    var lobbyClear = false
    private var aimedAll = false
    private var shotTheHostage = false
    private var diveFromX = 0f; private var diveFromY = 0f; private var diveToX = 0f; private var diveToY = 0f
    var tableHit: Pair<Int, Int>? = null
    var shooterId = -1

    init {
        for (r in 0 until rows) for (c in 0 until cols) when (grid[r][c]) {
            'S' -> { startX = c + 0.5f; startY = r + 0.5f; grid[r][c] = '.' }
            'M' -> { mayorX = c + 0.5f; mayorY = r + 0.5f; grid[r][c] = '.' }
            'D' -> closed += c to r
        }
        px = startX; py = startY
        var id = 0
        for (o in spec.officers) npcs += Npc(id++, o.x, o.y, o.face, false, o.room, o.kind == 1).also { it.baseFace = o.face }
        for (o in spec.civilians) npcs += Npc(id++, o.x, o.y, o.face, true, o.room, false).also { it.baseFace = o.face }
        hostage = Npc(id++, startX, startY - 1.6f, -PI.toFloat() / 2, false, -1, false).also { it.hostage = true; npcs += it }
    }

    fun roomsClear() = (0..6).count { cleared[it] }
    fun showMsg(s: String, good: Boolean = true) { msg = s; msgT = max(2.6f, readTime(s, 999f)); msgGood = good }

    // ---------- the map ----------
    fun tile(c: Int, r: Int): Char = if (c in 0 until cols && r in 0 until rows) grid[r][c] else '#'
    fun doorClosed(c: Int, r: Int) = (c to r) in closed
    fun isTipped(c: Int, r: Int) = (c to r) in tipped
    private fun solid(c: Int, r: Int): Boolean = when (tile(c, r)) { '#', ' ', 'c', 'u', 'r', 'd', 't', 'f', 'k', 'y', 'x' -> true; 'D' -> doorClosed(c, r); else -> false }
    private fun blocksSight(c: Int, r: Int): Boolean = when (tile(c, r)) { '#', ' ', 'c', 'u', 'r' -> true; 'D' -> doorClosed(c, r); else -> false }
    private fun blocksBullet(c: Int, r: Int): Boolean = blocksSight(c, r)
    /** 0 if you can't hide behind it, otherwise the chance (0.6-0.92) that a bullet crossing it is stopped by it. */
    fun coverOf(c: Int, r: Int): Float {
        if ((c to r) in tipped) return 0.92f
        return when (tile(c, r)) { 't' -> 0.85f; 'd' -> 0.8f; 'y' -> 0.8f; 'x' -> 0.85f; 'f' -> 0.65f; 'k' -> 0.6f; else -> 0f }
    }
    private fun isCoverTile(c: Int, r: Int) = coverOf(c, r) > 0f

    fun clearLine(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val dx = x2 - x1; val dy = y2 - y1; val d = hypot(dx, dy)
        var s = 0.25f
        while (s < d) { if (blocksSight(floor(x1 + dx / d * s).toInt(), floor(y1 + dy / d * s).toInt())) return false; s += 0.2f }
        return true
    }
    /** Can the player see this tile (walls and pillars block, and so do closed doors). Used for the fog. */
    fun visibleTile(c: Int, r: Int): Boolean {
        val tx = c + 0.5f; val ty = r + 0.5f
        val dx = tx - px; val dy = ty - py; val d = hypot(dx, dy)
        if (d < 0.8f) return true
        if (d > 15f) return false
        var s = 0.3f
        while (s < d - 0.55f) { if (blocksSight(floor(px + dx / d * s).toInt(), floor(py + dy / d * s).toInt())) return false; s += 0.25f }
        return true
    }
    fun canSeeNpc(n: Npc) = hypot(n.x - px, n.y - py) < 15f && clearLine(px, py, n.x, n.y)

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
    private fun turnToward(a: Float, target: Float, maxStep: Float): Float = a + angleDiff(target, a).coerceIn(-maxStep, maxStep)

    // ---------- the game ----------
    /** Move with ([ix],[iy]) scaled by [mag]; [fireHeld] means the gun button is down. */
    fun update(dt: Float, ix: Float, iy: Float, mag: Float, fireHeld: Boolean) {
        t += dt; phaseT += dt
        muzzle = max(0f, muzzle - dt); msgT = max(0f, msgT - dt)
        flash = max(0f, flash - dt); shake = max(0f, shake - dt)
        fireCd = max(0f, fireCd - dt); grenCd = max(0f, grenCd - dt); rollCd = max(0f, rollCd - dt)
        when (phase) {
            Phase.INTRO -> intro(dt)
            Phase.PLAY -> play(dt, ix, iy, mag, fireHeld)
            Phase.FINALE -> finale(dt)
            else -> {}
        }
        if (phase == Phase.PLAY || phase == Phase.INTRO) npcsUpdate(dt)
        if (phase != Phase.FINALE && phase != Phase.DONE) { bulletsUpdate(dt); grenadesUpdate(dt) }
        sparks.removeAll { it.life -= dt; it.life <= 0f }
        blasts.removeAll { it.life -= dt; it.life <= 0f }
        for (n in npcs) { n.flash = max(0f, n.flash - dt); if (n.dead) n.deadT += dt }
    }

    // ----- the ambush at the start -----
    private fun intro(dt: Float) {
        val h = hostage!!
        face = -PI.toFloat() / 2
        val walk = 3f * (t / 1.3f).coerceIn(0f, 1f)
        if (!shotTheHostage) { py = startY - walk; h.y = startY - 1.6f - walk }
        moving = t < 1.3f
        if (t >= 1.3f && !aimedAll) {
            aimedAll = true
            for (n in npcs) if (!n.dead && !n.hostage) { if (n.civ) n.state = COWER else { n.state = AIMED; n.lastX = px; n.lastY = py } }
        }
        if (t >= 1.3f) for (n in npcs) if (!n.civ && !n.dead && !n.hostage) n.face = turnToward(n.face, atan2(py - n.y, px - n.x), 6f * dt)
        if (t >= SHOT_T && !shotTheHostage) {
            shotTheHostage = true
            // the officer nearest to him with a clear shot takes it
            val sh = npcs.filter { !it.civ && !it.dead && !it.hostage }.minByOrNull { hypot(it.x - h.x, it.y - h.y) }
            if (sh != null) { sh.flash = 0.14f; shooterId = sh.id; sh.face = atan2(h.y - sh.y, h.x - sh.x) }
            h.dead = true; h.deadT = 0f; decals += h.x to h.y; decals += (h.x - 0.4f) to (h.y - 0.3f); decals += (h.x + 0.3f) to (h.y - 0.5f)
            flash = 0.18f; shake = 0.45f; events += "shot"
            if (sh != null) bullets += Bullet(sh.x, sh.y, cos(sh.face) * 20f, sin(sh.face) * 20f, false, true)
            // you throw up the nearest table and dive behind it
            diveFromX = px; diveFromY = py
            val tb = nearestTable()
            if (tb != null) {
                tableHit = tb                                              // you'll kick it over when you get there
                val spot = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).map { (tb.first + it.first) to (tb.second + it.second) }
                    .filter { !solid(it.first, it.second) }.minByOrNull { hypot(it.first + 0.5f - px, it.second + 0.5f - py) }
                if (spot != null) { diveToX = spot.first + 0.5f; diveToY = spot.second + 0.5f } else { diveToX = px; diveToY = py }
            } else { diveToX = px; diveToY = py }
        }
        if (shotTheHostage) {
            // you run to the table in front of you, kick it over, and get down behind it
            val k = ((t - SHOT_T - 0.2f) / 0.55f).coerceIn(0f, 1f)
            px = diveFromX + (diveToX - diveFromX) * k; py = diveFromY + (diveToY - diveFromY) * k
            moving = k in 0.01f..0.99f
            val tb = tableHit
            if (tb != null) face = atan2(tb.second + 0.5f - py, tb.first + 0.5f - px)
            if (t >= SHOT_T + 0.8f && tb != null && tb !in tipped) { tipped += tb; shake = 0.3f; events += "kick" }
            kickingTable = t in SHOT_T + 0.75f..SHOT_T + 1.05f
            diving = t >= SHOT_T + 1.05f
            if (t >= SHOT_T + 1.6f) {
                diving = false; kickingTable = false; phase = Phase.PLAY; phaseT = 0f; moving = false
                for (n in npcs) if (!n.civ && !n.dead && !n.hostage) { n.state = ENGAGE; n.react = 0f; n.cd = 0.25f + rnd.nextFloat() * 0.7f; n.lastX = px; n.lastY = py; n.spotT = 0f }
            }
        }
    }

    /** What the hostage screams as you walk him in (cut off by the shot). */
    fun hostageLine(): Pair<String, Int>? = if (phase == Phase.INTRO && t in 1.3f..SHOT_T) HOSTAGE_LINE to ((t - 1.3f) * 17f).toInt().coerceIn(1, HOSTAGE_LINE.length) else null
    /** The clerks, as you walk in. */
    fun civLine(n: Npc): String? = if (phase == Phase.INTRO && n.civ && t in 0.3f + (n.id % 4) * 0.25f..2.9f) CIV_LINES[n.id % CIV_LINES.size] else null
    var kickingTable = false; private set
    /** Sounds for the view to play. */
    val events = ArrayList<String>()

    private fun nearestTable(): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null; var bd = 99f
        for (r in 0 until rows) for (c in 0 until cols) {
            val ch = tile(c, r)
            if (ch != 't' && ch != 'd') continue
            val d = hypot(c + 0.5f - px, r + 0.5f - py)
            if (d < bd) { bd = d; best = c to r }
        }
        return best
    }

    private fun play(dt: Float, ix: Float, iy: Float, mag: Float, fireHeld: Boolean) {
        sinceFire += dt
        if (fireHeld) sinceFire = 0f
        // walking, or rolling
        if (rollT > 0f) {
            rollT = max(0f, rollT - dt); moving = true
            val sp = 7.6f * dt
            val nx = px + rdx * sp; val ny = py + rdy * sp
            if (!blockedAt(nx, py, 0.3f)) px = nx
            if (!blockedAt(px, ny, 0.3f)) py = ny
        } else {
            moving = mag > 0.05f
            if (moving) {
                val sp = 4.4f * mag * dt
                val nx = px + ix * sp; val ny = py + iy * sp
                if (!blockedAt(nx, py, 0.3f)) px = nx
                if (!blockedAt(px, ny, 0.3f)) py = ny
            }
            val tgt = nearestTarget()
            if (fireHeld && tgt != null) {
                face = atan2(tgt.y - py, tgt.x - px)
                if (fireCd <= 0f) shoot(face)
            } else if (moving) face = atan2(iy, ix)
        }
        checkClears()
        if (mayorUnlocked && px in 6.0f..10.0f && py < 12.7f) { startFinale() }
    }

    /** The officer the gun button would shoot at: the closest one you can see. */
    fun nearestTarget(): Npc? {
        var best: Npc? = null; var bd = 14f
        for (n in npcs) {
            if (n.civ || n.dead || n.hostage || n.gone) continue
            val d = hypot(n.x - px, n.y - py)
            if (d < bd && clearLine(px, py, n.x, n.y)) { bd = d; best = n }
        }
        return best
    }

    private fun shoot(angle: Float) {
        fireCd = 0.17f; muzzle = 0.08f
        val a = angle + (rnd.nextFloat() - 0.5f) * (if (moving) 0.13f else 0.06f)
        bullets += Bullet(px + cos(a) * 0.5f, py + sin(a) * 0.5f, cos(a) * 16f, sin(a) * 16f, true)
        alertNear(px, py, 11f)
    }

    private fun alertNear(x: Float, y: Float, rad: Float) {
        for (n in npcs) if (!n.civ && !n.dead && !n.hostage && (n.state == IDLE || n.state == SEARCH)) {
            val d = hypot(n.x - x, n.y - y)
            if (d < rad && (d < 4.5f || clearLine(n.x, n.y, x, y))) { n.state = ALERT; n.react = 0.3f + rnd.nextFloat() * 0.4f; n.lastX = px; n.lastY = py }
        }
    }

    /** Swipe with a second finger while you move: a quick roll that bullets pass over. */
    fun roll(dx: Float, dy: Float) {
        if (phase != Phase.PLAY || rollT > 0f || rollCd > 0f) return
        val l = hypot(dx, dy); if (l < 0.01f) return
        rdx = dx / l; rdy = dy / l; rollT = 0.42f; rollCd = 0.95f; face = atan2(rdy, rdx)
    }
    private val rolling get() = rollT > 0f
    val invulnerable get() = rolling || diving

    // ----- grenades -----
    companion object {
        const val SHOT_T = 3.9f
        const val HOSTAGE_LINE = "PLEASE HELP ME, HE'S CRAZY! PLEASE HELP ME-"
        val CIV_LINES = listOf("OH MY GOD!", "IT'S HIM!", "HE'S GOT A GUN!", "OH GOD...", "GET DOWN!", "NO, NO, NO!", "SOMEBODY HELP!")
        // the mayor's end: the song plays from the door until he's looked at you; a second of silence; the kick
        const val MUSIC_CUT = 17.8f
        const val KICK = 18.8f
        const val PAPER_AT = 12.0f      // he stops at one of your papers on the floor
        const val PAPER_UP = 12.6f      // ...has it in his hand, reads it, chuckles
        const val PAPER_AWAY = 14.6f    // ...folds it into his jacket and comes on
        const val IDLE = 0; const val ALERT = 1; const val ENGAGE = 2; const val SEARCH = 3; const val FLEE = 4; const val COWER = 5; const val AIMED = 6; const val EVAC = 7
        val D4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        const val THROW_MAX = 10.5f; const val THROW_MIN = 3.0f; const val BLAST = 2.5f
    }

    /** While dragging from the grenade button: [k] is how far you've dragged (0..1), (dx, dy) which way. Shows where it would land. */
    fun aimGrenade(dx: Float, dy: Float, k: Float) {
        if (phase != Phase.PLAY) { aimAt = null; return }
        val l = hypot(dx, dy); if (l < 0.01f) { aimAt = null; return }
        val dist = THROW_MIN + (THROW_MAX - THROW_MIN) * k.coerceIn(0f, 1f)
        aimAt = landing(px + dx / l * dist, py + dy / l * dist)
    }
    fun cancelAim() { aimAt = null }
    /** Let go: the grenade is lobbed where you aimed. Returns why not, or null if it was thrown. */
    fun throwAimed(): String? {
        val at = aimAt ?: return "DRAG TO AIM"; aimAt = null
        if (phase != Phase.PLAY) return "NOT NOW"
        if (grenades <= 0) return "NO GRENADES"
        if (grenCd > 0f) return "WAIT"
        val dist = hypot(at.first - px, at.second - py)
        grenades--; grenCd = 1.1f; face = atan2(at.second - py, at.first - px); muzzle = 0.1f; sinceFire = 0f
        grenadesAir += Grenade(px, py, at.first, at.second, 0.35f + dist * 0.045f)
        return null
    }
    /** The lob comes down short of a wall in its way. */
    private fun landing(tx: Float, ty: Float): Pair<Float, Float> {
        val d = hypot(tx - px, ty - py); var s = 0.4f; var lx = px; var ly = py
        while (s <= d) { val x = px + (tx - px) / d * s; val y = py + (ty - py) / d * s; if (blocksSight(floor(x).toInt(), floor(y).toInt())) break; lx = x; ly = y; s += 0.2f }
        return lx to ly
    }

    /** Throw at the biggest group of officers you can see that is in range. Returns why not, or null if it was thrown. */
    fun throwGrenade(): String? {
        if (phase != Phase.PLAY) return "NOT NOW"
        if (grenades <= 0) return "NO GRENADES"
        if (grenCd > 0f) return "WAIT"
        val vis = npcs.filter { !it.civ && !it.dead && !it.hostage && !it.gone && hypot(it.x - px, it.y - py) < 15f && clearLine(px, py, it.x, it.y) }
        if (vis.isEmpty()) return "NO TARGET"
        var bestN = 0; var bestScore = -1e9f; var bx = 0f; var by = 0f
        var farAny = false; var nearAny = false
        for (seed in vis) {
            val grp = vis.filter { hypot(it.x - seed.x, it.y - seed.y) < BLAST - 0.2f }
            val cx = grp.map { it.x }.average().toFloat(); val cy = grp.map { it.y }.average().toFloat()
            val d = hypot(cx - px, cy - py)
            if (d > THROW_MAX) { farAny = true; continue }
            if (d < THROW_MIN) { nearAny = true; continue }
            val score = grp.size * 100f - d
            if (score > bestScore) { bestScore = score; bestN = grp.size; bx = cx; by = cy }
        }
        if (bestN == 0) return if (farAny) "OUT OF RANGE" else if (nearAny) "TOO CLOSE" else "NO TARGET"
        val dist = hypot(bx - px, by - py)
        // farther throws are less accurate
        val ang = rnd.nextFloat() * 2f * PI.toFloat(); val rad = sqrt(rnd.nextFloat()) * 0.07f * dist
        var lx = bx + cos(ang) * rad; var ly = by + sin(ang) * rad
        var guard = 0
        while (solid(floor(lx).toInt(), floor(ly).toInt()) && guard++ < 30) { lx += (px - lx) * 0.08f; ly += (py - ly) * 0.08f }
        grenades--; grenCd = 1.1f; face = atan2(by - py, bx - px); muzzle = 0.1f
        grenadesAir += Grenade(px, py, lx, ly, 0.35f + dist * 0.045f)
        return null
    }

    private fun grenadesUpdate(dt: Float) {
        val it = grenadesAir.iterator()
        while (it.hasNext()) {
            val g = it.next(); g.t += dt
            if (g.t >= g.fuse) { explode(g.x1, g.y1); it.remove() }
        }
    }
    /** Where a grenade is now, and how high it is (0..1). */
    fun grenadePos(g: Grenade): Triple<Float, Float, Float> {
        val k = (g.t / g.flight).coerceIn(0f, 1f)
        return Triple(g.x0 + (g.x1 - g.x0) * k, g.y0 + (g.y1 - g.y0) * k, if (g.t >= g.flight) 0f else sin(k * PI.toFloat()))
    }

    /** True if low furniture sits between (x1,y1) and a point near (x2,y2), so something there is partly sheltered. */
    private fun shelteredFrom(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val d = hypot(x2 - x1, y2 - y1); if (d < 0.5f) return 0f
        var s = max(0.2f, d - 1.5f); var best = 0f
        while (s < d) { best = max(best, coverOf(floor(x1 + (x2 - x1) / d * s).toInt(), floor(y1 + (y2 - y1) / d * s).toInt())); s += 0.2f }
        return best
    }

    private fun explode(x: Float, y: Float) {
        blasts += Blast(x, y); scorch += x to y; flash = 0.16f; shake = 0.5f
        for (n in npcs) {
            if (n.dead || n.hostage || n.gone) continue
            if (hypot(n.x - x, n.y - y) > BLAST || !clearLine(x, y, n.x, n.y)) continue
            if (rnd.nextFloat() < shelteredFrom(x, y, n.x, n.y) * 0.6f) continue
            kill(n)
        }
        if (phase == Phase.PLAY && !invulnerable && hypot(px - x, py - y) < BLAST && clearLine(x, y, px, py) && rnd.nextFloat() >= shelteredFrom(x, y, px, py) * 0.6f) die()
        alertNear(x, y, 13f)
        for (n in npcs) if (!n.civ && !n.dead && !n.hostage && n.state != ENGAGE) { if (hypot(n.x - x, n.y - y) < 13f) { n.state = ALERT; n.react = 0.2f; n.lastX = px; n.lastY = py } }
    }

    private fun kill(n: Npc) {
        n.dead = true; n.deadT = 0f; decals += n.x to n.y
        if (n.civ) innocents++ else kills++
    }
    private fun die() { phase = Phase.DEAD; phaseT = 0f; decals += px to py; shake = 0.4f }

    private fun checkClears() {
        for (i in 0..7) {
            if (cleared[i]) continue
            if (npcs.none { !it.civ && !it.hostage && it.room == i && !it.dead }) {
                cleared[i] = true
                if (i == ShootLevel.LOBBY) {
                    lobbyClear = true; for (d in spec.hallDoors) closed.remove(d)
                    evacuate(); showMsg("LOBBY CLEAR. THE CLERKS ARE RUNNING OUT.")
                } else if (i == ShootLevel.HALL) showMsg("HALL CLEAR. ${roomsClear()} OF 7 AREAS DONE.")
                else showMsg("ROOM CLEAR. ${roomsClear()} OF 7 AREAS DONE.")
            }
        }
        if (!mayorUnlocked && (0..6).all { cleared[it] }) { mayorUnlocked = true; showMsg("ALL CLEAR. GO KICK IN THE MAYOR'S DOOR.") }
    }

    private fun evacuate() {
        evacuating = true
        val ex = floor(spec.exit.first).toInt() to floor(spec.exit.second).toInt()
        for (n in npcs) if (n.civ && !n.dead) {
            n.state = EVAC; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), ex))
            if (n.path.isEmpty()) n.gone = true
        }
    }

    // ----- the officers and the people in the room -----
    private fun canSee(n: Npc): Boolean {
        val d = hypot(px - n.x, py - n.y)
        if (d > 11f) return false
        if (n.state != ENGAGE && d > 1.8f && abs(angleDiff(atan2(py - n.y, px - n.x), n.face)) > 0.95f) return false
        return clearLine(n.x, n.y, px, py)
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

    /** A spot next to low furniture or a pillar, between the officer and you, from which he can still see you. */
    private fun pickCover(n: Npc): Pair<Int, Int>? {
        val nc = floor(if (n.camper) n.homeX else n.x).toInt(); val nr = floor(if (n.camper) n.homeY else n.y).toInt()
        val reach = if (n.camper) 3 else 6
        var best: Pair<Int, Int>? = null; var bs = 1e9f
        for (r in nr - reach..nr + reach) for (c in nc - reach..nc + reach) {
            if (solid(c, r)) continue
            val sx = c + 0.5f; val sy = r + 0.5f
            val dp = hypot(px - sx, py - sy)
            if ((dp < 2.8f && !n.camper) || dp > 13f) continue
            var ok = false
            for ((dx, dy) in D4) {
                val cc = c + dx; val rr = r + dy
                if (!isCoverTile(cc, rr)) continue
                val dc = hypot(px - (cc + 0.5f), py - (rr + 0.5f))
                val dot = (dx * (px - sx) + dy * (py - sy)) / dp
                if (dc < dp && dot > 0.55f) { ok = true; break }
            }
            if (!ok || !clearLine(sx, sy, px, py)) continue
            val score = hypot(sx - n.x, sy - n.y) + 0.25f * abs(dp - 6f)
            if (score < bs) { bs = score; best = c to r }
        }
        return best
    }

    private fun npcsUpdate(dt: Float) {
        for (n in npcs) {
            if (n.dead || n.hostage || n.gone) continue
            if (n.civ) {
                if (n.state == EVAC) {
                    if (!follow(n, dt, 3.8f, true)) n.gone = true
                }
                continue
            }
            if (n.state == AIMED) continue
            n.cd = max(0f, n.cd - dt)
            val see = phase != Phase.INTRO && canSee(n)
            when (n.state) {
                IDLE -> {
                    n.face = n.baseFace + 0.4f * sin(t * 0.7f + n.id)
                    if (see) { n.state = ALERT; n.react = 0.35f + rnd.nextFloat() * 0.3f; n.lastX = px; n.lastY = py }
                }
                ALERT -> {
                    n.face = turnToward(n.face, atan2(n.lastY - n.y, n.lastX - n.x), 9f * dt)
                    n.react -= dt
                    if (see) { n.lastX = px; n.lastY = py }
                    if (n.react <= 0f) { n.state = ENGAGE; n.lostT = 0f; n.spotT = 0f }
                }
                ENGAGE -> engage(n, dt, see)
                SEARCH -> {
                    if (see) { n.state = ENGAGE; n.lostT = 0f; n.react = 0f }
                    else if (!follow(n, dt, 2.4f, true)) { n.searchT += dt; n.face += 1.4f * dt; if (n.searchT > 2.2f) n.state = IDLE }
                }
            }
        }
    }

    private fun engage(n: Npc, dt: Float, see: Boolean) {
        if (see) {
            n.lostT = 0f; n.lastX = px; n.lastY = py
            n.face = turnToward(n.face, atan2(py - n.y, px - n.x), 7f * dt)
            val dist = hypot(px - n.x, py - n.y)
            n.spotT -= dt
            if (n.spotT <= 0f) { n.spotT = 1.6f + rnd.nextFloat() * 1.2f; val s = pickCover(n); if (s != null && s != n.spot) { n.spot = s; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), s)) } else if (s == null) n.spot = null }
            val spot = n.spot
            val atSpot = spot != null && hypot(spot.first + 0.5f - n.x, spot.second + 0.5f - n.y) < 0.35f
            if (spot != null && !atSpot && n.path.isNotEmpty()) { n.crouch = false; follow(n, dt, if (n.swat) 3.6f else 3.1f, false) }
            else if (spot == null && dist > 6.5f && !n.camper) {
                n.repath -= dt
                if (n.repath <= 0f || n.path.isEmpty()) { n.repath = 0.5f; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), floor(px).toInt() to floor(py).toInt())) }
                follow(n, dt, 2.5f, false)
            }
            if (atSpot || (n.camper && n.path.isEmpty())) {                      // duck behind the furniture, pop up, fire, duck again
                n.crouchT -= dt
                if (n.crouchT <= 0f) { n.crouch = !n.crouch; n.crouchT = if (n.crouch) 0.7f + rnd.nextFloat() * 0.9f else 1.0f + rnd.nextFloat() * 0.8f }
            } else n.crouch = false
            if (!n.crouch && n.cd <= 0f && abs(angleDiff(n.face, atan2(py - n.y, px - n.x))) < 0.22f && phase == Phase.PLAY) {
                val spread = (if (n.swat) 0.17f else 0.26f)
                val ang = atan2(py - n.y, px - n.x) + (rnd.nextFloat() - 0.5f) * spread
                val sp = if (n.swat) 13.5f else 12f
                bullets += Bullet(n.x + cos(ang) * 0.5f, n.y + sin(ang) * 0.5f, cos(ang) * sp, sin(ang) * sp, false)
                n.flash = 0.1f
                n.cd = if (n.swat) 0.45f + rnd.nextFloat() * 0.35f else 0.85f + rnd.nextFloat() * 0.6f
            }
        } else {
            n.lostT += dt
            if (n.crouch && n.crouchT > 0.2f) n.crouchT = 0.2f
            if (n.camper) {   // he holds his spot, low, watching where you were
                n.face = turnToward(n.face, atan2(n.lastY - n.y, n.lastX - n.x), 5f * dt)
                if (n.path.isNotEmpty()) follow(n, dt, 3f, false) else { n.crouch = true }
                if (n.lostT > 4f) { n.state = IDLE; n.baseFace = n.face; n.crouch = false }
                return
            }
            if (n.lostT > 0.6f) { n.state = SEARCH; n.crouch = false; n.path.clear(); n.path.addAll(route(floor(n.x).toInt() to floor(n.y).toInt(), floor(n.lastX).toInt() to floor(n.lastY).toInt())); n.searchT = 0f }
        }
    }

    // ----- bullets -----
    private fun bulletsUpdate(dt: Float) {
        val it = bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            val len = hypot(b.vx, b.vy) * dt
            var steps = (len / 0.2f).toInt() + 1
            val sx = b.vx * dt / steps; val sy = b.vy * dt / steps; val sl = hypot(sx, sy)
            var gone = false
            while (steps-- > 0 && !gone) {
                b.x += sx; b.y += sy; b.travel += sl
                val tc = floor(b.x).toInt(); val tr = floor(b.y).toInt()
                if (blocksBullet(tc, tr)) { sparks += Spark(b.x, b.y); gone = true; break }
                val cv = coverOf(tc, tr)
                if (cv > 0f && b.travel > 0.9f) { b.cover = 1.5f; b.coverP = max(b.coverP, cv) } else if (b.cover > 0f) b.cover = max(0f, b.cover - sl)
                if (b.harmless) { val h = hostage!!; if (hypot(h.x - b.x, h.y - b.y) < 0.5f) { gone = true; break } else continue }
                if (b.mine) {
                    for (n in npcs) if (!n.dead && !n.hostage && !n.gone && hypot(n.x - b.x, n.y - b.y) < 0.4f) {
                        if (soaked(b, n.crouched)) { sparks += Spark(b.x, b.y); gone = true; break }
                        kill(n); gone = true
                        if (!n.civ) alertNear(n.x, n.y, 9f)
                        break
                    }
                } else {
                    // officers' bullets can hit clerks too
                    for (n in npcs) if (n.civ && !n.dead && !n.gone && hypot(n.x - b.x, n.y - b.y) < 0.35f) {
                        if (soaked(b, n.crouched)) { sparks += Spark(b.x, b.y); gone = true; break }
                        kill(n); gone = true; break
                    }
                    if (!gone && phase == Phase.PLAY && !invulnerable && hypot(px - b.x, py - b.y) < 0.34f) {
                        val miss = when { hidden -> 1f; b.cover > 0f -> 0.9f; nearWall -> 0.4f; else -> 0f }
                        if (rnd.nextFloat() < miss) { sparks += Spark(b.x, b.y); gone = true } else { die(); gone = true }
                    }
                }
            }
            b.life -= dt
            if (gone || b.life <= 0f) it.remove()
        }
    }

    /** The bullet has just crossed low furniture: is it soaked up by it (60-90%, more if the target is ducking)? */
    private fun soaked(b: Bullet, crouched: Boolean): Boolean {
        if (b.cover <= 0f) return false
        val p = if (crouched) max(b.coverP, 0.9f) else b.coverP
        return rnd.nextFloat() < p
    }

    // ---------- the mayor's office ----------
    var ft = 0f
    val runnerCount = 20
    var mayorGun = 0f                 // which way he waves his gun
    private var finaleX = 0f; private var finaleY = 0f
    private var finStartX = 0f; private var finStartY = 0f

    /** The skip arrow: straight to the mayor's office and the end of the level. */
    fun skipToMayor() { if (phase == Phase.FINALE || phase == Phase.DONE) return; mayorUnlocked = true; px = 7.5f; py = 12.2f; startFinale() }

    private fun startFinale() {
        phase = Phase.FINALE; phaseT = 0f; ft = 0f; bullets.clear(); grenadesAir.clear(); moving = false
        finStartX = px; finStartY = py
    }

    /** Where runner [i] starts: lined up in the hall behind the door. */
    fun runnerStart(i: Int): Pair<Float, Float> = (6.7f + (i % 4) * 0.87f) to (14.5f + (i / 4) * 1.1f)
    private fun runnerSlot(i: Int, radius: Float): Pair<Float, Float> {
        val a = i * 2f * PI.toFloat() / runnerCount + 0.3f
        val rr = radius + (i % 2) * 0.9f
        return (finaleX + cos(a) * rr) to (finaleY + sin(a) * rr * 0.85f)
    }
    private fun runnerT0(i: Int) = 8.4f + i * 0.06f
    /** Where runner [i] is now: through the door, round you, then stepping back so the mayor can reach you. */
    fun runnerPos(i: Int): Pair<Float, Float>? {
        if (ft < runnerT0(i)) return null
        val s = runnerStart(i); val gate = (7.1f + (i % 2) * 0.8f) to 11.0f; val e = runnerSlot(i, 1.7f)
        val l1 = hypot(gate.first - s.first, gate.second - s.second); val l2 = hypot(e.first - gate.first, e.second - gate.second)
        val dist = (ft - runnerT0(i)) * 7.2f
        var x: Float; var y: Float
        if (dist < l1) { val k = dist / l1; x = s.first + (gate.first - s.first) * k; y = s.second + (gate.second - s.second) * k }
        else { val k = ((dist - l1) / l2).coerceIn(0f, 1f); x = gate.first + (e.first - gate.first) * k; y = gate.second + (e.second - gate.second) * k }
        if (ft > 10.5f) { val b = runnerSlot(i, 2.7f); val m = ((ft - 10.5f) / 0.7f).coerceIn(0f, 1f); x += (b.first - e.first) * m; y += (b.second - e.second) * m }
        // the mayor shoves through: anyone in his way is pushed aside
        if (ft > 10.6f) { val dx = x - mayorX; val dy = y - mayorY; val d = kotlin.math.hypot(dx, dy); if (d < 1.5f && d > 0.01f) { x += dx / d * (1.5f - d); y += dy / d * (1.5f - d) } }
        return x to y
    }
    val kicking get() = ft in 0.2f..0.6f
    /** The one paper he picks up off the floor, between him and you. */
    val paperSpot get() = (finaleX - 1.1f) to (finaleY - 1.2f)
    val mayorBending get() = ft in PAPER_AT..PAPER_UP
    val mayorReading get() = ft in PAPER_UP..PAPER_AWAY
    val mayorWalking get() = (ft in 10.6f..PAPER_AT) || (ft in PAPER_AWAY + 0.3f..17.0f)
    val playerDown get() = ft >= 9.8f
    val mayorKicking get() = ft in KICK - 0.25f..KICK + 0.15f
    val mayorLaughing get() = false
    val finaleDone get() = ft >= KICK + 1.6f
    val fade get() = if (ft >= KICK + 0.12f) 1f else 0f            // straight to black
    /** The song from the moment the door goes in until he's had his look at you. */
    val mayorMusic get() = phase == Phase.FINALE && ft in 0.5f..MUSIC_CUT
    /** What the mayor is shouting right now. */
    fun mayorLine(): String? = when {
        ft in 1.0f..3.0f -> "NO! NO! NO!"
        ft in 5.2f..7.2f -> "WHO LET YOU IN?!"
        ft in 7.3f..8.9f -> "GET HIM! GET HIM NOW!"
        ft in PAPER_UP + 0.3f..PAPER_AWAY -> "Heh... heh heh."
        else -> null
    }

    private fun finale(dt: Float) {
        val before = ft
        ft += dt
        // you run up and kick the door in, then walk into the room
        when {
            ft < 0.25f -> { val k = ft / 0.25f; px = finStartX + (8.0f - finStartX) * k; py = finStartY + (12.35f - finStartY) * k; face = -PI.toFloat() / 2 }
            ft < 0.7f -> { px = 8.0f; py = 12.35f; face = -PI.toFloat() / 2 }
            ft < 1.6f -> { val k = (ft - 0.7f) / 0.9f; py = 12.35f - 2.8f * k; moving = true; face = -PI.toFloat() / 2 }
            else -> { moving = false; py = 9.55f }
        }
        if (before < 0.5f && ft >= 0.5f) { for (d in spec.mayorDoors) closed.remove(d); shake = 0.6f; flash = 0.18f }
        if (before < 1.6f && ft >= 1.6f) { finaleX = px; finaleY = py }
        // the mayor: paces and waves his gun, stares at you, screams, then comes over
        when {
            ft < 5.0f -> {
                mayorX = 7.5f + 4.3f * sin(ft * 1.25f + 0.8f); mayorY = 6.2f + 0.6f * sin(ft * 2.3f)
                mayorGun = (sin(ft * 9f) * 1.4f + sin(ft * 5.3f) * 0.9f)
            }
            ft < 10.6f -> { mayorX += (7.5f - mayorX) * min(1f, 6f * dt); mayorY += (6.4f - mayorY) * min(1f, 6f * dt); mayorGun = atan2(py - mayorY, px - mayorX) + 0.12f * sin(ft * 21f) }
            ft < PAPER_AT -> { val (tx, ty) = paperSpot; val dx = tx - mayorX; val dy = ty - 0.6f - mayorY; val d = kotlin.math.hypot(dx, dy); val mv = min(1.1f * dt, d); if (d > 0.01f) { mayorX += dx / d * mv; mayorY += dy / d * mv }; mayorGun = atan2(py - mayorY, px - mayorX) }
            ft < PAPER_AWAY + 0.3f -> {}
            ft < 17.0f -> { val tx = finaleX - 0.2f; val ty = finaleY - 1.0f; val dx = tx - mayorX; val dy = ty - mayorY; val d = kotlin.math.hypot(dx, dy); val mv = min(1.0f * dt, d); if (d > 0.01f) { mayorX += dx / d * mv; mayorY += dy / d * mv }; mayorGun = atan2(py - mayorY, px - mayorX) }
            else -> mayorGun = atan2(py - mayorY, px - mayorX)
        }
        if (before < 0.5f && ft >= 0.5f) events += "kick"
        // boots: the squad coming down the hall, louder and louder, then they pile on and throw you down
        if (ft in 6.6f..10.2f && (ft * 7.5f).toInt() != (before * 7.5f).toInt()) events += "stomp" + (1 + ((ft - 6.6f) / 0.95f).toInt()).coerceAtMost(4)
        if (before < 9.8f && ft >= 9.8f) { events += "thrown"; shake = 0.5f }
        if (before < PAPER_UP + 0.3f && ft >= PAPER_UP + 0.3f) events += "chuckle"
        if (before < KICK && ft >= KICK) { flash = 0.3f; shake = 0.7f; events += "dome" }
        if (finaleDone) phase = Phase.DONE
    }
}
