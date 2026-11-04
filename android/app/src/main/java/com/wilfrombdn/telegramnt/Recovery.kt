package com.wilfrombdn.telegramnt

import android.content.Intent
import android.widget.LinearLayout

// Recovery backup file: save it, restore from it.

internal fun MainActivity.chooseRecoveryFile() {
    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "tellem-recovery.m01")   // no username in the name either
    @Suppress("DEPRECATION") startActivityForResult(i, CREATE_RECOVERY)
}

internal fun MainActivity.setupRecovery(uri: android.net.Uri) {
    takePersist(uri)
    Messenger.recoveryUri = uri.toString()
    Messenger.run({ Messenger.writeRecovery() }, { showProfile(); toast(T("Recovery backup on. Keep this file safe.")) }) { toast(T("Couldn't write the file")) }
}

internal fun MainActivity.pickRecoveryToRestore() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
    @Suppress("DEPRECATION") startActivityForResult(i, OPEN_RECOVERY)
}

internal fun MainActivity.restoreFromFile(uri: android.net.Uri) {
    var bytes: ByteArray? = null
    Messenger.run({ bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } }, {
        val b = bytes
        val owner = b?.let { Messenger.core.backupOwner(it) }
        if (b == null || owner == null) { toast(T("That isn't a $APP_NAME recovery file.")); return@run }
        val pass = field(T("Password"), password = true)
        // newer files don't carry the username on the outside, so it's typed in
        val user = if (owner.isEmpty()) field(T("Username")) else null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; user?.let { addView(it) }; addView(pass) }
        popup(if (owner.isEmpty()) T("Restore your chats") else T("Restore @$owner"), T("Enter the account ${if (owner.isEmpty()) "username and password" else "password"} to unlock this backup. Your chats will be restored on this phone."), box,
            T("Cancel") to dim, T("Restore") to white) { i ->
            if (i == 1) Messenger.run({
                if (!Messenger.core.restoreFull(b, pass.text.toString(), user?.text?.toString().orEmpty())) throw UserError(T("Wrong username or password, or the file is damaged"))
                takePersist(uri); Messenger.recoveryUri = uri.toString(); Messenger.save()
            }, {
                Messenger.signedOutReason = null
                startForegroundService(Intent(this, SyncService::class.java))
                showChats()
            }) { e -> toast(errorText(e)) }
        }
    }) { toast(T("Couldn't read that file")) }
}
