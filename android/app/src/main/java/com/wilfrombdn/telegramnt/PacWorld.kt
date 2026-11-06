package com.wilfrombdn.telegramnt

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// The sewer maze of Dead Letter level 2, played exactly like Pac-Man (no Android code here, so tests can run it).
// You are the player; the four police are the four ghosts and are programmed the way the arcade ghosts are:
//   * they never reverse on their own, except when the mode changes;
//   * they pick a direction one tile ahead, at junctions, by which neighbouring tile is nearest (straight-line) to their target,
//     breaking ties in the order up, left, down, right;
//   * they alternate "scatter" (each heads for its own corner) and "chase" on the arcade's timetable;
//   * in chase each has its own target: Blinky (red) your tile; Pinky (pink) four tiles ahead of you (with the arcade's
//     up-direction overflow bug); Inky (cyan) twice the vector from Blinky to two tiles ahead of you; Clyde (orange) your tile
//     if he is more than eight tiles away, otherwise his own corner;
//   * Blinky speeds up when few fragments are left ("Cruise Elroy");
//   * they are slower in the side tunnels, and the other three leave their chamber after you have collected some fragments
//     (or after you dawdle).
// Instead of dots you collect key fragments; when all are collected the exit door opens.

internal class PacSpec(
    val maze: List<String>,       // # wall, ' ' void, . path, M chamber, - chamber door, S your start, D the exit door
    val tunnelRow: Int,           // the row that wraps from one side to the other
)

internal class PacWorld(val spec: PacSpec) {
    enum class Phase { INTRO, READY, PLAY, WON, CAUGHT }

    val w = spec.maze[0].length
    val h = spec.maze.size
    private val g = Array(h) { r -> spec.maze[r].toCharArray() }
    val frag = Array(h) { BooleanArray(w) }
    var total = 0; var left = 0
    var startX = 0; var startY = 0
    var doorX = 0; var doorY = 0
    var doorOpen = false
    var phase = Phase.INTRO
    /** Each failed try makes the police 6% slower (down to 60%) and the catch circle a little smaller. */
    var fails = 0
    var t = 0f; var phaseT = 0f

    // me
    var px = 0f; var py = 0f
    var dir = LEFT
    var wantDir = -1
    private var wantAge = 0f
    private var lastWant = -1
    var moving = false
    private var eatStall = 0f
    var eaten = 0
    private var idleT = 0f

    // the intro: the four police climb down a manhole in the middle of the chamber, then it is covered
    var lidClose = 0f              // 0 open .. 1 shut
    var spot = 1f                  // the spotlight from above
    var descending = -1            // which cop is on the ladder right now
    var descendP = 0f              // how far down (0..1)
    var landed = 0                 // how many have reached the bottom
    val hatchX get() = w / 2f
    val hatchY: Float get() = (chamberTop + chamberBottom) / 2f
    private var chamberTop = 0; private var chamberBottom = 0

    // the ghosts
    class Ghost(val id: Int) {
        var x = 0f; var y = 0f
        var tx = 0; var ty = 0           // the tile centre he is walking toward
        var dir = LEFT
        var next = LEFT                  // the turn already chosen for that tile
        var state = IN_CHAMBER
        var pendingReverse = false
        var visible = false
        var homeX = 0f
        var bob = 1f
        val tileX get() = floor(x).toInt()
        val tileY get() = floor(y).toInt()
    }
    val ghosts = listOf(Ghost(BLINKY), Ghost(PINKY), Ghost(INKY), Ghost(CLYDE))

    // scatter and chase on the arcade's timetable
    var chase = false
    private var modeIdx = 0
    private var modeT = 0f
    private val schedule = floatArrayOf(7f, 20f, 7f, 20f, 5f, 20f, 5f, Float.MAX_VALUE)   // scatter, chase, scatter, chase, ...
    private val scatterCorner = listOf(w - 3 to -3, 2 to -3, w - 1 to h + 2, 0 to h + 2)

    init {
        for (r in 0 until h) for (c in 0 until w) when (g[r][c]) {
            'S' -> { startX = c; startY = r; g[r][c] = '.' }
            'D' -> { doorX = c; doorY = r }
        }
        var top = h; var bottom = 0
        for (r in 0 until h) for (c in 0 until w) if (g[r][c] == 'M') { top = min(top, r); bottom = maxOf(bottom, r + 1) }
        chamberTop = top; chamberBottom = bottom
        for (r in 0 until h) for (c in 0 until w) {
            val tunnel = r == spec.tunnelRow && (c < 5 || c > w - 6)
            val ok = g[r][c] == '.' && !tunnel && !(c == startX && r == startY) && (c + r) % 2 == 0
            if (ok) { frag[r][c] = true; total++ }
        }
        left = total
        px = startX + 0.5f; py = startY + 0.5f
        // where each cop waits down in the chamber
        ghosts[BLINKY].homeX = w / 2f; ghosts[PINKY].homeX = w / 2f; ghosts[INKY].homeX = w / 2f - 2f; ghosts[CLYDE].homeX = w / 2f + 2f
        for (gh in ghosts) { gh.x = gh.homeX; gh.y = top + 0.5f + (if (gh.id == BLINKY) 0f else 1f) }
    }

    // ---------- the maze ----------
    fun tile(x: Int, y: Int): Char = if (x in 0 until w && y in 0 until h) g[y][x] else '#'
    private fun norm(x: Int, y: Int): Pair<Int, Int>? {
        if (y !in 0 until h) return null
        var nx = x
        if (nx < 0 || nx >= w) { if (y == spec.tunnelRow) nx = ((nx % w) + w) % w else return null }
        return nx to y
    }
    private fun free(x: Int, y: Int): Boolean { val n = norm(x, y) ?: return false; val c = g[n.second][n.first]; return c == '.' || (c == 'D' && doorOpen) }
    /** Can a police officer who is out in the sewer walk here? (Never the chamber, its door or the exit door.) */
    private fun ghostFree(x: Int, y: Int): Boolean { val n = norm(x, y) ?: return false; return g[n.second][n.first] == '.' }
    fun fragmentAt(x: Int, y: Int) = x in 0 until w && y in 0 until h && frag[y][x]

    // ---------- the target each cop is heading for ----------
    private fun myTile() = floor(px).toInt() to floor(py).toInt()
    private fun ahead(n: Int): Pair<Int, Int> {
        val (x, y) = myTile()
        return when (dir) { UP -> (x - n) to (y - n)   /* the arcade's overflow bug */; LEFT -> (x - n) to y; DOWN -> x to (y + n); else -> (x + n) to y }
    }
    private fun elroy() = if (left <= total * 0.04f) 2 else if (left <= total * 0.09f) 1 else 0

    /** Where cop [id] is heading right now. */
    internal fun targetTile(id: Int): Pair<Int, Int> {
        val gh = ghosts[id]
        if (!chase && !(id == BLINKY && elroy() > 0)) return scatterCorner[id]
        val me = myTile()
        return when (id) {
            BLINKY -> me
            PINKY -> ahead(4)
            INKY -> { val a = ahead(2); val b = ghosts[BLINKY]; (2 * a.first - b.tileX) to (2 * a.second - b.tileY) }
            else -> { val dx = gh.tileX - me.first; val dy = gh.tileY - me.second; if (dx * dx + dy * dy >= 64) me else scatterCorner[CLYDE] }
        }
    }

    /** At tile ([bx],[by]), having arrived heading [dirIn]: the next direction a cop picks (nearest to his target; ties up, left, down, right). */
    internal fun choose(gh: Ghost, bx: Int, by: Int, dirIn: Int): Int {
        val target = targetTile(gh.id)
        var best = -1; var bd = Int.MAX_VALUE
        for (d in 0..3) {
            if (d == (dirIn + 2) % 4) continue                 // never straight back
            if (!ghostFree(bx + DX[d], by + DY[d])) continue
            val nx = bx + DX[d]; val ny = by + DY[d]
            val dd = (nx - target.first) * (nx - target.first) + (ny - target.second) * (ny - target.second)
            if (dd < bd) { bd = dd; best = d }
        }
        return if (best >= 0) best else (dirIn + 2) % 4        // a dead end: turn round
    }

    // ---------- the intro ----------
    private fun introUpdate(dt: Float) {
        val start = 0.8f; val gap = 1.0f; val dur = 0.9f
        descending = -1
        for (i in 0..3) {
            val at = start + i * gap
            if (t >= at && t < at + dur) { descending = i; descendP = (t - at) / dur }
            if (t >= at + dur && !ghosts[i].visible) { ghosts[i].visible = true; landed = i + 1 }
        }
        val allDown = start + 3 * gap + dur
        if (t > allDown + 0.3f) lidClose = ((t - allDown - 0.3f) / 1.0f).coerceIn(0f, 1f)
        spot = if (lidClose >= 1f) (1f - (t - allDown - 1.3f) / 0.6f).coerceIn(0f, 1f) else 1f
        if (t > allDown + 1.9f) { phase = Phase.READY; phaseT = 0f; spot = 0f; ghosts[BLINKY].state = LEAVING }
    }

    // ---------- the game ----------
    /** [want]: the direction I want to go (UP/LEFT/DOWN/RIGHT) or -1. */
    fun update(dt: Float, want: Int) {
        t += dt; phaseT += dt
        when (phase) {
            Phase.INTRO -> introUpdate(dt)
            Phase.READY -> { moveLeavers(dt); if (phaseT > 1.8f) { phase = Phase.PLAY; phaseT = 0f; ghosts[PINKY].state = LEAVING } }
            Phase.PLAY -> play(dt, want)
            else -> {}
        }
    }

    private fun play(dt: Float, want: Int) {
        // a turn I asked for is remembered for half a second (so a quick flick still turns at the next junction), then forgotten,
        // so a push I made long ago never makes the courier turn on his own later
        if (want >= 0) { wantDir = want; lastWant = want; wantAge = 0f } else { wantAge += dt; if (wantAge > 0.5f) wantDir = -1 }
        // the scatter/chase clock
        modeT += dt
        if (modeIdx < schedule.size - 1 && modeT >= schedule[modeIdx]) {
            modeT -= schedule[modeIdx]; modeIdx++; chase = modeIdx % 2 == 1
            for (gh in ghosts) if (gh.state == OUT) gh.pendingReverse = true      // they all turn round when the mode changes
        }
        idleT += dt
        releaseCops()
        movePac(dt)
        moveLeavers(dt)
        for (gh in ghosts) if (gh.state == OUT) moveCop(gh, dt)
        // caught?
        for (gh in ghosts) if (gh.state != IN_CHAMBER && gh.visible && hypot(gh.x - px, gh.y - py) < CATCH - 0.03f * min(fails, 4)) { phase = Phase.CAUGHT; phaseT = 0f }
    }

    private fun releaseCops() {
        // each of the others leaves once I have collected enough, or after I dawdle
        val need = intArrayOf(0, 0, maxOf(1, total / 11), maxOf(2, total / 5))
        for (id in PINKY..CLYDE) {
            val gh = ghosts[id]
            if (gh.state == IN_CHAMBER && (eaten >= need[id] || idleT > 4f) && ghosts.filter { it.id < id }.all { it.state != IN_CHAMBER }) { gh.state = LEAVING; idleT = 0f }
        }
    }

    // ----- me -----
    private fun movePac(dt: Float) {
        if (eatStall > 0f) { eatStall -= dt; moving = false; return }
        var remaining = PAC * dt
        moving = false
        while (remaining > 1e-4f) {
            val step = min(remaining, 0.08f); remaining -= step
            val tx = floor(px).toInt(); val ty = floor(py).toInt()
            val cx = tx + 0.5f; val cy = ty + 0.5f
            if (wantDir >= 0 && wantDir != dir) {
                if (wantDir == (dir + 2) % 4) dir = wantDir
                else {
                    // how far past the middle of this tile I am, along the way I am going (negative = still coming up to it)
                    val s0 = (px - cx) * DX[dir] + (py - cy) * DY[dir]
                    if (free(tx + DX[wantDir], ty + DY[wantDir]) && s0 >= -0.3f && s0 <= 0.5f) {
                        // turn now and keep the distance I had already gone past the middle, so a late turn does not jump back
                        px = cx; py = cy; dir = wantDir
                        if (s0 > 0f) { px += DX[dir] * s0; py += DY[dir] * s0 }
                    }
                }
            }
            val pastCentre = (px - cx) * DX[dir] + (py - cy) * DY[dir]
            if (pastCentre >= 0f && !free(tx + DX[dir], ty + DY[dir])) {
                // a wall ahead: never stop. Round a corner I keep going; at a junction with no fresh push I take the side I last pushed toward
                px = cx; py = cy
                val opts = listOf((dir + 1) % 4, (dir + 3) % 4).filter { free(tx + DX[it], ty + DY[it]) }
                dir = if (wantDir in opts) wantDir else if (lastWant in opts) lastWant else opts.firstOrNull() ?: ((dir + 2) % 4)
                continue
            }
            px += DX[dir] * step; py += DY[dir] * step
            moving = true
            if (px < -0.5f) px += w; if (px > w + 0.5f) px -= w
            // collect a fragment when I am near the middle of its tile
            val ex = floor(px).toInt(); val ey = floor(py).toInt()
            val n = norm(ex, ey)
            if (n != null && hypot(px - (ex + 0.5f), py - (ey + 0.5f)) < 0.4f && frag[n.second][n.first]) {
                frag[n.second][n.first] = false; left--; eaten++; idleT = 0f; eatStall = 0.02f
                if (left == 0) doorOpen = true
            }
            if (doorOpen && ex == doorX && ey == doorY) { phase = Phase.WON; phaseT = 0f; return }
        }
    }

    // ----- the cops in and just out of the chamber -----
    private fun moveLeavers(dt: Float) {
        for (gh in ghosts) {
            if (!gh.visible) continue
            if (gh.state == IN_CHAMBER) {   // bob up and down
                gh.y += gh.bob * 1.2f * dt
                val top = chamberTop + 0.5f; val bot = chamberBottom - 0.5f
                if (gh.y > bot) { gh.y = bot; gh.bob = -1f }; if (gh.y < top) { gh.y = top; gh.bob = 1f }
            } else if (gh.state == LEAVING) {
                val sp = LEAVE * dt
                val doorCx = w / 2f
                if (abs(gh.x - doorCx) > 0.05f) gh.x += (if (gh.x < doorCx) 1 else -1) * min(sp, abs(gh.x - doorCx))
                else {
                    gh.x = doorCx
                    val outY = chamberTop - 1.5f
                    gh.y -= min(sp, gh.y - outY)
                    if (gh.y <= outY + 0.001f) {       // out in the sewer, heading left
                        gh.y = outY; gh.state = OUT
                        gh.dir = LEFT; gh.tx = floor(gh.x).toInt() - 1; gh.ty = floor(gh.y).toInt()
                        gh.next = choose(gh, gh.tx, gh.ty, LEFT)
                    }
                }
            }
        }
    }

    // ----- a cop out in the sewer -----
    private fun copSpeed(gh: Ghost): Float = copSpeedRaw(gh) * (1f - 0.06f * fails).coerceAtLeast(0.6f)

    private fun copSpeedRaw(gh: Ghost): Float {
        val inTunnel = floor(gh.y).toInt() == spec.tunnelRow && (gh.x < 5f || gh.x > w - 5f)
        if (inTunnel) return U * 0.4f
        if (gh.id == BLINKY) { val e = elroy(); if (e == 2) return U * 0.85f; if (e == 1) return U * 0.8f }
        return U * 0.75f
    }

    private fun moveCop(gh: Ghost, dt: Float) {
        var remaining = copSpeed(gh) * dt
        while (remaining > 1e-4f) {
            val tcx = gh.tx + 0.5f; val tcy = gh.ty + 0.5f
            val dx = tcx - gh.x; val dy = tcy - gh.y
            val dist = hypot(dx, dy)
            if (dist <= remaining) {
                remaining -= dist
                gh.x = tcx; gh.y = tcy
                // arrived: wrap through the tunnel if need be, then turn as already decided (or turn round if the mode changed)
                if (gh.tx < 0) { gh.tx += w; gh.x += w } else if (gh.tx >= w) { gh.tx -= w; gh.x -= w }
                val arrivalDir = gh.dir
                gh.dir = if (gh.pendingReverse) { gh.pendingReverse = false; (arrivalDir + 2) % 4 } else gh.next
                gh.tx += DX[gh.dir]; gh.ty += DY[gh.dir]
                gh.next = choose(gh, gh.tx, gh.ty, gh.dir)
            } else {
                gh.x += dx / dist * remaining; gh.y += dy / dist * remaining; remaining = 0f
            }
        }
    }

    companion object {
        const val UP = 0; const val LEFT = 1; const val DOWN = 2; const val RIGHT = 3
        val DX = intArrayOf(0, -1, 0, 1)
        val DY = intArrayOf(-1, 0, 1, 0)
        const val BLINKY = 0; const val PINKY = 1; const val INKY = 2; const val CLYDE = 3
        const val IN_CHAMBER = 0; const val LEAVING = 1; const val OUT = 2
        const val U = 6.6f                  // "100% speed", in tiles per second
        const val PAC = U * 0.8f
        const val LEAVE = U * 0.5f
        const val CATCH = 0.55f
    }
}
