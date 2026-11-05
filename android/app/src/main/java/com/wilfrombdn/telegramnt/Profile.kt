package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// Your profile screen, profile photo and its crop screen.

// ---------- profile photo ----------

internal fun MainActivity.photoMenu(anchor: View) {
    val choose = "Choose a photo" to { pickPhoto() }
    if (Messenger.core.me?.avatar == null) dropdown(anchor, Gravity.START, choose)
    else dropdown(anchor, Gravity.START, choose, "Remove photo" to { Messenger.run({ Messenger.core.setAvatar(null) }, { showProfile() }) })
}

internal fun MainActivity.pickGroupPhoto() {
    val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
    else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
    @Suppress("DEPRECATION") startActivityForResult(i, PICK_GROUP_PHOTO)
}

internal fun MainActivity.pickPhoto() {
    // The system photo picker needs no permissions and only hands over the one photo chosen.
    val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
    else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
    @Suppress("DEPRECATION") startActivityForResult(i, PICK_PHOTO)
}

/** The crop screen. By default it sets your Tellem profile photo; [done] / [cancel] let another screen (a contact's photo) use it instead. */
internal fun MainActivity.showCrop(photo: android.graphics.Bitmap, done: ((ByteArray) -> Unit)? = null, cancel: (() -> Unit)? = null) {
    cropCancel = cancel
    screen = Screen.CROP
    val crop = CropView(this, photo)
    val bottom = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(dp(16), dp(10), dp(16), dp(16))
        addView(tv("Cancel", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { cancel?.invoke() ?: showProfile() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(mainButton("Done") {
            val jpeg = crop.crop()
            if (done != null) done(jpeg)
            else Messenger.run({ Messenger.core.setAvatar(jpeg) }, { showProfile() }) { e -> toast(errorText(e)); showProfile() }
        }.apply { background = round(Color.WHITE, 10); setTextColor(Color.parseColor("#111111")) }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    setContentView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        fitsSystemWindows = true
        addView(tv("Move and zoom to fit your face in the circle", 15f, dim).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    })
}

// ---------- your profile (personal) ----------

internal fun MainActivity.showProfile() {
    screen = Screen.PROFILE
    val me = Messenger.core.me ?: return
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(bar)
        setPadding(dp(20), dp(16), dp(20), dp(22))
        addView(avatar(me.name, 72, me.avatar, avatarSeed(me.user)).apply { setOnClickListener { v -> photoMenu(v) } })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(tv(me.name, 21f, white, true))
            addView(tv("@${me.user}", 15f, dim).apply { setPadding(0, dp(2), 0, 0) })
        })
    })
    // my QR code right here: a friend scans it (their New Message opens the camera) to add me
    col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(20), dp(20), dp(6))
        addView(ImageView(context).apply {
            setImageBitmap(QrActivity.codeFor(me.user, 512, Messenger.core.myKeyFingerprint()))
            background = round(Color.WHITE, 12); setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { myQr() }
        }, LinearLayout.LayoutParams(dp(200), dp(200)))
        addView(tv("My QR code: friends scan it to add you", 14f, dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
    })
    col.addView(settingRow("Scan a QR code", "Add a friend by scanning theirs") { scanToAdd() })
    col.addView(gap(8))
    col.addView(settingRow(if (me.avatar == null) "Set profile photo" else "Change profile photo", "Only your friends can see it") { v -> photoMenu(v) })
    col.addView(settingRow("Share username", "Invite a friend to add you") {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Add me on $APP_NAME: @${me.user}\nGet the app: https://wilfrombdn.github.io/"), "Share username"))
    })
    col.addView(gap(8))
    col.addView(settingRow("Cloud backup", if (me.cloudBackup) "On: you can log in on a new phone. The backup is locked with your password; the server can't read it." else "Off: this account only works on this phone, and the server keeps nothing to restore.") {
        if (me.cloudBackup) popup("Turn off cloud backup?",
            "The server will wipe its locked copy of your keys and friends. You'll no longer be able to log in on another phone or after reinstalling. Nothing will be left on the server to hand over.", null,
            "Cancel" to dim, "Turn off" to red) { i ->
            if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(false) }, { showProfile() }) { e -> toast(errorText(e)) }
        } else popup("Turn on cloud backup?",
            "A copy of your keys and friends, locked with your password, is kept on the server so you can log in on a new phone.", null,
            "Cancel" to dim, "Turn on" to white) { i ->
            if (i == 1) Messenger.run({ Messenger.core.setCloudBackup(true) }, { showProfile() }) { e -> toast(errorText(e)) }
        }
    })
    col.addView(settingRow("Recovery backup", if (Messenger.recoveryUri.isEmpty()) "Off. Save an encrypted backup file so your chats survive reinstalling the app." else "On. Your chats are saved, encrypted, to the file you chose. The server still keeps nothing.") {
        if (Messenger.recoveryUri.isEmpty()) popup("Turn on recovery backup",
            "Here's exactly what this does:\n\n" +
            "• $APP_NAME keeps a copy of all your chats in one file and keeps it up to date as you message.\n\n" +
            "• You choose where that file goes next — your phone's storage, an SD card, or a cloud folder like Drive. It never goes to the server; only you hold it.\n\n" +
            "• The file is locked with your account password (AES-256). Without that password it can't be read — not by us, not by the server, not by anyone who finds the file.\n\n" +
            "• To get your chats back after reinstalling: open $APP_NAME → Restore from a backup file → enter your password.\n\n" +
            "One caveat: if you save it into a synced folder like Drive, that service may keep older versions of the file. For zero copies anywhere, leave this off.", null,
            "Not now" to dim, "Choose file" to white) { i -> if (i == 1) chooseRecoveryFile() }
        else popup("Recovery backup", "Your chats are being saved to your chosen file, encrypted with your password. The server keeps nothing.", null,
            "Turn off" to red, "Change file" to white) { i ->
            if (i == 0) { Messenger.recoveryUri = ""; Messenger.settingsChanged(); showProfile() }
            if (i == 1) chooseRecoveryFile()
        }
    })
    col.addView(gap(8))
    col.addView(settingRow("Log out", "Your chats stay locked on this phone for when you log back in.") {
        popup("Log out?", "You can log back in any time with your username and password.", null,
            "Cancel" to dim, "Log out" to red) { i ->
            if (i == 1) {
                stopService(Intent(this, SyncService::class.java))
                Messenger.logOut()
                showWelcome()
            }
        }
    })
    col.addView(settingRow("Delete account", "Removes @${me.user} from the server for good.", red) {
        val pass = field("Password", password = true)
        popup("Delete your account?", "This permanently removes @${me.user} and all your chats. The server keeps no backup and nobody — not even us — can recover anything. Type your password to confirm.", pass,
            "Cancel" to dim, "Delete" to red) { i ->
            if (i == 1) Messenger.run({ Messenger.deleteAccount(pass.text.toString()) }, {
                stopService(Intent(this, SyncService::class.java))
                showWelcome()
            }) { e -> toast(errorText(e)) }
        }
    })
    setContentView(page(topBar("Profile", left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
}
