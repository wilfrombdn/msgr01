package com.wilfrombdn.telegramnt

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * The prison of level 5 (no Android code, so plain tests can drive it).
 *
 * Day one starts with you waking on your bunk: the lights come on, a bell rings, the cell door slides open. After that the prison runs on
 * a daily routine, like a real one: roll call, breakfast, a laundry shift, lunch, a second laundry shift, free time, dinner, showers,
 * roll call, lights out. A day takes about 7.5 real minutes; the night flies by once you are in your cell.
 * Your job is the laundry: each shift is one bulk load of 3 bags (dirty cart, washer, dryer, folding table, clean cart).
 * Miss a part of the routine, or let a guard catch you somewhere you shouldn't be, and the guards' suspicion (heat) goes up. At 100 you
 * spend the night in solitary in D block. The ways out come next (see the plan in CLAUDE.md).
 */
internal class PrisonWorld(val spec: PrisonSpec, seed: Int = 5) {
    enum class Phase { WAKE, PLAY }

    /** The parts of the day: when they start (minutes after midnight), what they're called, and where you must be. */
    enum class Period(val start: Int, val label: String, val place: String?) {
        ROLL_AM(360, "ROLL CALL", "YOUR CELL"),
        BREAKFAST(420, "BREAKFAST", "DINING HALL"),
        WORK_AM(480, "LAUNDRY SHIFT", "LAUNDRY"),
        LUNCH(720, "LUNCH", "DINING HALL"),
        WORK_PM(780, "LAUNDRY SHIFT", "LAUNDRY"),
        FREE(960, "FREE TIME", null),
        DINNER(1080, "DINNER", "DINING HALL"),
        SHOWERS(1140, "SHOWERS", "SHOWERS"),
        ROLL_PM(1200, "ROLL CALL", "YOUR CELL"),
        LIGHTS_OUT(1260, "LIGHTS OUT", "YOUR CELL");
        val work get() = this == WORK_AM || this == WORK_PM
    }

    enum class Carry { NONE, DIRTY, WET, DRY, FOLDED }

    var phase = Phase.WAKE; private set
    var t = 0f; private set
    var px = spec.startX; var py = spec.startY
    var face = 0f
    var moving = false; private set
    /** Seconds since you last moved. */
    var idle = 0f; private set
    /** 0 = your door shut, 1 = slid all the way open. */
    var doorOpen = 0f; private set
    private var doorTarget = 1f
    /** How far through the wake-up the lights are (0 dark .. 1 on). */
    val lights get() = ((t - 1.0f) / 0.6f).coerceIn(0f, 1f)
    val lyingDown get() = phase == Phase.WAKE && t < WAKE_STAND
    val cols get() = spec.cols
    val rows get() = spec.rows
    fun tile(c: Int, r: Int) = spec.tile(c, r)
    val zone get() = PrisonLevel.zoneName(spec, floor(px).toInt(), floor(py).toInt())
    val visited = HashSet<String>()

    // ---------- the clock ----------
    /** Game minutes since midnight of day one. The day starts at 6:00. */
    var clock = 360f
    val day get() = (clock / 1440f).toInt() + 1
    val minute get() = ((clock % 1440f) + 1440f) % 1440f
    val period get() = periodAt(minute)
    val night get() = minute >= Period.LIGHTS_OUT.start || minute < Period.ROLL_AM.start
    /** "07:42" */
    val clockText: String get() { val m = minute.toInt(); return "%02d:%02d".format(m / 60, m % 60) }
    private var lastPeriod = Period.ROLL_AM
    private var periodMin = 0f            // game minutes since this part of the day began

    // ---------- the routine ----------
    /** The guards' suspicion, 0..100. */
    var heat = 0f; private set
    /** Did you turn up for this part of the day (stood there for [ATTEND_MIN] game minutes)? */
    var attended = false; private set
    private var attendMin = 0f
    var solitary = false; private set
    /** The guards who can see you somewhere you shouldn't be right now (they show a "!"). */
    val watchers = HashSet<Int>()
    var msg = ""; private set
    var msgT = 0f; private set
    var msgBad = false; private set
    private fun say(m: String, bad: Boolean = false) { msg = m; msgT = 3.2f; msgBad = bad }

    // ---------- the laundry ----------
    var carry = Carry.NONE; private set
    var dirtyBags = 0; private set
    var delivered = 0; private set
    var shiftDone = false; private set
    var loadsToday = 0; private set
    var loadsTotal = 0; private set
    /** Seconds spent folding the bag in your hands (it takes [FOLD_S]). */
    var folding = -1f; private set
    class Machine(val c: Int, val r: Int, val dryer: Boolean) {
        var left = -1f        // seconds until done while running
        var ready = false     // finished, waiting to be emptied
        val running get() = left >= 0f
    }
    val machines = ArrayList<Machine>()
    class Station(val c: Int, val r: Int, val kind: Char)

    class Npc(val id: Int, var x: Float, var y: Float, val guard: Boolean) {
        var face = 0f
        var path = ArrayList<Pair<Int, Int>>()
        var wait = 0f
        var moving = false
        var stay = false          // standing where the routine put them until the next part of the day
        var home = 0 to 0         // the inmate's own cell
    }
    val npcs = ArrayList<Npc>()
    private val rnd = Random(seed)

    companion object {
        const val SPEED = 3.4f
        const val R = 0.28f
        const val WAKE_STAND = 2.6f
        const val WAKE_DOOR = 3.0f
        const val WAKE_END = 4.0f
        const val DAY_RATE = 2f           // game minutes per real second in the day (15 hours = 7.5 minutes)
        const val NIGHT_RATE = 30f        // ... and at night once you're in your cell
        const val ATTEND_MIN = 15f
        const val GRACE_MIN = 20f         // time to get somewhere at the start of each part of the day
        const val BAGS = 3
        const val WASH_S = 8f
        const val DRY_S = 8f
        const val FOLD_S = 2f
        const val SEE = 7f                // how far the guards see
        fun periodAt(m: Float): Period {
            if (m < Period.ROLL_AM.start) return Period.LIGHTS_OUT
            var p = Period.ROLL_AM
            for (q in Period.values()) if (m >= q.start) p = q
            return p
        }
    }

    fun walkable(c: Int, r: Int): Boolean = when (tile(c, r)) {
        '.', ',', 'o', 'z', 'h', 'p' -> true
        'O' -> doorOpen >= 0.9f
        else -> false
    }

    // places people go
    private val hangouts = ArrayList<Pair<Int, Int>>()
    private val rounds = ArrayList<Pair<Int, Int>>()
    private val spots = HashMap<String, ArrayList<Pair<Int, Int>>>()
    private val myCell = PrisonLevel.CX0 + 3 * PrisonLevel.MY_CELL + 1

    init {
        for (r in 0 until rows) for (c in 0 until cols) {
            val ch = tile(c, r)
            if (ch == 'M') machines += Machine(c, r, false)
            if (ch == 'Y') machines += Machine(c, r, true)
            if (ch != '.' && ch != ',' && ch != 'z' && ch != 'h') continue
            val z = PrisonLevel.zoneName(spec, c, r)
            spots.getOrPut(z) { ArrayList() } += c to r
            if (z in setOf("RECREATION YARD", "DINING HALL", "BROADWAY", "MICHIGAN AVENUE", "SEEDY STREET", "SHOWERS", "LIBRARY", "TIMES SQUARE")) hangouts += c to r
            if (z in setOf("BROADWAY", "MICHIGAN AVENUE", "SEEDY STREET", "TIMES SQUARE", "EAST CORRIDOR", "RECREATION YARD", "DINING HALL", "LAUNDRY")) rounds += c to r
        }
        // every other cell belongs to someone
        val homes = ArrayList<Pair<Int, Int>>()
        fun row(front: Int, k0: Int, k1: Int) { for (k in k0..k1) { val x = PrisonLevel.CX0 + 3 * k + 1; if (!(front == 15 && k == PrisonLevel.MY_CELL)) homes += x to front } }
        row(15, 0, 9); row(22, 0, 9); row(9, 0, 9); row(28, 0, 9); row(3, 2, 9); row(34, 3, 9)
        // inmates: a crowd in the yard, some at breakfast, some on the blocks; guards on the corridors
        val start = listOf(
            10 to 39, 14 to 40, 6 to 38, 30 to 42, 34 to 44, 40 to 41, 45 to 43, 20 to 44, 50 to 40,
            12 to 11, 18 to 14, 11 to 23, 19 to 26,
            35 to 18, 50 to 19, 40 to 6, 47 to 31, 30 to 34, 25 to 10,
        )
        for ((i, s) in start.withIndex()) npcs += Npc(i, s.first + 0.5f, s.second + 0.5f, false).also { it.wait = rnd.nextFloat() * 3f; it.home = homes[(i * 7) % homes.size] }
        val posts = listOf(26 to 18, 43 to 6, 52 to 31, 59 to 18, 25 to 3, 38 to 43, 15 to 19, 62 to 43)
        for ((i, s) in posts.withIndex()) npcs += Npc(100 + i, s.first + 0.5f, s.second + 0.5f, true).also { it.wait = 1f + rnd.nextFloat() * 4f }
        // the first morning: everyone's at roll call by their cells
        for (n in npcs) if (!n.guard) { n.x = n.home.first + 0.5f; n.y = n.home.second + 0.5f; n.stay = true }
    }

    // =================================================================================================================
    fun update(dt: Float, ix: Float, iy: Float, mag: Float) {
        t += dt
        msgT = max(0f, msgT - dt)
        if (phase == Phase.WAKE) {
            doorOpen = ((t - WAKE_DOOR) / (WAKE_END - WAKE_DOOR)).coerceIn(0f, 1f)
            if (t >= WAKE_STAND) face = -kotlin.math.PI.toFloat() / 2f + 0.01f
            if (t >= WAKE_END) { phase = Phase.PLAY; doorTarget = 1f; say("6:00 AM. MORNING COUNT. STAND BY YOUR CELL.") }
            moving = false
        } else {
            advanceClock(dt)
            // your door slides at its own pace; if it shuts on you, you end up inside
            doorOpen = if (doorOpen < doorTarget) minOf(doorTarget, doorOpen + dt) else max(doorTarget, doorOpen - dt)
            val (dc, dr) = spec.myDoor
            if (doorOpen < 0.9f && floor(px).toInt() == dc && floor(py).toInt() == dr) py = spec.startY
            moving = mag > 0.05f && !solitary
            idle = if (moving) 0f else idle + dt
            if (moving) {
                val sp = SPEED * (0.35f + 0.65f * mag)
                face = atan2(iy, ix)
                move(ix * sp * dt, iy * sp * dt)
            }
            laundryTick(dt)
            rules(dt)
            zone.let { if (it.isNotEmpty()) visited += it }
        }
        for (n in npcs) updateNpc(n, dt)
    }

    private fun blockedAt(x: Float, y: Float): Boolean {
        for ((ox, oy) in listOf(-R to -R, R to -R, -R to R, R to R)) if (!walkable(floor(x + ox).toInt(), floor(y + oy).toInt())) return true
        return false
    }

    /** Moves you by (dx, dy), sliding along walls. */
    fun move(dx: Float, dy: Float) {
        val steps = (maxOf(abs(dx), abs(dy)) / 0.1f).toInt() + 1
        val sx = dx / steps; val sy = dy / steps
        repeat(steps) {
            if (!blockedAt(px + sx, py)) px += sx
            if (!blockedAt(px, py + sy)) py += sy
        }
    }

    // ---------- time and the routine ----------
    val inMyCell get() = floor(py).toInt() in 14..15 && floor(px).toInt() in myCell..myCell + 1
    private val atMyDoor get() = floor(py).toInt() in 16..18 && floor(px).toInt() in myCell - 1..myCell + 1
    fun atPlace(place: String?): Boolean = when (place) {
        null -> true
        "YOUR CELL" -> inMyCell || atMyDoor
        else -> zone == place
    }
    /** Where you should be right now, or null if you're fine (free time, already counted, quota done). */
    val dueAt: String? get() {
        val p = period
        if (p.place == null) return null
        if (p.work) return if (shiftDone) null else p.place
        if (p == Period.LIGHTS_OUT) return if (inMyCell || solitary) null else p.place
        return if (attended) null else p.place
    }

    private fun advanceClock(dt: Float) {
        val fast = solitary || (night && inMyCell)
        clock += (if (fast) NIGHT_RATE else DAY_RATE) * dt
        val p = period
        periodMin = minute - (if (p == Period.LIGHTS_OUT && minute < Period.ROLL_AM.start) Period.LIGHTS_OUT.start - 1440 else p.start).toFloat()
        if (p != lastPeriod) { endPeriod(lastPeriod); lastPeriod = p; startPeriod(p) }
    }

    private fun endPeriod(p: Period) {
        if (solitary) return
        when {
            p.work -> {
                if (!shiftDone) { heat += 25f; say("YOU DIDN'T FINISH YOUR LOAD. THE GUARDS NOTICED.", true) }
                carry = Carry.NONE; folding = -1f; dirtyBags = 0
                for (m in machines) { m.left = -1f; m.ready = false }
            }
            p == Period.FREE || p == Period.LIGHTS_OUT -> {}
            !attended -> { heat += 20f; say("YOU MISSED ${p.label}.", true) }
        }
    }

    private fun startPeriod(p: Period) {
        attended = false; attendMin = 0f
        when (p) {
            Period.ROLL_AM -> {
                doorTarget = 1f
                if (solitary) { solitary = false; px = spec.startX; py = spec.startY; heat = 40f }
                say("6:00 AM. DAY $day. MORNING COUNT: STAND BY YOUR CELL.")
            }
            Period.LIGHTS_OUT -> { doorTarget = 0f; if (!solitary) say("LIGHTS OUT. GET IN YOUR CELL.") }
            Period.FREE -> say("FREE TIME. GO WHERE YOU LIKE.")
            else -> if (p.work) {
                shiftDone = false; dirtyBags = BAGS; delivered = 0
                say("LAUNDRY SHIFT: WASH, DRY AND FOLD $BAGS BAGS.")
            } else say("${p.label}: GO TO THE ${p.place}.")
        }
        routineForNpcs(p)
    }

    private fun rules(dt: Float) {
        val p = period
        // turning up
        if (!attended && !p.work && p.place != null && p != Period.LIGHTS_OUT && atPlace(p.place)) {
            attendMin += DAY_RATE * dt
            if (attendMin >= ATTEND_MIN) {
                attended = true
                say(when (p) { Period.ROLL_AM, Period.ROLL_PM -> "COUNTED."; Period.SHOWERS -> "SHOWERED."; else -> "YOU ATE." })
            }
        }
        // lights out: ten minutes to get in, then the guards drag you
        if (p == Period.LIGHTS_OUT && !solitary && !inMyCell && minute >= Period.LIGHTS_OUT.start + 10) {
            px = spec.startX; py = spec.startY; doorOpen = 0f
            heat += 20f; say("THE GUARDS DRAG YOU BACK TO YOUR CELL.", true)
        }
        // a guard who sees you where you shouldn't be gets suspicious
        watchers.clear()
        val due = dueAt
        if (due != null && p != Period.LIGHTS_OUT && periodMin > GRACE_MIN && !atPlace(due)) {
            for (n in npcs) if (n.guard && sees(n)) watchers += n.id
            if (watchers.isNotEmpty()) { heat += 4f * dt; if (msgT <= 0f) say("A GUARD IS WATCHING YOU. YOU SHOULD BE AT THE ${due}.", true) }
        }
        if (watchers.isEmpty()) heat = max(0f, heat - 0.4f * dt)
        if (heat >= 100f && !solitary) goToSolitary()
        heat = heat.coerceIn(0f, 100f)
    }

    private fun goToSolitary() {
        solitary = true; heat = 100f
        px = PrisonLevel.CX0 + 3 * 6 + 1.5f; py = 34.5f          // a D block cell
        carry = Carry.NONE; folding = -1f
        if (!night) clock += Period.LIGHTS_OUT.start - minute      // the rest of the day is gone
        say("SOLITARY. A NIGHT IN D BLOCK.", true)
    }

    /** Can this guard see you (close enough, and no wall in the way)? */
    fun sees(n: Npc): Boolean {
        if (hypot(n.x - px, n.y - py) > SEE) return false
        return clearLine(n.x, n.y, px, py)
    }
    private fun clearLine(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val d = hypot(x1 - x0, y1 - y0); val steps = (d / 0.25f).toInt() + 1
        for (i in 1 until steps) {
            val x = x0 + (x1 - x0) * i / steps; val y = y0 + (y1 - y0) * i / steps
            val ch = tile(floor(x).toInt(), floor(y).toInt())
            if (ch == '#' || ch == 'v' || ch == 'L' || ch == 'X' || ch == 'W' || ch == 'r') return false
        }
        return true
    }

    // ---------- the laundry ----------
    val laundryOpen get() = period.work && !solitary
    private fun machineAt(c: Int, r: Int) = machines.firstOrNull { it.c == c && it.r == r }

    /** The laundry station you are standing next to, if any. */
    fun station(): Station? {
        var best: Station? = null; var bd = 1.3f
        val c0 = floor(px).toInt(); val r0 = floor(py).toInt()
        for (r in r0 - 2..r0 + 2) for (c in c0 - 2..c0 + 2) {
            val ch = tile(c, r)
            if (ch !in "UMYFC") continue
            val d = hypot(c + 0.5f - px, r + 0.5f - py)
            if (d < bd) { bd = d; best = Station(c, r, ch) }
        }
        return best
    }

    /** What tapping the action button would do right now ("" = nothing to do here). */
    fun prompt(): String {
        val s = station() ?: return ""
        if (!laundryOpen) return "CLOSED"
        if (folding >= 0f) return "FOLDING…"
        return when (s.kind) {
            'U' -> if (carry != Carry.NONE) "HANDS FULL" else if (dirtyBags > 0) "TAKE A BAG" else "CART EMPTY"
            'M', 'Y' -> {
                val m = machineAt(s.c, s.r) ?: return ""
                val want = if (m.dryer) Carry.WET else Carry.DIRTY
                when {
                    m.running -> if (m.dryer) "DRYING…" else "WASHING…"
                    m.ready -> if (carry == Carry.NONE) "TAKE IT OUT" else "HANDS FULL"
                    carry == want -> if (m.dryer) "DRY IT" else "WASH IT"
                    else -> if (m.dryer) "DRYER" else "WASHER"
                }
            }
            'F' -> if (carry == Carry.DRY) "FOLD IT" else "FOLDING TABLE"
            'C' -> if (carry == Carry.FOLDED) "PUT IT IN" else "CLEAN CART"
            else -> ""
        }
    }

    /** The action button. Returns true if something happened. */
    fun act(): Boolean {
        val s = station() ?: return false
        if (!laundryOpen) { say("THE LAUNDRY IS CLOSED."); return false }
        if (folding >= 0f) return false
        when (s.kind) {
            'U' -> if (carry == Carry.NONE && dirtyBags > 0) { dirtyBags--; carry = Carry.DIRTY; return true }
            'M', 'Y' -> {
                val m = machineAt(s.c, s.r) ?: return false
                if (m.ready && carry == Carry.NONE) { m.ready = false; carry = if (m.dryer) Carry.DRY else Carry.WET; return true }
                if (!m.running && !m.ready && carry == (if (m.dryer) Carry.WET else Carry.DIRTY)) { m.left = if (m.dryer) DRY_S else WASH_S; carry = Carry.NONE; return true }
            }
            'F' -> if (carry == Carry.DRY) { folding = 0f; return true }
            'C' -> if (carry == Carry.FOLDED) {
                carry = Carry.NONE; delivered++
                if (delivered >= BAGS && !shiftDone) {
                    shiftDone = true; loadsToday = if (period == Period.WORK_AM) 1 else loadsToday + 1; loadsTotal++
                    heat = max(0f, heat - 10f)
                    say("LOAD DONE. ${if (period == Period.WORK_AM) "ONE MORE AFTER LUNCH." else "THAT'S BOTH FOR TODAY."}")
                }
                return true
            }
        }
        return false
    }

    private fun laundryTick(dt: Float) {
        for (m in machines) if (m.running) { m.left -= dt; if (m.left < 0f) { m.left = -1f; m.ready = true } }
        if (folding >= 0f) {
            val s = station()
            if (s == null || s.kind != 'F' || moving) folding = -1f            // walked off: start again
            else { folding += dt; if (folding >= FOLD_S) { folding = -1f; carry = Carry.FOLDED } }
        }
    }

    // ---------- the other people ----------
    private fun spot(zone: String, near: Pair<Int, Int>? = null): Pair<Int, Int>? {
        val l = spots[zone] ?: return null
        return l[rnd.nextInt(l.size)]
    }

    /** Sends the inmates where the routine says (their cells, the dining hall, work, the showers) or lets them roam in free time. */
    private fun routineForNpcs(p: Period) {
        for (n in npcs) {
            if (n.guard) continue
            val target: Pair<Int, Int>? = when (p) {
                Period.ROLL_AM, Period.ROLL_PM, Period.LIGHTS_OUT -> n.home
                Period.BREAKFAST, Period.LUNCH, Period.DINNER -> spot("DINING HALL")
                Period.SHOWERS -> spot("SHOWERS")
                Period.WORK_AM, Period.WORK_PM -> when (n.id % 5) { 0 -> spot("KITCHEN"); 1 -> spot("LIBRARY"); 2 -> spot("LAUNDRY"); else -> spot("RECREATION YARD") }
                Period.FREE -> null
            }
            if (target == null) { n.stay = false; n.wait = rnd.nextFloat() * 4f; continue }
            n.stay = true
            n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), target)
            n.wait = 0f
        }
    }

    private fun updateNpc(n: Npc, dt: Float) {
        if (n.path.isEmpty()) {
            n.moving = false
            if (n.stay) return
            n.wait -= dt
            if (n.wait > 0f) return
            val pool = if (n.guard) rounds else hangouts
            // mostly stay near where you are; now and then cross the prison
            val here = floor(n.x).toInt() to floor(n.y).toInt()
            val near = pool.filter { abs(it.first - here.first) + abs(it.second - here.second) < 14 }
            val goal = if (near.isNotEmpty() && rnd.nextFloat() < 0.75f) near[rnd.nextInt(near.size)] else pool[rnd.nextInt(pool.size)]
            n.path = route(here, goal)
            n.wait = if (n.guard) 2f + rnd.nextFloat() * 3f else 1.5f + rnd.nextFloat() * 5f
            return
        }
        val (tc, tr) = n.path[0]
        val tx = tc + 0.5f; val ty = tr + 0.5f
        val dx = tx - n.x; val dy = ty - n.y; val d = hypot(dx, dy)
        val sp = (if (n.guard) 1.3f else 1.9f) * dt
        n.moving = true
        if (d <= sp) { n.x = tx; n.y = ty; n.path.removeAt(0) } else { n.x += dx / d * sp; n.y += dy / d * sp; n.face = atan2(dy, dx) }
    }

    /** The shortest way between two tiles for the people (they never use your door or the locked ones). */
    fun route(a: Pair<Int, Int>, b: Pair<Int, Int>): ArrayList<Pair<Int, Int>> {
        val w = cols; val prev = IntArray(w * rows) { -2 }
        val q = ArrayDeque<Int>(); val s = a.second * w + a.first; prev[s] = -1; q.add(s)
        val goal = b.second * w + b.first
        while (q.isNotEmpty()) {
            val cur = q.removeFirst(); if (cur == goal) break
            val c = cur % w; val r = cur / w
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nc = c + dc; val nr = r + dr
                if (nc !in 0 until w || nr !in 0 until rows) continue
                val ni = nr * w + nc
                if (prev[ni] != -2) continue
                val ch = tile(nc, nr)
                if (ch !in ".,ozh") continue
                prev[ni] = cur; q.add(ni)
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        if (prev[goal] == -2) return out
        var cur = goal
        while (cur != s) { out.add(0, (cur % w) to (cur / w)); cur = prev[cur] }
        return out
    }

    /** Every walkable tile you can reach from your cell once the door is open (used by the tests). */
    fun reachable(): Set<Pair<Int, Int>> {
        val seen = HashSet<Pair<Int, Int>>(); val q = ArrayDeque<Pair<Int, Int>>()
        val s = floor(spec.startX).toInt() to floor(spec.startY).toInt(); seen += s; q.add(s)
        while (q.isNotEmpty()) {
            val (c, r) = q.removeFirst()
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = (c + dc) to (r + dr)
                if (n in seen) continue
                val ch = tile(n.first, n.second)
                if (ch !in ".,ozhO") continue
                seen += n; q.add(n)
            }
        }
        return seen
    }
}
