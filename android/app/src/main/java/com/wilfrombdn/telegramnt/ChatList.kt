package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.content.ClipboardManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

// The main chat list (Tellem window): rows, search, pins, archive and the Tor status title.

internal fun MainActivity.showChats(searchOnly: Boolean = false) {
    val refreshing = screen == Screen.CHATS   // same screen redrawing (new message, reconnect), not arriving from elsewhere
    if (!refreshing) window.decorView.post { maybeAskRingSetup() }
    emojiPopup?.dismiss(); emojiPopup = null
    if (screen != Screen.CHATS) Messenger.spot = Spot()
    screen = Screen.CHATS
    chatUser = null
    Messenger.core.openChat = null
    val core = Messenger.core
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val q = search.lowercase()
    val all = core.sorted().filter { (q.isEmpty() || it.name.lowercase().contains(q) || it.user.contains(q)) &&
        (it.status != Contact.SAVED || it.msgs.any { m -> m.state != Msg.NOTE }) }   // Saved Messages shows up after the first save
    val grps = synchronized(core) { core.groups.toList() }.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val requests = all.filter { it.status == Contact.ASKING }
    val textsOn = Texts.canRead(this)
    val mixed = textsOn && Texts.mixed(this)
    if (mixed && phoneSub == "texts") phoneSub = "calls"   // texts already sit among the chats
    if (phoneSub == "folders" && folderPlace() != "tab") phoneSub = if (mixed) "calls" else "texts"
    val phoneOnly = textsTab && !showArchive
    // everything else, keyed "u:<user>" / "g:<group>" so folders can hold either
    val rows = (all - requests.toSet()).map { Triple("u:${it.user}", it.lastTs, it as Any) } + grps.map { Triple("g:${it.id}", it.lastTs, it as Any) }
    fun rowFor(t: Triple<String, Long, Any>, bg: Int = listBg) = (t.third as? Contact)?.let { chatRow(it, t.first, bg) } ?: groupRow(t.third as Group, t.first, bg)
    val nested = nestedBg   // chats inside an open folder sit on a slightly different strip
    val byKey = rows.associateBy { it.first }
    val inFolder = HashSet<String>()
    val arch = Messenger.archived
    if (q.isEmpty()) arch.retainAll { it in byKey || it.startsWith("u:") && core.contact(it.drop(2)) != null }   // forget deleted chats
    if (phoneOnly) when (phoneSub) {
        "calls" -> addCallRows(list, q)
        "contacts" -> addContactRows(list, q)
        "folders" -> addFolderTab(list)
        else -> addTextRows(list, q)
    }
    else if (showArchive && q.isEmpty()) {
        // the Archived chats page: just those, newest first
        for (t in rows.filter { it.first in arch }.sortedByDescending { it.second }) list.addView(rowFor(t))
    } else {
    if (q.isEmpty() && !phoneOnly) ringFixes().firstOrNull()?.let { f ->   // messages arrive late while the phone is set to cut Tellem's internet
        list.addView(note("Messages can arrive late: ${f.first.replaceFirstChar { it.lowercase() }} isn't set up. Tap to fix.") { f.third() })
    }
    if (mixed && q.isEmpty() && folderPlace() == "top") folderRows(list)
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
    // with "show texts together with chats", text threads sit among the chats (never in pins or folders)
    val loose: List<Pair<Long, () -> View>> = rows.filter { it.first !in inFolder }.map { t -> t.second to { rowFor(t) } } +
        (if (mixed) Texts.threads.filter { !it.spam && ((!it.code && !it.archived) || q.isNotEmpty()) && textMatches(it, q) }.map { t -> t.date to { textRow(t) } } else emptyList())
    addBatched(list, loose.sortedByDescending { it.first }, 72, q.isNotEmpty() || (mixed && folderPlace() == "bottom") || showArchive) { it.second() }
    if (mixed && q.isEmpty() && folderPlace() == "bottom") folderRows(list)
    }
    if (q.length >= 2 && !phoneOnly) messageResults(list, q)
    if (!phoneOnly && all.isEmpty() && grps.isEmpty() && (!mixed || Texts.threads.isEmpty()) && (q.length < 2 || list.childCount == 0)) {
        list.addView(tv(
            if (q.isNotEmpty()) "No chats match \"$search\""
            else "Welcome, ${core.me?.name}!\n\nTap the pencil to add a friend by their @username.\n\nYour username is @${core.me?.user}. Share it so friends can add you.",
            15.5f, dim,
        ).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)); setLineSpacing(0f, 1.2f) })
    }
    liveList?.takeIf { searchOnly && refreshing && it.isAttachedToWindow }?.let { live ->
        live.removeAllViews()
        while (list.childCount > 0) { val v = list.getChildAt(0); list.removeViewAt(0); live.addView(v) }
        return
    }
    val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
    chatsScroll = scroll
    // keep the list where you had it across refreshes (new messages, folder open/close)
    val y = chatsScrollY
    list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
            list.removeOnLayoutChangeListener(this); scroll.scrollTo(0, y)
        }
    })
    scroll.setOnScrollChangeListener { _, _, sy, _, _ -> chatsScrollY = sy }
    enableFolderDrops(list, scroll)
    val frame = TabSwipeFrame(this, phoneOnly, { drawer?.openDrawer(GravityCompat.START) }, { d -> canSwipePhoneTab(d) }, { animationsOn() }) { d, l -> swipePhoneTab(d, l) }
    chatsFrame = frame
    frame.addView(scroll)
    // The round button sits in the middle of the gap between the Tellem | Phone switch and the right edge of the screen, and a
    // little above the bottom, so either thumb reaches it. (Until the switch has been measured, 3/4 of the way across is close.)
    val fabLp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply {
        leftMargin = (resources.displayMetrics.widthPixels * 0.75f).toInt() - dp(28); bottomMargin = dp(44)
    }
    frame.addView(if (phoneOnly) phoneButton() else fab(R.drawable.ic_edit) { showNewChat() }, fabLp)
    if (q.isEmpty() && !screenSearch && !showArchive) {
        val sw = windowSwitch(all.sumOf { it.unread } + grps.sumOf { it.unread }, textsUnread())
        frame.addView(sw, FrameLayout.LayoutParams(-2, dp(56), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(16)) })
        sw.addOnLayoutChangeListener { _, _, _, right, _, _, _, _, _ ->
            if (frame.width > 0) {
                val m = (right + frame.width) / 2 - dp(28)
                if (fabLp.leftMargin != m) { fabLp.leftMargin = m; frame.requestLayout() }
            }
        }
    }

    val top = if (showArchive && search.isEmpty() && !screenSearch) topBar("Archived chats", null, icon(R.drawable.ic_back) { showArchive = false; showChats() })
    else if (search.isNotEmpty() || screenSearch) searchBar() else {
        // the name at the top shows Tellem (chats) or Phone; the bottom-left switch changes between them
        val flip = android.text.SpannableStringBuilder()
        if (textsTab) flip.append("Phone")
        else flip.append(if (!Messenger.connected) "Connecting…" else if (Messenger.direct) APP_NAME else torTitle())
        topBar(flip,
            if (textsTab) null else if (Messenger.connected) null else if (Messenger.direct) "Direct connection" else Tor.status(),
            icon(R.drawable.ic_menu).also { v -> v.setOnClickListener { drawer?.openDrawer(GravityCompat.START) } },
            icon(R.drawable.ic_search) { screenSearch = true; showChats() },
        )
    }
    // Content + a left slide-out drawer (opens by tapping the lines or swiping from the edge).
    val keepOpen = refreshing && drawer?.isDrawerOpen(GravityCompat.START) == true   // refreshes rebuild the screen; don't snap the menu shut
    val d = DrawerLayout(this)
    val body = if (!showArchive) LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (textsTab) addView(textTabs(all.sumOf { it.unread } + grps.sumOf { it.unread }, mixed))
        addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
    } else frame
    d.addView(page(top, body), DrawerLayout.LayoutParams(-1, -1))
    d.addView(drawerPanel(d), DrawerLayout.LayoutParams(
        minOf(dp(300), (resources.displayMetrics.widthPixels * 0.82).toInt()), -1
    ).apply { gravity = GravityCompat.START })
    d.setScrimColor(0x99000000.toInt())
    drawer = d
    liveList = list
    if (flipAnim) { flipAnim = false; if (android.animation.ValueAnimator.areAnimatorsEnabled()) (top as? ViewGroup)?.getChildAt(1)?.let { t -> t.alpha = 0f; t.translationX = dp(28).toFloat(); t.animate().alpha(1f).translationX(0f).setDuration(220).start() } }
    setContentView(d)
    if (keepOpen) d.openDrawer(GravityCompat.START, false)
    // while still connecting over Tor, refresh so the status line (bootstrap %, ready, error) updates live
    if (!Messenger.connected) d.postDelayed({ if (screen == Screen.CHATS && !Messenger.connected) redraw() }, 3000)
}

/** "Tellem" with a small raised "TOR" after it, like a squared number: shows the app is talking through Tor. */
internal fun MainActivity.torTitle() = android.text.SpannableString("$APP_NAME TOR").apply {
    setSpan(android.text.style.SuperscriptSpan(), APP_NAME.length + 1, length, 0)
    setSpan(android.text.style.RelativeSizeSpan(0.5f), APP_NAME.length, length, 0)
}

/** Tor's latest messages in a pop-up, with Copy, so a stuck connection can be sent to whoever is fixing it. */
internal fun MainActivity.torDetails() {
    val text = (listOf(Tor.status()) + Tor.log()).joinToString("\n")
    popup("Tor details", text, null, "Close" to dim, "Copy" to white) { i ->
        if (i == 1) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("tor", text)); toast("Copied") }
    }
}

/** The "Archived chats" row: opens the page of chats you've put away. */
internal fun MainActivity.archiveRow(unread: Int, count: Int): View = LinearLayout(this).apply {
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

internal fun MainActivity.searchBar(): View {
    val box = field("Search", search).apply {
        background = null
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (search != s.toString()) { search = s.toString(); chatsScrollY = 0; showChats(searchOnly = true) }
            }
        })
    }
    // each letter redraws the screen, so take focus back and keep the keyboard up (it dropped in the Phone tab)
    box.post {
        box.requestFocus(); box.setSelection(box.text.length)
        getSystemService(InputMethodManager::class.java).showSoftInput(box, InputMethodManager.SHOW_IMPLICIT)
    }
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bar)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(icon(R.drawable.ic_back) { search = ""; screenSearch = false; showChats() })
        addView(box, LinearLayout.LayoutParams(0, -2, 1f))
    }
}

internal fun MainActivity.pinMark() = tv("📌", 12f, dim).apply { setPadding(dp(4), 0, 0, 0) }

internal fun MainActivity.togglePinKey(key: String) {
    val ps = Messenger.pins
    if (key in ps) ps.remove(key) else { ps.remove(key); ps.add(0, key) }
    Messenger.settingsChanged(); showChats()
}

/** Long-press a chat and let go without dragging it: pin / unpin. */
internal fun MainActivity.chatRowMenu(key: String) {
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

internal fun MainActivity.chatRow(c: Contact, key: String? = null, bg: Int = listBg): View {
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
    val swipe = SwipeRow(this, row, R.drawable.ic_trash, onOpen = { showChat(c.user) }, onDelete = { deleteChatConfirm(c) },
        onPin = { togglePinKey("u:${c.user}") }, pinLabel = if ("u:${c.user}" in Messenger.pins) "Unpin" else "Pin")
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(swipe, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
    }
}

internal fun MainActivity.deleteChatConfirm(c: Contact) {
    popup("Delete chat with ${c.name}?", "This erases the whole chat from this phone for good. The server keeps no copy, no backup and no way to recover it — once it's gone, it's gone. Are you sure?",
        null, "Cancel" to dim, "Delete" to red) { i -> if (i == 1) { Messenger.core.remove(c); showChats() } }
}

/** Search results inside messages (all chats and groups): tap one to open the chat at that message. */
internal fun MainActivity.messageResults(list: LinearLayout, q: String) {
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

/** Flip between the Tellem window and the Phone window (the bottom-left switch does this). */
internal fun MainActivity.switchWindow() {
    finishSlide()
    val old = if (animationsOn()) contentRoot()?.let { snapshotOf(it) } else null   // a picture of this screen, taken before it changes
    val dir = if (textsTab) -1 else 1   // to Phone: it comes in from the right; back to Tellem: from the left
    textsTab = !textsTab; chatsScrollY = 0
    showChats()
    old?.let { slideScreens(it, dir) }
}

/**
 * A small "Tellem | Phone" switch at the bottom-left, within thumb reach on a big phone. The side you're on is filled;
 * the other side shows its unread count, and tapping it switches window.
 */
internal fun MainActivity.windowSwitch(tellemUnread: Int, phoneUnread: Int): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    background = round(menuBg, 28); elevation = dp(6).toFloat()
    setPadding(dp(5), dp(5), dp(5), dp(5))
    // a tap anywhere on the pill (either side) switches window
    isClickable = true
    contentDescription = "Switch between $APP_NAME and Phone"
    setOnClickListener { switchWindow() }
    fun seg(label: String, unread: Int, on: Boolean) = addView(LinearLayout(context).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        background = if (on) round(white, 23) else null
        addView(tv(label, 15f, if (on) ink else dim, true))
        if (!on && unread > 0) addView(tv(if (unread > 99) "99+" else "$unread", 11.5f, android.graphics.Color.WHITE, true).apply {
            gravity = Gravity.CENTER; minWidth = dp(20); setPadding(dp(5), dp(1), dp(5), dp(1))
            background = round(if (label == "Phone") smsGreen else blue, 10)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
    }, LinearLayout.LayoutParams(-2, -1))
    seg(APP_NAME, tellemUnread, !textsTab)
    seg("Phone", phoneUnread, textsTab)
}
