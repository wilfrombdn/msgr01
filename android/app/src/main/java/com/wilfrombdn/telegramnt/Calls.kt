package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal fun MainActivity.callFriend(c: Contact) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        callAfterMic = c
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_FOR_CALL); return
    }
    Messenger.core.call?.let { startActivity(Intent(this, CallActivity::class.java)); return }   // already in one: show it
    Messenger.startCall(this, c, { e -> toast(errorText(e)) }, ::errorText)
}

/** Telegram-style call summary in the chat; tap the phone to call back. */
internal fun MainActivity.callBubble(c: Contact, m: Msg): View {
    val k = m.call!!
    val ok = k.optString("s") == "ok"
    val bad = k.optString("s") in listOf("missed", "failed") && k.optString("d") == "in" || k.optString("s") == "failed"
    val wrap = LinearLayout(this).apply { gravity = if (m.mine) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4)); tag = m.id }
    val fill = if (m.mine) look.out else look.inn
    val ink = Look.textOn(fill)
    val sub = (ink and 0x00FFFFFF) or (0x99 shl 24)
    val b = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = round(fill, if (look.square) 0 else 14)
        setPadding(dp(12), dp(8), dp(6), dp(8))
    }
    b.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(callTitle(k), 15.5f, ink, true))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, 0)
            addView(tv(if (k.optString("d") == "out") "↗" else "↙", 14f, if (bad) red else if (ok) Color.parseColor("#34C759") else sub, true).apply { setPadding(0, 0, dp(5), 0) })
            addView(tv(time(m.ts) + if (ok) ", " + callDuration(k.optInt("dur")) else "", 13f, sub))
        })
    })
    b.addView(icon(R.drawable.ic_call, ink, 22, 12) { callFriend(c) }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(18) })
    b.setOnLongClickListener {
        longPressMenu(null, null, listOf("Delete for me" to { Messenger.core.deleteMessage(c, m, forEveryone = false); fillChat() })); true
    }
    wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (m.mine) leftMargin = dp(56) else rightMargin = dp(56) })
    return wrap
}
