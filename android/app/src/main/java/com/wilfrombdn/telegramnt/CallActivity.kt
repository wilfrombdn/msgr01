package com.wilfrombdn.telegramnt

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The call screen, laid out like Telegram's: a slowly shifting background, the photo with pulsing rings
 * while it rings, name and status, four emoji to compare the encryption key, and round buttons
 * (Speaker · Mute · End, or Decline · Answer). ↓ goes back to the app; the call carries on.
 */
class CallActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var rings: List<View>
    private var shownPhase = -1
    private val listener: (Core.Call) -> Unit = { runOnUiThread { render() } }
    private var proximity: PowerManager.WakeLock? = null
    private var answerWhenAllowed = false
    private var shown: Core.Call? = null
    private var bgAnim: android.animation.ValueAnimator? = null
    private val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CALLING[0], CALLING[1]))
    private val tick = object : Runnable {
        override fun run() { updateStatus(); status.postDelayed(this, 1000) }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Messenger.init(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        // even if the call already ended (it couldn't start), show the screen and say so
        val k = Messenger.core.call ?: Messenger.lastCall ?: return finish()
        shown = k
        val c = Messenger.core.contact(k.user)
        val name = c?.name ?: "@${k.user}"
        status = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 16f; gravity = Gravity.CENTER }
        buttons = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(d(12), 0, d(12), 0) }

        val top = FrameLayout(this).apply {
            // ↓ back to the app (the call keeps going; the green bar there brings you back)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE); rotation = -90f
                setPadding(d(14), d(14), d(14), d(14)); setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(d(52), d(52), Gravity.START or Gravity.CENTER_VERTICAL))
            // the encryption key as four emoji: both phones show the same four
            addView(TextView(context).apply {
                text = keyEmoji(k.key); textSize = 22f; letterSpacing = 0.15f
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val photo = FrameLayout(this).apply {
            rings = List(2) { View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }; alpha = 0f } }
            rings.forEach { addView(it, FrameLayout.LayoutParams(d(150), d(150), Gravity.CENTER)) }
            addView(avatar(name, c?.avatar), FrameLayout.LayoutParams(d(150), d(150), Gravity.CENTER))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = bg; fitsSystemWindows = true
            addView(top, LinearLayout.LayoutParams(-1, d(56)))
            addView(TextView(context).apply {
                text = "If you both see these 4 emoji, the call is private"; setTextColor(0x99FFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
            })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 0.6f))
            addView(photo, LinearLayout.LayoutParams(d(230), d(230)))
            addView(TextView(context).apply {
                text = name; setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(d(24), d(8), d(24), d(6))
            })
            addView(status)
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(TextView(context).apply {
                text = "🔒 End-to-end encrypted"; setTextColor(0x99FFFFFF.toInt()); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, 0, 0, d(18))
            })
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(40) })
        })
        startBackground()
        startRings()
        if (intent.getBooleanExtra("answer", false)) answer()
        render()
    }

    override fun onStart() { super.onStart(); Messenger.callListeners.add(listener); status.post(tick); render() }
    override fun onStop() { Messenger.callListeners.remove(listener); status.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() {
        bgAnim?.cancel(); rings.forEach { it.animate().cancel() }
        proximity?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** The background drifts between two shades; it turns green once the call is connected (like Telegram). */
    private fun startBackground() {
        if (!animated()) { bg.colors = intArrayOf(CALLING[0], CALLING[2]); return }
        bgAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
            val ev = android.animation.ArgbEvaluator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val pal = if (Messenger.core.call?.phase == Core.Call.ACTIVE) TALKING else CALLING
                bg.colors = intArrayOf(ev.evaluate(f, pal[0], pal[1]) as Int, ev.evaluate(f, pal[2], pal[3]) as Int)
            }
            start()
        }
    }

    /** Two soft rings grow out of the photo while it's ringing or connecting. */
    private fun startRings() {
        if (!animated()) return
        rings.forEachIndexed { i, r ->
            fun pulse() {
                val k = Messenger.core.call
                if (isFinishing || k == null || k.phase == Core.Call.ACTIVE || k.phase == Core.Call.ENDED) { r.alpha = 0f; return }
                r.scaleX = 1f; r.scaleY = 1f; r.alpha = 0.9f
                r.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(1800).withEndAction { pulse() }.start()
            }
            r.postDelayed({ pulse() }, i * 900L)
        }
    }

    private fun avatar(name: String, photo: ByteArray?): View {
        val bmp = photo?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) return ImageView(this).apply {
            setImageBitmap(circle(bmp)); scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val initials = name.removePrefix("@").split(" ", "_").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        return TextView(this).apply {
            text = initials.ifEmpty { "?" }; setTextColor(Color.WHITE); textSize = 48f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
        }
    }

    /** The photo cut to a circle (centre square of it). */
    private fun circle(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val n = minOf(src.width, src.height)
        val out = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(n / 2f, n / 2f, n / 2f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, ((n - src.width) / 2).toFloat(), ((n - src.height) / 2).toFloat(), p)
        return out
    }

    /** A round button with its label underneath; [on] = filled white with a dark icon (Mute/Speaker switched on). */
    private fun round(icon: Int, fill: Int, label: String, on: Boolean = false, size: Int = 60, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Color.WHITE else fill) }
            foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (on) Color.BLACK else Color.WHITE) },
                FrameLayout.LayoutParams(d(size / 2), d(size / 2), Gravity.CENTER))
            isClickable = true; setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(d(size), d(size)))
        addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, d(8), 0, 0) },
            LinearLayout.LayoutParams(-1, -2))
    }

    private fun render() {
        if (!::status.isInitialized) return
        val k = Messenger.core.call
        if (k == null || k.phase == Core.Call.ENDED) {
            val why = shown?.problem
            status.text = why ?: "Call ended"; buttons.removeAllViews(); shownPhase = Core.Call.ENDED
            status.postDelayed({ finish() }, if (why != null) 3500 else 1200)
            return
        }
        updateStatus()
        if (bgAnim == null) (if (k.phase == Core.Call.ACTIVE) TALKING else CALLING).let { bg.colors = intArrayOf(it[0], it[2]) }   // still background: green once talking
        if (k.phase == shownPhase) return
        if (k.phase != Core.Call.ACTIVE) startRings()
        shownPhase = k.phase
        buttons.removeAllViews()
        fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        if (k.phase == Core.Call.INCOMING) {
            add(round(R.drawable.ic_call_end, RED, "Decline", size = 68) { Messenger.hangUp() })
            add(round(R.drawable.ic_call, GREEN, "Accept", size = 68) { answer() })
        } else {
            add(round(R.drawable.ic_speaker, BTN, "Speaker", on = CallAudio.speaker) {
                CallAudio.setSpeaker(this, !CallAudio.speaker); shownPhase = -1; render()
            })
            add(round(R.drawable.ic_mic_off, BTN, "Mute", on = k.muted) {
                k.muted = !k.muted; shownPhase = -1; render()
            })
            add(round(R.drawable.ic_call_end, RED, "End Call") { Messenger.hangUp() })
        }
        // screen off when the phone is at your ear (not on speaker)
        if (k.phase == Core.Call.ACTIVE && proximity == null) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "quipu:call").apply { acquire(4 * 3600_000L) }
        }
    }

    private fun updateStatus() {
        val k = Messenger.core.call ?: return
        status.text = statusText(k, Messenger.connected)
    }

    private fun answer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            answerWhenAllowed = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        Messenger.answer(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!answerWhenAllowed) return
        answerWhenAllowed = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) Messenger.answer(this)
        else { status.text = "quipu needs the microphone for calls"; Messenger.hangUp() }
    }

    companion object {
        private val BTN = 0x33FFFFFF
        private val RED = Color.parseColor("#F14B4B")
        private val GREEN = Color.parseColor("#3CC261")
        // background shades: [top-left from, to, bottom-right from, to]
        private val CALLING = intArrayOf(Color.parseColor("#3B4A63"), Color.parseColor("#4A3F66"), Color.parseColor("#1F2433"), Color.parseColor("#2A2238"))
        private val TALKING = intArrayOf(Color.parseColor("#2F5E4E"), Color.parseColor("#2E5463"), Color.parseColor("#1A3029"), Color.parseColor("#192B33"))

        private val EMOJI = listOf("🐶", "🐱", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦉",
            "🐝", "🦋", "🐢", "🐙", "🐬", "🐳", "🦀", "🌵", "🌲", "🌻", "🍀", "🍄", "🌙", "⭐", "🔥", "🌈",
            "🍎", "🍋", "🍉", "🍇", "🍓", "🍒", "🥕", "🌽", "🍕", "🍩", "🎈", "🎁", "⚽", "🏀", "🎸", "🎲",
            "🚀", "🚲", "⛵", "🏠", "⏰", "💡", "🔑", "🔔", "📚", "✏️", "🎩", "👓", "☂️", "⚓", "🧲", "💎")

        /** Off in screenshot tests (endless animations never let the test clock settle). */
        var animations = true
        private fun animated() = animations && android.animation.ValueAnimator.areAnimatorsEnabled() &&
            Messenger.core.call.let { it != null && it.phase != Core.Call.ENDED }   // nothing to animate once it's over

        /** Four emoji from the call's shared key; both phones show the same four. */
        fun keyEmoji(key: String): String = Crypto.sha256(("quipu-call|" + key).toByteArray()).take(4).joinToString(" ") { EMOJI[(it.toInt() and 0xFF) % EMOJI.size] }

        /** What the call screen (and the green bar) says under the name. */
        fun statusText(k: Core.Call, connected: Boolean): String = when (k.phase) {
            Core.Call.DIALING -> if (connected) "Requesting…" else "Connecting…"
            Core.Call.RINGING -> "Ringing…"
            Core.Call.INCOMING -> "quipu voice call"
            Core.Call.CONNECTING -> "Exchanging encryption keys…"
            Core.Call.ACTIVE -> ((System.currentTimeMillis() - k.connectedAt) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
            else -> "Call ended"
        }
        const val CH_CALL = "calls"

        const val CH_ONGOING = "call_ongoing"

        fun quietChannel(ctx: Context) {
            val ch = NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null); setShowBadge(false) }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun channels(ctx: Context) {
            val ch = NotificationChannel(CH_CALL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** "Incoming call" alert: full screen over the lock screen where allowed, with Decline / Answer. */
        fun incomingNotice(ctx: Context, k: Core.Call): Notification {
            channels(ctx)
            val name = Messenger.core.contact(k.user)?.name ?: "@${k.user}"
            val open = PendingIntent.getActivity(ctx, 20, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val answer = PendingIntent.getActivity(ctx, 21, Intent(ctx, CallActivity::class.java).putExtra("answer", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val decline = PendingIntent.getService(ctx, 22, Intent(ctx, CallService::class.java).setAction(CallService.DECLINE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val fullScreenOk = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            return Notification.Builder(ctx, CH_CALL)
                .setSmallIcon(R.drawable.ic_call).setContentTitle(name).setContentText("Incoming call")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(open)
                .apply { if (fullScreenOk) setFullScreenIntent(open, true) }
                .addAction(Notification.Action.Builder(null, "Decline", decline).build())
                .addAction(Notification.Action.Builder(null, "Answer", answer).build())
                .build()
        }
    }
}

/** Keeps a call (microphone + sound) running when you leave the call screen, with a "tap to return" notification. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DECLINE) { Messenger.hangUp(); stopSelf(); return START_NOT_STICKY }
        val k = Messenger.core.call ?: run { stopSelf(); return START_NOT_STICKY }
        CallActivity.channels(this)
        val name = Messenger.core.contact(k.user)?.name ?: "@${k.user}"
        val open = PendingIntent.getActivity(this, 23, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        // Android requires a notification while a call uses the mic; keep it silent and out of the way
        // (no pop-up, no status-bar icon), it only shows in the pulled-down shade
        CallActivity.quietChannel(this)
        val n = Notification.Builder(this, CallActivity.CH_ONGOING).setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Call with $name").setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTE, n)
        return START_NOT_STICKY
    }

    companion object {
        const val DECLINE = "com.wilfrombdn.telegramnt.DECLINE_CALL"
        const val NOTE = 91
    }
}
