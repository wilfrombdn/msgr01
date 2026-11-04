package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout

// Voice message recording button and playback view.

// ---------- voice messages ----------

/**
 * The button at the end of the message box: send when there's text, otherwise a mic.
 * Hold the mic to record, let go to send, slide left to cancel, slide up to lock and talk hands-free (like Telegram).
 */
internal fun MainActivity.sendOrMic(row: LinearLayout, box: EditText, send: () -> Unit, special: (Boolean, Long) -> Unit, sendVoice: (ByteArray, ByteArray) -> Unit): View {
    val btn = icon(R.drawable.ic_mic, white)
    fun showIcon() = btn.setImageResource(if (!box.text.isNullOrBlank()) R.drawable.ic_send else if (videoMode) R.drawable.ic_videocam else R.drawable.ic_mic)
    // tap the mic = switch to round video (and back); hold = record
    val openRound = Runnable {
        roundHeld = true
        buzz(btn)
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, RoundActivity::class.java), ROUND_VIDEO)
    }
    fun toggleMode() {
        videoMode = !videoMode; showIcon()
        if (videoMode) toast("Video message: hold to record. Tap to switch back to voice.")
    }
    showIcon()
    box.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) { showIcon() }
    })
    // while recording, the rest of the row is swapped for: red dot, timer, "slide to cancel"
    val timer = tv("0:00", 16f, white)
    val slide = tv("‹  Slide to cancel", 15f, dim)
    val dot = View(this).apply { background = circle(red) }
    val strip = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(12))
        addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        addView(timer)
        addView(slide, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        slide.gravity = Gravity.CENTER
    }
    // the lock that sits above the mic while you hold it; slide up onto it to keep recording hands-free
    val lockPill = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(10), dp(8), dp(10)); background = round(listBg, 22)
        elevation = dp(4).toFloat()
        addView(icon(R.drawable.ic_lock, dim, 20, 0))
        addView(tv("⌃", 16f, dim).apply { gravity = Gravity.CENTER })
    }
    val lockPop = android.widget.PopupWindow(lockPill, dp(44), -2, false).apply { isClippingEnabled = false; isTouchable = false }
    var held = false
    val holdSend = Runnable {
        held = true
        buzz(btn)
        longPressMenu(null, null, listOf(
            "Send without sound" to { special(true, 0) },
            "Schedule message" to { pickSendTime { at -> special(false, at) } },
        ))
    }
    var startX = 0f
    var startY = 0f
    var active = false
    var locked = false
    var lockTouch = false   // the touch that locked it: lifting that finger must not send
    val h = android.os.Handler(android.os.Looper.getMainLooper())
    val tick = object : Runnable {
        override fun run() {
            if (!Voice.recording) return
            // left the chat while recording (back button, another screen): throw it away
            if (!strip.isAttachedToWindow) { lockPop.dismiss(); active = false; locked = false; Voice.cancel(); return }
            val ms = Voice.elapsed()
            timer.text = Voice.clock((ms / 1000).toInt())
            dot.alpha = if ((ms / 500) % 2 == 0L) 1f else 0.25f
            h.postDelayed(this, 100)
        }
    }
    fun end(sendIt: Boolean) {
        if (!active) return
        active = false; locked = false
        lockPop.dismiss(); lockPill.translationY = 0f
        slide.setOnClickListener(null); slide.text = "‹  Slide to cancel"; slide.setTextColor(dim)
        btn.setImageResource(R.drawable.ic_mic)
        h.removeCallbacks(tick)
        row.removeView(strip)
        for (i in 0 until row.childCount) row.getChildAt(i).visibility = View.VISIBLE
        btn.scaleX = 1f; btn.scaleY = 1f; btn.background = null; btn.setColorFilter(white)
        Voice.onLimit = null
        if (!sendIt) { Voice.cancel(); return }
        val got = Voice.finish()
        if (got == null) { toast("Hold the mic to record, let go to send"); return }
        stick = true
        val (audio, th) = got
        Messenger.io.execute { runCatching { sendVoice(audio, th) } }
    }
    fun lock() {
        locked = true; lockTouch = true
        lockPop.dismiss()
        if (UiPrefs.haptics(this)) runCatching { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(25, 120)) }
        slide.translationX = 0f; slide.alpha = 1f
        slide.text = "Cancel"; slide.setTextColor(red)
        slide.setOnClickListener { end(false) }
        btn.setImageResource(R.drawable.ic_send); btn.scaleX = 1f; btn.scaleY = 1f
    }
    btn.setOnTouchListener { _, e ->
        if (locked) {   // hands-free: a tap on the button sends
            if (e.action == MotionEvent.ACTION_DOWN) lockTouch = false
            if (e.action == MotionEvent.ACTION_UP && !lockTouch) end(true)
            return@setOnTouchListener true
        }
        if (!box.text.isNullOrBlank()) {
            // tap = send; hold = "Send without sound" / "Schedule message"
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { held = false; btn.postDelayed(holdSend, 450) }
                MotionEvent.ACTION_UP -> { btn.removeCallbacks(holdSend); if (!held) send() }
                MotionEvent.ACTION_CANCEL -> btn.removeCallbacks(holdSend)
            }
            return@setOnTouchListener true
        }
        if (videoMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { roundHeld = false; btn.postDelayed(openRound, 300) }
                MotionEvent.ACTION_UP -> { btn.removeCallbacks(openRound); if (!roundHeld) toggleMode() }
                MotionEvent.ACTION_CANCEL -> btn.removeCallbacks(openRound)
            }
            return@setOnTouchListener true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_FOR_VOICE)
                    return@setOnTouchListener true
                }
                if (!Voice.start(this)) { toast("Couldn't use the microphone"); return@setOnTouchListener true }
                active = true; startX = e.rawX; startY = e.rawY
                btn.parent?.requestDisallowInterceptTouchEvent(true)
                if (UiPrefs.haptics(this)) runCatching { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(25, 120)) }
                for (i in 0 until row.childCount) row.getChildAt(i).let { if (it !== btn) it.visibility = View.GONE }
                row.addView(strip, 0, LinearLayout.LayoutParams(0, -2, 1f))
                slide.translationX = 0f; timer.text = "0:00"
                btn.background = circle(white); btn.setColorFilter(listBg); btn.scaleX = 1.25f; btn.scaleY = 1.25f
                Voice.onLimit = { end(true) }
                h.post(tick)
                btn.post { if (active && !locked) runCatching { lockPop.showAsDropDown(btn, (btn.width - dp(44)) / 2, -btn.height - dp(110)) } }
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = (startX - e.rawX).coerceAtLeast(0f)
                val dy = (startY - e.rawY).coerceAtLeast(0f)
                if (dy > dx) {           // going up, toward the lock
                    slide.translationX = 0f; slide.alpha = 1f
                    lockPill.translationY = -dy.coerceAtMost(dp(40).toFloat()) / 2
                    if (dy > dp(90)) lock()
                } else {
                    lockPill.translationY = 0f
                    slide.translationX = -dx / 2
                    slide.alpha = (1f - dx / dp(130)).coerceIn(0.2f, 1f)
                    if (dx > dp(130)) end(false)
                }
            }
            MotionEvent.ACTION_UP -> if (!locked) { if (active && Voice.elapsed() < 300) { end(false); toggleMode() } else end(true) }
            MotionEvent.ACTION_CANCEL -> if (!locked) end(false)
        }
        true
    }
    return btn
}

/** A voice message: play/pause, waveform (tap to jump), and the length or how far in you are. */
internal fun MainActivity.voiceView(m: Msg, ink: Int, onLong: () -> Unit): View {
    val f = m.file!!
    val here = f.has("local")
    val (secs, bars) = Voice.unpack(f.optString("th")) ?: (0 to FloatArray(Voice.BARS) { 0.15f })
    val faded = (ink and 0x00FFFFFF) or (0x55 shl 24)
    val play = ImageView(this).apply {
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = circle(ink)
        setColorFilter((ink.inv() and 0x00FFFFFF) or (0xFF shl 24))   // icon in the bubble's own colour
    }
    val wave = WaveView(this, bars, ink, faded)
    val label = tv(Voice.clock(secs), 12.5f, (ink and 0x00FFFFFF) or (0x99 shl 24))
    val cleared = f.optBoolean("cleared")
    fun load(): ByteArray? = Messenger.core.openFile(m)
    fun refresh() {
        val on = Voice.isPlaying(m.id)
        play.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        wave.progress = Voice.progress(m.id)
        label.text = when {
            !here -> if (cleared) "Removed to save space" else if (m.mine) Voice.clock(secs) else "Downloading…"
            Voice.playingId == m.id -> Voice.clock(Voice.position(m.id))
            else -> Voice.clock(secs)
        }
    }
    fun tap(at: Float?) {
        when {
            here -> Voice.toggle(m.id, ::load, at)
            cleared -> toast("This was removed from your phone to save space")
            else -> retryFetch(m)
        }
        refresh()
    }
    play.setOnClickListener { tap(null) }
    wave.onSeek = { tap(it) }
    val tick = object : Runnable {
        override fun run() { refresh(); if (play.isAttachedToWindow) play.postDelayed(this, if (Voice.playingId == m.id) 50 else 500) }
    }
    refresh()
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), dp(4), dp(2))
        addView(play, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(wave, LinearLayout.LayoutParams(dp(170), dp(26)))
            addView(label.apply { setPadding(0, dp(3), 0, 0) })
        })
        setOnLongClickListener { onLong(); true }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { play.post(tick) }
            override fun onViewDetachedFromWindow(v: View) { play.removeCallbacks(tick) }
        })
    }
}
