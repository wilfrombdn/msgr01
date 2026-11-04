package com.wilfrombdn.telegramnt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File

/** Telegram-style dark look, every screen built in code (like League Radio). */
class MainActivity : Activity() {
    // dark greys; bubble colours, sizes and corners come from Settings > Appearance
    // Light / Dark / match the phone (Settings > Appearance). Each colour below has a dark and a light value.
    internal val dark: Boolean get() = when (look.theme) {
        "light" -> false
        "system" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_NO
        else -> true
    }
    private val parsedColors = java.util.concurrent.ConcurrentHashMap<String, Int>()   // "#RRGGBB" -> colour, parsed once
    internal fun pick(d: String, l: String): Int = (if (dark) d else l).let { hex -> parsedColors.getOrPut(hex) { Color.parseColor(hex) } }
    internal val bar get() = pick("#1F1F1F", "#F1F3F6")       // top bars
    internal val listBg get() = pick("#141414", "#F7F8FA")    // chat list, inputs
    internal val wall get() = pick("#0B0B0B", "#E8EBEF")      // behind messages
    internal val menuBg get() = pick("#2A2A2A", "#FBFBFC")    // drop-down menus
    internal val raised get() = pick("#3A3A3A", "#DCE0E6")    // highlights, outlines, chip borders
    internal val nestedBg get() = pick("#181818", "#EEF0F4")  // chats inside an open folder
    internal val look get() = Messenger.look
    internal val blue = Color.parseColor("#4EA4F6")      // status only: unread, ticks, requests
    internal val ink get() = pick("#111111", "#F7F8FA")       // text on the main (white / dark) buttons
    internal val white get() = pick("#FFFFFF", "#2D323A")     // main text and icons (dark text in light mode)
    internal val dim get() = pick("#8E8E93", "#757B86")
    internal val divider get() = pick("#262626", "#E0E4E9")
    internal val faint get() = pick("#5A5A5A", "#B5BAC2")
    internal val panel2 get() = pick("#2A2A2A", "#E7EAEF")
    internal val soft get() = pick("#C7C7CC", "#4A505A")      // body text in pop-ups (between white and dim)
    internal val sunk get() = pick("#161616", "#FFFFFF")      // inside text boxes, the opened call, the Tor log
    internal val chip get() = pick("#303133", "#E3E6EB")      // small squared button tiles
    internal val red = Color.parseColor("#E5534B")
    internal val amber get() = pick("#E8C24A", "#9A6B00")   // gentle yellow for the Tor "first connection is slow" note
    internal val avatarColors = listOf("#E17076", "#FAA774", "#A695E7", "#7BC862", "#6EC9CB", "#65AADD", "#EE7AAE")
        .map { Color.parseColor(it) }

    // Backgrounds get a faint, tileable monochrome noise so screens aren't dead-flat — gives the app a
    // bit of a textured, "real surface" feel. Same base colour, just gently grainy. Cached per colour.
    internal val noiseTiles = HashMap<Int, android.graphics.Bitmap>()

    internal var openCallAt = 0L                          // the call opened out in the Calls list (0 = none); it closes when you change screen or tab
    internal var imeWanted = false                        // the screen being shown will open the keyboard itself
    internal var wantSearchIme = false                    // opening search: raise the keyboard (not on every redraw)
    internal var screen = Screen.CHATS
        set(v) { if (v != field) openCallAt = 0L; field = v }
    internal var chatUser: String? = null
    internal var search = ""
    internal val refresh: () -> Unit = { redrawSoon() }
    internal var redrawQueued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep chats out of screenshots and the recent-apps preview
        applyShotPolicy()
        applyBars()
        window.decorView.viewTreeObserver.addOnScrollChangedListener { onAnyScroll() }   // Tellem's own scroll bar (Scrollbar.kt)
        Messenger.init(this)
        SyncService.channels(this)
        File(cacheDir, "open").deleteRecursively() // unlocked copies of opened attachments
        Voice.cleanup(this)                          // a recording cut off when the app was closed
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val fresh = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        if (Messenger.locked && Messenger.signedIn) { afterUnlock = if (fresh) intent else null; showLock(); return }
        if (Messenger.signedIn) reopenSpot() else { showWelcome(); Messenger.connectInBackground() }
        // a notification tap opens that chat; but not again when reopening from recents (keep your place)
        if (fresh) handle(intent)
    }

    // ---------- app lock ----------

    /** A notification tap or share that arrived while locked: carried out after unlocking. */
    internal var afterUnlock: Intent? = null
    internal var lockAsked = false
    internal var pendingIdentity: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Messenger.locked && Messenger.signedIn) { afterUnlock = intent; return showLock() }   // open it once unlocked
        handle(intent)
    }

    internal fun handle(i: Intent?) {
        if (i?.data?.scheme in setOf("sms", "smsto", "mms", "mmsto")) return openTextIntent(i!!)
        i?.getLongExtra("textThread", 0L)?.takeIf { it > 0 }?.let { return showTextThread(it) }
        i?.getStringExtra("addContact")?.let { n -> return showAddContact(n) { showChats() } }
        if (i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_SEND_MULTIPLE) return shared(i)
        i?.getStringExtra("chat")?.let { u -> if (Messenger.core.contact(u) != null) showChat(u) }
        i?.getStringExtra("group")?.let { g -> if (Messenger.core.group(g) != null) showGroup(g) }
    }

    override fun onStart() {
        super.onStart()
        maybeCheckUpdate()
        Messenger.visible = true
        Messenger.appShown()
        Messenger.listen(refresh)
        Messenger.callListeners.add(callWatch); callTicker.post(callTick)
        Texts.listen(this, textsChanged)
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
        Messenger.callListeners.remove(callWatch); callTicker.removeCallbacks(callTick)
        Texts.unlisten(textsChanged); Texts.openThread = 0
        if (!Messenger.background) stopService(Intent(this, SyncService::class.java))
        super.onStop()
    }

    /** Every screen change goes through here. If the keyboard was down, it stays down unless the new screen asks for it (it used to pop back up). */
    override fun setContentView(view: View?) {
        val wasUp = imeVisible()
        super.setContentView(view)
        val want = imeWanted || wantSearchIme; imeWanted = false
        if (!wasUp && !want) window.decorView.post { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            emojiPopup != null -> { emojiPopup?.dismiss(); emojiPopup = null }
            (replyTo != null || editing != null) && (screen == Screen.CHAT || screen == Screen.GROUP) -> cancelReply()
            drawer?.isDrawerOpen(GravityCompat.START) == true -> drawer?.closeDrawer(GravityCompat.START)
            screen == Screen.CROP && cropCancel != null -> cropCancel?.invoke()
            screen == Screen.SNAP_SEND -> cancelSnap()
            screen == Screen.TEXT_THREAD || screen == Screen.NEW_TEXT || screen == Screen.TEXT_SPAM -> leaveTexts()
            !Messenger.signedIn && screen != Screen.WELCOME -> showWelcome()
            screen == Screen.VIEWER -> leaveViewer()
            screen == Screen.STORAGE -> showSettings()
            screen == Screen.ADD_CONTACT -> addContactBack()
            screen == Screen.CHATS && showArchive -> { showArchive = false; showChats() }
            Messenger.signedIn && screen != Screen.CHATS -> showChats()
            search.isNotEmpty() -> { search = ""; showChats() }
            screen == Screen.CHATS && textsTab -> switchWindow()   // back on the Phone window goes to Tellem first, then a second back leaves the app
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        updatePoll.removeCallbacks(updatePollRun)
        emojiPopup?.dismiss(); emojiPopup = null
        super.onPause()
    }

    // ---------- in-app updates ----------

    internal var updateChecked = 0L
    internal var updateShown = 0L          // newest version already offered in this run, so a "Not now" is not nagged again every minute
    internal var updateDialogOpen = false
    internal val updatePoll = android.os.Handler(android.os.Looper.getMainLooper())
    internal val updatePollRun = object : Runnable {   // while the app is open: look for a new version every minute
        override fun run() { pollUpdate(); updatePoll.postDelayed(this, 60_000) }
    }
    internal var pendingApk: File? = null

    override fun onResume() {
        super.onResume()
        updatePoll.removeCallbacks(updatePollRun); updatePoll.postDelayed(updatePollRun, 5_000)   // an update that dropped while the app was away shows within seconds
        // back from "allow installs from Tellem": carry on with the update
        if (newContactPending) { newContactPending = false; Messenger.run({ Texts.forgetNames(); Texts.threads.forEach { t -> t.addresses.forEach { Texts.name(this, it) } } }, { if (screen == Screen.TEXT_THREAD) showTextThread(textThread) else if (screen == Screen.CHATS) showChats() }) }
        pendingApk?.let { f -> if (packageManager.canRequestPackageInstalls()) { pendingApk = null; Messenger.run({ Updater.install(this, f) }) } }
        // back from a phone setting (battery, full-screen alerts, default texting app): show what changed
        if (screen == Screen.SETTINGS || screen == Screen.TEXT_THREAD && textMadeDefault != Texts.isDefault(this)) rebuildTextScreen()
    }

    internal val photoCache = HashMap<Int, android.graphics.Bitmap?>()

    internal var callBar: TextView? = null
    internal val callTicker = android.os.Handler(android.os.Looper.getMainLooper())
    internal val callTick = object : Runnable { override fun run() { updateCallBar(); callTicker.postDelayed(this, 1000) } }
    internal val callWatch: (Core.Call) -> Unit = { runOnUiThread { updateCallBar() } }
    internal var notice: TextView? = null

    /** Welcome-screen connection line: "Connecting." → ".." → "..." once a second; then a green
     *  "Connected!" for a second before moving on. The slow-Tor note only shows after 30 s of trying. */
    internal var connectStart = 0L
    internal var dots = 0
    internal var celebrating = false
    internal val green = Color.parseColor("#4CD964")
    internal val ticker = object : Runnable {
        override fun run() {
            val st = welcomeStatus ?: return
            if (screen != Screen.WELCOME || celebrating) return
            if (Messenger.connected) {
                welcomeTorNote?.visibility = View.GONE; welcomeTorLine?.visibility = View.GONE; welcomeDirect?.visibility = View.GONE
                if (st.text.isEmpty()) return   // already connected when the screen opened: nothing to show
                celebrating = true
                st.text = "Connected!"; st.setTextColor(green)
                st.postDelayed({
                    celebrating = false
                    st.text = ""; st.setTextColor(dim)
                    if (screen == Screen.WELCOME) pendingNext?.let { pendingNext = null; it() }
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
            if (Tor.outdated) maybeCheckUpdate()   // a newer app (with a newer Tor) is the only fix
            if (System.currentTimeMillis() - connectStart >= 30_000) {
                welcomeTorNote?.visibility = if (Messenger.direct) View.GONE else View.VISIBLE
                welcomeTorLine?.apply { visibility = View.VISIBLE; text = if (Messenger.direct) (Messenger.directProblem ?: "Trying a direct connection…") else Tor.status() }
                welcomeDirect?.apply { visibility = View.VISIBLE; text = if (Messenger.direct) "Use Tor instead" else "Connect directly instead" }
            }
            st.postDelayed(this, 1000)
        }
    }

    internal var welcomeStatus: TextView? = null
    internal var welcomeTorNote: TextView? = null
    internal var welcomeTorLine: TextView? = null
    internal var welcomeDirect: TextView? = null
    internal val TOR_WAIT_NOTE = "First connection can take a minute. The app is building a private route through the Tor network — hopping your connection through several relays around the world and setting up fresh encryption at each one. That's slower than a normal connection, but it's what hides where you are. It's quicker after this."
    internal var pendingNext: (() -> Unit)? = null

    /** The chat list inside the open screen: while you type in search only its rows are swapped, so nothing flashes. */
    internal var liveList: LinearLayout? = null
    internal var flipAnim = false

    internal var screenSearch = false
    internal var chatsScrollY = 0
    internal var chatsFrame: android.view.ViewGroup? = null   // the list area of the chat screen, and its scrolling list (for slide animations)
    internal var chatsScroll: android.view.View? = null
    internal var slideEnd: (() -> Unit)? = null   // set while a slide animation runs: finishes it on the spot (see finishSlide)
    internal var cropCancel: (() -> Unit)? = null              // set while the crop screen was opened for a contact photo: what Cancel and Back do
    internal var contactFormView: android.view.View? = null    // the contact form, kept while a photo is picked and cropped
    internal var contactPhotoDone: ((ByteArray) -> Unit)? = null
    internal val contactThumbs = HashMap<String, android.graphics.Bitmap?>()   // phone contacts' small photos, by address
    /** A chat is being dragged: hold off rebuilding the list (that would cancel the drag) until it's dropped. */
    internal var dragging = false

    // ---------- folders ----------

    /** Showing the Archived chats page instead of the main list. */
    internal var showArchive = false

    /** Long-press a chat row to pick it up. */
    internal var dragRow: View? = null

    // ---------- normal texts (SMS/MMS): kept visibly apart from private Tellem chats ----------

    internal val smsGreen = Color.parseColor("#2E7D4F")   // normal texts' own bubble colour, never a Tellem one
    internal var textsTab = false
        set(v) { if (v != field) openCallAt = 0L; field = v }                        // separate mode: the Texts tab is showing
    internal var textThread = 0L
    internal var textPeople: List<String> = emptyList()
    internal var textImage: Pair<ByteArray, String>? = null
    internal var textImageBar: TextView? = null
    internal var textDraft: String? = null
    internal val textPics = android.util.LruCache<String, android.graphics.Bitmap>(24)
    internal val textsChanged: () -> Unit = { redrawSoon() }

    internal var phoneSub = "texts"
        set(v) { if (v != field) openCallAt = 0L; field = v }
    internal var phoneCalls: List<Phone.Call>? = null
    internal var phonePeople: List<Phone.Person>? = null
    internal var phoneLoaded = 0L

    // ---------- Snap: hold the pencil, slide onto "Snap", let go ----------

    internal var snapBack: View? = null     // the screen you were on, put back after sending
    internal var snapScreen = Screen.CHATS
    internal var snapUri: android.net.Uri? = null

    internal var addContactBack: () -> Unit = {}
    internal var addContactSave: (() -> Unit)? = null
    internal var textSectionKind = "spam"

    internal var newContactPending = false   // came back from adding a contact: refresh names
    internal val textSpots = HashMap<Long, Int>()   // where you were in each text chat (kept until the app closes)
    internal var textRestoreY = -1
    internal var newTextTo: EditText? = null

    internal var textList: LinearLayout? = null
    internal var textKeys: List<String> = emptyList()
    internal val textViews = HashMap<String, View>()
    internal var textScroll: ScrollView? = null

    internal var textMadeDefault = false   // texting-app state when the open text chat was drawn

    companion object {

        /** Fixed reactions, then the most-used emoji not already in the bar, padded with [filler]. */
        fun quickReactions(use: Map<String, Int>, fixed: List<String>, filler: List<String>): List<String> {
            fun key(e: String) = e.replace("\uFE0F", "")   // ❤ and ❤️ are the same emoji
            val seen = fixed.map(::key).toMutableSet()
            val extra = (use.entries.sortedByDescending { it.value }.map { it.key } + filler)
                .filter { seen.add(key(it)) }.take(3)
            return fixed + extra
        }
    }

    /** Chats pinned to the top (shown with a pin). */
    internal var pinnedKeys = emptySet<String>()

    internal var groupToOpen: String? = null

    internal var groupId: String? = null
    internal var groupBubbles: LinearLayout? = null
    internal var groupScroller: ScrollView? = null
    internal var groupTop: LinearLayout? = null

    // ---------- one chat ----------

    internal var bubbles: LinearLayout? = null
    internal var scroller: ScrollView? = null
    internal var chatTop: LinearLayout? = null
    internal var banner: FrameLayout? = null

    // ---------- replies ----------

    /** The message you're replying to (shown above the message box until you send or tap ✕). */
    internal var replyTo: Msg? = null
    /** My message being edited in the message box (instead of writing a new one). */
    internal var editing: Msg? = null
    internal var chatBox: EditText? = null
    internal var groupBox: EditText? = null
    internal var replyBar: LinearLayout? = null

    // ---------- keeping your place in a chat ----------

    /** True while you're at (or within a hair of) the newest message: new ones then keep you at the bottom. */
    internal var stick = true
    /** A message id + its offset from the top of the screen to put back after the list is rebuilt. */
    internal var restoreTo: Pair<String, Int>? = null

    // ---------- calls ----------

    internal var callAfterMic: Contact? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TEXT_PERMS || requestCode == CALL_LOG_PERM || requestCode == CONTACTS_PERM) {
            Texts.forgetNames(); Texts.reload(this); phoneLoaded = 0; phoneCalls = null
            if (screen == Screen.CHATS) showChats()
            return
        }
        if (requestCode == ADD_CONTACT_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) addContactSave?.invoke() else toast("Allow contacts access to save a contact")
            return
        }
        if (requestCode == LOCATION_FOR_SHARE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) shareLocation() else toast("$APP_NAME needs location access to share where you are")
            return
        }
        if (requestCode == MIC_FOR_VOICE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("Hold the mic to record, let go to send")
            else toast("$APP_NAME needs the microphone for voice messages")
            return
        }
        if (requestCode != MIC_FOR_CALL) return
        val c = callAfterMic; callAfterMic = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) c?.let { callFriend(it) }
        else toast("$APP_NAME needs the microphone for calls")
    }

    internal val fixedReactions = listOf("👍", "❤️", "😂", "😮", "😢")
    internal val fillerReactions = listOf("🙏", "🔥", "👏", "🎉", "😍", "👀")

    // where the finger last went down (screen coords), so long-press menus grow out of that spot
    internal var touchX = 0f
    internal var touchY = 0f
    internal var touchT = 0L
    /** The message box row of the open chat; taps on it don't close the keyboard/emoji panel. */
    internal var composer: View? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { touchX = ev.rawX; touchY = ev.rawY; touchT = ev.eventTime }
        val handled = super.dispatchTouchEvent(ev)
        // "Vibrate on every tap": a quick press-and-release on something tappable gives a small classic tap
        if (ev.actionMasked == MotionEvent.ACTION_UP && UiPrefs.tapHaptics(this)) {
            val s = ViewConfiguration.get(this).scaledTouchSlop
            if (Math.abs(ev.rawX - touchX) < s && Math.abs(ev.rawY - touchY) < s && ev.eventTime - touchT < 300 && clickableAt(window.decorView, ev.rawX, ev.rawY) != null) tapBuzz()
        }
        // a quick tap anywhere outside the composer closes the emoji panel and the keyboard, like Telegram
        if (ev.actionMasked == MotionEvent.ACTION_UP && (screen == Screen.CHAT || screen == Screen.GROUP)) {
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

    internal fun inside(v: View, x: Float, y: Float): Boolean {
        val l = IntArray(2); v.getLocationOnScreen(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    // ---------- round video messages ----------

    /** The mic button is switched to round video. */
    internal var videoMode = false
    internal var roundHeld = false

    internal var pendingSave: Msg? = null

    // ---------- forwarding ----------

    /** "Forwarded from Sam" at the top of a bubble. */
    /** Messages whose spoilers you've tapped open (until the app closes). */
    internal val shownSpoilers = HashSet<String>()

    /**
     * The link preview above the message box: when you type a link, the page's title is fetched through
     * Tor and shown here; it goes with the message if it's ready when you send. ✕ drops it.
     */
    internal inner class LinkWatch {
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

    internal val links by lazy { LinkWatch() }

    /** "sam is typing…" / "sam and jo are typing…", or null. */
    internal fun groupTyping(g: Group): String? {
        val now = System.currentTimeMillis()
        val who = g.typing.filter { it.value > now }.keys.map { u -> Messenger.core.contact(u)?.name ?: "@$u" }
        return when (who.size) {
            0 -> null
            1 -> "${who[0]} is typing…"
            2 -> "${who[0]} and ${who[1]} are typing…"
            else -> "${who.size} people are typing…"
        }
    }

    internal var listDialog: android.app.Dialog? = null

    internal var drawer: DrawerLayout? = null

    // ---------- attachments ----------

    internal var attachTo: String? = null
    /** The next picked photo goes as view once (attach > View once photo). */
    internal var sendOnce = false
    internal var attachGroupId: String? = null
    internal var emojiPopup: android.widget.PopupWindow? = null
    internal var emojiData: List<Pair<String, List<String>>>? = null
    internal val pictures = HashMap<String, android.graphics.Bitmap?>()

    /** Animated drawables, decoded once per message (fillChat rebuilds the views often). */
    internal val gifCache = HashMap<String, android.graphics.drawable.Drawable>()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIRM_IDENTITY) { val ok = pendingIdentity; pendingIdentity = null; if (resultCode == RESULT_OK) ok?.invoke(); return }
        if (requestCode == SCAN_QR && resultCode == RESULT_OK) return data?.getStringExtra("qr")?.let { scanned(it) } ?: Unit
        if (requestCode == ROUND_VIDEO && resultCode == RESULT_OK) return data?.getStringExtra("path")?.let { sendRound(it) } ?: Unit
        if (requestCode == TEXT_ROLE) return textRoleResult()
        if (requestCode == CALL_ROLE) return callRoleResult()
        if (requestCode == SNAP) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) showSnapSend(u, data?.getBooleanExtra("photo", true) ?: true, data?.getStringExtra("caption") ?: "")
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == CREATE_RECOVERY) return setupRecovery(uri)
        if (requestCode == OPEN_RECOVERY) return restoreFromFile(uri)
        if (requestCode == SAVE_IMAGE) return saveImageTo(uri)
        if (requestCode == PICK_ATTACH_PHOTO || requestCode == PICK_ATTACH_FILE) return attachPicked(uri, requestCode == PICK_ATTACH_PHOTO)
        if (requestCode == TAKE_MEDIA) return attachPicked(uri, data?.getBooleanExtra("photo", true) ?: true)
        if (requestCode == PICK_CONTACT_PHOTO) return contactPhotoPicked(uri)
        if (requestCode == PICK_CONTACT) return contactPicked(uri)
        if (requestCode == TEXT_IMAGE) return textImagePicked(uri)
        if (requestCode == TEXT_CAMERA) return textImagePicked(uri, fromCamera = true)
        if (requestCode != PICK_PHOTO) return
        var bmp: android.graphics.Bitmap? = null
        Messenger.run({ bmp = loadPhoto(uri) }, { bmp?.let { showCrop(it) } ?: toast("Couldn't open that photo") }) {
            toast("Couldn't open that photo")
        }
    }

    // ---------- app settings ----------

    /** The picked connection's state in Settings ("✓ active" / "connecting…"); kept live while Settings is open. */
    internal var connLabel: TextView? = null
    internal val connTick = object : Runnable {
        override fun run() {
            val l = connLabel ?: return
            if (screen != Screen.SETTINGS || !l.isAttachedToWindow) return
            val on = Messenger.connected
            l.text = if (on) "✓ active" else if (Messenger.direct) (Messenger.directProblem ?: "connecting…") else Tor.status()
            l.setTextColor(if (on) green else amber)
            l.postDelayed(this, 1000)
        }
    }
}
