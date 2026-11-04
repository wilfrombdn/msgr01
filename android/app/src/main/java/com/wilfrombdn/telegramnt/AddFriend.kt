package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// Adding friends: the New Message screen, scanning a QR code, and showing my own.

// ---------- add a friend ----------

internal fun MainActivity.showNewChat() {
    screen = Screen.NEW_CHAT
    val me = Messenger.core.me ?: return
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
    // first option, like Telegram: add someone by scanning the QR code on their phone
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; background = pressable(); setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(icon(R.drawable.ic_qr, white, 24, 0))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
            addView(tv("Scan QR code", 17f, white, true))
            addView(tv("Point your camera at a friend's code to add them", 13.5f, dim))
        })
        setOnClickListener { scanToAdd() }
    })
    col.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(20) })
    col.addView(tv("Add a friend", 18f, white, true))
    col.addView(gap(6))
    col.addView(tv("Type their username. They'll get a friend request, and you can start writing right away.", 14.5f, dim))
    col.addView(gap(12))
    val user = usernameField("username")
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(tv("@", 17f, dim))
        addView(user, LinearLayout.LayoutParams(0, -2, 1f))
    })
    col.addView(gap(16))
    val go = cta("Send Friend Request") {}
    go.setOnClickListener {
        go.isEnabled = false
        go.text = "Looking…"
        var c: Contact? = null
        Messenger.run({ c = Messenger.core.addFriend(user.text.toString()); Messenger.kick() }, { c?.let { showChat(it.user) } }) { e ->
            go.isEnabled = true
            go.text = "Send Friend Request"
            toast(errorText(e))
        }
    }
    col.addView(go, ctaParams())
    col.addView(gap(36))
    col.addView(tv("Your username", 14f, dim, true))
    col.addView(gap(6))
    col.addView(tv("@${me.user}", 20f, white))
    col.addView(gap(6))
    col.addView(tv("Share it so friends can add you.", 14f, dim))
    col.addView(gap(12))
    col.addView(LinearLayout(this).apply {
        addView(tv("Copy", 15f, white, true).apply {
            setPadding(0, dp(8), dp(24), dp(8))
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("username", "@${me.user}"))
                toast("Copied")
            }
        })
        addView(tv("Share", 15f, white, true).apply {
            setPadding(0, dp(8), dp(8), dp(8))
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Add me on $APP_NAME: @${me.user}\nGet the app: https://wilfrombdn.github.io/"), "Share username"))
            }
        })
    })
    setContentView(page(topBar("New Message", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
}

// ---------- QR codes ----------

/** Opens the in-app camera to scan a friend's code (New Message > Scan QR code, or Profile). */
internal fun MainActivity.scanToAdd() {
    @Suppress("DEPRECATION") startActivityForResult(Intent(this, QrActivity::class.java), SCAN_QR)
}

/** My code (friends scan it to add me) and a button to scan theirs. */
internal fun MainActivity.myQr() {
    val me = Messenger.core.me ?: return
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply {
            setImageBitmap(QrActivity.codeFor(me.user, 512))
            background = round(Color.WHITE, 12); setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(230), dp(230)))
        addView(tv("@${me.user}", 17f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
    }
    popup("My QR code", "A friend taps New Message > Scan QR code in $APP_NAME and points the camera at this to add you.", col, "Close" to dim, "Scan a code" to white) { i ->
        if (i == 1) @Suppress("DEPRECATION") startActivityForResult(Intent(this, QrActivity::class.java), SCAN_QR)
    }
}

/** A scanned code: a friend (add them) or a group invite (join it). */
internal fun MainActivity.scanned(text: String) {
    if (text.startsWith("msgr01://group")) {
        Messenger.run({ val g = Messenger.core.joinGroupLink(text); Messenger.kick(); groupToOpen = g.id }, { groupToOpen?.let { showGroup(it) } }) { e -> toast(errorText(e)) }
        return
    }
    val user = text.removePrefix(QrActivity.ADD).trim().lowercase()
    if (!Core.USERNAME.matches(user)) return toast("That isn't a $APP_NAME code")
    if (user == Messenger.core.me?.user) return toast("That's your own code")
    Messenger.core.contact(user)?.let { return showChat(it.user) }
    popup("Add @$user?", "Sends them a friend request.", null, "Cancel" to dim, "Add" to white) { i ->
        if (i == 1) Messenger.run({ Messenger.core.addFriend(user); Messenger.kick() }, { showChat(user) }) { e -> toast(errorText(e)) }
    }
}
