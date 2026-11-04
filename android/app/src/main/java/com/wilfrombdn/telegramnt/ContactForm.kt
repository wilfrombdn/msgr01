package com.wilfrombdn.telegramnt

import android.Manifest
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.provider.ContactsContract.CommonDataKinds.Phone as P

// Adding and editing a contact on the phone: one screen for both (a big initials circle, name, numbers with labels, emails),
// and the contact pop-up that offers Edit contact, Call and Text.

private val PHONE_LABELS = listOf(P.TYPE_MOBILE to "Mobile", P.TYPE_HOME to "Home", P.TYPE_WORK to "Work", P.TYPE_OTHER to "Other")

/** New contact, made right here and saved to the phone's contacts. [number] pre-fills the first number. */
internal fun MainActivity.showAddContact(number: String?, back: () -> Unit) = showContactForm(null, number, back)

/** Edit a contact in the same screen as "New contact", filled in. Save changes it on the phone; the X leaves it as it was. */
internal fun MainActivity.showEditContact(p: Phone.Person, back: () -> Unit) {
    var d: Phone.Detail? = null
    Messenger.run({ d = Phone.detail(this, p.id) }, { d?.let { showContactForm(it, null, back) } ?: toast("Couldn't open that contact") }) { toast("Couldn't open that contact") }
}

private class PhoneEntry(val field: EditText, var type: Int)

private fun MainActivity.showContactForm(detail: Phone.Detail?, number: String?, back: () -> Unit) {
    screen = Screen.ADD_CONTACT
    addContactBack = back
    val editing = detail != null

    fun box(hint: String, value: String = "") = field(hint, value).apply {
        backgroundTintList = null
        background = round(panel2, 12)
        setPadding(dp(14), dp(13), dp(14), dp(13))
    }
    val given = detail?.let { if (it.given.isBlank() && it.family.isBlank()) it.display else it.given }.orEmpty()
    val first = box("First name", given).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val last = box("Last name", detail?.family.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }

    // phone numbers, each with a label you tap to change, and a remove button; emails the same without labels
    val phones = ArrayList<PhoneEntry>()
    val phoneBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun newPhone(value: String, type: Int) = PhoneEntry(box("Phone number", value).apply {
        inputType = InputType.TYPE_CLASS_PHONE; addTextChangedListener(PhoneFormatWatcher())
    }, type).also { phones += it }
    if (detail != null && detail.phones.isNotEmpty()) detail.phones.forEach { newPhone(it.number, it.type) } else newPhone(number.orEmpty(), P.TYPE_MOBILE)
    val emails = ArrayList<EditText>()
    val emailBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun newEmail(value: String) = box("Email", value).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }.also { emails += it }
    if (detail != null && detail.emails.isNotEmpty()) detail.emails.forEach { newEmail(it.address) } else newEmail("")

    fun labelOf(type: Int) = PHONE_LABELS.firstOrNull { it.first == type }?.second ?: P.getTypeLabel(resources, type, "").toString()
    fun small(s: String, color: Int, onClick: () -> Unit) = tv(s, 14f, color, true).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8)); background = pressable(); setOnClickListener { onClick() }
    }
    lateinit var redraw: () -> Unit
    redraw = {
        phoneBox.removeAllViews()
        phones.forEachIndexed { i, e ->
            (e.field.parent as? LinearLayout)?.removeView(e.field)
            phoneBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(R.drawable.ic_call, if (i == 0) dim else android.graphics.Color.TRANSPARENT, 22, 9))
                addView(e.field, LinearLayout.LayoutParams(0, -2, 1f))
                addView(small(labelOf(e.type), blue) {
                    val at = PHONE_LABELS.indexOfFirst { it.first == e.type }
                    e.type = PHONE_LABELS[(at + 1) % PHONE_LABELS.size].first; redraw()   // Mobile, Home, Work, Other, then round again
                })
                addView(icon(R.drawable.ic_close, dim, 18, 8) { if (phones.size > 1) phones.remove(e) else e.field.setText(""); redraw() })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        emailBox.removeAllViews()
        emails.forEachIndexed { i, f ->
            (f.parent as? LinearLayout)?.removeView(f)
            emailBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(R.drawable.ic_mail, if (i == 0) dim else android.graphics.Color.TRANSPARENT, 22, 9))
                addView(f, LinearLayout.LayoutParams(0, -2, 1f))
                addView(icon(R.drawable.ic_close, dim, 18, 8) { if (emails.size > 1) emails.remove(f) else f.setText(""); redraw() })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }
    redraw()

    // the big circle at the top shows the initials as you type the name
    val avatarHolder = FrameLayout(this)
    fun refreshAvatar() {
        val name = "${first.text} ${last.text}".trim()
        avatarHolder.removeAllViews()
        avatarHolder.addView(avatar(name.ifEmpty { "?" }, 96), FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
    }
    refreshAvatar()
    val watch = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refreshAvatar() }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
    first.addTextChangedListener(watch); last.addTextChangedListener(watch)

    fun save() {
        if (first.text.isBlank() && last.text.isBlank()) return toast("Add a name")
        val nums = phones.map { it.field.text.toString().trim() to it.type }.filter { it.first.isNotEmpty() }
        val mails = emails.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        if (nums.isEmpty() && mails.isEmpty()) return toast("Add a phone number or email")
        if (!Phone.canWriteContacts(this)) {
            addContactSave = { save() }
            return requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), ADD_CONTACT_PERM)
        }
        val f = first.text.toString().trim(); val l = last.text.toString().trim()
        var ok = false
        Messenger.run({
            ok = if (detail != null) Phone.updateContact(this, detail, f, l, nums, mails) else Phone.addContact(this, f, l, nums, mails)
        }, {
            if (ok) {
                toast("Contact saved")
                Texts.forgetNames(); phoneLoaded = 0; phoneCalls = null
                Messenger.run({ Texts.threads.forEach { t -> t.addresses.forEach { Texts.name(this, it) } } }, { addContactBack() })
            } else toast("Couldn't save the contact")
        }) { toast("Couldn't save the contact") }
    }

    fun nameRow() = LinearLayout(this).apply {
        addView(icon(R.drawable.ic_person, dim, 22, 9).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(first, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(last, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(24)) }
    col.addView(avatarHolder, LinearLayout.LayoutParams(-1, dp(116)))
    col.addView(nameRow())
    col.addView(phoneBox)
    col.addView(small("+  Add phone", blue) { newPhone("", P.TYPE_MOBILE); redraw() }.apply { setPadding(dp(56), dp(8), dp(10), dp(12)) })
    col.addView(emailBox)
    col.addView(small("+  Add email", blue) { newEmail(""); redraw() }.apply { setPadding(dp(56), dp(8), dp(10), dp(12)) })
    val saveBtn = tv("SAVE", 15f, white, true).apply {
        setPadding(dp(18), dp(10), dp(18), dp(10)); background = round(panel2, 20); setOnClickListener { save() }
    }
    setContentView(page(
        topBar(if (editing) "Edit contact" else "New contact", null, icon(R.drawable.ic_close) { back() },
            LinearLayout(this).apply { setPadding(0, 0, dp(10), 0); addView(saveBtn) }),
        ScrollView(this).apply { addView(col) }))
    if (!editing) first.post { first.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(first, 0) }
}

/**
 * The contact pop-up: Edit contact first (only when it's a saved contact and [edit] is true, i.e. opened from the Contacts tab),
 * then each number with Call and Text. A number that isn't saved gets "Add to contacts" instead.
 */
internal fun MainActivity.contactCard(p: Phone.Person, edit: Boolean = true) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialog: android.app.Dialog? = null
    if (p.id == 0L || edit) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(10)); background = pressable(); isClickable = true
        addView(icon(R.drawable.ic_edit, white, 22, 0))
        addView(tv(if (p.id != 0L) "Edit contact" else "Add to contacts", 16f, white, true).apply { setPadding(dp(14), 0, 0, 0) })
        setOnClickListener {
            dialog?.dismiss()
            if (p.id != 0L) showEditContact(p) { showChats() } else showAddContact(p.numbers.firstOrNull()?.first) { showChats() }
        }
    })
    for ((n, label) in p.numbers) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(n, 16f, white))
            if (label.isNotEmpty()) addView(tv(label, 13f, dim))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_call, white, 22) { PhoneCalls.dial(this@contactCard, n) })
        addView(icon(R.drawable.ic_send, smsGreen, 22) {
            var id = 0L
            Messenger.run({ id = Texts.threadFor(this@contactCard, listOf(n)) }, { showTextThread(id, listOf(n)) })
        })
    })
    popup(p.name, "Call or text (through your carrier, not private)", col, "Close" to dim, dialogOut = { dialog = it })
}

/** Tapping a phone call in the Calls list: the contact's numbers with Call and Text (no editing here), or the bare number with "Add to contacts". */
internal fun MainActivity.callCard(number: String) {
    val person = phonePeople?.firstOrNull { p -> p.numbers.any { android.telephony.PhoneNumberUtils.compare(it.first, number) } }
    contactCard(person ?: Phone.Person(0L, Texts.name(this, number), listOf(number to "")), edit = false)
}
