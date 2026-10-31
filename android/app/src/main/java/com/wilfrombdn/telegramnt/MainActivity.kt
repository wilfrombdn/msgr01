package com.wilfrombdn.telegramnt

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Telegram-style dark look, every screen built in code (like League Radio). */
class MainActivity : Activity() {
    // dark greys; bubble colours, sizes and corners come from Settings > Appearance
    // Light / Dark / match the phone (Settings > Appearance). Each colour below has a dark and a light value.
    private val dark: Boolean get() = when (look.theme) {
        "light" -> false
        "system" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_NO
        else -> true
    }
    private fun pick(d: String, l: String) = Color.parseColor(if (dark) d else l)
    private val bar get() = pick("#1F1F1F", "#F7F7F8")       // top bars
    private val listBg get() = pick("#141414", "#FFFFFF")    // chat list, inputs
    private val wall get() = pick("#0B0B0B", "#E6E6EA")      // behind messages
    private val menuBg get() = pick("#2A2A2A", "#FFFFFF")    // drop-down menus
    private val raised get() = pick("#3A3A3A", "#D9D9DE")    // highlights, outlines, chip borders
    private val nestedBg get() = pick("#181818", "#F3F3F5")  // chats inside an open folder
    private val look get() = Messenger.look
    private val blue = Color.parseColor("#4EA4F6")      // status only: unread, ticks, requests
    private val ink get() = pick("#111111", "#FFFFFF")       // text on the main (white / dark) buttons
    private val white get() = pick("#FFFFFF", "#111111")     // main text and icons (dark text in light mode)
    private val dim get() = pick("#8E8E93", "#6D6D72")
    private val divider get() = pick("#262626", "#E3E3E6")
    private val faint get() = pick("#5A5A5A", "#B0B0B5")
    private val panel2 get() = pick("#2A2A2A", "#EDEDF0")
    private val red = Color.parseColor("#E5534B")
    private val amber get() = pick("#E8C24A", "#9A6B00")   // gentle yellow for the Tor "first connection is slow" note
    private val avatarColors = listOf("#E17076", "#FAA774", "#A695E7", "#7BC862", "#6EC9CB", "#65AADD", "#EE7AAE")
        .map { Color.parseColor(it) }

    // Backgrounds get a faint, tileable monochrome noise so screens aren't dead-flat — gives the app a
    // bit of a textured, "real surface" feel. Same base colour, just gently grainy. Cached per colour.
    private val noiseTiles = HashMap<Int, android.graphics.Bitmap>()
    private fun texturedBg(base: Int): android.graphics.drawable.Drawable {
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
    private fun wallBg() = texturedBg(wall)

    private var screen = "chats"
    private var chatUser: String? = null
    private var search = ""
    private val refresh: () -> Unit = { redraw() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep chats out of screenshots and the recent-apps preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        applyBars()
        Messenger.init(this)
        SyncService.channels(this)
        File(cacheDir, "open").deleteRecursively() // unlocked copies of opened attachments
        Voice.cleanup(this)                          // a recording cut off when the app was closed
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Messenger.signedIn) reopenSpot() else { showWelcome(); Messenger.connectInBackground() }
        // a notification tap opens that chat; but not again when reopening from recents (keep your place)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
    }

    /** Back to the chat (and the place in it) you had open last time, else the chat list. */
    private fun reopenSpot() {
        val sp = Messenger.spot
        val at = if (sp.atBottom || sp.anchor.isEmpty()) null else sp.anchor to sp.offset
        when {
            sp.screen == "chat" && Messenger.core.contact(sp.id) != null -> showChat(sp.id, at)
            sp.screen == "group" && Messenger.core.group(sp.id) != null -> showGroup(sp.id, at)
            else -> showChats()
        }
    }

    /** Status/navigation bar colours (and dark icons on them in light mode). */
    private fun applyBars() {
        window.statusBarColor = bar
        window.navigationBarColor = listBg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra("chat")?.let { u -> if (Messenger.core.contact(u) != null) showChat(u) }
        i?.getStringExtra("group")?.let { g -> if (Messenger.core.group(g) != null) showGroup(g) }
    }

    override fun onStart() {
        super.onStart()
        maybeCheckUpdate()
        Messenger.visible = true
        Messenger.appShown()
        Messenger.listen(refresh)
        if (Messenger.signedIn) startForegroundService(Intent(this, SyncService::class.java))
        redraw()
    }

    override fun onStop() {
        Voice.cancel()
        saveSpot()
        if (Messenger.signedIn) Messenger.io.execute { Messenger.save() }
        Messenger.visible = false
        Messenger.appHidden()
        Messenger.unlisten(refresh)
        if (!Messenger.background) stopService(Intent(this, SyncService::class.java))
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            emojiPopup != null -> { emojiPopup?.dismiss(); emojiPopup = null }
            (replyTo != null || editing != null) && (screen == "chat" || screen == "group") -> cancelReply()
            drawer?.isDrawerOpen(GravityCompat.START) == true -> drawer?.closeDrawer(GravityCompat.START)
            !Messenger.signedIn && screen != "welcome" -> showWelcome()
            screen == "viewer" -> leaveViewer()
            screen == "storage" -> showSettings()
            screen == "chats" && showArchive -> { showArchive = false; showChats() }
            Messenger.signedIn && screen != "chats" -> showChats()
            search.isNotEmpty() -> { search = ""; showChats() }
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        emojiPopup?.dismiss(); emojiPopup = null
        super.onPause()
    }

    // ---------- in-app updates ----------

    private var updateChecked = 0L
    private var pendingApk: File? = null

    /** Once Tor is up, look for a newer version (at most every 6 hours) and offer it. */
    private fun maybeCheckUpdate() {
        if (Tor.socksPort() == null) return
        if (System.currentTimeMillis() - updateChecked < 10 * 60_000L) return   // every app open, at most every 10 minutes
        updateChecked = System.currentTimeMillis()
        var u: Updater.Update? = null
        Messenger.run({ u = Updater.check(this) }, { u?.let { if (screen != "viewer" && screen != "crop") updatePrompt(it) } }) {
            updateChecked = System.currentTimeMillis() - 10 * 60_000L + 60_000L   // couldn't reach it: try again in a minute
        }
    }

    /** Settings > Check for updates: says exactly what happened. */
    private fun checkUpdateNow() {
        if (Tor.socksPort() == null) { toast("Still connecting to Tor — try again in a moment"); return }
        toast("Checking for updates…")
        var u: Updater.Update? = null
        Messenger.run({ u = Updater.check(this) }, {
            updateChecked = System.currentTimeMillis()
            u?.let { updatePrompt(it) } ?: toast("You're up to date")
        }) { e -> toast("Couldn't check for updates (${e.message ?: e.javaClass.simpleName})") }
    }

    /** "Update available — Yes/No"; Yes opens what's new and an Update button with a progress bar. */
    private fun updatePrompt(u: Updater.Update) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(4))
            background = round(bar, 4)
            layoutTransition = android.animation.LayoutTransition()   // the panel grows smoothly when it expands
        }
        col.addView(tv("Update available", 17.5f, white, true).apply { setPadding(0, 0, dp(8), dp(6)) })
        col.addView(tv("quipu ${u.name} is ready. Update now?", 14.5f, dim).apply { setPadding(0, 0, dp(8), dp(4)) })
        fun buttons(vararg b: Pair<Pair<String, Int>, () -> Unit>) = LinearLayout(this).apply {
            gravity = Gravity.END; setPadding(0, dp(4), 0, 0)
            b.forEach { (lc, act) -> addView(tv(lc.first.uppercase(), 14f, lc.second, true).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { act() } }) }
        }
        val status = tv("", 13.5f, dim).apply { setPadding(0, dp(6), dp(8), 0); visibility = View.GONE }
        val fill = View(this).apply { setBackgroundColor(white); pivotX = 0f; scaleX = 0f }
        val track = FrameLayout(this).apply {
            setBackgroundColor(panel2); visibility = View.GONE
            addView(fill, FrameLayout.LayoutParams(-1, -1))
        }
        lateinit var ask: View
        lateinit var go: View
        fun startUpdate() {
            go.visibility = View.GONE; track.visibility = View.VISIBLE; status.visibility = View.VISIBLE
            status.text = "Downloading… 0%"
            d.setCancelable(false)
            Updater.onStatus = { err -> runOnUiThread {
                if (err != null) { status.text = "Couldn't update: $err"; go.visibility = View.VISIBLE; d.setCancelable(true) }
            } }
            var apk: File? = null
            Messenger.run({
                apk = Updater.download(this, u) { p -> runOnUiThread { fill.scaleX = p; status.text = "Downloading… ${(p * 100).toInt()}%" } }
            }, {
                fill.scaleX = 1f
                status.text = "Installing… quipu will close and reopen."
                val f = apk!!
                if (!packageManager.canRequestPackageInstalls()) {
                    // Android asks once whether quipu may install its own updates
                    pendingApk = f
                    status.text = "Allow quipu to install updates, then come back here."
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
                } else Messenger.run({ Updater.install(this, f) }, {}) { e -> status.text = "Couldn't update: ${e.message}"; go.visibility = View.VISIBLE; d.setCancelable(true) }
            }) { e ->
                status.text = if (e is java.io.IOException) "Download didn't finish (${e.message}). Try again." else errorText(e)
                go.visibility = View.VISIBLE; d.setCancelable(true)
            }
        }
        go = buttons(("Not now" to dim) to { d.dismiss() }, ("Update" to white) to { startUpdate() })
        ask = buttons(("No" to dim) to { d.dismiss() }, ("Yes" to white) to {
            col.removeView(ask)
            col.addView(tv("What's new", 15f, white, true).apply { setPadding(0, dp(8), dp(8), dp(4)) })
            (u.notes.ifEmpty { listOf("Fixes and improvements") }).forEach { n ->
                col.addView(tv("•  $n", 14f, dim).apply { setLineSpacing(0f, 1.06f); setPadding(0, dp(2), dp(8), dp(2)) })
            }
            col.addView(gap(10))
            col.addView(track, LinearLayout.LayoutParams(-1, dp(5)).apply { rightMargin = dp(8) })
            col.addView(status)
            col.addView(go)
        })
        col.addView(ask)
        d.setContentView(ScrollView(this).apply { addView(col) })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    override fun onResume() {
        super.onResume()
        // back from "allow installs from quipu": carry on with the update
        pendingApk?.let { f -> if (packageManager.canRequestPackageInstalls()) { pendingApk = null; Messenger.run({ Updater.install(this, f) }) } }
        if (screen == "settings") showSettings()   // back from a phone setting (battery, full-screen alerts): show what changed
    }

    private fun redraw() {
        maybeCheckUpdate()
        if (!Messenger.signedIn) {
            if (screen != "welcome" && screen != "signup" && screen != "login") showWelcome()
            return
        }
        when (screen) {
            "chats" -> if (!dragging) showChats()
            "chat" -> chatUser?.let { if (Messenger.core.contact(it) != null) fillChat() else showChats() }
            "group" -> groupId?.let { if (Messenger.core.group(it) != null) fillGroup() else showChats() }
        }
    }

    // ---------- small view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Hide the soft keyboard and drop focus (used when you tap off the message field). */
    private fun dismissKeyboard() {
        val v = currentFocus ?: window.decorView
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun tv(s: String, size: Float = 16f, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size * look.ui
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    /** A tappable background that lightens while pressed, so rows clearly read as buttons. */
    private fun pressable(pressed: Int = pick("#24FFFFFF", "#14000000")) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(pressed))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun icon(res: Int, color: Int = white, size: Int = 24, pad: Int = 12, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(dp(size + pad * 2), dp(size + pad * 2))
        onClick?.let { f -> setOnClickListener { f() }; isClickable = true }
    }

    private val photoCache = HashMap<Int, android.graphics.Bitmap?>()

    /** Profile photo if there is one, otherwise coloured initials like Telegram. */
    private fun avatar(name: String, sizeDp: Int, photo: ByteArray? = null): View {
        val bmp = photo?.let { p ->
            photoCache.getOrPut(p.contentHashCode()) { android.graphics.BitmapFactory.decodeByteArray(p, 0, p.size) }
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
        val initials = name.removePrefix("@").split(" ", "_").filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
        return tv(initials, sizeDp / 2.6f, white, true).apply {
            gravity = Gravity.CENTER
            background = circle(avatarColors[Math.floorMod(name.hashCode(), avatarColors.size)])
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun mainButton(s: String, onClick: () -> Unit) = tv(s, 16f, ink, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = round(white, 10)
        setOnClickListener { onClick() }
    }

    /** A primary button sized to its label and centred, rather than stretched edge to edge. */
    private fun cta(s: String, onClick: () -> Unit) = mainButton(s, onClick).apply { setPadding(dp(40), dp(13), dp(40), dp(13)) }
    private fun ctaParams() = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(dim)
        setTextColor(white)
        textSize = 17f * look.ui
        backgroundTintList = android.content.res.ColorStateList.valueOf(white)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.DEFAULT // password fields default to monospace
    }

    private fun usernameField(hint: String) = field(hint).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        typeface = Typeface.DEFAULT
    }

    private fun topBar(title: CharSequence, sub: CharSequence? = null, left: View? = null, vararg right: View) = LinearLayout(this).apply {
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

    private fun page(top: View, body: View, bg: Int = listBg): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = texturedBg(bg)
        fitsSystemWindows = true
        addView(top)
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private var notice: TextView? = null

    /** A short message at the bottom in the app's own style (replaces Android's grey toast). */
    private fun toast(s: String) {
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

    private fun time(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "MMM d", Locale.getDefault()).format(Date(ts))
    }

    /** "typing…" / "online" (blue) or "last seen …" under a friend's name, or null if they don't share it. */
    private fun presence(c: Contact): CharSequence? {
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

    private fun lastSeen(ts: Long, now: Long): String {
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

    private fun errorText(e: Exception) = when (e) {
        is UserError -> e.message ?: "Something went wrong"
        is Relay.RelayError -> e.reason.ifEmpty { "Server error" }.replaceFirstChar { it.uppercase() }
        else -> "No connection right now. Try again in a moment."
    }

    // ---------- welcome, sign up, log in ----------

    private fun showWelcome() {
        screen = "welcome"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        // the white bubble from the icon (no black square) with the name beside it
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_bubble_logo) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(tv("quipu", 34f, white, true).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(dp(12), 0, 0, 0) })
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(gap(18))
        col.addView(tv("The world's most private messaging app.\nEvery chat is end-to-end encrypted.\nNo phone number or email needed.", 16f, dim).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        })
        Messenger.signedOutReason?.let {
            col.addView(gap(16))
            col.addView(tv(it, 14f, red).apply { gravity = Gravity.CENTER })
        }
        col.addView(gap(40))
        col.addView(cta("Start Messaging") { connectThen { showLogin() } }, ctaParams())
        col.addView(gap(14))
        col.addView(tv("I don't have an account", 16f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { connectThen { showSignup() } }
        })
        col.addView(tv("Restore from a backup file", 15f, dim).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { pickRecoveryToRestore() }
        })
        col.addView(gap(24))
        welcomeStatus = tv("", 15f, dim, true).apply { gravity = Gravity.CENTER }
        col.addView(welcomeStatus)
        welcomeTorNote = tv(TOR_WAIT_NOTE, 13.5f, amber).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(dp(12), dp(14), dp(12), 0)
            visibility = View.GONE
        }
        col.addView(welcomeTorNote)
        // what Tor is doing right now, and a way out if Tor can't get through (the Settings menu needs an account)
        welcomeTorLine = tv("", 13f, dim).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), 0); visibility = View.GONE }
        col.addView(welcomeTorLine)
        welcomeDirect = tv("", 15f, white, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(8)); visibility = View.GONE
            setOnClickListener { Messenger.useDirect(!Messenger.direct) }
        }
        col.addView(welcomeDirect)
        setContentView(ScrollView(this).apply {
            background = texturedBg(listBg)
            fitsSystemWindows = true
            isFillViewport = true
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
        })
        celebrating = false
        startTicker()
    }

    /** Welcome-screen connection line: "Connecting." → ".." → "..." once a second; then a green
     *  "Connected!" for a second before moving on. The slow-Tor note only shows after 30 s of trying. */
    private var connectStart = 0L
    private var dots = 0
    private var celebrating = false
    private val green = Color.parseColor("#4CD964")
    private val ticker = object : Runnable {
        override fun run() {
            val st = welcomeStatus ?: return
            if (screen !in listOf("welcome") || celebrating) return
            if (Messenger.connected) {
                welcomeTorNote?.visibility = View.GONE; welcomeTorLine?.visibility = View.GONE; welcomeDirect?.visibility = View.GONE
                if (st.text.isEmpty()) return   // already connected when the screen opened: nothing to show
                celebrating = true
                st.text = "Connected!"; st.setTextColor(green)
                st.postDelayed({
                    celebrating = false
                    st.text = ""; st.setTextColor(dim)
                    if (screen == "welcome") pendingNext?.let { pendingNext = null; it() }
                }, 1000)
                return
            }
            if (connectStart == 0L) connectStart = System.currentTimeMillis()
            dots = dots % 3 + 1
            // always three dots wide (the unused ones invisible) so the centred word doesn't wobble
            st.text = android.text.SpannableString("Connecting...").apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.TRANSPARENT), 10 + dots, 13, 0)
            }
            st.setTextColor(dim)
            if (System.currentTimeMillis() - connectStart >= 30_000) {
                welcomeTorNote?.visibility = if (Messenger.direct) View.GONE else View.VISIBLE
                welcomeTorLine?.apply { visibility = View.VISIBLE; text = if (Messenger.direct) "Trying a direct connection…" else Tor.status() }
                welcomeDirect?.apply { visibility = View.VISIBLE; text = if (Messenger.direct) "Use Tor instead" else "Connect directly instead" }
            }
            st.postDelayed(this, 1000)
        }
    }

    private fun startTicker() {
        val st = welcomeStatus ?: return
        if (Messenger.connected || celebrating) return
        st.removeCallbacks(ticker)   // never two loops at once
        if (st.text.isEmpty()) { dots = 0; st.text = "Connecting" }
        ticker.run()
    }

    private var welcomeStatus: TextView? = null
    private var welcomeTorNote: TextView? = null
    private var welcomeTorLine: TextView? = null
    private var welcomeDirect: TextView? = null
    private val TOR_WAIT_NOTE = "First connection can take a minute. The app is building a private route through the Tor network — hopping your connection through several relays around the world and setting up fresh encryption at each one. That's slower than a normal connection, but it's what hides where you are. It's quicker after this."
    private var pendingNext: (() -> Unit)? = null

    /** The server is found automatically; if it isn't reachable yet, carry on once it is. */
    private fun connectThen(next: () -> Unit) {
        if (Messenger.connected) return next()
        pendingNext = next
        Messenger.connectInBackground()
        startTicker()
    }

    private fun accountForm(title: String, signup: Boolean) {
        screen = if (signup) "signup" else "login"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        col.addView(tv(if (signup) "Your Account" else "Log In", 24f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(8))
        col.addView(tv(if (signup) "Pick a username. Friends find you with it." else "Enter your username and password.", 15f, dim)
            .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(20))
        val name = field("Your name")
        if (signup) col.addView(name, LinearLayout.LayoutParams(-1, -2))
        val user = usernameField("Username")
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv("@", 17f, dim))
            addView(user, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        val pass = field("Password", password = true)
        col.addView(pass, LinearLayout.LayoutParams(-1, -2))
        val pass2 = field("Repeat password", password = true)
        if (signup) col.addView(pass2, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(14))
        col.addView(tv(
            if (signup) "No phone number or email. Use at least 10 characters. If you forget your password, nobody can get your account back, not even us."
            else "Logging in here signs you out on your other phone. Your friends come back, but old messages stay on the old phone.",
            13.5f, dim,
        ).apply { setLineSpacing(0f, 1.15f); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(24))
        val go = cta(if (signup) "Create Account" else "Log In") {}
        go.setOnClickListener {
            val u = user.text.toString().trim().removePrefix("@").lowercase()
            val p = pass.text.toString()
            if (signup && p != pass2.text.toString()) { toast("The passwords don't match"); return@setOnClickListener }
            dismissKeyboard()
            go.text = if (signup) "Making your keys…" else "Logging in…"
            go.isEnabled = false
            Messenger.run({
                if (signup) Messenger.core.signup(u, p, name.text.toString()) else Messenger.logIn(u, p)
            }, {
                Messenger.signedOutReason = null
                startForegroundService(Intent(this, SyncService::class.java))
                showChats()
            }) { e ->
                go.text = if (signup) "Create Account" else "Log In"
                go.isEnabled = true
                toast(errorText(e))
            }
        }
        col.addView(go, ctaParams())
        setContentView(page(topBar(title, left = icon(R.drawable.ic_back) { showWelcome() }), ScrollView(this).apply {
            isFillViewport = true
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
        }))
    }

    private fun showSignup() = accountForm("Sign Up", signup = true)
    private fun showLogin() = accountForm("Log In", signup = false)

    // ---------- chat list ----------

    // ---------- ringing when the app is closed ----------

    /**
     * What the phone still needs so a call rings like a normal phone call when quipu is closed:
     * each item is (what, why, fix). Empty = all set.
     */
    private fun ringFixes(): List<Triple<String, String, () -> Unit>> {
        val out = mutableListOf<Triple<String, String, () -> Unit>>()
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!Messenger.background) out.add(Triple("Get messages when the app is closed", "Without it, nothing can reach this phone while quipu is closed.") {
            Messenger.background = true; Messenger.settingsChanged(); startForegroundService(Intent(this, SyncService::class.java)); showSettings()
        })
        if (!nm.areNotificationsEnabled()) out.add(Triple("Notifications", "Calls and messages show as notifications.") {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        })
        if (!getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) out.add(Triple("Battery: unrestricted",
            "Otherwise Android cuts quipu's internet when the screen is off, and calls never arrive.") {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }.onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out.add(Triple("Full-screen call alerts",
            "Lets an incoming call take over the screen (even when locked), like a normal phone call.") {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")))
        })
        return out
    }

    /** Once, after signing in: offer to set the phone up so calls ring when quipu is closed. */
    private fun maybeAskRingSetup() {
        if (Messenger.ringSetupAsked || !Messenger.signedIn) return
        val fixes = ringFixes()
        Messenger.ringSetupAsked = true; Messenger.settingsChanged()
        if (fixes.isEmpty()) return
        popup("Ring like a phone call", "So calls ring this phone even when quipu is closed, allow: " + fixes.joinToString(", ") { it.first } +
            ". You can do this later in Settings > Calls.", null, "Later" to dim, "Set up" to white) { i -> if (i == 1) showSettings() }
    }

    private fun showChats() {
        val refreshing = screen == "chats"   // same screen redrawing (new message, reconnect), not arriving from elsewhere
        if (!refreshing) window.decorView.post { maybeAskRingSetup() }
        emojiPopup?.dismiss(); emojiPopup = null
        if (screen != "chats") Messenger.spot = Spot()
        screen = "chats"
        chatUser = null
        Messenger.core.openChat = null
        val core = Messenger.core
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val q = search.lowercase()
        val all = core.sorted().filter { q.isEmpty() || it.name.lowercase().contains(q) || it.user.contains(q) }
        val grps = synchronized(core) { core.groups.toList() }.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        val requests = all.filter { it.status == Contact.ASKING }
        // everything else, keyed "u:<user>" / "g:<group>" so folders can hold either
        val rows = (all - requests.toSet()).map { Triple("u:${it.user}", it.lastTs, it as Any) } + grps.map { Triple("g:${it.id}", it.lastTs, it as Any) }
        fun rowFor(t: Triple<String, Long, Any>, bg: Int = listBg) = (t.third as? Contact)?.let { chatRow(it, t.first, bg) } ?: groupRow(t.third as Group, t.first, bg)
        val nested = nestedBg   // chats inside an open folder sit on a slightly different strip
        val byKey = rows.associateBy { it.first }
        val inFolder = HashSet<String>()
        val arch = Messenger.archived
        if (q.isEmpty()) arch.retainAll { it in byKey || it.startsWith("u:") && core.contact(it.drop(2)) != null }   // forget deleted chats
        if (showArchive && q.isEmpty()) {
            // the Archived chats page: just those, newest first
            for (t in rows.filter { it.first in arch }.sortedByDescending { it.second }) list.addView(rowFor(t))
        } else {
        // pinned chats: at the very top, above folders, in the order you pinned them
        val pins = Messenger.pins
        pins.retainAll { it in byKey || it.startsWith("u:") && core.contact(it.drop(2)) != null }   // forget deleted chats
        pinnedKeys = pins.toSet()
        for (k in pins) byKey[k]?.let { list.addView(rowFor(it)) }
        inFolder += pins
        for (c in requests) list.addView(chatRow(c))   // friend requests right under the pins
        if (q.isEmpty()) {
            inFolder += arch   // archived chats only show on their own page
            val archRows = rows.filter { it.first in arch && it.first !in pinnedKeys }
            if (archRows.isNotEmpty()) list.addView(archiveRow(archRows.sumOf { (it.third as? Contact)?.unread ?: (it.third as Group).unread }, archRows.size))
            val fs = Messenger.folders
            fs.forEach { f -> f.items.retainAll { it in byKey || it.startsWith("u:") && core.contact(it.drop(2)) != null } }   // forget deleted chats
            for (f in fs) {
                val members = f.items.filter { it !in pinnedKeys && it !in arch }.mapNotNull { byKey[it] }.sortedByDescending { it.second }
                inFolder += f.items
                list.addView(folderRow(f, members.sumOf { (it.third as? Contact)?.unread ?: (it.third as Group).unread }, members.size))
                if (f.open) for (t in members) list.addView(rowFor(t, nested).apply {
                    tag = "in:${f.id}"
                    setPadding(dp(14), 0, 0, 0)
                    setBackgroundColor(nested)
                })
            }
        }
        for (t in rows.filter { it.first !in inFolder }.sortedByDescending { it.second }) list.addView(rowFor(t))
        }
        if (q.length >= 2) messageResults(list, q)
        if (all.isEmpty() && grps.isEmpty() && (q.length < 2 || list.childCount == 0)) {
            list.addView(tv(
                if (q.isNotEmpty()) "No chats match \"$search\""
                else "Welcome, ${core.me?.name}!\n\nTap the pencil to add a friend by their @username.\n\nYour username is @${core.me?.user}. Share it so friends can add you.",
                15.5f, dim,
            ).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)); setLineSpacing(0f, 1.2f) })
        }
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        // keep the list where you had it across refreshes (new messages, folder open/close)
        val y = chatsScrollY
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                list.removeOnLayoutChangeListener(this); scroll.scrollTo(0, y)
            }
        })
        scroll.setOnScrollChangeListener { _, _, sy, _, _ -> chatsScrollY = sy }
        enableFolderDrops(list, scroll)
        val frame = FrameLayout(this)
        frame.addView(scroll)
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_edit)
            setColorFilter(ink)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = circle(white)
            elevation = dp(6).toFloat()
            setOnClickListener { showNewChat() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })

        val top = if (showArchive && search.isEmpty() && !screenSearch) topBar("Archived chats", null, icon(R.drawable.ic_back) { showArchive = false; showChats() })
        else if (search.isNotEmpty() || screenSearch) searchBar() else topBar(
            if (!Messenger.connected) "Connecting…" else if (Messenger.direct) "quipu" else torTitle(),
            if (Messenger.connected) null else if (Messenger.direct) "Direct connection" else Tor.status(),
            icon(R.drawable.ic_menu).also { v -> v.setOnClickListener { drawer?.openDrawer(GravityCompat.START) } },
            icon(R.drawable.ic_search) { screenSearch = true; showChats() },
        )
        // Content + a left slide-out drawer (opens by tapping the lines or swiping from the edge).
        val keepOpen = refreshing && drawer?.isDrawerOpen(GravityCompat.START) == true   // refreshes rebuild the screen; don't snap the menu shut
        val d = DrawerLayout(this)
        d.addView(page(top, frame), DrawerLayout.LayoutParams(-1, -1))
        d.addView(drawerPanel(d), DrawerLayout.LayoutParams(
            minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82).toInt()), -1
        ).apply { gravity = GravityCompat.START })
        d.setScrimColor(0x99000000.toInt())
        drawer = d
        setContentView(d)
        if (keepOpen) d.openDrawer(GravityCompat.START, false)
        // while still connecting over Tor, refresh so the status line (bootstrap %, ready, error) updates live
        if (!Messenger.connected) d.postDelayed({ if (screen == "chats" && !Messenger.connected) redraw() }, 3000)
    }

    /** "quipu" with a small raised "TOR" after it, like a squared number: shows the app is talking through Tor. */
    private fun torTitle() = android.text.SpannableString("quipu TOR").apply {
        setSpan(android.text.style.SuperscriptSpan(), 6, 9, 0)
        setSpan(android.text.style.RelativeSizeSpan(0.5f), 5, 9, 0)
    }

    private var screenSearch = false
    private var chatsScrollY = 0
    /** A chat is being dragged: hold off rebuilding the list (that would cancel the drag) until it's dropped. */
    private var dragging = false

    // ---------- folders ----------

    /** Showing the Archived chats page instead of the main list. */
    private var showArchive = false

    /** The "Archived chats" row: opens the page of chats you've put away. */
    private fun archiveRow(unread: Int, count: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(14), dp(8)); background = pressable()
        addView(FrameLayout(context).apply {
            background = circle(raised)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_folder); setColorFilter(dim) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(tv("Archived chats", 16.5f, white, true))
            addView(tv("$count chat" + if (count != 1) "s" else "", 15f, dim).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (unread > 0) addView(tv("$unread", 12.5f, white, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1)); background = round(faint, 11)
        })
        setOnClickListener { showArchive = true; chatsScrollY = 0; showChats() }
    }

    private fun folderRow(f: Folder, unread: Int, count: Int): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = pressable()
            isClickable = true
            tag = "folder:${f.id}"
        }
        row.addView(icon(R.drawable.ic_folder, white, 26, 14).apply { background = circle(panel2) })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        mid.addView(tv(f.name, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        mid.addView(tv(if (count == 0) "Hold a chat and drag it here" else "$count chat" + if (count != 1) "s" else "", 14f, dim).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        if (unread > 0 && !f.open) row.addView(tv("$unread", 12.5f, white, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1)); background = round(blue, 11)
        })
        // the arrow: points down when open, right when closed
        row.addView(icon(R.drawable.ic_arrow, dim, 20, 10).apply { rotation = if (f.open) 90f else 0f })
        row.setOnClickListener { f.open = !f.open; Messenger.settingsChanged(); showChats() }
        row.setOnLongClickListener { folderMenu(f); true }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "folder:${f.id}"
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
        }
    }

    private fun folderMenu(f: Folder) {
        longPressMenu(null, null, listOf(
            "Rename folder" to {
                val box = field("Folder name", f.name)
                popup("Rename folder", null, box, "Cancel" to dim, "Save" to white) { i ->
                    if (i == 1) box.text.toString().trim().take(40).takeIf { it.isNotEmpty() }?.let { f.name = it; Messenger.settingsChanged(); showChats() }
                }
            },
            "Delete folder" to {
                popup("Delete \"${f.name}\"?", "Only the folder goes. The chats inside are NOT deleted — they move back to your main list.", null,
                    "Cancel" to dim, "Delete" to red) { i -> if (i == 1) { Messenger.folders.remove(f); Messenger.settingsChanged(); showChats() } }
            },
        ))
    }

    private fun newFolder() {
        val box = field("Folder name")
        popup("New folder", "Group chats together, like Family or Work. Then hold a chat and drag it onto the folder.", box,
            "Cancel" to dim, "Create" to white) { i ->
            val n = box.text.toString().trim().take(40)
            if (i == 1 && n.isNotEmpty()) {
                Messenger.folders.add(Folder(java.util.UUID.randomUUID().toString(), n))
                Messenger.settingsChanged(); showChats()
            }
        }
    }

    /** Long-press a chat row to pick it up. */
    private var dragRow: View? = null

    private fun startChatDrag(row: View, key: String) {
        row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        dragging = true
        dragRow = row
        if (!row.startDragAndDrop(ClipData.newPlainText("chat", key), View.DragShadowBuilder(row), key, 0)) dragging = false
    }

    private fun isInside(v: View, g: ViewGroup): Boolean {
        var p = v.parent
        while (p != null) { if (p === g) return true; p = p.parent }
        return false
    }

    /** Drop a dragged chat on a folder (or a chat inside one) to file it there; anywhere else takes it out. */
    private fun enableFolderDrops(list: LinearLayout, scroll: ScrollView) {
        var lit: View? = null
        fun folderAt(y: Float): Pair<String?, View?> {
            for (i in 0 until list.childCount) {
                val v = list.getChildAt(i)
                if (y >= v.top && y < v.bottom) {
                    val t = v.tag as? String ?: return null to null
                    return when {
                        t.startsWith("folder:") -> t.removePrefix("folder:") to v
                        t.startsWith("in:") -> t.removePrefix("in:") to v
                        else -> null to null
                    }
                }
            }
            return null to null
        }
        fun light(v: View?) {
            if (v === lit) return
            lit?.foreground = null
            v?.foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#334EA4F6"))
            lit = v
        }
        list.setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is String
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    light(folderAt(e.y).second)
                    // nudge the list when you drag near the top/bottom edge
                    val onScreen = e.y - scroll.scrollY
                    if (onScreen < dp(64)) scroll.scrollBy(0, -dp(14)) else if (onScreen > scroll.height - dp(64)) scroll.scrollBy(0, dp(14))
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { light(null); true }
                android.view.DragEvent.ACTION_DROP -> {
                    val key = e.localState as? String ?: return@setOnDragListener false
                    // let go on the same chat (didn't really drag it): show its menu instead of moving it
                    val self = dragRow?.let { r -> (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { v -> v === r || (v as? ViewGroup)?.let { g -> isInside(r, g) } == true } }
                    if (self != null && e.y >= self.top && e.y < self.bottom) { dragging = false; list.post { chatRowMenu(key) }; return@setOnDragListener true }
                    val target = folderAt(e.y).first
                    val fs = Messenger.folders
                    fs.forEach { it.items.remove(key) }
                    fs.firstOrNull { it.id == target }?.let { it.items.add(key); it.open = true }
                    Messenger.settingsChanged()
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    light(null)
                    if (dragging) { dragging = false; list.post { if (screen == "chats") showChats() } }
                    true
                }
                else -> true
            }
        }
    }

    companion object {
        private const val PICK_PHOTO = 7
        private const val PICK_ATTACH_PHOTO = 8
        private const val PICK_ATTACH_FILE = 9
        private const val TAKE_MEDIA = 12
        private const val CREATE_RECOVERY = 10
        private const val OPEN_RECOVERY = 11
        private const val SAVE_IMAGE = 13
        private const val MIC_FOR_CALL = 14
        private const val MIC_FOR_VOICE = 15
        private const val LOCATION_FOR_SHARE = 16
        private const val SCAN_QR = 17
        private const val ROUND_VIDEO = 18

        /** Fixed reactions, then the most-used emoji not already in the bar, padded with [filler]. */
        fun quickReactions(use: Map<String, Int>, fixed: List<String>, filler: List<String>): List<String> {
            fun key(e: String) = e.replace("\uFE0F", "")   // ❤ and ❤️ are the same emoji
            val seen = fixed.map(::key).toMutableSet()
            val extra = (use.entries.sortedByDescending { it.value }.map { it.key } + filler)
                .filter { seen.add(key(it)) }.take(3)
            return fixed + extra
        }
    }

    private fun searchBar(): View {
        val box = field("Search", search).apply {
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (search != s.toString()) { search = s.toString(); showChats() }
                }
            })
        }
        box.post { box.requestFocus(); box.setSelection(box.text.length) }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(icon(R.drawable.ic_back) { search = ""; screenSearch = false; showChats() })
            addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** Chats pinned to the top (shown with a pin). */
    private var pinnedKeys = emptySet<String>()

    private fun pinMark() = tv("📌", 12f, dim).apply { setPadding(dp(4), 0, 0, 0) }

    /** Long-press a chat and let go without dragging it: pin / unpin. */
    private fun chatRowMenu(key: String) {
        val pinned = key in Messenger.pins
        val archived = key in Messenger.archived
        longPressMenu(null, null, listOf(
            (if (archived) "Unarchive" else "Archive") to {
                if (archived) Messenger.archived.remove(key) else { Messenger.archived.add(key); Messenger.pins.remove(key) }
                Messenger.settingsChanged(); showChats()
            },
            (if (key in Messenger.muted) "Unmute" else "Mute") to { toggleMute(key) },
            (if (pinned) "Unpin" else "Pin to top") to {
                val ps = Messenger.pins
                if (pinned) ps.remove(key) else { ps.remove(key); ps.add(0, key) }
                Messenger.settingsChanged(); showChats()
            },
        ))
    }

    private fun chatRow(c: Contact, key: String? = null, bg: Int = listBg): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)   // opaque, so the red delete button only shows once you swipe
            setPadding(dp(10), dp(8), dp(14), dp(8))
            setOnClickListener { showChat(c.user) }
        }
        row.addView(if (c.status == Contact.SAVED) savedAvatar(54) else avatar(c.name, 54, c.avatar))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val line1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line1.addView(tv(c.name, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if ("u:${c.user}" in Messenger.muted) line1.addView(tv("🔇", 12f, dim).apply { setPadding(dp(4), 0, 0, 0) })
        val last = c.msgs.lastOrNull()
        if (last?.mine == true && last.state == Msg.SENT) line1.addView(icon(R.drawable.ic_check, blue, 16, 2))
        if (key in pinnedKeys) line1.addView(pinMark())
        line1.addView(tv(time(c.lastTs), 13f, dim).apply { setPadding(dp(4), 0, 0, 0) })
        mid.addView(line1)
        val line2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
        val (preview, previewColor) = when {
            c.status == Contact.ASKING -> (last?.takeIf { it.state == Msg.RECEIVED }?.let { "Message request: ${it.text}" } ?: "Wants to be your friend") to blue
            c.status == Contact.ASKED && last?.state == Msg.NOTE -> "Friend request sent" to dim
            c.status == Contact.FRIENDS && c.theirTypingUntil > System.currentTimeMillis() && c.user !in Messenger.core.blocked -> "typing…" to blue
            Messenger.drafts["u:${c.user}"] != null -> "Draft: ${Messenger.drafts["u:${c.user}"]!!.replace('\n', ' ')}" to red
            last == null -> "" to dim
            last.mine && last.call == null -> "You: ${preview(last)}" to dim
            last.call?.optString("s") == "missed" -> preview(last) to red
            else -> preview(last) to dim
        }
        line2.addView(tv(preview, 15f, previewColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val badge = if (c.status == Contact.ASKING) 1 else c.unread
        val quiet = "u:${c.user}" in Messenger.muted
        if (badge > 0) line2.addView(tv("$badge", 12.5f, white, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(22)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = round(if (quiet) faint else blue, 11)   // muted chats get a grey count
        })
        mid.addView(line2)
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        key?.let { k -> row.setOnLongClickListener { startChatDrag(row, k); true } }
        val swipe = SwipeRow(this, row, R.drawable.ic_trash, onOpen = { showChat(c.user) }, onDelete = { deleteChatConfirm(c) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(swipe, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
        }
    }

    private fun deleteChatConfirm(c: Contact) {
        popup("Delete chat with ${c.name}?", "This erases the whole chat from this phone for good. The server keeps no copy, no backup and no way to recover it — once it's gone, it's gone. Are you sure?",
            null, "Cancel" to dim, "Delete" to red) { i -> if (i == 1) { Messenger.core.remove(c); showChats() } }
    }

    private fun groupRow(g: Group, key: String? = null, bg: Int = listBg): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), dp(8), dp(14), dp(8))
        }
        // group avatar: initials on a ring so groups read differently from people
        row.addView(FrameLayout(this).apply {
            addView(avatar(g.name, 54))
            addView(icon(R.drawable.ic_more, white, 12, 0).apply {
                background = circle(raised); setPadding(dp(2), dp(2), dp(2), dp(2))
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END))
        })
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val line1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line1.addView(tv(g.name, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if ("g:${g.id}" in Messenger.muted) line1.addView(tv("🔇", 12f, dim).apply { setPadding(dp(4), 0, 0, 0) })
        if (key in pinnedKeys) line1.addView(pinMark())
        line1.addView(tv(time(g.lastTs), 13f, dim).apply { setPadding(dp(4), 0, 0, 0) })
        mid.addView(line1)
        val last = g.msgs.lastOrNull { it.state != Msg.NOTE }
        val draft = Messenger.drafts["g:${g.id}"]
        val typingNow = groupTyping(g)
        val preview = when {
            typingNow != null -> typingNow
            draft != null -> "Draft: ${draft.replace('\n', ' ')}"
            last == null -> "${g.members.size} member" + if (g.members.size != 1) "s" else ""
            last.mine -> "You: ${preview(last)}"
            else -> "${last.sender ?: "?"}: ${preview(last)}"
        }
        val line2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
        line2.addView(tv(preview, 15f, if (typingNow != null) blue else if (draft != null) red else dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (g.mentioned) line2.addView(tv("@", 12.5f, white, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(4), dp(1), dp(4), dp(1)); background = round(blue, 11)
            (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(4)
        })
        if (g.unread > 0) line2.addView(tv("${g.unread}", 12.5f, white, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1))
            background = round(if ("g:${g.id}" in Messenger.muted) faint else blue, 11)
        })
        mid.addView(line2)
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        key?.let { k -> row.setOnLongClickListener { startChatDrag(row, k); true } }
        val swipe = SwipeRow(this, row, R.drawable.ic_trash, onOpen = { showGroup(g.id) }, onDelete = {
            popup("Leave \"${g.name}\"?", "You'll stop getting its messages and it's removed from this phone. Nothing is kept on the server to recover. You can be re-invited later.",
                null, "Cancel" to dim, "Leave" to red) { i -> if (i == 1) { Messenger.io.execute { Messenger.core.leaveGroup(g) }; showChats() } }
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(swipe, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
        }
    }

    // ---------- groups ----------

    private fun showNewGroup() {
        screen = "newgroup"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        col.addView(tv("New group", 20f, white, true))
        col.addView(gap(14))
        val name = field("Group name")
        col.addView(name, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(18))
        // type: private (add friends) or public (invite link)
        var public = false
        val priv = tv("", 15f, white)
        val pub = tv("", 15f, white)
        fun paint() {
            priv.text = "Private — only friends you add can join"
            pub.text = "Public — anyone with the invite link can join"
            priv.setTextColor(if (!public) ink else white); priv.background = round(if (!public) white else panel2, 8)
            pub.setTextColor(if (public) ink else white); pub.background = round(if (public) white else panel2, 8)
        }
        for (t in listOf(priv, pub)) { t.gravity = Gravity.CENTER; t.setPadding(dp(14), dp(12), dp(14), dp(12)) }
        priv.setOnClickListener { public = false; paint() }
        pub.setOnClickListener { public = true; paint() }
        paint()
        col.addView(priv, LinearLayout.LayoutParams(-1, -2)); col.addView(gap(8)); col.addView(pub, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(18))
        // friend picker (for private)
        val picked = mutableSetOf<String>()
        val friends = synchronized(Messenger.core) { Messenger.core.contacts.filter { it.canChat } }
        if (friends.isNotEmpty()) {
            col.addView(tv("Add friends", 14f, dim, true)); col.addView(gap(6))
            for (c in friends) col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                background = pressable()
                val check = tv("○", 20f, dim)
                addView(avatar(c.name, 38, c.avatar))
                addView(tv(c.name, 16f, white).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(check)
                setOnClickListener {
                    if (picked.remove(c.user)) { check.text = "○"; check.setTextColor(dim) }
                    else { picked.add(c.user); check.text = "●"; check.setTextColor(blue) }
                }
            })
        }
        col.addView(gap(20))
        col.addView(cta("Create group") {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { toast("Give the group a name"); return@cta }
            Messenger.run({ val g = Messenger.core.createGroup(n, public, if (public) emptyList() else picked.toList()); Messenger.kick(); groupToOpen = g.id }, {
                groupToOpen?.let { showGroup(it) }
            }) { e -> toast(errorText(e)) }
        }, ctaParams())
        setContentView(page(topBar("New Group", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    }

    private var groupToOpen: String? = null

    private fun joinGroupPrompt() {
        val box = field("Paste the group invite link")
        (getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())
            ?.let { if (it.contains("msgr01://group")) box.setText(it) }
        popup("Join a group", "Paste a public group's invite link.", box, "Cancel" to dim, "Join" to white) { i ->
            if (i == 1) Messenger.run({ val g = Messenger.core.joinGroupLink(box.text.toString().trim()); Messenger.kick(); groupToOpen = g.id }, {
                groupToOpen?.let { showGroup(it) }
            }) { e -> toast(errorText(e)) }
        }
    }

    private var groupId: String? = null
    private var groupBubbles: LinearLayout? = null
    private var groupScroller: ScrollView? = null
    private var groupTop: LinearLayout? = null

    private fun showGroup(id: String, restore: Pair<String, Int>? = null) {
        val g = Messenger.core.group(id) ?: return showChats()
        screen = "group"; groupId = id; chatUser = null
        Messenger.core.openChat = "g:$id"
        stick = restore == null; restoreTo = restore
        groupTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupBubbles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true; setOnClickListener { dismissKeyboard() }
        }
        groupScroller = chatScroller(groupBubbles!!)
        val bar2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(listBg); setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        val box = ChatBox(this) { uri, mime, done -> keyboardContent(uri, mime, done, g) }.apply {
            hint = "Message"; setHintTextColor(dim); setTextColor(white); textSize = look.msg; background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false); maxLines = 5   // after inputType, so the box still wraps
        }
        fun sendWith(silent: Boolean, at: Long) {
            val t = box.text.toString().trim(); if (t.isEmpty()) return
            if (t.length > Core.MAX_TEXT) { toast("Too long"); return }
            editing?.let { e ->
                val g2 = Messenger.core.group(id) ?: return
                cancelReply(); box.setText("")
                Messenger.io.execute { Messenger.core.editGroupMessage(g2, e, t) }
                return
            }
            box.setText(""); box.requestFocus(); stick = true
            val g2 = Messenger.core.group(id) ?: return
            val r = replyTo?.let { replyRef(it) }; cancelReply()
            Messenger.sendGroup(g2, t, r, links.ready(t), silent, at)
            if (at > 0) toast("Scheduled for ${whenText(at)}")
        }
        fun doSend() = sendWith(false, 0)
        box.setText(Messenger.drafts["g:$id"] ?: "")
        box.setOnEditorActionListener { _, aid, _ -> if (aid == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        keepDraft(box, "g:$id")
        links.watch(box)
        box.addTextChangedListener(object : TextWatcher {   // the group sees "you are typing…" (unless switched off)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {
                if (!s.isNullOrBlank() && n > 0 && editing == null) Messenger.core.group(id)?.let { Messenger.groupTyping(it) }
            }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        bar2.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        bar2.addView(icon(R.drawable.ic_emoji, dim, pad = 10).also { v -> v.setOnClickListener { emojiPicker(v, box, g) } })
        bar2.addView(icon(R.drawable.ic_attach, dim, pad = 10).also { v -> v.setOnClickListener { attachGroupId = id; attachTo = null; attachMenu(v) } })
        bar2.addView(sendOrMic(bar2, box, ::doSend, { sil, at -> sendWith(sil, at) }) { a, th -> Messenger.core.group(id)?.let { Messenger.sendGroupFile(it, Voice.NAME, Voice.MIME, a, th) } })
        // read-only group: non-admins see a notice instead of the composer
        val canPost = g.canPost(Messenger.core.me?.user ?: "")
        replyTo = null; replyBar = makeReplyBar(); groupBox = box
        val picker = mentionPicker(box, g)
        val compose = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(picker); addView(links.bar); addView(replyBar); addView(bar2) }
        composer = compose
        val bottom: View = if (canPost) compose else tv("🔒  Only admins can post in this group", 14f, dim).apply {
            gravity = Gravity.CENTER; setBackgroundColor(listBg); setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = wallBg(); fitsSystemWindows = true
            addView(groupTop, LinearLayout.LayoutParams(-1, -2))
            addView(groupScroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(-1, -2))
        })
        fillGroup()
    }

    private fun fillGroup() {
        val g = groupId?.let { Messenger.core.group(it) } ?: return
        if (g.unread != 0 || g.mentioned) { g.unread = 0; g.mentioned = false; Messenger.save() }
        groupTop?.removeAllViews()
        val typing = groupTyping(g)
        val info = "${g.members.size} member" + (if (g.members.size != 1) "s" else "") + (if (g.public) " · public" else "") + if (g.readOnly) " · read-only" else ""
        groupTop?.addView(topBar(g.name, typing?.let { android.text.SpannableString(it).apply { setSpan(android.text.style.ForegroundColorSpan(blue), 0, it.length, 0) } } ?: info,
            LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(icon(R.drawable.ic_back) { showChats() }); addView(avatar(g.name, 40)) },
            icon(R.drawable.ic_more) { groupMenu(g) }), LinearLayout.LayoutParams(-1, -2))
        pinnedBar(g.pinned, g.msgs, groupBubbles, groupScroller, Messenger.core.canPin(g)) { Messenger.run({ Messenger.core.pinGroupMessage(g, null) }) { e -> toast(errorText(e)) } }?.let { groupTop?.addView(it) }
        val list = groupBubbles ?: return
        val msgs = synchronized(Messenger.core) { g.msgs.takeLast(300) }
        if (!stick && restoreTo == null) restoreTo = anchorOf(groupScroller, list)
        list.removeAllViews()
        list.addView(note("Messages in this group are end-to-end encrypted."))
        for (m in msgs) list.addView(if (m.state == Msg.NOTE) note(m.text) else groupBubble(g, m))
    }

    private fun groupBubble(g: Group, m: Msg): View {
        val wrap = LinearLayout(this).apply { gravity = if (m.mine) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4)); tag = m.id }
        val fill = if (m.mine) look.out else look.inn
        val tcol = Look.textOn(fill)
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(fill, if (look.square) 0 else 14)
            setPadding(dp(10), dp(6), dp(10), dp(5))
        }
        if (!m.mine) m.sender?.let { su ->
            b.addView(tv("@$su", 13f, avatarColors[Math.floorMod(su.hashCode(), avatarColors.size)], true))
        }
        if ((isSticker(m) || isRound(m)) && m.text.isEmpty() && m.reply == null) b.background = null
        m.fwd?.let { b.addView(fwdLabel(it, tcol)) }
        m.reply?.let { b.addView(quoteView(it, g.msgs, tcol, groupBubbles, groupScroller)) }
        m.file?.let { b.addView(attachmentView(m, tcol) { groupMessageMenu(g, m) }) }
        if (m.text.isNotEmpty()) b.addView(msgText(m, tcol, g.members) { groupMessageMenu(g, m) })
        m.lp?.let { b.addView(linkCard(it, tcol)) }
        m.loc?.let { b.addView(locationCard(it, tcol)) }
        if (m.poll != null) b.addView(pollView(g, m, tcol))
        val meta = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val mc = (tcol and 0x00FFFFFF) or (0x99 shl 24)
        meta.addView(tv((if (m.edited) "edited  " else "") + time(m.ts), 12f, mc).apply { setPadding(0, 0, dp(6), 0) })
        if (m.mine) when (m.state) {
            Msg.SENT -> meta.addView(icon(R.drawable.ic_check, mc, 14, 1))
            Msg.SENDING -> meta.addView(tv("🕓", 11f, mc))
            Msg.SCHEDULED -> meta.addView(tv("🕓 ${whenText(m.sendAt)}", 12f, mc))
            Msg.FAILED -> meta.addView(tv("  not sent · tap", 12f, red).apply { setOnClickListener { Messenger.io.execute { Messenger.core.deliverGroup(g, m) } } })
        }
        b.addView(meta, LinearLayout.LayoutParams(-1, -2))
        addReactions(b, m) { e -> val me = Messenger.core.me?.user; Messenger.reactGroup(g, m, if (m.reactions[me] == e) "" else e); fillGroup() }
        b.setOnLongClickListener { groupMessageMenu(g, m); true }
        wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (m.mine) leftMargin = dp(56) else rightMargin = dp(56) })
        return wrap
    }

    private fun groupMessageMenu(g: Group, m: Msg) {
        val me = Messenger.core.me?.user
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (m.state == Msg.SCHEDULED) opts.add("Send now" to { Messenger.sendScheduled(m) })
        if (g.canPost(me ?: "")) opts.add("Reply" to { startReply(m, groupBox) })
        if (m.text.isNotEmpty()) opts.add((if (m.file != null) "Copy text" else "Copy") to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("msg", m.text)); toast("Copied")
        })
        opts.addAll(imageActions(m, if (m.text.isNotEmpty()) "Copy photo" else "Copy"))
        if (canForward(m)) opts.add("Forward" to { forwardPicker(m, authorOf(m)) })
        if (Messenger.core.canPin(g)) opts.add((if (g.pinned == m.id) "Unpin" else "Pin") to {
            Messenger.run({ Messenger.core.pinGroupMessage(g, if (g.pinned == m.id) null else m) }) { e -> toast(errorText(e)) }
        })
        if (m.mine && m.text.isNotEmpty() && g.canPost(me ?: "")) opts.add("Edit" to { startEdit(m, groupBox) })
        if (m.mine && m.state != Msg.FAILED && m.state != Msg.SCHEDULED) opts.add("Delete for everyone" to {
            Messenger.io.execute { Messenger.core.deleteGroupMessage(g, m, forEveryone = true) }
        })
        opts.add("Delete for me" to { Messenger.core.deleteGroupMessage(g, m, forEveryone = false); fillGroup() })
        longPressMenu(m.reactions[me], { e -> Messenger.reactGroup(g, m, e); fillGroup() }, opts)
    }

    private fun groupMenu(g: Group) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val key = "g:${g.id}"
        items.add((if (key in Messenger.muted) "Unmute" else "Mute") to { toggleMute(key) })
        if (g.public) items.add("Copy invite link" to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("group", Messenger.core.groupInviteLink(g)))
            toast("Invite link copied")
        }) else items.add("Add a friend" to { addToGroup(g) })
        if (g.owner.isNotEmpty() && g.isLeader(Messenger.core.me?.user ?: "")) items.add("Group settings" to { showGroupSettings(g) })
        items.add("Leave group" to {
            popup("Leave \"${g.name}\"?", "You'll stop getting its messages. You can be re-invited later.", null, "Cancel" to dim, "Leave" to red) { i ->
                if (i == 1) { Messenger.io.execute { Messenger.core.leaveGroup(g) }; showChats() }
            }
        })
        dropdown(groupTop!!.getChildAt(0), Gravity.END, *items.toTypedArray())
    }

    private fun addToGroup(g: Group) {
        val friends = synchronized(Messenger.core) { Messenger.core.contacts.filter { it.canChat && it.user !in g.members } }
        if (friends.isEmpty()) { toast("No friends left to add"); return }
        pickList("Add to ${g.name}", null, friends.map { it.name }.toTypedArray()) { i ->
            Messenger.run({ Messenger.core.inviteToGroup(g, friends[i].user); Messenger.kick() }, { fillGroup() }) { e -> toast(errorText(e)) }
        }
    }

    /** Admin-only screen: rename, read-only, and who the admins are. */
    private fun showGroupSettings(g: Group) {
        screen = "groupsettings"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("Name", 14f, dim, true).apply { setPadding(dp(20), dp(16), dp(20), dp(6)) })
        val nameBox = field("Group name", g.name)
        col.addView(nameBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), 0, dp(20), 0) })
        col.addView(gap(10))
        var readOnly = g.readOnly
        col.addView(settingSwitch("Read-only", g.readOnly) { readOnly = it })
        col.addView(settingNote("When on, only admins can post. Anyone can still react. Note: this is enforced by the app, not the server — a modified app could still post, so don't treat it as a security guarantee."))
        col.addView(tv("Admins", 14f, dim, true).apply { setPadding(dp(20), dp(16), dp(20), dp(6)) })
        val chosen = g.leaders.toMutableSet()
        val iAmOwner = g.owner.isNotEmpty() && g.owner == Messenger.core.me?.user
        synchronized(Messenger.core) { g.members.sorted() }.forEach { member ->
            val isOwner = member == g.owner
            val sw = settingSwitch("@$member" + if (isOwner) "  (owner)" else "", isOwner || member in chosen) { on ->
                if (on) chosen.add(member) else chosen.remove(member)
            }.apply { if (isOwner) isEnabled = false }
            // only the owner can remove, and not themselves
            if (iAmOwner && !isOwner) {
                col.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(sw, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tv("Remove", 15f, red).apply {
                        setPadding(dp(16), dp(14), dp(20), dp(14)); background = pressable(); isClickable = true
                        setOnClickListener {
                            popup("Remove @$member?", "They'll lose access to new messages. The group gets a new key, so this only works for members on the latest app version.",
                                null, "Cancel" to dim, "Remove" to red) { i ->
                                if (i == 1) Messenger.run({ Messenger.core.rekeyGroup(g, setOf(member)); Messenger.kick() }, { showGroupSettings(Messenger.core.group(g.id) ?: g) }) { e -> toast(errorText(e)) }
                            }
                        }
                    })
                })
            } else col.addView(sw)
        }
        col.addView(gap(20))
        col.addView(cta("Save") {
            Messenger.run({ Messenger.core.setGroup(g, nameBox.text.toString(), readOnly, chosen); Messenger.kick() }, { showGroup(g.id) }) { e -> toast(errorText(e)) }
        }, ctaParams())
        col.addView(gap(20))
        setContentView(page(topBar("Group settings", left = icon(R.drawable.ic_back) { showGroup(g.id) }), ScrollView(this).apply { addView(col) }))
    }

    // ---------- add a friend ----------

    private fun showNewChat() {
        screen = "new"
        val me = Messenger.core.me ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
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
                        "Add me on quipu: @${me.user}\nGet the app: https://wilfrombdn.github.io/"), "Share username"))
                }
            })
        })
        setContentView(page(topBar("New Message", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    }

    // ---------- one chat ----------

    private var bubbles: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var chatTop: LinearLayout? = null
    private var banner: FrameLayout? = null

    private fun showChat(user: String, restore: Pair<String, Int>? = null) {
        val c = Messenger.core.contact(user) ?: return showChats()
        screen = "chat"
        chatUser = user
        Messenger.core.openChat = user
        stick = restore == null; restoreTo = restore

        chatTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner = FrameLayout(this)
        bubbles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true; setOnClickListener { dismissKeyboard() }   // tap empty area to close the keyboard
        }
        scroller = chatScroller(bubbles!!)

        val input = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(listBg)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        val box = ChatBox(this) { uri, mime, done -> keyboardContent(uri, mime, done, c) }.apply {
            hint = "Message"
            setHintTextColor(dim)
            setTextColor(white)
            textSize = look.msg
            background = null
            // no multi-line flag => Enter sends; but that also forces single-line, so re-enable
            // wrapping with maxLines/horizontallyScrolling AFTER inputType (order matters)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false)
            maxLines = 5
        }
        fun sendWith(silent: Boolean, at: Long) {
            val t = box.text.toString().trim()
            if (t.isEmpty()) return
            if (t.length > Core.MAX_TEXT) { toast("Too long (max ${Core.MAX_TEXT} characters)"); return }
            editing?.let { e ->
                cancelReply(); box.setText("")
                Messenger.io.execute { Messenger.core.editMessage(c, e, t) }
                return
            }
            box.setText("")
            Messenger.typingDone(c)
            box.requestFocus()                 // stay in the field so you can keep typing
            stick = true                       // your own message always brings you to the bottom
            val r = replyTo?.let { replyRef(it) }; cancelReply()
            Messenger.send(c, t, r, links.ready(t), silent, at)
            if (at > 0) toast("Scheduled for ${whenText(at)}")
        }
        fun doSend() = sendWith(false, 0)
        box.setText(Messenger.drafts["u:${c.user}"] ?: "")   // what you left unsent last time
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        keepDraft(box, "u:${c.user}")
        links.watch(box)
        box.addTextChangedListener(object : TextWatcher {   // friends see "typing…" (unless switched off in Settings)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { if (!s.isNullOrBlank() && n > 0) Messenger.typing(c) }
            override fun afterTextChanged(e: android.text.Editable?) {}
        })
        input.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        input.addView(icon(R.drawable.ic_emoji, dim, pad = 10).also { v -> v.setOnClickListener { emojiPicker(v, box, c) } })
        input.addView(icon(R.drawable.ic_attach, dim, pad = 10).also { v -> v.setOnClickListener { attachTo = c.user; attachGroupId = null; attachMenu(v) } })
        input.addView(sendOrMic(input, box, ::doSend, { sil, at -> sendWith(sil, at) }) { a, th -> Messenger.sendFile(c, Voice.NAME, Voice.MIME, a, th) })
        replyTo = null; replyBar = makeReplyBar(); chatBox = box
        val compose = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(links.bar); addView(replyBar); addView(input) }
        composer = compose
        // blocked: no message box, just a way back
        val bottom: View = if (c.user in Messenger.core.blocked && c.status != Contact.SAVED) tv("Unblock", 16f, blue, true).apply {
            gravity = Gravity.CENTER; setBackgroundColor(listBg); setPadding(dp(16), dp(16), dp(16), dp(16)); background = pressable()
            setOnClickListener { blockConfirm(c, false) }
        } else compose

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = wallBg()
            fitsSystemWindows = true
            addView(chatTop, LinearLayout.LayoutParams(-1, -2))
            addView(banner, LinearLayout.LayoutParams(-1, -2))
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        fillChat()
    }

    private fun fillChat() {
        val c = chatUser?.let { Messenger.core.contact(it) } ?: return
        val list = bubbles ?: return
        Messenger.run({ Messenger.core.markRead(c) }, { Messenger.save() })

        val sub = when (c.status) {
            Contact.ASKED -> "friend request sent"
            Contact.ASKING -> if (c.msgs.any { !it.mine && it.state == Msg.RECEIVED }) "message request" else "wants to be your friend"
            Contact.CHATTING -> if (c.waitingForAdd) "@${c.user} · hasn't added you yet" else "@${c.user} · not your friend"
            Contact.SAVED -> "only on this phone"
            else -> if (c.user in Messenger.core.blocked) "@${c.user} · blocked" else if (c.verified) "@${c.user} · verified ✓" else "@${c.user}"
        }
        chatTop?.removeAllViews()
        val blocked = Messenger.core.callBlocked(c)
        val netNote = blocked?.takeIf { c.status == Contact.FRIENDS }   // "your network differs…" shows under the name
        val live = presence(c).takeIf { c.user !in Messenger.core.blocked }
        val head: CharSequence = if (live?.toString() == "typing…") live else netNote ?: live ?: sub
        val left = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_back) { showChats() })
            addView(if (c.status == Contact.SAVED) savedAvatar(40) else avatar(c.name, 40, c.avatar))
        }
        val more = icon(R.drawable.ic_more).also { v -> v.setOnClickListener { chatMenu(c, v) } }
        chatTop?.addView(if (c.status == Contact.SAVED) topBar(c.name, head, left, more) else topBar(c.name, head, left,
            icon(R.drawable.ic_call, if (blocked == null) white else faint, 22) { if (blocked != null) toast(blocked) else callFriend(c) }, more),
            LinearLayout.LayoutParams(-1, -2))
        pinnedBar(c.pinned, c.msgs, bubbles, scroller, c.canChat) { Messenger.run({ Messenger.core.pinMessage(c, null) }) }?.let { chatTop?.addView(it) }

        banner?.removeAllViews()
        fun bannerButton(label: String, color: Int, action: () -> Unit) = tv(label, 15f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                Messenger.run({ action(); Messenger.kick() }, {}) { e -> toast(errorText(e)) }
            }
        }
        val actions = when {
            c.status == Contact.ASKING -> listOf(
                bannerButton("Decline", red) { Messenger.core.remove(c) },
                bannerButton("Accept", white) { Messenger.core.accept(c, friend = false) },
                bannerButton("Add Friend", white) { Messenger.core.accept(c, friend = true) },
            )
            c.status == Contact.CHATTING && !c.waitingForAdd -> listOf(bannerButton("Add @${c.user} as a friend", white) { Messenger.core.makeFriend(c) })
            else -> emptyList()
        }
        if (actions.isNotEmpty()) banner?.addView(LinearLayout(this).apply {
            setBackgroundColor(bar)
            setPadding(dp(8), 0, dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            for (v in actions) addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        })

        val msgs = synchronized(Messenger.core) { c.msgs.takeLast(300) }
        if (!stick && restoreTo == null) restoreTo = anchorOf(scroller, list)
        list.removeAllViews()
        if (c.status != Contact.SAVED) list.addView(note(if (c.verified) "Messages are end-to-end encrypted. Safety code checked ✓"
        else "Messages are end-to-end encrypted. Tap here to check the safety code.") { safety(c) })
        for (m in msgs) list.addView(if (m.state == Msg.NOTE) note(m.text) else bubble(c, m))
    }

    // ---------- replies ----------

    /** The message you're replying to (shown above the message box until you send or tap ✕). */
    private var replyTo: Msg? = null
    /** My message being edited in the message box (instead of writing a new one). */
    private var editing: Msg? = null
    private var chatBox: EditText? = null
    private var groupBox: EditText? = null
    private var replyBar: LinearLayout? = null

    private fun authorOf(m: Msg): String = if (m.mine) Messenger.core.me?.user ?: "" else m.sender ?: chatUser ?: ""
    private fun displayName(user: String): String = when (user) {
        Messenger.core.me?.user -> "You"
        else -> Messenger.core.contact(user)?.name ?: "@$user"
    }
    private fun replyRef(m: Msg) = org.json.JSONObject().put("id", m.id).put("u", authorOf(m)).put("p", preview(m).take(160))

    /** The strip above the message box: "Reply to Sam" + what they said, with ✕ to cancel. */
    private fun makeReplyBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(listBg)
        setPadding(dp(14), dp(8), dp(4), 0)
        visibility = View.GONE
    }

    private fun startReply(m: Msg, box: EditText?) {
        if (editing != null) { editing = null; box?.setText("") }
        replyTo = m
        showBar("Reply to ${displayName(authorOf(m))}", m, box)
    }

    /** Puts my message back in the box to change it; sending then edits it on both phones. */
    private fun startEdit(m: Msg, box: EditText?) {
        replyTo = null
        editing = m
        showBar("Edit message", m, box)
        box?.setText(m.text); box?.setSelection(box.text.length)
    }

    private fun showBar(title: String, m: Msg, box: EditText?) {
        val bar = replyBar ?: return
        bar.removeAllViews()
        bar.addView(View(this).apply { setBackgroundColor(white) }, LinearLayout.LayoutParams(dp(3), dp(36)))
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            addView(tv(title, 14f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(tv(preview(m), 14f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tv("✕", 18f, dim).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { cancelReply() } })
        bar.visibility = View.VISIBLE
        box?.let { b ->
            b.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(b, 0)
        }
    }

    private fun cancelReply() {
        if (editing != null) { editing = null; (if (screen == "group") groupBox else chatBox)?.setText("") }
        replyTo = null; replyBar?.visibility = View.GONE
    }

    /** The quote at the top of a reply bubble; tap it to jump to the original message. */
    private fun quoteView(r: org.json.JSONObject, all: List<Msg>, ink: Int, list: LinearLayout?, sc: ScrollView?): View {
        val orig = synchronized(Messenger.core) { all.firstOrNull { it.id == r.optString("id") } }
        val who = r.optString("u")
        val accent = if (who == Messenger.core.me?.user) ink else avatarColors[Math.floorMod(who.hashCode(), avatarColors.size)]
        val faded = (ink and 0x00FFFFFF) or (0xB0 shl 24)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor((ink and 0x00FFFFFF) or (0x18 shl 24)); cornerRadius = dp(if (look.square) 0 else 6).toFloat() }
            setPadding(0, 0, dp(8), 0)
            addView(View(context).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(dp(3), -1))
            // a little picture when replying to a photo
            orig?.file?.optString("th")?.takeIf { it.isNotEmpty() && orig.file?.optString("m")?.startsWith("image/") == true }?.let { th ->
                val t = java.util.Base64.getDecoder().decode(th)
                android.graphics.BitmapFactory.decodeByteArray(t, 0, t.size)?.let { bmp ->
                    addView(ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP },
                        LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6); topMargin = dp(4); bottomMargin = dp(4) })
                }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), 0, dp(4))
                addView(tv(displayName(who), 13.5f, accent, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(tv(orig?.let { preview(it) } ?: r.optString("p").ifEmpty { "Message deleted" }, 14f, faded).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            })
            setOnClickListener { jumpTo(r.optString("id"), list, sc) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4) }
        }
    }

    // ---------- keeping your place in a chat ----------

    /** True while you're at (or within a hair of) the newest message: new ones then keep you at the bottom. */
    private var stick = true
    /** A message id + its offset from the top of the screen to put back after the list is rebuilt. */
    private var restoreTo: Pair<String, Int>? = null

    private fun atBottom(s: ScrollView) = (s.getChildAt(0)?.bottom ?: 0) - (s.scrollY + s.height) <= dp(48)
    private fun toBottom(s: ScrollView) = s.scrollTo(0, maxOf(0, (s.getChildAt(0)?.bottom ?: 0) - s.height))

    /** The message scroller: follows the bottom when you're there (new messages, photos loading, the
     *  keyboard opening), and otherwise stays exactly where you scrolled to. */
    private fun chatScroller(list: LinearLayout) = ScrollView(this).apply {
        addView(list); isFillViewport = true
        val s = this
        setOnScrollChangeListener { _, _, _, _, _ -> if (restoreTo == null) stick = atBottom(s) }
        val follow = View.OnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t == ob - ot && restoreTo == null && !stick) return@OnLayoutChangeListener
            restoreTo?.let { r -> restoreTo = null; restoreAnchor(s, list, r); stick = atBottom(s) }
                ?: run { if (stick) toBottom(s) }
        }
        list.addOnLayoutChangeListener(follow)
        addOnLayoutChangeListener(follow)   // the window shrinking for the keyboard
    }

    /** The first message showing at the top of the screen, and how far its top is from the screen's top. */
    private fun anchorOf(s: ScrollView?, list: LinearLayout?): Pair<String, Int>? {
        s ?: return null; list ?: return null
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            val id = v.tag as? String ?: continue
            if (v.bottom > s.scrollY) return id to (v.top - s.scrollY)
        }
        return null
    }

    private fun restoreAnchor(s: ScrollView, list: LinearLayout, r: Pair<String, Int>) {
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v.tag == r.first) { s.scrollTo(0, maxOf(0, v.top - r.second)); return }
        }
        toBottom(s)   // that message isn't loaded any more: go to the newest
    }

    /** Remembers the open chat and your place in it, so coming back (even after the app was closed) lands there. */
    private fun saveSpot() {
        val inChat = screen == "chat" || (screen == "viewer" && chatUser != null)
        val inGroup = screen == "group" || (screen == "viewer" && chatUser == null && groupId != null)
        val sc = if (inChat) scroller else if (inGroup) groupScroller else null
        val list = if (inChat) bubbles else groupBubbles
        Messenger.spot = when {
            !Messenger.signedIn || sc == null -> Spot()
            else -> {
                val bottom = atBottom(sc)
                val a = if (bottom) null else anchorOf(sc, list)
                Spot(if (inChat) "chat" else "group", (if (inChat) chatUser else groupId) ?: "", a?.first ?: "", a?.second ?: 0, a == null)
            }
        }
    }

    private fun note(text: String, onClick: (() -> Unit)? = null): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(6), dp(24), dp(6))
        addView(tv(text, 13.5f, white).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = round(pick("#CC262626", "#CCFFFFFF"), 12)
            onClick?.let { f -> setOnClickListener { f() } }
        })
    }

    // ---------- calls ----------

    private var callAfterMic: Contact? = null

    private fun callFriend(c: Contact) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callAfterMic = c
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_FOR_CALL); return
        }
        Messenger.core.call?.let { startActivity(Intent(this, CallActivity::class.java)); return }   // already in one: show it
        Messenger.startCall(this, c, { e -> toast(errorText(e)) }, ::errorText)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_FOR_SHARE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) shareLocation() else toast("quipu needs location access to share where you are")
            return
        }
        if (requestCode == MIC_FOR_VOICE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("Hold the mic to record, let go to send")
            else toast("quipu needs the microphone for voice messages")
            return
        }
        if (requestCode != MIC_FOR_CALL) return
        val c = callAfterMic; callAfterMic = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) c?.let { callFriend(it) }
        else toast("quipu needs the microphone for calls")
    }

    private fun callTitle(k: org.json.JSONObject): String {
        val out = k.optString("d") == "out"
        return when (k.optString("s")) {
            "ok" -> if (out) "Outgoing call" else "Incoming call"
            "missed" -> "Missed call"
            "declined" -> if (out) "Call declined" else "Declined call"
            "cancelled" -> "Cancelled call"
            "busy" -> "Line busy"
            "noanswer" -> "No answer"
            else -> "Call failed"
        }
    }

    private fun callDuration(sec: Int) = when {
        sec >= 3600 -> "%d h %d min".format(sec / 3600, sec % 3600 / 60)
        sec >= 60 -> "%d min %d s".format(sec / 60, sec % 60)
        else -> "$sec s"
    }

    /** Telegram-style call summary in the chat; tap the phone to call back. */
    private fun callBubble(c: Contact, m: Msg): View {
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

    private fun bubble(c: Contact, m: Msg): View {
        if (m.call != null) return callBubble(c, m)
        val wrap = LinearLayout(this).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))   // a little breathing room between bubbles
            tag = m.id
        }
        val fill = if (m.mine) look.out else look.inn
        val ink = Look.textOn(fill)
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(fill, if (look.square) 0 else 14)
            setPadding(dp(10), dp(6), dp(10), dp(5))
        }
        if ((isSticker(m) || isRound(m)) && m.text.isEmpty() && m.reply == null) b.background = null   // stickers / round videos sit on the chat background
        m.fwd?.let { b.addView(fwdLabel(it, ink)) }
        m.reply?.let { b.addView(quoteView(it, c.msgs, ink, bubbles, scroller)) }
        m.file?.let { b.addView(attachmentView(m, ink) { messageMenu(c, m) }) }
        if (m.text.isNotEmpty()) b.addView(msgText(m, ink, setOf(c.user)) { messageMenu(c, m) })
        m.lp?.let { b.addView(linkCard(it, ink)) }
        m.loc?.let { b.addView(locationCard(it, ink)) }
        val meta = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val metaColor = (ink and 0x00FFFFFF) or (0x99 shl 24)
        meta.addView(tv((if (m.edited) "edited  " else "") + time(m.ts), 12f, metaColor).apply { setPadding(0, 0, dp(6), 0) }) // gap before the tick
        if (m.mine) when (m.state) {
            Msg.SENT -> {
                val read = m.ts <= c.theirReadUpto
                // one grey tick = sent; two accent ticks = read (like Telegram)
                meta.addView(icon(R.drawable.ic_check, if (read) blue else metaColor, 14, 1))
                if (read) meta.addView(icon(R.drawable.ic_check, blue, 14, 1).apply {
                    (layoutParams as LinearLayout.LayoutParams).leftMargin = -dp(9)
                })
            }
            Msg.SENDING -> meta.addView(tv("🕓", 11f, metaColor))
            Msg.SCHEDULED -> meta.addView(tv("🕓 ${whenText(m.sendAt)}", 12f, metaColor))
            Msg.FAILED -> meta.addView(tv("  not sent · tap to retry", 12f, red).apply { setOnClickListener { Messenger.retry(c, m) } })
        }
        b.addView(meta, LinearLayout.LayoutParams(-1, -2))
        addReactions(b, m) { e -> val me = Messenger.core.me?.user; Messenger.react(c, m, if (m.reactions[me] == e) "" else e); fillChat() }
        b.setOnLongClickListener { messageMenu(c, m); true }
        wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (m.mine) leftMargin = dp(56) else rightMargin = dp(56) })
        return wrap
    }

    private val fixedReactions = listOf("👍", "❤️", "😂", "😮", "😢")
    private val fillerReactions = listOf("🙏", "🔥", "👏", "🎉", "😍", "👀")

    /** First five are fixed; the last three are your most-used emoji (never repeating one already shown). */
    private fun quickReactions(): List<String> = Companion.quickReactions(synchronized(Messenger.emojiUse) { Messenger.emojiUse.toMap() }, fixedReactions, fillerReactions)

    // where the finger last went down (screen coords), so long-press menus grow out of that spot
    private var touchX = 0f
    private var touchY = 0f
    private var touchT = 0L
    /** The message box row of the open chat; taps on it don't close the keyboard/emoji panel. */
    private var composer: View? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { touchX = ev.rawX; touchY = ev.rawY; touchT = ev.eventTime }
        val handled = super.dispatchTouchEvent(ev)
        // a quick tap anywhere outside the composer closes the emoji panel and the keyboard, like Telegram
        if (ev.actionMasked == MotionEvent.ACTION_UP && (screen == "chat" || screen == "group")) {
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            val tap = Math.abs(ev.rawX - touchX) < slop && Math.abs(ev.rawY - touchY) < slop &&
                ev.eventTime - touchT < ViewConfiguration.getLongPressTimeout()
            if (tap && composer?.let { inside(it, ev.rawX, ev.rawY) } != true) {
                emojiPopup?.dismiss(); emojiPopup = null
                if (currentFocus is EditText) dismissKeyboard()
            }
        }
        return handled
    }

    private fun inside(v: View, x: Float, y: Float): Boolean {
        val l = IntArray(2); v.getLocationOnScreen(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    /**
     * Long-press menu: square-cornered panel that grows out of the spot you pressed, with an optional
     * reaction bar on top ([mine] = your current reaction) and the actions below.
     */
    private fun longPressMenu(mine: String?, onReact: ((String) -> Unit)?, actions: List<Pair<String, () -> Unit>>) {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(this).apply { setBackgroundColor(0x55000000); setOnClickListener { d.dismiss() } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(bar); setStroke(dp(1), divider) }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isClickable = true      // taps inside the panel don't fall through and close it
            visibility = View.INVISIBLE
        }
        if (onReact != null) {
            val reactRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            // nine equal cells (8 emoji + ＋) so the whole row always fits; emoji size ignores the app-text setting
            quickReactions().forEach { e ->
                reactRow.addView(tv(e).apply {
                    textSize = 22f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
                    background = if (e == mine) android.graphics.drawable.ColorDrawable(raised) else pressable()
                    setOnClickListener { d.dismiss(); onReact(if (e == mine) "" else e) }   // tap your own to remove it
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            reactRow.addView(tv("＋", 20f, dim).apply {
                textSize = 20f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)); background = pressable()
                setOnClickListener { d.dismiss(); pickEmoji { onReact(it) } }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(reactRow, LinearLayout.LayoutParams(-1, -2))
            if (actions.isNotEmpty()) col.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(4) })
        }
        actions.forEach { (label, act) ->
            col.addView(tv(label, 16f, if (label.startsWith("Delete")) red else white).apply {
                background = pressable(); isClickable = true; setPadding(dp(18), dp(13), dp(28), dp(13)); minWidth = dp(200)
                setOnClickListener { d.dismiss(); act() }
            })
        }
        val screenW = resources.displayMetrics.widthPixels
        val w = if (onReact != null) minOf((screenW * 0.86).toInt(), dp(360)) else -2
        root.addView(col, FrameLayout.LayoutParams(w, -2))
        val px = touchX; val py = touchY
        col.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                col.removeOnLayoutChangeListener(this)
                val o = IntArray(2); root.getLocationOnScreen(o)
                val x = px - o[0]; val y = py - o[1]; val m = dp(8).toFloat()
                // open to the right/below the finger, flipping left/up when it wouldn't fit
                val left = (if (x + col.width + m <= root.width) x else x - col.width).coerceIn(m, maxOf(m, root.width - col.width - m))
                val top = (if (y + col.height + m <= root.height) y else y - col.height).coerceIn(m, maxOf(m, root.height - col.height - m))
                col.x = left; col.y = top
                col.pivotX = (x - left).coerceIn(0f, col.width.toFloat()); col.pivotY = (y - top).coerceIn(0f, col.height.toFloat())
                col.scaleX = 0.3f; col.scaleY = 0.3f; col.alpha = 0f; col.visibility = View.VISIBLE
                col.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            }
        })
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    /** Copy / Download for a photo that's on this phone. */
    private fun imageActions(m: Msg, copyLabel: String = "Copy"): List<Pair<String, () -> Unit>> =
        if (m.file?.optString("m")?.startsWith("image/") == true && m.file?.has("local") == true)
            listOf(copyLabel to { copyImage(m) }, "Download" to { downloadImage(m) }, "Add to my stickers" to { addToStickers(m) }) else emptyList()

    // ---------- round video messages ----------

    /** The mic button is switched to round video. */
    private var videoMode = false
    private var roundHeld = false

    private fun isRound(m: Msg) = m.file?.optString("m") == "video/mp4" && m.file?.optString("n")?.startsWith("Video message") == true

    /** A recorded round video: read it, make a round-ish preview frame, delete the file, send it to the open chat. */
    private fun sendRound(path: String) {
        val c = chatUser?.let { Messenger.core.contact(it) }.takeIf { screen == "chat" }
        val g = groupId?.let { Messenger.core.group(it) }.takeIf { screen == "group" }
        val f = File(path)
        if (c == null && g == null) { f.delete(); return }
        stick = true
        Messenger.run({
            val bytes = f.readBytes()
            val thumb = runCatching {
                val r = android.media.MediaMetadataRetriever(); r.setDataSource(path)
                val frame = r.getFrameAtTime(0); r.release()
                frame?.let { b -> val s = minOf(b.width, b.height); thumbOf(android.graphics.Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)) }
            }.getOrNull()
            f.delete()
            if (g != null) Messenger.sendGroupFile(g, "Video message.mp4", "video/mp4", bytes, thumb) else Messenger.sendFile(c!!, "Video message.mp4", "video/mp4", bytes, thumb)
        }, {}) { e -> f.delete(); toast(errorText(e)) }
    }

    /** A round video in the chat (its first frame); tap plays it with sound in a pop-up circle. */
    private fun roundView(m: Msg, onLong: () -> Unit): View {
        val f = m.file!!
        val here = f.has("local")
        val thumb = f.optString("th").takeIf { it.isNotEmpty() }?.let { val t = java.util.Base64.getDecoder().decode(it); android.graphics.BitmapFactory.decodeByteArray(t, 0, t.size) }
        return FrameLayout(this).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
            setBackgroundColor(Color.parseColor("#33000000"))
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply { bottomMargin = dp(4) }
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; thumb?.let { setImageBitmap(it) } }, FrameLayout.LayoutParams(-1, -1))
            addView(tv(if (!here) (if (f.optBoolean("cleared")) "Removed" else if (m.mine) "" else "Downloading…") else "▶", if (here) 26f else 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER; background = circle(Color.parseColor("#88000000")); setPadding(dp(12), dp(6), dp(12), dp(6))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { if (here) playRound(m) else if (!f.optBoolean("cleared")) retryFetch(m) }
            setOnLongClickListener { onLong(); true }
        }
    }

    /** Plays a round video from memory in a big circle over the chat (so chat refreshes don't cut it off). */
    private fun playRound(m: Msg) {
        val bytes = Messenger.core.openFile(m) ?: return toast("Couldn't open that video")
        Voice.stopPlaying()
        val size = minOf(resources.displayMetrics.widthPixels - dp(40), dp(340))
        val d = android.app.Dialog(this)
        val mp = android.media.MediaPlayer()
        val texture = android.view.TextureView(this)
        texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                try {
                    mp.setDataSource(Voice.Bytes(bytes)); mp.setSurface(android.view.Surface(st))
                    mp.setOnVideoSizeChangedListener { _, vw, vh ->   // fill the circle without stretching
                        if (vw > 0 && vh > 0) {
                            val s = maxOf(size.toFloat() / vw, size.toFloat() / vh)
                            texture.setTransform(android.graphics.Matrix().apply { setScale(vw * s / size, vh * s / size, size / 2f, size / 2f) })
                        }
                    }
                    mp.setOnCompletionListener { d.dismiss() }
                    mp.prepare(); mp.start()
                } catch (e: Exception) { d.dismiss(); toast("Couldn't play that video") }
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(FrameLayout(this).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
            addView(texture, FrameLayout.LayoutParams(size, size))
            setOnClickListener { d.dismiss() }
        })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(size, size)
        d.setOnDismissListener { runCatching { mp.stop() }; runCatching { mp.release() } }
        d.show()
    }

    // ---------- stickers ----------

    /** Stickers: a picture named sticker.* — shown big, with no bubble behind it. */
    private fun isSticker(m: Msg) = m.file?.optString("n")?.startsWith("sticker.") == true && m.file?.optString("m") in listOf("image/webp", "image/gif", "image/png")

    /** Saves a picture/GIF/sticker from a chat into my stickers (photos become 512 px see-through-capable WebP). */
    private fun addToStickers(m: Msg) {
        Messenger.run({
            val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that picture")
            val mime = m.file?.optString("m") ?: ""
            if (mime == "image/gif" || (mime == "image/webp" && isAnimatedWebp(bytes))) Messenger.addSticker(bytes, mime)
            else {
                val bmp = decodeSmall(bytes, 1024) ?: throw UserError("Couldn't open that picture")
                val scale = 512f / maxOf(bmp.width, bmp.height)
                val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val out = java.io.ByteArrayOutputStream()
                @Suppress("DEPRECATION") small.compress(if (Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP, 90, out)
                Messenger.addSticker(out.toByteArray(), "image/webp")
            }
        }, { toast("Added to your stickers") }) { e -> toast(errorText(e)) }
    }

    private fun isAnimatedWebp(b: ByteArray) = b.size > 40 && String(b, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (b[20].toInt() and 0x02) != 0

    /** The Stickers tab: your saved stickers; tap sends, hold removes. */
    private fun showStickerTab(grid: LinearLayout, scroll: ScrollView, target: Any) {
        grid.removeAllViews()
        val ids = Messenger.stickers.toList()
        if (ids.isEmpty()) {
            grid.addView(tv("No stickers yet.\n\nHold any picture, GIF or sticker in a chat and tap \"Add to my stickers\".", 14.5f, dim).apply {
                gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), dp(40))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        var row: LinearLayout? = null
        ids.forEachIndexed { i, id ->
            if (i % 4 == 0) row = LinearLayout(this).also { grid.addView(it, LinearLayout.LayoutParams(-1, dp(86))) }
            val mime = id.substringAfter('|')
            row!!.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = pressable()
                Messenger.stickerBytes(id)?.let { b -> decodeSmall(b, 200)?.let { setImageBitmap(it) } }
                setOnClickListener {
                    val bytes = Messenger.stickerBytes(id) ?: return@setOnClickListener
                    val thumb = decodeSmall(bytes, 300)?.let { thumbOf(it) }
                    val name = "sticker." + if (mime == "image/gif") "gif" else "webp"
                    emojiPopup?.dismiss(); emojiPopup = null; stick = true
                    Messenger.io.execute {
                        when (target) {
                            is Contact -> Messenger.sendFile(target, name, mime, bytes, thumb)
                            is Group -> Messenger.sendGroupFile(target, name, mime, bytes, thumb)
                        }
                    }
                }
                setOnLongClickListener {
                    popup("Remove this sticker?", null, null, "Cancel" to dim, "Remove" to red) { k -> if (k == 1) { Messenger.removeSticker(id); showStickerTab(grid, scroll, target) } }
                    true
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        repeat((4 - ids.size % 4) % 4) { row!!.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f)) }
        scroll.scrollTo(0, 0)
    }

    /** Puts the photo on the clipboard (a temporary unlocked copy, wiped next time the app opens). */
    private fun copyImage(m: Msg) {
        var out: File? = null
        Messenger.run({ out = unlockedCopy(m) }, {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.wilfrombdn.telegramnt.files", out!!)
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "photo", uri))
            toast("Photo copied")
        }) { e -> toast(errorText(e)) }
    }

    private fun unlockedCopy(m: Msg): File {
        val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that photo")
        val name = m.file?.optString("n")?.takeIf { it.isNotBlank() }?.replace('/', '_') ?: "photo.jpg"
        return File(File(cacheDir, "open").apply { mkdirs() }, name).apply { writeBytes(bytes) }
    }

    private var pendingSave: Msg? = null

    /** Saves the photo to the phone's Pictures/quipu folder (only when you ask; outside the app it isn't locked). */
    private fun downloadImage(m: Msg) {
        val f = m.file ?: return
        val name = "quipu_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(m.ts)) + if (isGif(m)) ".gif" else ".jpg"
        if (Build.VERSION.SDK_INT < 29) {
            pendingSave = m
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.optString("m")).putExtra(Intent.EXTRA_TITLE, name)
            @Suppress("DEPRECATION") startActivityForResult(i, SAVE_IMAGE)
            return
        }
        Messenger.run({
            val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that photo")
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, f.optString("m", "image/jpeg"))
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/quipu")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = contentResolver
            val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw UserError("Couldn't save the photo")
            r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw UserError("Couldn't save the photo")
            values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
        }, { toast("Saved to Pictures/quipu") }) { e -> toast(errorText(e)) }
    }

    private fun saveImageTo(uri: android.net.Uri) {
        val m = pendingSave ?: return; pendingSave = null
        Messenger.run({
            val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that photo")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }, { toast("Photo saved") }) { e -> toast(errorText(e)) }
    }

    /** Full emoji list for choosing any reaction. */
    private fun pickEmoji(onPick: (String) -> Unit) {
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

    /** Chips under a bubble showing each emoji and how many people reacted; tap to toggle yours. */
    private fun addReactions(b: LinearLayout, m: Msg, toggle: (String) -> Unit) {
        if (m.reactions.isEmpty()) return
        val me = Messenger.core.me?.user
        val row = LinearLayout(this).apply { setPadding(0, dp(3), 0, dp(1)) }
        m.reactions.entries.groupBy({ it.value }, { it.key }).toList().sortedByDescending { it.second.size }.forEach { (emoji, who) ->
            val mineHere = me != null && me in who
            row.addView(tv("$emoji ${who.size}", 13f, if (mineHere) white else dim).apply {
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = round(if (mineHere) pick("#3A4654", "#D3E3F5") else panel2, 12)
                setOnClickListener { toggle(emoji) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        }
        b.addView(row)
    }

    // ---------- forwarding ----------

    /** "Forwarded from Sam" at the top of a bubble. */
    /** Messages whose spoilers you've tapped open (until the app closes). */
    private val shownSpoilers = HashSet<String>()

    /** A message's text: formatting, tappable links, highlighted @mentions, tap-to-show spoilers. */
    private fun msgText(m: Msg, ink: Int, people: Set<String>, onLong: () -> Unit): TextView {
        val me = Messenger.core.me?.user ?: ""
        return tv("", 16f, ink).apply {
            textSize = look.msg
            text = Format.render(m.text, ink, blue, people + me, m.id in shownSpoilers) {
                shownSpoilers.add(m.id); if (screen == "group") fillGroup() else fillChat()
            }
            setLinkTextColor(if (ink == Color.WHITE || ink == 0xFFFFFFFF.toInt()) Color.parseColor("#8CC4FF") else Color.parseColor("#1F6FC5"))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setOnLongClickListener { onLong(); true }   // tappable text would otherwise swallow the hold
        }
    }

    /** "today at 14:30" / "tomorrow at 08:00" / "Oct 30 at 09:00". */
    private fun whenText(at: Long): String {
        val day = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) } }
        val now = System.currentTimeMillis()
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        return when (day(at)) {
            day(now) -> "today at $clock"
            day(now + 86_400_000) -> "tomorrow at $clock"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at)) + " at $clock"
        }
    }

    /** When to send a scheduled message: a few easy choices. */
    private fun pickSendTime(done: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val opts = mutableListOf("In 30 minutes" to now + 30 * 60_000L, "In 1 hour" to now + 3_600_000L, "In 3 hours" to now + 3 * 3_600_000L)
        if (at(0, 21) > now + 30 * 60_000L) opts.add("Tonight at 21:00" to at(0, 21))
        opts.add("Tomorrow at 08:00" to at(1, 8)); opts.add("Tomorrow at 18:00" to at(1, 18))
        pickList("Schedule message", "It waits on this phone and goes out at that time (quipu needs to be running in the background).",
            opts.map { it.first }.toTypedArray()) { i -> done(opts[i].second) }
    }

    // ---------- QR codes ----------

    /** My code (friends scan it to add me) and a button to scan theirs. */
    private fun myQr() {
        val me = Messenger.core.me ?: return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                setImageBitmap(QrActivity.codeFor(me.user, 512))
                background = round(Color.WHITE, 12); setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(230), dp(230)))
            addView(tv("@${me.user}", 17f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        }
        popup("My QR code", "A friend scans this in quipu (side menu > My QR code > Scan) to add you.", col, "Close" to dim, "Scan a code" to white) { i ->
            if (i == 1) @Suppress("DEPRECATION") startActivityForResult(Intent(this, QrActivity::class.java), SCAN_QR)
        }
    }

    /** A scanned code: a friend (add them) or a group invite (join it). */
    private fun scanned(text: String) {
        if (text.startsWith("msgr01://group")) {
            Messenger.run({ val g = Messenger.core.joinGroupLink(text); Messenger.kick(); groupToOpen = g.id }, { groupToOpen?.let { showGroup(it) } }) { e -> toast(errorText(e)) }
            return
        }
        val user = text.removePrefix(QrActivity.ADD).trim().lowercase()
        if (!Core.USERNAME.matches(user)) return toast("That isn't a quipu code")
        if (user == Messenger.core.me?.user) return toast("That's your own code")
        Messenger.core.contact(user)?.let { return showChat(it.user) }
        popup("Add @$user?", "Sends them a friend request.", null, "Cancel" to dim, "Add" to white) { i ->
            if (i == 1) Messenger.run({ Messenger.core.addFriend(user); Messenger.kick() }, { showChat(user) }) { e -> toast(errorText(e)) }
        }
    }

    // ---------- polls ----------

    /** Attach > Poll (groups): a question and 2-10 options. */
    private fun createPoll(g: Group) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val q = field("Ask a question", "")
        col.addView(q)
        val opts = mutableListOf<EditText>()
        val optCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addOpt() { if (opts.size < 10) field("Option ${opts.size + 1}", "").also { opts.add(it); optCol.addView(it) } }
        addOpt(); addOpt()
        col.addView(optCol)
        col.addView(tv("+ Add an option", 15f, blue, true).apply { setPadding(dp(4), dp(10), dp(4), dp(4)); setOnClickListener { addOpt() } })
        popup("New poll", null, ScrollView(this).apply { addView(col) }, "Cancel" to dim, "Create" to white) { i ->
            if (i != 1) return@popup
            val options = opts.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
            stick = true
            Messenger.run({ Messenger.core.sendPoll(g, q.text.toString(), options) }, {}) { e -> toast(errorText(e)) }
        }
    }

    /** A poll in a group: tap an option to vote (tap your vote again to take it back); bars show results once you've voted. */
    private fun pollView(g: Group, m: Msg, ink: Int): View {
        val poll = m.poll!!
        val me = Messenger.core.me?.user ?: ""
        val options = poll.optJSONArray("o") ?: org.json.JSONArray()
        val mine = m.votes[me]
        val total = m.votes.size
        val faded = (ink and 0x00FFFFFF) or (0x99 shl 24)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(250), -2)
            addView(tv(poll.optString("q"), 16f, ink, true).apply { setPadding(0, dp(2), 0, dp(2)) })
            addView(tv("Poll", 12.5f, faded).apply { setPadding(0, 0, 0, dp(6)) })
            for (i in 0 until options.length()) {
                val count = m.votes.values.count { it == i }
                val pct = if (total > 0) count * 100 / total else 0
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)); background = pressable()
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(tv(if (mine == null) "○" else if (mine == i) "✓" else "", 15f, if (mine == i) blue else faded).apply { minWidth = dp(22) })
                        addView(tv(options.optString(i), 15f, ink), LinearLayout.LayoutParams(0, -2, 1f))
                        if (mine != null) addView(tv("$pct%", 13.5f, faded, true))
                    })
                    if (mine != null) addView(FrameLayout(context).apply {
                        addView(View(context).apply { background = round((ink and 0x00FFFFFF) or (0x22 shl 24), 3) }, FrameLayout.LayoutParams(-1, dp(5)))
                        addView(View(context).apply { background = round(blue, 3) }, FrameLayout.LayoutParams(maxOf(dp(5), dp(228) * pct / 100), dp(5)))
                    }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(4); leftMargin = dp(22) })
                    setOnClickListener { Messenger.run({ Messenger.core.vote(g, m, if (mine == i) -1 else i) }) }
                    setOnLongClickListener { groupMessageMenu(g, m); true }
                })
            }
            addView(tv(if (total == 1) "1 vote" else "$total votes", 13f, faded).apply { setPadding(0, dp(6), 0, 0) })
        }
    }

    // ---------- location ----------

    /** Attach > Location: asks first, then finds where you are once and sends it. Nothing is tracked. */
    private fun shareLocation() {
        val c = attachTo?.let { Messenger.core.contact(it) }
        val g = attachGroupId?.let { Messenger.core.group(it) }
        if (c == null && g == null) return
        val fine = Manifest.permission.ACCESS_FINE_LOCATION; val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(fine) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(coarse) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(fine, coarse), LOCATION_FOR_SHARE); return
        }
        popup("Send your location?", "Sends where you are right now to ${c?.name ?: g!!.name}. Just this once: it isn't tracked or updated.",
            null, "Cancel" to dim, "Send" to white) { i -> if (i == 1) locateAndSend(c, g) }
    }

    @android.annotation.SuppressLint("MissingPermission")   // checked in shareLocation
    private fun locateAndSend(c: Contact?, g: Group?) {
        val lm = getSystemService(android.location.LocationManager::class.java)
        val provider = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return toast("Turn on Location in your phone's settings first")
        toast("Finding your location…")
        var done = false
        fun send(l: android.location.Location?) {
            if (done) return
            done = true
            val best = l ?: listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (best == null) { toast("Couldn't find your location. Try again outside or with Wi-Fi on."); return }
            stick = true
            Messenger.sendLocation(c, g, org.json.JSONObject().put("la", best.latitude).put("lo", best.longitude).put("acc", best.accuracy.toDouble()))
        }
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { send(it) }
        else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { send(it) }, mainLooper)
        window.decorView.postDelayed({ send(null) }, 25_000)   // GPS can take a while indoors: fall back to the last fix
    }

    /** A shared location: the place and how exact it is; tap opens it in a maps app. */
    private fun locationCard(loc: org.json.JSONObject, ink: Int): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), dp(6), dp(4))
        val la = loc.optDouble("la"); val lo = loc.optDouble("lo")
        addView(icon(R.drawable.ic_location, ink, 26, 8).apply { background = circle(Color.parseColor("#22FFFFFF")) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            addView(tv("Location", 15f, ink, true))
            addView(tv("%.5f, %.5f".format(Locale.US, la, lo) + (loc.optDouble("acc", 0.0).takeIf { it > 0 }?.let { " · ±${it.toInt()} m" } ?: ""), 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
            addView(tv("Open in Maps", 13.5f, blue, true).apply { setPadding(0, dp(2), 0, 0) })
        })
        setOnClickListener {
            val uri = android.net.Uri.parse("geo:$la,$lo?q=$la,$lo")
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No maps app on this phone") }
        }
    }

    /** The link card under a message: site, title, a little description. Tap opens the link. */
    private fun linkCard(lp: org.json.JSONObject, ink: Int): View = LinearLayout(this).apply {
        val faded = (ink and 0x00FFFFFF) or (0xB0 shl 24)
        setPadding(0, dp(4), 0, dp(2))
        addView(View(context).apply { setBackgroundColor(blue) }, LinearLayout.LayoutParams(dp(3), -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0)
            lp.optString("s").takeIf { it.isNotEmpty() }?.let { addView(tv(it, 13.5f, blue, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
            addView(tv(lp.optString("t"), 14.5f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            lp.optString("d").takeIf { it.isNotEmpty() }?.let { addView(tv(it, 13.5f, faded).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }) }
        }, LinearLayout.LayoutParams(-2, -2))
        layoutParams = LinearLayout.LayoutParams(dp(250), -2).apply { bottomMargin = dp(2) }
        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(lp.optString("u")))) } }
    }

    /**
     * The link preview above the message box: when you type a link, the page's title is fetched through
     * Tor and shown here; it goes with the message if it's ready when you send. ✕ drops it.
     */
    private inner class LinkWatch {
        val bar = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(listBg); setPadding(dp(14), dp(6), dp(4), 0); visibility = View.GONE }
        private var url: String? = null
        private var card: org.json.JSONObject? = null
        private var dropped: String? = null
        private val h = android.os.Handler(android.os.Looper.getMainLooper())

        fun watch(box: EditText) {
            url = null; card = null; dropped = null; bar.visibility = View.GONE
            (bar.parent as? ViewGroup)?.removeView(bar)   // one bar, moved to whichever chat is open
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    val u = LinkPreview.firstUrl(e?.toString().orEmpty())
                    if (u == url) return
                    url = u; card = null; h.removeCallbacksAndMessages(null)
                    if (u == null || u == dropped) { bar.visibility = View.GONE; return }
                    show("Getting link preview…", null)
                    h.postDelayed({
                        Messenger.io.execute {
                            val got = runCatching { LinkPreview.fetch(u) }.getOrNull()
                            runOnUiThread {
                                if (url != u) return@runOnUiThread
                                card = got
                                if (got == null) bar.visibility = View.GONE else show(got.optString("t"), got.optString("s"))
                            }
                        }
                    }, 700)
                }
            })
        }

        private fun show(title: String, site: String?) {
            bar.removeAllViews()
            bar.addView(View(this@MainActivity).apply { setBackgroundColor(blue) }, LinearLayout.LayoutParams(dp(3), dp(34)))
            bar.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(tv(site ?: "Link", 13.5f, blue, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(tv(title, 14f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(tv("✕", 17f, dim).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { dropped = url; card = null; bar.visibility = View.GONE } })
            bar.visibility = View.VISIBLE
        }

        /** The card for [text] if it's been fetched (and not dropped); clears the bar. */
        fun ready(text: String): org.json.JSONObject? {
            val c = card?.takeIf { url != null && LinkPreview.firstUrl(text) == url && url != dropped }
            url = null; card = null; bar.visibility = View.GONE; h.removeCallbacksAndMessages(null)
            return c
        }
    }

    private val links by lazy { LinkWatch() }

    private fun fwdLabel(user: String, ink: Int) = tv("Forwarded from ${displayName(user)}", 13.5f, (ink and 0x00FFFFFF) or (0xCC shl 24)).apply {
        setTypeface(typeface, android.graphics.Typeface.ITALIC); setPadding(0, 0, 0, dp(3))
    }

    /** Text, or an attachment that's on this phone (not one still downloading or cleared). */
    private fun canForward(m: Msg) = m.call == null && m.state != Msg.NOTE &&
        (m.file?.has("local") ?: m.text.isNotEmpty())

    /** Pick a chat to forward [m] to; it keeps who first wrote it. Then opens that chat. */
    private fun forwardPicker(m: Msg, author: String) {
        val from = m.fwd ?: author
        val core = Messenger.core
        val me = core.me?.user ?: ""
        val targets = synchronized(core) {
            core.contacts.filter { it.canChat }.map { Triple(it.name, it.lastTs, it as Any) } +
                core.groups.filter { it.canPost(me) }.map { Triple(it.name, it.lastTs, it as Any) }
        }.sortedByDescending { it.second }
        if (targets.isEmpty()) { toast("No chats to forward to yet"); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = android.app.Dialog(this)
        for ((name, _, t) in targets) col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); background = pressable()
            addView(avatar(name, 40, (t as? Contact)?.avatar))
            addView(tv(name, 16f, white).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            setOnClickListener {
                d.dismiss()
                Messenger.run({ Messenger.forward(m, from, t as? Contact, t as? Group) }, {
                    stick = true
                    if (t is Contact) showChat(t.user) else showGroup((t as Group).id)
                }) { e -> toast(errorText(e)) }
            }
        })
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(bar, 4); setPadding(0, dp(14), 0, dp(6))
            addView(tv("Forward to…", 17.5f, white, true).apply { setPadding(dp(20), 0, dp(20), dp(8)) })
            addView(col)
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(ScrollView(this).apply { addView(wrap) })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun messageMenu(c: Contact, m: Msg) {
        val me = Messenger.core.me?.user
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        if (m.state == Msg.SCHEDULED) opts.add("Send now" to { Messenger.sendScheduled(m) })
        if (c.status != Contact.ASKING) opts.add("Reply" to { startReply(m, chatBox) })
        if (m.text.isNotEmpty()) opts.add((if (m.file != null) "Copy text" else "Copy") to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("msg", m.text)); toast("Copied")
        })
        opts.addAll(imageActions(m, if (m.text.isNotEmpty()) "Copy photo" else "Copy"))
        if (canForward(m)) opts.add("Forward" to { forwardPicker(m, authorOf(m)) })
        if (c.canChat && m.call == null) opts.add((if (c.pinned == m.id) "Unpin" else "Pin") to {
            Messenger.run({ Messenger.core.pinMessage(c, if (c.pinned == m.id) null else m) })
        })
        if (m.mine && m.text.isNotEmpty() && c.canChat) opts.add("Edit" to { startEdit(m, chatBox) })
        if (m.mine && m.state != Msg.FAILED && m.state != Msg.SCHEDULED) opts.add("Delete for everyone" to {
            Messenger.run({ Messenger.core.deleteMessage(c, m, forEveryone = true) }, { fillChat() })
        })
        opts.add("Delete for me" to { Messenger.core.deleteMessage(c, m, forEveryone = false); fillChat() })
        longPressMenu(m.reactions[me], { e -> Messenger.react(c, m, e); fillChat() }, opts)
    }

    // ---------- mute, block, pinned message, drafts, search ----------

    private fun toggleMute(key: String) {
        val ms = Messenger.muted
        val on = key !in ms
        if (on) ms.add(key) else ms.remove(key)
        Messenger.settingsChanged()
        toast(if (on) "Muted: no notifications from this chat" else "Unmuted")
        redraw()
    }

    private fun blockConfirm(c: Contact, on: Boolean) {
        if (!on) { Messenger.core.block(c.user, false); Messenger.save(); toast("Unblocked"); showChat(c.user); return }
        popup("Block ${c.name}?", "They won't be able to message or call you, and won't see when you're online, typing or have read their messages. They aren't told.",
            null, "Cancel" to dim, "Block" to red) { i ->
            if (i == 1) { Messenger.core.block(c.user, true); Messenger.save(); showChat(c.user) }
        }
    }

    /** Scrolls the chat to message [id] and flashes it. */
    private fun jumpTo(id: String, list: LinearLayout?, sc: ScrollView?) {
        val l = list ?: return; val s = sc ?: return
        for (i in 0 until l.childCount) {
            val v = l.getChildAt(i)
            if (v.tag == id) {
                s.smoothScrollTo(0, maxOf(0, v.top - dp(80)))
                // brief flash so you can see which message it was
                v.foreground = android.graphics.drawable.ColorDrawable(Color.parseColor("#304EA4F6"))
                v.postDelayed({ v.foreground = null }, 900)
                return
            }
        }
        toast("That message isn't loaded any more")
    }

    /** The "📌 Pinned message" strip under a chat's title: tap to jump to it, ✕ unpins it (for everyone). */
    private fun pinnedBar(id: String?, msgs: List<Msg>, list: LinearLayout?, sc: ScrollView?, canUnpin: Boolean, unpin: () -> Unit): View? {
        val m = synchronized(Messenger.core) { msgs.firstOrNull { it.id == id } } ?: return null
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(bar); setPadding(dp(14), dp(6), dp(4), dp(6)); background = pressable()
            addView(View(context).apply { setBackgroundColor(blue) }, LinearLayout.LayoutParams(dp(3), dp(34)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(tv("Pinned message", 13.5f, blue, true))
                addView(tv(preview(m), 14f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (canUnpin) addView(tv("✕", 17f, dim).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { unpin() } })
            setOnClickListener { jumpTo(m.id, list, sc) }
        }
    }

    /** "sam is typing…" / "sam and jo are typing…", or null. */
    private fun groupTyping(g: Group): String? {
        val now = System.currentTimeMillis()
        val who = g.typing.filter { it.value > now }.keys.map { u -> Messenger.core.contact(u)?.name ?: "@$u" }
        return when (who.size) {
            0 -> null
            1 -> "${who[0]} is typing…"
            2 -> "${who[0]} and ${who[1]} are typing…"
            else -> "${who.size} people are typing…"
        }
    }

    /** Type "@" in a group: a list of matching members appears above the box; tap one to put in "@username ". */
    private fun mentionPicker(box: EditText, g: Group): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(listBg); visibility = View.GONE }
        fun update() {
            col.removeAllViews()
            val cur = box.selectionStart
            val before = if (cur >= 0) box.text.substring(0, cur) else ""
            val m = Regex("(?:^|\\s)@([A-Za-z0-9_]*)$").find(before)
            val me = Messenger.core.me?.user
            val q = m?.groupValues?.get(1)?.lowercase()
            val hits = if (q == null) emptyList() else synchronized(Messenger.core) { g.members.toList() }
                .filter { it != me && (it.startsWith(q) || (Messenger.core.contact(it)?.name?.lowercase()?.contains(q) == true)) }.sorted().take(4)
            col.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
            for (u in hits) col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(6)); background = pressable()
                addView(avatar(displayName(u), 32, Messenger.core.contact(u)?.avatar))
                addView(tv(displayName(u), 15f, white, true).apply { setPadding(dp(12), 0, dp(8), 0) })
                addView(tv("@$u", 14f, dim))
                setOnClickListener {
                    val c2 = box.selectionStart
                    val start = c2 - (q?.length ?: 0) - 1
                    if (start >= 0) { box.text.replace(start, c2, "@$u "); box.setSelection(start + u.length + 2) }
                }
            })
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { update() }
        })
        return col
    }

    /** Remembers what's in the box for [key] as you type (cleared when sent). Not while editing a message. */
    private fun keepDraft(box: EditText, key: String) = box.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) {
            if (editing != null) return
            val t = e?.toString().orEmpty()
            if (t.isBlank()) Messenger.drafts.remove(key) else Messenger.drafts[key] = t
        }
    })

    private fun savedAvatar(sizeDp: Int): View = FrameLayout(this).apply {
        background = circle(blue)
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_bookmark); setColorFilter(Color.WHITE) },
            FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Search results inside messages (all chats and groups): tap one to open the chat at that message. */
    private fun messageResults(list: LinearLayout, q: String) {
        val core = Messenger.core
        data class Hit(val title: String, val m: Msg, val open: () -> Unit)
        val hits = synchronized(core) {
            core.contacts.filter { it.status != Contact.ASKING }.flatMap { c -> c.msgs.filter { it.state != Msg.NOTE && it.text.lowercase().contains(q) }
                .map { m -> Hit(c.name, m) { showChat(c.user, m.id to dp(80)) } } } +
            core.groups.flatMap { g -> g.msgs.filter { it.state != Msg.NOTE && it.text.lowercase().contains(q) }
                .map { m -> Hit(g.name, m) { showGroup(g.id, m.id to dp(80)) } } }
        }.sortedByDescending { it.m.ts }.take(60)
        if (hits.isEmpty()) return
        list.addView(tv("Messages", 14f, dim, true).apply { setPadding(dp(16), dp(14), dp(16), dp(6)); setBackgroundColor(nestedBg) })
        for (h in hits) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)); background = pressable()
            addView(LinearLayout(context).apply {
                addView(tv(h.title, 15.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(tv(time(h.m.ts), 13f, dim))
            })
            // the matching part, with the words around it
            val t = h.m.text.replace('\n', ' ')
            val at = t.lowercase().indexOf(q)
            val start = maxOf(0, at - 30)
            val shown = (if (start > 0) "…" else "") + t.substring(start)
            val span = android.text.SpannableString(shown)
            val hit = shown.lowercase().indexOf(q)
            if (hit >= 0) span.setSpan(android.text.style.ForegroundColorSpan(blue), hit, hit + q.length, 0)
            addView(tv("", 14.5f, dim).apply { text = span; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
            setOnClickListener { search = ""; screenSearch = false; h.open() }
        })
    }

    private fun chatMenu(c: Contact, anchor: View) {
        val key = "u:${c.user}"
        val isMuted = key in Messenger.muted
        val isBlocked = c.user in Messenger.core.blocked
        if (c.status == Contact.SAVED) {
            dropdown(anchor, Gravity.END, "Delete all saved messages" to { deleteChatConfirm(c) })
            return
        }
        dropdown(anchor, Gravity.END,
            (if (isMuted) "Unmute" else "Mute") to { toggleMute(key) },
            (if (isBlocked) "Unblock" else "Block") to { blockConfirm(c, !isBlocked) },
            "Safety code" to { safety(c) },
            (if (c.ttl == 0) "Disappearing messages" else "Disappearing: on") to { timerMenu(c) },
            "Rename" to {
                val box = field("Name", c.name)
                popup("Rename", null, box, "Cancel" to dim, "Save" to white) { i ->
                    if (i == 1) box.text.toString().trim().take(60).takeIf { it.isNotEmpty() }?.let { c.name = it; Messenger.save(); fillChat() }
                }
            },
            "Remove friend" to {
                popup("Delete chat with ${c.name}?", "This erases the whole chat from this phone for good. The server keeps no copy, no backup and no way to recover it — once it's gone it's gone. Are you sure?", null,
                    "Cancel" to dim, "Delete" to red) { i -> if (i == 1) { Messenger.core.remove(c); showChats() } }
            },
        )
    }

    private fun timerMenu(c: Contact) {
        val options = listOf("Off" to 0, "1 minute" to 60, "1 hour" to 3600, "1 day" to 86400, "1 week" to 604800)
        val labels = options.map { it.first }.toTypedArray()
        pickList("Disappearing messages", "New messages delete themselves on both phones after the time you pick.", labels) { i ->
            Messenger.run({ Messenger.core.setTimer(c, options[i].second); Messenger.kick() }, { fillChat() }) { e -> toast(errorText(e)) }
        }
    }

    /** A simple dark list dialog (used where there are more than a few choices). */
    private fun pickList(title: String, sub: String?, items: Array<String>, onPick: (Int) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, label ->
            col.addView(tv(label, 15.5f, white).apply { setPadding(dp(20), dp(11), dp(20), dp(11)); background = pressable(); setOnClickListener { listDialog?.dismiss(); onPick(i) } })
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(bar, 4)
            setPadding(0, dp(14), 0, dp(6))
            addView(tv(title, 17.5f, white, true).apply { setPadding(dp(20), 0, dp(20), if (sub == null) dp(4) else dp(2)) })
            sub?.let { addView(tv(it, 14f, dim).apply { setPadding(dp(20), 0, dp(20), dp(6)) }) }
            addView(gap(4))
        }
        wrap.addView(col)
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(ScrollView(this).apply { addView(wrap) })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
        listDialog = d
        d.show()
    }

    private var listDialog: android.app.Dialog? = null

    private var drawer: DrawerLayout? = null

    private fun drawerPanel(d: DrawerLayout): View {
        val me = Messenger.core.me
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(listBg)
            fitsSystemWindows = true
        }
        // header: avatar on the left, name + @username to its right
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(panel2, bar))
            setPadding(dp(20), dp(24), dp(16), dp(20))
            addView(avatar(me?.name ?: "", 58, me?.avatar))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(tv(me?.name ?: "", 18f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(tv("@${me?.user ?: ""}", 14f, dim).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv("›", 26f, dim))
            isClickable = true
            foreground = pressable()
            setOnClickListener { d.closeDrawer(GravityCompat.START); showProfile() }
        })
        fun item(res: Int, label: String, sub: String? = null, go: () -> Unit) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(15), dp(20), dp(15))
            background = pressable()
            isClickable = true
            addView(icon(res, dim, 23, 0))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(26), 0, 0, 0)
                addView(tv(label, 16.5f, white))
                sub?.let { addView(tv(it, 12.5f, dim).apply { setPadding(0, dp(1), 0, 0) }) }
            })
            setOnClickListener { d.closeDrawer(GravityCompat.START); go() }
        })
        fun divider() = col.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) })
        col.addView(gap(6))
        item(R.drawable.ic_edit, "New Message") { showNewChat() }
        item(R.drawable.ic_more, "New Group") { showNewGroup() }
        item(R.drawable.ic_search, "Join Group", "with an invite link") { joinGroupPrompt() }
        item(R.drawable.ic_folder, "New Folder", "group chats, e.g. Family") { newFolder() }
        item(R.drawable.ic_bookmark, "Saved Messages", "notes to yourself, on this phone") { showChat(Messenger.core.savedChat().user) }
        item(R.drawable.ic_qr, "My QR code", "or scan a friend's") { myQr() }
        divider()
        item(R.drawable.ic_palette, "Appearance", "colours, text size, bubbles") { showAppearance() }
        item(R.drawable.ic_menu, "Settings") { showSettings() }
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(tv("quipu ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")}", 12f, faint).apply {
            setPadding(dp(22), dp(14), dp(22), dp(16))
        })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(listBg); addView(col, LinearLayout.LayoutParams(-1, -1)) }
    }

    /** A plain dark rectangle that drops down right under [anchor]. */
    private fun dropdown(anchor: View, side: Int, vararg items: Pair<String, () -> Unit>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = round(menuBg, 4)
        }
        val pw = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for ((label, action) in items) box.addView(tv(label, 16f, white).apply {
            minWidth = dp(200)
            setPadding(dp(20), dp(13), dp(28), dp(13))
            setOnClickListener { pw.dismiss(); action() }
        })
        pw.elevation = dp(8).toFloat()
        pw.setBackgroundDrawable(round(menuBg, 4))
        pw.showAsDropDown(anchor, 0, 0, side)
    }

    /**
     * The app's own pop-up (instead of Android's): title, optional text or [content],
     * and buttons on the right. [onButton] gets the index of the button tapped.
     */
    private fun popup(title: String, message: String?, content: View?, vararg buttons: Pair<String, Int>, onButton: (Int) -> Unit = {}) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(4))
            background = round(bar, 4)
        }
        col.addView(tv(title, 17.5f, white, true).apply { setPadding(0, 0, dp(8), dp(6)) })
        message?.let { col.addView(tv(it, 14.5f, dim).apply { setLineSpacing(0f, 1.08f); setPadding(0, 0, dp(8), dp(4)) }) }
        content?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { rightMargin = dp(8) }) }
        val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(4), 0, 0) }
        buttons.forEachIndexed { i, (label, color) ->
            row.addView(tv(label.uppercase(), 14f, color, true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { d.dismiss(); onButton(i) }
            })
        }
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(col)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun safety(c: Contact) {
        val code = Messenger.core.safetyCode(c) ?: return
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Compare this with ${c.name}'s screen, in person or on a call. If every number matches, nobody is listening in between.", 14.5f, dim))
            addView(gap(16))
            addView(tv(code.replace(" ", "  "), 21f, white, true).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE })
        }
        if (c.verified) popup("Safety code", null, view, "Mark not checked" to dim, "OK" to white) { i ->
            if (i == 0) { c.verified = false; Messenger.save(); fillChat() }
        } else popup("Safety code", null, view, "Not now" to dim, "They match" to white) { i ->
            if (i == 1) { c.verified = true; Messenger.save(); fillChat() }
        }
    }

    // ---------- attachments ----------

    private var attachTo: String? = null
    private var attachGroupId: String? = null
    private var emojiPopup: android.widget.PopupWindow? = null
    private var emojiData: List<Pair<String, List<String>>>? = null
    private val pictures = HashMap<String, android.graphics.Bitmap?>()

    private fun preview(m: Msg): String = Format.plain(previewRaw(m))

    private fun previewRaw(m: Msg): String {
        if (m.loc != null) return "📍 Location"
        if (isRound(m)) return "📹 Video message"
        m.poll?.let { return "📊 " + it.optString("q") }
        m.call?.let { return callTitle(it) + if (it.optString("s") == "ok") " (${callDuration(it.optInt("dur"))})" else "" }
        val f = m.file ?: return m.text
        if (Voice.isVoice(f)) return "🎤 Voice message" + (Voice.unpack(f.optString("th"))?.let { " (${Voice.clock(it.first)})" } ?: "")
        return if (f.optString("m") == "image/gif") "GIF" else if (f.optString("m") == "image/webp") "Sticker" else if (f.optString("m").startsWith("image/")) "Photo" else "File: " + f.optString("n")
    }

    private fun attachMenu(anchor: View) {   // target already set in attachTo / attachGroupId
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(menuBg, 10)
            elevation = dp(10).toFloat()
            setPadding(0, dp(6), 0, dp(6))
        }
        val pw = android.widget.PopupWindow(panel, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        fun row(res: Int, label: String, go: () -> Unit) = panel.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(20), dp(14))
            background = pressable()
            isClickable = true
            addView(icon(res, white, 22, 0))
            addView(tv(label, 16f, white).apply { setPadding(dp(16), 0, 0, 0) })
            setOnClickListener { pw.dismiss(); go() }
        })
        row(R.drawable.ic_camera, "Camera") {
            @Suppress("DEPRECATION") startActivityForResult(Intent(this, CameraActivity::class.java), TAKE_MEDIA)
        }
        row(R.drawable.ic_attach, "Media") {
            val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_PHOTO)
        }
        row(R.drawable.ic_file, "File") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_FILE)
        }
        row(R.drawable.ic_location, "Location") { shareLocation() }
        attachGroupId?.let { gid -> row(R.drawable.ic_poll, "Poll") { Messenger.core.group(gid)?.let { createPoll(it) } } }
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        // expand upward, just above the clip
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(190), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        pw.showAsDropDown(anchor, -dp(130), -(anchor.height + panel.measuredHeight + dp(6)))
    }

    /** Reads the bundled emoji list once, keeping only ones this phone's font can actually draw. */
    private fun loadEmoji(): List<Pair<String, List<String>>> {
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

    // ---------- GIFs ----------

    private fun isGif(m: Msg) = m.file?.optString("m") == "image/gif"
    /** GIFs and (possibly animated) WebP stickers both play in place. */
    private fun isAnimated(m: Msg) = m.file?.optString("m").let { it == "image/gif" || it == "image/webp" }

    /** A GIF / sticker / picture chosen from the phone keyboard's own panel: send it to this chat. */
    private fun keyboardContent(uri: android.net.Uri, mime: String, done: () -> Unit, target: Any) {
        stick = true
        Messenger.run({
            val data = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } finally { done() }
                ?: throw UserError("Couldn't read that from the keyboard")
            if (data.size > Core.MAX_FILE) throw UserError("That's too big to send")
            val animated = mime == "image/gif" || mime == "image/webp"
            val bmp = decodeSmall(data, 1600)
            val (name, type, bytes) = if (animated) Triple(if (mime == "image/gif") "animation.gif" else "sticker.webp", mime, data)
                else Triple("photo.jpg", "image/jpeg", jpeg(bmp ?: throw UserError("Couldn't open that picture"), 1600, 85))
            val thumb = bmp?.let { thumbOf(it) }
            when (target) {
                is Contact -> Messenger.sendFile(target, name, type, bytes, thumb)
                is Group -> Messenger.sendGroupFile(target, name, type, bytes, thumb)
            }
        }, {}) { e -> toast(errorText(e)) }
    }

    /** GIFs already on this phone (sent or received in any chat), newest first, no repeats. */
    private fun recentGifs(): List<Msg> {
        val core = Messenger.core
        return synchronized(core) { core.contacts.flatMap { it.msgs } + core.groups.flatMap { it.msgs } }
            .filter { isAnimated(it) && it.file?.has("local") == true }
            .sortedByDescending { it.ts }
            .distinctBy { it.file?.optString("n") to it.file?.optInt("s") }
            .take(30)
    }

    /** Animated drawables, decoded once per message (fillChat rebuilds the views often). */
    private val gifCache = HashMap<String, android.graphics.drawable.Drawable>()

    /** Decodes [bytes] as a playing GIF (Android 9+), or its first frame on older phones. Off the main thread. */
    private fun decodeGif(bytes: ByteArray, maxSide: Int): android.graphics.drawable.Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
                val big = maxOf(info.size.width, info.size.height)
                if (big > maxSide) d.setTargetSampleSize(big / maxSide + 1)
            }
        } else decodeSmall(bytes, maxSide)?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
    }.getOrNull()

    /** Puts message [m]'s GIF into [iv], playing (decoded in the background the first time). */
    private fun playGif(iv: ImageView, m: Msg, maxSide: Int) {
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
    private fun showGifTab(grid: LinearLayout, scroll: ScrollView, target: Any) {
        grid.removeAllViews()
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "Search GIFs"; setHintTextColor(dim); setTextColor(white); textSize = 15f
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
        grid.addView(tv("Powered by GIPHY", 11f, faint).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(4)) }, LinearLayout.LayoutParams(-1, -2))

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
            val loading = tv("Loading GIFs…", 13.5f, dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)) }
            results.addView(loading, LinearLayout.LayoutParams(-1, -2))
            var found: List<Pair<Relay.Gif, android.graphics.drawable.Drawable?>> = emptyList()
            Messenger.run({
                found = Messenger.core.relay.gifs(q, offset).map { it to decodeGif(it.preview, dp(140)) }
            }, {
                if (my != gen) return@run   // a newer search replaced this one
                results.removeView(loading)
                if (found.isEmpty() && offset == 0) results.addView(tv(if (q.isEmpty()) "No GIFs right now" else "No GIFs for \"$q\"", 14f, dim).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16))
                }, LinearLayout.LayoutParams(-1, -2))
                rows(results, found.size) { row, i -> cell(row) { iv ->
                    val (g, d) = found[i]
                    d?.let { iv.setImageDrawable(it); if (Build.VERSION.SDK_INT >= 28 && it is android.graphics.drawable.AnimatedImageDrawable) it.start() }
                    iv.setOnClickListener { emojiPopup?.dismiss(); emojiPopup = null; sendGiphy(g, target) }
                } }
                if (found.size >= 24) results.addView(tv("More", 14f, white, true).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); background = pressable(); isClickable = true
                    setOnClickListener { results.removeView(this); load(q, offset + found.size) }
                }, LinearLayout.LayoutParams(-1, -2))
            }) { e ->
                if (my != gen) return@run
                loading.text = if (e is Relay.RelayError) errorText(e) else "Couldn't load GIFs. Check your connection."
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
    private fun sendGiphy(g: Relay.Gif, target: Any) {
        stick = true
        toast("Sending GIF…")
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
    private fun resendGif(m: Msg, target: Any) {
        stick = true
        Messenger.run({
            val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that GIF")
            val thumb = decodeSmall(bytes, 400)?.let { thumbOf(it) }
            val name = m.file?.optString("n")?.ifBlank { null } ?: "animation.gif"
            when (target) {
                is Contact -> Messenger.sendFile(target, name, "image/gif", bytes, thumb)
                is Group -> Messenger.sendGroupFile(target, name, "image/gif", bytes, thumb)
            }
        }, {}) { e -> toast(errorText(e)) }
    }

    /** Telegram-style emoji panel that pops up above the clip and inserts into [box] at the cursor. */
    private fun emojiPicker(anchor: View, box: EditText, target: Any) {
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

    private fun jpeg(b: android.graphics.Bitmap, maxSide: Int, quality: Int): ByteArray {
        val k = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val sized = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
        return ByteArrayOutputStream().also { sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    /** Tiny blurry preview that travels inside the message, shown until the photo arrives. */
    private fun thumbOf(b: android.graphics.Bitmap): ByteArray {
        var q = 60
        while (true) {
            val t = jpeg(b, 96, q)
            if (t.size <= 5000 || q <= 20) return t
            q -= 10
        }
    }

    private fun attachPicked(uri: android.net.Uri, asPhoto: Boolean) {
        val c = attachTo?.let { Messenger.core.contact(it) }
        val g = attachGroupId?.let { Messenger.core.group(it) }
        if (c == null && g == null) return
        // send to whichever target the attach menu was opened from
        fun out(name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
            if (g != null) Messenger.sendGroupFile(g, name, mime, data, thumb) else Messenger.sendFile(c!!, name, mime, data, thumb)
        }
        toast("Locking and sending…")
        Messenger.run({
            var name = "file"
            var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                    cur.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cur.isNull(it)) size = cur.getLong(it) }
                }
            }
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            if (mime != "image/gif" && (asPhoto || mime.startsWith("image/"))) {
                // re-saved as JPEG: smaller, and hidden details like GPS location are dropped
                val bmp = loadPhoto(uri) ?: throw UserError("Couldn't open that photo")
                out(name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg(bmp, 1600, 85), thumbOf(bmp))
            } else {
                if (size > Core.MAX_FILE) throw UserError("Files can be up to ${Core.MAX_FILE / 1024 / 1024} MB")
                val data = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserError("Couldn't read that file")
                out(name, mime, data, if (mime == "image/gif") decodeSmall(data, 400)?.let { thumbOf(it) } else null)
            }
            // the camera's unencrypted temp file has now been read into memory; don't leave it on disk
            if (uri.authority == "$packageName.files") runCatching { contentResolver.delete(uri, null, null) }
        }, {}) { e -> toast(errorText(e)) }
    }

    private fun sizeText(n: Int) = when {
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    // ---------- voice messages ----------

    /**
     * The button at the end of the message box: send when there's text, otherwise a mic.
     * Hold the mic to record, let go to send, slide left to cancel, slide up to lock and talk hands-free (like Telegram).
     */
    private fun sendOrMic(row: LinearLayout, box: EditText, send: () -> Unit, special: (Boolean, Long) -> Unit, sendVoice: (ByteArray, ByteArray) -> Unit): View {
        val btn = icon(R.drawable.ic_mic, white)
        fun showIcon() = btn.setImageResource(if (!box.text.isNullOrBlank()) R.drawable.ic_send else if (videoMode) R.drawable.ic_videocam else R.drawable.ic_mic)
        // tap the mic = switch to round video (and back); hold = record
        val openRound = Runnable {
            roundHeld = true
            btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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
            btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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
            runCatching { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(25, 120)) }
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
                    runCatching { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(25, 120)) }
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
    private fun voiceView(m: Msg, ink: Int, onLong: () -> Unit): View {
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

    private fun attachmentView(m: Msg, ink: Int, onLong: () -> Unit = {}): View {
        val f = m.file!!
        val here = f.has("local")
        if (Voice.isVoice(f)) return voiceView(m, ink, onLong)
        if (isRound(m)) return roundView(m, onLong)
        if (f.optString("m").startsWith("image/")) {
            val bmp = pictures[m.id] ?: run {
                val full = if (here) Messenger.core.openFile(m)?.let { decodeSmall(it, 720) } else null
                val shown = full ?: f.optString("th").takeIf { it.isNotEmpty() }?.let {
                    val t = java.util.Base64.getDecoder().decode(it); android.graphics.BitmapFactory.decodeByteArray(t, 0, t.size)
                }
                if (full != null) pictures[m.id] = full
                shown
            }
            val sticker = isSticker(m)
            val w = if (sticker) dp(170) else dp(240)
            val h = if (bmp != null) (w * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(dp(if (sticker) 40 else 80), dp(360)) else dp(160)
            return FrameLayout(this).apply {
                addView(ImageView(context).apply {
                    scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    bmp?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.parseColor("#33000000"))
                    if (here && isAnimated(m)) playGif(this, m, dp(360))
                }, FrameLayout.LayoutParams(w, h))
                if (isGif(m)) addView(tv("GIF", 11f, Color.WHITE, true).apply {
                    setPadding(dp(6), dp(2), dp(6), dp(2)); background = round(Color.parseColor("#88000000"), 4)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
                val cleared = f.optBoolean("cleared")
                if (!here) addView(tv(if (cleared) "Removed to save space" else if (m.mine) "" else "Downloading…", 13f, Color.WHITE).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = round(Color.parseColor("#99000000"), 10)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                setOnClickListener { if (here) showImage(m) else if (cleared) toast("This was removed from your phone to save space") else retryFetch(m) }
                setOnLongClickListener { onLong(); true }
                layoutParams = LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(4) }
            }
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), dp(6), dp(4))
            addView(icon(R.drawable.ic_file, ink, 26, 8).apply { background = circle(Color.parseColor("#22FFFFFF")) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(tv(f.optString("n"), 15f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
                addView(tv(sizeText(f.optInt("s")) + if (here) "" else if (f.optBoolean("cleared")) " · removed to save space" else if (m.mine) "" else " · downloading…", 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
            })
            setOnClickListener { if (here) openOutside(m) else if (f.optBoolean("cleared")) toast("This was removed from your phone to save space") else retryFetch(m) }
            setOnLongClickListener { onLong(); true }
        }
    }

    private fun decodeSmall(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / maxSide) }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun retryFetch(m: Msg) {
        if (m.mine) return
        Messenger.run({ Messenger.core.fetchFile(m) }, {}) { e -> toast(errorText(e)) }
    }

    private fun leaveViewer() = chatUser?.let { showChat(it) } ?: groupId?.let { showGroup(it) } ?: showChats()

    /** Full-screen photo, unlocked only in memory. */
    private fun showImage(m: Msg) {
        var bmp: android.graphics.Bitmap? = null
        var anim: android.graphics.drawable.Drawable? = null
        Messenger.run({
            val bytes = Messenger.core.openFile(m)
            if (bytes != null && isAnimated(m)) anim = decodeGif(bytes, 1600)
            bmp = bytes?.let { decodeSmall(it, 2400) }
        }, {
            val b = bmp ?: return@run toast("Couldn't open that photo")
            screen = "viewer"
            setContentView(FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(ImageView(context).apply {
                    setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_CENTER
                    anim?.let { a -> setImageDrawable(a); if (Build.VERSION.SDK_INT >= 28 && a is android.graphics.drawable.AnimatedImageDrawable) a.start() }
                    setOnLongClickListener { longPressMenu(null, null, imageActions(m)); true }
                })
                addView(icon(R.drawable.ic_back, Color.WHITE) { leaveViewer() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
                fitsSystemWindows = true
            })
        })
    }

    /** Hands a temporary unlocked copy to another app (PDF viewer, etc.). It's deleted next time the app opens. */
    private fun openOutside(m: Msg) {
        val f = m.file ?: return
        var out: File? = null
        Messenger.run({
            val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that file")
            out = File(File(cacheDir, "open").apply { mkdirs() }, f.optString("n")).apply { writeBytes(bytes) }
        }, {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.wilfrombdn.telegramnt.files", out!!)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.optString("m")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { startActivity(Intent.createChooser(view, f.optString("n"))) } catch (e: Exception) { toast("No app on this phone can open that file") }
        }) { e -> toast(errorText(e)) }
    }

    // ---------- recovery backup (survives uninstall; encrypted; nothing server-side) ----------

    private fun takePersist(uri: android.net.Uri) = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun chooseRecoveryFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "quipu-recovery-${Messenger.core.me?.user}.m01")
        @Suppress("DEPRECATION") startActivityForResult(i, CREATE_RECOVERY)
    }

    private fun setupRecovery(uri: android.net.Uri) {
        takePersist(uri)
        Messenger.recoveryUri = uri.toString()
        Messenger.run({ Messenger.writeRecovery() }, { showProfile(); toast("Recovery backup on. Keep this file safe.") }) { toast("Couldn't write the file") }
    }

    private fun pickRecoveryToRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(i, OPEN_RECOVERY)
    }

    private fun restoreFromFile(uri: android.net.Uri) {
        var bytes: ByteArray? = null
        Messenger.run({ bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } }, {
            val b = bytes
            val owner = b?.let { Messenger.core.backupOwner(it) }
            if (b == null || owner == null) { toast("That isn't a quipu recovery file."); return@run }
            val pass = field("Password", password = true)
            popup("Restore @$owner", "Enter the account password to unlock this backup. Your chats will be restored on this phone.", pass,
                "Cancel" to dim, "Restore" to white) { i ->
                if (i == 1) Messenger.run({
                    if (!Messenger.core.restoreFull(b, pass.text.toString())) throw UserError("Wrong password, or the file is damaged")
                    takePersist(uri); Messenger.recoveryUri = uri.toString(); Messenger.save()
                }, {
                    Messenger.signedOutReason = null
                    startForegroundService(Intent(this, SyncService::class.java))
                    showChats()
                }) { e -> toast(errorText(e)) }
            }
        }) { toast("Couldn't read that file") }
    }

    // ---------- profile photo ----------

    private fun photoMenu(anchor: View) {
        val choose = "Choose a photo" to { pickPhoto() }
        if (Messenger.core.me?.avatar == null) dropdown(anchor, Gravity.START, choose)
        else dropdown(anchor, Gravity.START, choose, "Remove photo" to { Messenger.run({ Messenger.core.setAvatar(null) }, { showProfile() }) })
    }

    private fun pickPhoto() {
        // The system photo picker needs no permissions and only hands over the one photo chosen.
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(i, PICK_PHOTO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCAN_QR && resultCode == RESULT_OK) return data?.getStringExtra("qr")?.let { scanned(it) } ?: Unit
        if (requestCode == ROUND_VIDEO && resultCode == RESULT_OK) return data?.getStringExtra("path")?.let { sendRound(it) } ?: Unit
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == CREATE_RECOVERY) return setupRecovery(uri)
        if (requestCode == OPEN_RECOVERY) return restoreFromFile(uri)
        if (requestCode == SAVE_IMAGE) return saveImageTo(uri)
        if (requestCode == PICK_ATTACH_PHOTO || requestCode == PICK_ATTACH_FILE) return attachPicked(uri, requestCode == PICK_ATTACH_PHOTO)
        if (requestCode == TAKE_MEDIA) return attachPicked(uri, data?.getBooleanExtra("photo", true) ?: true)
        if (requestCode != PICK_PHOTO) return
        var bmp: android.graphics.Bitmap? = null
        Messenger.run({ bmp = loadPhoto(uri) }, { bmp?.let { showCrop(it) } ?: toast("Couldn't open that photo") }) {
            toast("Couldn't open that photo")
        }
    }

    /** Opens the chosen photo at a sensible size, the right way up. */
    private fun loadPhoto(uri: android.net.Uri): android.graphics.Bitmap? {
        val maxSide = 2000
        if (Build.VERSION.SDK_INT >= 28) {
            return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                val big = maxOf(info.size.width, info.size.height)
                if (big > maxSide) d.setTargetSampleSize(big / maxSide + 1)
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxSide + 1)
        }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun showCrop(photo: android.graphics.Bitmap) {
        screen = "crop"
        val crop = CropView(this, photo)
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(16))
            addView(tv("Cancel", 16f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                setOnClickListener { showProfile() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mainButton("Done") {
                val jpeg = crop.crop()
                Messenger.run({ Messenger.core.setAvatar(jpeg) }, { showProfile() }) { e -> toast(errorText(e)); showProfile() }
            }.apply { background = round(Color.WHITE, 10); setTextColor(Color.parseColor("#111111")) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(tv("Move and zoom to fit your face in the circle", 15f, dim).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(18), dp(16), dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(-1, -2))
        })
    }

    // ---------- settings ----------

    /** A tappable settings/profile row: title + optional subtitle, with press feedback. */
    private fun settingRow(title: String, sub: String? = null, color: Int = white, onClick: (View) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = pressable()
        isClickable = true
        addView(tv(title, 16f, color))
        sub?.let { addView(tv(it, 13.5f, dim)) }
        setOnClickListener { v -> onClick(v) }
    }

    private fun settingSwitch(label: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
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

    private fun settingNote(text: String) = tv(text, 13.5f, dim).apply { setPadding(dp(20), 0, dp(20), dp(10)) }

    // ---------- your profile (personal) ----------

    private fun showProfile() {
        screen = "profile"
        val me = Messenger.core.me ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(20), dp(16), dp(20), dp(22))
            addView(avatar(me.name, 72, me.avatar).apply { setOnClickListener { v -> photoMenu(v) } })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(tv(me.name, 21f, white, true))
                addView(tv("@${me.user}", 15f, dim).apply { setPadding(0, dp(2), 0, 0) })
            })
        })
        col.addView(gap(8))
        col.addView(settingRow(if (me.avatar == null) "Set profile photo" else "Change profile photo", "Only your friends can see it") { v -> photoMenu(v) })
        col.addView(settingRow("Share username", "Invite a friend to add you") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Add me on quipu: @${me.user}\nGet the app: https://wilfrombdn.github.io/"), "Share username"))
        })
        col.addView(gap(8))
        col.addView(settingRow("Cloud backup", if (me.cloudBackup) "On: you can log in on a new phone. The backup is locked with your password; the server can't read it." else "Off: this account only works on this phone, and the server keeps nothing to restore.") {
            if (me.cloudBackup) popup("Turn off cloud backup?",
                "The server will wipe its locked copy of your keys and friends. You'll no longer be able to log in on another phone or after reinstalling. Nothing will be left on the server to hand over.", null,
                "Cancel" to dim, "Turn off" to red) { i ->
                if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(false) }, { showProfile() }) { e -> toast(errorText(e)) }
            } else popup("Turn on cloud backup?",
                "A copy of your keys and friends, locked with your password, is kept on the server so you can log in on a new phone.", null,
                "Cancel" to dim, "Turn on" to white) { i ->
                if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(true) }, { showProfile() }) { e -> toast(errorText(e)) }
            }
        })
        col.addView(settingRow("Recovery backup", if (Messenger.recoveryUri.isEmpty()) "Off. Save an encrypted backup file so your chats survive reinstalling the app." else "On. Your chats are saved, encrypted, to the file you chose. The server still keeps nothing.") {
            if (Messenger.recoveryUri.isEmpty()) popup("Turn on recovery backup",
                "Here's exactly what this does:\n\n" +
                "• quipu keeps a copy of all your chats in one file and keeps it up to date as you message.\n\n" +
                "• You choose where that file goes next — your phone's storage, an SD card, or a cloud folder like Drive. It never goes to the server; only you hold it.\n\n" +
                "• The file is locked with your account password (AES-256). Without that password it can't be read — not by us, not by the server, not by anyone who finds the file.\n\n" +
                "• To get your chats back after reinstalling: open quipu → Restore from a backup file → enter your password.\n\n" +
                "One caveat: if you save it into a synced folder like Drive, that service may keep older versions of the file. For zero copies anywhere, leave this off.", null,
                "Not now" to dim, "Choose file" to white) { i -> if (i == 1) chooseRecoveryFile() }
            else popup("Recovery backup", "Your chats are being saved to your chosen file, encrypted with your password. The server keeps nothing.", null,
                "Turn off" to red, "Change file" to white) { i ->
                if (i == 0) { Messenger.recoveryUri = ""; Messenger.settingsChanged(); showProfile() }
                if (i == 1) chooseRecoveryFile()
            }
        })
        col.addView(gap(8))
        col.addView(settingRow("Log out", "Your chats stay locked on this phone for when you log back in.") {
            popup("Log out?", "You can log back in any time with your username and password.", null,
                "Cancel" to dim, "Log out" to red) { i ->
                if (i == 1) {
                    stopService(Intent(this, SyncService::class.java))
                    Messenger.logOut()
                    showWelcome()
                }
            }
        })
        col.addView(settingRow("Delete account", "Removes @${me.user} from the server for good.", red) {
            val pass = field("Password", password = true)
            popup("Delete your account?", "This permanently removes @${me.user} and all your chats. The server keeps no backup and nobody — not even us — can recover anything. Type your password to confirm.", pass,
                "Cancel" to dim, "Delete" to red) { i ->
                if (i == 1) Messenger.run({ Messenger.deleteAccount(pass.text.toString()) }, {
                    stopService(Intent(this, SyncService::class.java))
                    showWelcome()
                }) { e -> toast(errorText(e)) }
            }
        })
        setContentView(page(topBar("Profile", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    }

    // ---------- app settings ----------

    private fun showSettings() {
        screen = "settings"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(gap(8))
        col.addView(settingRow("Appearance", "Bubble colours, text size, corners") { showAppearance() })
        col.addView(settingRow("Storage", "Photos, videos and files on this phone") { showStorage() })
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
        col.addView(settingRow("Check for updates", "You have quipu $ver") { checkUpdateNow() })
        col.addView(tv("Connection", 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
        val direct = Messenger.direct
        val on = Messenger.connected
        fun choice(title: String, state: String, picked: Boolean, go: () -> Unit) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(2)); background = pressable()
            addView(tv(if (picked) "◉" else "○", 18f, if (picked) blue else dim).apply { setPadding(0, 0, dp(12), 0) })
            addView(tv(title, 16f, white), LinearLayout.LayoutParams(0, -2, 1f))
            if (picked) addView(tv(state, 13.5f, if (on) green else amber))
            setOnClickListener { go() }
        })
        choice("Tor (most private)", if (on) "✓ active" else Tor.status(), !direct) { if (direct) { Messenger.useDirect(false); showSettings() } }
        col.addView(settingNote("Everything goes through the Tor network, so no one, not even the server, ever sees your phone's internet address."))
        choice("Direct (faster)", if (on) "✓ active" else "connecting…", direct) {
            if (!direct) popup("Use Direct?", "Faster, and works on networks that block Tor (like some work Wi-Fi). Your messages stay end-to-end encrypted, but Cloudflare and the server can see your phone's internet address. Calls still go through Tor.",
                null, "Cancel" to dim, "Use Direct" to white) { i -> if (i == 1) { Messenger.useDirect(true); showSettings() } }
        }
        col.addView(settingNote("Goes through a Cloudflare tunnel to the server. Messages stay end-to-end encrypted; your internet address is not hidden."))
        if (Messenger.core.unreadable > 0) col.addView(tv("⚠ ${Messenger.core.unreadable} message(s) arrived but couldn't be decrypted — the two accounts' keys are out of sync (usually from reinstalling/restoring). Fresh accounts fix this.", 13.5f, amber).apply { setPadding(dp(20), dp(2), dp(20), dp(10)) })
        if (Messenger.core.lastDrop.isNotEmpty()) col.addView(tv("diag · last fetched message: ${Messenger.core.lastDrop}", 12.5f, dim).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
        col.addView(gap(8))
        col.addView(settingSwitch("Get messages when the app is closed", Messenger.background) { on -> Messenger.background = on; Messenger.settingsChanged(); showSettings() })
        // Calls when closed: each missing phone setting, tap to fix
        col.addView(tv("Calls", 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
        col.addView(settingSwitch("Wake-up pings", Messenger.pushOn) { on -> Messenger.pushOn = on; Messenger.settingsChanged(); PushService.register(this) })
        col.addView(settingNote("Lets calls and messages wake quipu when it's closed, like Telegram, through Google's push service. The ping is empty: Google never sees who it's from or what it says."))
        val fixes = ringFixes()
        if (fixes.isEmpty()) col.addView(tv("✓ Calls will ring this phone even when quipu is closed", 15f, green).apply { setPadding(dp(20), dp(6), dp(20), dp(6)) })
        else {
            col.addView(settingNote("To ring like a normal phone call when quipu is closed, tap each one:"))
            for ((what, why, fix) in fixes) col.addView(settingRow("⚠  $what", why) { fix() })
        }
        col.addView(settingNote("Keeps a small \"connected\" notification. No Google push service is used."))
        col.addView(settingSwitch("Read receipts", Messenger.core.readReceipts) { on -> Messenger.core.readReceipts = on; Messenger.settingsChanged() })
        col.addView(settingNote("When on, friends see when you've read their messages, and you see when they've read yours."))
        col.addView(settingSwitch("Show when I'm typing", Messenger.core.showTyping) { on -> Messenger.core.showTyping = on; Messenger.settingsChanged() })
        col.addView(settingNote("When on, a friend sees \"typing…\" while you write to them."))
        col.addView(settingSwitch("Show when I'm online", Messenger.core.showSeen) { on -> Messenger.setShowSeen(on) })
        col.addView(settingNote("When on, friends see \"online\" while you have quipu open, and \"last seen\" after."))
        col.addView(gap(20))
        col.addView(tv("quipu ${packageManager.getPackageInfo(packageName, 0).versionName}\nEvery chat is end-to-end encrypted. The server can't read your messages and keeps no logs.",
            13f, dim).apply { setPadding(dp(20), 0, dp(20), dp(20)) })
        setContentView(page(topBar("Settings", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    }

    // ---------- storage ----------

    private fun mb(n: Long) = when {
        n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / 1073741824f)
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    /** How much each chat's photos/videos/files take, clear them per chat, and auto-delete old ones. */
    private fun showStorage() {
        screen = "storage"
        val core = Messenger.core
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        data class Item(val name: String, val avatar: ByteArray?, val msgs: List<Msg>, val bytes: Long)
        val items = synchronized(core) {
            core.contacts.map { Item(it.name, it.avatar, it.msgs.toList(), 0) } + core.groups.map { Item(it.name, null, it.msgs.toList(), 0) }
        }.map { it.copy(bytes = core.mediaBytes(it.msgs)) }.sortedByDescending { it.bytes }
        val total = items.sumOf { it.bytes }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(14))
            addView(tv(mb(total), 28f, white, true))
            addView(tv("used by photos, videos and files on this phone. Your messages themselves take almost nothing.", 14f, dim).apply { setPadding(0, dp(2), 0, 0) })
        })

        col.addView(tv("AUTO-DELETE MEDIA", 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(8)) })
        val opts = listOf("Never" to 0, "1 year" to 365, "30 days" to 30)
        col.addView(LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            opts.forEach { (label, days) ->
                val on = Messenger.keepMediaDays == days
                addView(tv(label, 15f, if (on) ink else white, true).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                    background = GradientDrawable().apply { setColor(if (on) white else Color.TRANSPARENT); setStroke(dp(1), if (on) white else raised); cornerRadius = dp(4).toFloat() }
                    setOnClickListener {
                        Messenger.keepMediaDays = days; Messenger.settingsChanged()
                        Messenger.run({ Messenger.sweepMedia(force = true) }, { showStorage() })
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        })
        col.addView(settingNote("Photos, videos and files older than this are removed from this phone. The messages stay, with a small blurry preview so you can see something was sent.").apply { setPadding(dp(20), dp(8), dp(20), dp(10)) })

        col.addView(tv("BY CHAT", 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(4)) })
        val withMedia = items.filter { it.bytes > 0 }
        if (withMedia.isEmpty()) col.addView(settingNote("No photos, videos or files stored."))
        withMedia.forEach { it ->
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)); background = pressable(); isClickable = true
                addView(avatar(it.name, 40, it.avatar))
                addView(tv(it.name, 16f, white).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(tv(mb(it.bytes), 15f, dim))
                setOnClickListener { _ ->
                    popup("Clear photos and videos?", "Removes ${mb(it.bytes)} of photos, videos and files in \"${it.name}\" from this phone. The text stays, and each one keeps a small blurry preview. They can't be downloaded again.",
                        null, "Cancel" to dim, "Clear" to red) { i ->
                        if (i == 1) Messenger.run({ core.clearMedia(it.msgs) }, { it.msgs.forEach { m -> pictures.remove(m.id); gifCache.remove(m.id) }; toast("Cleared"); showStorage() })
                    }
                }
            })
        }
        col.addView(gap(20))
        setContentView(page(topBar("Storage", left = icon(R.drawable.ic_back) { showSettings() }), ScrollView(this).apply { addView(col) }))
    }

    // ---------- appearance ----------

    private fun showAppearance() {
        screen = "appearance"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // live preview
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(wall)
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }
        val now = System.currentTimeMillis()
        val demo = Contact("preview", "Sam", Contact.FRIENDS, "00", null, "", "")
        preview.addView(bubble(demo, Msg("a", false, "Hey! Did you pick a colour yet?", now - 120_000, Msg.RECEIVED)))
        preview.addView(bubble(demo, Msg("b", true, "Trying a few now", now, Msg.SENT)))
        col.addView(preview)

        fun section(t: String) = col.addView(tv(t, 14f, dim, true).apply { setPadding(dp(20), dp(20), dp(20), dp(8)) })
        fun chips(vararg items: Pair<String, Boolean>, onPick: (Int) -> Unit) = col.addView(LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0)
            items.forEachIndexed { i, (label, on) ->
                addView(tv(label, 15f, if (on) ink else white, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    background = GradientDrawable().apply {
                        setColor(if (on) white else Color.TRANSPARENT)
                        setStroke(dp(1), if (on) white else raised)
                        cornerRadius = dp(4).toFloat()
                    }
                    setOnClickListener { onPick(i); Messenger.settingsChanged(); showAppearance() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        })
        fun swatches(current: Int, onPick: (Int) -> Unit) = col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                setPadding(dp(16), 0, dp(16), 0)
                for ((name, color) in Look.BUBBLES) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(color)
                            setStroke(dp(if (color == current) 3 else 1), if (color == current) white else raised)
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(tv(name, 12f, if (color == current) white else dim).apply { setPadding(0, dp(4), 0, 0); maxLines = 1 })
                    setOnClickListener { onPick(color); Messenger.settingsChanged(); showAppearance() }
                }, LinearLayout.LayoutParams(dp(66), -2))
            })
        })

        section("THEME")
        chips("Dark" to (look.theme == "dark"), "Light" to (look.theme == "light"), "Match phone" to (look.theme == "system")) { i ->
            look.theme = listOf("dark", "light", "system")[i]
            // the default dark "their" bubble is heavy on a light background; swap it for the light one
            if (!dark && look.inn == Look.BUBBLES[1].second) look.inn = Look.BUBBLES.last().second
            if (dark && look.inn == Look.BUBBLES.last().second) look.inn = Look.BUBBLES[1].second
            applyBars()
        }
        section("YOUR BUBBLES")
        swatches(look.out) { look.out = it }
        section("THEIR BUBBLES")
        swatches(look.inn) { look.inn = it }
        section("BUBBLE CORNERS")
        chips("Rounded" to !look.square, "Square" to look.square) { look.square = it == 1 }
        section("MESSAGE TEXT SIZE")
        chips(*Look.MSG_SIZES.map { "A" to (it == look.msg) }.toTypedArray()) { look.msg = Look.MSG_SIZES[it] }
        // make each "A" bigger than the last so the sizes read at a glance
        (col.getChildAt(col.childCount - 1) as LinearLayout).let { row ->
            for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).textSize = 12f + i * 3f
        }
        section("APP TEXT SIZE")
        chips(*Look.UI_SIZES.map { (label, v) -> label to (v == look.ui) }.toTypedArray()) { look.ui = Look.UI_SIZES[it].second }
        col.addView(tv("Reset to default", 15f, white, true).apply {
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setOnClickListener { Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second; Messenger.settingsChanged(); showAppearance() }
        })
        setContentView(page(topBar("Appearance", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    }
}
