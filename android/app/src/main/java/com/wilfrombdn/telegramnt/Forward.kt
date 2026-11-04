package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun MainActivity.fwdLabel(user: String, ink: Int) = tv("Forwarded from ${displayName(user)}", 13.5f, (ink and 0x00FFFFFF) or (0xCC shl 24)).apply {
    setTypeface(typeface, android.graphics.Typeface.ITALIC); setPadding(0, 0, 0, dp(3))
}

/** Text, or an attachment that's on this phone (not one still downloading or cleared). */
internal fun MainActivity.canForward(m: Msg) = m.call == null && m.state != Msg.NOTE && m.file?.optBoolean("vo") != true &&
    (m.file?.has("local") ?: m.text.isNotEmpty())

/** Pick a chat to forward [m] to; it keeps who first wrote it. Then opens that chat. */
internal fun MainActivity.forwardPicker(m: Msg, author: String) {
    val from = m.fwd?.takeUnless { it.startsWith("sms:") } ?: author
    chatPicker("Forward to…") { t ->
        Messenger.run({ Messenger.forward(m, from, t as? Contact, t as? Group) }, {
            stick = true
            if (t is Contact) showChat(t.user) else showGroup((t as Group).id)
        }) { e -> toast(errorText(e)) }
    }
}

/** Something shared from another app ("Share" > Tellem): pick a chat, then send the photos/videos/files and text there. */
internal fun MainActivity.shared(i: Intent) {
    if (!Messenger.signedIn) { toast("Log in to $APP_NAME first, then share again"); return }
    @Suppress("DEPRECATION")
    val uris = if (i.action == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(i.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (uris.isEmpty() && text.isEmpty()) return
    setIntent(Intent(this, MainActivity::class.java))   // don't share it again on rotate/reopen
    val what = when {
        uris.size > 1 -> "${uris.size} items"
        uris.size == 1 -> contentResolver.getType(uris[0]).orEmpty().let { if (it.startsWith("image/")) "photo" else if (it.startsWith("video/")) "video" else "file" }
        else -> "message"
    }
    chatPicker("Send $what to…") { t ->
        attachTo = (t as? Contact)?.user; attachGroupId = (t as? Group)?.id
        uris.forEach { attachPicked(it, asPhoto = false) }
        if (text.isNotEmpty()) { if (t is Contact) Messenger.send(t, text) else Messenger.sendGroup(t as Group, text) }
        stick = true
        if (t is Contact) showChat(t.user) else showGroup((t as Group).id)
    }
}

/** A pop-up list of chats and groups you can write to, newest first; [pick] gets a Contact or a Group. */
internal fun MainActivity.chatPicker(title: String, pick: (Any) -> Unit) {
    val core = Messenger.core
    val me = core.me?.user ?: ""
    val targets = synchronized(core) {
        core.contacts.filter { it.canChat }.map { Triple(it.name, it.lastTs, it as Any) } +
            core.groups.filter { it.canPost(me) }.map { Triple(it.name, it.lastTs, it as Any) }
    }.sortedByDescending { it.second }
    if (targets.isEmpty()) { toast("No chats yet: add a friend first"); return }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val d = android.app.Dialog(this)
    for ((name, _, t) in targets) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); background = pressable()
        addView(avatar(name, 40, (t as? Contact)?.avatar))
        addView(tv(name, 16f, white).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        setOnClickListener { d.dismiss(); pick(t) }
    })
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(bar, 4); setPadding(0, dp(14), 0, dp(6))
        addView(tv(title, 17.5f, white, true).apply { setPadding(dp(20), 0, dp(20), dp(8)) })
        addView(col)
    }
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    d.setContentView(ScrollView(this).apply { addView(wrap) })
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
    d.show()
}
