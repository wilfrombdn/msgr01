package com.wilfrombdn.telegramnt

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

// Saved Messages: hidden until the first save, saving Tellem and text messages into it.

// ---------- saved messages ----------

/** Keep saved messages in the Tellem window only (otherwise they also sit in the Phone window's texts). */
internal fun MainActivity.savedTellemOnly() = UiPrefs.savedTellemOnly(this)

/** The Saved Messages chat, but only once something has really been saved into it. */
internal fun MainActivity.savedWithMessages(): Contact? = synchronized(Messenger.core) {
    Messenger.core.contacts.firstOrNull { it.status == Contact.SAVED && it.msgs.any { m -> m.state != Msg.NOTE } }
}

/** Save a Tellem message (text or a photo/file on this phone) into Saved Messages. */
internal fun MainActivity.saveTellem(m: Msg, author: String) {
    val from = m.fwd ?: author
    Messenger.run({ Messenger.forward(m, from, Messenger.core.savedChat(), null) }, { toast(T("Saved")) }) { e -> toast(errorText(e)) }
}

/** Save a normal text (and its first picture, if any) into Saved Messages; it remembers who it was from. */
internal fun MainActivity.saveText(t: Texts.Text) {
    val who = "sms:" + if (t.mine) T("You") else Texts.name(this, t.from ?: Texts.threadTitle(this, textThread, textPeople))
    Messenger.run({
        val core = Messenger.core
        val saved = core.savedChat()
        val pic = t.attachments.firstOrNull { it.mime.startsWith("image/") }
        val bytes = pic?.let { contentResolver.openInputStream(it.uri)?.use { s -> s.readBytes() } }
        if (pic != null && bytes != null) {
            val bmp = decodeSmall(bytes, 1600)
            val msg = core.queueFile(saved, "text-picture.jpg", "image/jpeg", bmp?.let { jpeg(it, 1600, 85) } ?: bytes, bmp?.let { thumbOf(it) })
            msg.fwd = who; msg.text = t.body
            core.deliver(saved, msg)
        } else {
            val msg = core.queue(saved, t.body); msg.fwd = who
            core.deliver(saved, msg)
        }
    }, { toast(T("Saved")) }) { e -> toast(errorText(e)) }
}

internal fun MainActivity.savedAvatar(sizeDp: Int): View = FrameLayout(this).apply {
    background = circle(blue)
    addView(ImageView(context).apply { setImageResource(R.drawable.ic_bookmark); setColorFilter(Color.WHITE) },
        FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}
