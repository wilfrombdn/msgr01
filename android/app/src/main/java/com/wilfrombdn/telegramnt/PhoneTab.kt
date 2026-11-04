package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView

// The Phone window: calls, contacts and the pencil button for each sub-tab.

/** Call history and contacts, read in the background (again when older than 15 s); redraws when they arrive. */
internal fun MainActivity.loadPhone() {
    if (System.currentTimeMillis() - phoneLoaded < 15_000 && phoneCalls != null) return
    phoneLoaded = System.currentTimeMillis()
    var calls: List<Phone.Call> = emptyList(); var people: List<Phone.Person> = emptyList()
    Messenger.run({ calls = Phone.calls(this); people = Phone.people(this); calls.mapNotNull { it.number }.forEach { Texts.name(this, it) } }, {
        val changed = calls.size != phoneCalls?.size || calls.firstOrNull()?.date != phoneCalls?.firstOrNull()?.date || people.size != phonePeople?.size
        phoneCalls = calls; phonePeople = people
        if (changed && screen == Screen.CHATS && textsTab) showChats()
    })
}

/** The round button of the Phone tab: new text, dial pad, or new contact on a tap (always the camera icon; hold to snap). */
internal fun MainActivity.phoneButton() = newTextButton().apply {
    when (phoneSub) {
        "calls" -> { setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_DIAL)) } } }
        "contacts" -> { setOnClickListener { showAddContact(null) { showChats() } } }
    }
}

internal fun MainActivity.permissionNote(text: String, perm: String, code: Int) =
    note(text) { requestPermissions(arrayOf(perm), code) }

/** Phone and Tellem calls in one list, newest first. Tap to call back the same way. */
internal fun MainActivity.addCallRows(list: LinearLayout, q: String) {
    loadPhone()
    if (!Phone.canReadCalls(this)) list.addView(permissionNote("Tap to show your phone's call history here. It's read on this phone only.", Manifest.permission.READ_CALL_LOG, CALL_LOG_PERM))
    val core = Messenger.core
    val tellem = synchronized(core) {
        core.contacts.flatMap { c -> c.msgs.mapNotNull { m -> m.call?.let { k ->
            val inc = k.optString("d") != "out"
            Phone.Call(m.ts, inc, inc && k.optString("s") != "ok", k.optInt("dur"), friend = c.user)
        } } }
    }
    val all = (tellem + (phoneCalls ?: emptyList())).sortedByDescending { it.date }.filter { k ->
        q.isEmpty() || callName(k).lowercase().contains(q) || k.number?.contains(q) == true
    }.take(300)
    for (k in all) list.addView(callRow(k))
    if (all.isEmpty() && Phone.canReadCalls(this)) list.addView(tv(if (q.isEmpty()) "No calls yet" else "No calls match \"$search\"", 15.5f, dim)
        .apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)) })
}

internal fun MainActivity.callName(k: Phone.Call) = k.friend?.let { Messenger.core.contact(it)?.name ?: "@$it" } ?: Texts.name(this, k.number ?: "")

internal fun MainActivity.callRow(k: Phone.Call): View {
    val name = callName(k)
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(8))
        background = pressable()
        setOnClickListener {
            k.friend?.let { u -> Messenger.core.contact(u)?.takeIf { it.canChat }?.let { callFriend(it) } ?: toast("Can't call them on $APP_NAME") }
                ?: k.number?.let { PhoneCalls.dial(this@callRow, it) }
        }
        setOnLongClickListener {
            val n = k.number
            if (n != null) longPressMenu(null, null, listOf(
                "Text" to { var id = 0L; Messenger.run({ id = Texts.threadFor(this@callRow, listOf(n)) }, { showTextThread(id, listOf(n)) }) },
                "Copy number" to { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", n)); toast("Copied") },
            )) else k.friend?.let { showChat(it) }
            true
        }
    }
    val friend = k.friend?.let { Messenger.core.contact(it) }
    row.addView(avatar(if (name.any(Char::isLetter)) name else "#", 48, friend?.avatar))
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    val l1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    l1.addView(tv(name, 16f, if (k.missed) red else white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    l1.addView(tv(if (k.friend != null) APP_NAME else "Phone", 10.5f, Color.WHITE, true).apply {
        setPadding(dp(5), dp(1), dp(5), dp(1)); background = round(if (k.friend != null) Color.parseColor("#3A6EA5") else smsGreen, 4)
    })
    l1.addView(tv(time(k.date), 13f, dim).apply { setPadding(dp(6), 0, 0, 0) })
    mid.addView(l1)
    val what = when {
        k.missed -> "↙ Missed"
        k.incoming -> "↙ Incoming"
        else -> "↗ Outgoing"
    } + if (k.seconds > 0) " · ${callDuration(k.seconds)}" else ""
    mid.addView(tv(what, 14f, if (k.missed) red else dim).apply { setPadding(0, dp(2), 0, 0) })
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(70) })
    }
}

/** The phone's contacts, A to Z; tap for a card with Call and Text. */
internal fun MainActivity.addContactRows(list: LinearLayout, q: String) {
    loadPhone()
    if (!Phone.canReadContacts(this)) return list.addView(permissionNote("Tap to show your phone's contacts here. They're read on this phone only and never sent anywhere.", Manifest.permission.READ_CONTACTS, CONTACTS_PERM))
    val people = (phonePeople ?: emptyList()).filter { p -> q.isEmpty() || p.name.lowercase().contains(q) || p.numbers.any { it.first.contains(q) } }
    var letter = ""
    var next = 0
    fun addUpTo(count: Int) {
        val end = minOf(people.size, next + count)
        while (next < end) {
            val p = people[next++]
            val l = p.name.take(1).uppercase().let { if (it.firstOrNull()?.isLetter() == true) it else "#" }
            if (l != letter) { letter = l; list.addView(tv(l, 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(4)) }) }
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = pressable()
                setOnClickListener { contactCard(p) }
                addView(avatar(p.name, 44))
                addView(tv(p.name, 16f, white).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }
    // A big address book used to be turned into thousands of rows before anything showed. Now the first screenful (plus enough to
    // reach where you were scrolled to) appears at once and the rest follows in small batches. A search still builds everything.
    val firstBatch = if (q.isEmpty()) maxOf(40, (chatsScrollY + resources.displayMetrics.heightPixels) / dp(61) + 20) else people.size
    addUpTo(firstBatch)
    if (next < people.size) {
        val rest = object : Runnable {
            override fun run() {
                if (!list.isAttachedToWindow || screen != Screen.CHATS || !textsTab) return   // the screen was rebuilt or left
                addUpTo(60)
                if (next < people.size) list.post(this)
            }
        }
        list.post(rest)
    }
    if (people.isEmpty() && phonePeople != null) list.addView(tv(if (q.isEmpty()) "No contacts with phone numbers" else "No contacts match \"$search\"", 15.5f, dim)
        .apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)) })
}

/** New contact, made right here (name, number, email) and saved to the phone's contacts. [number] pre-fills it. */
internal fun MainActivity.showAddContact(number: String?, back: () -> Unit) {
    screen = Screen.ADD_CONTACT
    addContactBack = back
    val first = field("First name").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val last = field("Last name").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val phone = field("Phone number", number.orEmpty()).apply { inputType = InputType.TYPE_CLASS_PHONE; addTextChangedListener(PhoneFormatWatcher()) }
    val mail = field("Email (optional)").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
    fun save() {
        if (first.text.isBlank() && last.text.isBlank()) return toast("Add a name")
        if (phone.text.isBlank() && mail.text.isBlank()) return toast("Add a phone number or email")
        if (!Phone.canWriteContacts(this)) {
            addContactSave = { save() }
            return requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), ADD_CONTACT_PERM)
        }
        var ok = false
        Messenger.run({ ok = Phone.addContact(this, first.text.toString().trim(), last.text.toString().trim(), phone.text.toString(), mail.text.toString()) }, {
            if (ok) {
                toast("Contact saved")
                Texts.forgetNames(); phoneLoaded = 0; phoneCalls = null
                Messenger.run({ Texts.threads.forEach { t -> t.addresses.forEach { Texts.name(this, it) } } }, { addContactBack() })
            } else toast("Couldn't save the contact")
        }) { toast("Couldn't save the contact") }
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
    listOf(first, last, phone, mail).forEach { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
    col.addView(cta("Save contact") { save() })
    setContentView(page(topBar("New contact", null, icon(R.drawable.ic_back) { back() }), ScrollView(this).apply { addView(col) }))
    first.post { first.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(first, 0) }
}

/** Name, each number, and Call / Text for each. */
internal fun MainActivity.contactCard(p: Phone.Person) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialog: android.app.Dialog? = null
    for ((n, label) in p.numbers) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(n, 16f, white))
            addView(tv(label, 13f, dim))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_call, white, 22) { PhoneCalls.dial(this@contactCard, n) })
        addView(icon(R.drawable.ic_send, smsGreen, 22) {
            var id = 0L
            Messenger.run({ id = Texts.threadFor(this@contactCard, listOf(n)) }, { showTextThread(id, listOf(n)) })
        })
    })
    popup(p.name, "Call or text (through your carrier, not private)", col, "Close" to dim)
}
