package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// GIF search, recent GIFs and sending/resending them.

// ---------- GIFs ----------

internal fun MainActivity.isGif(m: Msg) = m.file?.optString("m") == "image/gif"

/** GIFs and (possibly animated) WebP stickers both play in place. */
internal fun MainActivity.isAnimated(m: Msg) = m.file?.optString("m").let { it == "image/gif" || it == "image/webp" }

/** A GIF / sticker / picture chosen from the phone keyboard's own panel: send it to this chat. */
internal fun MainActivity.keyboardContent(uri: android.net.Uri, mime: String, done: () -> Unit, target: Any) {
    stick = true
    Messenger.run({
        val data = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } finally { done() }
            ?: throw UserError(T("Couldn't read that from the keyboard"))
        if (data.size > Core.MAX_FILE) throw UserError(T("That's too big to send"))
        val animated = mime == "image/gif" || mime == "image/webp"
        val bmp = decodeSmall(data, 1600)
        val (name, type, bytes) = if (animated) Triple(if (mime == "image/gif") "animation.gif" else "sticker.webp", mime, data)
            else Triple("photo.jpg", "image/jpeg", jpeg(bmp ?: throw UserError(T("Couldn't open that picture")), 1600, 85))
        val thumb = bmp?.let { thumbOf(it) }
        when (target) {
            is Contact -> Messenger.sendFile(target, name, type, bytes, thumb)
            is Group -> Messenger.sendGroupFile(target, name, type, bytes, thumb)
        }
    }, {}) { e -> toast(errorText(e)) }
}

/** GIFs already on this phone (sent or received in any chat), newest first, no repeats. */
internal fun MainActivity.recentGifs(): List<Msg> {
    val core = Messenger.core
    return synchronized(core) { core.contacts.flatMap { it.msgs } + core.groups.flatMap { it.msgs } }
        .filter { isAnimated(it) && it.file?.has("local") == true }
        .sortedByDescending { it.ts }
        .distinctBy { it.file?.optString("n") to it.file?.optInt("s") }
        .take(30)
}

/** Decodes [bytes] as a playing GIF (Android 9+), or its first frame on older phones. Off the main thread. */
internal fun MainActivity.decodeGif(bytes: ByteArray, maxSide: Int): android.graphics.drawable.Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > maxSide) d.setTargetSampleSize(big / maxSide + 1)
        }
    } else decodeSmall(bytes, maxSide)?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
}.getOrNull()

/** Puts message [m]'s GIF into [iv], playing (decoded in the background the first time). */
internal fun MainActivity.playGif(iv: ImageView, m: Msg, maxSide: Int) {
    fun show(d: android.graphics.drawable.Drawable) {
        iv.setImageDrawable(d)
        if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) d.start()
    }
    gifCache[m.id]?.let { return show(it) }
    var d: android.graphics.drawable.Drawable? = null
    Messenger.run({ d = Messenger.core.openFile(m)?.let { decodeGif(it, maxSide) } }, {
        d?.let { gifCache[m.id] = it; if (iv.isAttachedToWindow) show(it) }
    })
}

/** The GIF keyboard: search GIPHY (through our server, so GIPHY never sees you), trending, your recent GIFs. */
internal fun MainActivity.showGifTab(grid: LinearLayout, scroll: ScrollView, target: Any) {
    grid.removeAllViews()
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val search = EditText(this).apply {
        hint = T("Search GIFs"); setHintTextColor(dim); setTextColor(white); textSize = 15f
        background = GradientDrawable().apply { setColor(panel2); cornerRadius = dp(4).toFloat() }
        setPadding(dp(12), dp(8), dp(12), dp(8)); isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // the panel normally can't take typing (so your message box keeps the keyboard); allow it while searching
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) emojiPopup?.let { if (!it.isFocusable) { it.isFocusable = true; it.update() } }
            false
        }
    }
    grid.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(2), dp(4))
        addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv("＋", 20f, white).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(4), dp(10), dp(4)); background = pressable(); isClickable = true
            setOnClickListener {   // a GIF saved on your phone
                emojiPopup?.dismiss(); emojiPopup = null
                if (target is Contact) { attachTo = target.user; attachGroupId = null } else if (target is Group) { attachGroupId = target.id; attachTo = null }
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/gif")
                @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_FILE)
            }
        })
    }, LinearLayout.LayoutParams(-1, -2))
    grid.addView(results, LinearLayout.LayoutParams(-1, -2))
    grid.addView(tv(T("Powered by GIPHY"), 11f, faint).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(4)) }, LinearLayout.LayoutParams(-1, -2))

    var gen = 0
    fun cell(row: LinearLayout, fill: (ImageView) -> Unit) = row.addView(ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(panel2); fill(this)
    }, LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    fun rows(into: LinearLayout, n: Int, each: (LinearLayout, Int) -> Unit) {
        var row: LinearLayout? = null
        for (i in 0 until n) {
            if (i % 3 == 0) row = LinearLayout(this).also { into.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            each(row!!, i)
        }
        if (n % 3 != 0) repeat(3 - n % 3) { row?.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
    }
    fun label(t: String) = tv(t, 12.5f, dim, true).apply { setPadding(dp(6), dp(8), 0, dp(4)) }

    fun load(q: String, offset: Int) {
        val my = ++gen
        if (offset == 0) {
            results.removeAllViews()
            if (q.isEmpty()) {
                val recent = recentGifs()
                if (recent.isNotEmpty()) {
                    results.addView(label("RECENT"))
                    rows(results, minOf(recent.size, 6)) { row, i -> cell(row) { iv ->
                        playGif(iv, recent[i], dp(140))
                        iv.setOnClickListener { emojiPopup?.dismiss(); emojiPopup = null; resendGif(recent[i], target) }
                    } }
                }
                results.addView(label("TRENDING"))
            }
        }
        val loading = tv(T("Loading GIFs…"), 13.5f, dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)) }
        results.addView(loading, LinearLayout.LayoutParams(-1, -2))
        var found: List<Pair<Relay.Gif, android.graphics.drawable.Drawable?>> = emptyList()
        Messenger.run({
            found = Messenger.core.relay.gifs(q, offset).map { it to decodeGif(it.preview, dp(140)) }
        }, {
            if (my != gen) return@run   // a newer search replaced this one
            results.removeView(loading)
            if (found.isEmpty() && offset == 0) results.addView(tv(if (q.isEmpty()) T("No GIFs right now") else T("No GIFs for \"$q\""), 14f, dim).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16))
            }, LinearLayout.LayoutParams(-1, -2))
            rows(results, found.size) { row, i -> cell(row) { iv ->
                val (g, d) = found[i]
                d?.let { iv.setImageDrawable(it); if (Build.VERSION.SDK_INT >= 28 && it is android.graphics.drawable.AnimatedImageDrawable) it.start() }
                iv.setOnClickListener { emojiPopup?.dismiss(); emojiPopup = null; sendGiphy(g, target) }
            } }
            if (found.size >= 24) results.addView(tv(T("More"), 14f, white, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); background = pressable(); isClickable = true
                setOnClickListener { results.removeView(this); load(q, offset + found.size) }
            }, LinearLayout.LayoutParams(-1, -2))
        }) { e ->
            if (my != gen) return@run
            loading.text = if (e is Relay.RelayError) errorText(e) else T("Couldn't load GIFs. Check your connection.")
        }
    }
    val debounce = Runnable { load(search.text.toString().trim(), 0) }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { search.removeCallbacks(debounce); search.postDelayed(debounce, 700) }
    })
    search.setOnEditorActionListener { _, _, _ -> search.removeCallbacks(debounce); debounce.run(); dismissKeyboard(); true }
    load("", 0)
    scroll.scrollTo(0, 0)
}

/** Fetches the full GIF through our server and sends it. */
internal fun MainActivity.sendGiphy(g: Relay.Gif, target: Any) {
    stick = true
    toast(T("Sending GIF…"))
    Messenger.run({
        val bytes = Messenger.core.relay.gif(g.id)
        val thumb = decodeSmall(bytes, 400)?.let { thumbOf(it) }
        when (target) {
            is Contact -> Messenger.sendFile(target, "giphy.gif", "image/gif", bytes, thumb)
            is Group -> Messenger.sendGroupFile(target, "giphy.gif", "image/gif", bytes, thumb)
        }
    }, {}) { e -> toast(errorText(e)) }
}

/** Sends a GIF that's already on this phone to the open chat or group. */
internal fun MainActivity.resendGif(m: Msg, target: Any) {
    stick = true
    Messenger.run({
        val bytes = Messenger.core.openFile(m) ?: throw UserError(T("Couldn't open that GIF"))
        val thumb = decodeSmall(bytes, 400)?.let { thumbOf(it) }
        val name = m.file?.optString("n")?.ifBlank { null } ?: "animation.gif"
        when (target) {
            is Contact -> Messenger.sendFile(target, name, "image/gif", bytes, thumb)
            is Group -> Messenger.sendGroupFile(target, name, "image/gif", bytes, thumb)
        }
    }, {}) { e -> toast(errorText(e)) }
}
