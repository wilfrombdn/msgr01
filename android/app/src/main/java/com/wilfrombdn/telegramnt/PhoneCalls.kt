package com.wilfrombdn.telegramnt

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager

import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tellem as the phone's calling app (Settings > Phone calls). Android hands every normal phone call to
 * [PhoneCallService]; the screens are [PhoneCallActivity] (ringing / in a call) and [DialerActivity] (keypad).
 * Calls go through the phone carrier like in any dialer: not Tor, not encrypted. Tellem's own calls are separate.
 */
object PhoneCalls {
    val calls = CopyOnWriteArrayList<Call>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var service: PhoneCallService? = null
    private val ui = Handler(Looper.getMainLooper())
    const val CHANNEL = "phone_calls"
    const val MISSED = "phone_missed"
    const val NOTE_ID = 9100

    fun changed() { ui.post { listeners.forEach { it() } } }

    fun isDefaultDialer(ctx: Context) = runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName }.getOrDefault(false)

    fun number(c: Call): String = c.details?.handle?.schemeSpecificPart.orEmpty()

    /** Who to show: a saved contact's name, else the number the network gave, else "Unknown". */
    fun nameOf(ctx: Context, c: Call): String {
        val n = number(c)
        if (n.isEmpty()) return c.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: T("Unknown")
        return runCatching { Texts.name(ctx, n) }.getOrDefault(n)
    }

    /** The call to show first: one that's ringing, else one in progress. */
    fun primary(): Call? = calls.firstOrNull { it.state == Call.STATE_RINGING } ?: calls.firstOrNull { it.state != Call.STATE_DISCONNECTED } ?: calls.firstOrNull()

    /** Places a call (through the system once Tellem is the calling app and allowed to), else opens the dial pad. */
    fun dial(ctx: Context, number: String) {
        val uri = Uri.fromParts("tel", number, null)
        val can = isDefaultDialer(ctx) && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (can) runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle()); return }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)   // Android itself rings; this only shows the call
        })
        nm.createNotificationChannel(NotificationChannel(MISSED, "Missed phone calls", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openScreen(ctx: Context) = Intent(ctx, PhoneCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun action(ctx: Context, what: String, code: Int) = PendingIntent.getBroadcast(ctx, code,
        Intent(ctx, PhoneCallReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The ringing / ongoing call notification (full-screen when ringing on a locked phone). */
    fun notify(ctx: Context) = runCatching { notifyNow(ctx) }

    private fun notifyNow(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val c = primary()
        if (c == null || c.state == Call.STATE_DISCONNECTED) { nm.cancel(NOTE_ID); return }
        channels(ctx)
        val name = nameOf(ctx, c)
        val screen = PendingIntent.getActivity(ctx, 1, openScreen(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ringing = c.state == Call.STATE_RINGING
        val b = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentIntent(screen)
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
        val decline = action(ctx, PhoneCallReceiver.DECLINE, 2); val answer = action(ctx, PhoneCallReceiver.ANSWER, 3); val hang = action(ctx, PhoneCallReceiver.HANGUP, 4)
        if (ringing && Build.VERSION.SDK_INT >= 31) {
            // CallStyle is only allowed with a full-screen intent (ringing); an ongoing call gets a plain notification
            val who = android.app.Person.Builder().setName(name).build()
            b.setStyle(Notification.CallStyle.forIncomingCall(who, decline, answer))
        } else {
            b.setContentTitle(name).setContentText(if (ringing) T("Incoming call") else T("Phone call"))
            if (ringing) { b.addAction(Notification.Action.Builder(null, T("Decline"), decline).build()); b.addAction(Notification.Action.Builder(null, T("Answer"), answer).build()) }
            else b.addAction(Notification.Action.Builder(null, T("Hang up"), hang).build())
        }
        if (ringing) b.setFullScreenIntent(screen, true)
        runCatching { nm.notify(NOTE_ID, b.build()) }
    }

    fun missed(ctx: Context, who: String) {
        channels(ctx)
        val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(NotificationManager::class.java).notify(who.hashCode(), Notification.Builder(ctx, MISSED)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(T("Missed call")).setContentText(who).setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Android calls this for every phone call once Tellem is the calling app. */
class PhoneCallService : InCallService() {
    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { PhoneCalls.notify(this@PhoneCallService); PhoneCalls.changed() }
        override fun onDetailsChanged(call: Call, details: Call.Details) { PhoneCalls.notify(this@PhoneCallService); PhoneCalls.changed() }
    }

    override fun onCreate() { super.onCreate(); PhoneCalls.service = this }
    override fun onDestroy() { PhoneCalls.service = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        PhoneCalls.calls.add(call)
        call.registerCallback(cb)
        PhoneCalls.notify(this)
        // ringing: the notification's full-screen intent shows the screen; a call you start shows it right away
        if (call.state != Call.STATE_RINGING) runCatching { startActivity(PhoneCalls.openScreen(this)) }
        PhoneCalls.changed()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        PhoneCalls.calls.remove(call)
        if (call.details?.disconnectCause?.code == DisconnectCause.MISSED) PhoneCalls.missed(this, PhoneCalls.nameOf(this, call))
        PhoneCalls.notify(this)
        PhoneCalls.changed()
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) { PhoneCalls.changed() }
}

/** Answer / Decline / Hang up from the notification. */
class PhoneCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = PhoneCalls.primary() ?: return
        when (i.action) {
            ANSWER -> { c.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY); runCatching { ctx.startActivity(PhoneCalls.openScreen(ctx)) } }
            DECLINE -> c.reject(false, null)
            HANGUP -> c.disconnect()
        }
    }
    companion object {
        const val ANSWER = "com.wilfrombdn.telegramnt.PHONE_ANSWER"
        const val DECLINE = "com.wilfrombdn.telegramnt.PHONE_DECLINE"
        const val HANGUP = "com.wilfrombdn.telegramnt.PHONE_HANGUP"
    }
}

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Activity.circle(label: String, size: Int, fill: Int, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** A button with a small label under it. */
private fun Activity.labelled(label: String, glyph: String, on: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    addView(circle(glyph, 64, if (on) Color.WHITE else 0x33FFFFFF) { onClick() }.also { if (on) it.setTextColor(Color.BLACK) })
    addView(TextView(context).apply { text = label; setTextColor(0xCCFFFFFF.toInt()); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
}

/** The phone-call screen: ringing (Answer / Decline) and in a call (Mute · Keypad · Speaker · Hold · Hang up). */
class PhoneCallActivity : Activity() {
    private lateinit var root: LinearLayout
    private var keypad = false
    private val names = HashMap<String, String>()
    private val tick = object : Runnable { override fun run() { render(); root.postDelayed(this, 1000) } }
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; fitsSystemWindows = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1F1F1F.toInt(), 0xFF0B0B0B.toInt()))
        }
        setContentView(root)
    }

    override fun onStart() { super.onStart(); PhoneCalls.listeners.add(listener); root.post(tick) }
    override fun onStop() { PhoneCalls.listeners.remove(listener); root.removeCallbacks(tick); super.onStop() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); render() }

    @Suppress("DEPRECATION")
    private fun render() {
        try { renderNow() } catch (e: Exception) {   // never crash mid-call: show what went wrong and keep a way to hang up
            root.removeAllViews()
            root.addView(TextView(this).apply { text = T("Call screen problem: ${e.javaClass.simpleName} ${e.message}"); setTextColor(Color.WHITE); setPadding(dp(24), dp(80), dp(24), dp(24)) })
            root.addView(circle("✆", 72, 0xFFE53935.toInt()) { PhoneCalls.primary()?.disconnect(); finish() })
        }
    }

    @Suppress("DEPRECATION")
    private fun renderNow() {
        val c = PhoneCalls.primary()
        if (c == null) { finish(); return }
        val num = PhoneCalls.number(c)
        val name = names[num] ?: PhoneCalls.nameOf(this, c).also { names[num] = it }
        val st = c.state
        if (st == Call.STATE_DISCONNECTED) { showEnded(name); return }
        val ringing = st == Call.STATE_RINGING
        val secs = if (c.details.connectTimeMillis > 0) (System.currentTimeMillis() - c.details.connectTimeMillis) / 1000 else 0
        val status = when (st) {
            Call.STATE_RINGING -> T("Incoming phone call")
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> T("Calling…")
            Call.STATE_HOLDING -> T("On hold")
            else -> "%d:%02d".format(secs / 60, secs % 60)
        }
        val audio = PhoneCalls.service?.callAudioState
        val muted = audio?.isMuted == true
        val speaker = audio?.route == CallAudioState.ROUTE_SPEAKER
        val onHold = st == Call.STATE_HOLDING
        root.removeAllViews()
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.5f))
        root.addView(TextView(this).apply {   // initial in a circle
            text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"; textSize = 54f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A3A3C.toInt()) }
        }, LinearLayout.LayoutParams(dp(140), dp(140)))
        root.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(dp(24), dp(20), dp(24), dp(4))
        })
        if (name != num && num.isNotEmpty()) root.addView(TextView(this).apply { text = num; setTextColor(0x99FFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = status; setTextColor(0xCCFFFFFF.toInt()); textSize = 17f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        root.addView(TextView(this).apply { text = T("Normal phone call · not encrypted"); setTextColor(0x66FFFFFF.toInt()); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        if (ringing) {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(labelledRound(T("Decline"), "✕", 0xFFE53935.toInt()) { c.reject(false, null) })
                addView(View(context), LinearLayout.LayoutParams(dp(80), 1))
                addView(labelledRound(T("Answer"), "✆", 0xFF2EAD5B.toInt()) { c.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(56) })
            return
        }
        if (keypad) {
            root.addView(keypad(62, 8, { k -> c.playDtmfTone(k[0]); root.postDelayed({ c.stopDtmfTone() }, 160) }))
        } else {
            root.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                listOf(labelled(T("Mute"), "🎙", muted) { PhoneCalls.service?.setMuted(!muted); render() },
                    labelled(T("Keypad"), "⌗", false) { keypad = true; render() },
                    labelled(T("Speaker"), "🔊", speaker) { PhoneCalls.service?.setAudioRoute(if (speaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER); render() },
                    labelled(T("Hold"), "⏸", onHold) { if (onHold) c.unhold() else c.hold() }
                ).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            if (keypad) addView(TextView(context).apply { text = T("Hide keypad"); setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(20), dp(10), dp(30), dp(10)); setOnClickListener { keypad = false; render() } })
            addView(circle("✆", 72, 0xFFE53935.toInt()) { c.disconnect() }.apply { rotation = 135f })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(48) })
    }

    private fun labelledRound(label: String, glyph: String, fill: Int, go: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(circle(glyph, 76, fill) { go() })
        addView(TextView(context).apply { text = label; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
    }

    private fun showEnded(name: String) {
        root.removeAllViews()
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 26f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = T("Call ended"); setTextColor(0xCCFFFFFF.toInt()); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.postDelayed({ if (PhoneCalls.primary()?.state != Call.STATE_RINGING) finish() }, 1200)
    }
}

/** The dial pad. Android opens it for "dial this number" links, and Tellem's Phone tab opens it. */
class DialerActivity : Activity() {
    private val digits = StringBuilder()   // what was pressed: digits and a leading +
    private lateinit var shown: TextView
    private lateinit var actions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.let { digits.append(PhoneFormat.dialable(it)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; fitsSystemWindows = true
            setBackgroundColor(0xFF141414.toInt())
        }
        root.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.START; topMargin = dp(4); leftMargin = dp(4) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        shown = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1; minHeight = dp(64); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        root.addView(shown, LinearLayout.LayoutParams(-1, -2))
        // appear once there is a number: save it as a contact, or text it
        actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            fun chip(label: String, go: () -> Unit) = addView(TextView(context).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply { setColor(0x14FFFFFF); setStroke(dp(1), 0x40FFFFFF); cornerRadius = dp(22).toFloat() }
                setOnClickListener { go() }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(6), 0, dp(6), 0) })
            chip(T("＋  Create contact")) {
                startActivity(Intent(this@DialerActivity, MainActivity::class.java).putExtra("addContact", PhoneFormat.pretty(digits.toString())))
            }
            chip(T("💬  Send message")) {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", PhoneFormat.dialable(digits.toString()), null)).setPackage(packageName))
            }
        }
        root.addView(actions, LinearLayout.LayoutParams(-2, dp(48)).apply { bottomMargin = dp(12) })
        root.addView(keypad(74, 8, { k -> digits.append(k); refresh() }) { digits.append("+"); refresh() })
        // call button centred under the keypad; backspace on its right (a same-size gap keeps the call button in the middle)
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(View(context), LinearLayout.LayoutParams(dp(74), dp(74)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
            addView(circle("✆", 74, 0xFF2EAD5B.toInt()) { call() }, LinearLayout.LayoutParams(dp(74), dp(74)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
            addView(TextView(context).apply {
                text = "⌫"; textSize = 26f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
                setOnClickListener { if (digits.isNotEmpty()) digits.setLength(digits.length - 1); refresh() }
                setOnLongClickListener { digits.setLength(0); refresh(); true }
            }, LinearLayout.LayoutParams(dp(74), dp(74)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.8f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val raw = digits.toString()
        shown.text = PhoneFormat.pretty(raw)
        shown.textSize = if (shown.text.length > 17) 26f else if (shown.text.length > 14) 30f else 34f
        actions.visibility = if (raw.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun call() {
        val n = digits.toString()
        if (n.isEmpty()) return
        if (!PhoneCalls.isDefaultDialer(this)) {
            // not the calling app: hand the number to whichever dialer the phone uses
            runCatching { startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null))) }.onFailure { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1) }
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1); return }
        PhoneCalls.dial(this, n); finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) call()
    }
}

private val KEY_LETTERS = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")

/** A phone keypad: four rows of three round keys with letters under the digits. [size] and [gap] are in dp. */
private fun Activity.keypad(size: Int, gap: Int, onKey: (String) -> Unit, onPlus: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            for (k in row) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }
                addView(TextView(context).apply { text = k; setTextColor(Color.WHITE); textSize = if (size >= 70) 30f else 24f; gravity = Gravity.CENTER; includeFontPadding = false })
                if (k in KEY_LETTERS) addView(TextView(context).apply { text = KEY_LETTERS[k]; setTextColor(0x99FFFFFF.toInt()); textSize = 9f; letterSpacing = 0.15f; gravity = Gravity.CENTER; includeFontPadding = false })
                setOnClickListener { onKey(k) }
                if (k == "0" && onPlus != null) setOnLongClickListener { onPlus(); true }   // hold 0 for +
            }, LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(gap), dp(gap), dp(gap), dp(gap)) })
        })
    }
}
