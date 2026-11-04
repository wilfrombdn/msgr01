package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

// Long-press menus, the side drawer, chat menus, safety codes and the message actions.

/** First five are fixed; the last three are your most-used emoji (never repeating one already shown). */
internal fun MainActivity.quickReactions(): List<String> = MainActivity.quickReactions(synchronized(Messenger.emojiUse) { Messenger.emojiUse.toMap() }, fixedReactions, fillerReactions)

/**
 * Long-press menu: square-cornered panel that grows out of the spot you pressed, with an optional
 * reaction bar on top ([mine] = your current reaction) and the actions below.
 */
internal fun MainActivity.longPressMenu(mine: String?, onReact: ((String) -> Unit)?, actions: List<Pair<String, () -> Unit>>) {
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
internal fun MainActivity.imageActions(m: Msg, copyLabel: String = T("Copy")): List<Pair<String, () -> Unit>> =
    if (m.file?.optBoolean("vo") != true && m.file?.optString("m")?.startsWith("image/") == true && m.file?.has("local") == true)
        listOf(copyLabel to { copyImage(m) }, T("Download") to { downloadImage(m) }, T("Add to my stickers") to { addToStickers(m) }) else emptyList()

internal fun MainActivity.messageMenu(c: Contact, m: Msg) {
    val me = Messenger.core.me?.user
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (m.state == Msg.SCHEDULED) opts.add(T("Send now") to { Messenger.sendScheduled(m) })
    if (c.status != Contact.ASKING) opts.add(T("Reply") to { startReply(m, chatBox) })
    if (m.text.isNotEmpty()) opts.add((if (m.file != null) T("Copy text") else T("Copy")) to {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("msg", m.text)); toast(T("Copied"))
    })
    opts.addAll(imageActions(m, if (m.text.isNotEmpty()) T("Copy photo") else T("Copy")))
    if (canForward(m)) opts.add(T("Forward") to { forwardPicker(m, authorOf(m)) })
    if (canForward(m) && c.status != Contact.SAVED) opts.add(T("Save") to { saveTellem(m, authorOf(m)) })
    if (c.canChat && m.call == null) opts.add((if (c.pinned == m.id) T("Unpin") else T("Pin")) to {
        Messenger.run({ Messenger.core.pinMessage(c, if (c.pinned == m.id) null else m) })
    })
    if (m.mine && m.text.isNotEmpty() && c.canChat) opts.add(T("Edit") to { startEdit(m, chatBox) })
    if (m.mine && m.state != Msg.FAILED && m.state != Msg.SCHEDULED) opts.add(T("Delete for everyone") to {
        Messenger.run({ Messenger.core.deleteMessage(c, m, forEveryone = true) }, { fillChat() })
    })
    opts.add(T("Delete for me") to { Messenger.core.deleteMessage(c, m, forEveryone = false); fillChat() })
    longPressMenu(m.reactions[me], { e -> Messenger.react(c, m, e); fillChat() }, opts)
}

// ---------- mute, block, pinned message, drafts, search ----------

internal fun MainActivity.toggleMute(key: String) {
    val ms = Messenger.muted
    val on = key !in ms
    if (on) ms.add(key) else ms.remove(key)
    Messenger.settingsChanged()
    toast(if (on) T("Muted: no notifications from this chat") else T("Unmuted"))
    redraw()
}

internal fun MainActivity.blockConfirm(c: Contact, on: Boolean) {
    if (!on) { Messenger.core.block(c.user, false); Messenger.save(); toast(T("Unblocked")); showChat(c.user); return }
    popup(T("Block ${c.name}?"), T("They won't be able to message or call you, and won't see when you're online, typing or have read their messages. They aren't told."),
        null, T("Cancel") to dim, T("Block") to red) { i ->
        if (i == 1) { Messenger.core.block(c.user, true); Messenger.save(); showChat(c.user) }
    }
}

internal fun MainActivity.chatMenu(c: Contact, anchor: View) {
    val key = "u:${c.user}"
    val isMuted = key in Messenger.muted
    val isBlocked = c.user in Messenger.core.blocked
    if (c.status == Contact.SAVED) {
        dropdown(anchor, Gravity.END, T("Delete all saved messages") to { deleteChatConfirm(c) })
        return
    }
    dropdown(anchor, Gravity.END,
        (if (isMuted) T("Unmute") else T("Mute")) to { toggleMute(key) },
        (if (isBlocked) T("Unblock") else T("Block")) to { blockConfirm(c, !isBlocked) },
        T("Safety code") to { safety(c) },
        (if (c.ttl == 0) T("Disappearing messages") else T("Disappearing: on")) to { timerMenu(c) },
        T("Rename") to {
            val box = field(T("Name"), c.name)
            popup(T("Rename"), null, box, T("Cancel") to dim, T("Save") to white) { i ->
                if (i == 1) box.text.toString().trim().take(60).takeIf { it.isNotEmpty() }?.let { c.name = it; Messenger.save(); fillChat() }
            }
        },
        T("Remove friend") to {
            popup(T("Delete chat with ${c.name}?"), T("This erases the whole chat from this phone for good. The server keeps no copy, no backup and no way to recover it — once it's gone it's gone. Are you sure?"), null,
                T("Cancel") to dim, T("Delete") to red) { i -> if (i == 1) { Messenger.core.remove(c); showChats() } }
        },
    )
}

internal fun MainActivity.timerMenu(c: Contact) {
    val options = listOf(T("Off") to 0, T("1 minute") to 60, T("1 hour") to 3600, T("1 day") to 86400, T("1 week") to 604800)
    val labels = options.map { it.first }.toTypedArray()
    pickList(T("Disappearing messages"), T("New messages delete themselves on both phones after the time you pick."), labels) { i ->
        Messenger.run({ Messenger.core.setTimer(c, options[i].second); Messenger.kick() }, { fillChat() }) { e -> toast(errorText(e)) }
    }
}

internal fun MainActivity.drawerPanel(d: DrawerLayout): View {
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
    if (textsTab) {
        // Phone screen: texts, dialling, contacts and the text folders
        if (Texts.isDefault(this)) item(R.drawable.ic_edit, T("New Text"), T("a normal text, not encrypted")) { showNewText() }
        else if (Texts.canRead(this)) item(R.drawable.ic_edit, T("Use $APP_NAME for texts"), T("so you can send and receive here")) { askTextRole() }
        item(R.drawable.ic_call, T("Dial pad")) { runCatching { startActivity(Intent(Intent.ACTION_DIAL)) } }
        item(R.drawable.ic_add, T("New Contact")) { showAddContact(null) { showChats() } }
        if (!PhoneCalls.isDefaultDialer(this)) item(R.drawable.ic_call, T("Use $APP_NAME for phone calls"), T("answer and place calls here")) { askCallRole() }
        divider()
        if (folderPlace() == "menu" && Texts.canRead(this)) {
            val (a, c, s) = folderCounts()
            item(R.drawable.ic_folder, T("Archive"), "$a texts") { showTextSection("archive") }
            item(R.drawable.ic_folder, T("Codes"), "$c texts") { showTextSection("codes") }
            item(R.drawable.ic_folder, T("Spam & blocked"), "$s texts") { showTextSection("spam") }
            divider()
        }
    } else {
        // Tellem screen: private chats
        item(R.drawable.ic_edit, T("New Message")) { showNewChat() }
        if (Texts.mixed(this) && Texts.isDefault(this)) item(R.drawable.ic_edit, T("New Text"), T("a normal text, not encrypted")) { showNewText() }   // texts sit among the chats
        item(R.drawable.ic_more, T("New Group")) { showNewGroup() }
        item(R.drawable.ic_search, T("Join Group"), T("with an invite link")) { joinGroupPrompt() }
        item(R.drawable.ic_folder, T("New Folder"), T("group chats, e.g. Family")) { newFolder() }
        item(R.drawable.ic_bookmark, T("Saved Messages"), T("notes to yourself, on this phone")) { showChat(Messenger.core.savedChat().user) }
        divider()
    }
    item(R.drawable.ic_menu, T("Settings")) { showSettings() }
    col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
    col.addView(tv(T("$APP_NAME ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")}"), 12f, faint).apply {
        setPadding(dp(22), dp(14), dp(22), dp(16))
    })
    // clickable: taps on empty parts of the sidebar stop here instead of falling through to the screen behind it
    return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(listBg); isClickable = true; addView(col, LinearLayout.LayoutParams(-1, -1)) }
}

internal fun MainActivity.safety(c: Contact) {
    val code = Messenger.core.safetyCode(c) ?: return
    val view = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(T("Compare this with ${c.name}'s screen, in person or on a call. If every number matches, nobody is listening in between."), 14.5f, dim))
        addView(gap(16))
        addView(tv(code.replace(" ", "  "), 21f, white, true).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE })
    }
    if (c.verified) popup(T("Safety code"), null, view, T("Mark not checked") to dim, "OK" to white) { i ->
        if (i == 0) { c.verified = false; Messenger.save(); fillChat() }
    } else popup(T("Safety code"), null, view, T("Not now") to dim, T("They match") to white) { i ->
        if (i == 1) { c.verified = true; Messenger.save(); fillChat() }
    }
}
