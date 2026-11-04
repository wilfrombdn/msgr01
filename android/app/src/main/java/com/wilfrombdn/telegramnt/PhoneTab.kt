package com.wilfrombdn.telegramnt

import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import java.util.Locale
import java.util.Date
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
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

/** The round button of the Phone tab: new text, dial pad, or new contact on a tap (pencil, phone or plus; hold to snap). */
internal fun MainActivity.phoneButton() = when (phoneSub) {
    "calls" -> fab(R.drawable.ic_call) { runCatching { startActivity(Intent(Intent.ACTION_DIAL)) } }
    "contacts" -> fab(R.drawable.ic_plus) { showAddContact(null) { showChats() } }
    else -> newTextButton()
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
    addBatched(list, all, 66, q.isNotEmpty()) { k -> callRow(k) }
    if (all.isEmpty() && Phone.canReadCalls(this)) list.addView(tv(if (q.isEmpty()) "No calls yet" else "No calls match \"$search\"", 15.5f, dim)
        .apply { gravity = Gravity.CENTER; setPadding(dp(32), dp(64), dp(32), dp(32)) })
}

/** A call's length in words: "3 min 41 sec", "52 sec", "1 hr 4 min". */
internal fun lengthWords(sec: Int): String = when {
    sec >= 3600 -> "${sec / 3600} hr ${sec % 3600 / 60} min"
    sec >= 60 -> "${sec / 60} min ${sec % 60} sec"
    else -> "$sec sec"
}

internal fun MainActivity.callName(k: Phone.Call) = k.friend?.let { Messenger.core.contact(it)?.name ?: "@$it" } ?: Texts.name(this, k.number ?: "")

/** The opened-out part of the call that is open now (only one at a time). */
private var openDetail: java.lang.ref.WeakReference<View>? = null

/**
 * One line of the call log: name, tag, time and what kind of call. Tap to open it downwards with the details and buttons
 * (Call, Text, Contact, Copy); tap again to close it. Holding it still gives the old menu.
 */
internal fun MainActivity.callRow(k: Phone.Call): View {
    val name = callName(k)
    val friend = k.friend?.let { Messenger.core.contact(it) }
    val number = k.number
    val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (openCallAt == k.date) View.VISIBLE else View.GONE }
    val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (openCallAt == k.date) openDetail = java.lang.ref.WeakReference<View>(detail)
    val row = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(16), dp(12))
        background = if (openCallAt == k.date) android.graphics.drawable.ColorDrawable(menuBg) else pressable()   // the open call gets the shaded band
        setOnClickListener {
            val open = detail.visibility != View.VISIBLE
            (parent as? ViewGroup)?.let { p -> (p.parent as? ViewGroup ?: p).let { root ->
                if (animationsOn()) android.transition.TransitionManager.beginDelayedTransition(root, android.transition.AutoTransition().setDuration(260).setInterpolator(SMOOTH))
            } }
            openDetail?.get()?.takeIf { it !== detail }?.let { other ->   // only one call open at a time
                other.visibility = View.GONE; (other.tag as? View)?.background = pressable()
            }
            detail.visibility = if (open) View.VISIBLE else View.GONE
            background = if (open) android.graphics.drawable.ColorDrawable(menuBg) else pressable()
            openDetail = if (open) java.lang.ref.WeakReference<View>(detail) else null
            openCallAt = if (open) k.date else 0L
        }
        setOnLongClickListener {
            if (number != null) longPressMenu(null, null, listOf(
                "Text" to { var id = 0L; Messenger.run({ id = Texts.threadFor(this@callRow, listOf(number)) }, { showTextThread(id, listOf(number)) }) },
                "Copy number" to { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", number)); toast("Copied") },
            )) else k.friend?.let { showChat(it) }
            true
        }
    }
    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val l1 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    l1.addView(tv(name, 16f, if (k.missed) red else white, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    // a thin outlined tag: white-ish for Tellem calls, the texting colour for normal phone calls
    l1.addView(if (k.friend != null) tagChip(APP_NAME.uppercase(), soft, faint, 10f) else tagChip("PHONE", look.sms, (look.sms and 0x00FFFFFF) or (0x8C shl 24), 10f))
    l1.addView(tv(time(k.date), 13f, dim).apply { setPadding(dp(6), 0, 0, 0) })
    mid.addView(l1)
    val what = when {
        k.missed -> "↙ Missed"
        k.incoming -> "↙ Incoming"
        else -> "↗ Outgoing"
    } + if (k.seconds > 0) " · ${callDuration(k.seconds)}" else ""
    mid.addView(tv(what, 14f, if (k.missed) red else dim).apply { setPadding(0, dp(2), 0, 0) })
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))

    // ---- the part that opens (B2): short labelled facts, then squared tiles ----
    detail.tag = row
    detail.background = android.graphics.drawable.LayerDrawable(arrayOf(
        android.graphics.drawable.ColorDrawable(divider), android.graphics.drawable.ColorDrawable(sunk))).apply { setLayerInset(1, 0, 1, 0, 1) }
    detail.setPadding(0, dp(1), 0, dp(1))
    val facts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(11), dp(16), dp(2)) }
    fun fact(label: String, value: CharSequence) = facts.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
        addView(tv(label.uppercase(), 11f, dim, true).apply { letterSpacing = 0.08f }, LinearLayout.LayoutParams(dp(68), -2))
        addView(tv("", 14f, white).apply { text = value }, LinearLayout.LayoutParams(0, -2, 1f))
    })
    fun faded(main: String, extra: String) = android.text.SpannableString("$main$extra").apply {
        setSpan(android.text.style.ForegroundColorSpan(dim), main.length, length, 0)
        setSpan(android.text.style.RelativeSizeSpan(0.93f), main.length, length, 0)
    }
    fact("When", java.text.SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(k.date)))
    fact("Length", if (k.seconds > 0) lengthWords(k.seconds) else if (k.missed) "Not answered" else "Not connected")
    fact("Type", if (k.friend != null) faded("@${k.friend}", " · end-to-end encrypted") else faded(number ?: "Unknown number", " · not encrypted"))
    detail.addView(facts)
    val tiles = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(14)) }
    fun tile(res: Int, label: String, main: Boolean = false, go: () -> Unit) = tiles.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(7)); isClickable = true
        background = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(if (main) white else chip); cornerRadius = dp(3).toFloat() }, pressable()))
        val c = if (main) ink else white
        addView(android.widget.ImageView(context).apply { setImageResource(res); setColorFilter(c) }, LinearLayout.LayoutParams(dp(19), dp(19)))
        addView(tv(label, 12.5f, c).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; gravity = Gravity.CENTER })
        setOnClickListener { tapBuzz(); go() }
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    if (friend != null) {
        tile(R.drawable.ic_call, "Call") { if (friend.canChat) callFriend(friend) else toast("Can't call them on $APP_NAME") }
        tile(R.drawable.ic_send, "Message", main = true) { showChat(friend.user) }
    } else if (number != null) {
        tile(R.drawable.ic_call, "Call") { PhoneCalls.dial(this, number) }
        tile(R.drawable.ic_send, "Text", main = true) { var id = 0L; Messenger.run({ id = Texts.threadFor(this, listOf(number)) }, { showTextThread(id, listOf(number)) }) }
        tile(R.drawable.ic_person, "Contact") { callCard(number) }
        tile(R.drawable.ic_copy, "Copy") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", number)); toast("Copied") }
    }
    detail.addView(tiles)

    outer.addView(row, LinearLayout.LayoutParams(-1, -2))
    outer.addView(detail, LinearLayout.LayoutParams(-1, -2))
    outer.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(18) })
    return outer
}

/** The phone's contacts, in the order chosen with the sort button (names A to Z unless changed), optionally grouped by company; tap for a card with Edit, Call and Text. */
internal fun MainActivity.addContactRows(list: LinearLayout, q: String) {
    loadPhone()
    if (!Phone.canReadContacts(this)) return list.addView(permissionNote("Tap to show your phone's contacts here. They're read on this phone only and never sent anywhere.", Manifest.permission.READ_CONTACTS, CONTACTS_PERM))
    val sort = UiPrefs.contactSort(this); val group = UiPrefs.groupByCompany(this)
    val desc = sort.endsWith("_za"); val byEmail = sort.startsWith("email")
    val dir = if (desc) -1 else 1
    val people = (phonePeople ?: emptyList()).filter { p ->
        q.isEmpty() || p.name.lowercase().contains(q) || p.email.lowercase().contains(q) || p.company.lowercase().contains(q) || p.numbers.any { it.first.contains(q) }
    }.sortedWith { a, b ->
        if (group) {   // companies in the chosen direction; people without one come last
            if (a.company.isEmpty() != b.company.isEmpty()) return@sortedWith if (a.company.isEmpty()) 1 else -1
            val c = a.company.compareTo(b.company, ignoreCase = true) * dir
            if (c != 0) return@sortedWith c
        }
        val ka = if (byEmail) a.email else a.name; val kb = if (byEmail) b.email else b.name
        if (byEmail && ka.isEmpty() != kb.isEmpty()) return@sortedWith if (ka.isEmpty()) 1 else -1   // no email: always last
        val c = ka.compareTo(kb, ignoreCase = true) * dir
        if (c != 0) c else a.name.compareTo(b.name, ignoreCase = true)
    }
    fun letterOf(s: String) = s.take(1).uppercase().let { if (it.firstOrNull()?.isLetter() == true) it else "#" }
    fun headerOf(p: Phone.Person) = when {
        group -> p.company.ifEmpty { "No company" }
        byEmail -> if (p.email.isEmpty()) "No email" else letterOf(p.email)
        else -> letterOf(p.name)
    }
    var letter = ""
    var next = 0
    fun addUpTo(count: Int) {
        val end = minOf(people.size, next + count)
        while (next < end) {
            val p = people[next++]
            val l = headerOf(p)
            if (l != letter) {   // B2 section label: small spaced capitals over a thin line
                letter = l
                list.addView(tv(l.uppercase(), 11.5f, dim, true).apply { letterSpacing = 0.08f; setPadding(dp(20), dp(14), dp(20), dp(6)) })
                list.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = dp(2) })
            }
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = pressable()
                setOnClickListener { contactCard(p) }
                addView(contactAvatar(p.name, p.photoUri, 44, p.numbers.firstOrNull()?.first?.let { numberSeed(it) } ?: "sms:n${p.name}"))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(tv(p.name, 16f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    // a small second line says what it is sorted or grouped by
                    val sub = if (byEmail) p.email else if (!group) p.company else ""   // grouped by company: the header already says it
                    if (sub.isNotEmpty()) addView(tv(sub, 13.5f, dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
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


/**
 * Fills [list] with one row per item, but only builds the first screenful (plus enough to reach where the list was scrolled to)
 * right away; the rest follows in small batches. Building hundreds of rows in one go on the main thread is what made switching
 * to Phone, opening Texts and backing out of a text chat feel slow. A search, or [all] = true, still builds everything at once.
 */
internal fun <T> MainActivity.addBatched(list: LinearLayout, items: List<T>, rowDp: Int, all: Boolean, make: (T) -> View) {
    var next = 0
    fun addUpTo(count: Int) {
        val end = minOf(items.size, next + count)
        while (next < end) list.addView(make(items[next++]))
    }
    addUpTo(if (all) items.size else maxOf(16, (chatsScrollY + resources.displayMetrics.heightPixels) / dp(rowDp) + 8))
    if (next < items.size) {
        val rest = object : Runnable {
            override fun run() {
                if (!list.isAttachedToWindow || (screen != Screen.CHATS && screen != Screen.TEXT_LIST)) return   // the screen was rebuilt or left
                if (slideEnd != null) { list.postDelayed(this, 40); return }   // wait for the slide to finish: building rows between frames makes it stutter
                addUpTo(25)
                if (next < items.size) list.post(this)
            }
        }
        list.post(rest)
    }
}

