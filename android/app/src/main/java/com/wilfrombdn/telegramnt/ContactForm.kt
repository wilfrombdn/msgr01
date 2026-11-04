package com.wilfrombdn.telegramnt

import android.Manifest
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.ViewGroup
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

    fun box(hint: String, value: String = "") = field(hint, value)   // the app's rounded text box
    var newPhoto: ByteArray? = null   // a photo picked here; null = leave the contact's photo as it is
    val given = detail?.let { if (it.given.isBlank() && it.family.isBlank()) it.display else it.given }.orEmpty()
    val first = box("First name", given).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val last = box("Last name", detail?.family.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
    val company = box("Company", detail?.company.orEmpty()).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }

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
    // Every box is the same width: the label and remove button sit inside the box, on its right.
    fun shell() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.InsetDrawable(round(panel2, 12), 0, dp(5), 0, dp(5))
    }
    fun inside(f: EditText) { f.background = null; f.setPadding(dp(14), dp(14), dp(4), dp(14)) }
    fun leadIcon(res: Int, show: Boolean) = icon(res, dim, 22, 9).apply { if (!show) visibility = View.INVISIBLE }
    lateinit var redraw: () -> Unit
    redraw = {
        phoneBox.removeAllViews()
        phones.forEachIndexed { i, e ->
            (e.field.parent as? ViewGroup)?.removeView(e.field)
            inside(e.field)
            phoneBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(leadIcon(R.drawable.ic_call, i == 0))
                addView(shell().apply {
                    addView(e.field, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(small(labelOf(e.type), blue) {
                        val at = PHONE_LABELS.indexOfFirst { it.first == e.type }
                        e.type = PHONE_LABELS[(at + 1) % PHONE_LABELS.size].first; redraw()   // Mobile, Home, Work, Other, then round again
                    })
                    addView(icon(R.drawable.ic_close, dim, 18, 8) { if (phones.size > 1) phones.remove(e) else e.field.setText(""); redraw() })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        emailBox.removeAllViews()
        emails.forEachIndexed { i, f ->
            (f.parent as? ViewGroup)?.removeView(f)
            inside(f)
            emailBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(leadIcon(R.drawable.ic_mail, i == 0))
                addView(shell().apply {
                    addView(f, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(icon(R.drawable.ic_close, dim, 18, 8) { if (emails.size > 1) emails.remove(f) else f.setText(""); redraw() })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    redraw()

    // the big circle at the top: their photo if they have one (tap to choose another), else the initials as you type the name
    val avatarHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    fun refreshAvatar() {
        val name = "${first.text} ${last.text}".trim()
        avatarHolder.removeAllViews()
        avatarHolder.addView(avatar(name.ifEmpty { "?" }, 96, newPhoto ?: detail?.photo))
        avatarHolder.addView(tv("Tap to add a photo", 13.5f, blue, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
    }
    avatarHolder.setOnClickListener {
        contactPhotoDone = { jpeg -> newPhoto = jpeg; refreshAvatar() }
        // The system photo picker needs no permissions and only hands over the one photo chosen.
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(i, PICK_CONTACT_PHOTO)
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
        val f = first.text.toString().trim(); val l = last.text.toString().trim(); val co = company.text.toString().trim()
        var ok = false
        Messenger.run({
            ok = if (detail != null) Phone.updateContact(this, detail, f, l, co, nums, mails, newPhoto) else Phone.addContact(this, f, l, co, nums, mails, newPhoto)
        }, {
            if (ok) {
                toast("Contact saved")
                Texts.forgetNames(); phoneLoaded = 0; phoneCalls = null; contactThumbs.clear()
                Messenger.run({ Texts.threads.forEach { t -> t.addresses.forEach { Texts.name(this, it) } } }, { addContactBack() })
            } else toast("Couldn't save the contact")
        }) { toast("Couldn't save the contact") }
    }

    fun nameRow() = LinearLayout(this).apply {
        addView(icon(R.drawable.ic_person, dim, 22, 9).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(first, LinearLayout.LayoutParams(-1, -2))
            addView(last, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun companyRow() = LinearLayout(this).apply {
        addView(icon(R.drawable.ic_company, dim, 22, 9).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) })
        addView(company, LinearLayout.LayoutParams(0, -2, 1f))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }
    col.addView(avatarHolder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(14) })
    col.addView(nameRow())
    col.addView(companyRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    col.addView(phoneBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    col.addView(small("+  Add phone", blue) { newPhone("", P.TYPE_MOBILE); redraw() }.apply { setPadding(dp(54), dp(8), dp(10), dp(8)) })
    col.addView(emailBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    col.addView(small("+  Add email", blue) { newEmail(""); redraw() }.apply { setPadding(dp(54), dp(8), dp(10), dp(8)) })
    val saveBtn = tv("SAVE", 15f, white, true).apply {
        setPadding(dp(18), dp(10), dp(18), dp(10)); background = round(panel2, 20); setOnClickListener { save() }
    }
    val root = page(
        topBar(if (editing) "Edit contact" else "New contact", null, icon(R.drawable.ic_close) { back() },
            LinearLayout(this).apply { setPadding(0, 0, dp(10), 0); addView(saveBtn) }),
        ScrollView(this).apply { addView(col) })
    contactFormView = root
    setContentView(root)
    if (!editing) first.post { first.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(first, 0) }
}

/**
 * The contact pop-up: Edit contact first (only when it's a saved contact and [edit] is true, i.e. opened from the Contacts tab),
 * then everything saved about them: company, each number (Call, Text; hold a number to copy it) and each email (tap to write
 * to them in an email app of your choice; hold to copy it). A number that isn't saved gets "Add to contacts" instead.
 */
internal fun MainActivity.contactCard(p: Phone.Person, edit: Boolean = true) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialog: android.app.Dialog? = null
    fun copy(what: String, value: String) {
        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(what, value)); toast("Copied")
    }
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
    if (p.company.isNotEmpty()) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        setOnLongClickListener { copy("company", p.company); true }
        addView(icon(R.drawable.ic_company, dim, 22, 0))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
            addView(tv(p.company, 16f, white)); addView(tv("Company", 13f, dim))
        })
    })
    for ((n, label) in p.numbers) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(n, 16f, white))
            if (label.isNotEmpty()) addView(tv(label, 13f, dim))
            background = pressable(); isLongClickable = true
            setOnLongClickListener { copy("number", n); true }   // hold the number to copy it
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_call, white, 22) { PhoneCalls.dial(this@contactCard, n) })
        addView(icon(R.drawable.ic_send, smsGreen, 22) {
            var id = 0L
            Messenger.run({ id = Texts.threadFor(this@contactCard, listOf(n)) }, { showTextThread(id, listOf(n)) })
        })
    })
    for (m in p.emails) col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6)); background = pressable(); isClickable = true
        addView(icon(R.drawable.ic_mail, dim, 22, 0))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
            addView(tv(m, 16f, blue).apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG })   // a link
            addView(tv("Email", 13f, dim))
        })
        setOnClickListener {   // asks which email app to use, then starts a new message to them
            val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + android.net.Uri.encode(m)))
            runCatching { startActivity(android.content.Intent.createChooser(i, "Email $m with")) }.onFailure { toast("No email app found") }
        }
        setOnLongClickListener { copy("email", m); true }
    })
    popup(p.name, "Hold a number or email to copy it. Calls and texts go through your carrier, not private.", col, "Close" to dim, dialogOut = { dialog = it })
}

/** Tapping a phone call in the Calls list: the contact's numbers with Call and Text (no editing here), or the bare number with "Add to contacts". */
internal fun MainActivity.callCard(number: String) {
    val person = phonePeople?.firstOrNull { p -> p.numbers.any { android.telephony.PhoneNumberUtils.compare(it.first, number) } }
    contactCard(person ?: Phone.Person(0L, Texts.name(this, number), listOf(number to "")), edit = false)
}

/** A photo was chosen for a contact: crop it, then hand it back to the open contact form (which is put back exactly as it was). */
internal fun MainActivity.contactPhotoPicked(uri: android.net.Uri) {
    var bmp: android.graphics.Bitmap? = null
    Messenger.run({ bmp = loadPhoto(uri) }, {
        val b = bmp
        if (b == null) toast("Couldn't open that photo")
        else showCrop(b, done = { jpeg -> restoreContactForm(); contactPhotoDone?.invoke(jpeg) }, cancel = { restoreContactForm() })
    }) { toast("Couldn't open that photo") }
}

internal fun MainActivity.restoreContactForm() {
    cropCancel = null
    contactFormView?.let { screen = Screen.ADD_CONTACT; setContentView(it) }
}

/**
 * A phone contact's picture for the lists: their photo if they have one (loaded in the background, the initials show meanwhile),
 * else the initials circle. Tellem friends keep using their Tellem profile picture everywhere else; this is only for phone contacts.
 */
internal fun MainActivity.contactAvatar(name: String, photoUri: String?, sizeDp: Int, seed: String? = null): View {
    if (photoUri == null) return avatar(name, sizeDp, null, seed)
    fun round(b: android.graphics.Bitmap) = android.widget.ImageView(this).apply {
        setImageBitmap(b); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
    }
    if (contactThumbs.containsKey(photoUri)) return contactThumbs[photoUri]?.let { round(it) }?.apply { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) } ?: avatar(name, sizeDp, null, seed)
    val holder = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)); addView(avatar(name, sizeDp, null, seed)) }
    var loaded: android.graphics.Bitmap? = null
    Messenger.run({
        loaded = runCatching { contentResolver.openInputStream(android.net.Uri.parse(photoUri))?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }, {
        contactThumbs[photoUri] = loaded
        loaded?.let { holder.removeAllViews(); holder.addView(round(it), FrameLayout.LayoutParams(-1, -1)) }
    })
    return holder
}
