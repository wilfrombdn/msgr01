package com.wilfrombdn.telegramnt

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Full emoji list for choosing any reaction. */
internal fun MainActivity.pickEmoji(onPick: (String) -> Unit) {
    val data = loadEmoji(); if (data.isEmpty()) return
    val cols = 8
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(12)) }
    data.forEach { (header, emojis) ->
        grid.addView(tv(header.substringAfter(' '), 13f, dim).apply { setPadding(dp(6), dp(10), 0, dp(4)) })
        var row: LinearLayout? = null
        emojis.forEachIndexed { i, e ->
            if (i % cols == 0) row = LinearLayout(this).also { grid.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(tv(e, 22f).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)); background = pressable(); isClickable = true
                setOnClickListener { d.dismiss(); onPick(e) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    d.setContentView(ScrollView(this).apply { addView(grid); setBackgroundColor(bar) })
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bar))
    d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), (resources.displayMetrics.heightPixels * 0.68).toInt())
    d.show()
}

/** Reads the bundled emoji list once, keeping only ones this phone's font can actually draw. */
internal fun MainActivity.loadEmoji(): List<Pair<String, List<String>>> {
    emojiData?.let { return it }
    val paint = android.graphics.Paint()
    val out = ArrayList<Pair<String, List<String>>>()
    runCatching {
        resources.openRawResource(R.raw.emoji).bufferedReader().useLines { lines ->
            for (line in lines) {
                val bar = line.indexOf('|'); if (bar < 0) continue
                val header = line.substring(0, bar)                       // e.g. "😀 Smileys"
                val list = line.substring(bar + 1).split(" ").filter { it.isNotEmpty() && paint.hasGlyph(it) }
                if (list.isNotEmpty()) out.add(header to list)
            }
        }
    }
    emojiData = out
    return out
}

/** Telegram-style emoji panel that pops up above the clip and inserts into [box] at the cursor. */
internal fun MainActivity.emojiPicker(anchor: View, box: EditText, target: Any) {
    emojiPopup?.let { it.dismiss(); emojiPopup = null; return }   // tapping the face again closes it
    val data = loadEmoji()
    if (data.isEmpty()) return
    val wPx = resources.displayMetrics.widthPixels
    val cols = (wPx / dp(42)).coerceIn(6, 10)

    val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(6)) }
    val scroll = ScrollView(this).apply { addView(grid); isFillViewport = true }
    fun insert(e: String) {
        val s = box.selectionStart.coerceAtLeast(0); val en = box.selectionEnd.coerceAtLeast(0)
        box.text.replace(minOf(s, en), maxOf(s, en), e)
    }
    fun showTab(emojis: List<String>) {
        grid.removeAllViews()
        var row: LinearLayout? = null
        emojis.forEachIndexed { i, e ->
            if (i % cols == 0) row = LinearLayout(this).also { grid.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(tv(e, 23f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                background = pressable()
                isClickable = true
                setOnClickListener { insert(e); Messenger.useEmoji(e) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        scroll.scrollTo(0, 0)
    }

    val tabs = LinearLayout(this).apply { setBackgroundColor(bar); setPadding(dp(2), 0, dp(2), 0) }
    // GIF tab first: your recent GIFs (sent or received in any chat) + one from your phone
    tabs.addView(tv("GIF", 13f, white, true).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11))
        background = pressable(); isClickable = true
        setOnClickListener { showGifTab(grid, scroll, target) }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    tabs.addView(tv("🖼", 19f).apply {   // my stickers
        gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
        background = pressable(); isClickable = true
        setOnClickListener { showStickerTab(grid, scroll, target) }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    data.forEach { (header, emojis) ->
        tabs.addView(tv(header.substringBefore(' '), 19f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
            background = pressable(); isClickable = true
            setOnClickListener { emojiPopup?.let { if (it.isFocusable) { it.isFocusable = false; it.update() } }; showTab(emojis) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(menuBg)
        addView(tabs, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LinearLayout.LayoutParams(-1, dp(228)))
    }
    showTab(data[0].second)

    val h = dp(228) + dp(42)
    val pw = android.widget.PopupWindow(panel, wPx, h, false)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(menuBg))
    pw.isClippingEnabled = false
    emojiPopup = pw
    val loc = IntArray(2); anchor.getLocationOnScreen(loc)
    pw.showAsDropDown(anchor, -loc[0], -(anchor.height + h + dp(4)))
    // the keyboard opening (e.g. to search GIFs) moves the composer: keep the panel sitting on top of it
    val follow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (pw.isShowing) { anchor.getLocationOnScreen(loc); pw.update(anchor, -loc[0], -(anchor.height + h + dp(4)), wPx, h) }
    }
    anchor.viewTreeObserver.addOnGlobalLayoutListener(follow)
    pw.setOnDismissListener { emojiPopup = null; anchor.viewTreeObserver.removeOnGlobalLayoutListener(follow) }
}
