package com.wilfrombdn.telegramnt

import android.content.Intent
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Tap = the button's normal job. Hold = a "Snap" bubble pops up above it; slide onto it and release to open the camera. */
internal fun MainActivity.snapHold(fab: View, frame: FrameLayout) {
    val ui = android.os.Handler(android.os.Looper.getMainLooper())
    var bubble: View? = null
    var open = false; var over = false; var cancelled = false
    var sx = 0f; var sy = 0f
    val show = Runnable {
        open = true
        fab.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val b = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = round(menuBg, 28); elevation = dp(8).toFloat()
            setPadding(dp(18), dp(12), dp(22), dp(12))
            addView(icon(R.drawable.ic_camera, white, 22, 0))
            addView(tv("Snap", 16f, white, true).apply { setPadding(dp(10), 0, 0, 0) })
        }
        frame.addView(b, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16 + 56 + 14)) })
        bubble = b
    }
    fun close() { ui.removeCallbacks(show); bubble?.let { frame.removeView(it) }; bubble = null; open = false; fab.isPressed = false }
    fab.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                sx = e.rawX; sy = e.rawY; open = false; over = false; cancelled = false
                v.isPressed = true; ui.postDelayed(show, 380)
            }
            MotionEvent.ACTION_MOVE -> {
                val b = bubble
                if (open && b != null) {
                    val at = IntArray(2); b.getLocationOnScreen(at)
                    val m = dp(14)
                    val inside = e.rawX >= at[0] - m && e.rawX <= at[0] + b.width + m && e.rawY >= at[1] - m && e.rawY <= at[1] + b.height + m
                    if (inside != over) {
                        over = inside
                        b.background = round(if (over) blue else menuBg, 28)
                        b.animate().scaleX(if (over) 1.08f else 1f).scaleY(if (over) 1.08f else 1f).setDuration(90).start()
                        if (over) b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else if (!open && Math.hypot((e.rawX - sx).toDouble(), (e.rawY - sy).toDouble()) > dp(12)) {
                    cancelled = true; ui.removeCallbacks(show); v.isPressed = false   // dragged off before it opened: not a tap
                }
            }
            MotionEvent.ACTION_UP -> {
                val go = open && over; val tap = !open && !cancelled
                close()
                if (go) startSnap() else if (tap) v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> close()
        }
        true
    }
}

internal fun MainActivity.startSnap() {
    @Suppress("DEPRECATION") startActivityForResult(Intent(this, CameraActivity::class.java).putExtra("snap", true), SNAP)
}

/** The shot is taken: pick who gets it. Backing out (or sending) puts you back on the screen you left. */
internal fun MainActivity.showSnapSend(uri: android.net.Uri, photo: Boolean, caption: String) {
    if (screen != "snapsend") {
        snapBack = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        snapScreen = screen
    }
    screen = "snapsend"; snapUri = uri
    val picked = LinkedHashSet<String>()   // "t:user" Tellem friend, "g:id" group, "s:number" text
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val send = mainButton("Pick who to send to") {}
    fun refreshButton() { send.text = if (picked.isEmpty()) "Pick who to send to" else "Send to ${picked.size}"; send.alpha = if (picked.isEmpty()) 0.5f else 1f }
    refreshButton()
    fun heading(s: String) = col.addView(tv(s, 13.5f, dim, true).apply { setPadding(dp(20), dp(16), dp(20), dp(6)) })
    fun row(into: LinearLayout, key: String, name: String, sub: String?, photoBytes: ByteArray?) {
        val tick = icon(R.drawable.ic_check, white, 22, 0).apply { visibility = if (key in picked) View.VISIBLE else View.INVISIBLE }
        into.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)); background = pressable(); isClickable = true
            addView(avatar(name, 42, photoBytes))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(tv(name, 16.5f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                sub?.let { addView(tv(it, 13f, dim)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tick)
            setOnClickListener {
                if (!picked.remove(key)) picked.add(key)
                tick.visibility = if (key in picked) View.VISIBLE else View.INVISIBLE; refreshButton()
            }
        })
    }
    val core = Messenger.core
    val friends = core.contacts.toList().filter { it.canChat && it.status != Contact.SAVED }.sortedBy { it.name.lowercase() }
    val groups = core.groups.toList().sortedBy { it.name.lowercase() }
    if (friends.isNotEmpty() || groups.isNotEmpty()) {
        heading(APP_NAME.uppercase() + " · private")
        friends.forEach { row(col, "t:${it.user}", it.name, "@${it.user}", it.avatar) }
        groups.forEach { row(col, "g:${it.id}", it.name, "group", null) }
    }
    heading("TEXT · normal text, not encrypted" + if (photo) "" else " (photos only)")
    val numbers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val typed = field("Type a phone number").apply { inputType = InputType.TYPE_CLASS_PHONE; addTextChangedListener(PhoneFormatWatcher()) }
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), dp(4))
        addView(typed, LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv("Add", 16f, blue, true).apply {
            setPadding(dp(14), dp(12), 0, dp(12))
            setOnClickListener {
                val n = typed.text.toString().filter { it.isDigit() || it == '+' }
                if (n.count { it.isDigit() } < 3) return@setOnClickListener toast("Type a phone number")
                if (picked.add("s:$n")) { row(numbers, "s:$n", typed.text.toString(), "number you typed", null); refreshButton() }
                typed.setText("")
            }
        })
    })
    col.addView(numbers)
    if (Phone.canReadContacts(this)) {
        val people = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(people)
        var found: List<Phone.Person> = emptyList()
        Messenger.run({ found = Phone.people(this) }, {
            if (screen == "snapsend") found.forEach { p -> p.numbers.take(3).forEach { (n, label) -> row(people, "s:${n.filter { c -> c.isDigit() || c == '+' }}", p.name, "$label · $n", null) } }
        })
    }
    col.addView(gap(24))

    send.setOnClickListener {
        if (picked.isEmpty()) return@setOnClickListener toast("Pick at least one person")
        val sms = picked.filter { it.startsWith("s:") }.map { it.substring(2) }
        if (sms.isNotEmpty() && !photo) return@setOnClickListener toast("Videos can only go to $APP_NAME friends")
        if (sms.isNotEmpty() && !Texts.isDefault(this)) return@setOnClickListener toast("To send picture texts, make $APP_NAME your texting app first")
        send.isEnabled = false; send.text = "Sending…"
        val tellem = picked.filter { it.startsWith("t:") }.map { it.substring(2) }.mapNotNull { core.contact(it) }
        val grp = picked.filter { it.startsWith("g:") }.map { it.substring(2) }.mapNotNull { core.group(it) }
        Messenger.run({
            if (photo) {
                val bmp = loadPhoto(uri, CAMERA_SIDE) ?: throw UserError("Couldn't open that photo")
                val data = jpeg(bmp, CAMERA_SIDE, 90); val thumb = thumbOf(bmp)
                tellem.forEach { Messenger.sendFile(it, "snap.jpg", "image/jpeg", data, thumb) }
                grp.forEach { Messenger.sendGroupFile(it, "snap.jpg", "image/jpeg", data, thumb) }
                if (sms.isNotEmpty()) {
                    val limit = (Texts.mmsLimit(this) * 0.9).toInt()
                    var side = 1600; var q = 85; var b = jpeg(bmp, side, q)
                    while (b.size > limit && side > 320) { if (q > 60) q -= 10 else side = side * 3 / 4; b = jpeg(bmp, side, q) }
                    sms.forEach { Texts.send(this, listOf(it), "", b to "image/jpeg") }
                }
            } else {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserError("Couldn't read that video")
                if (raw.size > Core.MAX_FILE) throw UserError("That video is too big")
                val data = Mp4Clean.strip(raw)
                tellem.forEach { Messenger.sendFile(it, "snap.mp4", "video/mp4", data, null); if (caption.isNotEmpty()) Messenger.send(it, caption) }
                grp.forEach { Messenger.sendGroupFile(it, "snap.mp4", "video/mp4", data, null) }
            }
        }, {
            runCatching { contentResolver.delete(uri, null, null) }
            toast("Sent"); leaveSnap()
        }) { e -> send.isEnabled = true; refreshButton(); toast(errorText(e)) }
    }
    setContentView(page(topBar("Send snap", null, icon(R.drawable.ic_back) { cancelSnap() }), LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(ScrollView(this@showSnapSend).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(send, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
    }))
}

internal fun MainActivity.cancelSnap() {
    snapUri?.let { u -> runCatching { contentResolver.delete(u, null, null) } }
    leaveSnap()
}

/** Back to wherever you were before the camera. */
internal fun MainActivity.leaveSnap() {
    snapUri = null
    val back = snapBack; snapBack = null
    screen = snapScreen
    if (back == null || snapScreen == "chats") showChats() else setContentView(back)
}
