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

/** The call screen: who, status / timer, and Mute · Speaker · End (or Decline · Answer when ringing). */
class CallActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private var shownPhase = -1
    private val listener: (Core.Call) -> Unit = { render() }
    private var proximity: PowerManager.WakeLock? = null
    private var answerWhenAllowed = false
    private val tick = object : Runnable {
        override fun run() { updateStatus(); status.postDelayed(this, 1000) }
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Messenger.init(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val k = Messenger.core.call ?: return finish()
        val c = Messenger.core.contact(k.user)
        val name = c?.name ?: "@${k.user}"
        status = TextView(this).apply { setTextColor(DIM); textSize = 16f; gravity = Gravity.CENTER }
        buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(BG); fitsSystemWindows = true
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(avatar(name, c?.avatar), LinearLayout.LayoutParams(d(116), d(116)))
            addView(TextView(context).apply {
                text = name; setTextColor(Color.WHITE); textSize = 26f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(d(24), d(20), d(24), d(6))
            })
            addView(status)
            addView(TextView(context).apply {
                text = "🔒 End-to-end encrypted · through Tor"; setTextColor(0x66FFFFFF); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, d(10), 0, 0)
            })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1.4f))
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(56) })
        })
        if (intent.getBooleanExtra("answer", false)) answer()
        render()
    }

    override fun onStart() { super.onStart(); Messenger.callListeners.add(listener); status.post(tick) }
    override fun onStop() { Messenger.callListeners.remove(listener); status.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() { proximity?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    private fun avatar(name: String, photo: ByteArray?): View {
        val bmp = photo?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) return ImageView(this).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }
        val initials = name.removePrefix("@").split(" ", "_").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        return TextView(this).apply {
            text = initials.ifEmpty { "?" }; setTextColor(Color.WHITE); textSize = 40f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3A3A3C")) }
        }
    }

    private fun round(icon: Int, fill: Int, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
                FrameLayout.LayoutParams(d(30), d(30), Gravity.CENTER))
            isClickable = true; setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(d(68), d(68)))
        addView(TextView(context).apply { text = label; setTextColor(DIM); textSize = 13f; setPadding(0, d(8), 0, 0) })
    }

    private fun render() {
        val k = Messenger.core.call
        if (k == null || k.phase == Core.Call.ENDED) {
            status.text = "Call ended"; buttons.removeAllViews()
            status.postDelayed({ finish() }, 1200)
            return
        }
        updateStatus()
        if (k.phase == shownPhase) return
        shownPhase = k.phase
        buttons.removeAllViews()
        fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        if (k.phase == Core.Call.INCOMING) {
            add(round(R.drawable.ic_call_end, RED, "Decline") { Messenger.hangUp() })
            add(round(R.drawable.ic_call, GREEN, "Answer") { answer() })
        } else {
            add(round(R.drawable.ic_mic_off, if (k.muted) Color.WHITE else BTN, if (k.muted) "Muted" else "Mute") {
                k.muted = !k.muted; shownPhase = -1; render()
            }.also { if (k.muted) ((it.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setColorFilter(BG) })
            add(round(R.drawable.ic_speaker, if (CallAudio.speaker) Color.WHITE else BTN, "Speaker") {
                CallAudio.setSpeaker(this, !CallAudio.speaker); shownPhase = -1; render()
            }.also { if (CallAudio.speaker) ((it.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setColorFilter(BG) })
            add(round(R.drawable.ic_call_end, RED, "End") { Messenger.hangUp() })
        }
        // screen off when the phone is at your ear (not on speaker)
        if (k.phase == Core.Call.ACTIVE && proximity == null) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "msngr:call").apply { acquire(4 * 3600_000L) }
        }
    }

    private fun updateStatus() {
        val k = Messenger.core.call ?: return
        status.text = when (k.phase) {
            Core.Call.DIALING -> "Calling…"
            Core.Call.RINGING -> "Ringing…"
            Core.Call.INCOMING -> "Incoming call"
            Core.Call.CONNECTING -> "Connecting…"
            Core.Call.ACTIVE -> ((System.currentTimeMillis() - k.connectedAt) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
            else -> "Call ended"
        }
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
        else { status.text = "MSNGR needs the microphone for calls"; Messenger.hangUp() }
    }

    companion object {
        private val BG = Color.parseColor("#101012")
        private val BTN = Color.parseColor("#2C2C2E")
        private val DIM = Color.parseColor("#A0A0A5")
        private val RED = Color.parseColor("#E5534B")
        private val GREEN = Color.parseColor("#34C759")
        const val CH_CALL = "calls"

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
        val n = Notification.Builder(this, CallActivity.CH_CALL).setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Call with $name").setContentText("Tap to return to the call").setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTE, n)
        return START_NOT_STICKY
    }

    companion object {
        const val DECLINE = "com.wilfrombdn.telegramnt.DECLINE_CALL"
        const val NOTE = 91
    }
}
