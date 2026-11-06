package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// DEAD LETTER, the hidden game (tap the version text at the bottom of Settings 7 times).
// You are a wanted city clerk carrying six sealed letters through a dark, hunted city. Each one goes to someone who holds
// a piece of the truth. Touch and drag anywhere to walk: a gentle drag sneaks quietly, a full drag runs (and is loud).
// Stay out of the light. The rules live in StealthWorld.kt, the story and buildings in StealthLevels.kt, the music in
// DeadLetterMusic.kt; this file draws everything and reads your thumb.

internal fun MainActivity.showDeadDrop() {
    screen = Screen.DEAD_DROP
    val prefs = getSharedPreferences("dead_drop", Context.MODE_PRIVATE)
    val game = DeadDropView(this, prefs.getInt("level", 0), prefs.getBoolean("sound", true),
        saveLevel = { prefs.edit().putInt("level", it).apply() }, saveSound = { prefs.edit().putBoolean("sound", it).apply() }, onExit = { showSettings() },
        beaten = prefs.getBoolean("beaten", false), saveBeaten = { prefs.edit().putBoolean("beaten", it).apply() })
    // on the right: restart (tap = this level, hold = back to level 1) next to exit
    val skip = icon(R.drawable.ic_skip) { game.skip() }
    deadDropGame = game
    // hold restart: once you've beaten the game, choose any letter; before that, back to level 1
    val again = icon(R.drawable.ic_restart) { game.restart() }.apply { setOnLongClickListener { if (game.beaten) game.openChooser() else { game.restartAll(); toast("Back to level 1") }; true } }
    // edge to edge: the game fills the whole screen and the title bar floats over it, see-through (the game blurs behind it)
    val tb = topBar("Dead Letter", "Six letters. One truth.", null, skip, again, icon(R.drawable.ic_close) { showSettings() })
    tb.setBackgroundColor(Color.TRANSPARENT)
    @Suppress("DEPRECATION")
    tb.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(v.paddingLeft, dp(4) + ins.systemWindowInsetTop, v.paddingRight, dp(4)); ins }
    val frame = FrameLayout(this).apply {
        setBackgroundColor(0xFF03050A.toInt())
        addView(game, FrameLayout.LayoutParams(-1, -1))
        addView(tb, FrameLayout.LayoutParams(-1, -2))
    }
    setContentView(frame)
    window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
    if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    frame.requestApplyInsets()
}

internal class DeadDropView(
    ctx: Context, startLevel: Int, private var soundOn: Boolean,
    private val saveLevel: (Int) -> Unit, private val saveSound: (Boolean) -> Unit, private val onExit: () -> Unit,
    beaten: Boolean = false, private val saveBeaten: (Boolean) -> Unit = {},
) : View(ctx) {
    /** You've finished the game once: from now on you can pick any letter (level) to play. */
    var beaten = beaten; private set
    /** The "choose a letter" screen is up (it opens first once the game is beaten). */
    private var choosing = beaten
    private val chooserRows = ArrayList<RectF>()
    internal fun openChooser() { choosing = true; arcadeId = null; tracks.stop(); invalidate() }

    // ---------- the arcade (after the game is beaten): each mini-game as an endless game with a score ----------
    private val ARCADE = listOf("maze" to "SEWER MAZE", "belt" to "CONVEYOR RUN", "fly" to "JETPACK", "hall" to "TOWN HALL", "tower" to "THE TOWER", "uplink" to "UPLINK")
    private val ARCADE_HOW = mapOf("maze" to "ENDLESS MAZES. 3 LIVES.", "belt" to "HOW FAR CAN YOU GET?", "fly" to "COLLECT COINS. 3 HITS.", "hall" to "CLEAR THE WHOLE HALL.", "tower" to "EVERY CLIMB GETS FASTER.", "uplink" to "HOW LONG CAN YOU REMEMBER?")
    private var arcadeId: String? = null
    private var arcLives = 3; private var arcScore = 0; private var arcOver = false; private var arcOverT = 0f; private var arcWon = false
    private var pacBank = 0; private var arcRound = 1; private var flyHit = false; private var pacCaughtT = -1f
    private val prefsDD get() = context.getSharedPreferences("dead_drop", Context.MODE_PRIVATE)
    private fun best(id: String) = prefsDD.getInt("best_$id", 0)
    private fun startArcade(id: String) {
        choosing = false; arcadeId = id; arcLives = 3; arcScore = 0; arcOver = false; arcOverT = 0f; arcWon = false; pacBank = 0; arcRound = 1; flyHit = false; pacCaughtT = -1f
        when (id) {
            "maze" -> { levelIdx = 1; load(false); attempt = 2; toStage(1) }
            "belt" -> { levelIdx = 2; load(false); belt = BeltWorld(BeltLevel.warehouseArcade); beltScene = BeltScene(belt!!) }
            "fly" -> { levelIdx = 2; load(false); startMg(1); fly = FlyWorld(FlySpec((System.nanoTime() % 1000).toInt(), 1_000_000)); flyScene = FlyScene(fly!!) }
            "hall" -> { levelIdx = 3; load(false); startMg(1) }
            "tower" -> { levelIdx = 5; load(false); finale?.arcade = true; finale?.debugEnter(FinaleWorld.Stage.CLIMB) }
            "uplink" -> { levelIdx = 5; load(false); finale?.arcade = true; finale?.debugEnter(FinaleWorld.Stage.SIMON) }
        }
        fade = 0f; fadeDir = 0; cue = ""; applyCue()
    }
    private fun arcadeOver(won: Boolean = false) {
        if (arcOver) return
        arcOver = true; arcOverT = 0f; arcWon = won
        val id = arcadeId ?: return
        if (arcScore > best(id)) prefsDD.edit().putInt("best_$id", arcScore).apply()
    }
    /** The arcade games run here instead of the story's stage-to-stage flow. */
    private fun arcadeUpdate(dt: Float) {
        if (arcOver) { arcOverT += dt; return }
        belt?.let { b -> b.update(dt, ix, iy, mag); arcScore = b.coins * 10 + b.s.toInt(); if (b.phase == BeltWorld.Phase.FALL && b.phaseT > 1.2f) arcadeOver() }
        fly?.let { f ->
            f.thrust = thrustId >= 0; f.update(dt); arcScore = f.coins * 10
            if (f.hitFlash > 0.4f && !flyHit) { flyHit = true; arcLives--; if (arcLives <= 0) arcadeOver() } else if (f.hitFlash < 0.3f) flyHit = false
        }
        shoot?.let { sw ->
            sw.update(dt, ix, iy, mag, gunId >= 0); if (soundOn) for (e in sw.events) DeadLetterSfx.play(e); sw.events.clear()
            arcScore = sw.npcs.count { !it.civ && !it.hostage && it.dead } * 100
            if (sw.phase == ShootWorld.Phase.DEAD) { deadT += dt; if (deadT > 1f) arcadeOver() }
            if (sw.phase == ShootWorld.Phase.FINALE) { arcScore += 2000; arcadeOver(won = true) }
        }
        finale?.let { fw ->
            fw.update(dt, ix, iy, mag); if (soundOn) for (e in fw.events) DeadLetterSfx.play(e); fw.events.clear()
            arcScore = if (arcadeId == "tower") fw.arcadeRound * 1000 + fw.jumpsCleared * 100 else (fw.round - 1) * 100
            if (fw.gameOver) arcadeOver()
        }
    }
    /** The sewer maze in the arcade: a new maze each time you clear one; 3 lives. */
    private fun arcadePacTick(dt: Float) {
        val pw = pac ?: return
        if (arcOver) { arcOverT += dt; return }
        arcScore = pacBank + pw.eaten * 10
        if (pw.phase == PacWorld.Phase.WON && pw.phaseT > 0.9f) { pacBank += pw.eaten * 10 + 500; arcRound++; pac = PacWorld(spec.pac!!); pacScene = PacScene(pac!!); pacWant = -1 }
        if (pw.phase == PacWorld.Phase.CAUGHT) {
            if (pacCaughtT < 0f) { pacCaughtT = 0f; arcLives--; if (arcLives <= 0) arcadeOver() }
            else { pacCaughtT += dt; if (pacCaughtT > 1.2f && !arcOver) { pacCaughtT = -1f; pacBank += pw.eaten * 10; pac = PacWorld(spec.pac!!).also { it.fails = 0 }; pacScene = PacScene(pac!!); pacWant = -1 } }
        }
    }
    /** The arcade's score, lives and the game-over card, over whatever is on screen. */
    private fun drawArcade(c: Canvas) {
        val id = arcadeId ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val y = barH + 26f * d
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x90); c.drawRect(0f, y - 18f * d, w, y + 8f * d, p)
        mgText(c, "SCORE $arcScore", w / 2f - 70f * d, y, 12f, 0xFFFFD84D.toInt())
        mgText(c, if (id == "belt" || id == "hall" || id == "uplink") "BEST ${best(id)}" else "♥".repeat(arcLives.coerceAtLeast(0)) + "  BEST ${best(id)}", w / 2f + 70f * d, y, 12f, if (id == "belt" || id == "hall" || id == "uplink") a(Color.WHITE, 0xCC) else 0xFFFF6A7A.toInt())
        if (arcOver) {
            val k = (arcOverT / 0.5f).coerceIn(0f, 1f)
            p.color = a(Color.BLACK, (k * 0xC8).toInt()); c.drawRect(0f, 0f, w, h, p)
            mgText(c, if (arcWon) "HALL CLEARED" else "GAME OVER", w / 2f, h / 2f - 30f * d, 26f, a(if (arcWon) 0xFF3BFF9A.toInt() else 0xFFFF4A5A.toInt(), (k * 255).toInt()))
            mgText(c, "SCORE  $arcScore", w / 2f, h / 2f + 4f * d, 15f, a(0xFFFFD84D.toInt(), (k * 255).toInt()))
            mgText(c, if (arcScore >= best(id) && arcScore > 0) "A NEW BEST!" else "BEST  ${best(id)}", w / 2f, h / 2f + 28f * d, 12f, a(Color.WHITE, (k * 0xCC).toInt()))
            if (arcOverT > 0.8f) mgText(c, "TAP TO PLAY AGAIN  ·  HOLD ↻ FOR THE MENU", w / 2f, h / 2f + 64f * d, 10f, a(Color.WHITE, 0x99), false)
        }
    }
    internal fun isChoosing() = choosing
    internal fun debugArcade(id: String) = startArcade(id)
    internal fun debugArcadeOver() = arcOver
    internal fun debugArcadeScore() = arcScore
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val levels = StealthLevels.all
    private val roman = listOf("I", "II", "III", "IV", "V", "VI")
    private val heatNames = listOf("LOW", "MODERATE", "HIGH", "CRITICAL", "MAXIMUM", "FULL MANHUNT")

    private var levelIdx = startLevel.coerceIn(0, levels.size - 1)
    private var world = StealthWorld(levels[0])
    private var attempt = 1
    private var sinceLoad = 0f
    private var running = false
    private var last = 0L
    private var clock = 0f
    private val read = HashSet<Int>()           // clues I have walked up to
    private var clueText: String? = null
    private var clueAlpha = 0f
    private val music = DeadLetterMusic()
    private val tracks = DeadLetterTracks(ctx)
    private var cue = ""
    private fun applyCue() {
        if (!soundOn) { tracks.stop(); music.stop(); return }
        if (cue == "synth") { tracks.stop(); music.start() } else { music.stop(); tracks.play(cue) }
    }

    /** Which song should be on right now ("" = silence, null = leave it alone). */
    private fun wantedCue(): String? {
        when (arcadeId) { "maze" -> return "l2"; "belt" -> return "l3"; "fly" -> return "l3b"; "hall" -> return "synth"; "tower", "uplink" -> return "l6" }
        when (mgName) {
            "belt" -> { val b = belt ?: return ""; return if (b.phase == BeltWorld.Phase.RIDE && b.phaseT >= 1f) "l3" else "" }
            "fly" -> { val f = fly ?: return ""; return if (f.phase != FlyWorld.Phase.DONE) "l3b" else "" }
            "cine0" -> return ""
            "cine1" -> return "synth"
            "shoot" -> return if (shoot?.phase == ShootWorld.Phase.FINALE || shoot?.phase == ShootWorld.Phase.DONE) (if (shoot?.mayorMusic == true) "tremolo" else "") else "synth"   // the mayor's song, cut hard before the kick
            "prison" -> return "synth"
            "finale" -> return if (finale?.silent == true) "" else "l6"
        }
        if (levelIdx == 0) return "synth"
        if (levelIdx == 1) {
            if (stage == 0) return "synth"
            val pw = pac ?: return ""
            return when (pw.phase) { PacWorld.Phase.CAUGHT -> null; else -> "l2" }      // the song kicks in the moment the maze starts
        }
        if (levelIdx == 5) return "l6"
        return ""
    }
    private var pixelScene: PixelScene? = null
    private val nearest = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    // the joystick at the bottom left: how far you push sets how fast (and how loudly) the courier moves; in the maze it picks a direction
    private var joyActive = false; private var soundTap = false; private var touchWasJoy = false
    private var overlayAtDown = false
    private var knobX = 0f; private var knobY = 0f
    private var ix = 0f; private var iy = 0f; private var mag = 0f
    private var pacWant = -1

    // level 2 has three parts: the walk (0), the sewer maze (1) and the maintenance room (2); the screen fades between them
    private var stage = 0
    private var pac: PacWorld? = null
    private var pacScene: PacScene? = null
    private var fade = 0f; private var fadeDir = 0; private var pendingStage = -1
    private var clueT = 0f
    private var scrollY = 0f                       // pixel levels taller than the screen: the first tile row on screen

    // ---------- levels 3 and 4: conveyor, jetpack, cutscenes and the town-hall shoot-out ----------
    private var mgIdx = -1
    private var belt: BeltWorld? = null; private var beltScene: BeltScene? = null
    private var fly: FlyWorld? = null; private var flyScene: FlyScene? = null
    private var shoot: ShootWorld? = null; private var shootScene: ShootScene? = null
    private var cine: CineScene? = null
    private var prison: PrisonWorld? = null; private var prisonScene: PrisonScene? = null
    private var finale: FinaleWorld? = null; private var finaleScene: FinaleScene? = null
    private var mgOx = 0f; private var mgOy = 0f; private var mgSc = 1f      // where the picture sits on screen (for taps on it)
    private var mgPending = -9                    // where the fade goes: a stage number, -2 next level, -3 redo this part
    private var aimId = -1; private var joyId = -1; private var thrustId = -1
    private var aimDx = 0f; private var aimDy = 0f; private var aimMag = 0f; private var aimKX = 0f; private var aimKY = 0f
    private var carryFade = false
    private var topInset = 0f; private var botInset = 0f
    private val barH get() = topInset + 60f * d
    private var node: android.graphics.RenderNode? = null; private var blurNode: android.graphics.RenderNode? = null
    init {
        @Suppress("DEPRECATION")
        setOnApplyWindowInsetsListener { _, i -> topInset = i.systemWindowInsetTop.toFloat(); botInset = i.systemWindowInsetBottom.toFloat(); i }
    }
    private var bPid = -1; private var bDX = 0f; private var bDY = 0f; private var bJumped = false
    private var deadT = 0f

    private fun stagesOf(s: LevelSpec): List<String>? = when (s.building) {
        "The Industrial District" -> listOf("belt", "fly", "cine0")
        "The Administration" -> listOf("cine1", "shoot")
        "The Rock" -> listOf("prison")
        "The Broadcast" -> listOf("finale")
        else -> null
    }
    private val mgName get() = if (mgIdx < 0) null else stagesOf(spec)?.getOrNull(mgIdx)
    private val aimCx get() = width - joyCx
    private var gunId = -1; private var rollId = -1; private var rollX = 0f; private var rollY = 0f; private var rolled = false
    private var grMsg = ""; private var grMsgT = 0f
    private var grId = -1; private var grX0 = 0f; private var grY0 = 0f
    private val gunR get() = 46f * d; private val gunCx get() = width - 22f * d - gunR
    private val grR get() = 32f * d; private val grCx get() = gunCx - gunR - grR - 8f * d; private val grCy get() = joyCy + 14f * d

    private fun startMg(i: Int) {
        mgIdx = i; belt = null; beltScene = null; fly = null; flyScene = null; shoot = null; shootScene = null; cine = null; prison = null; prisonScene = null; finale = null; finaleScene = null
        bPid = -1; joyId = -1; aimId = -1; thrustId = -1; gunId = -1; rollId = -1; grId = -1; grMsgT = 0f; joyActive = false; mag = 0f; aimMag = 0f; knobX = 0f; knobY = 0f; aimKX = 0f; aimKY = 0f; deadT = 0f; mgPending = -9
        when (mgName) {
            "belt" -> { belt = BeltWorld(BeltLevel.warehouse); beltScene = BeltScene(belt!!); music.setHeat(3) }
            "fly" -> { fly = FlyWorld(FlySpec()); flyScene = FlyScene(fly!!); music.setHeat(4) }
            "cine0" -> { cine = CineScene(0); music.setHeat(1) }
            "cine1" -> { cine = CineScene(1); music.setHeat(2) }
            "shoot" -> { shoot = ShootWorld(ShootLevel.townHall()); shootScene = ShootScene(shoot!!); music.setHeat(5) }
            "prison" -> { prison = PrisonWorld(PrisonLevel.rock()); prisonScene = PrisonScene(prison!!); music.setHeat(1) }
            "finale" -> { finale = FinaleWorld(); finaleScene = FinaleScene(finale!!); music.setHeat(5) }
        }
    }

    private fun mgGo(to: Int) { if (fadeDir == 0 && mgPending == -9) { mgPending = to; fadeDir = 1 } }

    private fun mgApply() {
        val to = mgPending; mgPending = -9
        val n = stagesOf(spec)?.size ?: 0
        when {
            to == -3 -> startMg(mgIdx)
            to >= n || to == -2 -> { if (levelIdx < levels.size - 1) { carryFade = true; levelIdx++; saveLevel(levelIdx); load(false) } else startMg(mgIdx) }
            else -> startMg(to)
        }
    }

    private fun mgUpdate(dt: Float) {
        if (arcadeId != null) { arcadeUpdate(dt); return }
        if (fadeDir > 0) { fade += dt / 0.7f; if (fade >= 1f) { mgApply(); fade = 1f; fadeDir = -1 }; return }
        if (fadeDir < 0) { fade -= dt / 0.7f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }
        belt?.let { b ->
            b.update(dt, ix, iy, mag)
            if (b.fell) mgGo(-3) else if (b.phase == BeltWorld.Phase.DONE && b.phaseT > 0.8f) mgGo(mgIdx + 1)
        }
        fly?.let { f -> f.thrust = thrustId >= 0; f.update(dt); if (soundOn) for (e in f.events) DeadLetterSfx.play(e); f.events.clear(); if (f.finished) mgGo(mgIdx + 1) }
        cine?.let { cn -> cn.t += dt; if (cn.t >= cn.fadeStart) mgGo(mgIdx + 1) }
        prison?.let { pw -> pw.update(dt, ix, iy, mag); if (soundOn) for (e in pw.events) DeadLetterSfx.play(e); pw.events.clear(); if (pw.done) mgGo(mgIdx + 1) }
        finale?.let { fw -> fw.update(dt, ix, iy, mag); if (soundOn) for (e in fw.events) DeadLetterSfx.play(e); fw.events.clear() }
        shoot?.let { sw ->
            sw.update(dt, ix, iy, mag, gunId >= 0)
            if (soundOn) for (e in sw.events) DeadLetterSfx.play(e); sw.events.clear()
            grMsgT = max(0f, grMsgT - dt)
            if (sw.phase == ShootWorld.Phase.DEAD) deadT += dt
            if (sw.phase == ShootWorld.Phase.DONE) mgGo(mgIdx + 1)
        }
    }

    private fun readAim(x: Float, y: Float) {
        val dx = x - aimCx; val dy = y - joyCy; val len = hypot(dx, dy); val shown = min(len, joyR)
        if (len > 0.001f) { aimKX = dx / len * shown; aimKY = dy / len * shown } else { aimKX = 0f; aimKY = 0f }
        val dead = 7f * d
        if (len < dead) { aimMag = 0f; return }
        aimDx = dx / len; aimDy = dy / len; aimMag = ((shown - dead) / (joyR - dead)).coerceIn(0f, 1f)
    }

    private fun mgTouch(e: MotionEvent): Boolean {
        val act = e.actionMasked; val idx = e.actionIndex; val id = e.getPointerId(idx)
        when (act) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx)
                if (act == MotionEvent.ACTION_DOWN && x > width - 52f * d && y > barH && y < barH + 46f * d) { soundTap = true; return true }
                val sw = shoot
                if (sw != null && sw.phase == ShootWorld.Phase.DEAD) { if (deadT > 0.5f) startMg(mgIdx); return true }
                prison?.let { pw -> if (pw.phase == PrisonWorld.Phase.SIGNAL || pw.phase == PrisonWorld.Phase.ESCAPE) { if (pw.drowned) pw.retry(); return true }; if (pw.dead) { pw.retry(); return true } }
                finale?.let { fw ->
                    if (fw.over) { beaten = true; saveBeaten(true); choosing = true; onExit(); return true }
                    when (fw.stage) {
                        FinaleWorld.Stage.SIMON -> { val b = finaleScene?.simonButtonAt((x - mgOx) / mgSc, (y - mgOy) / mgSc) ?: -1; if (b >= 0) fw.simonTap(b) }
                        FinaleWorld.Stage.CLIMB -> if (hypot(x - joyCx, y - joyCy) < joyR * 1.7f) { joyId = id; joyActive = true; readJoystick(x, y) } else fw.jump()
                        else -> {}
                    }
                    return true
                }
                val m = mgName
                if (m == "belt") { bPid = id; bDX = x; bDY = y; bJumped = false }
                else if ((m == "shoot" || m == "prison") && hypot(x - joyCx, y - joyCy) < joyR * 1.7f) { joyId = id; joyActive = true; readJoystick(x, y) }
                else if (m == "prison" && hypot(x - gunCx, y - joyCy) < gunR * 1.25f) prison?.act()
                else if (m == "prison") { if (invOpen) invOpen = false else { invPid = id; invX = x; invY = y } }   // swipe up anywhere else: your things
                else if (m == "shoot" && hypot(x - gunCx, y - joyCy) < gunR * 1.25f) gunId = id
                else if (m == "shoot" && hypot(x - grCx, y - grCy) < grR * 1.4f) { grId = id; grX0 = x; grY0 = y }   // drag from the grenade button to aim it
                else if (m == "shoot" && joyId >= 0) { rollId = id; rollX = x; rollY = y; rolled = false }
                else if (m == "fly") thrustId = id
            }
            MotionEvent.ACTION_MOVE -> {
                if (soundTap) return true
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    if (pid == joyId) readJoystick(e.getX(i), e.getY(i)) else if (pid == aimId) readAim(e.getX(i), e.getY(i))
                    if (pid == grId) { val dx = e.getX(i) - grX0; val dy = e.getY(i) - grY0; val l = hypot(dx, dy); if (l > 14f * d) shoot?.aimGrenade(dx, dy, ((l - 14f * d) / (150f * d)).coerceIn(0f, 1f)) else shoot?.cancelAim() }
                    if (pid == invPid && e.getY(i) - invY < -44f * d && abs(e.getX(i) - invX) < 60f * d) { invOpen = true; invPid = -1 }
                    if (pid == rollId && !rolled && hypot(e.getX(i) - rollX, e.getY(i) - rollY) > 26f * d) { shoot?.roll(e.getX(i) - rollX, e.getY(i) - rollY); rolled = true }
                    if (pid == bPid) {
                        // swipes: up = jump, left/right = leap to the next belt; several in one drag, each from where the last one ended
                        val dx = e.getX(i) - bDX; val dy = e.getY(i) - bDY
                        if (!bJumped) {   // one swipe, one action: after a jump or a leap, nothing more until the finger lifts
                            if (-dy > 34f * d && -dy > abs(dx)) { belt?.jump(); bJumped = true }
                            else if (abs(dx) > 30f * d && abs(dx) > abs(dy)) { belt?.leap(if (dx > 0) 1 else -1); bJumped = true }
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (soundTap && act != MotionEvent.ACTION_POINTER_UP) {
                    soundTap = false
                    if (act == MotionEvent.ACTION_UP) { soundOn = !soundOn; saveSound(soundOn); applyCue() }
                    return true
                }
                if (act == MotionEvent.ACTION_CANCEL) { joyId = -1; aimId = -1; thrustId = -1; bPid = -1; gunId = -1; rollId = -1 }
                if (id == gunId) gunId = -1
                if (id == grId) { grId = -1; if (act == MotionEvent.ACTION_CANCEL) shoot?.cancelAim() else { val why = shoot?.throwAimed(); if (why != null) { grMsg = why; grMsgT = 1.3f } } }
                if (id == invPid || act == MotionEvent.ACTION_CANCEL) invPid = -1
                if (id == rollId) rollId = -1
                if (id == bPid) bPid = -1
                if (id == joyId) { joyId = -1; joyActive = false; mag = 0f; knobX = 0f; knobY = 0f }
                if (id == aimId) { aimId = -1; aimMag = 0f; aimKX = 0f; aimKY = 0f }
                if (id == thrustId) thrustId = -1
                if (act == MotionEvent.ACTION_CANCEL || act == MotionEvent.ACTION_UP) { joyActive = false; if (joyId < 0) mag = 0f }
            }
        }
        return true
    }

    // the prison: your things, on a swipe up (the world keeps going behind it)
    private var invOpen = false
    private var invPid = -1; private var invX = 0f; private var invY = 0f
    private fun drawInventory(c: Canvas, w: Float, h: Float, pw: PrisonWorld) {
        val lines = ArrayList<Pair<String, String>>()
        for ((it, n) in pw.items) lines += (if (n > 1) "${it.label} ×$n" else it.label) to pw.hintFor(it)
        if (pw.haircut) lines += "GUARD'S HAIRCUT" to "WITH A UNIFORM, YOU'D PASS FOR ONE OF THEM."
        if (pw.disguised) lines += "IN UNIFORM" to "THE GUARDS' DOOR BY THE FRONT GATE OPENS FOR YOU."
        if (pw.signalSent) lines += "THE BOAT" to "IT'S WAITING OFF THE ISLAND."
        val top = h * 0.42f; val bot = h - 150f * d
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0xE0); c.drawRoundRect(14f * d, top, w - 14f * d, bot, 6f * d, 6f * d, p)
        p.color = a(0xFFFFD84D.toInt(), 0x90); c.drawRect(14f * d, top, w - 14f * d, top + 2f * d, p)
        mgText(c, "YOUR THINGS", w / 2f, top + 26f * d, 13f, 0xFFFFD84D.toInt())
        var y = top + 54f * d
        if (lines.isEmpty()) mgText(c, "NOTHING YET. LOOK AROUND: MEALS, THE KITCHEN, THE LIBRARY, THE SHOWERS.", w / 2f, y, 9.5f, a(Color.WHITE, 0xAA), false)
        for ((name, hint) in lines) { if (y > bot - 20f * d) break; mgText(c, name, w / 2f, y, 11.5f, Color.WHITE); mgText(c, hint, w / 2f, y + 16f * d, 9f, a(Color.WHITE, 0xAA), false); y += 40f * d }
        mgText(c, "TAP ANYWHERE TO CLOSE", w / 2f, bot - 12f * d, 9f, a(Color.WHITE, 0x66), false)
    }

    private fun mgText(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true) {
        p.style = Paint.Style.FILL; p.typeface = if (bold) monoBold else mono; p.textAlign = Paint.Align.CENTER; p.textSize = size * d; p.color = color; p.letterSpacing = 0.05f
        c.drawText(text, x, y, p); p.letterSpacing = 0f
    }

    private fun drawMg(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF03050A.toInt())
        // EDGE TO EDGE: a whole-number scale that fills the screen's width (every art pixel stays the same size). The scrolling scenes
        // then draw a taller view so they fill the height too; the fixed scenes keep their framing and their edges are carried out to
        // the screen's edges, fading.
        val fillW = { bw: Int -> max(1, ceil(w / bw).toInt()) }
        beltScene?.let { it.resize(ceil(h / fillW(it.W)).toInt()) }
        flyScene?.let { it.resize(ceil(h / fillW(it.W)).toInt()) }
        shootScene?.let { it.resize(ceil(h / fillW(it.W)).toInt()) }
        prisonScene?.let { val k = max(1, (w / 256f).roundToInt()); it.resize(ceil(w / k).toInt(), ceil(h / k).toInt()); it.hudTop = ((max(barH + 30f * d, 0f) + 70f * d - (h - it.H * k) / 2f) / k).toInt() }
        val bmp = beltScene?.render(clock) ?: flyScene?.render(clock) ?: cine?.render(clock) ?: shootScene?.render(clock) ?: prisonScene?.render(clock) ?: finaleScene?.render(clock)
        if (bmp != null) {
            val grows = beltScene != null || flyScene != null || shootScene != null || prisonScene != null
            val sc = if (prisonScene != null) max(1, (w / 256f).roundToInt()) else if (grows) fillW(bmp.width) else max(1, min(w / bmp.width, h / bmp.height).toInt())
            val ox = ((w - bmp.width * sc) / 2f).toInt().toFloat(); val oy = ((h - bmp.height * sc) / 2f).toInt().toFloat()
            rect.set(ox, oy, ox + bmp.width * sc, oy + bmp.height * sc)
            c.drawBitmap(bmp, null, rect, nearest)
            if (!grows) extendEdges(c, bmp, ox, oy, sc, w, h)
            mgOx = ox; mgOy = oy; mgSc = sc.toFloat()
            val bt = oy + bmp.height * sc
            // where the words go: just under the title bar and just above the bottom edge, whatever the picture does
            val hy = max(oy, barH + 30f * d); val hb = min(bt, h - botInset - 46f * d)
            if (arcadeId == null) belt?.let { b ->
                mgText(c, "COINS  ${b.coins} / ${b.spec.coinGoal}", w / 2f + 70f * d, hy - 10f * d, 13f, if (b.coinFlash > 0f) Color.WHITE else 0xFFFFD84D.toInt()); mgText(c, "♥".repeat(b.lives.coerceAtLeast(0)), w / 2f - 90f * d, hy - 10f * d, 13f, 0xFFFF6A7A.toInt())
                if (b.phase == BeltWorld.Phase.FALL) mgText(c, if (b.fellInHole) "YOU FELL" else "YOU DIED", w / 2f, h / 2f, 22f, danger)
                if (b.phase == BeltWorld.Phase.LAUNCH || b.phase == BeltWorld.Phase.PICKUP) mgText(c, "THE JETPACK!", w / 2f, hy - 10f * d - 18f * d, 12f, 0xFFFFD84D.toInt())
                if (b.t < 9f) mgText(c, "SWIPE LEFT / RIGHT TO SWITCH BELTS. SWIPE UP TO JUMP.", w / 2f, hb + 14f * d, 10f, a(Color.WHITE, 0xAA), false) else if (b.coins < b.spec.coinGoal) mgText(c, "COLLECT 100 COINS TO REACH THE END.", w / 2f, hb + 14f * d, 10f, a(Color.WHITE, 0x80), false)
            }
            if (arcadeId == null) fly?.let { f ->
                mgText(c, "COINS  ${f.coins} / ${f.spec.coinGoal}", w / 2f, hy - 10f * d, 14f, 0xFFFFD84D.toInt())
                if (f.t < 6f) mgText(c, "HOLD ANYWHERE TO FLY UP. LET GO TO FALL. AVOID THE ZAPPERS.", w / 2f, hb + 14f * d, 10f, a(Color.WHITE, 0xAA), false)
            }
            prison?.let { pw ->
                if (pw.phase == PrisonWorld.Phase.WAKE) { if (pw.t > 1.2f) mgText(c, "6:00 AM.  MORNING COUNT.", w / 2f, h / 2f - 40f * d, 15f, a(Color.WHITE, 0xEE)) }
                else if (pw.phase == PrisonWorld.Phase.SIGNAL || pw.phase == PrisonWorld.Phase.ESCAPE) {
                    val cap = pw.caption()
                    if (cap.isNotEmpty()) {
                        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0xA0); c.drawRect(0f, hb - 44f * d, w, hb - 14f * d, p)
                        mgText(c, cap, w / 2f, hb - 24f * d, 10f, a(Color.WHITE, 0xEE))
                    }
                    if (pw.drowned && pw.sceneT > 4.2f) {
                        val k = min(1f, (pw.sceneT - 4.2f) * 1.5f)
                        mgText(c, "YOU DIED", w / 2f, h / 2f - 10f * d, 30f, a(0xFFFF4A5A.toInt(), (k * 255).toInt()))
                        mgText(c, "shouldn't someone come pick you up?", w / 2f, h / 2f + 18f * d, 12f, a(Color.WHITE, (k * 0xCC).toInt()), false)
                        if (pw.sceneT > PrisonWorld.DROWN_S) mgText(c, "Tap to wake up.", w / 2f, h / 2f + 44f * d, 10f, a(Color.WHITE, 0x88), false)
                    }
                }
                else {
                    // the clock and the guards' suspicion
                    mgText(c, "DAY ${pw.day}  ${pw.clockText}", w / 2f - 100f * d, hy + 8f * d, 11f, a(Color.WHITE, 0xDD))
                    mgText(c, "♥".repeat(pw.hearts.coerceAtLeast(0)) + "♡".repeat((PrisonWorld.HEARTS - pw.hearts).coerceAtLeast(0)), w / 2f, hy + 8f * d, 12f, 0xFFFF6A7A.toInt())
                    val hc = when { pw.heat >= 70f -> 0xFFFF6A6A.toInt(); pw.heat >= 35f -> 0xFFFFB347.toInt(); else -> 0xFF8AD0A0.toInt() }
                    mgText(c, "HEAT ${pw.heat.toInt()}%" + (if (pw.trusted) " · TRUSTED" else ""), w / 2f + 100f * d, hy + 8f * d, 11f, hc)
                    // what you should be doing
                    val due = pw.dueAt
                    val step = pw.nextStep().first
                    val now = if (pw.period.work && !pw.shiftDone) "LAUNDRY ${pw.delivered}/${PrisonWorld.BAGS}  ·  $step" else if (step.isEmpty()) pw.period.label else "${pw.period.label}  ·  $step"
                    mgText(c, now, w / 2f, hy + 26f * d, 11f, if (due != null && !pw.period.work) 0xFFFFD84D.toInt() else a(Color.WHITE, 0xCC))
                    if (pw.msgT > 0f) {
                        val al = (min(1f, pw.msgT * 2f) * 0xEE).toInt()
                        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, al * 2 / 3); c.drawRect(0f, hy + 36f * d, w, hy + 62f * d, p)
                        mgText(c, pw.msg, w / 2f, hy + 54f * d, 10.5f, a(if (pw.msgBad) 0xFFFF6A6A.toInt() else 0xFF3BFF9A.toInt(), al))
                    }
                    if (invOpen) drawInventory(c, w, h, pw)
                    else if (pw.phase == PrisonWorld.Phase.PLAY && pw.t < 40f) mgText(c, "SWIPE UP FOR YOUR THINGS", w / 2f, hb - 4f * d, 9f, a(Color.WHITE, 0x77), false)
                    if (pw.dead) {
                        val k = min(1f, pw.deadT * 1.5f)
                        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, (k * 0xD0).toInt()); c.drawRect(0f, 0f, w, h, p)
                        mgText(c, "YOU DIED", w / 2f, h / 2f - 10f * d, 30f, a(0xFFFF4A5A.toInt(), (k * 255).toInt()))
                        mgText(c, "the guards beat you one time too many.", w / 2f, h / 2f + 18f * d, 12f, a(Color.WHITE, (k * 0xCC).toInt()), false)
                        if (pw.deadT > PrisonWorld.DEAD_S) mgText(c, "Tap to wake up. Same morning, same day.", w / 2f, h / 2f + 44f * d, 10f, a(Color.WHITE, 0x88), false)
                    }
                    mgText(c, pw.zone, w / 2f, hb + 14f * d, 10f, a(Color.WHITE, 0xAA), false)
                    // what you're carrying, and how your plans are coming along
                    val bits = ArrayList<String>()
                    for ((it, n) in pw.items) bits += if (n > 1 || it == PrisonWorld.Item.SHEET) "${it.label} ${n}${if (it == PrisonWorld.Item.SHEET) "/3" else ""}" else it.label
                    if (pw.haircut && !pw.disguised) bits += "HAIRCUT"
                    if (pw.disguised) bits += "IN UNIFORM"
                    if (pw.digNights > 0) bits += "TUNNEL ${minOf(pw.digNights, PrisonWorld.DIG_NIGHTS)}/${PrisonWorld.DIG_NIGHTS}"
                    if (pw.chipNights > 0) bits += "VENT ${minOf(pw.chipNights, PrisonWorld.CHIP_NIGHTS)}/${PrisonWorld.CHIP_NIGHTS}"
                    if (pw.signalSent) bits += "BOAT WAITING"
                    if (bits.isNotEmpty()) mgText(c, bits.joinToString("  ·  "), w / 2f, hb + 30f * d, 9f, 0xFFFFD84D.toInt(), false)
                }
            }
            finale?.let { fw ->
                if (fw.stage == FinaleWorld.Stage.CLIMB) mgText(c, "♥".repeat(fw.lives.coerceAtLeast(0)), w / 2f, hy - 10f * d, 13f, 0xFFFF6A7A.toInt())
                val cap = fw.caption()
                if (cap.isNotEmpty()) {
                    val sy = if (fw.stage == FinaleWorld.Stage.SIMON) oy + bmp.height * sc * (350f / FinaleWorld.H) + 14f * d else hb - 18f * d
                    if (fw.stage != FinaleWorld.Stage.SIMON) { p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x99); c.drawRect(0f, sy - 18f * d, w, sy + 8f * d, p) }
                    mgText(c, cap, w / 2f, sy, 10.5f, a(Color.WHITE, 0xEE))
                }
                // the cut: black, and the last words in red
                if (fw.redText.isNotEmpty()) {
                    p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawRect(0f, 0f, w, h, p)
                    val f = if (fw.t > FinaleWorld.RED_OFF - 0.4f) ((FinaleWorld.RED_OFF - fw.t) / 0.4f).coerceIn(0f, 1f) else 1f
                    val jx = if (fw.t in FinaleWorld.RED_ON + 0.85f..FinaleWorld.RED_ON + 2.6f) ((fw.clock * 47f).toInt() % 3 - 1) * 3f * d else 0f
                    p.textAlign = Paint.Align.CENTER; p.typeface = monoBold; p.letterSpacing = 0.05f; p.textSize = 30f * d
                    while (p.measureText(fw.redText) > w - 32f * d && p.textSize > 14f * d) p.textSize -= 1f * d
                    p.color = a(0xFFE0182C.toInt(), (f * 255).toInt()); c.drawText(fw.redText, w / 2 + jx, h / 2, p)
                    p.letterSpacing = 0f
                }
                // the very end: black, and the title
                if (fw.stage == FinaleWorld.Stage.END && fw.t > FinaleWorld.END_TITLE - 1.5f) {
                    p.style = Paint.Style.FILL; p.color = Color.BLACK; c.drawRect(0f, 0f, w, h, p)
                    val f = ((fw.t - FinaleWorld.END_TITLE) / 1.2f).coerceIn(0f, 1f)
                    p.textAlign = Paint.Align.CENTER
                    p.typeface = monoBold; p.letterSpacing = 0.3f; p.textSize = 26f * d; p.color = a(Color.WHITE, (f * 255).toInt()); c.drawText("DEAD LETTER", w / 2, h / 2, p)
                    val f2 = ((fw.t - FinaleWorld.END_TITLE - 1.2f) / 1.2f).coerceIn(0f, 1f)
                    p.typeface = mono; p.letterSpacing = 0.1f; p.textSize = 12f * d; p.color = a(Color.WHITE, (f2 * 0x99).toInt()); c.drawText("six letters. one truth.", w / 2, h / 2 + 30f * d, p)
                    if (fw.over) { p.letterSpacing = 0f; p.textSize = 11f * d; p.color = a(Color.WHITE, 0x66); c.drawText("tap to close", w / 2, h - 40f * d, p) }
                    p.letterSpacing = 0f
                }
            }
            // subtitles sit down in the dark space under the picture (wrapped if they're long)
            cine?.let { cs -> val cap = cs.caption(); if (cap != null && cs.bubbleX >= 0f) speechBubble(c, w, mgOx + cs.bubbleX * mgSc, mgOy + cs.bubbleY * mgSc, cap, 999) }
            cine?.caption()?.takeIf { cine?.bubbleX ?: -1f < 0f }?.let { cap ->
                val room = h - botInset - bt
                val y0 = if (room > 70f * d) bt + room * 0.38f else hb - 16f * d
                p.typeface = monoBold; p.textSize = 13f * d; p.textAlign = Paint.Align.CENTER; p.letterSpacing = 0.05f; p.color = a(Color.WHITE, 0xEE)
                wrap(c, cap, w / 2f, y0, w - 48f * d, 19f * d); p.letterSpacing = 0f
            }
            shoot?.let { sw ->
                if (sw.msgT > 0f) mgText(c, sw.msg, w / 2f, hy + 26f * d, 11f, if (sw.msgGood) 0xFF3BFF9A.toInt() else 0xFFFF6A6A.toInt())
                if (sw.phase == ShootWorld.Phase.PLAY) { mgText(c, "AREAS ${sw.roomsClear()}/7", w / 2f - 96f * d, hy + 8f * d, 10f, a(Color.WHITE, 0xCC)); if (sw.innocents > 0) mgText(c, "CLERKS LOST ${sw.innocents}", w / 2f + 96f * d, hy + 8f * d, 10f, a(0xFFFF6A6A.toInt(), 0xDD)) }
                if (sw.phase == ShootWorld.Phase.PLAY && sw.phaseT < 8f) mgText(c, "HOLD GUN: SHOOT THE CLOSEST. DRAG FROM GRENADE TO AIM A THROW. 2ND-FINGER SWIPE: ROLL. AGAINST A WALL, NOT FIRING: HIDDEN.", w / 2f, hb + 14f * d, 8.5f, a(Color.WHITE, 0xAA), false)
                if (sw.phase == ShootWorld.Phase.FINALE && sw.fade > 0f) { p.style = Paint.Style.FILL; p.color = a(Color.BLACK, (sw.fade * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }
                if (sw.phase == ShootWorld.Phase.DEAD) {
                    p.style = Paint.Style.FILL; p.color = a(0xFFB01020.toInt(), (min(1f, deadT * 2f) * 0x88).toInt()); c.drawRect(0f, 0f, w, h, p)
                    if (deadT > 0.3f) { mgText(c, "SHOT.", w / 2f, h / 2f - 10f * d, 26f, Color.WHITE); mgText(c, "Tap anywhere to try again.", w / 2f, h / 2f + 16f * d, 12f, a(Color.WHITE, 0xCC), false) }
                }
            }
        }
        if (mgName == "shoot" || (mgName == "prison" && prison?.phase == PrisonWorld.Phase.PLAY) || (mgName == "finale" && finale?.stage == FinaleWorld.Stage.CLIMB)) drawJoystick(c)
        if (mgName == "finale" && finale?.stage == FinaleWorld.Stage.CLIMB) {
            // JUMP: tap anywhere on the right (the button shows where)
            val gold = 0xFFFFD84D.toInt()
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(gunCx, joyCy, gunR + 4f * d, p)
            p.color = a(gold, 0x55); c.drawCircle(gunCx, joyCy, gunR - 4f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(gold, 0xCC); c.drawCircle(gunCx, joyCy, gunR, p); p.style = Paint.Style.FILL
            mgText(c, "JUMP", gunCx, joyCy + 4f * d, 12f, a(Color.WHITE, 0xEE))
        }
        if (mgName == "shoot" && shoot?.phase == ShootWorld.Phase.PLAY) {
            val sw = shoot!!
            val cy = joyCy
            // GUN: hold to shoot the closest officer you can see
            val hasT = sw.nearestTarget() != null
            val red = 0xFFFF6A6A.toInt()
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(gunCx, cy, gunR + 4f * d, p)
            p.color = a(red, if (gunId >= 0) 0xAA else if (hasT) 0x66 else 0x2A); c.drawCircle(gunCx, cy, gunR - 4f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(red, if (hasT) 0xAA else 0x55); c.drawCircle(gunCx, cy, gunR, p)
            p.strokeWidth = 2.2f * d; p.color = a(Color.WHITE, if (hasT) 0xEE else 0x66)
            c.drawCircle(gunCx, cy, 15f * d, p)
            c.drawLine(gunCx - 24f * d, cy, gunCx - 8f * d, cy, p); c.drawLine(gunCx + 8f * d, cy, gunCx + 24f * d, cy, p)
            c.drawLine(gunCx, cy - 24f * d, gunCx, cy - 8f * d, p); c.drawLine(gunCx, cy + 8f * d, gunCx, cy + 24f * d, p)
            // GRENADE: tap to throw at the biggest group in range
            val ready = sw.grenades > 0 && sw.grCd() <= 0f
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(grCx, grCy, grR + 4f * d, p)
            val amber = 0xFFFFB347.toInt()
            p.color = a(amber, if (ready) 0x55 else 0x22); c.drawCircle(grCx, grCy, grR - 3f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(amber, if (ready) 0xAA else 0x55); c.drawCircle(grCx, grCy, grR, p)
            p.style = Paint.Style.FILL; p.color = a(Color.WHITE, if (ready) 0xEE else 0x66)
            c.drawCircle(grCx, grCy + 3f * d, 10f * d, p); c.drawRect(grCx - 3f * d, grCy - 11f * d, grCx + 3f * d, grCy - 6f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; c.drawLine(grCx + 2f * d, grCy - 10f * d, grCx + 9f * d, grCy - 14f * d, p)
            mgText(c, "x${sw.grenades}", grCx, grCy + grR + 14f * d, 11f, a(Color.WHITE, 0xCC))
            if (grMsgT > 0f) mgText(c, grMsg, grCx, grCy - grR - 8f * d, 11f, a(0xFFFF6A6A.toInt(), min(1f, grMsgT * 3f * 255f / 255f).let { (it * 255).toInt() }))
        }
        if (mgName == "prison" && prison?.phase == PrisonWorld.Phase.PLAY) {
            val pw = prison!!
            val pr = pw.prompt()
            val can = pr.isNotEmpty() && !pr.endsWith("…") && pr != "CLOSED" && pr != "HANDS FULL" && pr != "CART EMPTY" && pr != "WASHER" && pr != "DRYER" && pr != "FOLDING TABLE" && pr != "CLEAN CART"
            val gold = 0xFFFFD84D.toInt()
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(gunCx, joyCy, gunR + 4f * d, p)
            p.color = a(gold, if (can) 0x66 else 0x1E); c.drawCircle(gunCx, joyCy, gunR - 4f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(gold, if (can) 0xCC else 0x50); c.drawCircle(gunCx, joyCy, gunR, p)
            p.style = Paint.Style.FILL
            if (pr.length <= 11) mgText(c, if (pr.isEmpty()) "USE" else pr, gunCx, joyCy + 4f * d, if (pr.length > 9) 9f else 11f, a(Color.WHITE, if (can) 0xEE else 0x77))
            else {
                // a long one goes in a little label above the button, right-aligned to the screen edge
                mgText(c, "USE", gunCx, joyCy + 4f * d, 11f, a(Color.WHITE, 0xEE))
                p.textSize = 10f * d; p.typeface = monoBold
                val tw = p.measureText(pr) + 16f * d
                val rx = min(w - 8f * d, gunCx + tw / 2f); val lx = rx - tw; val ly = joyCy - gunR - 34f * d
                p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0xB0); c.drawRect(lx, ly, rx, ly + 22f * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(gold, 0xAA); c.drawRect(lx, ly, rx, ly + 22f * d, p); p.style = Paint.Style.FILL
                mgText(c, pr, (lx + rx) / 2f, ly + 15f * d, 10f, a(Color.WHITE, 0xEE))
            }
        }
        if (mgIdx == 0 && arcadeId == null && attempt == 1) drawTitleCard(c, w, h, spec.pal)
        drawFade(c, w, h)
    }


    /** After the game is beaten: pick any of the six letters to play. */
    private fun drawChooser(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF03050A.toInt())
        p.textAlign = Paint.Align.CENTER; p.style = Paint.Style.FILL
        p.typeface = monoBold; p.letterSpacing = 0.3f; p.textSize = 24f * d; p.color = Color.WHITE
        val top = barH + 40f * d
        c.drawText("DEAD LETTER", w / 2, top + 20f * d, p)
        p.typeface = mono; p.letterSpacing = 0.15f; p.textSize = 12f * d; p.color = a(Color.WHITE, 0x99)
        c.drawText("THE ARCADE", w / 2, top + 46f * d, p)
        chooserRows.clear()
        val rowH = min(84f * d, (h - top - 80f * d - botInset - 20f * d) / (ARCADE.size + 1))
        for ((i, g) in ARCADE.withIndex()) {
            val y0 = top + 70f * d + i * rowH
            val r = RectF(20f * d, y0, w - 20f * d, y0 + rowH - 8f * d); chooserRows += r
            val accent = levels[when (g.first) { "maze" -> 1; "belt", "fly" -> 2; "hall" -> 3; else -> 5 }].pal.accent
            p.style = Paint.Style.FILL; p.color = 0xFF0A0E16.toInt(); c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(accent, 0x88); c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT
            p.typeface = monoBold; p.letterSpacing = 0.04f; p.textSize = 16f * d; p.color = Color.WHITE
            c.drawText(g.second, r.left + 18f * d, r.centerY() - 2f * d, p)
            p.typeface = mono; p.letterSpacing = 0.1f; p.textSize = 10f * d; p.color = a(accent, 0xDD)
            c.drawText(ARCADE_HOW[g.first] ?: "", r.left + 18f * d, r.centerY() + 16f * d, p)
            p.textAlign = Paint.Align.RIGHT; p.typeface = monoBold; p.textSize = 11f * d; p.color = 0xFFFFD84D.toInt()
            c.drawText("BEST ${best(g.first)}", r.right - 16f * d, r.centerY() + 4f * d, p)
            p.textAlign = Paint.Align.CENTER
        }
        run {
            val y0 = top + 70f * d + ARCADE.size * rowH
            val r = RectF(20f * d, y0, w - 20f * d, y0 + rowH - 8f * d); chooserRows += r
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(Color.WHITE, 0x44); c.drawRoundRect(r, 6f * d, 6f * d, p)
            p.style = Paint.Style.FILL; p.typeface = mono; p.letterSpacing = 0.15f; p.textSize = 12f * d; p.color = a(Color.WHITE, 0xAA)
            c.drawText("THE STORY, FROM THE START", w / 2, r.centerY() + 4f * d, p)
        }
        p.letterSpacing = 0f
    }

    /** Carries a fixed scene's outer rows and columns out to the screen's edges and fades them to black, instead of hard black bars. */
    private val edgeFade = Paint()
    private fun extendEdges(c: Canvas, bmp: android.graphics.Bitmap, ox: Float, oy: Float, sc: Int, w: Float, h: Float) {
        val bw = bmp.width * sc.toFloat(); val bh = bmp.height * sc.toFloat()
        val sr = android.graphics.Rect(); val dr = RectF()
        if (oy > 0f) {
            sr.set(0, 0, bmp.width, 1); dr.set(ox, 0f, ox + bw, oy); c.drawBitmap(bmp, sr, dr, nearest)
            sr.set(0, bmp.height - 1, bmp.width, bmp.height); dr.set(ox, oy + bh, ox + bw, h); c.drawBitmap(bmp, sr, dr, nearest)
            edgeFade.shader = LinearGradient(0f, 0f, 0f, oy, 0xFF000000.toInt(), 0x90000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, oy, edgeFade)
            edgeFade.shader = LinearGradient(0f, oy + bh, 0f, h, 0x90000000.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, oy + bh, w, h, edgeFade)
        }
        if (ox > 0f) {
            sr.set(0, 0, 1, bmp.height); dr.set(0f, oy, ox, oy + bh); c.drawBitmap(bmp, sr, dr, nearest)
            sr.set(bmp.width - 1, 0, bmp.width, bmp.height); dr.set(ox + bw, oy, w, oy + bh); c.drawBitmap(bmp, sr, dr, nearest)
            edgeFade.shader = LinearGradient(0f, 0f, ox, 0f, 0xFF000000.toInt(), 0x90000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, ox, h, edgeFade)
            edgeFade.shader = LinearGradient(ox + bw, 0f, w, 0f, 0x90000000.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(ox + bw, 0f, w, h, edgeFade)
        }
        edgeFade.shader = null
    }

    internal fun debugMg(i: Int) { startMg(i) }
    internal fun debugMgAdvance(sec: Float) { var t = sec; while (t > 0f) { val s = min(t, 0.025f); clock += s; mgUpdate(s); t -= s } }
    internal fun debugMgIdx() = mgIdx
    internal fun debugLevelIdx() = levelIdx
    internal fun debugFinale() = finale
    internal fun debugBannerUp() = bannerUp

    // screen -> tiles
    private var T = 1f; private var offX = 0f; private var offY = 0f

    private val spec get() = levels[levelIdx]
    private val lastLevel get() = levelIdx == levels.size - 1
    /** 0..1: how far the final broadcast has changed the city (posters, screens, radios). */
    private val wt get() = max(0f, world.stateT - world.wonDelay)   // time since the win screen could start (the contact may be busy first)
    private val bc get() = if (lastLevel && world.state == StealthWorld.State.WON) ((wt - 1.2f) / 2.5f).coerceIn(0f, 1f) else 0f

    init { load(false) }

    /** Start the level you are on again from the beginning. */
    internal fun restart() { val a0 = arcadeId; if (a0 != null) startArcade(a0) else load(false) }

    /** Is a Spotted / Delivered style screen showing (so that any tap should dismiss it)? */
    private fun overlayUp(): Boolean = world.state != StealthWorld.State.PLAY || pac?.phase == PacWorld.Phase.CAUGHT || pac?.phase == PacWorld.Phase.WON

    /** The level's title banner is showing (you can't move until it's gone). */
    private val bannerUp get() = mgIdx < 0 && stage == 0 && sinceLoad < cardS && attempt == 1 && world.state == StealthWorld.State.PLAY
    /** How long each level's title card stays up (long enough to read it all; a tap skips it), and the how-to-play line after it. */
    private val cardS get() = 3f + (spec.objective.length + spec.situation.length) / 19f
    private val tipS get() = 4f + spec.tip.length / 18f
    /** The title card is up at the start of a mini-game level (the game waits behind it). */
    private val mgCardUp get() = mgIdx == 0 && arcadeId == null && attempt == 1 && sinceLoad < cardS

    /** On to the next level. */
    internal fun skipLevel() { if (levelIdx < levels.size - 1) { levelIdx++; saveLevel(levelIdx); load(false) } }

    /**
     * The skip arrow (for building the game): on to the next room or scene, not the next level. Level 1 goes straight to the sewer maze;
     * level 2: maze > maintenance room; level 3: belts > jetpack > cop cutscene; level 4: arrival > shoot-out; level 5 is one place;
     * level 6: beach > tower > climb > (skips the shot and the door) control room > uplink > broadcast > the end.
     */
    internal fun skip() {
        if (arcadeId != null) return
        finale?.let { fw -> fw.skip(); return }
        shoot?.let { sw -> if (sw.phase != ShootWorld.Phase.FINALE && sw.phase != ShootWorld.Phase.DONE) { sw.skipToMayor(); return } }   // the shoot-out: to the mayor first
        val stages = stagesOf(spec)
        if (mgIdx >= 0 && stages != null) { if (mgIdx + 1 < stages.size) { fade = 0f; fadeDir = 0; mgPending = -9; startMg(mgIdx + 1) } else skipLevel(); return }
        if (levelIdx == 0) { skipLevel(); toStage(1); return }
        if (spec.pac != null && stage < 2) { toStage(stage + 1); return }
        skipLevel()
    }
    private fun toStage(s: Int) { if (spec.pac == null) return; pendingStage = s; switchStage(); fade = 0f; fadeDir = 0 }

    /** Hold the restart button: back to level one. */
    internal fun restartAll() { levelIdx = 0; saveLevel(0); load(false) }

    // for the picture-taking test
    internal fun debugLevel(i: Int) { levelIdx = i; load(false); sinceLoad = 9f; attempt = 2 }
    internal fun debugAdvance(sec: Float, ix: Float = 0f, iy: Float = 0f, mag: Float = 0f) { var t = sec; while (t > 0f) { val s = min(t, 0.025f); clock += s; sinceLoad += s; world.update(s, ix, iy, mag); updateClue(s); t -= s } }
    internal fun debugWorld() = world
    internal fun debugIntro() { attempt = 1; sinceLoad = 1.5f }
    internal fun debugStage(s: Int) { pendingStage = s; switchStage(); fade = 0f; fadeDir = 0 }
    internal fun debugPac() = pac
    internal fun debugPacAdvance(sec: Float, want: Int = -1) { var t = sec; while (t > 0f) { val s = min(t, 0.02f); clock += s; pac?.update(s, want); t -= s } }
    internal fun debugPacWant(w: Int) { pacWant = w }
    internal fun debugStageTick(dt: Float) = stageTick(dt)
    internal fun debugStage() = stage
    internal fun debugFadeDone() = fadeDir == 0 && fade <= 0.001f

    private fun load(retry: Boolean) {
        world = StealthWorld(spec)
        pixelScene = if (spec.pixel) PixelScene(spec, world) else null
        stage = 0; pac = null; pacScene = null; fade = 0f; fadeDir = 0; pendingStage = -1; pacWant = -1; joyActive = false; descentT = -1f; descent = null
        sinceLoad = if (retry) 99f else 0f
        if (!retry) { attempt = 1; read.clear() }
        mag = 0f; clueText = null; clueAlpha = 0f
        music.setHeat(spec.heat); music.setCalm(false)
        mgIdx = -1; if (stagesOf(spec) != null) startMg(0)
        if (carryFade) { carryFade = false; fade = 1f; fadeDir = -1 }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0L; applyCue(); postOnAnimation(tick) }
    /** The app went to the background or the screen went off: stop the clock and the sound. */
    internal fun pauseGame() { running = false; tracks.stop(); music.stop() }
    /** Back again: carry on where we were. */
    internal fun resumeGame() { if (!isAttachedToWindow || running) return; running = true; last = 0L; cue = ""; applyCue(); postOnAnimation(tick) }
    override fun onDetachedFromWindow() { running = false; tracks.stop(); music.stop(); super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (choosing) { last = now; invalidate(); postOnAnimation(this); return }      // paused behind the "choose a letter" screen
            if (last != 0L) {
                val raw = ((now - last) / 1e9f).coerceAtMost(0.05f)
                var dt = raw
                clock += dt; sinceLoad += dt
                if (mgIdx >= 0) { if (!mgCardUp) mgUpdate(raw) } else stageTick(raw)
                var left = dt
                while (mgIdx < 0 && left > 0f) {
                    val s = min(left, 0.025f)
                    val pw = pac
                    if (pw != null) { if (fadeDir <= 0) pw.update(s, pacWant) } else world.update(s, ix, iy, if (sinceLoad < 0.4f || bannerUp || descentT >= 0f) 0f else mag)
                    left -= s
                }
                updateClue(raw)
                wantedCue()?.let { if (it != cue) { cue = it; applyCue() } }
                if (lastLevel && world.state == StealthWorld.State.WON) music.setCalm(true)
            }
            last = now
            invalidate()
            postOnAnimation(this)
        }
    }

    private val joyR get() = 56f * d
    private var joyFx = -1f; private var joyFy = -1f      // in the maze the stick floats: it sits wherever you put your thumb down
    private val joyCx get() = if (joyFx >= 0f) joyFx else 24f * d + joyR + 6f * d
    private val joyCy get() = if (joyFy >= 0f) joyFy else height - 30f * d - botInset - joyR

    /** Read the joystick: the courier walks the way you push, faster the harder you push (a light push sneaks quietly). */
    private fun readJoystick(x: Float, y: Float) {
        val dx = x - joyCx; val dy = y - joyCy
        val len = hypot(dx, dy)
        val shown = min(len, joyR)
        if (len > 0.001f) { knobX = dx / len * shown; knobY = dy / len * shown } else { knobX = 0f; knobY = 0f }
        val dead = if (pac != null) 4f * d else 7f * d
        if (len < dead) { mag = 0f; return }
        ix = dx / len; iy = dy / len
        mag = ((shown - dead) / (joyR - dead)).coerceIn(0f, 1f)
        if (mag > (if (pac != null) 0.12f else 0.35f)) pacWant = if (kotlin.math.abs(ix) > kotlin.math.abs(iy)) (if (ix > 0) PacWorld.RIGHT else PacWorld.LEFT) else (if (iy > 0) PacWorld.DOWN else PacWorld.UP)
    }

    /** Moves level 2 from one part to the next, with a fade to black between. */
    // level 2: climbing down the manhole between the street and the maze (DescentScene)
    private var descentT = -1f
    private var descent: DescentScene? = null
    private fun descentTick(dt: Float) {
        val t0 = descentT; descentT += dt
        fun at(s: Float) = t0 < s && descentT >= s
        if (soundOn) { if (descentT in DescentScene.CLIMB0..DescentScene.CLIMB1 && (descentT * 4f).toInt() != (t0 * 4f).toInt()) DeadLetterSfx.play("type"); if (at(DescentScene.LID0 + 0.4f)) DeadLetterSfx.play("clank") }
        if (at(DescentScene.BLACK) && stage == 0) { pendingStage = 1; switchStage() }
        if (descentT >= DescentScene.END) { descentT = -1f; descent = null }
    }
    private fun drawDescent(c: Canvas, w: Float, h: Float) {
        val ds = descent ?: return
        val t = descentT
        // into the shaft (the street fades out), then, after the black, the maze glows up underneath
        val alpha = when { t < 0.45f -> t / 0.45f; t > DescentScene.BLACK -> 1f - (t - DescentScene.BLACK) / (DescentScene.END - DescentScene.BLACK); else -> 1f }.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val bmp = ds.render(t, clock)
        val sc = max(1, ceil(w / ds.W).toInt()); val ox = (w - ds.W * sc) / 2f; val oy = (h - ds.H * sc) / 2f
        val pa = Paint(nearest).apply { this.alpha = (alpha * 255).toInt() }
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, (alpha * 255).toInt()); c.drawRect(0f, 0f, w, h, p)
        if (t < DescentScene.BLACK) { rect.set(ox, oy, ox + ds.W * sc, oy + ds.H * sc); c.drawBitmap(bmp, null, rect, pa) }
    }

    private fun stageTick(dt: Float) {
        if (descentT >= 0f) { descentTick(dt); if (descentT >= 0f && stage == 0) return }
        if (arcadeId == "maze") { arcadePacTick(dt); return }
        if (fadeDir > 0) { fade += dt / 0.8f; if (fade >= 1f) { fade = 1f; switchStage(); fadeDir = -1 } }
        else if (fadeDir < 0) { fade -= dt / 0.8f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }
        else if (stage == 0 && world.enteredManhole && spec.pac != null) { world.sayText = null; pendingStage = 1; fadeDir = 1 }   // down the manhole: fade into the maze
        else if (spec.pixel && !lastLevel && world.state == StealthWorld.State.WON && (stage == 0 || stage == 2) && fadeDir == 0) {
            // the alley and the maintenance room end without a card: a fade, then the next part of the story
            if (world.spec.scene != null) { carryFade = true; levelIdx++; saveLevel(levelIdx); load(false) }
            else if (wt > 1.4f) { carryFade = true; levelIdx++; saveLevel(levelIdx); load(false) }
        }
        else if (stage == 1) {
            val pw = pac
            if (pw != null) {
                if (pw.phase == PacWorld.Phase.WON && pw.phaseT > 0.9f && spec.room != null) { pendingStage = 2; fadeDir = 1 }
                if (pw.phase == PacWorld.Phase.CAUGHT && world.state != StealthWorld.State.CAUGHT) { world.state = StealthWorld.State.CAUGHT; world.spotter = "A guard caught you."; world.stateT = 0f }
                else if (world.state == StealthWorld.State.CAUGHT) world.stateT += dt     // the world is not running in the maze, so count the red screen here
            }
        }
    }

    private fun switchStage() {
        music.setCalm(false)
        if (pendingStage == 1) {
            stage = 1; pac = PacWorld(spec.pac!!); pacScene = PacScene(pac!!); pacWant = -1; sinceLoad = 99f; joyActive = false; mag = 0f; music.setHeat(4)
        } else if (pendingStage == 2) {
            stage = 2; pac = null; pacScene = null
            val rs = spec.room!!
            world = StealthWorld(rs); pixelScene = PixelScene(rs, world)
            sinceLoad = 99f; read.clear(); clueText = null; clueAlpha = 0f; joyActive = false; mag = 0f; music.setHeat(1)
        }
        pendingStage = -1
    }

    private fun restartPac() {
        val f = (pac?.fails ?: 0) + 1
        pac = PacWorld(spec.pac!!).also { it.fails = f }; pacScene = PacScene(pac!!); pacWant = -1
        world.state = StealthWorld.State.PLAY; world.stateT = 0f
    }

    private fun updateClue(dt: Float) {
        var best: Prop? = null; var bd = 99f; var bi = -1
        if (world.state == StealthWorld.State.PLAY && pac == null) for ((i, pr) in world.spec.props.withIndex()) {
            if (pr.text == null) continue
            val reach = if (pr.kind == "window") (if (world.spec.walk) 2.2f else 2.1f) else 1.15f      // you can hear a radio from further off
            val dd = hypot(world.px - pr.x, world.py - pr.y)
            if (dd < reach && dd < bd) { bd = dd; best = pr; bi = i }
        }
        clueT += dt
        if (best != null) {
            if (clueText != best.text) clueT = 0f
            clueText = best.text; read += bi; clueAlpha = min(1f, clueAlpha + dt * 5f)
        } else clueAlpha = max(0f, clueAlpha - dt * 2.5f)
    }

    // ---------- thumb ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (arcadeId != null && arcOver) { if (e.actionMasked == MotionEvent.ACTION_UP && arcOverT > 0.8f) startArcade(arcadeId!!); return true }
        if (choosing) {
            if (e.actionMasked == MotionEvent.ACTION_UP) for ((i, r) in chooserRows.withIndex()) if (r.contains(e.x, e.y)) {
                if (i < ARCADE.size) startArcade(ARCADE[i].first)
                else { choosing = false; arcadeId = null; levelIdx = 0; saveLevel(0); load(false) }   // the story, from the start
                break
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (mgCardUp || bannerUp) && e.y > barH + 46f * d) { sinceLoad = cardS; return true }   // tap: skip the title card
        if (mgIdx >= 0) return mgTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > width - 52f * d && e.y > barH && e.y < barH + 46f * d) { soundTap = true; return true }
                overlayAtDown = overlayUp()                       // a tap anywhere dismisses a Spotted / Delivered screen
                touchWasJoy = !overlayAtDown && hypot(e.x - joyCx, e.y - joyCy) < joyR * 1.7f
                if (!overlayAtDown && pac != null && e.y > barH) { joyFx = e.x; joyFy = e.y; touchWasJoy = true }
                if (touchWasJoy) { joyActive = true; readJoystick(e.x, e.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (soundTap) return true
                if (joyActive) readJoystick(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (soundTap) {
                    soundTap = false
                    if (e.actionMasked == MotionEvent.ACTION_UP) { soundOn = !soundOn; saveSound(soundOn); applyCue() }
                    return true
                }
                joyActive = false; mag = 0f; knobX = 0f; knobY = 0f; joyFx = -1f; joyFy = -1f
                if (e.actionMasked == MotionEvent.ACTION_UP && overlayAtDown) when (world.state) {
                    StealthWorld.State.CAUGHT -> if (world.stateT > 0.5f) { attempt++; if (stage == 1) restartPac() else load(true) }
                    StealthWorld.State.WON -> if (lastLevel) {
                        if (wt > 12f) { saveLevel(0); beaten = true; saveBeaten(true); choosing = true; tracks.stop(); onExit() }
                    } else if (wt > 0.8f) { levelIdx++; saveLevel(levelIdx); load(false) }
                    else -> {}
                }
            }
        }
        return true
    }

    // ---------- drawing ----------
    private fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun mix(c1: Int, c2: Int, f: Float): Int {
        fun ch(s: Int) = (((c1 shr s) and 255) * (1 - f) + ((c2 shr s) and 255) * f).toInt()
        return Color.argb(255, ch(16), ch(8), ch(0))
    }
    private val paper = 0xFFE6E0CF.toInt()
    private val ink = 0xFF1A1B20.toInt()
    private val danger = 0xFFFF4D4D.toInt()

    /** Draws the game, then a blurred copy of it behind the see-through title bar (blur needs Android 12; older phones get a dark fade). */
    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        val bh = barH.toInt().coerceAtMost(h)
        if (android.os.Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated && w > 0 && h > 0) {
            val n = node ?: android.graphics.RenderNode("dd-game").also { node = it }
            val bn = blurNode ?: android.graphics.RenderNode("dd-bar").also {
                blurNode = it; it.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP))
            }
            n.setPosition(0, 0, w, h)
            val rc = n.beginRecording()
            try { drawAll(rc) } finally { n.endRecording() }
            c.drawRenderNode(n)
            bn.setPosition(0, 0, w, bh)
            val rc2 = bn.beginRecording()
            try { rc2.drawRenderNode(n) } finally { bn.endRecording() }
            c.drawRenderNode(bn)
        } else drawAll(c)
        // a soft dark fade so the title and buttons stay readable
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), intArrayOf(0x99000000.toInt(), 0x55000000, 0x00000000), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), bh.toFloat(), p); p.shader = null
    }

    private fun drawAll(c: Canvas) {
        if (choosing) { drawChooser(c); return }
        if (mgIdx >= 0) { drawMg(c); drawArcade(c); return }
        val w = width.toFloat(); val h = height.toFloat()
        val s = world.spec
        val pal = s.pal
        c.drawColor(pal.wall)
        val top = barH + 30f * d; val bottom = 14f * d + botInset
        T = min(w / world.cols, (h - top - bottom) / world.rows)
        offX = (w - T * world.cols) / 2; offY = top + (h - top - bottom - T * world.rows) / 2

        val fade = 1f - bc
        val pix = pixelScene
        val ps = pacScene; val pw = pac
        if (ps != null && pw != null) {
            drawPacStage(c, w, h, ps, pw)
        } else if (pix != null) {
            // pixel-art level: the scene is drawn small and scaled up by a whole number so every pixel is the same size.
            // Tall maps scroll: the view follows the courier (kept to whole art pixels so nothing shimmers).
            // fills the whole screen edge to edge: scaled to the width (or the height for short maps), the extra is cropped
            // a short room (like the maintenance room under the street) fits the width and sits in the middle of the screen,
            // so you see all of it instead of a zoomed-in, cropped slice
            val short = world.rows * (w / world.cols) <= h - barH
            T = if (short) w / world.cols else max(w / world.cols, h / world.rows)
            offX = (w - T * world.cols) / 2f
            val viewRows = min(world.rows.toFloat(), h / T)
            scrollY = if (short) 0f else ((world.py - viewRows * 0.68f).coerceIn(0f, world.rows - viewRows) * 16f).toInt() / 16f   // look ahead: you sit low on the screen
            offY = if (short) (barH + (h - barH - T * world.rows) / 2f).toInt().toFloat() else 0f
            val srcTop = (scrollY * 16f).toInt()
            val srcH = min(pix.H - srcTop, ceil(h / T * 16f).toInt())
            c.drawColor(0xFF03050A.toInt())
            val src = android.graphics.Rect(0, srcTop, pix.W, srcTop + srcH)
            rect.set(offX, offY, offX + T * world.cols, offY + srcH * T / 16f)
            c.drawBitmap(pix.render(clock, read, bc), src, rect, nearest)
        } else {
        c.save(); c.translate(offX, offY); c.scale(T, T)
        drawFloor(c, pal)
        drawLamps(c, pal)
        drawCoverAndDesks(c, pal)
        drawDoorsAndKeys(c, pal)
        drawWalls(c, pal)
        drawProps(c, pal)
        drawLasers(c)
        for (g in world.guards) if (!g.dead) drawCone(c, g.eye(), pal.cone, g.meter, fade)
        for (cm in world.cams) drawCone(c, cm.eye(), pal.cone, cm.meter, fade)
        for (cm in world.cams) drawCamera(c, cm, pal)
        drawFeet(c)
        drawContact(c, pal)
        for (g in world.guards) drawGuard(c, g, pal)
        drawPlayer(c, pal)
        c.restore()
        }

        drawMarks(c)
        drawLight(c, w, h, pal)
        if (arcadeId == null) { c.save(); c.translate(0f, barH - 8f * d); drawHud(c, w, h, pal); c.restore() }
        drawJoystick(c)
        drawClue(c, w, h, pal)
        drawSay(c, w, h, pal)
        drawOverlays(c, w, h, pal)
        if (descentT >= 0f) drawDescent(c, w, h)
        drawFade(c, w, h)
        drawArcade(c)
    }

    private fun drawFloor(c: Canvas, pal: Palette) {
        p.style = Paint.Style.FILL; p.shader = null
        p.color = pal.floor; c.drawRect(0f, 0f, world.cols.toFloat(), world.rows.toFloat(), p)
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            if (world.tile(col, r) == '#' || world.tile(col, r) == ' ') continue
            if ((col + r) % 2 == 0) { p.color = a(Color.WHITE, 3); c.drawRect(col.toFloat(), r.toFloat(), col + 1f, r + 1f, p) }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.02f; p.color = pal.floorLine
        for (i in 1 until world.cols) c.drawLine(i.toFloat(), 0f, i.toFloat(), world.rows.toFloat(), p)
        for (i in 1 until world.rows) c.drawLine(0f, i.toFloat(), world.cols.toFloat(), i.toFloat(), p)
    }

    private fun drawLamps(c: Canvas, pal: Palette) {
        for ((x, y) in world.lamps) {
            val r = StealthWorld.LAMP_R + 0.35f
            val flick = 1f + 0.04f * sin(clock * 5f + x * 3f)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(x, y, r, intArrayOf(a(pal.lamp, (0x66 * flick).toInt()), a(pal.lamp, 0x2c), a(pal.lamp, 0x00)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p); p.shader = null
            p.color = a(pal.lamp, 0xDD); c.drawCircle(x, y, 0.1f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.035f; p.color = a(pal.lamp, 0x77); c.drawCircle(x, y, 0.22f, p)
        }
    }

    private fun drawCoverAndDesks(c: Canvas, pal: Palette) {
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val ch = world.tile(col, r)
            val x = col.toFloat(); val y = r.toFloat()
            if (ch == 'c' && spec.cover == "bin") {
                drawBin(c, x, y)
            } else if (ch == 'c') {
                p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x70); rect.set(x + 0.1f, y + 0.14f, x + 1.02f, y + 1.06f); c.drawRoundRect(rect, 0.1f, 0.1f, p)
                p.color = mix(pal.wallTop, Color.BLACK, 0.25f); rect.set(x + 0.04f, y + 0.04f, x + 0.96f, y + 0.96f); c.drawRoundRect(rect, 0.1f, 0.1f, p)
                p.color = mix(pal.wallTop, pal.accent, 0.12f); rect.set(x + 0.1f, y + 0.1f, x + 0.9f, y + 0.9f); c.drawRoundRect(rect, 0.08f, 0.08f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.03f; p.color = a(Color.BLACK, 0x66)
                for (i in 1..3) c.drawLine(x + 0.18f, y + 0.1f + i * 0.2f, x + 0.82f, y + 0.1f + i * 0.2f, p)
                p.style = Paint.Style.FILL; p.color = a(pal.accent, 0xE0); c.drawCircle(x + 0.76f, y + 0.2f, 0.03f, p)
                p.color = a(Color.WHITE, 0x22); c.drawRect(x + 0.1f, y + 0.1f, x + 0.9f, y + 0.115f, p)
            } else if (ch == 'd') {
                p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x60); rect.set(x + 0.1f, y + 0.18f, x + 0.98f, y + 0.94f); c.drawRoundRect(rect, 0.08f, 0.08f, p)
                p.color = mix(pal.floor, Color.rgb(120, 92, 70), 0.55f); rect.set(x + 0.06f, y + 0.12f, x + 0.94f, y + 0.88f); c.drawRoundRect(rect, 0.08f, 0.08f, p)
                p.color = a(Color.WHITE, 0x18); rect.set(x + 0.06f, y + 0.12f, x + 0.94f, y + 0.2f); c.drawRoundRect(rect, 0.05f, 0.05f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = a(Color.BLACK, 0x55); c.drawLine(x + 0.1f, y + 0.5f, x + 0.9f, y + 0.5f, p)
            }
        }
    }

    /** A big alley dumpster: you can hide behind it. */
    private fun drawBin(c: Canvas, x: Float, y: Float) {
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x70); rect.set(x + 0.1f, y + 0.16f, x + 1.02f, y + 1.06f); c.drawRoundRect(rect, 0.08f, 0.08f, p)
        p.color = 0xFF1F3A2E.toInt(); rect.set(x + 0.04f, y + 0.06f, x + 0.96f, y + 0.94f); c.drawRoundRect(rect, 0.08f, 0.08f, p)
        p.color = 0xFF2D5440.toInt(); rect.set(x + 0.08f, y + 0.1f, x + 0.92f, y + 0.5f); c.drawRoundRect(rect, 0.06f, 0.06f, p)          // lid
        p.color = 0xFF204534.toInt(); rect.set(x + 0.08f, y + 0.54f, x + 0.92f, y + 0.9f); c.drawRoundRect(rect, 0.06f, 0.06f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = a(Color.BLACK, 0x66)
        c.drawLine(x + 0.08f, y + 0.5f, x + 0.92f, y + 0.5f, p)
        for (i in 1..3) c.drawLine(x + 0.08f + i * 0.21f, y + 0.56f, x + 0.08f + i * 0.21f, y + 0.88f, p)
        p.style = Paint.Style.FILL; p.color = a(Color.WHITE, 0x22); c.drawRect(x + 0.1f, y + 0.1f, x + 0.9f, y + 0.125f, p)
        p.color = 0xFF14181C.toInt(); c.drawCircle(x + 0.3f, y + 0.12f, 0.09f, p); c.drawCircle(x + 0.55f, y + 0.1f, 0.07f, p)           // bin bags poking out
    }

    private fun solidWall(col: Int, r: Int) = world.tile(col, r).let { it == '#' || it == ' ' }

    private fun drawWalls(c: Canvas, pal: Palette) {
        p.style = Paint.Style.FILL
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            if (solidWall(col, r)) continue
            val x = col.toFloat(); val y = r.toFloat()
            p.color = a(Color.BLACK, 0x45)
            if (solidWall(col, r - 1)) c.drawRect(x, y, x + 1f, y + 0.2f, p)
            if (solidWall(col - 1, r)) c.drawRect(x, y, x + 0.2f, y + 1f, p)
            if (solidWall(col + 1, r)) c.drawRect(x + 0.8f, y, x + 1f, y + 1f, p)
            if (solidWall(col, r + 1)) c.drawRect(x, y + 0.82f, x + 1f, y + 1f, p)
        }
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            if (!solidWall(col, r)) continue
            val x = col.toFloat(); val y = r.toFloat()
            p.style = Paint.Style.FILL
            p.color = pal.wall; c.drawRect(x - 0.005f, y - 0.005f, x + 1.005f, y + 1.005f, p)
            val l = if (solidWall(col - 1, r)) 0f else 0.08f; val rr = if (solidWall(col + 1, r)) 0f else 0.08f
            val tp = if (solidWall(col, r - 1)) 0f else 0.08f; val bt = if (solidWall(col, r + 1)) 0f else 0.08f
            p.color = mix(pal.wallTop, pal.wall, 0.55f); c.drawRect(x + l, y + tp, x + 1f - rr, y + 1f - bt, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.035f; p.color = a(mix(pal.accent, Color.WHITE, 0.25f), 0x70)
            if (!solidWall(col, r + 1)) c.drawLine(x + l, y + 1f - bt, x + 1f - rr, y + 1f - bt, p)
            if (!solidWall(col, r - 1)) c.drawLine(x + l, y + tp, x + 1f - rr, y + tp, p)
            if (!solidWall(col - 1, r)) c.drawLine(x + l, y + tp, x + l, y + 1f - bt, p)
            if (!solidWall(col + 1, r)) c.drawLine(x + 1f - rr, y + tp, x + 1f - rr, y + 1f - bt, p)
        }
    }

    private fun keyColor(ch: Char) = if (ch == 'k' || ch == 'D') 0xFFFFC857.toInt() else 0xFF5BE0FF.toInt()

    private fun drawDoorsAndKeys(c: Canvas, pal: Palette) {
        for (r in 0 until world.rows) for (col in 0 until world.cols) {
            val ch = world.tile(col, r)
            if (ch != 'D' && ch != 'E') continue
            val x = col.toFloat(); val y = r.toFloat(); val kc = keyColor(ch)
            if (ch in world.opened) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.07f; p.color = a(kc, 0x99); c.drawLine(x + 0.05f, y + 0.1f, x + 0.05f, y + 0.9f, p)
            } else {
                p.style = Paint.Style.FILL; p.color = mix(pal.floor, kc, 0.18f); rect.set(x, y, x + 1f, y + 1f); c.drawRect(rect, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.06f; p.color = a(kc, 0xE0); rect.set(x + 0.08f, y + 0.08f, x + 0.92f, y + 0.92f); c.drawRect(rect, p)
                p.style = Paint.Style.FILL; p.color = a(kc, 0xF0); c.drawCircle(x + 0.5f, y + 0.46f, 0.12f, p); rect.set(x + 0.46f, y + 0.5f, x + 0.54f, y + 0.74f); c.drawRect(rect, p)
            }
        }
        for (k in world.keys) {
            if (k.taken) continue
            val kc = keyColor(k.ch)
            val bob = sin(clock * 3f) * 0.05f
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(k.x, k.y, 0.7f, intArrayOf(a(kc, 0x77), a(kc, 0x00)), null, Shader.TileMode.CLAMP)
            c.drawCircle(k.x, k.y, 0.7f, p); p.shader = null
            drawKeyIcon(c, k.x, k.y + bob, 0.34f, kc)
        }
    }

    private fun drawKeyIcon(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.2f; p.color = color
        c.drawCircle(x - size * 0.45f, y, size * 0.28f, p)
        c.drawLine(x - size * 0.17f, y, x + size * 0.6f, y, p)
        c.drawLine(x + size * 0.38f, y, x + size * 0.38f, y + size * 0.3f, p)
        c.drawLine(x + size * 0.6f, y, x + size * 0.6f, y + size * 0.24f, p)
    }

    // ---------- the story props ----------
    /** Small text in tile units (works because the canvas is scaled to tiles). */
    private fun tinyText(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true) {
        p.style = Paint.Style.FILL; p.typeface = if (bold) monoBold else mono; p.textAlign = Paint.Align.CENTER; p.textSize = size; p.color = color
        c.drawText(text, x, y, p)
    }

    private fun jitter(pr: Prop) = (((pr.x * 7f + pr.y * 13f) % 5f) - 2f) * 1.6f

    private fun drawProps(c: Canvas, pal: Palette) {
        for ((i, pr) in spec.props.withIndex()) {
            val x = pr.x; val y = pr.y
            // clues you have not read yet pulse gently
            if (pr.text != null && i !in read) {
                val ph = (clock * 0.9f + i * 0.37f) % 1f
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = a(pal.accent, ((1f - ph) * 0x55).toInt()); c.drawCircle(x, y, 0.3f + ph * 0.35f, p)
            }
            c.save(); c.rotate(if (pr.kind == "window") 0f else jitter(pr) + pr.rot, x, y)
            when (pr.kind) {
                "poster", "notice" -> drawPoster(c, x, y, pr.kind == "notice")
                "news" -> {
                    p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); rect.set(x - 0.24f, y - 0.14f, x + 0.3f, y + 0.24f); c.drawRect(rect, p)
                    p.color = paper; rect.set(x - 0.27f, y - 0.19f, x + 0.27f, y + 0.19f); c.drawRect(rect, p)
                    p.color = ink; rect.set(x - 0.22f, y - 0.15f, x + 0.22f, y - 0.07f); c.drawRect(rect, p)
                    p.color = a(ink, 0x77); rect.set(x - 0.22f, y - 0.03f, x - 0.04f, y + 0.12f); c.drawRect(rect, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.02f; p.color = a(ink, 0x77); for (k in 0..2) c.drawLine(x, y + k * 0.055f, x + 0.22f, y + k * 0.055f, p)
                }
                "radio" -> {
                    p.style = Paint.Style.FILL; p.color = 0xFF14171C.toInt(); rect.set(x - 0.2f, y - 0.12f, x + 0.2f, y + 0.12f); c.drawRoundRect(rect, 0.04f, 0.04f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.02f; p.color = a(Color.WHITE, 0x55); c.drawLine(x + 0.12f, y - 0.12f, x + 0.22f, y - 0.32f, p)
                    p.style = Paint.Style.FILL; p.color = a(Color.WHITE, 0x44); for (k in 0..2) c.drawCircle(x - 0.12f + k * 0.07f, y, 0.025f, p)
                    val on = bc < 0.6f && ((clock * 2f).toInt() % 2 == 0)
                    p.color = if (on) danger else a(Color.WHITE, 0x22); c.drawCircle(x + 0.13f, y + 0.05f, 0.03f, p)
                }
                "stuff" -> {
                    p.style = Paint.Style.FILL
                    p.color = 0xFF2B2F3A.toInt(); rect.set(x - 0.26f, y - 0.1f, x + 0.04f, y + 0.12f); c.save(); c.rotate(-18f, x, y); c.drawRoundRect(rect, 0.06f, 0.06f, p); c.restore()
                    p.color = pal.accent; rect.set(x + 0.04f, y - 0.2f, x + 0.2f, y - 0.04f); c.drawRect(rect, p)
                    p.color = paper; rect.set(x + 0.08f, y + 0.04f, x + 0.28f, y + 0.18f); c.save(); c.rotate(24f, x, y); c.drawRect(rect, p); c.restore()
                    p.color = 0xFF14171C.toInt(); c.drawOval(x - 0.22f, y + 0.14f, x - 0.08f, y + 0.22f, p); c.drawOval(x - 0.04f, y + 0.18f, x + 0.1f, y + 0.26f, p)
                }
                "screen" -> drawScreen(c, x, y, pal)
                "window" -> drawWindow(c, x, y, i, pal)
                "sign" -> {
                    p.style = Paint.Style.FILL; p.color = 0xFF14171C.toInt(); rect.set(x - 0.26f, y - 0.1f, x + 0.26f, y + 0.1f); c.drawRect(rect, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = a(0xFFFFC857.toInt(), 0xE0); c.drawRect(rect, p)
                    p.strokeWidth = 0.02f; c.drawLine(x - 0.18f, y - 0.02f, x + 0.18f, y - 0.02f, p); c.drawLine(x - 0.12f, y + 0.05f, x + 0.12f, y + 0.05f, p)
                }
                "car" -> drawCar(c, x, y, pr.w)
                "burned" -> {
                    p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x90); c.drawOval(x - 0.36f, y - 0.26f, x + 0.36f, y + 0.26f, p)
                    p.color = a(0xFFB9B29C.toInt(), 0xB0); c.save(); c.rotate(20f, x, y); rect.set(x - 0.14f, y - 0.1f, x + 0.08f, y + 0.04f); c.drawRect(rect, p); c.restore()
                    for (k in 0..3) { p.color = a(0xFFFF9A52.toInt(), ((0.4f + 0.6f * sin(clock * 4f + k * 2f).coerceAtLeast(0f)) * 0xCC).toInt()); c.drawCircle(x - 0.2f + k * 0.13f, y + 0.08f - (k % 2) * 0.18f, 0.025f, p) }
                }
                "photos" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.015f; p.color = a(danger, 0x99)
                    c.drawLine(x - 0.26f, y - 0.14f, x + 0.04f, y + 0.12f, p); c.drawLine(x + 0.04f, y + 0.12f, x + 0.26f, y - 0.1f, p); c.drawLine(x - 0.26f, y + 0.12f, x + 0.26f, y - 0.14f, p)
                    p.style = Paint.Style.FILL
                    for (k in 0 until 6) {
                        val px = x - 0.3f + (k % 3) * 0.22f; val py = y - 0.26f + (k / 3) * 0.3f
                        p.color = a(Color.BLACK, 0x66); rect.set(px + 0.02f, py + 0.02f, px + 0.2f, py + 0.26f); c.drawRect(rect, p)
                        p.color = paper; rect.set(px, py, px + 0.18f, py + 0.24f); c.drawRect(rect, p)
                        p.color = a(ink, 0x99); c.drawCircle(px + 0.09f, py + 0.09f, 0.04f, p); rect.set(px + 0.04f, py + 0.14f, px + 0.14f, py + 0.21f); c.drawRect(rect, p)
                    }
                }
                "terminal" -> {
                    p.style = Paint.Style.FILL; p.color = 0xFF0A0D10.toInt(); rect.set(x - 0.3f, y - 0.22f, x + 0.3f, y + 0.2f); c.drawRoundRect(rect, 0.04f, 0.04f, p)
                    p.shader = RadialGradient(x, y, 0.7f, intArrayOf(a(0xFF5BFF9A.toInt(), 0x33), a(0xFF5BFF9A.toInt(), 0)), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, 0.7f, p); p.shader = null
                    tinyText(c, "STATUS:", x, y - 0.07f, 0.085f, 0xFF5BFF9A.toInt()); tinyText(c, "DECEASED", x, y + 0.06f, 0.095f, if ((clock * 1.5f).toInt() % 2 == 0) danger else 0xFF5BFF9A.toInt())
                }
                "files" -> {
                    p.style = Paint.Style.FILL
                    for (k in 0..1) { p.color = if (k == 0) 0xFF8B7B4E.toInt() else 0xFFB59F63.toInt(); rect.set(x - 0.26f + k * 0.04f, y - 0.2f + k * 0.12f, x + 0.22f + k * 0.04f, y + 0.02f + k * 0.12f); c.drawRect(rect, p) }
                    p.color = ink; for (k in 0..2) { rect.set(x - 0.18f, y - 0.1f + k * 0.07f, x + 0.12f - k * 0.04f, y - 0.05f + k * 0.07f); c.drawRect(rect, p) }
                }
                "doc" -> {
                    p.style = Paint.Style.FILL; p.color = paper; rect.set(x - 0.26f, y - 0.32f, x + 0.26f, y + 0.32f); c.drawRect(rect, p)
                    p.color = a(ink, 0x77); for (k in 0..3) { rect.set(x - 0.2f, y - 0.24f + k * 0.07f, x + 0.2f - (k % 2) * 0.1f, y - 0.2f + k * 0.07f); c.drawRect(rect, p) }
                    c.save(); c.rotate(-14f, x, y + 0.14f); tinyText(c, "TERMINATED", x, y + 0.18f, 0.075f, danger); c.restore()
                }
                "barricade" -> {
                    c.save(); rect.set(x - 0.44f, y - 0.36f, x + 0.44f, y + 0.36f); c.clipRect(rect)
                    p.style = Paint.Style.FILL; p.color = 0xFF1B1710.toInt(); c.drawRect(rect, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 0.1f; p.color = a(pal.accent, 0xC0)
                    var k = -1.0f; while (k < 1.2f) { c.drawLine(x - 0.5f + k, y + 0.4f, x - 0.1f + k, y - 0.4f, p); k += 0.3f }
                    c.restore()
                    p.strokeWidth = 0.03f; p.color = a(Color.BLACK, 0x99); c.drawRect(rect, p)
                }
                "docs" -> {
                    p.style = Paint.Style.FILL
                    for (k in 0..3) { c.save(); c.rotate(k * 11f - 14f, x, y); p.color = a(paper, 0xE6 - k * 0x18); rect.set(x - 0.2f, y - 0.14f, x + 0.2f, y + 0.14f); c.drawRect(rect, p); c.restore() }
                }
                "photo" -> {
                    p.style = Paint.Style.FILL; p.color = 0xFFC9A24A.toInt(); rect.set(x - 0.26f, y - 0.22f, x + 0.26f, y + 0.22f); c.drawRect(rect, p)
                    p.color = 0xFF1A1710.toInt(); rect.set(x - 0.21f, y - 0.17f, x + 0.21f, y + 0.17f); c.drawRect(rect, p)
                    p.color = a(paper, 0xCC); c.drawCircle(x - 0.08f, y - 0.05f, 0.045f, p); rect.set(x - 0.14f, y + 0.0f, x - 0.02f, y + 0.15f); c.drawRect(rect, p)
                    c.drawCircle(x + 0.1f, y - 0.02f, 0.035f, p); rect.set(x + 0.05f, y + 0.03f, x + 0.15f, y + 0.15f); c.drawRect(rect, p)
                }
            }
            c.restore()
        }
    }

    /** An open window with a radio on the sill, blasting. Sound waves roll out of it; walk close to read what it says. */
    private fun drawWindow(c: Canvas, x: Float, y: Float, i: Int, pal: Palette) {
        val flick = 0.9f + 0.1f * sin(clock * 6f + i)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, 1.0f, intArrayOf(a(0xFFFFD79A.toInt(), (0x55 * flick).toInt()), 0), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, 1.0f, p); p.shader = null
        p.color = 0xFF07090C.toInt(); rect.set(x - 0.32f, y - 0.36f, x + 0.32f, y + 0.36f); c.drawRect(rect, p)
        p.color = a(0xFFFFD79A.toInt(), (0xE0 * flick).toInt()); rect.set(x - 0.27f, y - 0.31f, x + 0.27f, y + 0.31f); c.drawRect(rect, p)
        p.color = a(0xFF3B2A22.toInt(), 0xCC); rect.set(x - 0.27f, y - 0.31f, x - 0.1f, y + 0.31f); c.drawRect(rect, p); rect.set(x + 0.1f, y - 0.31f, x + 0.27f, y + 0.31f); c.drawRect(rect, p)   // curtains
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = 0xFF07090C.toInt(); c.drawLine(x, y - 0.31f, x, y + 0.31f, p); c.drawLine(x - 0.27f, y, x + 0.27f, y, p)
        p.style = Paint.Style.FILL; p.color = 0xFF14171C.toInt(); rect.set(x - 0.13f, y + 0.1f, x + 0.13f, y + 0.28f); c.drawRoundRect(rect, 0.03f, 0.03f, p)         // the radio on the sill
        p.color = a(Color.WHITE, 0x66); for (k in 0..2) c.drawCircle(x - 0.06f + k * 0.06f, y + 0.19f, 0.018f, p)
        // sound rolling out into the alley
        p.style = Paint.Style.STROKE
        for (k in 0..2) {
            val ph = (clock * 0.8f + k / 3f + i * 0.21f) % 1f
            p.strokeWidth = 0.03f; p.color = a(0xFFFFD79A.toInt(), ((1f - ph) * 0x70).toInt()); c.drawCircle(x, y, 0.4f + ph * 1.3f, p)
        }
    }

    private fun drawPoster(c: Canvas, x: Float, y: Float, notice: Boolean) {
        val b = bc
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); rect.set(x - 0.25f, y - 0.31f, x + 0.29f, y + 0.37f); c.drawRect(rect, p)
        p.color = mix(paper, 0xFFBFF0CF.toInt(), b); rect.set(x - 0.27f, y - 0.34f, x + 0.27f, y + 0.34f); c.drawRect(rect, p)
        p.color = mix(if (notice) 0xFFC98A12.toInt() else 0xFFB3261E.toInt(), 0xFF1F9D55.toInt(), b); rect.set(x - 0.27f, y - 0.34f, x + 0.27f, y - 0.2f); c.drawRect(rect, p)
        tinyText(c, if (b > 0.5f) "TRUTH" else if (notice) "MISSING" else "WANTED", x, y - 0.235f, 0.085f, paper)
        p.style = Paint.Style.FILL; p.color = a(ink, 0xCC)
        if (b > 0.5f) { p.style = Paint.Style.STROKE; p.strokeWidth = 0.05f; p.color = 0xFF1F9D55.toInt(); path.reset(); path.moveTo(x - 0.1f, y + 0.0f); path.lineTo(x - 0.02f, y + 0.08f); path.lineTo(x + 0.12f, y - 0.1f); c.drawPath(path, p) }
        else { c.drawCircle(x, y - 0.03f, 0.09f, p); rect.set(x - 0.15f, y + 0.07f, x + 0.15f, y + 0.19f); c.drawRoundRect(rect, 0.06f, 0.06f, p) }
        p.style = Paint.Style.FILL; p.color = a(ink, 0x77); rect.set(x - 0.2f, y + 0.22f, x + 0.2f, y + 0.25f); c.drawRect(rect, p); rect.set(x - 0.14f, y + 0.28f, x + 0.14f, y + 0.31f); c.drawRect(rect, p)
    }

    private fun drawScreen(c: Canvas, x: Float, y: Float, pal: Palette) {
        val b = bc
        val flick = 0.85f + 0.15f * sin(clock * 9f + x)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, 0.9f, intArrayOf(a(if (b > 0.3f) 0xFF5BFF9A.toInt() else pal.accent, (0x55 * flick).toInt()), a(pal.accent, 0)), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, 0.9f, p); p.shader = null
        p.color = 0xFF07090C.toInt(); rect.set(x - 0.35f, y - 0.26f, x + 0.35f, y + 0.26f); c.drawRoundRect(rect, 0.03f, 0.03f, p)
        p.color = mix(mix(pal.accent, Color.WHITE, 0.35f), 0xFF8DFFB5.toInt(), b); p.alpha = (0xE0 * flick).toInt(); rect.set(x - 0.3f, y - 0.21f, x + 0.3f, y + 0.21f); c.drawRect(rect, p); p.alpha = 255
        if (b > 0.4f) {   // the broadcast: a lively sound meter and LIVE
            p.color = 0xFF07090C.toInt()
            for (k in 0..5) { val hgt = 0.05f + 0.14f * (0.5f + 0.5f * sin(clock * 7f + k * 1.7f)); rect.set(x - 0.24f + k * 0.085f, y + 0.17f - hgt, x - 0.18f + k * 0.085f, y + 0.17f); c.drawRect(rect, p) }
            p.color = danger; c.drawCircle(x - 0.22f, y - 0.14f, 0.03f, p); tinyText(c, "LIVE", x - 0.05f, y - 0.11f, 0.08f, 0xFF07090C.toInt())
        } else {
            p.color = a(0xFF07090C.toInt(), 0xCC); c.drawCircle(x, y - 0.04f, 0.1f, p); rect.set(x - 0.17f, y + 0.07f, x + 0.17f, y + 0.2f); c.drawRoundRect(rect, 0.07f, 0.07f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.012f; p.color = a(Color.BLACK, 0x33); var yy = y - 0.2f; while (yy < y + 0.2f) { c.drawLine(x - 0.3f, yy, x + 0.3f, yy, p); yy += 0.045f }
        }
    }

    private fun drawCar(c: Canvas, x: Float, y: Float, w: Int) {
        val hw = w / 2f - 0.06f
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x66); rect.set(x - hw + 0.05f, y - 0.32f, x + hw + 0.07f, y + 0.4f); c.drawRoundRect(rect, 0.16f, 0.16f, p)
        p.color = 0xFF10141C.toInt(); for (wx in listOf(x - hw + 0.18f, x + hw - 0.42f)) { rect.set(wx, y - 0.4f, wx + 0.24f, y - 0.3f); c.drawRect(rect, p); rect.set(wx, y + 0.3f, wx + 0.24f, y + 0.4f); c.drawRect(rect, p) }
        p.color = 0xFF1D2433.toInt(); rect.set(x - hw, y - 0.34f, x + hw, y + 0.34f); c.drawRoundRect(rect, 0.16f, 0.16f, p)
        p.color = 0xFFE9EDF4.toInt(); rect.set(x - hw + 0.2f, y - 0.34f, x + hw - 0.2f, y - 0.2f); c.drawRect(rect, p); rect.set(x - hw + 0.2f, y + 0.2f, x + hw - 0.2f, y + 0.34f); c.drawRect(rect, p)
        p.color = 0xFF3A4A66.toInt(); rect.set(x - 0.34f, y - 0.2f, x + 0.34f, y + 0.2f); c.drawRoundRect(rect, 0.05f, 0.05f, p)
        val flash = (clock * 4f).toInt() % 2 == 0
        p.color = if (flash) 0xFFFF3B3B.toInt() else 0xFF3B7BFF.toInt(); rect.set(x - 0.2f, y - 0.1f, x, y + 0.1f); c.drawRect(rect, p)
        p.color = if (flash) 0xFF3B7BFF.toInt() else 0xFFFF3B3B.toInt(); rect.set(x, y - 0.1f, x + 0.2f, y + 0.1f); c.drawRect(rect, p)
        p.shader = RadialGradient(x, y, 1.1f, intArrayOf(a(if (flash) 0xFFFF3B3B.toInt() else 0xFF3B7BFF.toInt(), 0x44), 0), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, 1.1f, p); p.shader = null
    }

    private fun drawLasers(c: Canvas) {
        for (l in world.lasers) {
            val s = l.spec
            val on = l.on(world.t); val warn = l.warning(world.t)
            val red = 0xFFFF3B4E.toInt()
            if (on) {
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                p.strokeWidth = 0.26f; p.color = a(red, 0x40); c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
                p.strokeWidth = 0.1f; p.color = a(red, 0x90); c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
                p.strokeWidth = 0.035f; p.color = Color.WHITE; c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
            } else {
                val flick = warn && ((clock * 18f).toInt() % 2 == 0)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
                p.strokeWidth = if (flick) 0.06f else 0.03f
                p.color = a(red, if (flick) 0xCC else 0x38)
                p.pathEffect = DashPathEffect(floatArrayOf(0.1f, 0.2f), 0f); c.drawLine(s.x1, s.y1, s.x2, s.y2, p); p.pathEffect = null
            }
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            for ((ex, ey) in listOf(s.x1 to s.y1, s.x2 to s.y2)) {
                p.color = 0xFF1A1114.toInt(); c.drawCircle(ex, ey, 0.17f, p)
                p.color = if (on) red else a(red, 0x66); c.drawCircle(ex, ey, 0.09f, p)
            }
        }
    }

    private fun drawCone(c: Canvas, e: Eye, color: Int, meter: Float, fade: Float) {
        if (fade < 0.03f) return
        val n = 48
        path.reset(); path.moveTo(e.x, e.y)
        for (i in 0..n) {
            val ang = e.face - e.half + (2 * e.half) * i / n
            val len = world.rayLength(e, ang)
            path.lineTo(e.x + cos(ang) * len, e.y + sin(ang) * len)
        }
        path.close()
        val hot = (meter * 0x55).toInt()
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(e.x, e.y, e.range, intArrayOf(a(color, ((0x6A + hot) * fade).toInt()), a(color, ((0x30 + hot) * fade).toInt()), a(color, (0x06 * fade).toInt())), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.025f; p.color = a(color, ((0x44 + hot) * fade).toInt())
        c.drawPath(path, p)
    }

    private fun drawCamera(c: Canvas, cm: StealthWorld.Cam, pal: Palette) {
        val e = cm.eye()
        c.save(); c.rotate(e.face * 180f / PI.toFloat(), e.x, e.y)
        p.style = Paint.Style.FILL; p.color = 0xFF10151A.toInt(); rect.set(e.x - 0.12f, e.y - 0.14f, e.x + 0.22f, e.y + 0.14f); c.drawRoundRect(rect, 0.06f, 0.06f, p)
        p.color = a(Color.WHITE, 0x30); rect.set(e.x - 0.12f, e.y - 0.14f, e.x + 0.22f, e.y - 0.08f); c.drawRoundRect(rect, 0.03f, 0.03f, p)
        p.color = if (cm.meter > 0.3f) pal.cone else 0xFFFF4D4D.toInt(); c.drawCircle(e.x + 0.2f, e.y, 0.06f, p)
        c.restore()
    }

    private fun drawPerson(c: Canvas, x: Float, y: Float, ang: Float, coat: Int, hair: Int, accent: Int) {
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x66); c.drawOval(x - 0.36f, y - 0.3f + 0.06f, x + 0.36f, y + 0.3f + 0.1f, p)
        c.save(); c.rotate(ang * 180f / PI.toFloat(), x, y)
        p.color = coat; rect.set(x - 0.17f, y - 0.31f, x + 0.17f, y + 0.31f); c.drawRoundRect(rect, 0.15f, 0.15f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.035f; p.color = a(accent, 0xC0); c.drawRoundRect(rect, 0.15f, 0.15f, p)
        p.style = Paint.Style.FILL; p.color = hair; c.drawCircle(x + 0.01f, y, 0.175f, p)
        p.color = a(Color.WHITE, 0x22); c.drawCircle(x + 0.07f, y - 0.03f, 0.08f, p)
        c.restore()
    }

    private fun drawGuard(c: Canvas, g: StealthWorld.Guard, pal: Palette) {
        if (g.dead) {   // shot: lying where he fell
            c.save()
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawOval(g.x - 0.4f, g.y - 0.2f, g.x + 0.4f, g.y + 0.3f, p)
            p.color = 0xFF1A2030.toInt(); rect.set(g.x - 0.34f, g.y - 0.14f, g.x + 0.3f, g.y + 0.14f); c.save(); c.rotate(g.face * 180f / PI.toFloat(), g.x, g.y); c.drawRoundRect(rect, 0.12f, 0.12f, p); c.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.04f; p.color = a(danger, 0xCC)
            c.drawLine(g.x - 0.12f, g.y - 0.12f, g.x + 0.12f, g.y + 0.12f, p); c.drawLine(g.x + 0.12f, g.y - 0.12f, g.x - 0.12f, g.y + 0.12f, p)
            c.restore()
            return
        }
        if (g.chasing) {   // locked on: a pulsing red ring
            val ph = (clock * 3f) % 1f
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.05f; p.color = a(danger, ((1f - ph) * 0xCC).toInt()); c.drawCircle(g.x, g.y, 0.4f + ph * 0.5f, p)
        }
        drawPerson(c, g.x, g.y, g.face, 0xFF222B3B.toInt(), 0xFF0E1118.toInt(), pal.cone)
        c.save(); c.rotate(g.face * 180f / PI.toFloat(), g.x, g.y)
        p.style = Paint.Style.FILL; p.color = a(0xFFFFE9A8.toInt(), (255 * (1f - bc)).toInt()); c.drawCircle(g.x + 0.34f, g.y - 0.22f, 0.06f, p)
        c.restore()
        if (g.meter > 0.02f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.07f; p.strokeCap = Paint.Cap.ROUND
            p.color = a(if (g.meter > 0.6f) danger else 0xFFFFC857.toInt(), 0xDD)
            rect.set(g.x - 0.5f, g.y - 0.5f, g.x + 0.5f, g.y + 0.5f); c.drawArc(rect, -90f, 360f * g.meter, false, p)
            p.strokeCap = Paint.Cap.BUTT
        }
    }

    private fun drawContact(c: Canvas, pal: Palette) {
        val x = world.contactX; val y = world.contactY
        val green = 0xFF4ADE80.toInt()
        val pulse = (clock * 1.2f) % 1f
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.04f; p.color = a(green, ((1f - pulse) * 0xAA).toInt()); c.drawCircle(x, y, 0.4f + pulse * 0.6f, p)
        p.shader = RadialGradient(x, y, 0.9f, intArrayOf(a(green, 0x55), a(green, 0x00)), null, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL; c.drawCircle(x, y, 0.9f, p); p.shader = null
        val face = if (world.contactAiming) world.contactAim else if (world.state == StealthWorld.State.WON) atan2f(world.py - y, world.px - x) else sin(clock * 0.9f) * 0.5f + 1.2f
        drawPerson(c, x, y, face, 0xFF1E3D2D.toInt(), 0xFF0E1118.toInt(), green)
        if (world.contactAiming) {   // the gun
            c.save(); c.rotate(face * 180f / PI.toFloat(), x, y)
            p.style = Paint.Style.FILL; p.color = 0xFF0A0C10.toInt(); rect.set(x + 0.12f, y - 0.05f, x + 0.5f, y + 0.03f); c.drawRoundRect(rect, 0.02f, 0.02f, p)
            c.restore()
        }
        // shots: a bright line and a flash at the muzzle
        for (s in world.shots) {
            val f = (1f - s.age / 0.3f).coerceIn(0f, 1f)
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 0.06f; p.color = a(0xFFFFF2B0.toInt(), (f * 255).toInt()); c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
            p.strokeWidth = 0.02f; p.color = a(Color.WHITE, (f * 255).toInt()); c.drawLine(s.x1, s.y1, s.x2, s.y2, p)
            p.strokeCap = Paint.Cap.BUTT
            val fl = (1f - s.age / 0.14f).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(s.x1, s.y1, 0.7f, intArrayOf(a(0xFFFFE08A.toInt(), (fl * 0xCC).toInt()), 0), null, Shader.TileMode.CLAMP); c.drawCircle(s.x1, s.y1, 0.7f, p); p.shader = null
            p.color = a(danger, (fl * 0xAA).toInt()); c.drawCircle(s.x2, s.y2, 0.18f * fl + 0.05f, p)
        }
    }

    private fun atan2f(y: Float, x: Float) = Math.atan2(y.toDouble(), x.toDouble()).toFloat()

    private fun drawPlayer(c: Canvas, pal: Palette) {
        val w = world
        val x = w.px; val y = w.py
        if (w.noise > 0.05f) {
            val ph = (clock * 2.4f) % 1f
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.035f
            p.color = a(Color.WHITE, ((1f - ph) * 0x70).toInt()); c.drawCircle(x, y, w.noise * (0.35f + 0.65f * ph), p)
            p.color = a(Color.WHITE, 0x22); c.drawCircle(x, y, w.noise, p)
        }
        if (w.lightAt(x, y) > 0.9f) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(x, y, 0.7f, intArrayOf(a(pal.lamp, 0x00), a(pal.lamp, 0x66)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, 0.7f, p); p.shader = null
        }
        drawPerson(c, x, y, w.face, 0xFFE8EEF6.toInt(), 0xFF3B2F2F.toInt(), pal.accent)
        // the letter in my hand, with its red wax seal
        c.save(); c.rotate(w.face * 180f / PI.toFloat(), x, y)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; rect.set(x + 0.22f, y + 0.0f, x + 0.48f, y + 0.18f); c.drawRoundRect(rect, 0.02f, 0.02f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.02f; p.color = a(ink, 0x88)
        c.drawLine(x + 0.22f, y + 0.0f, x + 0.35f, y + 0.09f, p); c.drawLine(x + 0.48f, y + 0.0f, x + 0.35f, y + 0.09f, p)
        p.style = Paint.Style.FILL; p.color = danger; c.drawCircle(x + 0.35f, y + 0.09f, 0.035f, p)
        c.restore()
    }

    private fun drawFeet(c: Canvas) {
        p.style = Paint.Style.FILL
        for (f in world.feet) { p.color = a(Color.WHITE, ((1f - f.age / 2.2f) * 0x2A).toInt()); c.drawCircle(f.x, f.y, 0.07f, p) }
    }

    /** "?" and "!" above guards, in screen space so the text stays sharp. */
    private fun drawMarks(c: Canvas) {
        if (bc > 0.5f) return
        p.style = Paint.Style.FILL; p.typeface = monoBold; p.textAlign = Paint.Align.CENTER; p.textSize = T * 0.7f
        for (g in world.guards) {
            if (g.dead) continue
            val sx = offX + g.x * T; val sy = offY + (g.y - 0.62f) * T
            when {
                g.chasing || g.meter > 0.6f -> { p.color = danger; c.drawText("!", sx, sy, p) }
                g.meter > 0.12f -> { p.color = 0xFFFFC857.toInt(); c.drawText("?", sx, sy, p) }
                g.mode != StealthWorld.PATROL -> { p.color = a(Color.WHITE, 0xB0); c.drawText("?", sx, sy, p) }
            }
        }
    }

    private fun drawLight(c: Canvas, w: Float, h: Float, pal: Palette) {
        val calm = 1f - bc
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, (if (world.spec.pixel || pacScene != null) 0x60000000 else 0xA0000000).toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // the city's mood: the hotter the manhunt, the redder the edges; the last levels flash like sirens
        val heat = spec.heat
        if (heat >= 2 && calm > 0f) {
            val sw = sin(clock * (3f + heat))
            val siren = if (heat >= 4) (0.5f + 0.5f * sw) else 0.5f
            val col = if (heat >= 4 && sw > 0f) 0xFF2D5BFF.toInt() else 0xFFFF2D3D.toInt()
            p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.7f, intArrayOf(0x00000000, a(col, ((heat - 1) * 9 * (0.5f + siren) * calm).toInt())), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }
        val al = world.alert()
        if (al > 0.02f) {
            p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.7f, intArrayOf(0x00000000, a(0xFFFF2D3D.toInt(), (al * 0xB0).toInt())), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, pal: Palette) {
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT; p.typeface = monoBold
        p.textSize = 12f * d; p.color = pal.accent; p.letterSpacing = 0.1f
        c.drawText(spec.building.uppercase(), 14f * d, 20f * d, p)
        // how hunted you are
        p.typeface = mono; p.textSize = 10.5f * d; p.letterSpacing = 0.05f
        val heat = spec.heat
        p.color = a(Color.WHITE, 0x70); c.drawText("ALERT", 14f * d, 36f * d, p)
        val aw = p.measureText("ALERT") + 8f * d
        val heatCol = mix(0xFFFFC857.toInt(), danger, heat / 5f)
        for (k in 0 until 6) {
            p.style = Paint.Style.FILL; p.color = if (k <= heat) heatCol else a(Color.WHITE, 0x25)
            rect.set(14f * d + aw + k * 8f * d, 28f * d, 14f * d + aw + k * 8f * d + 5f * d, 37f * d); c.drawRect(rect, p)
        }
        p.style = Paint.Style.FILL; p.color = heatCol; c.drawText(heatNames[heat], 14f * d + aw + 6 * 8f * d + 4f * d, 36f * d, p)
        p.letterSpacing = 0f
        // the six letters
        val lw = 15f * d; val lx = w - 14f * d - 6 * lw
        for (i in 0 until 6) {
            val x = lx + i * lw
            val done = i < levelIdx || (i == levelIdx && world.state == StealthWorld.State.WON)
            val now = i == levelIdx && !done
            rect.set(x, 26f * d, x + 12f * d, 26f * d + 9f * d)
            if (done) { p.style = Paint.Style.FILL; p.color = pal.accent; c.drawRect(rect, p) }
            else { p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = if (now) a(Color.WHITE, (0x70 + 0x8F * (0.5f + 0.5f * sin(clock * 4f))).toInt()) else a(Color.WHITE, 0x38); c.drawRect(rect, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = if (done) ink else a(Color.WHITE, if (now) 0x90 else 0x30)
            c.drawLine(rect.left, rect.top, rect.centerX(), rect.centerY() + 1f * d, p); c.drawLine(rect.right, rect.top, rect.centerX(), rect.centerY() + 1f * d, p)
        }
        // keys in my pocket, and the sound switch
        var kx = w - 40f * d
        for (k in world.held) { drawKeyIcon(c, kx - 8f * d, 14f * d, 12f * d, keyColor(k)); kx -= 28f * d }
        drawSpeaker(c, w - 22f * d, 14f * d, 7f * d, soundOn)
        // locked on: say so, and what to do about it
        val n = world.chasers()
        if (n > 0 && world.state == StealthWorld.State.PLAY && clueAlpha < 0.05f) {
            p.textAlign = Paint.Align.CENTER; p.typeface = monoBold; p.textSize = 13f * d
            p.color = a(danger, (0x80 + 0x7F * (0.5f + 0.5f * sin(clock * 8f))).toInt())
            c.drawText(if (n == 1) "1 GUARD IS CHASING YOU" else "$n GUARDS ARE CHASING YOU", w / 2, h - 52f * d, p)
            p.typeface = mono; p.textSize = 11.5f * d; p.color = a(Color.WHITE, 0xB0)
            c.drawText(if (spec.armed) "Run to your contact. He will cover you." else "Break line of sight. Hide.", w / 2, h - 34f * d, p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSpeaker(c: Canvas, x: Float, y: Float, s: Float, on: Boolean) {
        p.style = Paint.Style.FILL; p.color = a(Color.WHITE, if (on) 0xC0 else 0x55)
        path.reset(); path.moveTo(x - s, y - s * 0.4f); path.lineTo(x - s * 0.4f, y - s * 0.4f); path.lineTo(x + s * 0.2f, y - s); path.lineTo(x + s * 0.2f, y + s); path.lineTo(x - s * 0.4f, y + s * 0.4f); path.lineTo(x - s, y + s * 0.4f); path.close(); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d
        if (on) { rect.set(x - s * 0.2f, y - s * 0.6f, x + s * 0.9f, y + s * 0.6f); c.drawArc(rect, -50f, 100f, false, p); rect.set(x - s * 0.5f, y - s * 1.0f, x + s * 1.4f, y + s * 1.0f); c.drawArc(rect, -50f, 100f, false, p) }
        else { c.drawLine(x + s * 0.5f, y - s * 0.6f, x + s * 1.3f, y + s * 0.6f, p); c.drawLine(x + s * 1.3f, y - s * 0.6f, x + s * 0.5f, y + s * 0.6f, p) }
    }

    /** Draws [text] wrapped to [maxW] pixels, starting at [y]; returns the next free y. */
    /** How many lines [wrap] would use for this text at the current text size. */
    private fun wrapLines(text: String, maxW: Float): Int {
        var n = 0; var cur = ""
        for (wd in text.split(' ')) { val t = if (cur.isEmpty()) wd else "$cur $wd"; if (p.measureText(t) > maxW && cur.isNotEmpty()) { n++; cur = wd } else cur = t }
        return n + (if (cur.isNotEmpty()) 1 else 0)
    }
    private fun wrap(c: Canvas, text: String, cx: Float, y: Float, maxW: Float, lineH: Float): Float {
        val words = text.split(' '); val lines = ArrayList<String>(); var cur = ""
        for (wd in words) { val t = if (cur.isEmpty()) wd else "$cur $wd"; if (p.measureText(t) > maxW && cur.isNotEmpty()) { lines += cur; cur = wd } else cur = t }
        if (cur.isNotEmpty()) lines += cur
        for ((i, ln) in lines.withIndex()) c.drawText(ln, cx, y + i * lineH, p)
        return y + lines.size * lineH
    }

    /** Wraps [t] into lines no wider than [maxW] pixels (with the "> " prompt on the first). */
    private fun clueLines(t: String, maxW: Float): List<String> {
        val out = ArrayList<String>(); var cur = "> "
        for (wd in t.split(' ')) { val s = if (cur == "> ") "> $wd" else "$cur $wd"; if (p.measureText(s) > maxW && cur != "> ") { out += cur; cur = wd } else cur = s }
        out += cur
        return out
    }

    private fun drawClue(c: Canvas, w: Float, h: Float, pal: Palette) {
        if (world.sayText != null) return
        val t = clueText ?: return
        panel(c, w, h, pal, t, clueAlpha, (clueT * 70f).toInt())
    }

    /** What the contact says: a speech bubble over his head (what you do, like showing your ID, is told in the box at the bottom). */
    private fun drawSay(c: Canvas, w: Float, h: Float, pal: Palette) {
        // level 1's last moment and the climb out of the maintenance room fade to black
        val blk = if (world.leaveT >= 0f) ((world.leaveT - 0.6f) / 2.2f).coerceIn(0f, 1f) else if (world.state == StealthWorld.State.WON && spec.pixel && !lastLevel) (wt / 1.3f).coerceIn(0f, 1f) else 0f
        if (blk > 0f) { p.style = Paint.Style.FILL; p.color = a(Color.BLACK, (blk * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }
        val t = world.sayText ?: return
        if (world.state != StealthWorld.State.PLAY) return
        if (pixelScene != null && !world.showingId && !world.givingEnvelope) { speechBubble(c, w, offX + world.contactX * T, offY + (world.contactY - 1.15f - scrollY) * T, t, (world.lineT * 40f).toInt()); return }
        if (world.showingId) { val look = world.lineT >= 1.0f; panel(c, w, h, pal, if (look) "THEY LOOK IT OVER." else "YOU SHOW YOUR CLERK ID.", 1f, ((if (look) world.lineT - 1.0f else world.lineT) * 45f).toInt()); return }
        if (world.givingEnvelope) { panel(c, w, h, pal, "THEY HAND YOU A SEALED ENVELOPE. INFORMATION TO BROADCAST.", 1f, (world.lineT * 45f).toInt()); return }
        panel(c, w, h, pal, (world.spec.lead?.speaker ?: world.spec.worker?.speaker ?: world.spec.scene?.speaker?.ifEmpty { null } ?: spec.contact.uppercase()) + ": “" + t + "”", 1f, (world.lineT * 45f).toInt())
    }

    /** A white speech bubble with its tail on (sx, sy), the speaker's head; the words type in. */
    private fun speechBubble(c: Canvas, w: Float, sx: Float, sy: Float, text: String, typed: Int) {
        p.typeface = monoBold; p.textSize = 12.5f * d; p.letterSpacing = 0f; p.textAlign = Paint.Align.LEFT
        val maxW = min(w * 0.72f, 300f * d)
        val lines = ArrayList<String>(); var cur = ""
        for (wd in text.split(' ')) { val s = if (cur.isEmpty()) wd else "$cur $wd"; if (p.measureText(s) > maxW && cur.isNotEmpty()) { lines += cur; cur = wd } else cur = s }
        if (cur.isNotEmpty()) lines += cur
        val lh = 16f * d; val pad = 9f * d
        val bw = lines.maxOf { p.measureText(it) } + pad * 2; val bh = lines.size * lh + pad * 2 - 4f * d
        val bx = (sx - bw / 2).coerceIn(10f * d, w - 10f * d - bw); val by = sy - 12f * d - bh
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x70); rect.set(bx + 2f * d, by + 3f * d, bx + bw + 2f * d, by + bh + 3f * d); c.drawRoundRect(rect, 7f * d, 7f * d, p)
        p.color = 0xFFF6F2E8.toInt(); rect.set(bx, by, bx + bw, by + bh); c.drawRoundRect(rect, 7f * d, 7f * d, p)
        val tail = android.graphics.Path(); val tx = sx.coerceIn(bx + 12f * d, bx + bw - 12f * d); tail.moveTo(tx - 6f * d, by + bh - 1f); tail.lineTo(tx + 6f * d, by + bh - 1f); tail.lineTo(sx, sy - 2f * d); tail.close(); c.drawPath(tail, p)
        p.color = 0xFF14161C.toInt(); var left = typed; var y = by + pad + 11f * d
        for (ln in lines) { if (left <= 0) break; c.drawText(ln.take(left), bx + pad, y, p); left -= ln.length + 1; y += lh }
    }

    private fun panel(c: Canvas, w: Float, h: Float, pal: Palette, t: String, f: Float, typed: Int) {
        if (f <= 0.02f) return
        p.style = Paint.Style.FILL; p.typeface = mono; p.textSize = 13f * d; p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
        val lines = clueLines(t, w - 60f * d)
        val ph = lines.size * 18f * d + 18f * d
        val y0 = min(h - 24f * d, joyCy - joyR - 14f * d) - ph   // above the joystick
        p.color = a(0xFF05070A.toInt(), (f * 0xD0).toInt()); rect.set(18f * d, y0, w - 18f * d, y0 + ph); c.drawRoundRect(rect, 4f * d, 4f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(pal.accent, (f * 0x90).toInt()); c.drawRoundRect(rect, 4f * d, 4f * d, p)
        p.style = Paint.Style.FILL; p.color = a(Color.WHITE, (f * 0xF0).toInt())
        // typed out like a radio or a terminal: a few letters at a time
        var left = typed
        var yy = y0 + 22f * d
        for (ln in lines) {
            if (left <= 0) break
            c.drawText(ln.take(left), 30f * d, yy, p)
            left -= ln.length; yy += 18f * d
        }
    }

    /** The joystick at the bottom left. Push it the way you want to go; a light push walks quietly, a hard push runs. */
    private fun drawJoystick(c: Canvas) {
        val playing = if (pac != null) pac?.phase == PacWorld.Phase.PLAY else world.state == StealthWorld.State.PLAY
        if (!playing && !joyActive) return
        val cx = joyCx; val cy = joyCy; val r = joyR
        val run = mag > 0.5f && pac == null
        p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(cx, cy, r + 4f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(Color.WHITE, 0x44); c.drawCircle(cx, cy, r, p)
        p.strokeWidth = 1f * d; p.color = a(Color.WHITE, 0x22); c.drawCircle(cx, cy, r * 0.5f, p)
        // little arrows to show the four ways
        p.style = Paint.Style.FILL; p.color = a(Color.WHITE, 0x44)
        for (k in 0..3) {
            val ax = cx + kotlin.math.cos(k * Math.PI / 2).toFloat() * (r - 9f * d); val ay = cy + kotlin.math.sin(k * Math.PI / 2).toFloat() * (r - 9f * d)
            c.drawCircle(ax, ay, 2f * d, p)
        }
        p.color = a(if (run) 0xFFFF8A4D.toInt() else Color.WHITE, if (joyActive) 0x99 else 0x66)
        c.drawCircle(cx + knobX, cy + knobY, 22f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = a(Color.WHITE, 0xB0); c.drawCircle(cx + knobX, cy + knobY, 22f * d, p)
    }

    /** The screen goes to black between the parts of a level. */
    private fun drawFade(c: Canvas, w: Float, h: Float) {
        if (fade <= 0.01f) return
        p.style = Paint.Style.FILL; p.color = a(0xFF000000.toInt(), (fade * 255).toInt()); c.drawRect(0f, 0f, w, h, p)
    }

    /** The sewer maze: drawn whole, scaled by a whole number, with the fragment count and "READY!" around it. */
    private fun drawPacStage(c: Canvas, w: Float, h: Float, ps: PacScene, pw: PacWorld) {
        val top = barH; val bottom = 44f * d + botInset
        val sc = max(1, min(w / ps.W, (h - top - bottom) / ps.H).toInt())
        T = 16f * sc
        offX = ((w - ps.W * sc) / 2f).toInt().toFloat(); offY = (top + (h - top - bottom - ps.H * sc) / 2f).toInt().toFloat()
        scrollY = 0f
        c.drawColor(0xFF03080C.toInt())
        rect.set(offX, offY, offX + ps.W * sc, offY + ps.H * sc)
        c.drawBitmap(ps.render(clock), null, rect, nearest)
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = monoBold
        if (pw.phase == PacWorld.Phase.READY || (pw.phase == PacWorld.Phase.INTRO && pw.lidClose >= 1f)) {
            p.textSize = 14f * d; p.letterSpacing = 0.15f; p.color = 0xFFFFE04D.toInt()
            c.drawText("READY!", offX + pw.w * T / 2f, offY + (pw.hatchY + 3.1f) * T, p)
        }
        // how many fragments are left, and the door
        p.letterSpacing = 0.05f; p.textSize = 12.5f * d
        val have = pw.total - pw.left
        p.color = if (pw.doorOpen) 0xFF3BFF9A.toInt() else 0xFFFFD84D.toInt()
        c.drawText(if (pw.doorOpen) "THE DOOR IS OPEN. GO!" else "KEY FRAGMENTS  $have / ${pw.total}", w / 2f, offY + ps.H * sc + 22f * d, p)
        p.typeface = mono; p.letterSpacing = 0f; p.textSize = 11.5f * d; p.color = a(Color.WHITE, 0x99)
        if (pw.phase != PacWorld.Phase.PLAY || pw.eaten < 6) wrap(c, spec.pacTip, w / 2f, offY + ps.H * sc + 42f * d, w - 40f * d, 15f * d)
        p.letterSpacing = 0f
    }

    private fun drawBigLetter(c: Canvas, cx: Float, cy: Float, n: Int, alpha: Float) {
        val wd = 64f * d; val ht = 42f * d
        p.style = Paint.Style.FILL; p.color = a(paper, (alpha * 255).toInt()); rect.set(cx - wd / 2, cy - ht / 2, cx + wd / 2, cy + ht / 2); c.drawRoundRect(rect, 3f * d, 3f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = a(ink, (alpha * 0x88).toInt())
        c.drawLine(cx - wd / 2, cy - ht / 2, cx, cy + 2f * d, p); c.drawLine(cx + wd / 2, cy - ht / 2, cx, cy + 2f * d, p)
        p.style = Paint.Style.FILL; p.color = a(danger, (alpha * 255).toInt()); c.drawCircle(cx, cy + 2f * d, 9f * d, p)
        p.typeface = monoBold; p.textAlign = Paint.Align.CENTER; p.textSize = 10f * d; p.letterSpacing = 0f; p.color = a(Color.WHITE, (alpha * 255).toInt()); c.drawText(roman[n], cx, cy + 5.5f * d, p)
    }

    /** The level's title card (place, what to do, the situation), then the how-to-play line. */
    private fun drawTitleCard(c: Canvas, w: Float, h: Float, pal: Palette) {
        p.textAlign = Paint.Align.CENTER
        if (sinceLoad < cardS) {
            val f = if (sinceLoad < 0.3f) sinceLoad / 0.3f else if (sinceLoad > cardS - 0.8f) (cardS - sinceLoad) / 0.8f else 1f
            // measure the words first so the dark band only reaches a little above and below them, then centre it on the screen
            p.typeface = mono; p.letterSpacing = 0f; p.textSize = 13f * d; val n1 = wrapLines(spec.objective, w - 56f * d)
            p.textSize = 12f * d; val n2 = wrapLines(spec.situation, w - 56f * d)
            val lastBase = 129f * d + n1 * 18f * d + 10f * d + (n2 - 1) * 17f * d          // from the top of the letter icon
            val pad = 14f * d; val bandH = lastBase + 5f * d + pad * 2
            val y0 = h / 2f - bandH / 2f; val top = y0 + pad
            p.style = Paint.Style.FILL; p.color = a(0xFF04060A.toInt(), (f * 0xE0).toInt()); c.drawRect(0f, y0, w, y0 + bandH, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(pal.accent, (f * 0x80).toInt()); c.drawLine(24f * d, y0 + 1f, w - 24f * d, y0 + 1f, p); c.drawLine(24f * d, y0 + bandH - 1f, w - 24f * d, y0 + bandH - 1f, p)
            drawBigLetter(c, w / 2, top + 21f * d, levelIdx, f)
            p.style = Paint.Style.FILL; p.typeface = monoBold; p.letterSpacing = 0.2f; p.textSize = 11f * d; p.color = a(pal.accent, (f * 255).toInt()); c.drawText("PART ${roman[levelIdx]} OF VI", w / 2, top + 69f * d, p)
            p.letterSpacing = 0.04f; p.textSize = 25f * d; p.color = a(Color.WHITE, (f * 255).toInt()); c.drawText(spec.building.uppercase(), w / 2, top + 101f * d, p)
            p.typeface = mono; p.letterSpacing = 0f; p.textSize = 13f * d; p.color = a(Color.WHITE, (f * 0xE0).toInt())
            val yy = wrap(c, spec.objective, w / 2, top + 129f * d, w - 56f * d, 18f * d)
            p.color = a(Color.WHITE, (f * 0x80).toInt()); p.textSize = 12f * d
            wrap(c, spec.situation, w / 2, yy + 10f * d, w - 56f * d, 17f * d)
            p.textSize = 10f * d; p.color = a(Color.WHITE, (f * 0x60).toInt()); c.drawText("tap to skip", w / 2, y0 + bandH + 18f * d, p)
        } else if (sinceLoad < cardS + tipS) {
            // the how-to-play line, once, after the title card
            val t1 = cardS + tipS
            val f = if (sinceLoad < cardS + 0.6f) (sinceLoad - cardS) / 0.6f else if (sinceLoad > t1 - 0.8f) (t1 - sinceLoad) / 0.8f else 1f
            if (clueAlpha < 0.05f || mgIdx >= 0) {
                p.style = Paint.Style.FILL; p.typeface = mono; p.textSize = 12.5f * d
                val n = wrapLines(spec.tip, w - 56f * d); val ty = h * 0.30f
                p.color = a(Color.BLACK, (f * 0xB0).toInt()); c.drawRect(0f, ty - 22f * d, w, ty + n * 17f * d, p)
                p.color = a(Color.WHITE, (f * 0xE0).toInt())
                wrap(c, spec.tip, w / 2, ty, w - 56f * d, 17f * d)
            }
        }
    }

    private fun drawOverlays(c: Canvas, w: Float, h: Float, pal: Palette) {
        p.textAlign = Paint.Align.CENTER
        if (attempt == 1 && world.state == StealthWorld.State.PLAY) drawTitleCard(c, w, h, pal)
        when (world.state) {
            StealthWorld.State.CAUGHT -> {
                val f = (world.stateT / 0.25f).coerceIn(0f, 1f)
                p.style = Paint.Style.FILL; p.color = a(0xFF16060A.toInt(), (f * 0xC8).toInt()); c.drawRect(0f, 0f, w, h, p)
                if (world.stateT < 0.18f) { p.color = a(0xFFFF2D3D.toInt(), ((0.18f - world.stateT) / 0.18f * 0x90).toInt()); c.drawRect(0f, 0f, w, h, p) }
                p.typeface = monoBold; p.letterSpacing = 0.2f; p.textSize = 30f * d; p.color = a(danger, (f * 255).toInt()); c.drawText("SPOTTED", w / 2, h / 2 - 8f * d, p)
                p.typeface = mono; p.letterSpacing = 0f; p.textSize = 14f * d; p.color = a(Color.WHITE, (f * 0xC0).toInt())
                c.drawText(world.spotter, w / 2, h / 2 + 20f * d, p)
                if (world.stateT > 0.5f) { p.color = a(Color.WHITE, 0x90); c.drawText("Tap to try again", w / 2, h / 2 + 52f * d, p) }
            }
            StealthWorld.State.WON -> if (lastLevel) drawEnding(c, w, h, pal) else {
                val f = (wt / 0.5f).coerceIn(0f, 1f)
                p.style = Paint.Style.FILL; p.color = a(0xFF04060A.toInt(), (f * 0xE0).toInt()); c.drawRect(0f, 0f, w, h, p)
                drawBigLetter(c, w / 2, h * 0.28f, levelIdx, f)
                p.style = Paint.Style.FILL; p.typeface = monoBold; p.letterSpacing = 0.2f; p.textSize = 14f * d; p.color = a(pal.accent, (f * 255).toInt())
                c.drawText("LETTER ${roman[levelIdx]} DELIVERED", w / 2, h * 0.28f + 54f * d, p)
                p.typeface = mono; p.letterSpacing = 0f; p.textSize = 12f * d; p.color = a(Color.WHITE, (f * 0x90).toInt()); c.drawText(spec.contact.uppercase(), w / 2, h * 0.28f + 80f * d, p)
                p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC); p.textSize = 18f * d; p.color = a(Color.WHITE, (f * 255).toInt())
                val yy = wrap(c, "“" + spec.line + "”", w / 2, h * 0.28f + 118f * d, w - 56f * d, 24f * d)
                val f2 = ((wt - 0.9f) / 0.6f).coerceIn(0f, 1f)
                p.typeface = mono; p.textSize = 11f * d; p.letterSpacing = 0.12f; p.color = a(pal.accent, (f2 * 0xC0).toInt()); c.drawText("THE LETTER REVEALS", w / 2, yy + 24f * d, p)
                p.letterSpacing = 0f; p.textSize = 14f * d; p.color = a(Color.WHITE, (f2 * 0xF0).toInt())
                wrap(c, spec.reveal, w / 2, yy + 50f * d, w - 56f * d, 20f * d)
                if (wt > 2f) { p.textSize = 12f * d; p.color = a(Color.WHITE, 0x90); c.drawText("Tap to continue", w / 2, h - 50f * d, p) }
            }
            else -> {}
        }
    }

    /** The last level has no "next": the broadcast goes out. The city changes (see [bc]); then the screen goes dark. */
    private fun drawEnding(c: Canvas, w: Float, h: Float, pal: Palette) {
        val t = wt
        p.style = Paint.Style.FILL; p.typeface = mono; p.textAlign = Paint.Align.LEFT; p.textSize = 13f * d; p.letterSpacing = 0f
        val lines = listOf(0.4f to "> \"${spec.line}\"", 2.2f to "> The letter goes out.", 3.4f to "> Every screen in the city changes.", 4.6f to "> Wanted posters: replaced.", 5.8f to "> Police radios: silence.")
        val fadeOut = 1f - ((t - 7.4f) / 1.2f).coerceIn(0f, 1f)
        var y = h - 176f * d
        p.color = a(0xFF04060A.toInt(), (min(1f, t / 0.8f) * 0xB0 * fadeOut).toInt()); rect.set(18f * d, y - 22f * d, w - 18f * d, y + 5 * 20f * d - 6f * d); c.drawRoundRect(rect, 4f * d, 4f * d, p)
        for ((at, s) in lines) {
            if (t < at) continue
            val f = min(1f, (t - at) / 0.5f) * fadeOut
            p.color = a(if (at < 1f) pal.accent else Color.WHITE, (f * 0xF0).toInt())
            val typed = s.substring(0, min(s.length, ((t - at) * 38f).toInt()))
            c.drawText(typed, 30f * d, y, p); y += 20f * d
        }
        // dark, then the title
        val black = ((t - 7.4f) / 1.2f).coerceIn(0f, 1f)
        if (black > 0f) { p.color = a(0xFF000000.toInt(), (black * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }
        p.textAlign = Paint.Align.CENTER
        if (t > 9.2f) {
            val f = ((t - 9.2f) / 1.2f).coerceIn(0f, 1f)
            p.typeface = monoBold; p.letterSpacing = 0.3f; p.textSize = 26f * d; p.color = a(Color.WHITE, (f * 255).toInt()); c.drawText("DEAD LETTER", w / 2, h / 2, p)
            val f2 = ((t - 10.4f) / 1.2f).coerceIn(0f, 1f)
            p.typeface = mono; p.letterSpacing = 0.1f; p.textSize = 12f * d; p.color = a(Color.WHITE, (f2 * 0x99).toInt()); c.drawText("six letters. one truth.", w / 2, h / 2 + 30f * d, p)
        }
        if (t > 12f) { p.letterSpacing = 0f; p.textSize = 11f * d; p.color = a(Color.WHITE, 0x66); c.drawText("tap to close", w / 2, h - 40f * d, p) }
        p.letterSpacing = 0f
    }
}
