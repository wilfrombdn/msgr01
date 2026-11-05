package com.wilfrombdn.telegramnt

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    enum class Phase { WAKE, PLAY, SIGNAL, ESCAPE }

    /** Things you can steal and make. */
    enum class Item(val label: String) { SPOON("SPOON"), FORK("FORK"), FILE("FILE"), PIPE("PIPE"), SHEET("SHEET"), POSTER("POSTER"), UNIFORM("UNIFORM"), ROPE("ROPE"), HOOK("HOOK") }
    /** The ways out. */
    enum class Route { DIG, CHIP, WALL, CART, GATE }

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
    // ---------- the ways out ----------
    val items = LinkedHashMap<Item, Int>()
    fun has(i: Item, n: Int = 1) = (items[i] ?: 0) >= n
    private fun give(i: Item) { items[i] = (items[i] ?: 0) + 1; gotItem = i.label; gotT = 0f }
    private fun take(i: Item, n: Int = 1) { val v = (items[i] ?: 0) - n; if (v <= 0) items.remove(i) else items[i] = v }
    var haircut = false; private set
    var disguised = false; private set
    /** You flashed the mirror and a boat flashed back: it waits off the island. Without it, escaping means swimming (you don't make it). */
    var signalSent = false; private set
    var digNights = 0; private set
    var chipNights = 0; private set
    /** What you're doing tonight in your cell ("DIG" or "CHIP"); it counts when the morning comes. */
    var workTonight: String? = null; private set
    var route: Route? = null; private set
    /** Seconds into the signal or escape scene. */
    var sceneT = 0f; private set
    /** The escape is over and the boat has you: on to the last level. */
    val done get() = phase == Phase.ESCAPE && signalSent && sceneT >= ESCAPE_RESCUED
    /** You escaped with no boat waiting. */
    val drowned get() = phase == Phase.ESCAPE && !signalSent
    /** What the scene says right now. */
    fun caption(): String = when (phase) {
        Phase.SIGNAL -> if (sceneT < 2.6f) "YOU CATCH THE LIGHT WITH THE MIRROR AND FLASH IT OUT THE WINDOW..." else "...A BOAT FLASHES BACK. IT WILL WAIT OFF THE ISLAND."
        Phase.ESCAPE -> when {
            sceneT < 3.2f -> when (route) {
                Route.DIG -> "YOU CRAWL OUT UNDER THE WALL AND DOWN TO THE ROCKS."
                Route.CHIP -> "THROUGH THE PIPES, OVER THE ROOF, DOWN TO THE ROCKS."
                Route.WALL -> "OVER THE WALL. DOWN THE CLIFF TO THE WATER."
                Route.CART -> "THE CART ROLLS DOWN TO THE DOCK. YOU SLIP OUT."
                Route.GATE -> "THE GATE GUARD NODS. YOU WALK DOWN TO THE WATER."
                null -> ""
            }
            signalSent -> "THE BOAT WAS WAITING."
            else -> ""
        }
        else -> ""
    }

    class Machine(val c: Int, val r: Int, val dryer: Boolean) {
        var left = -1f        // seconds until done while running
        var ready = false     // finished, waiting to be emptied
        val running get() = left >= 0f
    }
    val machines = ArrayList<Machine>()
    class Station(val c: Int, val r: Int, val kind: Char)

    /** Who an inmate is: a name, a look, what he does with his free time, and what (if anything) he says when you walk up. */
    class Persona(val name: String, val activity: String, val lines: List<String>, val skin: Int, val hair: Int, val shirt: Int,
                  val bald: Boolean = false, val beard: Boolean = false, val glasses: Boolean = false, val tattoo: Boolean = false)

    class Npc(val id: Int, var x: Float, var y: Float, val guard: Boolean) {
        var face = 0f
        var path = ArrayList<Pair<Int, Int>>()
        var wait = 0f
        var moving = false
        var stay = false          // standing where the routine put them until the next part of the day
        var home = 0 to 0         // the inmate's own cell
        var persona: Persona? = null
        // guards: are they watching, distracted, or have they stepped out?
        var attn = WATCH; var attnT = 6f
        var post: Pair<Int, Int>? = null          // keeping watch over a room from here
        var awayStage = -1                        // stepping out: 0 going, 1 out, 2 coming back
        var escorting = false; var hold = false   // walking you somewhere (and waiting for you to catch up)
        // what they're saying right now
        var say = ""; var sayT = 0f
        var lineAt = 0
        val talking get() = sayT > 0f
        var heard = 0f           // seconds left facing you after you spoke to them
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
        const val CONE = 1.15f            // ... and how wide (radians either side of where they look)
        const val WATCH = 0; const val DISTRACT = 1; const val AWAY = 2
        private fun K(v: Long) = v.toInt()
        /** The men inside. Some talk (gossip, hints, the story), some don't. */
        val PERSONAS = listOf(
            Persona("BIG LOU", "weights", listOf("YARD WALL'S LOWEST BY THE TOWER. NOT THAT I'D KNOW.", "THEY DON'T WATCH THE BOTTOM WALL IN FREE TIME. MUCH."), K(0xFF8A6048), K(0xFF0E0A0A), K(0xFF5A7FB0), bald = true, tattoo = true),
            Persona("DOC", "reader", listOf("THE SHOWERS HAVE A BROKEN MIRROR BY THE FIRST STALL.", "A MIRROR CATCHES THE LIGHT. A BOAT CAN SEE THAT FROM MILES OUT."), K(0xFFE2B590), K(0xFF8A8A92), K(0xFF6A8CBC), glasses = true),
            Persona("ROOK", "loner", emptyList(), K(0xFFC89A78), K(0xFF1A1420), K(0xFF4E72A4)),
            Persona("MAGS", "cards", listOf("SPOONS GO MISSING EVERY WEEK. THEY COUNT THE FORKS, NOT THE SPOONS.", "FLOOR UNDER THE BUNKS IS OLD. SOFT, EVEN."), K(0xFFA87858), K(0xFF6A3A1A), K(0xFF5A7FB0), beard = true),
            Persona("FELIX", "talker", listOf("WANT TO LOOK LIKE A SCREW? COME SIT IN MY CHAIR AT FREE TIME.", "BARBER'S BY THE VISITING ROOM. EAST END."), K(0xFF6A4434), K(0xFF0A0808), K(0xFF52769E)),
            Persona("OLD SAL", "sleeper", listOf("THE LAUNDRY CARTS GO DOWN TO THE DOCK EVERY AFTERNOON.", "NOBODY LOOKS INSIDE A CART OF CLEAN SHEETS."), K(0xFFE2B590), K(0xFFC8C8D0), K(0xFF6A8CBC), beard = true),
            Persona("TANK", "pushups", listOf("MIND YOUR BUSINESS, NEW GUY."), K(0xFF8A6048), K(0xFF0E0A0A), K(0xFF4E72A4), bald = true, tattoo = true),
            Persona("WHISPER", "pacer", listOf("TELLEM... IS THAT REALLY YOU? THEY SAID YOU WERE DEAD.", "THEY SAY YOU HAVE LETTERS. THEY SAY IT LIKE IT'S A CRIME."), K(0xFFC89A78), K(0xFF3A2A1E), K(0xFF5A7FB0)),
            Persona("PREACHER", "reader", listOf("THE TRUTH GETS OUT. IT ALWAYS DOES.", "PRAY IF YOU LIKE. THEN DIG IF YOU HAVE TO."), K(0xFF6A4434), K(0xFFB8B8C0), K(0xFF6A8CBC), glasses = true),
            Persona("KID", "talker", listOf("IS IT TRUE YOU BLEW UP THE BRIDGE?", "I'M IN FOR A PARKING TICKET. NO, REALLY."), K(0xFFE2B590), K(0xFFB86A2A), K(0xFF5A7FB0)),
            Persona("VIC", "sweeper", listOf("THE VENTS BEHIND THE SINKS ARE SOFT. RUST.", "A FILE FROM THE KITCHEN WOULD GO RIGHT THROUGH."), K(0xFFA87858), K(0xFF2A1A10), K(0xFF52769E)),
            Persona("DUTCH", "cards", listOf("SHEETS, MAN. THREE SHEETS MAKE A ROPE. EVERYBODY KNOWS.", "A FORK AND A PIPE. BEND IT. NOW YOU'VE GOT A HOOK."), K(0xFFE2B590), K(0xFFD8C890), K(0xFF4E72A4), beard = true),
            Persona("GHOST", "loner", emptyList(), K(0xFFC8C8C8), K(0xFF1A1420), K(0xFF6A8CBC), bald = true),
            Persona("MOE", "weights", listOf("MASHED POTATOES AGAIN.", "GUARDS STEP OUT FOR A SMOKE. EVERY HOUR, LIKE A CLOCK."), K(0xFF8A6048), K(0xFF0E0A0A), K(0xFF5A7FB0)),
            Persona("NUMBERS", "pacer", listOf("SEVEN MINUTES BETWEEN ROUNDS. I'VE COUNTED.", "THE ONE IN THE LAUNDRY TURNS HIS BACK WHEN HIS RADIO GOES."), K(0xFFC89A78), K(0xFF3A2A1E), K(0xFF6A8CBC), glasses = true),
            Persona("BISHOP", "talker", listOf("THE MAYOR PUT HALF OF US IN HERE.", "THEY KEEP THE UNIFORMS IN THE LAUNDRY. FUNNY, THAT."), K(0xFF6A4434), K(0xFF0A0808), K(0xFF52769E), beard = true),
            Persona("RAY", "pushups", emptyList(), K(0xFFE2B590), K(0xFF4A2E1B), K(0xFF4E72A4), tattoo = true),
            Persona("COOKIE", "sweeper", listOf("KITCHEN'S GOT FILES FOR THE KNIVES. NOBODY COUNTS THOSE EITHER."), K(0xFFA87858), K(0xFF1A1420), K(0xFF5A7FB0), bald = true),
            Persona("SLIM", "sleeper", listOf("ZZZ..."), K(0xFFC89A78), K(0xFF6A3A1A), K(0xFF6A8CBC)),
        )
        const val SIGNAL_S = 6f           // the mirror scene
        const val ESCAPE_RESCUED = 9.5f   // the escape scene when the boat picks you up
        const val DROWN_S = 6f            // ...and when it doesn't
        const val DIG_NIGHTS = 3
        const val CHIP_NIGHTS = 2
        val GATE_DOOR = 61 to 34          // the guards' door to the front gate
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
        'L' -> disguised && c == GATE_DOOR.first && r == GATE_DOOR.second
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
        for ((i, s) in start.withIndex()) npcs += Npc(i, s.first + 0.5f, s.second + 0.5f, false).also { it.wait = rnd.nextFloat() * 3f; it.home = homes[(i * 7) % homes.size]; it.persona = PERSONAS[i % PERSONAS.size] }
        val posts = listOf(26 to 18, 43 to 6, 52 to 31, 59 to 18, 25 to 3, 38 to 43, 15 to 19, 62 to 43)
        for ((i, s) in posts.withIndex()) npcs += Npc(100 + i, s.first + 0.5f, s.second + 0.5f, true).also { it.wait = 1f + rnd.nextFloat() * 4f }
        // the first morning: everyone's at roll call by their cells
        for (n in npcs) if (!n.guard) { n.x = n.home.first + 0.5f; n.y = n.home.second + 0.5f; n.stay = true }
    }

    // =================================================================================================================
    fun update(dt: Float, ix: Float, iy: Float, mag: Float) {
        t += dt
        msgT = max(0f, msgT - dt)
        if (phase == Phase.SIGNAL || phase == Phase.ESCAPE) {
            sceneT += dt; moving = false
            if (phase == Phase.SIGNAL && sceneT >= SIGNAL_S) { phase = Phase.PLAY; say("THE BOAT WILL WAIT. NOW GET OUT OF HERE.") }
            return
        }
        gotT += dt
        if (phase == Phase.WAKE) {
            doorOpen = 0f; doorTarget = 0f
            if (t >= WAKE_STAND) face = -kotlin.math.PI.toFloat() / 2f + 0.01f
            if (t >= WAKE_END) { phase = Phase.PLAY; startCount() }
            moving = false
        } else if (bustStage >= 0) {
            bustTick(dt); moving = false
        } else {
            if (countStage >= 0) countTick(dt) else advanceClock(dt)
            // your door slides at its own pace; if it shuts on you, you end up inside
            doorOpen = if (doorOpen < doorTarget) minOf(doorTarget, doorOpen + dt) else max(doorTarget, doorOpen - dt)
            val (dc, dr) = spec.myDoor
            if (doorOpen < 0.9f && floor(px).toInt() == dc && floor(py).toInt() == dr) py = spec.startY
            moving = mag > 0.05f && !solitary && !lockedInSpot && beatT < 0f && enforcer == null
            idle = if (moving) 0f else idle + dt
            if (moving) {
                val sp = SPEED * (0.35f + 0.65f * mag)
                face = atan2(iy, ix)
                move(ix * sp * dt, iy * sp * dt)
            }
            laundryTick(dt)
            if (countStage < 0) { escortTick(dt); rules(dt) }
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
        if (trusted && (p == Period.BREAKFAST || p == Period.LUNCH || p == Period.DINNER)) return null
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
                if (!shiftDone) { heat += 25f; punish(); say("YOU DIDN'T FINISH YOUR LOAD. THE GUARDS NOTICED.", true) }
                carry = Carry.NONE; folding = -1f; dirtyBags = 0
                for (m in machines) { m.left = -1f; m.ready = false }
            }
            p == Period.FREE || p == Period.LIGHTS_OUT -> {}
            trusted && (p == Period.BREAKFAST || p == Period.LUNCH || p == Period.DINNER) -> {}
            !attended -> { heat += 20f; punish(); say("YOU MISSED ${p.label}.", true) }
        }
    }

    private fun startPeriod(p: Period) {
        attended = false; attendMin = 0f
        when (p) {
            Period.ROLL_AM -> {
                doorTarget = 1f
                when (workTonight) {
                    "DIG" -> { digNights++; workTonight = null; startPeriodMsg = if (digNights >= DIG_NIGHTS) "THE TUNNEL IS DONE. CRAWL OUT TONIGHT." else "YOU DUG ALL NIGHT ($digNights/$DIG_NIGHTS). THE BUNK HIDES THE HOLE." }
                    "CHIP" -> { chipNights++; workTonight = null; startPeriodMsg = if (chipNights >= CHIP_NIGHTS) "THE VENT IS OUT. SQUEEZE THROUGH TONIGHT." else "YOU CHIPPED ALL NIGHT ($chipNights/$CHIP_NIGHTS). THE POSTER HIDES THE HOLE." }
                }
                if (solitary) { solitary = false; px = spec.startX; py = spec.startY; heat = 40f }
                if (cleanToday && !trusted) { trusted = true; startPeriodMsg = "A CLEAN DAY. THE GUARDS EASE OFF: ROAM AT MEALS AND IN FREE TIME." }
                cleanToday = true
                doorTarget = 0f
                startCount()
                return
            }
            Period.LIGHTS_OUT -> { doorTarget = 0f; if (!solitary) say("LIGHTS OUT. GET IN YOUR CELL.") }
            Period.FREE -> say("FREE TIME. GO WHERE YOU LIKE.")
            else -> if (p.work) {
                shiftDone = false; dirtyBags = BAGS; delivered = 0
                say("LAUNDRY SHIFT: WASH, DRY AND FOLD $BAGS BAGS.")
            } else say("${p.label}: GO TO THE ${p.place}.")
        }
        routineForNpcs(p)
        assignEscort(p)
    }

    private var startPeriodMsg: String? = null

    // ---------- trust: until you get through a whole day without trouble, a guard walks you everywhere ----------
    /** A clean day earns it: then you only get escorted to your laundry shifts and the evening count, and you can roam at meals and in free time. */
    var trusted = false; private set
    var cleanToday = true; private set
    /** Anything the guards punish you for: you lose their trust, and today no longer counts as clean. */
    private fun punish() {
        if (trusted) say("THE GUARDS DON'T TRUST YOU ANY MORE. ESCORTS EVERYWHERE UNTIL YOU DO A CLEAN DAY.", true)
        trusted = false; cleanToday = false
    }

    // ---------- the morning count ----------
    /** -1 none; 0 doors shut; 1 "please exit your cell" (get on your square); 2 the clipboards walk the halls; 3 count clear. */
    var countStage = -1; private set
    private var countT = 0f
    val countSpot get() = myCell to 17
    val onSpot get() = floor(px).toInt() == myCell && floor(py).toInt() == 17
    /** Every cell door is shut (first thing in the morning). */
    val cellsShut get() = phase == Phase.WAKE || countStage == 0
    /** Locked on your square till the count's done (after the guards have had to drag you there). */
    var lockedInSpot = false; private set
    var beatT = -1f; private set
    var enforcer: Npc? = null; private set
    val clipboards = ArrayList<Npc>()
    private val clipStops = HashMap<Int, Int>()
    private var clipPause = HashMap<Int, Float>()
    private val COUNT_ROWS = floatArrayOf(6.5f, 18.5f, 31.5f)
    /** The spot an inmate stands on for the count: just outside his cell door. */
    private fun countSpotFor(home: Pair<Int, Int>): Pair<Int, Int> = home.first to when (home.second) { 15 -> 17; 22 -> 20; 9 -> 7; 3 -> 5; 28 -> 30; 34 -> 32; else -> home.second }

    private fun startCount() {
        countStage = 0; countT = 0f; doorTarget = 0f; doorOpen = 0f; lockedInSpot = false; beatT = -1f; enforcer = null
        clipboards.clear(); clipStops.clear(); clipPause.clear()
        if (!inMyCell) { px = spec.startX; py = spec.startY }
        for (n in npcs) if (!n.guard) { n.x = n.home.first + 0.5f; n.y = n.home.second + 0.5f; n.path.clear(); n.stay = true }
        for (n in npcs) if (n.guard) { n.post = null; n.escorting = false; n.hold = false }
        escort = null
        say(startPeriodMsg ?: "6:00 AM. DAY $day."); startPeriodMsg = null
    }
    private fun countTick(dt: Float) {
        countT += dt
        when (countStage) {
            0 -> if (countT > 1.8f) {
                countStage = 1; countT = 0f; doorTarget = 1f
                say("INTERCOM: PLEASE EXIT YOUR CELL. STAND FOR ROLL CALL.")
                for (n in npcs) if (!n.guard) { n.path = route(n.home, countSpotFor(n.home)); n.stay = true }
            }
            1 -> if (countT > 6f) {
                countStage = 2; countT = 0f
                // three guards with clipboards, one down each hall
                val free = npcs.filter { it.guard }.sortedBy { it.id }
                for ((i, row) in COUNT_ROWS.withIndex()) { val g = free.getOrNull(i) ?: break; g.x = PrisonLevel.CX0 - 0.5f; g.y = row; g.path.clear(); g.post = null; g.face = 0f; clipboards += g; clipStops[g.id] = 0; clipPause[g.id] = 0f }
                if (!onSpot) beatUp()
            }
            2 -> {
                if (!onSpot && !lockedInSpot) beatUp()
                var allDone = true
                for (g in clipboards) {
                    val stops = (0..9).map { PrisonLevel.CX0 + 3 * it + 1 + 0.5f }
                    val k = clipStops[g.id] ?: 0
                    if (k >= stops.size) { if (g.x < PrisonLevel.CX1 + 0.5f) { g.x += 4f * dt; allDone = false }; continue }
                    allDone = false
                    val pz = clipPause[g.id] ?: 0f
                    if (pz > 0f) { clipPause[g.id] = pz - dt; if (pz - dt <= 0f) clipStops[g.id] = k + 1; continue }
                    g.moving = true; g.face = 0f
                    g.x += 4f * dt
                    if (g.x >= stops[k]) {
                        g.x = stops[k]; g.moving = false; clipPause[g.id] = 0.28f
                        if (abs(g.y - 18.5f) < 0.1f && abs(stops[k] - (myCell + 0.5f)) < 0.1f && onSpot) speak(g, "TELLEM. HERE.")
                    }
                }
                if (allDone) { countStage = 3; countT = 0f; say("COUNT CLEAR.") }
            }
            3 -> if (countT > 1.2f) {
                countStage = -1; lockedInSpot = false; attended = true; clipboards.clear()
                for (n in npcs) if (n.guard) n.wait = rnd.nextFloat() * 2f
                assignEscortForNow()
            }
        }
        // the guard sent to deal with you: runs over, beats you with his baton, drags you onto your square
        val e = enforcer
        if (e != null) {
            if (beatT < 0f) {
                val dx = px - e.x; val dy = py - e.y; val d = hypot(dx, dy)
                e.moving = true; e.face = kotlin.math.atan2(dy, dx)
                if (d > 0.8f) { val mv = min(6.5f * dt, d - 0.7f); e.x += dx / d * mv; e.y += dy / d * mv } else { beatT = 0f; speak(e, "WHAT DID I SAY? ON YOUR SQUARE!"); events += "beat" }
            } else {
                beatT += dt
                if ((beatT * 5f).toInt() != ((beatT - dt) * 5f).toInt() && beatT < 1.3f) events += "beat"
                if (beatT >= 1.5f) {
                    px = myCell + 0.5f; py = 17.5f; e.x = myCell + 1.5f; e.y = 18.5f; e.moving = false
                    lockedInSpot = true; beatT = -1f; enforcer = null
                    say("BEATEN AND DRAGGED TO YOUR SQUARE. YOU STAY THERE TILL THE COUNT'S DONE.", true)
                }
            }
        }
    }
    private fun beatUp() {
        if (enforcer != null || beatT >= 0f || lockedInSpot) return
        val g = npcs.filter { it.guard && it !in clipboards }.minByOrNull { hypot(it.x - px, it.y - py) } ?: return
        nearTile(floor(px).toInt() to floor(py).toInt(), 4, 6)?.let { (c, r) -> g.x = c + 0.5f; g.y = r + 0.5f }
        g.path.clear(); enforcer = g
        heat += 15f; punish()
        say("YOU'RE NOT ON YOUR SQUARE. A GUARD IS COMING.", true)
    }
    private fun assignEscortForNow() { routineForNpcs(period); assignEscort(period) }
    /** Sounds for the view to play. */
    val events = ArrayList<String>()

    // ---------- caught: a guard comes over, takes what you have, and locks you in till morning ----------
    var bustStage = -1; private set
    var bustT = 0f; private set
    var bustTook = ""; private set
    private var bustBy: Npc? = null
    /** You got caught. [by] is the guard who saw (or the nearest one); [lost] is the thing you were reaching for, if any. */
    private fun bust(by: Npc?, lost: Item?) {
        if (bustStage >= 0) return
        val g = by ?: npcs.filter { it.guard }.minByOrNull { hypot(it.x - px, it.y - py) } ?: return
        bustBy = g; bustStage = 0; bustT = 0f; carry = Carry.NONE; folding = -1f
        // he takes what you were after, or something you're carrying
        val have = items.keys.toList()
        val took = if (have.isNotEmpty()) have[rnd.nextInt(have.size)] else null
        bustTook = when { took != null -> { take(took); took.label }; lost != null -> lost.label; else -> "" }
        speak(g, "HEY! STOP RIGHT THERE, TELLEM.")
        punish()
    }
    private fun bustTick(dt: Float) {
        bustT += dt
        val g = bustBy
        when (bustStage) {
            0 -> {
                if (g == null) { bustStage = 1; bustT = 0f; return }
                val dx = px - g.x; val dy = py - g.y; val d = hypot(dx, dy)
                g.face = kotlin.math.atan2(dy, dx); g.moving = d > 0.9f; g.path.clear()
                if (d > 0.9f) { val mv = min(4f * dt, d - 0.85f); g.x += dx / d * mv; g.y += dy / d * mv }
                else { bustStage = 1; bustT = 0f; speak(g, if (bustTook.isNotEmpty()) "HAND IT OVER." else "YOU'RE DONE FOR TODAY.") }
                face = kotlin.math.atan2(-dy, -dx)
            }
            1 -> if (bustT > 1.8f) { bustStage = 2; bustT = 0f; if (bustTook.isNotEmpty()) say("HE TAKES YOUR ${bustTook}. THEN HE LOCKS YOU IN YOUR CELL.", true) else say("HE LOCKS YOU IN YOUR CELL.", true) }
            else -> if (bustT > 1.6f) {
                // the night passes behind the bars; morning comes
                bustStage = -1; g?.moving = false
                px = spec.startX; py = spec.startY; doorOpen = 0f
                val next = ((clock - 360f) / 1440f).toInt() + 1
                clock = (next * 1440 + 360).toFloat(); lastPeriod = Period.ROLL_AM
                heat = 35f; workTonight = null
                startPeriodMsg = "MORNING. YOU SPENT THE NIGHT LOCKED IN YOUR CELL."
                startPeriod(Period.ROLL_AM)
            }
        }
    }

    /** The last thing you picked up or made, and how long ago (for the pop-up over your head). */
    var gotItem = ""; private set
    var gotT = 9f; private set

    // ---------- the escort: a guard comes for you at each part of the day and walks you there, then keeps watch ----------
    var escort: Npc? = null; private set
    var escortArrived = false; private set
    private var escortGoal: Pair<Int, Int>? = null
    /** Where each part of the day happens (the tile you're walked to). */
    fun goalFor(p: Period): Pair<Int, Int>? = when (p) {
        Period.BREAKFAST, Period.LUNCH, Period.DINNER -> 15 to 18
        Period.WORK_AM, Period.WORK_PM -> 64 to 44
        Period.SHOWERS -> 11 to 3
        Period.ROLL_PM -> myCell to 17
        else -> null
    }
    private fun assignEscort(p: Period) {
        // last part's guards go back to their rounds
        for (n in npcs) if (n.guard) { n.post = null; n.escorting = false; n.hold = false; n.attn = WATCH; n.awayStage = -1 }
        escort = null; escortArrived = false; escortGoal = null
        val goal = goalFor(p) ?: return
        if (solitary || disguised || phase != Phase.PLAY) return
        if (trusted && !(p.work || p == Period.ROLL_PM)) return        // trusted: you make your own way, except to work and the evening count
        val g = npcs.filter { it.guard }.minByOrNull { hypot(it.x - px, it.y - py) } ?: return
        // he turns up a couple of steps from you
        nearTile(floor(px).toInt() to floor(py).toInt(), 2, 3)?.let { (c, r) -> g.x = c + 0.5f; g.y = r + 0.5f }
        g.escorting = true; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), goal); g.wait = 0f
        speak(g, when (p) { Period.ROLL_PM -> "COUNT. BACK TO YOUR CELL, TELLEM."; Period.SHOWERS -> "SHOWERS. MOVE."; else -> "MOVE, TELLEM. ${p.label}." })
        escort = g; escortGoal = goal
    }
    /** A walkable tile between [lo] and [hi] steps from [from] (for the escort to turn up on). */
    private fun nearTile(from: Pair<Int, Int>, lo: Int, hi: Int): Pair<Int, Int>? {
        val seen = HashMap<Pair<Int, Int>, Int>(); val q = ArrayDeque<Pair<Int, Int>>(); seen[from] = 0; q.add(from)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst(); val d = seen[cur]!!
            if (d in lo..hi && tile(cur.first, cur.second) in ".,ozh") return cur
            if (d >= hi) continue
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) { val n = (cur.first + dc) to (cur.second + dr); if (n !in seen && (walkable(n.first, n.second) || tile(n.first, n.second) == 'O')) { seen[n] = d + 1; q.add(n) } }
        }
        return null
    }
    private fun escortTick(dt: Float) {
        val g = escort ?: return
        if (escortArrived) return
        val d = hypot(g.x - px, g.y - py)
        val p = period
        val there = atPlace(p.place)                        // already where you're meant to be: he just follows on
        g.hold = d > 3.2f && !there                         // otherwise he waits for you to catch up
        if (d > 6f && !there) { heat += 5f * dt; if (g.sayT <= 0f) speak(g, "KEEP UP, TELLEM.") }
        if (d > 12f && !atPlace(p.place)) {                 // too far: he comes and gets you
            px = g.x; py = g.y + 0.6f; if (!walkable(floor(px).toInt(), floor(py).toInt())) { px = g.x; py = g.y }
            heat += 10f; punish(); say("THE GUARD GRABS YOUR ARM AND DRAGS YOU ALONG.", true)
        }
        if (g.path.isEmpty()) { escortArrived = true; g.escorting = false; g.hold = false; g.post = escortGoal; g.attn = WATCH; g.attnT = 7f + rnd.nextFloat() * 3f; speak(g, "IN YOU GO. I'M WATCHING.") }
    }
    private fun speak(n: Npc, line: String) { n.say = line; n.sayT = 2.8f + line.length * 0.035f }

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
            heat += 20f; punish(); say("THE GUARDS DRAG YOU BACK TO YOUR CELL.", true)
        }
        // a guard who sees you where you shouldn't be gets suspicious
        watchers.clear()
        val due = dueAt
        if (due != null && !disguised && p != Period.LIGHTS_OUT && periodMin > GRACE_MIN && !atPlace(due)) {
            for (n in npcs) if (n.guard && sees(n)) watchers += n.id
            if (watchers.isNotEmpty()) { heat += 4f * dt; if (msgT <= 0f) say("A GUARD IS WATCHING YOU. YOU SHOULD BE AT THE ${due}.", true) }
        }
        if (watchers.isEmpty()) heat = max(0f, heat - 0.4f * dt)
        if (heat >= 100f && !solitary) bust(npcs.firstOrNull { it.guard && sees(it) }, null)
        heat = heat.coerceIn(0f, 100f)
    }


    /** Can this guard see you (close enough, and no wall in the way)? */
    fun sees(n: Npc): Boolean {
        if (n.attn != WATCH) return false
        val d = hypot(n.x - px, n.y - py)
        if (d > SEE) return false
        if (d > 1.6f) { var da = kotlin.math.atan2(py - n.y, px - n.x) - n.face; while (da > Math.PI) da -= (2 * Math.PI).toFloat(); while (da < -Math.PI) da += (2 * Math.PI).toFloat(); if (abs(da) > CONE) return false }
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

    /** What you should do right now, in plain words, and the tile to point an arrow at (or null). */
    fun nextStep(): Pair<String, Pair<Int, Int>?> {
        val p = period
        if (phase != Phase.PLAY) return "" to null
        if (solitary) return "SOLITARY. WAIT FOR MORNING." to null
        if (bustStage >= 0) return "" to null
        when (countStage) {
            0 -> return "WAIT FOR THE DOORS" to null
            1 -> return (if (onSpot) "STAY ON YOUR SQUARE" else "STEP OUT ONTO YOUR SQUARE") to countSpot
            2, 3 -> return "STAND STILL FOR THE COUNT" to (if (onSpot) null else countSpot)
        }
        val g = escort
        if (g != null && !escortArrived) return "FOLLOW THE GUARD" to (floor(g.x).toInt() to floor(g.y).toInt())
        if (p.work && !shiftDone) {
            if (!atPlace("LAUNDRY")) return "GO TO THE LAUNDRY" to goalFor(p)
            if (folding >= 0f) return "HOLD STILL, FOLDING..." to null
            fun idle(dryer: Boolean) = machines.firstOrNull { it.dryer == dryer && !it.running && !it.ready }
            return when (carry) {
                Carry.NONE -> machines.firstOrNull { it.ready }?.let { (if (it.dryer) "TAKE THE DRY LAUNDRY OUT" else "TAKE THE WET LAUNDRY OUT") to (it.c to it.r) }
                    ?: if (dirtyBags > 0) "TAKE A DIRTY BAG FROM THE CART" to (61 to 45) else "WAIT FOR THE MACHINES" to null
                Carry.DIRTY -> idle(false)?.let { "PUT IT IN A WASHER" to (it.c to it.r) } ?: ("WAIT FOR A FREE WASHER" to null)
                Carry.WET -> idle(true)?.let { "PUT IT IN A DRYER" to (it.c to it.r) } ?: ("WAIT FOR A FREE DRYER" to null)
                Carry.DRY -> "FOLD IT ON THE TABLE" to (65 to 42)
                Carry.FOLDED -> "PUT IT IN THE CLEAN CART" to (69 to 45)
            }
        }
        val due = dueAt
        if (due != null) return (if (due == "YOUR CELL") "STAND AT YOUR CELL DOOR" else "GO TO THE $due") to (if (due == "YOUR CELL") myCell to 17 else goalFor(p))
        return when {
            p == Period.FREE -> "FREE TIME. LOOK AROUND, TALK, PLAN." to null
            p == Period.LIGHTS_OUT -> "LIGHTS OUT" to null
            p.work -> "LOAD DONE. KEEP YOUR HEAD DOWN." to null
            else -> "STAY PUT UNTIL THE GUARD MOVES YOU." to null
        }
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
        val s = station() ?: return options().firstOrNull()?.label ?: ""
        if (s.kind == 'C' && canHideInCart()) return "HIDE IN THE CART"
        if (s.kind == 'F' && laundryOpen && carry == Carry.NONE && folding < 0f && wantSheet()) return "TAKE A SHEET"
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
        if (phase != Phase.PLAY) return false
        val s = station() ?: return options().firstOrNull()?.run?.invoke() ?: false
        if (s.kind == 'C' && canHideInCart()) return tryUnseen { escape(Route.CART) }
        if (s.kind == 'F' && laundryOpen && carry == Carry.NONE && folding < 0f && wantSheet()) return steal(Item.SHEET)
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

    // ---------- stealing, making things, and the ways out ----------
    class Option(val label: String, val run: () -> Boolean)
    private fun near(c: Int, r: Int, d: Float = 1.45f) = hypot(c + 0.5f - px, r + 0.5f - py) < d
    private fun nearTile(chars: String, zoneName: String? = null): Boolean {
        val c0 = floor(px).toInt(); val r0 = floor(py).toInt()
        for (r in r0 - 2..r0 + 2) for (c in c0 - 2..c0 + 2) if (tile(c, r) in chars && near(c, r) && (zoneName == null || PrisonLevel.zoneName(spec, c, r) == zoneName || PrisonLevel.zoneName(spec, c, r + 1) == zoneName)) return true
        return false
    }
    private fun guardSees() = npcs.any { it.guard && sees(it) }
    private fun wantSheet() = !has(Item.ROPE) && (items[Item.SHEET] ?: 0) < 3
    private fun canHideInCart() = period == Period.WORK_PM && shiftDone && carry == Carry.NONE && !solitary
    /** Do something only if no guard is looking; if one is, it costs you. */
    private fun tryUnseen(what: () -> Unit): Boolean {
        npcs.firstOrNull { it.guard && sees(it) }?.let { bust(it, null); return false }
        what(); return true
    }
    private fun steal(i: Item): Boolean {
        npcs.firstOrNull { it.guard && sees(it) }?.let { bust(it, i); return false }
        give(i)
        say(when (i) {
            Item.SPOON -> "A SPOON. THE FLOOR UNDER YOUR BUNK IS SOFT... AT NIGHT."
            Item.FORK -> "A FORK. BENT RIGHT, IT COULD HOOK ONTO SOMETHING."
            Item.FILE -> "A FILE. THE VENT BEHIND YOUR SINK LOOKS WEAK."
            Item.PIPE -> "A LOOSE PIPE. GOOD FOR A HOOK, WITH A FORK."
            Item.SHEET -> "A SHEET (${items[Item.SHEET]}/3). KNOTTED TOGETHER THEY'D MAKE A ROPE."
            Item.POSTER -> "A POSTER. IT WOULD HIDE A HOLE IN A WALL."
            Item.UNIFORM -> "A GUARD UNIFORM. YOU'D NEED A GUARD'S HAIRCUT TOO."
            else -> ""
        })
        return true
    }
    private fun escape(r: Route) { route = r; phase = Phase.ESCAPE; sceneT = 0f; moving = false; carry = Carry.NONE; folding = -1f }

    /** Everything you could do where you're standing, best first (the action button does the first one). */
    fun options(): List<Option> {
        if (phase != Phase.PLAY || solitary) return emptyList()
        val o = ArrayList<Option>()
        val p = period
        // the signal: a broken mirror on the shower-room wall
        if (!signalSent && near(4, 1, 1.6f)) o += Option("PICK UP THE MIRROR PIECE") { phase = Phase.SIGNAL; sceneT = 0f; signalSent = true; true }
        // things to steal
        if (near(20, 1, 1.6f) && !has(Item.PIPE) && !has(Item.HOOK)) o += Option("PULL A LOOSE PIPE") { steal(Item.PIPE) }
        val meal = p == Period.BREAKFAST || p == Period.LUNCH || p == Period.DINNER
        if (meal && nearTile("T")) {
            if (!has(Item.SPOON)) o += Option("TAKE A SPOON") { steal(Item.SPOON) }
            else if (!has(Item.FORK) && !has(Item.HOOK)) o += Option("TAKE A FORK") { steal(Item.FORK) }
        }
        if (nearTile("k", "KITCHEN") && !has(Item.FILE)) o += Option("TAKE A FILE") { steal(Item.FILE) }
        if (nearTile("r", "LIBRARY") && !has(Item.POSTER)) o += Option("TAKE A POSTER") { steal(Item.POSTER) }
        if (nearTile("u") && !has(Item.UNIFORM) && !disguised) o += Option("TAKE A UNIFORM") { steal(Item.UNIFORM) }
        if (p == Period.FREE && !haircut && nearTile("c", "BARBER")) o += Option("GET A GUARD'S HAIRCUT") { haircut = true; gotItem = "HAIRCUT"; gotT = 0f; say(if (has(Item.UNIFORM)) "SHORT BACK AND SIDES. PUT THE UNIFORM ON IN YOUR CELL." else "SHORT BACK AND SIDES. VERY GUARD-LIKE."); true }
        // in your cell: make things, and the night's work
        if (inMyCell) {
            if (night) {
                if (digNights >= DIG_NIGHTS) o += Option("CRAWL OUT") { escape(Route.DIG); true }
                if (chipNights >= CHIP_NIGHTS) o += Option("SQUEEZE INTO THE PIPES") { escape(Route.CHIP); true }
                if (workTonight == null) {
                    if (has(Item.SPOON) && digNights < DIG_NIGHTS) o += Option("DIG TONIGHT") { workTonight = "DIG"; say("YOU PUSH THE BUNK ASIDE AND START DIGGING."); true }
                    if (has(Item.FILE) && has(Item.POSTER) && chipNights < CHIP_NIGHTS) o += Option("CHIP THE VENT TONIGHT") { workTonight = "CHIP"; say("YOU START ON THE VENT BEHIND THE SINK."); true }
                }
            }
            if (has(Item.SHEET, 3) && !has(Item.ROPE)) o += Option("KNOT A ROPE") { take(Item.SHEET, 3); give(Item.ROPE); say(if (has(Item.HOOK)) "A ROPE. WITH THE HOOK, GO OVER THE YARD WALL IN FREE TIME." else "A ROPE. NOW A HOOK."); true }
            if (has(Item.FORK) && has(Item.PIPE) && !has(Item.HOOK)) o += Option("BEND A HOOK") { take(Item.FORK); take(Item.PIPE); give(Item.HOOK); say(if (has(Item.ROPE)) "A HOOK. WITH THE ROPE, GO OVER THE YARD WALL IN FREE TIME." else "A HOOK. NOW A ROPE."); true }
            if (has(Item.UNIFORM) && haircut && !disguised) o += Option("PUT ON THE UNIFORM") { take(Item.UNIFORM); disguised = true; say("IN UNIFORM. THE GUARDS' DOOR BY THE FRONT GATE WILL OPEN FOR YOU."); true }
        }
        // over the yard wall in free time
        if (p == Period.FREE && zone == "RECREATION YARD" && py >= 45.2f && has(Item.HOOK) && has(Item.ROPE)) o += Option("THROW THE HOOK") {
            val seer = npcs.firstOrNull { it.guard && sees(it) }
            if (seer != null) { take(Item.HOOK); take(Item.ROPE); bust(seer, Item.HOOK); false } else { escape(Route.WALL); true }
        }
        // out the front gate in uniform
        if (disguised && !night && nearTile("X")) o += Option("WALK OUT THE GATE") { escape(Route.GATE); true }
        // talk to whoever's next to you
        npcs.filter { hypot(it.x - px, it.y - py) < 1.5f }.minByOrNull { hypot(it.x - px, it.y - py) }?.let { n ->
            o += Option(if (n.guard) "TALK TO THE GUARD" else "TALK TO ${n.persona?.name ?: "HIM"}") { talk(n); true }
        }
        return o
    }

    /** What someone says when you walk up to them. */
    fun talk(n: Npc) {
        n.face = kotlin.math.atan2(py - n.y, px - n.x); n.heard = 3f
        if (n.guard) {
            val p = period
            val line = when {
                heat >= 70f -> listOf("ONE MORE STEP OUT OF LINE AND IT'S THE HOLE, TELLEM.", "I'VE WRITTEN YOUR NAME DOWN TWICE TODAY.").random(rnd)
                heat >= 30f -> listOf("I'VE GOT MY EYE ON YOU, TELLEM.", "QUIET DAY. KEEP IT THAT WAY.").random(rnd)
                else -> when (p) {
                    Period.ROLL_AM, Period.ROLL_PM -> "STAND AT YOUR DOOR FOR THE COUNT."
                    Period.BREAKFAST -> "EAT. YOU'VE GOT LAUNDRY AFTER."
                    Period.LUNCH -> "BACK TO THE LAUNDRY AFTER THIS. SECOND LOAD."
                    Period.WORK_AM, Period.WORK_PM -> "THREE BAGS. WASHED, DRIED, FOLDED. MOVE."
                    Period.FREE -> "FREE TIME. DON'T MAKE ME REGRET IT."
                    Period.DINNER -> "LAST MEAL OF THE DAY. SHOWERS AFTER."
                    Period.SHOWERS -> "IN AND OUT, TELLEM."
                    Period.LIGHTS_OUT -> "LIGHTS OUT. GET IN YOUR CELL."
                }
            }
            speak(n, line); return
        }
        val pr = n.persona ?: return
        if (pr.lines.isEmpty()) { speak(n, "..."); say("${pr.name} DOESN'T SAY ANYTHING."); return }
        speak(n, pr.lines[n.lineAt % pr.lines.size]); n.lineAt++
    }

    /** After drowning: you wake up in your cell the next morning with everything you had. */
    fun retry() {
        if (!drowned || sceneT < DROWN_S) return
        phase = Phase.PLAY; route = null; sceneT = 0f
        clock = ((day) * 1440 + Period.ROLL_AM.start).toFloat()
        lastPeriod = Period.ROLL_AM; px = spec.startX; py = spec.startY; doorOpen = 1f; doorTarget = 1f
        heat = 30f; solitary = false
        startPeriod(Period.ROLL_AM)
        say("YOU WAKE UP IN YOUR CELL, SOAKED. A BOAT. YOU NEED A BOAT.", true)
    }

    // for the tests
    internal fun debugGive(i: Item, n: Int = 1) { repeat(n) { give(i) } }
    internal fun debugHaircut() { haircut = true }
    internal fun debugEndCount() { if (countStage < 0) return; countStage = -1; lockedInSpot = false; enforcer = null; beatT = -1f; attended = true; clipboards.clear(); doorOpen = 1f; doorTarget = 1f; assignEscortForNow() }

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
                Period.FREE -> freeSpot(n)
            }
            if (target == null) { n.stay = false; n.wait = rnd.nextFloat() * 4f; continue }
            n.stay = true
            n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), target)
            n.wait = 0f
        }
    }

    /** Where an inmate spends his free time, by what he likes doing (null = he wanders). */
    private fun freeSpot(n: Npc): Pair<Int, Int>? = when (n.persona?.activity) {
        "weights" -> (30 + n.id % 6 * 3) to 43
        "pushups" -> (14 + n.id % 4 * 4) to 45
        "reader" -> (29 + n.id % 5) to 33
        "cards" -> (11 + n.id % 3) to 11
        "talker" -> (8 + n.id % 5 * 3) to 41
        "loner" -> (54 - n.id % 3) to 46
        "sleeper", "pacer" -> n.home
        else -> null
    }
    /** What an inmate is doing where he stands (the scene draws it): push-ups, weights, reading, cards, sleeping... */
    fun activityOf(n: Npc): String = if (!n.guard && n.stay && n.path.isEmpty() && period == Period.FREE) n.persona?.activity ?: "" else if (!n.guard && n.persona?.activity == "sleeper" && night) "sleeper" else ""

    private fun updateNpc(n: Npc, dt: Float) {
        n.sayT = max(0f, n.sayT - dt); n.heard = max(0f, n.heard - dt)
        if (n.guard) guardMind(n, dt)
        if (n.heard > 0f) { n.moving = false; n.face = kotlin.math.atan2(py - n.y, px - n.x); return }     // turned to you, talking
        if (n.guard && n.hold) { n.moving = false; return }
        if (n.path.isEmpty() && !n.guard && n.persona?.activity == "pacer" && period == Period.FREE && n.stay) {   // pacing in front of his cell
            val goal = if (floor(n.x).toInt() == n.home.first) (n.home.first + 1) to n.home.second else n.home
            if (walkable(goal.first, goal.second)) n.path = arrayListOf(goal)
        }
        if (n.path.isEmpty() && !n.guard && n.persona?.activity == "sweeper" && period == Period.FREE) n.stay = false
        if (n.path.isEmpty()) {
            n.moving = false
            if (n.stay || n.post != null) return
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
        val sp = (if (n.escorting) 2.2f else if (n.guard) 1.3f else if (n.persona?.activity == "sweeper" && period == Period.FREE) 0.8f else if (n.persona?.activity == "pacer") 0.9f else 1.9f) * dt
        n.moving = true
        if (d <= sp) { n.x = tx; n.y = ty; n.path.removeAt(0) } else { n.x += dx / d * sp; n.y += dy / d * sp; n.face = atan2(dy, dx) }
    }

    /** A guard keeping watch: he looks at you for a while, then gets distracted (radio, another inmate), and now and then steps out. */
    private fun guardMind(n: Npc, dt: Float) {
        val post = n.post
        if (post == null) {   // on his rounds: he looks where he walks, and looks about when he stops
            n.attn = WATCH
            if (n.path.isEmpty() && !n.escorting) n.face += dt * 0.9f
            return
        }
        n.attnT -= dt
        when (n.attn) {
            WATCH -> {
                n.face = kotlin.math.atan2(py - n.y, px - n.x)
                if (n.attnT <= 0f) {
                    if (rnd.nextFloat() < 0.3f) {
                        val far = rounds.filter { val dd = abs(it.first - post.first) + abs(it.second - post.second); dd in 7..11 && PrisonLevel.zoneName(spec, it.first, it.second) != PrisonLevel.zoneName(spec, post.first, post.second) }
                        if (far.isNotEmpty()) { n.attn = AWAY; n.awayStage = 0; n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), far[rnd.nextInt(far.size)]); speak(n, "BACK IN A MINUTE. DON'T TRY ANYTHING."); return }
                    }
                    n.attn = DISTRACT; n.attnT = 3.5f + rnd.nextFloat() * 2f
                    n.face += Math.PI.toFloat()
                    speak(n, listOf("...", "COPY THAT. SAY AGAIN?", "*YAWNS*", "WHAT DID YOU SAY ABOUT MY MOTHER?", "TOWER, THIS IS LAUNDRY. ALL QUIET.").random(rnd))
                }
            }
            DISTRACT -> if (n.attnT <= 0f) { n.attn = WATCH; n.attnT = 7f + rnd.nextFloat() * 4f }
            AWAY -> when (n.awayStage) {
                0 -> if (n.path.isEmpty()) { n.awayStage = 1; n.attnT = 3f + rnd.nextFloat() * 2f }
                1 -> if (n.attnT <= 0f) { n.awayStage = 2; n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), post) }
                else -> if (n.path.isEmpty()) { n.awayStage = -1; n.attn = WATCH; n.attnT = 8f + rnd.nextFloat() * 4f }
            }
        }
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
