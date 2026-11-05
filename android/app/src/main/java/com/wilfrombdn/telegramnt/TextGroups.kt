package com.wilfrombdn.telegramnt

import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// Extras for normal (SMS/MMS) group texts and chats: a photo, search, a media gallery, and moving the people to Tellem.
// All of it stays on this phone; the carrier has nowhere to store any of it.

/** A photo was picked for a text group: crop it and keep it on this phone. */
internal fun MainActivity.textGroupPhotoPicked(uri: android.net.Uri) {
    val id = textPhotoFor
    var bmp: android.graphics.Bitmap? = null
    Messenger.run({ bmp = loadPhoto(uri) }, {
        val b = bmp
        val back = { showTextThread(id, textPeople.ifEmpty { null }) }
        if (b == null) { toast("Couldn't open that photo"); back() }
        else showCrop(b, done = { jpeg -> Texts.setGroupPhoto(this, id, jpeg); back() }, cancel = { back() })
    }) { toast("Couldn't open that photo") }
}

internal fun MainActivity.pickTextGroupPhoto() {
    textPhotoFor = textThread
    val i = if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
    else android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")
    @Suppress("DEPRECATION") startActivityForResult(i, PICK_TEXT_GROUP_PHOTO)
}

/** Search the messages of the open text conversation. Tapping a result goes back to the chat at that message. */
internal fun MainActivity.showTextSearch() {
    val id = textThread; val people = textPeople
    screen = Screen.TEXT_TOOLS
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val box = field("Search this conversation")
    col.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(10), dp(14), dp(6)) })
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(results)
    var all: List<Texts.Text> = emptyList()
    var loaded = false
    fun show(q: String) {
        results.removeAllViews()
        if (q.length < 2) { results.addView(tv(if (loaded) "Type at least two letters" else "Loading…", 14f, dim).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }); return }
        val hits = all.filter { it.body.contains(q, ignoreCase = true) }.asReversed().take(100)
        if (hits.isEmpty()) { results.addView(tv("Nothing found", 14f, dim).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }); return }
        for (t in hits) results.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pressable(); setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(tv((if (t.mine) "You" else Texts.name(this@showTextSearch, t.from ?: "?")) + " · " + time(t.date), 13f, dim))
            addView(tv(t.body.replace('\n', ' '), 15.5f, white).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            setOnClickListener { textJump = "${if (t.mms) "m" else "s"}${t.id}:"; showTextThread(id, people.ifEmpty { null }) }
        })
    }
    box.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun afterTextChanged(e: Editable?) { show(e?.toString()?.trim().orEmpty()) }
    })
    show("")
    setContentView(page(topBar("Search", Texts.threadTitle(this, id, people), left = icon(R.drawable.ic_back) { showTextThread(id, people.ifEmpty { null }) }),
        ScrollView(this).apply { addView(col) }))
    Messenger.run({ all = Texts.messages(this, id, 3000); all.mapNotNull { it.from }.forEach { Texts.name(this, it) } }, { loaded = true; show(box.text.toString().trim()) })
    box.requestFocus()
}

/** Every picture and video in the open text conversation, newest first. */
internal fun MainActivity.showTextGallery() {
    val id = textThread; val people = textPeople
    screen = Screen.TEXT_TOOLS
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(2)) }
    col.addView(tv("Loading…", 14f, dim).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) })
    setContentView(page(topBar("Photos & videos", Texts.threadTitle(this, id, people), left = icon(R.drawable.ic_back) { showTextThread(id, people.ifEmpty { null }) }),
        ScrollView(this).apply { addView(col) }))
    var media: List<Texts.Attachment> = emptyList()
    Messenger.run({
        media = Texts.messages(this, id, 3000).asReversed().flatMap { it.attachments }.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }.take(150)
    }, {
        if (screen != Screen.TEXT_TOOLS) return@run
        col.removeAllViews()
        if (media.isEmpty()) { col.addView(tv("No photos or videos in this conversation yet.", 14f, dim).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }); return@run }
        val side = (resources.displayMetrics.widthPixels - dp(12)) / 3
        for (chunk in media.chunked(3)) col.addView(LinearLayout(this).apply {
            for (a in chunk) addView(FrameTile(a, side), LinearLayout.LayoutParams(side, side).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            repeat(3 - chunk.size) { addView(View(this@showTextGallery), LinearLayout.LayoutParams(side, side).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        })
    })
}

private fun MainActivity.FrameTile(a: Texts.Attachment, side: Int): View {
    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(panel2) }
    if (a.mime.startsWith("video/")) {
        return tv("🎬", 28f, white).apply { gravity = Gravity.CENTER; setBackgroundColor(panel2); setOnClickListener { toast("Open videos in your old texting app") } }
    }
    val key = "g" + a.uri.toString()
    textPics.get(key)?.let { iv.setImageBitmap(it) } ?: run {
        var bmp: android.graphics.Bitmap? = null
        Messenger.run({ bmp = contentResolver.openInputStream(a.uri)?.use { it.readBytes() }?.let { decodeSmall(it, 360) } }, { bmp?.let { textPics.put(key, it); iv.setImageBitmap(it) } })
    }
    iv.setOnClickListener {
        var big: android.graphics.Bitmap? = null
        Messenger.run({ big = contentResolver.openInputStream(a.uri)?.use { it.readBytes() }?.let { decodeSmall(it, 2048) } }, {
            big?.let { bm ->
                val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.setContentView(ImageView(this).apply { setImageBitmap(bm); setBackgroundColor(Color.BLACK); setOnClickListener { d.dismiss() } })
                d.show()
            }
        })
    }
    return iv
}

/**
 * "Move to Tellem": makes a private Tellem group with the same name and texts everyone in this group a short invite
 * (what Tellem is, where to get it, and your @username). Nobody is added until they join and you add them as a friend.
 */
internal fun MainActivity.moveTextGroupToTellem() {
    val id = textThread; val people = textPeople
    if (!Messenger.signedIn) { toast("Sign in to Tellem first"); return }
    if (!Texts.isDefault(this)) { toast("Make $APP_NAME your texting app first"); return }
    val title = Texts.threadTitle(this, id, people)
    val me = Messenger.core.me ?: return
    popup("Move this group to Tellem?",
        "This makes a private, encrypted Tellem group called \"$title\" and sends everyone here one text saying how to get Tellem and your username. " +
            "Nobody is added until they join and you add them.", null, "Cancel" to dim, "Move" to white) { b ->
        if (b != 1) return@popup
        var gid: String? = null
        Messenger.run({
            gid = Messenger.core.createGroup(title.take(60), false).id
            Messenger.kick()
            Texts.send(this, people, "I'm moving us to Tellem, a private encrypted messenger. Get it at https://wilfrombdn.github.io/ then add me: @${me.user}. I'll add you to \"${title.take(60)}\".")
        }, {
            toast("Tellem group made. Add friends as they join.")
            gid?.let { showGroup(it) }
        }) { e -> toast(errorText(e)) }
    }
}
