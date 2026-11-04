package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.content.ClipboardManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun MainActivity.groupRow(g: Group, key: String? = null, bg: Int = listBg): View {
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
    }, onPin = { togglePinKey("g:${g.id}") }, pinLabel = if ("g:${g.id}" in Messenger.pins) "Unpin" else "Pin")
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(swipe, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
    }
}

// ---------- groups ----------

internal fun MainActivity.showNewGroup() {
    screen = Screen.NEW_GROUP
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

internal fun MainActivity.joinGroupPrompt() {
    val box = field("Paste the group invite link")
    (getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())
        ?.let { if (it.contains("msgr01://group")) box.setText(it) }
    popup("Join a group", "Paste a public group's invite link.", box, "Cancel" to dim, "Join" to white) { i ->
        if (i == 1) Messenger.run({ val g = Messenger.core.joinGroupLink(box.text.toString().trim()); Messenger.kick(); groupToOpen = g.id }, {
            groupToOpen?.let { showGroup(it) }
        }) { e -> toast(errorText(e)) }
    }
}

internal fun MainActivity.showGroup(id: String, restore: Pair<String, Int>? = null) {
    val g = Messenger.core.group(id) ?: return showChats()
    screen = Screen.GROUP; groupId = id; chatUser = null
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
        addView(callBarView(), LinearLayout.LayoutParams(-1, -2))
        addView(groupTop, LinearLayout.LayoutParams(-1, -2))
        addView(groupScroller, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    })
    fillGroup()
}

internal fun MainActivity.fillGroup() {
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

internal fun MainActivity.groupBubble(g: Group, m: Msg): View {
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

internal fun MainActivity.groupMessageMenu(g: Group, m: Msg) {
    val me = Messenger.core.me?.user
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (m.state == Msg.SCHEDULED) opts.add("Send now" to { Messenger.sendScheduled(m) })
    if (g.canPost(me ?: "")) opts.add("Reply" to { startReply(m, groupBox) })
    if (m.text.isNotEmpty()) opts.add((if (m.file != null) "Copy text" else "Copy") to {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("msg", m.text)); toast("Copied")
    })
    opts.addAll(imageActions(m, if (m.text.isNotEmpty()) "Copy photo" else "Copy"))
    if (canForward(m)) opts.add("Forward" to { forwardPicker(m, authorOf(m)) })
    if (canForward(m)) opts.add("Save" to { saveTellem(m, authorOf(m)) })
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

internal fun MainActivity.groupMenu(g: Group) {
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

internal fun MainActivity.addToGroup(g: Group) {
    val friends = synchronized(Messenger.core) { Messenger.core.contacts.filter { it.canChat && it.user !in g.members } }
    if (friends.isEmpty()) { toast("No friends left to add"); return }
    pickList("Add to ${g.name}", null, friends.map { it.name }.toTypedArray()) { i ->
        Messenger.run({ Messenger.core.inviteToGroup(g, friends[i].user); Messenger.kick() }, { fillGroup() }) { e -> toast(errorText(e)) }
    }
}

/** Admin-only screen: rename, read-only, and who the admins are. */
internal fun MainActivity.showGroupSettings(g: Group) {
    screen = Screen.GROUP_SETTINGS
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
