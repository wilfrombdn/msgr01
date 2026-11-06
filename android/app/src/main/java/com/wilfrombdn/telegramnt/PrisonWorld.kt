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
import kotlin.random.Random

/**
 * The prison of level 5 (no Android code, so plain tests can drive it).
 *
 * Day one starts with you waking on your bunk: the lights come on, a bell rings, the cell door slides open. After that the prison runs on
 * a daily routine, like a real one: roll call, breakfast, a laundry shift, lunch, a second laundry shift, free time, dinner, showers,
 * roll call, lights out. A day takes about 7.5 real minutes; the night flies by once you are in your cell.
 * Your job is the laundry: each shift is one bulk load of 3 bags (dirty cart, washer, dryer, folding table, clean cart).
 * You can skip any part of the day as long as no guard sees you. Get seen somewhere you shouldn't be (after the time it takes to walk
 * there) and a guard beats you and drags you there; then you don't have to finish it, and the day goes on. Meals are optional: they're
 * the best time to talk to the men. Every second day something big happens ([PrisonEvent]).
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
    private fun say(m: String, bad: Boolean = false) { msg = m; msgT = max(3.2f, readTime(m, 999f)); msgBad = bad }

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
        var door: String? = null  // a guard standing at this room's door, checking people in
        var patrol: List<Pair<Int, Int>>? = null; var patrolAt = 0   // walking a loop (round the dining tables at meals)
        var seat: Pair<Int, Int>? = null                             // an inmate's place at the table this meal
        var job: String? = null; var jobAt = 0; var carrying = false // his work in a shift: "LAUNDRY", "KITCHEN", "LIBRARY" or "RECREATION YARD"
        var glanceT = 0f          // standing still: when he next looks somewhere else
        var px0 = 0f; var py0 = 0f   // where he was last frame (his facing follows how he actually moved)
        var sayAge = 0f           // seconds since he started saying it (the words type in)
        var lineDay = 0           // the day [lineAt] counts for
        // in one of the big events: what he's doing there, how fast he goes, and what he looks at
        var inEvent = false; var act: String? = null; var evSpeed = 2.5f; var look: Pair<Float, Float>? = null
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
        const val GRACE_MIN = 20f         // the least time you get to reach somewhere at the start of each part of the day
        const val EAT_MIN = 12f           // game minutes sat at a table to have eaten
        const val HEARTS = 3
        const val DEAD_S = 2.5f           // the YOU DIED screen before a tap wakes you
        /** The rooms with a guard on the door, the tile he stands on, and the doorway he watches. */
        val DOOR_POSTS = listOf(
            Triple("SHOWERS", 22 to 2, 23 to 3), Triple("DINING HALL", 22 to 16, 23 to 18), Triple("KITCHEN", 6 to 26, 7 to 27),
            Triple("INFIRMARY", 22 to 32, 23 to 33), Triple("RECREATION YARD", 24 to 38, 25 to 37), Triple("LAUNDRY", 60 to 41, 59 to 42),
            Triple("LIBRARY", 36 to 34, 32 to 33), Triple("VISITING ROOM", 62 to 13, 61 to 14), Triple("BARBER", 62 to 20, 61 to 21),
        )
        val ROOMS = DOOR_POSTS.map { it.first }.toSet()
        /** The guards' loops round the dining tables at meals. */
        val MEAL_LOOPS = listOf(listOf(9 to 8, 21 to 8, 21 to 28, 9 to 28), listOf(15 to 9, 15 to 27))
        const val BAGS = 3
        const val WASH_S = 8f
        const val DRY_S = 8f
        const val FOLD_S = 2f
        const val SEE = 7f                // how far the guards see
        const val CONE = 1.15f            // ... and how wide (radians either side of where they look)
        const val WATCH = 0; const val DISTRACT = 1; const val AWAY = 2; const val BUSY = 3   // BUSY: caught up in an event, sees nothing
        const val SPOT_S = 0.6f           // seconds a guard has to see you skipping before he comes for you
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
    /** The seats at the dining tables (the floor just above or below a table), and which way you face sitting there. */
    val seats = ArrayList<Pair<Int, Int>>()
    /** Where the men work in each place during a shift: the floor in front of the carts, machines, stoves, counters and shelves. */
    private val jobSpots = HashMap<String, ArrayList<Pair<Int, Int>>>()
    fun seatFacesDown(s: Pair<Int, Int>) = tile(s.first, s.second + 1) == 'T'

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
        // a guard on every room's door
        for ((i, dp) in DOOR_POSTS.withIndex()) npcs += Npc(200 + i, dp.second.first + 0.5f, dp.second.second + 0.5f, true).also { it.door = dp.first; it.post = dp.second; it.face = atan2(dp.third.second - dp.second.second.toFloat(), dp.third.first - dp.second.first.toFloat()) }
        for (r in 0 until rows) for (c in 0 until cols) {
            val ch = tile(c, r); val z = PrisonLevel.zoneName(spec, c, r)
            val job = when { ch in "UMYFC" -> "LAUNDRY"; ch in "Kk" && (z == "KITCHEN" || PrisonLevel.zoneName(spec, c + 1, r) == "KITCHEN") -> "KITCHEN"; ch == 'r' && z == "LIBRARY" -> "LIBRARY"; else -> null } ?: continue
            for ((dc, dr) in listOf(0 to 1, 1 to 0, -1 to 0, 0 to -1)) { val f = (c + dc) to (r + dr); if (tile(f.first, f.second) == '.') { jobSpots.getOrPut(job) { ArrayList() } += f; break } }
        }
        jobSpots["RECREATION YARD"] = ArrayList(spots["RECREATION YARD"]?.filter { it.second in 42..45 } ?: emptyList())
        for (tr in intArrayOf(10, 13, 16, 22, 25)) for (c in 10..20) if (c != 15) for (sr in intArrayOf(tr - 1, tr + 1)) if (tile(c, sr) == '.' && (c to sr) !in seats) seats += c to sr
        // the first morning: everyone's at roll call by their cells
        for (n in npcs) if (!n.guard) { n.x = n.home.first + 0.5f; n.y = n.home.second + 0.5f; n.stay = true }
        for (n in npcs) { n.px0 = n.x; n.py0 = n.y }
    }
    /** The guards who walk about (not the ones on the doors). */
    private val roamers get() = npcs.filter { it.guard && it.door == null && !it.inEvent && !gone(it) }
    private fun doorGuard(zone: String) = npcs.firstOrNull { it.door == zone }

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
        for (n in npcs) { n.px0 = n.x; n.py0 = n.y }
        val mx0 = px; val my0 = py
        if (dead) { deadT += dt; moving = false; return }
        if (phase == Phase.WAKE) {
            doorOpen = 0f; doorTarget = 0f
            if (t >= WAKE_STAND) face = -kotlin.math.PI.toFloat() / 2f + 0.01f
            if (t >= WAKE_END) { phase = Phase.PLAY; startCount() }
            moving = false
        } else if (bustStage >= 0) {
            bustTick(dt); moving = false
        } else {
            if (countStage >= 0) countTick(dt) else advanceClock(dt)
            if (grabStage >= 0) grabTick(dt)
            // your door slides at its own pace; if it shuts on you, you end up inside
            doorOpen = if (doorOpen < doorTarget) minOf(doorTarget, doorOpen + dt) else max(doorTarget, doorOpen - dt)
            val (dc, dr) = spec.myDoor
            if (doorOpen < 0.9f && floor(px).toInt() == dc && floor(py).toInt() == dr) py = spec.startY
            if (seated && mag > 0.5f && grabStage < 0) standUp()
            moving = mag > 0.05f && !solitary && !lockedInSpot && grabStage < 0 && !seated
            idle = if (moving) 0f else idle + dt
            if (seated) eatTick(dt)
            if (moving) {
                val sp = SPEED * (0.35f + 0.65f * mag)
                face = atan2(iy, ix)
                move(ix * sp * dt, iy * sp * dt)
            }
            laundryTick(dt)
            if (countStage < 0 && grabStage < 0) { escortTick(dt); rules(dt) }
            if (countStage < 0) eventTick(dt)
            zone.let { if (it.isNotEmpty()) visited += it }
        }
        if (fireT >= 0f) fireT += dt
        for (n in npcs) if (!gone(n)) updateNpc(n, dt)
        // everyone faces the way they actually moved (never backwards, never spinning)
        for (n in npcs) { val dx = n.x - n.px0; val dy = n.y - n.py0; if (dx * dx + dy * dy > 1e-6f) { n.face = atan2(dy, dx); n.moving = true } }
        if (grabStage == 2) { val dx = px - mx0; val dy = py - my0; if (dx * dx + dy * dy > 1e-6f) { face = atan2(dy, dx) } }
    }

    // ---------- hearts: every beating costs one; lose all three and you're dead (you wake the same morning, with your things) ----------
    var hearts = HEARTS; private set
    var dead = false; private set
    var deadT = 0f; private set
    private var missedLoads = 0
    private fun loseHeart() {
        hearts--; punish()
        if (hearts <= 0) { dead = true; deadT = 0f; moving = false; seated = false }
    }

    // ---------- a guard comes for you: runs over, maybe hits you once, maybe walks you somewhere by the arm, then goes back ----------
    /** -1 none; 0 he's on his way; 1 the baton; 2 walking you by the arm; */
    var grabStage = -1; private set
    private var grabGoal: Pair<Int, Int>? = null
    private var grabSmack = false
    private var grabLine = ""
    private var grabDone: (() -> Unit)? = null
    private var chaseRepath = 0f
    val beingHauled get() = grabStage == 2
    private fun grab(by: Npc?, smack: Boolean, goal: Pair<Int, Int>?, line: String, done: () -> Unit = {}) {
        if (grabStage >= 0 || dead) return
        val g = by ?: roamers.filter { it !in clipboards && it != escort }.minByOrNull { hypot(it.x - px, it.y - py) } ?: return
        if (g == escort) { escort = null; escortArrived = false }
        enforcer = g; g.path.clear(); g.post = if (g.door != null) g.post else null; g.patrol = null; g.escorting = false; g.hold = false; g.attn = WATCH
        grabStage = 0; grabSmack = smack; grabGoal = goal; grabLine = line; grabDone = done; chaseRepath = 0f; beatT = -1f
        seated = false; folding = -1f
    }
    /** Walks [g] toward you along the corridors (not through walls) at [speed]; returns how far he still is. */
    private fun chase(g: Npc, dt: Float, speed: Float): Float {
        val d = hypot(px - g.x, py - g.y)
        chaseRepath -= dt
        if (chaseRepath <= 0f || g.path.isEmpty()) { chaseRepath = 0.4f; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), floor(px).toInt() to floor(py).toInt(), true); if (g.path.isNotEmpty()) g.path.removeAt(g.path.size - 1) }
        if (d < 1.6f || g.path.isEmpty()) { val mv = min(speed * dt, max(0f, d - 0.75f)); if (d > 0.01f) { g.x += (px - g.x) / d * mv; g.y += (py - g.y) / d * mv } }
        else stepAlong(g, speed * dt)
        return hypot(px - g.x, py - g.y)
    }
    /** Moves [n] up to [dist] along his path. */
    private fun stepAlong(n: Npc, dist: Float) {
        var left = dist
        while (left > 0f && n.path.isNotEmpty()) {
            val (tc, tr) = n.path[0]; val tx = tc + 0.5f; val ty = tr + 0.5f
            val dx = tx - n.x; val dy = ty - n.y; val d = hypot(dx, dy)
            if (d <= left) { n.x = tx; n.y = ty; n.path.removeAt(0); left -= d } else { n.x += dx / d * left; n.y += dy / d * left; left = 0f }
        }
    }
    private fun grabTick(dt: Float) {
        val g = enforcer ?: run { grabStage = -1; return }
        moving = false
        when (grabStage) {
            0 -> if (chase(g, dt, 4.6f) < 0.95f) {
                speak(g, grabLine); face = atan2(g.y - py, g.x - px)
                if (grabSmack) { grabStage = 1; beatT = 0f; events += "beat"; loseHeart() } else startHaul(g)
            }
            1 -> { beatT += dt; if (beatT >= 0.9f) { beatT = -1f; if (dead) { grabStage = -1; enforcer = null } else if (grabGoal != null) startHaul(g) else letGo(g) } }
            2 -> {
                stepAlong(g, 2.5f * dt)
                // you come along, a step behind him, by the arm
                val back = g.face + PI.toFloat()
                val tx = g.x + cos(back) * 0.6f; val ty = g.y + sin(back) * 0.6f
                if (!blockedAt(tx, ty)) { px = tx; py = ty } else { px = g.x; py = g.y }
                if (g.path.isEmpty()) { grabGoal?.let { (c, r) -> px = c + 0.5f; py = r + 0.5f }; letGo(g) }
            }
        }
    }
    private fun startHaul(g: Npc) {
        val goal = grabGoal ?: return letGo(g)
        grabStage = 2; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), goal, true)
        if (g.path.isEmpty()) { px = goal.first + 0.5f; py = goal.second + 0.5f; letGo(g) }
    }
    private fun letGo(g: Npc) {
        grabStage = -1; beatT = -1f; enforcer = null
        // back to where he was: his door, or his rounds
        val home = if (g.door != null) g.post else rounds.filter { hypot(it.first - g.x, it.second - g.y) in 4f..12f }.randomOrNull(rnd)
        if (home != null) g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), home)
        g.wait = 2f
        grabDone?.invoke(); grabDone = null
    }

    // ---------- meals: sit at a table and eat ----------
    var seated = false; private set
    var eatMin = 0f; private set
    val meal get() = period == Period.BREAKFAST || period == Period.LUNCH || period == Period.DINNER
    private fun freeSeatNear(): Pair<Int, Int>? = seats.filter { hypot(it.first + 0.5f - px, it.second + 0.5f - py) < 1.3f && npcs.none { n -> !n.guard && n.seat == it && hypot(n.x - (it.first + 0.5f), n.y - (it.second + 0.5f)) < 0.6f } }.minByOrNull { hypot(it.first + 0.5f - px, it.second + 0.5f - py) }
    private fun sitDown(s: Pair<Int, Int>) { seated = true; px = s.first + 0.5f; py = s.second + 0.5f; face = if (seatFacesDown(s)) PI.toFloat() / 2f else -PI.toFloat() / 2f; say(if (attended) "YOU SIT DOWN." else "YOU SIT DOWN AND EAT. A SPOON'S RIGHT THERE... WHEN NOBODY'S LOOKING.") }
    private fun standUp() { seated = false }
    private fun eatTick(dt: Float) {
        if (!meal) { standUp(); return }
        if (!attended) { eatMin += DAY_RATE * dt; if (eatMin >= EAT_MIN) { attended = true; say("YOU ATE.") } }
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
        if (p.place == null || excused) return null
        if (p == Period.BREAKFAST || p == Period.LUNCH || p == Period.DINNER) return null        // eating is up to you
        if (p.work) return if (shiftDone) null else p.place
        if (p == Period.LIGHTS_OUT) return if (inMyCell || solitary) null else p.place
        return if (attended) null else p.place
    }
    /** You got caught skipping and were dragged here: you don't have to finish it now. */
    var excused = false; private set
    private var spotT = 0f

    private fun advanceClock(dt: Float) {
        val fast = solitary || (night && inMyCell)
        clock += (if (fast) NIGHT_RATE else DAY_RATE) * dt
        val p = period
        periodMin = minute - (if (p == Period.LIGHTS_OUT && minute < Period.ROLL_AM.start) Period.LIGHTS_OUT.start - 1440 else p.start).toFloat()
        if (p != lastPeriod) { endPeriod(lastPeriod); lastPeriod = p; startPeriod(p) }
    }

    private fun endPeriod(p: Period) {
        if (solitary) return
        // skipping it unseen costs nothing: only being seen does (see [rules])
        if (p.work) {
            carry = Carry.NONE; folding = -1f; dirtyBags = 0
            for (m in machines) { m.left = -1f; m.ready = false }
        }
    }

    private fun startPeriod(p: Period) {
        attended = false; attendMin = 0f; eatMin = 0f; wrongRooms = 0; graceFrom = 0f; checkedIn = false; excused = false; spotT = 0f
        for (n in npcs) if (n.guard && n.patrol != null) { n.patrol = null; n.path.clear() }
        // time to get there: as long as the walk takes, with room to spare
        graceMin = goalFor(p)?.let { g -> max(GRACE_MIN, route(floor(px).toInt() to floor(py).toInt(), g, true).size / SPEED * 1.6f * DAY_RATE + 8f) } ?: GRACE_MIN
        when (p) {
            Period.ROLL_AM -> {
                doorTarget = 1f
                when (workTonight) {
                    "DIG" -> { digNights++; workTonight = null; startPeriodMsg = if (digNights >= DIG_NIGHTS) "THE TUNNEL IS DONE. CRAWL OUT TONIGHT." else "YOU DUG ALL NIGHT ($digNights/$DIG_NIGHTS). THE BUNK HIDES THE HOLE." }
                    "CHIP" -> { chipNights++; workTonight = null; startPeriodMsg = if (chipNights >= CHIP_NIGHTS) "THE VENT IS OUT. SQUEEZE THROUGH TONIGHT." else "YOU CHIPPED ALL NIGHT ($chipNights/$CHIP_NIGHTS). THE POSTER HIDES THE HOLE." }
                }
                if (solitary) { solitary = false; px = spec.startX; py = spec.startY; heat = 40f }
                cleanToday = true
                doorTarget = 0f
                startCount()
                return
            }
            Period.LIGHTS_OUT -> { doorTarget = 0f; if (!solitary) say("LIGHTS OUT. GET IN YOUR CELL.") }
            Period.FREE -> say("FREE TIME. GO WHERE YOU LIKE.")
            Period.BREAKFAST, Period.LUNCH, Period.DINNER -> {
                say("INTERCOM: ${p.label} IS SERVED. EAT IF YOU LIKE: THE MEN TALK AT THE TABLES.")
                // two guards walk loops round the tables while the men eat
                for ((i, g) in roamers.sortedBy { hypot(it.x - 15f, it.y - 18f) }.take(2).withIndex()) { if (g == escort || g == enforcer) continue; g.patrol = MEAL_LOOPS[i]; g.patrolAt = 0; g.post = null; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), MEAL_LOOPS[i][0]) }
            }
            else -> if (p.work) {
                shiftDone = false; dirtyBags = BAGS; delivered = 0
                say("LAUNDRY SHIFT: WASH, DRY AND FOLD $BAGS BAGS. OR SKIP IT, IF NOBODY SEES.")
            } else say("${p.label}: GO TO THE ${p.place}. OR DON'T GET SEEN.")
        }
        routineForNpcs(p)
        assignEscort(p)
    }

    private var startPeriodMsg: String? = null
    // ---------- getting where you're due: the door guards check you in; walk into the wrong room twice and you're walked out ----------
    private var graceMin = GRACE_MIN
    private var graceFrom = 0f
    private var wrongRooms = 0
    var checkedIn = false; private set
    private var lastZone = ""

    // ---------- trust: until you get through a whole day without trouble, a guard walks you everywhere ----------
    /** A clean day earns it: then you only get escorted to your laundry shifts and the evening count, and you can roam at meals and in free time. */
    var trusted = false; private set
    var cleanToday = true; private set
    /** Anything the guards punish you for: you lose their trust, and today no longer counts as clean. */
    private fun punish() {
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
        if (evNow != null) { clearEvent(); doneDay = day }
        countStage = 0; countT = 0f; doorTarget = 0f; doorOpen = 0f; lockedInSpot = false; beatT = -1f; enforcer = null
        clipboards.clear(); clipStops.clear(); clipPause.clear()
        if (!inMyCell) { px = spec.startX; py = spec.startY }
        for (n in npcs) if (!n.guard) { n.x = n.home.first + 0.5f; n.y = n.home.second + 0.5f; n.px0 = n.x; n.py0 = n.y; n.path.clear(); n.stay = true; n.seat = null }   // (in the night, behind the bars)
        for (n in npcs) if (n.guard && n.door == null && gone(n)) { n.path.clear() }
        for (n in npcs) if (n.guard && n.door == null) { n.post = null; n.escorting = false; n.hold = false; n.patrol = null }
        seated = false
        escort = null
        say(startPeriodMsg ?: "6:00 AM. DAY $day."); startPeriodMsg = null
    }
    private fun countTick(dt: Float) {
        countT += dt
        when (countStage) {
            0 -> if (countT > 1.8f) {
                countStage = 1; countT = 0f; doorTarget = 1f
                say("INTERCOM: PLEASE EXIT YOUR CELL. STAND FOR ROLL CALL.")
                for (n in npcs) if (!n.guard && !gone(n)) { n.path = route(n.home, countSpotFor(n.home)); n.stay = true }
            }
            1 -> {
                // three guards with clipboards walk to the head of each hall
                if (clipboards.isEmpty()) {
                    val free = roamers.toMutableList()
                    for ((i, row) in COUNT_ROWS.withIndex()) { val start = PrisonLevel.CX0 to row.toInt(); val g = free.minByOrNull { hypot(it.x - start.first, it.y - start.second) } ?: break; free.remove(g)
                        g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), start); g.post = null; g.patrol = null; clipboards += g; clipStops[g.id] = 0; clipPause[g.id] = 0f; clipAt[g.id] = i }
                }
                for (g in clipboards) { stepAlong(g, 3.2f * dt) }
                if (countT > 6f && clipboards.all { it.path.isEmpty() }) { countStage = 2; countT = 0f; if (!onSpot) beatUp() }
            }
            2 -> {
                if (!onSpot && !lockedInSpot) beatUp()
                var allDone = true
                for (g in clipboards) {
                    val stops = (0..9).map { PrisonLevel.CX0 + 3 * it + 1 + 0.5f }
                    val k = clipStops[g.id] ?: 0
                    if (k >= stops.size) continue
                    allDone = false
                    val pz = clipPause[g.id] ?: 0f
                    if (pz > 0f) { clipPause[g.id] = pz - dt; if (pz - dt <= 0f) clipStops[g.id] = k + 1; continue }
                    val row = COUNT_ROWS[clipAt[g.id] ?: 0]
                    g.y += (row - g.y).coerceIn(-2.4f * dt, 2.4f * dt)
                    g.x = min(stops[k], g.x + 2.4f * dt)                                        // a steady walk down the hall
                    if (g.x >= stops[k]) {
                        clipPause[g.id] = 0.45f                                                 // a tick on the clipboard
                        if (abs(row - 18.5f) < 0.1f && abs(stops[k] - (myCell + 0.5f)) < 0.1f && onSpot) speak(g, "TELLEM. HERE.")
                    }
                }
                if (allDone) { countStage = 3; countT = 0f; say("COUNT CLEAR.") }
            }
            3 -> if (countT > 1.6f) {
                countStage = -1; lockedInSpot = false; attended = true
                for (g in clipboards) { g.wait = 1f; rounds.filter { hypot(it.first - g.x, it.second - g.y) in 3f..10f }.randomOrNull(rnd)?.let { g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), it) } }
                clipboards.clear()
                // everyone stays on their spots; breakfast is called
                clock = floor(clock / 1440f) * 1440f + Period.BREAKFAST.start
            }
        }
    }
    private val clipAt = HashMap<Int, Int>()
    private fun beatUp() {
        if (grabStage >= 0 || lockedInSpot) return
        heat += 10f
        say("YOU'RE NOT ON YOUR SQUARE. A GUARD IS COMING.", true)
        grab(roamers.filter { it !in clipboards }.minByOrNull { hypot(it.x - px, it.y - py) }, true, countSpot, "WHAT DID I SAY? ON YOUR SQUARE!") {
            lockedInSpot = true; say("DRAGGED TO YOUR SQUARE. YOU STAY THERE TILL THE COUNT'S DONE.", true)
        }
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
        bustBy = g; bustStage = 0; bustT = 0f; carry = Carry.NONE; folding = -1f; seated = false; chaseRepath = 0f; g.patrol = null
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
                val d = chase(g, dt, 4f)
                if (d <= 0.95f) { bustStage = 1; bustT = 0f; g.path.clear(); speak(g, if (bustTook.isNotEmpty()) "HAND IT OVER." else "YOU'RE DONE FOR TODAY.") }
                face = atan2(g.y - py, g.x - px)
            }
            1 -> if (bustT > 1.8f) { bustStage = 2; bustT = 0f; if (bustTook.isNotEmpty()) say("HE TAKES YOUR ${bustTook}. THEN HE LOCKS YOU IN YOUR CELL.", true) else say("HE LOCKS YOU IN YOUR CELL.", true) }
            else -> if (bustT > 1.6f) {
                // the night passes behind the bars; morning comes
                bustStage = -1; g?.moving = false
                if (evNow != null) { clearEvent(); doneDay = day }
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
        for (n in npcs) if (n.guard && n.door == null && n.patrol == null && n != enforcer) { n.post = null; n.escorting = false; n.hold = false; n.attn = WATCH; n.awayStage = -1 }
        escort?.let { it.escorting = false; it.hold = false }
        escort = null; escortArrived = false; escortGoal = null; escortComing = false
        // nobody walks you anywhere any more: one guard goes and keeps watch where you're due (watching, distracted, stepping out)
        dutyGuard = null
        if (p.work || p == Period.SHOWERS) postDutyGuard(p)
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
    private var escortComing = false
    /** The guard keeping watch over where you're due this part of the day. */
    var dutyGuard: Npc? = null; private set
    private fun postDutyGuard(p: Period) {
        val goal = goalFor(p) ?: return
        val g = roamers.filter { it.patrol == null && it != enforcer }.minByOrNull { hypot(it.x - (goal.first + 0.5f), it.y - (goal.second + 0.5f)) } ?: return
        val spot = nearTile(goal, 2, 3) ?: goal
        g.post = spot; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), spot); g.attn = WATCH; g.attnT = 8f + rnd.nextFloat() * 3f; g.wait = 0f
        dutyGuard = g
    }
    private fun escortTick(dt: Float) {
        val g = escort ?: return
        if (escortArrived) return
        if (escortComing) {
            if (chase(g, dt, 2.6f) < 2.4f || atPlace(period.place)) {
                escortComing = false; g.path = route(floor(g.x).toInt() to floor(g.y).toInt(), escortGoal ?: return)
                val p = period
                speak(g, when (p) { Period.ROLL_PM -> "COUNT. BACK TO YOUR CELL, TELLEM."; Period.SHOWERS -> "SHOWERS. MOVE."; else -> "MOVE, TELLEM. ${p.label}." })
            }
            return
        }
        val d = hypot(g.x - px, g.y - py)
        val p = period
        val there = atPlace(p.place)                        // already where you're meant to be: he just follows on
        // ahead of him on the way there is fine; only lagging behind counts
        val goal = escortGoal; val behind = goal == null || hypot(goal.first + 0.5f - px, goal.second + 0.5f - py) > hypot(goal.first + 0.5f - g.x, goal.second + 0.5f - g.y) + 1f
        g.hold = behind && d > 3.2f && !there               // he waits for you to catch up
        if (behind && d > 6f && !there) { heat += 3f * dt; if (g.sayT <= 0f) speak(g, "KEEP UP, TELLEM.") }
        val late = periodMin - graceFrom > graceMin
        if (late && d > 12f && !there) {                    // out of time and nowhere near: he comes back and walks you there by the arm
            val goal = escortGoal; escort = null; g.escorting = false; g.hold = false
            heat += 10f; punish(); say("THE GUARD COMES BACK FOR YOU.", true)
            grab(g, false, goal, "THAT'S IT. YOU'RE COMING WITH ME.") { say("WALKED TO THE ${p.place}.", true) }
            return
        }
        if (g.path.isEmpty()) {
            escortArrived = true; g.escorting = false; g.hold = false; g.attn = WATCH; g.attnT = 7f + rnd.nextFloat() * 3f
            if (meal) { g.patrol = MEAL_LOOPS[1]; g.patrolAt = 0 } else g.post = escortGoal
            speak(g, if (meal) "SIT DOWN AND EAT." else "IN YOU GO. I'M WATCHING.")
        }
    }
    private fun speak(n: Npc, line: String) { n.say = line; n.sayT = readTime(line, 30f); n.sayAge = 0f }

    private fun rules(dt: Float) {
        val p = period
        // turning up
        if (!attended && !p.work && !meal && p.place != null && p != Period.LIGHTS_OUT && atPlace(p.place)) {
            attendMin += DAY_RATE * dt
            if (attendMin >= ATTEND_MIN) {
                attended = true
                say(when (p) { Period.ROLL_AM, Period.ROLL_PM -> "COUNTED."; Period.SHOWERS -> "SHOWERED."; else -> "YOU ATE." })
            }
        }
        // walking into a room: the guard on its door checks you in, or turns you round
        val z = zone
        if (z != lastZone) { val from = lastZone; lastZone = z; if (z in ROOMS && from.isNotEmpty()) enteredRoom(z) }
        // lights out: ten minutes to get in, then a guard walks you there
        if (p == Period.LIGHTS_OUT && !solitary && !inMyCell && minute >= Period.LIGHTS_OUT.start + 10) {
            doorTarget = 1f; doorOpen = 1f
            heat += 20f; punish(); say("LIGHTS OUT. A GUARD IS COMING FOR YOU.", true)
            grab(null, false, floor(spec.startX).toInt() to floor(spec.startY).toInt(), "LIGHTS OUT MEANS LIGHTS OUT, TELLEM.") { doorTarget = 0f; say("WALKED BACK TO YOUR CELL.", true) }
            return
        }
        // skipping: fine while nobody sees you. A guard who sees you for a moment beats you and drags you there; then you're excused.
        watchers.clear()
        val due = dueAt
        if (due != null && !disguised && p != Period.LIGHTS_OUT && periodMin - graceFrom > graceMin && !atPlace(due)) {
            for (n in npcs) if (n.guard && sees(n)) watchers += n.id
        }
        if (watchers.isNotEmpty() && due != null) {
            if (spotT == 0f) say("A GUARD HAS SEEN YOU!", true)
            spotT += dt
            if (spotT >= SPOT_S) { caughtSkipping(npcs.first { it.id in watchers }, due); return }
        } else spotT = 0f
        if (watchers.isEmpty()) heat = max(0f, heat - 0.4f * dt)
        if (heat >= 100f && !solitary) bust(npcs.firstOrNull { it.guard && sees(it) }, null)
        heat = heat.coerceIn(0f, 100f)
    }


    private fun caughtSkipping(g: Npc, due: String) {
        spotT = 0f; watchers.clear(); heat += 10f; punish()
        val p = period
        say("CAUGHT SKIPPING ${p.label}.", true)
        val goal = goalFor(p) ?: (myCell to 17)
        grab(g, true, goal, listOf("WHERE DO YOU THINK YOU'RE GOING?", "SKIPPING, TELLEM? NOT ON MY WATCH.", "WRONG WAY, TELLEM.").random(rnd)) {
            excused = true
            say("DRAGGED TO THE ${if (due == "YOUR CELL") "COUNT" else due}. YOU DON'T HAVE TO FINISH IT NOW.", true)
        }
    }

    private fun enteredRoom(z: String) {
        val g = doorGuard(z)
        val due = dueAt
        when {
            due == null || disguised -> {}
            z == due || (due == "LAUNDRY" && z == "RECREATION YARD") -> {
                if (z == due && !checkedIn) { checkedIn = true; g?.let { speak(it, "TELLEM. CHECKED IN.") } }
            }
            else -> {}                                                           // the wrong room is only trouble if a guard sees you (see [rules])
        }
    }

    /** Can this guard see you (close enough, and no wall in the way)? */
    fun sees(n: Npc): Boolean {
        if (n.attn != WATCH || n.inEvent || gone(n)) return false
        val d = hypot(n.x - px, n.y - py)
        if (d > seeRange) return false
        if (d > 1.6f) { var da = kotlin.math.atan2(py - n.y, px - n.x) - n.face; while (da > Math.PI) da -= (2 * Math.PI).toFloat(); while (da < -Math.PI) da += (2 * Math.PI).toFloat(); if (abs(da) > (if (alert) 1.45f else CONE)) return false }
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
        if (grabStage >= 0) return "" to null
        val g = escort
        if (g != null && !escortArrived && !escortComing) return "FOLLOW THE GUARD" to (floor(g.x).toInt() to floor(g.y).toInt())
        if (meal && seated) return (if (attended) "PUSH THE STICK TO GET UP" else "EATING... (A SPOON, WHEN THEY LOOK AWAY?)") to null
        if (meal) return (if (atPlace("DINING HALL")) "EAT IF YOU LIKE, OR TALK" else "OPTIONAL. GOOD TIME TO TALK") to null
        if (p.work && excused) return "DRAGGED HERE. NO NEED TO FINISH" to null
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
        if (due != null) return (if (due == "YOUR CELL") "STAND AT YOUR CELL DOOR" else "GO TO THE $due (OR SKIP, UNSEEN)") to (if (due == "YOUR CELL") myCell to 17 else goalFor(p))
        return when {
            p == Period.FREE -> "FREE TIME. LOOK AROUND, TALK, PLAN." to null
            p == Period.LIGHTS_OUT -> "LIGHTS OUT" to null
            p.work -> "LOAD DONE. KEEP YOUR HEAD DOWN." to null
            else -> "DONE. SLIP OFF IF NOBODY LOOKS" to null
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
        if (meal && !seated) freeSeatNear()?.let { s0 -> o += Option(if (attended) "SIT DOWN" else "SIT DOWN AND EAT") { sitDown(s0); true } }
        if (meal && seated) {
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
        npcs.filter { hypot(it.x - px, it.y - py) < 1.5f && !gone(it) && !it.inEvent }.minByOrNull { hypot(it.x - px, it.y - py) }?.let { n ->
            o += Option(if (n.guard) "TALK TO THE GUARD" else "TALK TO ${n.persona?.name ?: "HIM"}") { talk(n); true }
        }
        return o
    }

    /** What a thing is good for (the inventory shows it). */
    fun hintFor(i: Item): String = when (i) {
        Item.SPOON -> "DIG UNDER YOUR BUNK AT NIGHT (${DIG_NIGHTS} NIGHTS)."
        Item.FORK -> "BENT ROUND A PIPE, IT MAKES A HOOK."
        Item.FILE -> "WITH A POSTER TO HIDE IT: CHIP THE VENT AT NIGHT."
        Item.PIPE -> "WITH A FORK: BEND A HOOK IN YOUR CELL."
        Item.SHEET -> "THREE OF THEM KNOT INTO A ROPE (IN YOUR CELL)."
        Item.POSTER -> "HIDES A HOLE IN YOUR CELL WALL."
        Item.UNIFORM -> "WITH A GUARD'S HAIRCUT: PUT IT ON IN YOUR CELL."
        Item.ROPE -> "WITH A HOOK: OVER THE YARD WALL IN FREE TIME."
        Item.HOOK -> "WITH A ROPE: OVER THE YARD WALL IN FREE TIME."
    }

    /** What someone says when you walk up to them. */
    fun talk(n: Npc) {
        n.face = kotlin.math.atan2(py - n.y, px - n.x)
        if (n.lineDay != day) { n.lineDay = day; n.lineAt = 0 }
        if (n.guard) {
            val p = period
            val mine = PrisonEvents.talkedAbout(day, doneDay == day)?.involved?.get(keyOf(n))
            val line = when {
                mine != null -> mine[n.lineAt++ % mine.size]
                alert -> listOf("HIGH ALERT. ONE WRONG MOVE, TELLEM.", "NOBODY ELSE GETS OFF THIS ROCK. NOBODY.").random(rnd)
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
            speak(n, line); n.heard = n.sayT; return
        }
        val pr = n.persona ?: return
        val lines = linesFor(n)
        if (lines.isEmpty()) { speak(n, "..."); n.heard = n.sayT; say("${pr.name} DOESN'T SAY ANYTHING."); return }
        val p = period
        // at work, in the showers and at the counts the men keep their heads down: chow and free time are for talking
        if ((p.work || p == Period.SHOWERS || p == Period.ROLL_PM) && PrisonEvents.talkedAbout(day, doneDay == day)?.involved?.containsKey(pr.name) != true) {
            speak(n, PrisonEvents.BRUSH_OFF[PrisonEvents.mix(day, n.id, n.lineAt++) % PrisonEvents.BRUSH_OFF.size]); n.heard = n.sayT; return
        }
        speak(n, lines[n.lineAt % lines.size]); n.lineAt++; n.heard = n.sayT
    }

    /** What an inmate has to say today: what happened to him, the talk of the prison, one of his own tips, and a couple of things
     *  (tips, jokes, facts, how to dodge the guards) that change every day. */
    fun linesFor(n: Npc): List<String> {
        val pr = n.persona ?: return emptyList()
        val out = ArrayList<String>()
        val ev = PrisonEvents.talkedAbout(day, doneDay == day)
        val hurt = hurtOf(n)
        val mine = ev?.involved?.get(pr.name) ?: if (hurt != null) recentInvolved(pr.name) else null
        if (mine != null) out += mine[PrisonEvents.mix(day, n.id, 1) % mine.size]
        if (pr.lines.isEmpty()) return out                                                      // the quiet ones say nothing else
        if (hurt != null && mine == null) PrisonEvents.HURT[hurt]?.let { out += it[PrisonEvents.mix(day, n.id, 2) % it.size] }
        if (ev != null && mine == null) out += ev.talk[PrisonEvents.mix(day, n.id, 3) % ev.talk.size]
        out += pr.lines[(day - 1 + n.id) % pr.lines.size].takeIf { day > 1 } ?: pr.lines[0]
        val pools = listOf(PrisonEvents.TIPS, PrisonEvents.GUARD_TIPS, PrisonEvents.FACTS, PrisonEvents.JOKES)
        for (k in 0..1) { val pool = pools[(PrisonEvents.mix(day, n.id, 10 + k) + k) % pools.size]; out += pool[PrisonEvents.mix(day, n.id, 20 + k) % pool.size] }
        if (day == 1 && pr.lines.size > 1) out += pr.lines[1]
        return out.distinct()
    }
    /** His own words about an event from the last few days (while it still shows on him). */
    private fun recentInvolved(name: String): List<String>? { for (b in 0..3) PrisonEvents.on(day - b)?.involved?.get(name)?.let { return it }; return null }

    // =================================================================================================================
    //  the big events: one every second day (PrisonEvent). What they leave behind comes from the day alone (PrisonEvents.marks);
    //  only the scene itself runs here, as a list of steps.
    // =================================================================================================================
    /** The event playing out right now, if any. */
    var evNow: PrisonEvent? = null; private set
    private var firedDay = 0
    /** The day the last event finished (today's has happened once this is [day]). */
    var doneDay = 0; private set
    private val steps = ArrayList<(Float) -> Boolean>()
    private var stepAt = 0; private var stepT = 0f; private var beatK = -1
    private val evPeople = ArrayList<Npc>()
    var evCenter = 0 to 0; private set
    /** The kitchen fire (seconds since it started, -1 = none), and the smoke it leaves till this clock time. */
    var fireT = -1f; private set
    var smokeUntil = -1f; private set
    /** The power cut: dark till this clock time. */
    var blackoutUntil = -1f; private set
    val blackout get() = blackoutUntil > clock
    /** Something dropped on the floor in an event (the arrested guard's pills), in tiles. */
    var dropped: Pair<Float, Float>? = null; private set
    /** The two days after an escape: the guards see further and never look away. */
    val alert get() = PrisonEvents.alertOn(day)
    val seeRange get() = if (blackout) 3f else if (alert) 9.5f else SEE

    fun keyOf(n: Npc): String = if (n.guard) "GUARD#${n.id}" else n.persona?.name ?: "#${n.id}"
    private var marksDay = -1; private var marksDone = false; private var marks: Map<String, Set<String>> = emptyMap()
    private fun marksNow(): Map<String, Set<String>> {
        val dn = doneDay == day
        if (marksDay != day || marksDone != dn) { marksDay = day; marksDone = dn; marks = PrisonEvents.marks(day, dn) }
        return marks
    }
    /** Out of the prison for now (escaped, in the hole, in the infirmary, arrested). */
    fun gone(n: Npc): Boolean = !n.inEvent && marksNow()[keyOf(n)]?.contains("gone") == true
    /** What an event left on him: "bruised", "bandaged", "burned" or null. */
    fun hurtOf(n: Npc): String? = marksNow()[keyOf(n)]?.firstOrNull { it != "gone" }
    private fun who(name: String): Npc? = npcs.firstOrNull { keyOf(it) == name }

    private fun eventTick(dt: Float) {
        if (evNow == null) {
            val today = PrisonEvents.on(day) ?: return
            if (firedDay == day || phase != Phase.PLAY || bustStage >= 0 || dead || period != today.period || periodMin < today.delayMin) return
            startEvent(today); return
        }
        if (stepAt >= steps.size) { endEvent(); return }
        stepT += dt
        if (steps[stepAt](stepT)) { stepAt++; stepT = 0f; beatK = -1 }
    }

    private fun startEvent(e: PrisonEvent) {
        evNow = e; firedDay = day; steps.clear(); stepAt = 0; stepT = 0f; beatK = -1; evPeople.clear(); dropped = null
        val cast = e.cast.mapNotNull { who(it) }.filter { !gone(it) }
        for (n in cast) join(n)
        buildScript(e, cast)
    }

    // ---- building blocks ----
    private fun join(n: Npc) { if (n !in evPeople) evPeople += n; n.inEvent = true; n.act = null; n.look = null; n.seat = null; n.job = null; n.carrying = false; n.heard = 0f; if (n.guard) { n.attn = BUSY; n.patrol = null } }
    private fun floorAt(c: Int, r: Int) = tile(c, r) in ".,ozh"
    /** The nearest floor tile to [t]. */
    private fun floorNear(t: Pair<Int, Int>): Pair<Int, Int> {
        if (floorAt(t.first, t.second)) return t
        for (rad in 1..5) for (dr in -rad..rad) for (dc in -rad..rad) if (abs(dr) == rad || abs(dc) == rad) { val c = t.first + dc; val r = t.second + dr; if (floorAt(c, r)) return c to r }
        return t
    }
    /** Up to [k] floor tiles in a ring round [c] (between [r0] and [r1] tiles away), spread out, not in [skip]. */
    private fun ring(c: Pair<Int, Int>, k: Int, r0: Float, r1: Float, skip: Set<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val reach = HashSet<Pair<Int, Int>>()
        val q = ArrayDeque<Pair<Int, Int>>(); q.add(c); reach += c
        while (q.isNotEmpty()) { val t = q.removeFirst(); for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) { val n = (t.first + dc) to (t.second + dr); if (n !in reach && floorAt(n.first, n.second) && hypot((n.first - c.first).toFloat(), (n.second - c.second).toFloat()) <= r1 + 1f) { reach += n; q.add(n) } } }
        val cand = reach.filter { val d = hypot((it.first - c.first).toFloat(), (it.second - c.second).toFloat()); d in r0..r1 && it !in skip }
            .sortedBy { atan2((it.second - c.second).toFloat(), (it.first - c.first).toFloat()) }
        if (cand.size <= k) return cand
        return (0 until k).map { cand[it * cand.size / k] }
    }
    private fun send(n: Npc, to: Pair<Int, Int>, speed: Float, act: String? = null, look: Pair<Int, Int>? = null) {
        join(n); n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), to); n.evSpeed = speed; n.act = act
        n.look = (look ?: evCenter).let { it.first + 0.5f to it.second + 0.5f }
    }
    /** The men nearest [c] gather round to watch ([act] "cheer" or "watch"). */
    private fun crowd(c: Pair<Int, Int>, k: Int, act: String, r0: Float = 2f, r1: Float = 3.6f): List<Npc> {
        val taken = evPeople.map { floor(it.x).toInt() to floor(it.y).toInt() }.toSet() + evPeople.mapNotNull { it.path.lastOrNull() }
        val spots = ring(c, k, r0, r1, taken + c)
        val men = npcs.filter { !it.guard && !it.inEvent && !gone(it) }.sortedBy { hypot(it.x - c.first, it.y - c.second) }.take(spots.size)
        for ((i, n) in men.withIndex()) send(n, spots[i], 2.8f, act, c)
        return men
    }
    /** The nearest free guards run over to [targets] (one each, standing next to him). */
    private fun guardsTo(k: Int, targets: List<Npc>, near: Pair<Int, Int>): List<Npc> {
        val gs = roamers.filter { it != enforcer && it !in clipboards }.sortedBy { hypot(it.x - near.first, it.y - near.second) }.take(k)
        val busy = evPeople.mapNotNull { it.path.lastOrNull() ?: (floor(it.x).toInt() to floor(it.y).toInt()) }.toMutableSet()
        for ((i, g) in gs.withIndex()) {
            val t = targets.getOrNull(i % targets.size.coerceAtLeast(1))
            val at = if (t == null) near else (t.path.lastOrNull() ?: (floor(t.x).toInt() to floor(t.y).toInt()))
            val spot = ring(at, 1, 1f, 1.5f, busy).firstOrNull() ?: floorNear(at)
            busy += spot
            send(g, spot, 3.4f, null, at)
        }
        if (gs.isNotEmpty()) events += "stomp1"
        return gs
    }
    private fun spot(n: Npc) = n.path.lastOrNull() ?: (floor(n.x).toInt() to floor(n.y).toInt())

    // ---- steps ----
    private fun once(f: () -> Unit): (Float) -> Boolean = { _ -> f(); true }
    private fun wait(s: Float): (Float) -> Boolean = { t -> t >= s }
    private fun arrive(max: Float = 20f, who: () -> List<Npc>): (Float) -> Boolean = { t -> t >= max || who().all { it.path.isEmpty() } }
    /** For [s] seconds, every [every] seconds call [f] with the beat number. */
    private fun during(s: Float, every: Float, f: (Int) -> Unit): (Float) -> Boolean = { t -> val k = (t / every).toInt(); if (k != beatK) { beatK = k; f(k) }; t >= s }

    private fun buildScript(e: PrisonEvent, cast: List<Npc>) {
        val s = steps
        val gs = ArrayList<Npc>(); val watchers = ArrayList<Npc>()
        fun headline() { say(e.headline, true); events += "static" }
        fun lineFrom(list: List<Npc>, lines: List<String>, k: Int) { if (list.isNotEmpty()) speak(list[k % list.size], lines[k % lines.size]) }
        fun beating(targets: List<Npc>, secs: Float, line: String) {
            s += once { for (t in targets) { t.act = "down"; t.look = null }; for (g in gs) g.act = "beat"; speak(gs.firstOrNull() ?: return@once, line) }
            s += during(secs, 0.55f) { k -> events += if (k % 2 == 0) "beat" else "hit"; if (k == 2) lineFrom(watchers, listOf("OHHH!", "THAT'S GOTTA HURT!", "LET HIM UP!", "DAMN..."), k) }
            s += once { for (g in gs) g.act = null }
        }
        fun fight(a: Npc, b: Npc, at: Pair<Int, Int>) {
            evCenter = at
            val sa = floorNear(at.first - 1 to at.second); val sb = ring(at, 1, 1f, 1.5f, setOf(sa)).firstOrNull() ?: floorNear(at.first + 1 to at.second)
            send(a, sa, 3f, null, sb); send(b, sb, 3f, null, sa)
        }
        when (e) {
            PrisonEvent.BRAWL, PrisonEvent.FOODFIGHT -> {
                val food = e == PrisonEvent.FOODFIGHT
                val at = floorNear(if (food) 12 to 19 else 15 to 19); evCenter = at
                if (cast.size >= 2) fight(cast[0], cast[1], at)
                if (food) cast.drop(2).forEach { send(it, ring(at, 1, 2f, 3f, evPeople.map { p -> spot(p) }.toSet()).firstOrNull() ?: at, 3f, null, at) }
                s += once { headline(); speak(cast.getOrNull(0) ?: return@once, if (food) "HEY! CATCH!" else "YOU LOOKING AT MY TRAY?") }
                s += once { watchers += crowd(at, if (food) 10 else 9, "cheer") }
                s += arrive(8f) { cast }
                s += once { for (n in cast) n.act = if (food) "throw" else "fight" }
                s += during(7f, 0.7f) { k -> events += if (food) "throw" else "hit"; lineFrom(watchers, if (food) listOf("FOOD FIGHT!", "INCOMING!", "EAT THIS!", "HAHAHA!") else listOf("FIGHT! FIGHT! FIGHT!", "HIT HIM, LOU!", "GET HIM, TANK!", "OHHH!"), k) }
                s += once { gs += guardsTo(3, listOfNotNull(cast.getOrNull(0), cast.getOrNull(1)), at); speak(gs.firstOrNull() ?: return@once, "BREAK IT UP! NOW!") }
                s += arrive(12f) { gs }
                beating(if (food) cast.take(1) else cast.take(2), 3.5f, if (food) "YOU STARTED THIS, MAGS?" else "ON THE FLOOR! BOTH OF YOU!")
                s += once { speak(gs.firstOrNull() ?: return@once, "SHOW'S OVER. BACK TO YOUR TABLES.") }
                s += wait(2.5f)
            }
            PrisonEvent.DRUGS -> {
                val bad = cast.firstOrNull() ?: run { s += once { }; return }
                val at = floorNear(30 to 42); evCenter = at
                send(bad, at, 2.2f, null, at.first to at.second + 1)
                s += once { headline() }
                s += once { gs += guardsTo(3, listOf(bad), at); speak(gs.firstOrNull() ?: return@once, "OFFICER! STAY RIGHT THERE.") }
                s += once { watchers += crowd(at, 12, "cheer", 2.5f, 4.5f) }
                s += arrive(14f) { gs + bad }
                s += once { speak(gs.firstOrNull() ?: return@once, "EMPTY YOUR POCKETS.") ; bad.act = "handsUp" }
                s += wait(2.2f)
                s += once { dropped = bad.x + 0.5f to bad.y + 0.3f; speak(bad, "IT'S NOT MINE! I'M HOLDING IT FOR A FRIEND!"); events += "thud" }
                s += during(5f, 0.8f) { k -> lineFrom(watchers, listOf("HA! LOOK AT HIM NOW!", "BYE BYE, BOSS!", "HOW'S IT FEEL?", "PUT HIM IN B BLOCK!", "WOOOO!"), k) }
                s += once { speak(gs.firstOrNull() ?: return@once, "YOU'RE UNDER ARREST. HANDS BEHIND YOUR BACK."); bad.act = "cuffed"; events += "keys" }
                s += wait(2f)
                s += once { val out = floorNear(60 to 33); send(bad, out, 1.6f, "cuffed"); gs.take(2).forEach { send(it, floorNear(60 to 32), 1.6f) }; dropped = null }
                s += during(6f, 1f) { k -> lineFrom(watchers, listOf("BYE BYE!", "DON'T DROP THE SOAP, OFFICER!", "HAHAHA!", "WHO'S IN THE CAGE NOW?"), k) }
                s += arrive(30f) { listOf(bad) }
            }
            PrisonEvent.ESCAPE -> {
                evCenter = floorNear(25 to 18)
                s += once { headline() }
                s += once { val far = rounds.filter { hypot(it.first - 25f, it.second - 18f) > 10f }; gs += guardsTo(4, emptyList(), evCenter); for ((i, g) in gs.withIndex()) { send(g, far[(i * 37 + day) % far.size], 3.4f); speak(g, listOf("HE CAN'T HAVE GOT FAR!", "CHECK THE PIPES!", "TOWER, SWEEP THE WATER!", "NOBODY MOVES!")[i % 4]) } }
                s += once { watchers += npcs.filter { !it.guard && !gone(it) && hypot(it.x - px, it.y - py) < 9f }.take(3); lineFrom(watchers, listOf("NUMBERS GOT OUT!", "HA! HE DID IT!", "THEY'LL NEVER FIND HIM."), 0) }
                s += arrive(16f) { gs }
            }
            PrisonEvent.SHOWERS -> {
                val stall = floorNear(2 to 2); val stall2 = floorNear(3 to 2); val mid = floorNear(11 to 5); evCenter = mid
                if (cast.size >= 2) { send(cast[0], stall, 2.2f, null, stall2); send(cast[1], stall2, 2.2f, null, stall) }
                s += arrive(15f) { cast }
                s += wait(3f)
                s += once { gs += guardsTo(1, cast.take(1), stall); speak(gs.firstOrNull() ?: return@once, "WHAT THE HELL IS GOING ON BACK HERE?!") }
                s += arrive(12f) { gs }
                s += once { headline(); watchers += crowd(mid, 9, "watch", 2f, 3.6f) }
                s += once { val g0 = gs.firstOrNull(); if (cast.size >= 2) { send(cast[0], floorNear(mid.first - 1 to mid.second), 1.6f, null, mid); send(cast[1], floorNear(mid.first + 1 to mid.second), 1.6f, null, mid) }; g0?.let { send(it, floorNear(mid.first to mid.second - 1), 1.6f, null, mid); speak(it, "OUT HERE. IN FRONT OF EVERYONE.") }; gs += guardsTo(2, cast, mid) }
                s += arrive(14f) { cast + gs }
                beating(cast, 4.2f, "LET THIS BE A LESSON TO ALL OF YOU.")
                s += during(3f, 1f) { k -> lineFrom(watchers, listOf("...", "JESUS.", "THAT'S ENOUGH, MAN.", "NOBODY SAY A WORD."), k) }
                s += once { speak(gs.firstOrNull() ?: return@once, "SHOWER'S OVER. EVERYBODY OUT.") }
                s += wait(2f)
            }
            PrisonEvent.STABBING -> {
                val at = floorNear(64 to 44); evCenter = at
                val att = cast.getOrNull(0); val vic = cast.getOrNull(1)
                if (att != null && vic != null) fight(att, vic, at)
                s += arrive(14f) { cast }
                s += once { att?.act = "stab"; events += "hit" }
                s += wait(1.2f)
                s += once { vic?.act = "down"; att?.act = null; headline(); vic?.let { speak(it, "AAGH! HE STUCK ME!") }; watchers += crowd(at, 5, "watch") }
                s += once { gs += guardsTo(3, listOfNotNull(att, vic), at); speak(gs.firstOrNull() ?: return@once, "DROP IT! DROP IT NOW!") }
                s += arrive(12f) { gs }
                beating(listOfNotNull(att), 3f, "ON THE GROUND, RAY!")
                s += once {
                    att?.let { send(it, floorNear(38 to 34), 1.6f, "cuffed"); gs.getOrNull(0)?.let { g -> send(g, floorNear(38 to 32), 1.6f) } }
                    vic?.let { send(it, floorNear(10 to 33), 1.4f); gs.getOrNull(1)?.let { g -> send(g, floorNear(11 to 33), 1.4f) } }
                    speak(gs.getOrNull(2) ?: return@once, "BACK TO WORK. ALL OF YOU.")
                }
                s += arrive(40f) { listOfNotNull(att, vic) }
            }
            PrisonEvent.FIRE -> {
                val stove = floorNear(2 to 16); val front = floorNear(10 to 18); evCenter = front
                val cook = cast.firstOrNull()
                cook?.let { send(it, stove, 2.5f, null, 1 to 16) }
                s += arrive(12f) { listOfNotNull(cook) }
                s += once { fireT = 0f; headline(); events += "boom"; cook?.let { speak(it, "FIRE! FIRE! MY ARM!") } }
                s += once { cook?.let { send(it, floorNear(5 to 18), 3.4f, "down", 1 to 16) }; watchers += crowd(front, 10, "watch", 2f, 4f) }
                s += once { gs += guardsTo(2, emptyList(), floorNear(3 to 17)); for (g in gs) { g.look = 1.5f to 16.5f }; speak(gs.firstOrNull() ?: return@once, "GET BACK! EVERYBODY BACK!") }
                s += arrive(12f) { gs }
                s += once { for (g in gs) g.act = "spray" }
                s += during(6f, 1f) { k -> events += "static"; lineFrom(watchers, listOf("COUGH... COUGH...", "LET IT BURN!", "THERE GOES DINNER.", "IS COOKIE OK?"), k) }
                s += once { fireT = -1f; smokeUntil = clock + 60f; for (g in gs) g.act = null; speak(gs.firstOrNull() ?: return@once, "IT'S OUT. NOBODY TOUCH ANYTHING.") }
                s += wait(2.5f)
            }
            PrisonEvent.SHAKEDOWN -> {
                val vic = cast.firstOrNull() ?: run { s += once { }; return }
                val door = floorNear(countSpotFor(vic.home)); evCenter = door
                send(vic, door, 2.6f, null, vic.home)
                s += once { headline(); gs += guardsTo(3, listOf(vic), door) }
                s += once { watchers += crowd(door, 7, "watch", 2f, 3.6f) }
                s += arrive(16f) { gs + vic }
                s += once { gs.firstOrNull()?.let { send(it, vic.home, 1.4f, "search", vic.home.first to vic.home.second - 1) }; speak(vic, "THERE'S NOTHING IN THERE, BOSS.") }
                s += arrive(6f) { gs.take(1) }
                s += during(3f, 0.6f) { k -> events += "clank" }
                s += once { gs.firstOrNull()?.let { send(it, floorNear(vic.home.first to vic.home.second + 1), 1.4f, null, door); speak(it, "WELL, WELL. WHAT'S THIS THEN?") }; dropped = vic.home.first + 0.5f to vic.home.second + 0.8f }
                s += arrive(4f) { gs.take(1) }
                beating(listOf(vic), 3.2f, "A SHANK, VIC? TWO DAYS IN THE HOLE.")
                s += once { dropped = null; send(vic, floorNear(38 to 34), 1.6f, "cuffed"); gs.take(2).forEach { send(it, floorNear(38 to 32), 1.6f) } }
                s += arrive(40f) { listOf(vic) }
            }
            PrisonEvent.BLACKOUT -> {
                evCenter = floorNear(15 to 19)
                s += once { blackoutUntil = clock + 50f; headline(); events += "thud" }
                s += once { watchers += npcs.filter { !it.guard && !gone(it) && it.zone() == "DINING HALL" }.take(6) }
                s += during(8f, 1.1f) { k -> lineFrom(watchers, listOf("WOOOOO!", "WHO TURNED OUT THE LIGHTS?", "AWOOOO!", "GRAB THE PUDDING!", "HAHAHA!"), k) }
                s += once { roamers.firstOrNull()?.let { speak(it, "NOBODY MOVE! STAY IN YOUR SEATS!") } }
                s += wait(1f)
            }
            PrisonEvent.JUMPED -> {
                val at = floorNear(20 to 43); evCenter = at
                val guard = cast.firstOrNull { it.guard }; val men = cast.filter { !it.guard }
                guard?.let { send(it, at, 2.2f, null, at.first to at.second + 1) }
                men.forEachIndexed { i, m -> send(m, ring(at, 1, 1f, 1.5f, evPeople.map { p -> spot(p) }.toSet() + at).firstOrNull() ?: floorNear(at.first + i + 1 to at.second), 3f, null, at) }
                s += arrive(16f) { cast }
                s += once { headline(); guard?.act = "down"; for (m in men) m.act = "fight"; guard?.let { speak(it, "HELP! OFFICER DOWN!") } }
                s += once { watchers += crowd(at, 11, "cheer", 2.5f, 4.5f) }
                s += during(6f, 0.7f) { k -> events += "hit"; lineFrom(watchers, listOf("GET HIM!", "PAYBACK!", "WOOOO!", "STOMP HIM!"), k) }
                s += once { gs += guardsTo(4, men, at); speak(gs.firstOrNull() ?: return@once, "GET OFF HIM! GET OFF HIM NOW!") }
                s += arrive(12f) { gs }
                beating(men, 4f, "YOU TOUCH A GUARD, YOU BLEED.")
                s += once { guard?.act = null; guard?.let { speak(it, "I'M OK... I'M OK.") } }
                s += wait(2.5f)
            }
        }
    }
    private fun Npc.zone() = PrisonLevel.zoneName(spec, floor(x).toInt(), floor(y).toInt())
    fun zoneOf(n: Npc) = n.zone()

    /** The scene is over: everyone goes back to what the day has them doing. */
    private fun endEvent() {
        doneDay = day
        val back = evPeople.toList()
        clearEvent()
        for (n in back) {
            if (gone(n)) { n.path.clear(); continue }
            if (n.guard) {
                val home = if (n.door != null) n.post else rounds.filter { hypot(it.first - n.x, it.second - n.y) in 3f..10f }.randomOrNull(rnd)
                if (home != null) n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), home)
                n.wait = 2f
            } else routineFor(n, period)
        }
        if (dutyGuard?.let { it in back } == true && (period.work || period == Period.SHOWERS)) postDutyGuard(period)
    }
    /** Lets go of everyone in the scene (no routine: used when the day is being reset anyway). */
    private fun clearEvent() {
        for (n in evPeople) { n.inEvent = false; n.act = null; n.look = null; if (n.guard) { n.attn = WATCH; n.post = if (n.door != null) n.post else null } }
        evPeople.clear(); steps.clear(); stepAt = 0; evNow = null; fireT = -1f; dropped = null
    }

    /** After drowning: you wake up in your cell the next morning with everything you had. */
    fun retry() {
        if (dead) {
            if (deadT < DEAD_S) return
            dead = false; hearts = HEARTS; heat = 20f; missedLoads = 0; grabStage = -1; enforcer = null; beatT = -1f; seated = false
            if (evNow != null) clearEvent()
            if (doneDay == day) doneDay = 0; firedDay = 0                                         // the same day again: today's event hasn't happened yet
            clock = ((day - 1) * 1440 + Period.ROLL_AM.start).toFloat(); lastPeriod = Period.ROLL_AM
            px = spec.startX; py = spec.startY; doorOpen = 0f; doorTarget = 0f; workTonight = null; carry = Carry.NONE; folding = -1f
            for (n in npcs) if (n.guard && n.door == null) { n.path.clear(); n.patrol = null; n.post = null; n.escorting = false }
            startPeriodMsg = "YOU WAKE UP IN YOUR CELL. EVERYTHING HURTS. SAME DAY, AGAIN."
            startPeriod(Period.ROLL_AM)
            return
        }
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
    /** Jump to the same time of day on [d] (for the tests). */
    internal fun debugDay(d: Int) { clock = (d - 1) * 1440f + minute; lastPeriod = period }
    internal val debugEventSteps get() = stepAt
    internal fun debugHaircut() { haircut = true }
    internal fun debugNoEscort() { escort?.let { it.escorting = false; it.hold = false }; escort = null; escortComing = false }
    internal fun debugBeat() = grab(null, true, null, "THAT'S FOR NOTHING.")
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
        for (n in npcs) if (!n.guard && !n.inEvent) { n.seat = null; n.job = null; n.carrying = false }
        for (n in npcs) if (!n.guard && !n.inEvent && !gone(n)) routineFor(n, p)
    }
    private fun routineFor(n: Npc, p: Period) {
        run {
            val target: Pair<Int, Int>? = when (p) {
                Period.ROLL_AM, Period.ROLL_PM, Period.LIGHTS_OUT -> n.home
                Period.BREAKFAST, Period.LUNCH, Period.DINNER -> {   // each man to his own seat
                    val taken = npcs.mapNotNull { it.seat }.toSet()
                    val s0 = seats.filter { it !in taken }.let { if (it.isEmpty()) null else it[(n.id * 5 + rnd.nextInt(3)) % it.size] }
                    n.seat = s0; s0
                }
                Period.SHOWERS -> spot("SHOWERS")
                Period.WORK_AM, Period.WORK_PM -> {   // each man to his job; once there he keeps busy
                    n.job = when (n.id % 5) { 0 -> "KITCHEN"; 1 -> "LIBRARY"; 2, 3 -> "LAUNDRY"; else -> "RECREATION YARD" }
                    n.jobAt = n.id; jobSpots[n.job]?.let { it[n.jobAt % it.size] }
                }
                Period.FREE -> freeSpot(n)
            }
            if (target == null) { n.stay = false; n.wait = rnd.nextFloat() * 4f; return }
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
    fun activityOf(n: Npc): String = if (n.inEvent) (if (n.path.isEmpty()) n.act ?: "" else if (n.act == "cuffed") "cuffed" else "") else if (!n.guard && period.work && n.job != null && n.path.isEmpty()) "work:" + n.job else if (!n.guard && meal && n.seat != null && n.path.isEmpty() && floor(n.x).toInt() == n.seat!!.first && floor(n.y).toInt() == n.seat!!.second) "eating" else if (!n.guard && n.stay && n.path.isEmpty() && period == Period.FREE) n.persona?.activity ?: "" else if (!n.guard && n.persona?.activity == "sleeper" && night) "sleeper" else ""

    private fun updateNpc(n: Npc, dt: Float) {
        n.sayT = max(0f, n.sayT - dt); n.heard = max(0f, n.heard - dt); n.sayAge += dt
        if (n.inEvent && n != enforcer) {
            if (n.guard) n.attn = BUSY
            if (n.path.isNotEmpty()) { stepAlong(n, n.evSpeed * dt); n.moving = true }
            else { n.moving = false; n.look?.let { (lx, ly) -> if (hypot(lx - n.x, ly - n.y) > 0.05f) n.face = atan2(ly - n.y, lx - n.x) } }
            return
        }
        if (n == enforcer || n in clipboards || (n == escort && escortComing) || (n == bustBy && bustStage >= 0)) { n.moving = false; return }
        if (n.guard) guardMind(n, dt)
        if (n.heard > 0f) { n.moving = false; n.face = kotlin.math.atan2(py - n.y, px - n.x); return }     // turned to you, talking
        if (n.guard && n.hold) { n.moving = false; return }
        if (n.path.isEmpty() && !n.guard && n.persona?.activity == "pacer" && period == Period.FREE && n.stay) {   // pacing in front of his cell
            val goal = if (floor(n.x).toInt() == n.home.first) (n.home.first + 1) to n.home.second else n.home
            if (walkable(goal.first, goal.second)) n.path = arrayListOf(goal)
        }
        if (n.path.isEmpty() && !n.guard && n.persona?.activity == "sweeper" && period == Period.FREE) n.stay = false
        if (n.path.isEmpty() && n.job != null && period.work) {   // at work: stay at a station a while, then on to the next (laundry: the bag goes with him)
            n.wait -= dt
            if (n.wait <= 0f) {
                val js = jobSpots[n.job]
                if (!js.isNullOrEmpty()) {
                    n.jobAt++
                    val next = if (n.job == "LAUNDRY") js[(n.jobAt * 3 + n.id) % js.size] else js[(n.jobAt * 2 + n.id) % js.size]
                    n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), next)
                    if (n.job == "LAUNDRY") n.carrying = !n.carrying
                    n.wait = 2.2f + rnd.nextFloat() * 2.5f
                }
            }
        }
        if (n.path.isEmpty() && n.patrol != null) {   // walking his loop round the tables
            val loop = n.patrol!!; n.patrolAt = (n.patrolAt + 1) % loop.size
            n.path = route(floor(n.x).toInt() to floor(n.y).toInt(), loop[n.patrolAt])
        }
        if (n.path.isEmpty()) {
            n.moving = false
            // standing still: now and then he looks somewhere else (a glance, not a spin)
            if (n.heard <= 0f && !(n.guard && n.post != null && n.attn == WATCH && n.door == null)) {
                n.glanceT -= dt
                if (n.glanceT <= 0f) {
                    n.glanceT = 2.5f + rnd.nextFloat() * 3f
                    n.face = when {
                        n.door != null -> doorFace(n) + (rnd.nextFloat() - 0.5f) * 1.6f
                        activityOf(n) == "eating" -> if (seatFacesDown(n.seat!!)) PI.toFloat() / 2f else -PI.toFloat() / 2f
                        else -> (rnd.nextInt(4) * PI.toFloat() / 2f)
                    }
                }
                if (activityOf(n) == "eating") n.face = if (seatFacesDown(n.seat!!)) PI.toFloat() / 2f else -PI.toFloat() / 2f
            }
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
        val sp = (if (n.escorting) 2.2f else if (n.patrol != null) 1.1f else if (n.guard) 1.5f else if (n.persona?.activity == "sweeper" && period == Period.FREE) 0.8f else if (n.persona?.activity == "pacer" && period == Period.FREE) 0.9f else 2.5f) * dt
        n.moving = true
        if (d <= sp) { n.x = tx; n.y = ty; n.path.removeAt(0) } else { n.x += dx / d * sp; n.y += dy / d * sp }
    }

    /** A guard keeping watch: he looks at you for a while, then gets distracted (radio, another inmate), and now and then steps out. */
    /** Which way a door guard looks: out through his doorway. */
    private fun doorFace(n: Npc): Float { val dp = DOOR_POSTS.firstOrNull { it.first == n.door } ?: return 0f; return atan2(dp.third.second - dp.second.second.toFloat(), dp.third.first - dp.second.first.toFloat()) }
    private fun guardMind(n: Npc, dt: Float) {
        val post = n.post
        if (n.door != null) {   // on the door: always watching; he looks at you when you're close
            n.attn = WATCH
            if (n.path.isEmpty() && hypot(px - n.x, py - n.y) < 3.5f && phase == Phase.PLAY) n.face = atan2(py - n.y, px - n.x)
            return
        }
        if (post == null) { n.attn = WATCH; return }   // on his rounds (or his loop): he looks where he walks
        n.attnT -= dt
        when (n.attn) {
            WATCH -> {
                n.face = kotlin.math.atan2(py - n.y, px - n.x)
                if (alert && n.attnT <= 0f) n.attnT = 6f                        // high alert: no smoke breaks, no radio
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
    fun route(a: Pair<Int, Int>, b: Pair<Int, Int>, yourDoor: Boolean = false): ArrayList<Pair<Int, Int>> {
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
                if (ch !in ".,ozh" && !(yourDoor && ch == 'O' && doorOpen >= 0.9f) && !(yourDoor && ch == 'p')) continue
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
