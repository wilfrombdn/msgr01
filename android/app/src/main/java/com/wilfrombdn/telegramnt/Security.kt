package com.wilfrombdn.telegramnt

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

// App lock, screenshot blocking, identity confirmation, self-destruct menus and reopening where you were.

/** Nothing but the logo and an Unlock button until the phone's own lock (fingerprint, face, PIN) is passed. */
internal fun MainActivity.showLock() {
    val firstShow = screen != Screen.LOCK
    screen = Screen.LOCK
    val km = getSystemService(android.app.KeyguardManager::class.java)
    if (!km.isDeviceSecure) {
        // the phone's screen lock was removed: don't trap anyone behind a lock that can't open
        Messenger.appLock = false; Messenger.unlock(); Messenger.settingsChanged()
        toast(T("App lock is off: this phone has no screen lock any more"))
        return unlocked()
    }
    setContentView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = texturedBg(listBg); fitsSystemWindows = true
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_bubble_logo) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(tv(T("$APP_NAME is locked"), 20f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(28)) })
        addView(cta(T("Unlock")) { askToUnlock() }, ctaParams())
    })
    // ask straight away the first time; after a cancel, wait for a tap on Unlock
    if (firstShow && !lockAsked) window.decorView.post { askToUnlock() }
}

internal fun MainActivity.askToUnlock() {
    lockAsked = true
    confirmIdentity(T("Unlock $APP_NAME")) { unlocked() }
}

internal fun MainActivity.unlocked() {
    Messenger.unlock(); lockAsked = false
    Destruct.touch(this)   // only someone who can unlock it restarts the self-destruct countdown
    screen = Screen.NONE
    if (Messenger.signedIn) reopenSpot() else showWelcome()
    afterUnlock?.let { afterUnlock = null; handle(it) }
}

/** The phone's own check: fingerprint/face or PIN/pattern on Android 11+, PIN/pattern before that. */
internal fun MainActivity.confirmIdentity(title: String, ok: () -> Unit) {
    if (!getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure) return ok()   // no screen lock: nothing to check against
    if (Build.VERSION.SDK_INT >= 30) {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { ok() }
            })
        }.onFailure { toast(T("Couldn't show the unlock prompt")) }
    } else {
        pendingIdentity = ok
        @Suppress("DEPRECATION")
        val i = getSystemService(android.app.KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, null)
        if (i == null) ok() else @Suppress("DEPRECATION") startActivityForResult(i, CONFIRM_IDENTITY)
    }
}

internal fun MainActivity.destructLabel(h: Int) = when (h) { 1 -> T("1 hour"); 24 -> T("1 day"); 168 -> T("1 week"); 720 -> T("1 month"); 8760 -> T("1 year"); else -> TP(h, "hour", "hours") }

/** Settings > Privacy > Self-destruct: off, or wipe everything after 1 hour / day / week / month / year unopened. */
internal fun MainActivity.selfDestructMenu() {
    val opts = listOf(1, 24, 168, 720, 8760)
    pickList(T("Self-destruct"), T("If $APP_NAME isn't opened for this long, everything in it on this phone is wiped for good:"),
        (opts.map { T("After ") + destructLabel(it) } + T("Off")).toTypedArray()) { i ->
        if (i == opts.size) { Destruct.setHours(this, 0); showSettings(); return@pickList }
        val h = opts[i]
        popup(T("Turn on self-destruct?"),
            T("If you don't open $APP_NAME for ${destructLabel(h)}, every chat, photo, key and setting in it on this phone is erased. ") +
            T("There is no undo and no warning first. (Photos you saved to the phone's gallery, and normal texts, are outside $APP_NAME and stay.)\n\n") +
            T("Opening $APP_NAME starts the countdown again. Your account itself stays: with cloud backup on you could log in again and get your friends back, but not your chats."),
            null, T("Cancel") to dim, T("Turn on") to red) { b ->
            if (b == 1) confirmIdentity(T("Turn on self-destruct")) { Destruct.setHours(this, h); showSettings() }
        }
    }
}

/** Settings > Privacy > App lock: turn on (after one check), pick the delay, or turn off. */
internal fun MainActivity.appLockMenu() {
    if (!Messenger.appLock) {
        if (!getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
            return popup(T("Set a screen lock first"), T("App lock uses your phone's own fingerprint, face or PIN. Set one up in your phone's settings, then come back."), null, "OK" to white)
        return confirmIdentity(T("Turn on app lock")) { Messenger.appLock = true; Messenger.lockAfterMin = 1; Messenger.settingsChanged(); showSettings() }
    }
    val opts = listOf(1 to T("After 1 minute away"), 5 to T("After 5 minutes away"), 60 to T("After 1 hour away"))
    pickList(T("App lock"), T("$APP_NAME asks for your fingerprint, face or PIN when you come back after:"), (opts.map { it.second } + T("Turn off app lock")).toTypedArray()) { i ->
        if (i < opts.size) Messenger.lockAfterMin = opts[i].first else Messenger.appLock = false
        Messenger.settingsChanged(); showSettings()
    }
}

/** Back to the chat (and the place in it) you had open last time, else the chat list. */
internal fun MainActivity.reopenSpot() {
    val sp = Messenger.spot
    val at = if (sp.atBottom || sp.anchor.isEmpty()) null else sp.anchor to sp.offset
    when {
        sp.screen == "chat" && Messenger.core.contact(sp.id) != null -> showChat(sp.id, at)
        sp.screen == "group" && Messenger.core.group(sp.id) != null -> showGroup(sp.id, at)
        else -> showChats()
    }
}

/** Status/navigation bar colours (and dark icons on them in light mode). */
internal fun MainActivity.shotsAllowed() = UiPrefs.screenshots(this)

internal fun MainActivity.applyShotPolicy() {
    if (shotsAllowed()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
