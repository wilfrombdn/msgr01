package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// DEAD LETTER, the hidden game (tap the version text at the bottom of Settings 7 times).
// You are a wanted city clerk carrying six sealed letters through a dark, hunted city. Each one goes to someone who holds
// a piece of the truth. Touch and drag anywhere to walk: a gentle drag sneaks quietly, a full drag runs (and is loud).
// Stay out of the light. The rules live in StealthWorld.kt, the story and buildings in StealthLevels.kt, the music in
// DeadLetterMusic.kt; this file draws everything and reads your thumb.

internal fun MainActivity.showDeadDrop() {
    screen = Screen.DEAD_DROP
    val prefs = getSharedPreferences("dead_drop", Context.MODE_PRIVATE)
    val game = DeadDropView(this, prefs.getInt("level", 0), prefs.getBoolean("sound", false),
        saveLevel = { prefs.edit().putInt("level", it).apply() }, saveSound = { prefs.edit().putBoolean("sound", it).apply() }, onExit = { showSettings() })
    // on the right: restart (tap = this level, hold = back to level 1) next to exit
    val skip = icon(R.drawable.ic_skip) { game.skipLevel(); toast("Skipped") }
    val again = icon(R.drawable.ic_restart) { game.restart() }.apply { setOnLongClickListener { game.restartAll(); toast("Back to level 1"); true } }
    setContentView(page(topBar("Dead Letter", "Six letters. One truth.", null, skip, again, icon(R.drawable.ic_close) { showSettings() }), game))
}

internal class DeadDropView(
    ctx: Context, startLevel: Int, private var soundOn: Boolean,
    private val saveLevel: (Int) -> Unit, private val saveSound: (Boolean) -> Unit, private val onExit: () -> Unit,
) : View(ctx) {
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
    private var mgPending = -9                    // where the fade goes: a stage number, -2 next level, -3 redo this part
    private var aimId = -1; private var joyId = -1; private var thrustId = -1
    private var aimDx = 0f; private var aimDy = 0f; private var aimMag = 0f; private var aimKX = 0f; private var aimKY = 0f
    private var carryFade = false
    private var deadT = 0f

    private fun stagesOf(s: LevelSpec): List<String>? = when (s.building) {
        "The Industrial District" -> listOf("belt", "fly", "cine0")
        "The Administration" -> listOf("cine1", "shoot")
        else -> null
    }
    private val mgName get() = if (mgIdx < 0) null else stagesOf(spec)?.getOrNull(mgIdx)
    private val aimCx get() = width - joyCx

    private fun startMg(i: Int) {
        mgIdx = i; belt = null; beltScene = null; fly = null; flyScene = null; shoot = null; shootScene = null; cine = null
        joyId = -1; aimId = -1; thrustId = -1; joyActive = false; mag = 0f; aimMag = 0f; knobX = 0f; knobY = 0f; aimKX = 0f; aimKY = 0f; deadT = 0f; mgPending = -9
        when (mgName) {
            "belt" -> { belt = BeltWorld(BeltLevel.warehouse); beltScene = BeltScene(belt!!); music.setHeat(3) }
            "fly" -> { fly = FlyWorld(FlySpec()); flyScene = FlyScene(fly!!); music.setHeat(4) }
            "cine0" -> { cine = CineScene(0); music.setHeat(1) }
            "cine1" -> { cine = CineScene(1); music.setHeat(2) }
            "shoot" -> { shoot = ShootWorld(ShootLevel.townHall()); shootScene = ShootScene(shoot!!); music.setHeat(5) }
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
        if (fadeDir > 0) { fade += dt / 0.7f; if (fade >= 1f) { mgApply(); fade = 1f; fadeDir = -1 }; return }
        if (fadeDir < 0) { fade -= dt / 0.7f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }
        belt?.let { b ->
            b.update(dt, ix, iy, mag)
            if (b.fell) mgGo(-3) else if (b.phase == BeltWorld.Phase.DONE && b.phaseT > 0.8f) mgGo(mgIdx + 1)
        }
        fly?.let { f -> f.thrust = thrustId >= 0; f.update(dt); if (f.finished) mgGo(mgIdx + 1) }
        cine?.let { cn -> cn.t += dt; if (cn.t >= cn.fadeStart) mgGo(mgIdx + 1) }
        shoot?.let { sw ->
            sw.update(dt, ix, iy, mag, aimDx, aimDy, aimMag)
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
                if (act == MotionEvent.ACTION_DOWN && x > width - 52f * d && y < 46f * d) { soundTap = true; return true }
                val sw = shoot
                if (sw != null && sw.phase == ShootWorld.Phase.DEAD) { if (deadT > 0.5f) startMg(mgIdx); return true }
                val m = mgName
                if ((m == "belt" || m == "shoot") && hypot(x - joyCx, y - joyCy) < joyR * 1.7f) { joyId = id; joyActive = true; readJoystick(x, y) }
                else if (m == "shoot" && hypot(x - aimCx, y - joyCy) < joyR * 1.7f) { aimId = id; readAim(x, y) }
                else if (m == "fly") thrustId = id
            }
            MotionEvent.ACTION_MOVE -> {
                if (soundTap) return true
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    if (pid == joyId) readJoystick(e.getX(i), e.getY(i)) else if (pid == aimId) readAim(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (soundTap && act != MotionEvent.ACTION_POINTER_UP) {
                    soundTap = false
                    if (act == MotionEvent.ACTION_UP) { soundOn = !soundOn; saveSound(soundOn); if (soundOn) music.start() else music.stop() }
                    return true
                }
                if (act == MotionEvent.ACTION_CANCEL) { joyId = -1; aimId = -1; thrustId = -1 }
                if (id == joyId) { joyId = -1; joyActive = false; mag = 0f; knobX = 0f; knobY = 0f }
                if (id == aimId) { aimId = -1; aimMag = 0f; aimKX = 0f; aimKY = 0f }
                if (id == thrustId) thrustId = -1
                if (act == MotionEvent.ACTION_CANCEL || act == MotionEvent.ACTION_UP) { joyActive = false; if (joyId < 0) mag = 0f }
            }
        }
        return true
    }

    private fun mgText(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true) {
        p.style = Paint.Style.FILL; p.typeface = if (bold) monoBold else mono; p.textAlign = Paint.Align.CENTER; p.textSize = size * d; p.color = color; p.letterSpacing = 0.05f
        c.drawText(text, x, y, p); p.letterSpacing = 0f
    }

    private fun drawMg(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF03050A.toInt())
        val top = 44f * d; val bottom = 14f * d
        val bmp = beltScene?.render(clock) ?: flyScene?.render(clock) ?: cine?.render(clock) ?: shootScene?.render(clock)
        if (bmp != null) {
            val sc = max(1, min(w / bmp.width, (h - top - bottom) / bmp.height).toInt())
            val ox = ((w - bmp.width * sc) / 2f).toInt().toFloat(); val oy = (top + (h - top - bottom - bmp.height * sc) / 2f).toInt().toFloat()
            rect.set(ox, oy, ox + bmp.width * sc, oy + bmp.height * sc)
            c.drawBitmap(bmp, null, rect, nearest)
            val bt = oy + bmp.height * sc
            belt?.let { b ->
                mgText(c, "LIVES  " + "♥".repeat(b.lives.coerceAtLeast(0)), w / 2f, oy - 10f * d, 13f, 0xFFFF6A7A.toInt())
                if (b.phase == BeltWorld.Phase.FALL) mgText(c, "YOU FELL", w / 2f, oy + bmp.height * sc / 2f, 22f, danger)
                if (b.phase == BeltWorld.Phase.PICKUP) mgText(c, "GRAB THE JETPACK", w / 2f, oy - 10f * d - 18f * d, 12f, 0xFFFFD84D.toInt())
                if (b.t < 6f) mgText(c, "Dodge boxes, tools, cables, machines.", w / 2f, bt + 14f * d, 10.5f, a(Color.WHITE, 0x99), false)
            }
            fly?.let { f ->
                mgText(c, "COINS  ${f.coins} / ${f.spec.coinGoal}", w / 2f, oy - 10f * d, 14f, 0xFFFFD84D.toInt())
                if (f.t < 6f) mgText(c, "HOLD ANYWHERE TO FLY UP. LET GO TO FALL. AVOID THE ZAPPERS.", w / 2f, bt + 14f * d, 10f, a(Color.WHITE, 0xAA), false)
            }
            cine?.caption()?.let { mgText(c, it, w / 2f, bt - 16f * d, 13f, a(Color.WHITE, 0xEE)) }
            shoot?.let { sw ->
                if (sw.clearedT > 0f) mgText(c, "ROOM CLEAR. THE DOOR IS OPEN.", w / 2f, oy - 10f * d, 13f, 0xFF3BFF9A.toInt())
                if (sw.phase == ShootWorld.Phase.PLAY && sw.phaseT < 6f) mgText(c, "Left stick walks. Right stick aims and shoots. One hit kills.", w / 2f, bt + 14f * d, 10f, a(Color.WHITE, 0xAA), false)
                if (sw.phase == ShootWorld.Phase.FINALE && sw.fade > 0f) { p.style = Paint.Style.FILL; p.color = a(Color.BLACK, (sw.fade * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }
                if (sw.phase == ShootWorld.Phase.DEAD) {
                    p.style = Paint.Style.FILL; p.color = a(0xFFB01020.toInt(), (min(1f, deadT * 2f) * 0x88).toInt()); c.drawRect(0f, 0f, w, h, p)
                    if (deadT > 0.3f) { mgText(c, "SHOT.", w / 2f, h / 2f - 10f * d, 26f, Color.WHITE); mgText(c, "Tap anywhere to try again.", w / 2f, h / 2f + 16f * d, 12f, a(Color.WHITE, 0xCC), false) }
                }
            }
        }
        if (mgName == "belt" || mgName == "shoot") drawJoystick(c)
        if (mgName == "shoot" && shoot?.phase == ShootWorld.Phase.PLAY) {
            val cx = aimCx; val cy = joyCy; val r = joyR
            p.style = Paint.Style.FILL; p.color = a(Color.BLACK, 0x55); c.drawCircle(cx, cy, r + 4f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a(0xFFFF6A6A.toInt(), 0x66); c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL; p.color = a(0xFFFF6A6A.toInt(), if (aimId >= 0) 0x99 else 0x55); c.drawCircle(cx + aimKX, cy + aimKY, 22f * d, p)
        }
        drawFade(c, w, h)
    }


    internal fun debugMg(i: Int) { startMg(i) }
    internal fun debugMgAdvance(sec: Float) { var t = sec; while (t > 0f) { val s = min(t, 0.025f); clock += s; mgUpdate(s); t -= s } }
    internal fun debugMgIdx() = mgIdx
    internal fun debugLevelIdx() = levelIdx

    // screen -> tiles
    private var T = 1f; private var offX = 0f; private var offY = 0f

    private val spec get() = levels[levelIdx]
    private val lastLevel get() = levelIdx == levels.size - 1
    /** 0..1: how far the final broadcast has changed the city (posters, screens, radios). */
    private val wt get() = max(0f, world.stateT - world.wonDelay)   // time since the win screen could start (the contact may be busy first)
    private val bc get() = if (lastLevel && world.state == StealthWorld.State.WON) ((wt - 1.2f) / 2.5f).coerceIn(0f, 1f) else 0f

    init { load(false) }

    /** Start the level you are on again from the beginning. */
    internal fun restart() { load(false) }

    /** Is a Spotted / Delivered style screen showing (so that any tap should dismiss it)? */
    private fun overlayUp(): Boolean = world.state != StealthWorld.State.PLAY || pac?.phase == PacWorld.Phase.CAUGHT || pac?.phase == PacWorld.Phase.WON

    /** The skip arrow: on to the next level. */
    internal fun skipLevel() { if (levelIdx < levels.size - 1) { levelIdx++; saveLevel(levelIdx); load(false) } }

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
        stage = 0; pac = null; pacScene = null; fade = 0f; fadeDir = 0; pendingStage = -1; pacWant = -1; joyActive = false
        sinceLoad = if (retry) 99f else 0f
        if (!retry) { attempt = 1; read.clear() }
        mag = 0f; clueText = null; clueAlpha = 0f
        music.setHeat(spec.heat); music.setCalm(false)
        mgIdx = -1; if (stagesOf(spec) != null) startMg(0)
        if (carryFade) { carryFade = false; fade = 1f; fadeDir = -1 }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; last = 0L; if (soundOn) music.start(); postOnAnimation(tick) }
    override fun onDetachedFromWindow() { running = false; music.stop(); super.onDetachedFromWindow() }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (last != 0L) {
                val raw = ((now - last) / 1e9f).coerceAtMost(0.05f)
                var dt = raw
                clock += dt; sinceLoad += dt
                if (mgIdx >= 0) mgUpdate(raw) else stageTick(raw)
                var left = dt
                while (mgIdx < 0 && left > 0f) {
                    val s = min(left, 0.025f)
                    val pw = pac
                    if (pw != null) { if (fadeDir <= 0) pw.update(s, pacWant) } else world.update(s, ix, iy, if (sinceLoad < 0.4f) 0f else mag)
                    left -= s
                }
                updateClue(raw)
                if (lastLevel && world.state == StealthWorld.State.WON) music.setCalm(true)
            }
            last = now
            invalidate()
            postOnAnimation(this)
        }
    }

    private val joyR get() = 56f * d
    private val joyCx get() = 24f * d + joyR + 6f * d
    private val joyCy get() = height - 30f * d - joyR

    /** Read the joystick: the courier walks the way you push, faster the harder you push (a light push sneaks quietly). */
    private fun readJoystick(x: Float, y: Float) {
        val dx = x - joyCx; val dy = y - joyCy
        val len = hypot(dx, dy)
        val shown = min(len, joyR)
        if (len > 0.001f) { knobX = dx / len * shown; knobY = dy / len * shown } else { knobX = 0f; knobY = 0f }
        val dead = 7f * d
        if (len < dead) { mag = 0f; return }
        ix = dx / len; iy = dy / len
        mag = ((shown - dead) / (joyR - dead)).coerceIn(0f, 1f)
        if (mag > 0.35f) pacWant = if (kotlin.math.abs(ix) > kotlin.math.abs(iy)) (if (ix > 0) PacWorld.RIGHT else PacWorld.LEFT) else (if (iy > 0) PacWorld.DOWN else PacWorld.UP)
    }

    /** Moves level 2 from one part to the next, with a fade to black between. */
    private fun stageTick(dt: Float) {
        if (fadeDir > 0) { fade += dt / 0.8f; if (fade >= 1f) { fade = 1f; switchStage(); fadeDir = -1 } }
        else if (fadeDir < 0) { fade -= dt / 0.8f; if (fade <= 0f) { fade = 0f; fadeDir = 0 } }
        else if (stage == 0 && world.enteredManhole && spec.pac != null) { pendingStage = 1; fadeDir = 1 }
        else if (stage == 1) {
            val pw = pac
            if (pw != null) {
                if (pw.phase == PacWorld.Phase.WON && pw.phaseT > 0.9f && spec.room != null) { pendingStage = 2; fadeDir = 1 }
                if (pw.phase == PacWorld.Phase.CAUGHT && world.state != StealthWorld.State.CAUGHT) { world.state = StealthWorld.State.CAUGHT; world.spotter = "A guard caught you."; world.stateT = 0f }
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
        pac = PacWorld(spec.pac!!); pacScene = PacScene(pac!!); pacWant = -1
        world.state = StealthWorld.State.PLAY; world.stateT = 0f
    }

    private fun updateClue(dt: Float) {
        var best: Prop? = null; var bd = 99f; var bi = -1
        if (world.state == StealthWorld.State.PLAY && pac == null) for ((i, pr) in world.spec.props.withIndex()) {
            if (pr.text == null) continue
            val reach = if (pr.kind == "window") (if (world.spec.walk) 2.8f else 2.1f) else 1.15f      // you can hear a radio from further off
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
        if (mgIdx >= 0) return mgTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > width - 52f * d && e.y < 46f * d) { soundTap = true; return true }
                overlayAtDown = overlayUp()                       // a tap anywhere dismisses a Spotted / Delivered screen
                touchWasJoy = !overlayAtDown && hypot(e.x - joyCx, e.y - joyCy) < joyR * 1.7f
                if (touchWasJoy) { joyActive = true; readJoystick(e.x, e.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (soundTap) return true
                if (joyActive) readJoystick(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (soundTap) {
                    soundTap = false
                    if (e.actionMasked == MotionEvent.ACTION_UP) { soundOn = !soundOn; saveSound(soundOn); if (soundOn) music.start() else music.stop() }
                    return true
                }
                joyActive = false; mag = 0f; knobX = 0f; knobY = 0f
                if (e.actionMasked == MotionEvent.ACTION_UP && overlayAtDown) when (world.state) {
                    StealthWorld.State.CAUGHT -> if (world.stateT > 0.5f) { attempt++; if (stage == 1) restartPac() else load(true) }
                    StealthWorld.State.WON -> if (lastLevel) {
                        if (wt > 12f) { saveLevel(0); music.stop(); onExit() }
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

    override fun onDraw(c: Canvas) {
        if (mgIdx >= 0) { drawMg(c); return }
        val w = width.toFloat(); val h = height.toFloat()
        val s = world.spec
        val pal = s.pal
        c.drawColor(pal.wall)
        val top = 44f * d; val bottom = 14f * d
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
            val sc = max(1, (w / (world.cols * 16f)).toInt())
            T = 16f * sc
            offX = ((w - T * world.cols) / 2f).toInt().toFloat(); offY = top
            val availH = h - top - bottom
            val viewRows = min(world.rows.toFloat(), floor((availH / T)))
            scrollY = (world.py - viewRows * 0.68f).coerceIn(0f, world.rows - viewRows)   // look ahead: you sit low on the screen
            offY = top + max(0f, ((availH - viewRows * T) / 2f).toInt().toFloat())          // a short map sits in the middle
            val srcTop = (scrollY * 16f).toInt()
            c.drawColor(0xFF03050A.toInt())
            val src = android.graphics.Rect(0, srcTop, pix.W, srcTop + (viewRows * 16f).toInt())
            rect.set(offX, offY, offX + T * world.cols, offY + viewRows * T)
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
        drawHud(c, w, h, pal)
        drawJoystick(c)
        drawClue(c, w, h, pal)
        drawSay(c, w, h, pal)
        drawOverlays(c, w, h, pal)
        drawFade(c, w, h)
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

    /** What the contact says as you walk up to him. */
    private fun drawSay(c: Canvas, w: Float, h: Float, pal: Palette) {
        val t = world.sayText ?: return
        if (world.state != StealthWorld.State.PLAY) return
        panel(c, w, h, pal, (world.spec.lead?.speaker ?: world.spec.worker?.speaker ?: spec.contact.uppercase()) + ": “" + t + "”", 1f, (world.sayT * 45f).toInt())
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
        val top = 44f * d; val bottom = 44f * d
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

    private fun drawOverlays(c: Canvas, w: Float, h: Float, pal: Palette) {
        p.textAlign = Paint.Align.CENTER
        if (sinceLoad < 4.2f && attempt == 1 && world.state == StealthWorld.State.PLAY) {
            val f = if (sinceLoad < 0.3f) sinceLoad / 0.3f else if (sinceLoad > 3.4f) (4.2f - sinceLoad) / 0.8f else 1f
            val y0 = h * 0.22f; val ph = h * 0.46f
            p.style = Paint.Style.FILL; p.color = a(0xFF04060A.toInt(), (f * 0xE0).toInt()); c.drawRect(0f, y0, w, y0 + ph, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = a(pal.accent, (f * 0x80).toInt()); c.drawLine(24f * d, y0 + 1f, w - 24f * d, y0 + 1f, p); c.drawLine(24f * d, y0 + ph - 1f, w - 24f * d, y0 + ph - 1f, p)
            drawBigLetter(c, w / 2, y0 + 44f * d, levelIdx, f)
            p.style = Paint.Style.FILL; p.typeface = monoBold; p.letterSpacing = 0.2f; p.textSize = 11f * d; p.color = a(pal.accent, (f * 255).toInt()); c.drawText("LETTER ${roman[levelIdx]} OF VI", w / 2, y0 + 92f * d, p)
            p.letterSpacing = 0.04f; p.textSize = 25f * d; p.color = a(Color.WHITE, (f * 255).toInt()); c.drawText(spec.building.uppercase(), w / 2, y0 + 124f * d, p)
            p.typeface = mono; p.letterSpacing = 0f; p.textSize = 13f * d; p.color = a(Color.WHITE, (f * 0xE0).toInt())
            val yy = wrap(c, spec.objective, w / 2, y0 + 152f * d, w - 56f * d, 18f * d)
            p.color = a(Color.WHITE, (f * 0x80).toInt()); p.textSize = 12f * d
            wrap(c, spec.situation, w / 2, yy + 10f * d, w - 56f * d, 17f * d)
        } else if (sinceLoad in 4.2f..9f && attempt == 1 && world.state == StealthWorld.State.PLAY) {
            // the how-to-play line, once, after the title card
            val f = if (sinceLoad < 4.8f) (sinceLoad - 4.2f) / 0.6f else if (sinceLoad > 8.2f) (9f - sinceLoad) / 0.8f else 1f
            if (clueAlpha < 0.05f) {
                p.style = Paint.Style.FILL; p.typeface = mono; p.textSize = 12.5f * d; p.color = a(Color.WHITE, (f * 0xD0).toInt())
                wrap(c, spec.tip, w / 2, h - 80f * d, w - 56f * d, 17f * d)
            }
        }
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
