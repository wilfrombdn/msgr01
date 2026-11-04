package com.wilfrombdn.telegramnt

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// One private chat: the screen, message bubbles, replies and edits, keeping your place, and jump-to-message.

internal fun MainActivity.showChat(user: String, restore: Pair<String, Int>? = null) {
    val c = Messenger.core.contact(user) ?: return showChats()
    screen = Screen.CHAT
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
        addView(callBarView(), LinearLayout.LayoutParams(-1, -2))
        addView(chatTop, LinearLayout.LayoutParams(-1, -2))
        addView(banner, LinearLayout.LayoutParams(-1, -2))
        addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }
    setContentView(root)
    fillChat()
}

internal fun MainActivity.fillChat() {
    val c = chatUser?.let { Messenger.core.contact(it) } ?: return
    val list = bubbles ?: return
    Messenger.run({ Messenger.core.markRead(c) }, { Messenger.save() })

    val sub = when (c.status) {
        Contact.ASKED -> "friend request sent"
        Contact.ASKING -> if (c.msgs.any { !it.mine && it.state == Msg.RECEIVED }) "message request" else "wants to be your friend"
        Contact.CHATTING -> if (c.waitingForAdd) "@${c.user} · hasn't added you yet" else "@${c.user} · not your friend"
        Contact.SAVED -> "only on this phone"
        else -> if (c.user in Messenger.core.blocked) "@${c.user} · blocked" else if (c.verified) "@${c.user} · verified ✓" else "@${c.user} · not verified"
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

internal fun MainActivity.authorOf(m: Msg): String = if (m.mine) Messenger.core.me?.user ?: "" else m.sender ?: chatUser ?: ""

internal fun MainActivity.displayName(user: String): String = when (user) {
    Messenger.core.me?.user -> "You"
    else -> if (user.startsWith("sms:")) user.drop(4) + " (text)" else Messenger.core.contact(user)?.name ?: "@$user"
}

internal fun MainActivity.replyRef(m: Msg) = org.json.JSONObject().put("id", m.id).put("u", authorOf(m)).put("p", preview(m).take(160))

/** The strip above the message box: "Reply to Sam" + what they said, with ✕ to cancel. */
internal fun MainActivity.makeReplyBar() = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(listBg)
    setPadding(dp(14), dp(8), dp(4), 0)
    visibility = View.GONE
}

internal fun MainActivity.startReply(m: Msg, box: EditText?) {
    if (editing != null) { editing = null; box?.setText("") }
    replyTo = m
    showBar("Reply to ${displayName(authorOf(m))}", m, box)
}

/** Puts my message back in the box to change it; sending then edits it on both phones. */
internal fun MainActivity.startEdit(m: Msg, box: EditText?) {
    replyTo = null
    editing = m
    showBar("Edit message", m, box)
    box?.setText(m.text); box?.setSelection(box.text.length)
}

internal fun MainActivity.showBar(title: String, m: Msg, box: EditText?) {
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

internal fun MainActivity.cancelReply() {
    if (editing != null) { editing = null; (if (screen == Screen.GROUP) groupBox else chatBox)?.setText("") }
    replyTo = null; replyBar?.visibility = View.GONE
}

/** The quote at the top of a reply bubble; tap it to jump to the original message. */
internal fun MainActivity.quoteView(r: org.json.JSONObject, all: List<Msg>, ink: Int, list: LinearLayout?, sc: ScrollView?): View {
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
            safeBitmap(java.util.Base64.getDecoder().decode(th), 256)?.let { bmp ->
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

internal fun MainActivity.atBottom(s: ScrollView) = (s.getChildAt(0)?.bottom ?: 0) - (s.scrollY + s.height) <= dp(48)

internal fun MainActivity.toBottom(s: ScrollView) = s.scrollTo(0, maxOf(0, (s.getChildAt(0)?.bottom ?: 0) - s.height))

/** The message scroller: follows the bottom when you're there (new messages, photos loading, the
 *  keyboard opening), and otherwise stays exactly where you scrolled to. */
internal fun MainActivity.chatScroller(list: LinearLayout) = ScrollView(this).apply {
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
internal fun MainActivity.anchorOf(s: ScrollView?, list: LinearLayout?): Pair<String, Int>? {
    s ?: return null; list ?: return null
    for (i in 0 until list.childCount) {
        val v = list.getChildAt(i)
        val id = v.tag as? String ?: continue
        if (v.bottom > s.scrollY) return id to (v.top - s.scrollY)
    }
    return null
}

internal fun MainActivity.restoreAnchor(s: ScrollView, list: LinearLayout, r: Pair<String, Int>) {
    for (i in 0 until list.childCount) {
        val v = list.getChildAt(i)
        if (v.tag == r.first) { s.scrollTo(0, maxOf(0, v.top - r.second)); return }
    }
    toBottom(s)   // that message isn't loaded any more: go to the newest
}

/** Remembers the open chat and your place in it, so coming back (even after the app was closed) lands there. */
internal fun MainActivity.saveSpot() {
    val inChat = screen == Screen.CHAT || (screen == Screen.VIEWER && chatUser != null)
    val inGroup = screen == Screen.GROUP || (screen == Screen.VIEWER && chatUser == null && groupId != null)
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

internal fun MainActivity.note(text: String, onClick: (() -> Unit)? = null): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER
    setPadding(dp(24), dp(6), dp(24), dp(6))
    addView(tv(text, 13.5f, white).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = round(pick("#CC262626", "#CCFFFFFF"), 12)
        onClick?.let { f -> setOnClickListener { f() } }
    })
}

internal fun MainActivity.bubble(c: Contact, m: Msg): View {
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

/** Chips under a bubble showing each emoji and how many people reacted; tap to toggle yours. */
internal fun MainActivity.addReactions(b: LinearLayout, m: Msg, toggle: (String) -> Unit) {
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

/** A message's text: formatting, tappable links, highlighted @mentions, tap-to-show spoilers. */
internal fun MainActivity.msgText(m: Msg, ink: Int, people: Set<String>, onLong: () -> Unit): TextView {
    val me = Messenger.core.me?.user ?: ""
    return tv("", 16f, ink).apply {
        textSize = look.msg
        text = Format.render(m.text, ink, blue, people + me, m.id in shownSpoilers) {
            shownSpoilers.add(m.id); if (screen == Screen.GROUP) fillGroup() else fillChat()
        }
        setLinkTextColor(if (ink == Color.WHITE || ink == 0xFFFFFFFF.toInt()) Color.parseColor("#8CC4FF") else Color.parseColor("#1F6FC5"))
        movementMethod = android.text.method.LinkMovementMethod.getInstance()
        setOnLongClickListener { onLong(); true }   // tappable text would otherwise swallow the hold
    }
}

/** "today at 14:30" / "tomorrow at 08:00" / "Oct 30 at 09:00". */
internal fun MainActivity.whenText(at: Long): String {
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
internal fun MainActivity.pickSendTime(done: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val opts = mutableListOf("In 30 minutes" to now + 30 * 60_000L, "In 1 hour" to now + 3_600_000L, "In 3 hours" to now + 3 * 3_600_000L)
    if (at(0, 21) > now + 30 * 60_000L) opts.add("Tonight at 21:00" to at(0, 21))
    opts.add("Tomorrow at 08:00" to at(1, 8)); opts.add("Tomorrow at 18:00" to at(1, 18))
    pickList("Schedule message", "It waits on this phone and goes out at that time ($APP_NAME needs to be running in the background).",
        opts.map { it.first }.toTypedArray()) { i -> done(opts[i].second) }
}

/** Scrolls the chat to message [id] and flashes it. */
internal fun MainActivity.jumpTo(id: String, list: LinearLayout?, sc: ScrollView?) {
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
internal fun MainActivity.pinnedBar(id: String?, msgs: List<Msg>, list: LinearLayout?, sc: ScrollView?, canUnpin: Boolean, unpin: () -> Unit): View? {
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

/** Type "@" in a group: a list of matching members appears above the box; tap one to put in "@username ". */
internal fun MainActivity.mentionPicker(box: EditText, g: Group): LinearLayout {
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
internal fun MainActivity.keepDraft(box: EditText, key: String) = box.addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
    override fun afterTextChanged(e: android.text.Editable?) {
        if (editing != null) return
        val t = e?.toString().orEmpty()
        if (t.isBlank()) Messenger.drafts.remove(key) else Messenger.drafts[key] = t
    }
})
