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
import android.widget.Toast
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
    private val bar = Color.parseColor("#1F1F1F")       // top bars
    private val listBg = Color.parseColor("#141414")    // chat list, inputs
    private val wall = Color.parseColor("#0B0B0B")      // behind messages
    private val menuBg = Color.parseColor("#2A2A2A")    // drop-down menus
    private val look get() = Messenger.look
    private val blue = Color.parseColor("#4EA4F6")      // status only: unread, ticks, requests
    private val ink = Color.parseColor("#111111")       // text on white buttons
    private val white = Color.WHITE
    private val dim = Color.parseColor("#8E8E93")
    private val divider = Color.parseColor("#262626")
    private val red = Color.parseColor("#E5534B")
    private val avatarColors = listOf("#E17076", "#FAA774", "#A695E7", "#7BC862", "#6EC9CB", "#65AADD", "#EE7AAE")
        .map { Color.parseColor(it) }

    private var screen = "chats"
    private var chatUser: String? = null
    private var search = ""
    private val refresh: () -> Unit = { redraw() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep chats out of screenshots and the recent-apps preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = bar
        window.navigationBarColor = listBg
        Messenger.init(this)
        SyncService.channels(this)
        File(cacheDir, "open").deleteRecursively() // unlocked copies of opened attachments
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Messenger.signedIn) showChats() else { showWelcome(); Messenger.connectInBackground() }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra("chat")?.let { u -> if (Messenger.core.contact(u) != null) showChat(u) }
    }

    override fun onStart() {
        super.onStart()
        Messenger.visible = true
        Messenger.listen(refresh)
        if (Messenger.signedIn) startForegroundService(Intent(this, SyncService::class.java))
        redraw()
    }

    override fun onStop() {
        Messenger.visible = false
        Messenger.unlisten(refresh)
        if (!Messenger.background) stopService(Intent(this, SyncService::class.java))
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawer?.isDrawerOpen(GravityCompat.START) == true -> drawer?.closeDrawer(GravityCompat.START)
            !Messenger.signedIn && screen != "welcome" -> showWelcome()
            screen == "viewer" -> chatUser?.let { showChat(it) } ?: showChats()
            Messenger.signedIn && screen != "chats" -> showChats()
            search.isNotEmpty() -> { search = ""; showChats() }
            else -> super.onBackPressed()
        }
    }

    private fun redraw() {
        if (!Messenger.signedIn) {
            if (screen == "welcome" && Messenger.connected) pendingNext?.let { pendingNext = null; welcomeStatus?.text = ""; it(); return }
            if (screen != "welcome" && screen != "signup" && screen != "login") showWelcome()
            return
        }
        when (screen) {
            "chats" -> showChats()
            "chat" -> chatUser?.let { if (Messenger.core.contact(it) != null) fillChat() else showChats() }
        }
    }

    // ---------- small view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 16f, color: Int = white, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size * look.ui
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
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

    private fun topBar(title: String, sub: String? = null, left: View? = null, vararg right: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bar)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        minimumHeight = dp(56)
        left?.let { addView(it) } ?: addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titles.addView(tv(title, 19f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        sub?.let { titles.addView(tv(it, 13.5f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
        addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        right.forEach { addView(it) }
    }

    private fun page(top: View, body: View, bg: Int = listBg): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        fitsSystemWindows = true
        addView(top)
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun time(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "MMM d", Locale.getDefault()).format(Date(ts))
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
        col.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(150), dp(150)))
        col.addView(gap(24))
        col.addView(tv("msgr01", 26f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(12))
        col.addView(tv("The world's most private messaging app.\nEvery chat is end-to-end encrypted.\nNo phone number or email needed.", 16f, dim).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        })
        Messenger.signedOutReason?.let {
            col.addView(gap(16))
            col.addView(tv(it, 14f, red).apply { gravity = Gravity.CENTER })
        }
        col.addView(gap(40))
        col.addView(mainButton("Start Messaging") { connectThen { showSignup() } }, LinearLayout.LayoutParams(-1, -2))
        col.addView(gap(14))
        col.addView(tv("I already have an account", 16f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { connectThen { showLogin() } }
        })
        col.addView(gap(24))
        welcomeStatus = tv(if (Messenger.connected || pendingNext == null) "" else "Connecting…", 14f, dim).apply { gravity = Gravity.CENTER }
        col.addView(welcomeStatus)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(listBg)
            fitsSystemWindows = true
            isFillViewport = true
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
        })
    }

    private var welcomeStatus: TextView? = null
    private var pendingNext: (() -> Unit)? = null

    /** The server is found automatically; if it isn't reachable yet, carry on once it is. */
    private fun connectThen(next: () -> Unit) {
        if (Messenger.connected) return next()
        pendingNext = next
        welcomeStatus?.text = "Connecting…"
        Messenger.connectInBackground()
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
        val go = mainButton(if (signup) "Create Account" else "Log In") {}
        go.setOnClickListener {
            val u = user.text.toString().trim().removePrefix("@").lowercase()
            val p = pass.text.toString()
            if (signup && p != pass2.text.toString()) { toast("The passwords don't match"); return@setOnClickListener }
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
        col.addView(go, LinearLayout.LayoutParams(-1, -2))
        setContentView(page(topBar(title, left = icon(R.drawable.ic_back) { showWelcome() }), ScrollView(this).apply {
            isFillViewport = true
            addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
        }))
    }

    private fun showSignup() = accountForm("Sign Up", signup = true)
    private fun showLogin() = accountForm("Log In", signup = false)

    // ---------- chat list ----------

    private fun showChats() {
        screen = "chats"
        chatUser = null
        Messenger.core.openChat = null
        val core = Messenger.core
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val q = search.lowercase()
        val all = core.sorted().filter { q.isEmpty() || it.name.lowercase().contains(q) || it.user.contains(q) }
        val requests = all.filter { it.status == Contact.ASKING }
        for (c in requests + (all - requests.toSet())) list.addView(chatRow(c))
        if (all.isEmpty()) {
            list.addView(tv(
                if (q.isNotEmpty()) "No chats match \"$search\""
                else "Welcome, ${core.me?.name}!\n\nTap the pencil to add a friend by their @username.\n\nYour username is @${core.me?.user}. Share it so friends can add you.",
                15.5f, dim,
            ).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)); setLineSpacing(0f, 1.2f) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
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

        val top = if (search.isNotEmpty() || screenSearch) searchBar() else topBar(
            if (Messenger.connected) "msgr01" else "Connecting…", null,
            icon(R.drawable.ic_menu).also { v -> v.setOnClickListener { drawer?.openDrawer(GravityCompat.START) } },
            icon(R.drawable.ic_search) { screenSearch = true; showChats() },
        )
        // Content + a left slide-out drawer (opens by tapping the lines or swiping from the edge).
        val d = DrawerLayout(this)
        d.addView(page(top, frame), DrawerLayout.LayoutParams(-1, -1))
        d.addView(drawerPanel(d), DrawerLayout.LayoutParams(
            minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82).toInt()), -1
        ).apply { gravity = GravityCompat.START })
        d.setScrimColor(0x99000000.toInt())
        drawer = d
        setContentView(d)
    }

    private var screenSearch = false

    companion object {
        private const val PICK_PHOTO = 7
        private const val PICK_ATTACH_PHOTO = 8
        private const val PICK_ATTACH_FILE = 9
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

    private fun chatRow(c: Contact): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            setOnClickListener { showChat(c.user) }
        }
        row.addView(avatar(c.name, 54, c.avatar))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val line1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        line1.addView(tv(c.name, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val last = c.msgs.lastOrNull()
        if (last?.mine == true && last.state == Msg.SENT) line1.addView(icon(R.drawable.ic_check, blue, 16, 2))
        line1.addView(tv(time(c.lastTs), 13f, dim).apply { setPadding(dp(4), 0, 0, 0) })
        mid.addView(line1)
        val line2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
        val (preview, previewColor) = when {
            c.status == Contact.ASKING -> (last?.takeIf { it.state == Msg.RECEIVED }?.let { "Message request: ${it.text}" } ?: "Wants to be your friend") to blue
            c.status == Contact.ASKED && last?.state == Msg.NOTE -> "Friend request sent" to dim
            last == null -> "" to dim
            last.mine -> "You: ${preview(last)}" to dim
            else -> preview(last) to dim
        }
        line2.addView(tv(preview, 15f, previewColor).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val badge = if (c.status == Contact.ASKING) 1 else c.unread
        if (badge > 0) line2.addView(tv("$badge", 12.5f, white, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(22)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = round(blue, 11)
        })
        mid.addView(line2)
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
        }
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
        val go = mainButton("Send Friend Request") {}
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
        col.addView(go)
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
                        "Add me on msgr01: @${me.user}\nGet the app: https://wilfrombdn.github.io/"), "Share username"))
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
    private var shownCount = -1

    private fun showChat(user: String) {
        val c = Messenger.core.contact(user) ?: return showChats()
        screen = "chat"
        chatUser = user
        Messenger.core.openChat = user
        shownCount = -1

        chatTop = LinearLayout(this)
        banner = FrameLayout(this)
        bubbles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroller = ScrollView(this).apply { addView(bubbles); isFillViewport = true }

        val input = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(listBg)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        val box = EditText(this).apply {
            hint = "Message"
            setHintTextColor(dim)
            setTextColor(white)
            textSize = look.msg
            background = null
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        input.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        input.addView(icon(R.drawable.ic_attach, dim, pad = 10).also { v -> v.setOnClickListener { attachMenu(c) } })
        input.addView(icon(R.drawable.ic_send, white) {
            val t = box.text.toString().trim()
            if (t.isEmpty()) return@icon
            if (t.length > Core.MAX_TEXT) { toast("Too long (max ${Core.MAX_TEXT} characters)"); return@icon }
            box.setText("")
            Messenger.send(c, t)
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(wall)
            fitsSystemWindows = true
            addView(chatTop, LinearLayout.LayoutParams(-1, -2))
            addView(banner, LinearLayout.LayoutParams(-1, -2))
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(input, LinearLayout.LayoutParams(-1, -2))
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
            else -> if (c.verified) "@${c.user} · verified ✓" else "@${c.user}"
        }
        chatTop?.removeAllViews()
        chatTop?.addView(topBar(c.name, sub, LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_back) { showChats() })
            addView(avatar(c.name, 40, c.avatar))
        }, icon(R.drawable.ic_more).also { v -> v.setOnClickListener { chatMenu(c, v) } }), LinearLayout.LayoutParams(-1, -2))

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
        list.removeAllViews()
        list.addView(note(if (c.verified) "Messages are end-to-end encrypted. Safety code checked ✓"
        else "Messages are end-to-end encrypted. Tap here to check the safety code.") { safety(c) })
        for (m in msgs) list.addView(if (m.state == Msg.NOTE) note(m.text) else bubble(c, m))
        if (msgs.size != shownCount) {
            shownCount = msgs.size
            scroller?.post { scroller?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun note(text: String, onClick: (() -> Unit)? = null): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(6), dp(24), dp(6))
        addView(tv(text, 13.5f, white).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = round(Color.parseColor("#CC262626"), 12)
            onClick?.let { f -> setOnClickListener { f() } }
        })
    }

    private fun bubble(c: Contact, m: Msg): View {
        val wrap = LinearLayout(this).apply {
            gravity = if (m.mine) Gravity.END else Gravity.START
            setPadding(0, dp(2), 0, dp(2))
        }
        val fill = if (m.mine) look.out else look.inn
        val ink = Look.textOn(fill)
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(fill, if (look.square) 0 else 14)
            setPadding(dp(10), dp(6), dp(10), dp(5))
        }
        m.file?.let { b.addView(attachmentView(c, m, ink)) }
        if (m.text.isNotEmpty()) b.addView(tv(m.text, 16f, ink).apply { textSize = look.msg; setTextIsSelectable(true) })
        val meta = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val metaColor = (ink and 0x00FFFFFF) or (0x99 shl 24)
        meta.addView(tv(time(m.ts), 12f, metaColor).apply { setPadding(0, 0, dp(6), 0) }) // gap before the tick
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
            Msg.FAILED -> meta.addView(tv("  not sent · tap to retry", 12f, red).apply { setOnClickListener { Messenger.retry(c, m) } })
        }
        b.addView(meta, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (m.mine) leftMargin = dp(56) else rightMargin = dp(56) })
        return wrap
    }

    private fun chatMenu(c: Contact, anchor: View) {
        dropdown(anchor, Gravity.END,
            "Safety code" to { safety(c) },
            (if (c.ttl == 0) "Disappearing messages" else "Disappearing: on") to { timerMenu(c) },
            "Rename" to {
                val box = field("Name", c.name)
                popup("Rename", null, box, "Cancel" to dim, "Save" to white) { i ->
                    if (i == 1) box.text.toString().trim().take(60).takeIf { it.isNotEmpty() }?.let { c.name = it; Messenger.save(); fillChat() }
                }
            },
            "Remove friend" to {
                popup("Remove ${c.name}?", "Deletes the chat from this phone. To talk again, one of you sends a new friend request.", null,
                    "Cancel" to dim, "Remove" to red) { i -> if (i == 1) { Messenger.core.remove(c); showChats() } }
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
            col.addView(tv(label, 16f, white).apply { setPadding(dp(24), dp(14), dp(24), dp(14)); setOnClickListener { listDialog?.dismiss(); onPick(i) } })
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(bar, 6)
            setPadding(0, dp(18), 0, dp(10))
            addView(tv(title, 19f, white, true).apply { setPadding(dp(24), 0, dp(24), if (sub == null) dp(8) else dp(4)) })
            sub?.let { addView(tv(it, 14f, dim).apply { setPadding(dp(24), 0, dp(24), dp(8)) }) }
            addView(gap(4))
        }
        wrap.addView(col)
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(ScrollView(this).apply { addView(wrap) })
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        // header: avatar + name + @username (tap -> settings)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(18), dp(22), dp(18), dp(16))
            addView(avatar(me?.name ?: "", 60, me?.avatar))
            addView(tv(me?.name ?: "", 18f, white, true).apply { setPadding(0, dp(10), 0, 0) })
            addView(tv("@${me?.user ?: ""}", 14f, dim))
            setOnClickListener { d.closeDrawer(GravityCompat.START); showSettings() }
        })
        fun item(res: Int, label: String, go: () -> Unit) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(icon(res, dim, 22, 0))
            addView(tv(label, 16f, white).apply { setPadding(dp(22), 0, 0, 0) })
            setOnClickListener { d.closeDrawer(GravityCompat.START); go() }
        })
        item(R.drawable.ic_edit, "New Message") { showNewChat() }
        item(R.drawable.ic_lock, "Appearance") { showAppearance() }
        item(R.drawable.ic_more, "Settings") { showSettings() }
        return ScrollView(this).apply { setBackgroundColor(listBg); addView(col) }
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
            setPadding(dp(24), dp(22), dp(16), dp(10))
            background = round(bar, 6)
        }
        col.addView(tv(title, 19f, white, true).apply { setPadding(0, 0, dp(8), dp(10)) })
        message?.let { col.addView(tv(it, 15f, dim).apply { setLineSpacing(0f, 1.15f); setPadding(0, 0, dp(8), dp(8)) }) }
        content?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { rightMargin = dp(8) }) }
        val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
        buttons.forEachIndexed { i, (label, color) ->
            row.addView(tv(label.uppercase(), 14.5f, color, true).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { d.dismiss(); onButton(i) }
            })
        }
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        d.setContentView(col)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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
    private val pictures = HashMap<String, android.graphics.Bitmap?>()

    private fun preview(m: Msg): String {
        val f = m.file ?: return m.text
        return if (f.optString("m").startsWith("image/")) "Photo" else "File: " + f.optString("n")
    }

    private fun attachMenu(c: Contact) {
        attachTo = c.user
        // drop the keyboard so the sheet isn't hidden behind it, then slide up from the bottom
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        val sheet = android.app.Dialog(this)
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        fun row(res: Int, label: String, go: () -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(icon(res, white, 24, 0))
            addView(tv(label, 17f, white).apply { setPadding(dp(20), 0, 0, 0) })
            setOnClickListener { sheet.dismiss(); go() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(bar, 14)
            setPadding(0, dp(10), 0, dp(14))
            addView(tv("Send", 14f, dim, true).apply { setPadding(dp(24), dp(6), dp(24), dp(6)) })
            addView(row(R.drawable.ic_attach, "Photo") {
                val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_PHOTO)
            })
            addView(row(R.drawable.ic_file, "File") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_FILE)
            })
        }
        sheet.setContentView(LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), dp(8)); addView(col, LinearLayout.LayoutParams(-1, -2)) })
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheet.show()
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
        val c = attachTo?.let { Messenger.core.contact(it) } ?: return
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
            if (asPhoto || (mime.startsWith("image/") && mime != "image/gif")) {
                // re-saved as JPEG: smaller, and hidden details like GPS location are dropped
                val bmp = loadPhoto(uri) ?: throw UserError("Couldn't open that photo")
                Messenger.sendFile(c, name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg(bmp, 1600, 85), thumbOf(bmp))
            } else {
                if (size > Core.MAX_FILE) throw UserError("Files can be up to ${Core.MAX_FILE / 1024 / 1024} MB")
                val data = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserError("Couldn't read that file")
                Messenger.sendFile(c, name, mime, data, null)
            }
        }, {}) { e -> toast(errorText(e)) }
    }

    private fun sizeText(n: Int) = when {
        n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
        n >= 1024 -> "${n / 1024} KB"
        else -> "$n B"
    }

    private fun attachmentView(c: Contact, m: Msg, ink: Int): View {
        val f = m.file!!
        val here = f.has("local")
        if (f.optString("m").startsWith("image/")) {
            val bmp = pictures[m.id] ?: run {
                val full = if (here) Messenger.core.openFile(m)?.let { decodeSmall(it, 720) } else null
                val shown = full ?: f.optString("th").takeIf { it.isNotEmpty() }?.let {
                    val t = java.util.Base64.getDecoder().decode(it); android.graphics.BitmapFactory.decodeByteArray(t, 0, t.size)
                }
                if (full != null) pictures[m.id] = full
                shown
            }
            val w = dp(240)
            val h = if (bmp != null) (w * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(dp(80), dp(360)) else dp(160)
            return FrameLayout(this).apply {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    bmp?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.parseColor("#33000000"))
                }, FrameLayout.LayoutParams(w, h))
                if (!here) addView(tv(if (m.mine) "" else "Downloading…", 13f, white).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = round(Color.parseColor("#99000000"), 10)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                setOnClickListener { if (here) showImage(m) else retryFetch(m) }
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
                addView(tv(sizeText(f.optInt("s")) + if (here) "" else if (m.mine) "" else " · downloading…", 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
            })
            setOnClickListener { if (here) openOutside(m) else retryFetch(m) }
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

    /** Full-screen photo, unlocked only in memory. */
    private fun showImage(m: Msg) {
        var bmp: android.graphics.Bitmap? = null
        Messenger.run({ bmp = Messenger.core.openFile(m)?.let { decodeSmall(it, 2400) } }, {
            val b = bmp ?: return@run toast("Couldn't open that photo")
            screen = "viewer"
            setContentView(FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(ImageView(context).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_CENTER })
                addView(icon(R.drawable.ic_back) { chatUser?.let { showChat(it) } }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
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

    // ---------- profile photo ----------

    private fun photoMenu(anchor: View) {
        val choose = "Choose a photo" to { pickPhoto() }
        if (Messenger.core.me?.avatar == null) dropdown(anchor, Gravity.START, choose)
        else dropdown(anchor, Gravity.START, choose, "Remove photo" to { Messenger.run({ Messenger.core.setAvatar(null) }, { showSettings() }) })
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
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == PICK_ATTACH_PHOTO || requestCode == PICK_ATTACH_FILE) return attachPicked(uri, requestCode == PICK_ATTACH_PHOTO)
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
            addView(tv("Cancel", 16f, white, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                setOnClickListener { showSettings() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mainButton("Done") {
                val jpeg = crop.crop()
                Messenger.run({ Messenger.core.setAvatar(jpeg) }, { showSettings() }) { e -> toast(errorText(e)); showSettings() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun showSettings() {
        screen = "settings"
        val me = Messenger.core.me ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bar)
            setPadding(dp(20), dp(12), dp(20), dp(20))
            addView(avatar(me.name, 64, me.avatar).apply { setOnClickListener { v -> photoMenu(v) } })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(tv(me.name, 20f, white, true))
                addView(tv("@${me.user}", 15f, dim))
            })
        })
        fun row(title: String, sub: String? = null, color: Int = white, onClick: (View) -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(tv(title, 16f, color))
            sub?.let { addView(tv(it, 13.5f, dim)) }
            setOnClickListener { v -> onClick(v) }
        }
        col.addView(gap(10))
        col.addView(row(if (me.avatar == null) "Set Profile Photo" else "Change Profile Photo", "Only your friends can see it") { v -> photoMenu(v) })
        col.addView(row("Appearance", "Bubble colours, text size, corners") { showAppearance() })
        col.addView(Switch(this).apply {
            text = "Get messages when the app is closed"
            setTextColor(white)
            textSize = 16f * look.ui
            isChecked = Messenger.background
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(white, Color.parseColor("#9AA4AE")))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(Color.parseColor("#80FFFFFF"), Color.parseColor("#3A4654")))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnCheckedChangeListener { _, on -> Messenger.background = on; Messenger.settingsChanged() }
        })
        col.addView(tv("Keeps a small \"connected\" notification. No Google push service is used.", 13.5f, dim).apply {
            setPadding(dp(20), 0, dp(20), dp(10))
        })
        col.addView(Switch(this).apply {
            text = "Read receipts"
            setTextColor(white)
            textSize = 16f * look.ui
            isChecked = Messenger.core.readReceipts
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(st, intArrayOf(white, Color.parseColor("#9AA4AE")))
            trackTintList = android.content.res.ColorStateList(st, intArrayOf(Color.parseColor("#80FFFFFF"), Color.parseColor("#3A4654")))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnCheckedChangeListener { _, on -> Messenger.core.readReceipts = on; Messenger.settingsChanged() }
        })
        col.addView(tv("When on, friends see when you've read their messages, and you see when they've read yours.", 13.5f, dim).apply {
            setPadding(dp(20), 0, dp(20), dp(10))
        })
        col.addView(row("Cloud backup", if (me.cloudBackup) "On: you can log in on a new phone. The backup is locked with your password; the server can't read it." else "Off: this account only works on this phone, and the server keeps nothing to restore.") {
            if (me.cloudBackup) popup("Turn off cloud backup?",
                "The server will wipe its locked copy of your keys and friends. You'll no longer be able to log in on another phone or after reinstalling. Nothing will be left on the server to hand over.", null,
                "Cancel" to dim, "Turn off" to red) { i ->
                if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(false) }, { showSettings() }) { e -> toast(errorText(e)) }
            } else popup("Turn on cloud backup?",
                "A copy of your keys and friends, locked with your password, is kept on the server so you can log in on a new phone.", null,
                "Cancel" to dim, "Turn on" to white) { i ->
                if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(true) }, { showSettings() }) { e -> toast(errorText(e)) }
            }
        })
        col.addView(row("Log out", "Your chats stay locked on this phone for when you log back in.") {
            popup("Log out?", "You can log back in any time with your username and password.", null,
                "Cancel" to dim, "Log out" to red) { i ->
                if (i == 1) {
                    stopService(Intent(this, SyncService::class.java))
                    Messenger.logOut()
                    showWelcome()
                }
            }
        })
        col.addView(row("Delete account", "Removes @${me.user} from the server for good.", red) {
            val pass = field("Password", password = true)
            popup("Delete your account?", "Type your password to confirm. This can't be undone.", pass,
                "Cancel" to dim, "Delete" to red) { i ->
                if (i == 1) Messenger.run({ Messenger.deleteAccount(pass.text.toString()) }, {
                    stopService(Intent(this, SyncService::class.java))
                    showWelcome()
                }) { e -> toast(errorText(e)) }
            }
        })
        col.addView(gap(20))
        col.addView(tv("msgr01 ${packageManager.getPackageInfo(packageName, 0).versionName}\nEvery chat is end-to-end encrypted. The server can't read your messages and keeps no logs.",
            13f, dim).apply { setPadding(dp(20), 0, dp(20), dp(20)) })
        setContentView(page(topBar("Settings", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
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
                        setStroke(dp(1), if (on) white else Color.parseColor("#3A3A3A"))
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
                            setStroke(dp(if (color == current) 3 else 1), if (color == current) white else Color.parseColor("#3A3A3A"))
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(tv(name, 12f, if (color == current) white else dim).apply { setPadding(0, dp(4), 0, 0); maxLines = 1 })
                    setOnClickListener { onPick(color); Messenger.settingsChanged(); showAppearance() }
                }, LinearLayout.LayoutParams(dp(66), -2))
            })
        })

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
