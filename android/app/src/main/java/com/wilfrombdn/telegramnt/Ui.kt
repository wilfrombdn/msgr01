package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Shared look and view helpers: dimensions, text/icon/button builders, top bar, dialogs, toasts, the call bar.

internal fun MainActivity.texturedBg(base: Int): android.graphics.drawable.Drawable {
    val bmp = noiseTiles.getOrPut(base) {
        val size = 180
        val r = Color.red(base); val g = Color.green(base); val b = Color.blue(base)
        val rnd = java.util.Random(7)   // fixed seed = the texture is the same every launch
        val px = IntArray(size * size) {
            val d = rnd.nextInt(21) - 10 // ±10 brightness jitter: a subtle grain that still reads as one colour
            Color.rgb((r + d).coerceIn(0, 255), (g + d).coerceIn(0, 255), (b + d).coerceIn(0, 255))
        }
        android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }
    return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
        setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
    }
}

internal fun MainActivity.wallBg() = texturedBg(wall)

internal fun MainActivity.applyBars() {
    window.statusBarColor = bar
    window.navigationBarColor = listBg
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = if (dark) 0 else
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
}

/**
 * Redraw once for a burst of change notifications. Every message, delivery tick, typing and online event used to rebuild the whole
 * screen on its own; now the ones that arrive together share a single rebuild (no delay: it runs as soon as the screen is free).
 */
internal fun MainActivity.redrawSoon() {
    if (redrawQueued) return
    redrawQueued = true
    callTicker.post { redrawQueued = false; redraw() }
}

internal fun MainActivity.redraw() {
    if (Messenger.locked && Messenger.signedIn) { if (screen != Screen.LOCK) showLock(); return }
    maybeCheckUpdate()
    if (screen == Screen.TEXT_THREAD) return fillTextThread()   // texts work signed in or not
    if (screen == Screen.TEXT_LIST) return showTextList()
    if (screen == Screen.TEXT_SPAM) return showTextSection(textSectionKind)
    if (!Messenger.signedIn) {
        if (screen != Screen.WELCOME && screen != Screen.SIGNUP && screen != Screen.LOGIN && screen != Screen.NEW_TEXT && screen != Screen.ADD_CONTACT) showWelcome()
        return
    }
    when (screen) {
        Screen.CHATS -> if (!dragging) showChats()
        Screen.CHAT -> chatUser?.let { if (Messenger.core.contact(it) != null) fillChat() else showChats() }
        Screen.GROUP -> groupId?.let { if (Messenger.core.group(it) != null) fillGroup() else showChats() }
        else -> {}
    }
}

// ---------- small view helpers ----------

internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Hide the soft keyboard and drop focus (used when you tap off the message field). */
internal fun MainActivity.dismissKeyboard() {
    wantSearchIme = false
    val v = currentFocus ?: window.decorView
    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
    currentFocus?.clearFocus()
}

internal fun MainActivity.tv(s: String, size: Float = 16f, color: Int = white, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size * look.ui
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

internal fun MainActivity.round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

/** A tappable background that lightens while pressed, so rows clearly read as buttons. */
internal fun MainActivity.pressable(pressed: Int = pick("#24FFFFFF", "#14000000")) = android.graphics.drawable.StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(pressed))
    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
}

internal fun MainActivity.circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

internal fun MainActivity.icon(res: Int, color: Int = white, size: Int = 24, pad: Int = 12, onClick: (() -> Unit)? = null) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(color)
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    layoutParams = LinearLayout.LayoutParams(dp(size + pad * 2), dp(size + pad * 2))
    onClick?.let { f -> setOnClickListener { f() }; isClickable = true }
}

/** Profile photo if there is one, otherwise coloured initials like Telegram. */
internal fun MainActivity.avatar(name: String, sizeDp: Int, photo: ByteArray? = null, seed: String? = null): View {
    val bmp = photo?.let { p ->
        val key = p.contentHashCode()
        photoCache[key] ?: safeBitmap(p, 512)?.also { photoCache[key] = it }
    }
    if (bmp != null) return ImageView(this).apply {
        setImageBitmap(bmp)
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
    if (seed != null) return View(this).apply {
        background = BlobAvatar(seed)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
    val initials = name.removePrefix("@").split(" ", "_").filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    return tv(initials, sizeDp / 2.6f, white, true).apply {
        gravity = Gravity.CENTER
        background = circle(avatarColors[Math.floorMod(name.hashCode(), avatarColors.size)])
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

internal fun MainActivity.mainButton(s: String, onClick: () -> Unit) = tv(s, 16f, ink, true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = round(white, 10)
    setOnClickListener { onClick() }
}

/** A primary button sized to its label and centred, rather than stretched edge to edge. */
internal fun MainActivity.cta(s: String, onClick: () -> Unit) = mainButton(s, onClick).apply { setPadding(dp(40), dp(13), dp(40), dp(13)) }

internal fun MainActivity.ctaParams() = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }

/**
 * The app's text box: a rounded, softly filled box (no thin line edge to edge). Stacked boxes get a small gap from the inset.
 * [boxed] = false keeps a bare field for places that draw their own background (the search bar).
 */
internal fun MainActivity.field(hint: String, value: String = "", password: Boolean = false, boxed: Boolean = true) = EditText(this).apply {
    setText(value)
    this.hint = hint
    setHintTextColor(dim)
    setTextColor(white)
    textSize = 17f * look.ui
    if (boxed) {
        backgroundTintList = null
        background = android.graphics.drawable.InsetDrawable(round(panel2, 12), 0, dp(5), 0, dp(5))
        setPadding(dp(14), dp(14), dp(14), dp(14))
    } else backgroundTintList = android.content.res.ColorStateList.valueOf(white)
    inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    typeface = Typeface.DEFAULT // password fields default to monospace
}

internal fun MainActivity.usernameField(hint: String) = field(hint).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    typeface = Typeface.DEFAULT
}

internal fun MainActivity.topBar(title: CharSequence, sub: CharSequence? = null, left: View? = null, vararg right: View) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(bar)
    setPadding(dp(4), dp(4), dp(4), dp(4))
    minimumHeight = dp(56)
    left?.let { addView(it) } ?: addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
    val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
    titles.addView(tv("", 19f, white, true).apply { text = title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    sub?.let { titles.addView(tv("", 13.5f, dim).apply { text = it; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
    addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
    right.forEach { addView(it) }
}

internal fun MainActivity.page(top: View, body: View, bg: Int = listBg): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = texturedBg(bg)
    fitsSystemWindows = true
    addView(callBarView())
    addView(top)
    addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
}

/** Telegram-style green bar while a call is going (top of every screen): tap it to get back to the call. */
internal fun MainActivity.callBarView() = tv("", 14f, Color.WHITE, true).apply {
    gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#3CC261")); setPadding(dp(12), dp(9), dp(12), dp(9))
    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    setOnClickListener { startActivity(Intent(this@callBarView, CallActivity::class.java)) }
}.also { callBar = it; updateCallBar() }

internal fun MainActivity.updateCallBar() {
    val b = callBar ?: return
    val k = Messenger.core.call
    if (k == null || k.phase == Core.Call.ENDED) { b.visibility = View.GONE; return }
    val name = Messenger.core.contact(k.user)?.name ?: "@${k.user}"
    b.text = "$name · ${CallActivity.statusText(k, Messenger.connected)} · Tap to return"
    b.visibility = View.VISIBLE
}

internal fun MainActivity.gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

/** A short message at the bottom in the app's own style (replaces Android's grey toast). */
internal fun MainActivity.toast(s: String) {
    val root = window.decorView as? FrameLayout ?: return
    notice?.let { root.removeView(it) }
    val v = tv(s, 14.5f, white).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply { setColor(menuBg); setStroke(dp(1), divider); cornerRadius = dp(4).toFloat() }
        elevation = dp(8).toFloat()
        alpha = 0f; translationY = dp(12).toFloat()
    }
    notice = v
    root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        setMargins(dp(24), 0, dp(24), dp(92))
    })
    v.animate().alpha(1f).translationY(0f).setDuration(160).start()
    v.postDelayed({
        v.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(v); if (notice === v) notice = null }.start()
    }, 2600)
}

internal fun MainActivity.time(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (today) "HH:mm" else "MMM d", Locale.getDefault()).format(Date(ts))
}

/** "typing…" / "online" (blue) or "last seen …" under a friend's name, or null if they don't share it. */
internal fun MainActivity.presence(c: Contact): CharSequence? {
    if (c.status != Contact.FRIENDS) return null
    val now = System.currentTimeMillis()
    fun blueText(t: String) = android.text.SpannableString(t).apply { setSpan(android.text.style.ForegroundColorSpan(blue), 0, t.length, 0) }
    return when {
        c.theirTypingUntil > now -> blueText("typing…")
        c.theirOnlineUntil > now -> blueText("online")
        c.theirSeen > 0 -> "last seen " + lastSeen(c.theirSeen, now)
        else -> null
    }
}

internal fun MainActivity.lastSeen(ts: Long, now: Long): String {
    val mins = (now - ts) / 60_000
    val day = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    return when {
        mins < 1 -> "just now"
        mins < 60 -> if (mins == 1L) "1 minute ago" else "$mins minutes ago"
        day(ts) == day(now) -> "today at $clock"
        day(ts) == day(now - 86_400_000) -> "yesterday at $clock"
        else -> "on " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
    }
}

internal fun MainActivity.errorText(e: Exception) = when (e) {
    is UserError -> e.message ?: "Something went wrong"
    is Relay.RelayError -> e.reason.ifEmpty { "Server error" }.replaceFirstChar { it.uppercase() }
    else -> "No connection right now. Try again in a moment."
}

/** A simple dark list dialog (used where there are more than a few choices). */
internal fun MainActivity.pickList(title: String, sub: String?, items: Array<String>, onPick: (Int) -> Unit) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    items.forEachIndexed { i, label ->
        col.addView(tv(label, 15.5f, white).apply { setPadding(dp(24), dp(13), dp(24), dp(13)); background = pressable(); setOnClickListener { listDialog?.dismiss(); onPick(i) } })
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground()
        setPadding(0, dp(22), 0, dp(12))
        addView(tv(title, 19f, white, true).apply { setPadding(dp(24), 0, dp(24), if (sub == null) dp(8) else dp(2)) })
        sub?.let { addView(tv(it, 14.5f, dim).apply { setPadding(dp(24), 0, dp(24), dp(8)) }) }
        addView(gap(4))
    }
    wrap.addView(col)
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    d.setContentView(ScrollView(this).apply { addView(wrap) })
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.88).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
    listDialog = d
    presentDialog(d, wrap)
}

/** A plain dark rectangle that drops down right under [anchor]. */
internal fun MainActivity.dropdown(anchor: View, side: Int, vararg items: Pair<String, () -> Unit>) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        background = GradientDrawable().apply { setColor(menuBg); setStroke(dp(1), divider); cornerRadius = dp(16).toFloat() }
    }
    val pw = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    for ((label, action) in items) box.addView(tv(label, 16f, white).apply {
        minWidth = dp(200)
        setPadding(dp(22), dp(14), dp(30), dp(14)); background = pressable()
        setOnClickListener { pw.dismiss(); action() }
    })
    pw.elevation = dp(12).toFloat()
    pw.setBackgroundDrawable(GradientDrawable().apply { setColor(menuBg); setStroke(dp(1), divider); cornerRadius = dp(16).toFloat() })
    pw.showAsDropDown(anchor, 0, 0, side)
}

/** The panel every pop-up sits on: softly rounded, a hairline edge, and it eases in (scale + fade) unless animations are off. */
internal fun MainActivity.panelBackground(radiusDp: Int = 22) = GradientDrawable().apply {
    setColor(bar); setStroke(dp(1), divider); cornerRadius = dp(radiusDp).toFloat()
}

/** Dim the screen behind a dialog a bit more than Android's default and ease the panel in. */
internal fun MainActivity.presentDialog(d: android.app.Dialog, panel: View) {
    d.window?.setDimAmount(0.62f)
    if (animationsOn()) {
        panel.alpha = 0f; panel.scaleX = 0.94f; panel.scaleY = 0.94f
        d.setOnShowListener { panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(SMOOTH).start() }
    }
    d.show()
}

/**
 * The app's own pop-up (instead of Android's): title, optional text or [content], and the buttons as soft pills along the bottom.
 * The main action (white) is filled, red is a gentle red, anything else is quiet. [onButton] gets the index of the button tapped.
 */
internal fun MainActivity.popup(title: String, message: String?, content: View?, vararg buttons: Pair<String, Int>, dialogOut: ((android.app.Dialog) -> Unit)? = null, onButton: (Int) -> Unit = {}) {
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(18))
        background = panelBackground()
        elevation = dp(10).toFloat()
    }
    col.addView(tv(title, 19f, white, true).apply { setPadding(0, 0, 0, dp(8)) })
    message?.let { col.addView(tv(it, 15f, dim).apply { setLineSpacing(0f, 1.18f); setPadding(0, 0, 0, dp(6)) }) }
    content?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    val stacked = buttons.size > 2
    val row = LinearLayout(this).apply { orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
    buttons.forEachIndexed { i, (label, color) ->
        val main = color == white && i == buttons.size - 1          // the action you came here to do
        val fill = when {
            main -> white
            color == red -> (red and 0x00FFFFFF) or (0x2E shl 24)
            color == dim -> pick("#1FFFFFFF", "#12000000")
            else -> (color and 0x00FFFFFF) or (0x2E shl 24)
        }
        val ink2 = if (main) ink else if (color == dim) white else color
        row.addView(tv(label, 15f, ink2, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(26).toFloat() }
            setOnClickListener { d.dismiss(); onButton(i) }
        }, if (stacked) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } else LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10) })
    }
    col.addView(row, LinearLayout.LayoutParams(-1, -2))
    d.setContentView(col)
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.88).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
    dialogOut?.invoke(d)
    presentDialog(d, col)
}

// ---------- settings ----------

/** A tappable settings/profile row: title + optional subtitle, with press feedback. */
internal fun MainActivity.settingRow(title: String, sub: String? = null, color: Int = white, arrow: Boolean = false, onClick: (View) -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(20), dp(14), dp(20), dp(14))
    background = pressable()
    isClickable = true
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(title, 16f, color))
        sub?.let { addView(tv(it, 13.5f, dim)) }
    }, LinearLayout.LayoutParams(0, -2, 1f))
    // a soft arrow on rows that open something, so they read as buttons and not as headings
    if (arrow) addView(tv("›", 24f, dim).apply { setPadding(dp(12), 0, 0, 0); alpha = 0.7f })
    setOnClickListener { v -> onClick(v) }
}

internal fun MainActivity.settingSwitch(label: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
    text = label
    setTextColor(white)
    textSize = 16f * look.ui
    isChecked = on
    val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    thumbTintList = android.content.res.ColorStateList(st, intArrayOf(white, pick("#9AA4AE", "#FFFFFF")))
    trackTintList = android.content.res.ColorStateList(st, intArrayOf((white and 0x00FFFFFF) or (0x80 shl 24), pick("#3A4654", "#C4C4CA")))
    setPadding(dp(20), dp(14), dp(20), dp(14))
    setOnCheckedChangeListener { _, checked -> change(checked) }
}

internal fun MainActivity.settingNote(text: String) = tv(text, 13.5f, dim).apply { setPadding(dp(20), 0, dp(20), dp(10)) }

/** What a person's picture is drawn from when they have no photo: their username, or for a normal text their phone number. */
internal fun avatarSeed(user: String) =
    if (user.startsWith("sms:")) "sms:" + user.drop(4).filter { it.isDigit() }.takeLast(10) else "u:$user"
internal fun numberSeed(number: String) = "sms:" + number.filter { it.isDigit() }.takeLast(10)

/** Is the soft keyboard on screen right now? */
internal fun MainActivity.imeVisible(): Boolean {
    val v = window.decorView
    if (android.os.Build.VERSION.SDK_INT >= 30) return v.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
    val r = android.graphics.Rect(); v.getWindowVisibleDisplayFrame(r)
    return v.rootView.height - r.bottom > dp(120)
}

/** A small buzz for a press-and-hold that worked, only when Appearance → "Vibrate when a hold works" is on. */
internal fun MainActivity.buzz(v: View? = null) {
    if (UiPrefs.haptics(this)) (v ?: window.decorView).performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
}
