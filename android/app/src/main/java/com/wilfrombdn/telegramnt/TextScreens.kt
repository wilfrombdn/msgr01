package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.children

// Normal texts (SMS/MMS): the text list, a text conversation, new text, and the texting-app role.

/** Ask Android to make Tellem the texting app (Android shows its own "set as default" question). */
internal fun MainActivity.askCallRole() {
    val roleIntent = if (Build.VERSION.SDK_INT >= 29)
        getSystemService(android.app.role.RoleManager::class.java)?.takeIf { it.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER) }
            ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
    else null
    val i = roleIntent
        ?: @Suppress("DEPRECATION") Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
    @Suppress("DEPRECATION") startActivityForResult(i, CALL_ROLE)
}

internal fun MainActivity.callRoleResult() {
    if (PhoneCalls.isDefaultDialer(this)) {
        PhoneCalls.channels(this)
        toast("$APP_NAME now handles phone calls")
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS), CALL_PERM)
    }
    if (screen == Screen.SETTINGS) showSettings()
}

internal fun MainActivity.askTextRole() {
    val i = if (Build.VERSION.SDK_INT >= 29) getSystemService(android.app.role.RoleManager::class.java)
        .takeIf { it.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS) }?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
    else @Suppress("DEPRECATION") Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
    if (i == null) return toast("This phone can't send texts")
    @Suppress("DEPRECATION") startActivityForResult(i, TEXT_ROLE)
}

internal fun MainActivity.textRoleResult() {
    if (Texts.isDefault(this)) {
        Texts.listen(this, textsChanged)   // now allowed to read texts: start watching them
        // names instead of numbers, and this phone's own number (left out of group texts); both optional
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.READ_PHONE_STATE), TEXT_PERMS)
        toast("$APP_NAME is now your texting app")
    } else popup("$APP_NAME isn't your texting app",
        "If Android didn't ask, it may be blocking this for apps installed outside the Play Store. Open $APP_NAME's App info, tap ⋮ (top right), choose \"Allow restricted settings\", then try again.",
        null, "Cancel" to dim, "Open App info" to white) { b ->
        if (b == 1) startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }
    rebuildTextScreen()
}

/** The texting-app state decides the bottom of a text chat (message box or "make Tellem…"), so rebuild it. */
internal fun MainActivity.rebuildTextScreen() = when (screen) {
    Screen.SETTINGS -> showSettings()
    Screen.TEXT_THREAD -> showTextThread(textThread, textPeople.ifEmpty { null })
    else -> redraw()
}

internal fun MainActivity.textsUnread() = Texts.threads.filter { !it.spam }.sumOf { it.unread }

/** Chats | Phone under the top bar; Phone has its own row: Texts · Calls · Contacts (carrier things, not private). */
internal fun MainActivity.textTabs(chatsUnread: Int, mixed: Boolean): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(bar)
    fun row() = LinearLayout(context)
    fun LinearLayout.tab(label: String, n: Int, on: Boolean, size: Float, go: () -> Unit) = addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable()
        setOnClickListener { go() }
        addView(tv(label + if (n > 0) "  $n" else "", size, if (on) white else dim, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(if (on) white else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, dp(2)))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (textsTab) {
        val sub = row().apply { setBackgroundColor(listBg) }
        fun go(k: String) { if (phoneSub != k) { val o = phoneTabOrder(); gotoPhoneTab(k, if (o.indexOf(k) > o.indexOf(phoneSub)) 1 else -1) } }
        sub.tab("Contacts", 0, phoneSub == "contacts", 14f) { go("contacts") }
        sub.tab("Calls", 0, phoneSub == "calls", 14f) { go("calls") }
        if (!mixed) sub.tab("Texts", textsUnread(), phoneSub == "texts", 14f) { go("texts") }
        if (folderPlace() == "tab") sub.tab("Folders", 0, phoneSub == "folders", 14f) { go("folders") }
        addView(sub)
    }
}

internal fun MainActivity.textMatches(t: Texts.Thread, q: String) = q.isEmpty() || Texts.title(this, t.addresses).lowercase().contains(q) ||
    t.addresses.any { it.contains(q) } || t.snippet.lowercase().contains(q)

/** The text threads, newest first (the Texts tab and the signed-out texts screen). */
internal fun MainActivity.addTextRows(list: LinearLayout, q: String) {
    if (!Texts.isDefault(this)) list.addView(note("$APP_NAME isn't your texting app right now, so texts can't be sent or received here. Tap to change that.") { askTextRole() })
    val pins = Texts.pins(this)
    val ts = Texts.threads.filter { !it.spam && ((!it.code && !it.archived) || q.isNotEmpty()) && textMatches(it, q) }.sortedByDescending { it.id in pins }   // pinned first, then newest (list is already newest first)
    if (q.isEmpty() && folderPlace() == "top") folderRows(list)
    if (!savedTellemOnly() && !Texts.mixed(this)) savedWithMessages()?.takeIf { q.isEmpty() || "saved messages".contains(q) }?.let { list.addView(chatRow(it)) }
    addBatched(list, ts, 72, q.isNotEmpty() || folderPlace() == "bottom") { t -> textRow(t, pins) }
    if (q.isEmpty() && folderPlace() == "bottom") folderRows(list)
    if (ts.isEmpty()) list.addView(tv(if (q.isNotEmpty()) "No texts match \"$search\"" else "No texts yet.\n\nTap the pencil to text a phone number.", 15.5f, dim)
        .apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)); setLineSpacing(0f, 1.2f) })
}

internal fun MainActivity.textRow(t: Texts.Thread, pins: Set<Long> = Texts.pins(this)): View {
    val title = Texts.threadTitle(this, t.id, t.addresses)
    val pinned = t.id in pins
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(listBg)
        setPadding(dp(10), dp(8), dp(14), dp(8))
        setOnClickListener { showTextThread(t.id) }
        setOnLongClickListener { v -> dropdown(v, Gravity.END, (if (pinned) "Unpin" else "Pin to top") to { Texts.setPinned(this@textRow, t.id, !pinned) },
            (if (t.archived) "Unarchive" else "Archive") to { Texts.setArchived(this@textRow, t.id, !t.archived); toast(if (t.archived) "Moved back to your texts" else "Archived") },
            (if (t.spam) "Not spam" else "Move to spam") to { Texts.markSpam(this@textRow, t.id, !t.spam); toast(if (t.spam) "Moved back to your texts" else "Moved to spam") }); true }
    }
    row.addView(avatar(if (title.any(Char::isLetter)) title else "#", 54, null, numberSeed(t.addresses.joinToString(","))))   // a bare number gets "#", not "+5"
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    val line1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    line1.addView(tv(title, 16.5f, white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    if (pinned) line1.addView(pinMark())
    if (Texts.mixed(this)) line1.addView(tv("SMS", 10.5f, Color.WHITE, true).apply { setPadding(dp(5), dp(1), dp(5), dp(1)); background = round(smsGreen, 4) })   // only needed when texts sit among the chats
    line1.addView(tv(time(t.date), 13f, dim).apply { setPadding(dp(6), 0, 0, 0) })
    mid.addView(line1)
    val line2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, 0) }
    line2.addView(tv(t.snippet.replace('\n', ' '), 15f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    if (t.unread > 0) line2.addView(tv("${t.unread}", 12.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; minWidth = dp(22); setPadding(dp(6), dp(1), dp(6), dp(1)); background = round(smsGreen, 11)
    })
    mid.addView(line2)
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    val swipe = SwipeRow(this, row, R.drawable.ic_trash, onOpen = { showTextThread(t.id) }, onDelete = {
        popup("Delete texts with $title?", "This deletes these texts from the phone for good.", null, "Cancel" to dim, "Delete" to red) { i ->
            if (i == 1) Texts.deleteThread(this, t.id) else redraw()
        }
    }, onPin = {
        when { t.spam -> { Texts.markSpam(this, t.id, false); toast("Moved back to your texts") }
            t.archived -> { Texts.setArchived(this, t.id, false); toast("Moved back to your texts") }
            else -> { Texts.setArchived(this, t.id, true); toast("Archived") } }
    }, pinLabel = if (t.spam) "Not spam" else if (t.archived) "Unarchive" else "Archive", directRight = true, pinEmoji = "✉️",
        holdToSwipe = textsTab && screen == Screen.CHATS)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(swipe, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(76) })
    }
}

/** A folder row at the end of the texts ("Codes · 3"), or null if it's empty. */
internal fun MainActivity.sectionRow(icon: String, label: String, n: Int, open: () -> Unit): View? {
    // always shown, even when empty, so you know where it is
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(16), dp(16), dp(16))
        background = pressable()
        setOnClickListener { open() }
        addView(tv(icon, 18f, dim).apply { setPadding(0, 0, dp(18), 0) })
        addView(tv(label, 16f, dim), LinearLayout.LayoutParams(0, -2, 1f))
        if (n > 0) addView(tv("$n", 14f, dim))
    }
}

/** "archive", "codes" or "spam". Nothing is deleted unless you clear it. */
internal fun MainActivity.showTextSection(kind: String) {
    screen = Screen.TEXT_SPAM
    textSectionKind = kind
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(settingNote(when (kind) {
        "codes" -> "Texts with a one-time code, like logins and two-factor. They stay out of your normal texts. Decided on your phone; nothing is sent anywhere."
        "archive" -> "Texts you put away. Open one to read it, or tap Unarchive to bring it back."
        else -> "Texts from numbers that aren't in your contacts and have a link or typical scam wording, plus numbers you blocked. They arrive silently. Tap Not spam to bring one back, or clear them all. Decided on your phone; nothing is sent anywhere."
    }).apply { setPadding(dp(20), dp(14), dp(20), dp(10)) })
    val ts = Texts.threads.filter { when (kind) { "codes" -> it.code; "archive" -> it.archived; else -> it.spam } }
    for (t in ts) {
        list.addView(textRow(t))
        if (kind != "codes") list.addView(tv(if (kind == "archive") "UNARCHIVE" else "NOT SPAM", 13f, blue, true).apply {
            setPadding(dp(76), dp(2), dp(16), dp(12))
            setOnClickListener {
                if (kind == "archive") Texts.setArchived(this@showTextSection, t.id, false) else Texts.markSpam(this@showTextSection, t.id, false)
                toast("Moved back to your texts"); showTextSection(kind)
            }
        })
    }
    if (ts.isEmpty()) list.addView(tv(when (kind) { "codes" -> "No codes"; "archive" -> "Nothing archived"; else -> "No spam" }, 15.5f, dim).apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(48), dp(32), dp(32)) })
    val clear = if (ts.isEmpty() || kind == "archive") null else icon(R.drawable.ic_trash) {
        popup(if (kind == "codes") "Delete all ${ts.size} code conversations?" else "Clear all ${ts.size} spam conversations?",
            "This deletes these texts from the phone for good. To keep one, cancel and tap Not spam on it first.", null, "Cancel" to dim, "Delete all" to red) { b ->
            if (b == 1) { ts.forEach { Texts.deleteThread(this, it.id) }; showTextSection(kind) }
        }
    }
    setContentView(page(topBar(when (kind) { "codes" -> "Codes"; "archive" -> "Archive"; else -> "Spam & blocked" }, null, icon(R.drawable.ic_back) { leaveTexts() }, *listOfNotNull(clear).toTypedArray()), ScrollView(this).apply { addView(list) }))
}

/** Everyone in a group text on one scrolling line; tap it for the full list. */
internal fun MainActivity.peopleStrip(people: List<String>): View = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    setBackgroundColor(bar)
    val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(8)) }
    people.forEach { n -> row.addView(tv(Texts.name(this@peopleStrip, n), 13f, dim).apply {
        setPadding(dp(10), dp(3), dp(10), dp(3)); background = round(listBg, 10); maxLines = 1
    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }) }
    row.addView(tv("all ›", 13f, blue, true).apply { setPadding(dp(6), dp(3), dp(6), dp(3)) })
    addView(row)
    val showAll = View.OnClickListener {
        popup("${people.size} people", people.joinToString("\n") { n -> Texts.name(this@peopleStrip, n).let { nm -> if (nm == n) n else "$nm · $n" } }, null, "Close" to white)
    }
    row.setOnClickListener(showAll)
    row.children.forEach { it.setOnClickListener(showAll) }
}

/** Block / unblock, spam / not spam, delete: the ⋮ menu of a text conversation. */
internal fun MainActivity.textThreadMenu(anchor: View) {
    val id = textThread; val people = textPeople
    val t = Texts.threads.firstOrNull { it.id == id }
    val items = ArrayList<Pair<String, () -> Unit>>()
    val pinned = id in Texts.pins(this)
    items += (if (t?.archived == true) "Unarchive" else "Archive") to { Texts.setArchived(this, id, t?.archived != true); toast(if (t?.archived == true) "Moved back to your texts" else "Archived"); if (t?.archived != true) leaveTexts() }
    items += (if (pinned) "Unpin from top" else "Pin to top") to { Texts.setPinned(this, id, !pinned); toast(if (pinned) "Unpinned" else "Pinned to the top of your texts") }
    people.singleOrNull()?.takeIf { Texts.contact(this, it) == null }?.let { n ->
        items += "Add to contacts" to { val back = textThread; showAddContact(n) { showTextThread(back, people) } }

    }
    // someone already in your contacts: change their name, numbers, email or photo (the same screen as the Contacts tab)
    people.singleOrNull()?.takeIf { Texts.contact(this, it) != null }?.let { n ->
        items += "Edit contact" to {
            val back = textThread
            var match: Phone.Person? = null
            Messenger.run({
                match = Phone.people(this).firstOrNull { p -> p.numbers.any { android.telephony.PhoneNumberUtils.compare(it.first, n) } }
            }, {
                match?.let { p -> showEditContact(p) { showTextThread(back, people) } } ?: toast("Couldn't find that contact")
            }) { toast("Couldn't find that contact") }
        }
    }
    if (people.size > 1) items += "Name this group" to {
        val box = field("Group name", Texts.groupName(this, id).orEmpty())
        popup("Name this group", "Only you see this name. Normal group texts can't share one with the others.", box, "Cancel" to dim, "Save" to white) { b ->
            if (b == 1) { Texts.setGroupName(this, id, box.text.toString().take(60)); showTextThread(id, people) }
        }
    }
    if (people.isNotEmpty()) items += if (t?.blocked == true) "Unblock" to { Texts.block(this, people, false); toast("Unblocked") }
        else "Block number" to {
            popup("Block ${Texts.title(this, people)}?", "Android will stop their texts and calls from reaching this phone (in every app). You can unblock them later from here.",
                null, "Cancel" to dim, "Block" to red) { b -> if (b == 1) { Texts.block(this, people, true); Texts.markSpam(this, id, true); leaveTexts() } }
        }
    items += if (t?.spam == true) "Not spam" to { Texts.markSpam(this, id, false); toast("Moved back to your texts") }
        else "Move to spam" to { Texts.markSpam(this, id, true); leaveTexts() }
    items += "Delete conversation" to {
        popup("Delete this conversation?", "This deletes these texts from the phone for good.", null, "Cancel" to dim, "Delete" to red) { b ->
            if (b == 1) { Texts.deleteThread(this, id); leaveTexts() }
        }
    }
    dropdown(anchor, Gravity.END, *items.toTypedArray())
}

/** Signed out but Tellem is the texting app: texts still have to be reachable. */
internal fun MainActivity.showTextList() {
    screen = Screen.TEXT_LIST
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    addTextRows(list, "")
    val frame = FrameLayout(this)
    frame.addView(ScrollView(this).apply { addView(list); isFillViewport = true })
    frame.addView(newTextButton(), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
    setContentView(page(topBar("Texts", "Normal texts · not encrypted", icon(R.drawable.ic_back) { showWelcome() }), frame))
}

internal fun MainActivity.newTextButton() = fab(R.drawable.ic_edit) { showNewText() }

internal fun MainActivity.leaveTexts() {
    if (screen == Screen.TEXT_THREAD) textScroll?.let { s -> if (atBottom(s)) textSpots.remove(textThread) else textSpots[textThread] = s.scrollY }
    Texts.openThread = 0
    if (Messenger.signedIn) { textsTab = !Texts.mixed(this); phoneSub = "texts"; showChats() } else showTextList()
}

/** "To:" numbers (or pick a contact), then the thread. */
internal fun MainActivity.showNewText(body: String? = null) {
    screen = Screen.NEW_TEXT
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
    val to = field("Phone number(s), separated by commas").apply { inputType = InputType.TYPE_CLASS_PHONE; addTextChangedListener(PhoneFormatWatcher()) }
    newTextTo = to
    col.addView(to)
    col.addView(tv("Choose from contacts", 16f, blue, true).apply {
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener {
            runCatching { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK_CONTACT) }
                .onFailure { toast("No contacts app") }
        }
    })
    col.addView(settingNote("Normal texts go through your phone carrier and aren't encrypted. For private messages, use a $APP_NAME chat.").apply { setPadding(0, dp(4), 0, dp(16)) })
    col.addView(cta("Next") {
        val nums = to.text.split(',', ';').map { it.trim() }.filter { it.any(Char::isDigit) || it.contains('@') }.map { if (it.contains('@')) it else it.filter { c -> c.isDigit() || c == '+' } }   // typed formatting is only for show
        if (nums.isEmpty()) return@cta toast("Type a phone number")
        var id = 0L
        Messenger.run({ id = Texts.threadFor(this, nums) }, { textDraft = body; showTextThread(id, nums) }) { toast("Couldn't start that text") }
    }, ctaParams())
    setContentView(page(topBar("New text", null, icon(R.drawable.ic_back) { leaveTexts() }), ScrollView(this).apply { addView(col) }))
    to.requestFocus()
}

internal fun MainActivity.contactPicked(uri: android.net.Uri) {
    val n = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: return toast("Couldn't read that contact")
    newTextTo?.let { f -> val cur = f.text.toString().trim().trimEnd(','); f.setText(if (cur.isEmpty()) n else "$cur, $n"); f.setSelection(f.text.length) }
}

/** "Text this number" from the dialer, contacts or an sms: link. */
internal fun MainActivity.openTextIntent(i: Intent) {
    val data = i.data ?: return
    val nums = data.schemeSpecificPart.orEmpty().substringBefore('?').split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT)
        ?: data.schemeSpecificPart?.substringAfter("body=", "")?.takeIf { it.isNotEmpty() }
    if (!Texts.isDefault(this)) return popup("Use $APP_NAME for texts?", "To send normal texts from $APP_NAME, make it this phone's texting app.", null, "Cancel" to dim, "Set up" to white) { b -> if (b == 1) askTextRole() }
    if (nums.isEmpty()) return showNewText(body)
    var id = 0L
    Messenger.run({ id = Texts.threadFor(this, nums) }, { textDraft = body; showTextThread(id, nums) })
}

internal fun MainActivity.showTextThread(id: Long, people: List<String>? = null) {
    screen = Screen.TEXT_THREAD
    textMadeDefault = Texts.isDefault(this)
    textThread = id
    Texts.openThread = id
    textPeople = people ?: Texts.threads.firstOrNull { it.id == id }?.addresses ?: emptyList()
    textImage = null
    val title = Texts.threadTitle(this, id, textPeople)
    val group = textPeople.size > 1
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        isClickable = true; setOnClickListener { dismissKeyboard() }
    }
    textList = list
    textKeys = emptyList(); textViews.clear()
    val sc = chatScroller(list); textScroll = sc
    val call = textPeople.singleOrNull()?.let { n -> icon(R.drawable.ic_call) { PhoneCalls.dial(this@showTextThread, n) } }
    val more = icon(R.drawable.ic_more).also { v -> v.setOnClickListener { textThreadMenu(v) } }
    val top = topBar(title, if (group) "Group text · ${textPeople.size} people · not encrypted" else "Text message · not encrypted",
        icon(R.drawable.ic_back) { leaveTexts() }, *listOfNotNull(call, more).toTypedArray())
    val info = Texts.threads.firstOrNull { it.id == id }
    val spamBar: View? = if (info?.spam != true) null else LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bar)
        setPadding(dp(16), dp(8), dp(8), dp(8))
        addView(tv(if (info.blocked) "🚫 This number is blocked" else "⚠ This looks like spam. Don't tap links or reply with personal info.", 13.5f, amber), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv(if (info.blocked) "UNBLOCK" else "NOT SPAM", 13.5f, blue, true).apply {
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener {
                if (info.blocked) Texts.block(this@showTextThread, textPeople, false)
                Texts.markSpam(this@showTextThread, id, false)
                visibility = View.GONE; (parent as View).visibility = View.GONE
            }
        })
    }
    val bottom: View = if (!textMadeDefault) tv("Make $APP_NAME your texting app to send", 16f, blue, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(listBg)
        setOnClickListener { askTextRole() }
    } else LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(listBg)
        val picBar = tv("", 14f, white).apply {
            visibility = View.GONE; setPadding(dp(16), dp(8), dp(16), dp(4))
            setOnClickListener { textImage = null; visibility = View.GONE }
        }
        textImageBar = picBar
        addView(picBar)
        val input = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(4), dp(6), dp(4)) }
        val box = EditText(context).apply {
            hint = "Text message · not encrypted"
            setHintTextColor(dim); setTextColor(white); textSize = look.msg; background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            setText(textDraft ?: ""); textDraft = null
        }
        fun send() {
            val body = box.text.toString().trim()
            val pic = textImage
            if (body.isEmpty() && pic == null || textPeople.isEmpty()) return
            box.setText(""); textImage = null; picBar.visibility = View.GONE
            stick = true   // your own text always brings you to the bottom
            Texts.send(this@showTextThread, textPeople, body, pic)
        }
        input.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        var attachBtn: View? = null
        attachBtn = icon(R.drawable.ic_attach, dim, pad = 10) { attachBtn?.let { textAttachMenu(it) } }
        input.addView(attachBtn)
        input.addView(icon(R.drawable.ic_send, smsGreen, pad = 10) { send() })
        addView(input)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = wallBg()
        fitsSystemWindows = true
        addView(callBarView(), LinearLayout.LayoutParams(-1, -2))
        addView(top, LinearLayout.LayoutParams(-1, -2))
        spamBar?.let { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        if (group) addView(peopleStrip(textPeople), LinearLayout.LayoutParams(-1, -2))
        addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }
    setContentView(root)
    stick = true
    textRestoreY = textSpots[id] ?: -1
    if (textRestoreY >= 0) stick = false
    fillTextThread()
    if (textPeople.isEmpty()) {   // opened from a notification before the thread list loaded
        var found: List<String> = emptyList()
        Messenger.run({ found = Texts.addressesOf(this, id) }, { if (found.isNotEmpty() && screen == Screen.TEXT_THREAD && textThread == id) showTextThread(id, found) })
    }
}

internal fun MainActivity.fillTextThread() {
    val id = textThread
    var msgs: List<Texts.Text> = emptyList()
    Messenger.run({
        msgs = Texts.messages(this, id)
        msgs.mapNotNull { it.from }.forEach { Texts.name(this, it) }   // look names up here, not while drawing
    }, {
        val list = textList ?: return@run
        if (screen != Screen.TEXT_THREAD || textThread != id) return@run
        if (Texts.threads.firstOrNull { it.id == id }?.unread ?: 1 > 0) Texts.markRead(this, id)
        // reuse bubbles that haven't changed: redrawing them all made the chat jump on every send
        val keys = msgs.map { "${if (it.mms) "m" else "s"}${it.id}:${it.state}:${it.attachments.size}:${it.body.length}" }
        if (keys == textKeys && list.childCount > 0) return@run
        textKeys = keys
        val old = HashMap(textViews); textViews.clear()
        val want = msgs.zip(keys).map { (t, k) -> (old[k] ?: textBubble(t, textPeople.size > 1)).also { textViews[k] = it } }
        val keep = want.toHashSet()
        for (i in list.childCount - 1 downTo 0) if (list.getChildAt(i) !in keep) list.removeViewAt(i)   // only what changed
        want.forEachIndexed { i, v -> if (list.getChildAt(i) !== v) { (v.parent as? ViewGroup)?.removeView(v); list.addView(v, i) } }
        if (msgs.isEmpty()) list.addView(tv("Texts go through your phone carrier, not Tor, and aren't encrypted.", 13.5f, dim).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) })
        // the scroller keeps to the bottom while you're there (see chatScroller)
        if (textRestoreY >= 0) { val y = textRestoreY; textRestoreY = -1; textScroll?.let { s -> s.post { s.scrollTo(0, y); stick = atBottom(s) } } }
    })
}

internal fun MainActivity.textBubble(t: Texts.Text, group: Boolean): View {
    val wrap = LinearLayout(this).apply { gravity = if (t.mine) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4)) }
    val fill = if (t.mine) look.sms else look.inn
    val ink = Look.textOn(fill)
    val b = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(fill, if (look.square) 0 else 14)
        setPadding(dp(10), dp(6), dp(10), dp(5))
    }
    if (group && !t.mine && t.from != null) b.addView(tv(Texts.name(this, t.from), 13.5f, avatarColors[Math.floorMod(t.from.hashCode(), avatarColors.size)], true))
    for (a in t.attachments) b.addView(textPicture(a))
    if (t.state == Texts.DOWNLOADING) b.addView(tv("📷 Downloading picture message…", look.msg, ink))
    if (t.state == Texts.NOT_DOWNLOADED) b.addView(tv("📷 Picture message didn't download. Tap to try again.", look.msg, ink).apply {
        setOnClickListener { Texts.retryDownload(this@textBubble, t.id); toast("Downloading…") }
    })
    if (t.body.isNotEmpty()) b.addView(tv(t.body, look.msg, ink).apply {
        markMessageText()
        android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.PHONE_NUMBERS)
        setLinkTextColor(ink)
        setOnLongClickListener { textMenu(t); true }
    })
    val meta = (ink and 0x00FFFFFF) or (0x99 shl 24)
    b.addView(LinearLayout(this).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(tv(time(t.date), 12f, meta))
        when (t.state) {
            Texts.SENDING -> addView(tv("  🕓", 11f, meta))
            Texts.FAILED -> addView(tv("  not sent · tap to retry", 12f, if (t.mine) Color.WHITE else red, true).apply {
                setOnClickListener {
                    val people = textPeople
                    var pic: Pair<ByteArray, String>? = null
                    Messenger.run({
                        pic = t.attachments.firstOrNull { it.mime.startsWith("image/") }?.let { a -> contentResolver.openInputStream(a.uri)?.use { it.readBytes() }?.let { it to a.mime } }
                    }, {
                        if (t.body.isEmpty() && pic == null) return@run toast("Couldn't resend that one")
                        Texts.deleteText(this@textBubble, t)
                        Texts.send(this@textBubble, people, t.body, pic)
                    })
                }
            })
        }
    }, LinearLayout.LayoutParams(-1, -2))
    b.setOnLongClickListener { textMenu(t); true }
    wrap.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (t.mine) leftMargin = dp(56) else rightMargin = dp(56) })
    return wrap
}

internal fun MainActivity.textMenu(t: Texts.Text) = longPressMenu(null, null, listOfNotNull(
    t.body.takeIf { it.isNotEmpty() }?.let { body -> "Copy" to { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", body)); toast("Copied") } },
    if (Messenger.signedIn && (t.body.isNotEmpty() || t.attachments.any { it.mime.startsWith("image/") })) "Save" to { saveText(t) } else null,
    "Delete" to { Texts.deleteText(this, t) },
))

/** A picture inside a picture text, loaded off the main thread; tap to see it big. */
internal fun MainActivity.textPicture(a: Texts.Attachment): View {
    val iv = ImageView(this).apply {
        adjustViewBounds = true; maxHeight = dp(320); maxWidth = dp(260); minimumHeight = dp(80); minimumWidth = dp(120)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    if (!a.mime.startsWith("image/")) return tv(if (a.mime.startsWith("video/")) "🎬 Video" else "🎵 Audio", look.msg, white).apply {
        setOnClickListener { toast("Open this one in your old texting app") }
    }
    val key = a.uri.toString()
    textPics.get(key)?.let { iv.setImageBitmap(it) } ?: run {
        var bmp: android.graphics.Bitmap? = null
        Messenger.run({ bmp = contentResolver.openInputStream(a.uri)?.use { it.readBytes() }?.let { decodeSmall(it, 720) } },
            { bmp?.let { textPics.put(key, it); iv.setImageBitmap(it) } })
    }
    iv.setOnClickListener {
        var big: android.graphics.Bitmap? = null
        Messenger.run({ big = contentResolver.openInputStream(a.uri)?.use { it.readBytes() }?.let { decodeSmall(it, 2048) } }, {
            big?.let { bm ->
                val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.setContentView(ImageView(this).apply { setImageBitmap(bm); setBackgroundColor(Color.BLACK); setOnClickListener { d.dismiss() } })
                d.show()
            }
        })
    }
    return iv
}

/** The paperclip in a text conversation: take a picture with the in-app camera, or pick one from the phone. */
internal fun MainActivity.textAttachMenu(anchor: View) {
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
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, CameraActivity::class.java).putExtra("photoOnly", true), TEXT_CAMERA)
    }
    row(R.drawable.ic_folder, "Photo") {
        runCatching { @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), TEXT_IMAGE) }
    }
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    pw.isOutsideTouchable = true
    panel.measure(View.MeasureSpec.makeMeasureSpec(dp(190), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    pw.showAsDropDown(anchor, -dp(130), -(anchor.height + panel.measuredHeight + dp(6)))   // opens upward, just above the clip
}

/** A photo chosen for a picture text, shrunk to fit the carrier's size limit. [fromCamera]: the in-app camera's temporary file is deleted once read. */
internal fun MainActivity.textImagePicked(uri: android.net.Uri, fromCamera: Boolean = false) {
    val limit = (Texts.mmsLimit(this) * 0.9).toInt()
    var out: ByteArray? = null
    Messenger.run({
        val bmp = loadPhoto(uri)
        if (fromCamera) runCatching { contentResolver.delete(uri, null, null) }   // the unencrypted temp copy has been read into memory
        if (bmp == null) return@run
        var side = 1600; var q = 85
        var b = jpeg(bmp, side, q)
        while (b.size > limit && side > 320) { if (q > 60) q -= 10 else side = side * 3 / 4; b = jpeg(bmp, side, q) }
        out = b
    }, {
        val b = out ?: return@run toast("Couldn't open that photo")
        textImage = b to "image/jpeg"
        textImageBar?.apply { text = "📷 Picture attached (${b.size / 1024} KB) · tap to remove"; visibility = View.VISIBLE }
    }) { toast("Couldn't open that photo") }
}
