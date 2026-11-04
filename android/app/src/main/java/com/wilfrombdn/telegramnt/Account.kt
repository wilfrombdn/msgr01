package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

// ---------- welcome, sign up, log in ----------

internal fun MainActivity.showWelcome() {
    screen = "welcome"
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(32), dp(32), dp(32))
    }
    // the white bubble from the icon (no black square) with the name beside it
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_bubble_logo) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(tv(APP_NAME, 34f, white, true).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); setPadding(dp(12), 0, 0, 0) })
    }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    col.addView(gap(18))
    col.addView(tv("Features or privacy? Pick one?\nThat's a hostage situation.\nHave both. Free, forever.", 16f, dim).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
    })
    Messenger.signedOutReason?.let {
        col.addView(gap(16))
        col.addView(tv(it, 14f, red).apply { gravity = Gravity.CENTER })
    }
    col.addView(gap(40))
    col.addView(cta("Start Messaging") { connectThen { showLogin() } }, ctaParams())
    col.addView(gap(14))
    col.addView(tv("I don't have an account", 16f, white, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { connectThen { showSignup() } }
    })
    col.addView(tv("Restore from a backup file", 15f, dim).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { pickRecoveryToRestore() }
    })
    if (Texts.isDefault(this)) col.addView(tv("Open my texts", 15f, white, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { showTextList() }
    })
    col.addView(gap(24))
    welcomeStatus = tv("", 15f, dim, true).apply { gravity = Gravity.CENTER }
    col.addView(welcomeStatus)
    welcomeTorNote = tv(TOR_WAIT_NOTE, 13.5f, amber).apply {
        gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f); setPadding(dp(12), dp(14), dp(12), 0)
        visibility = View.GONE
    }
    col.addView(welcomeTorNote)
    // what Tor is doing right now, and a way out if Tor can't get through (the Settings menu needs an account)
    welcomeTorLine = tv("", 13f, dim).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), 0); visibility = View.GONE; setOnClickListener { torDetails() } }
    col.addView(welcomeTorLine)
    welcomeDirect = tv("", 15f, white, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(8)); visibility = View.GONE
        setOnClickListener { Messenger.useDirect(!Messenger.direct) }
    }
    col.addView(welcomeDirect)
    setContentView(ScrollView(this).apply {
        background = texturedBg(listBg)
        fitsSystemWindows = true
        isFillViewport = true
        addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
    })
    celebrating = false
    startTicker()
}

internal fun MainActivity.startTicker() {
    val st = welcomeStatus ?: return
    if (Messenger.connected || celebrating) return
    st.removeCallbacks(ticker)   // never two loops at once
    if (st.text.isEmpty()) { dots = 0; st.text = "Connecting" }
    ticker.run()
}

/** The server is found automatically; if it isn't reachable yet, carry on once it is. */
internal fun MainActivity.connectThen(next: () -> Unit) {
    if (Messenger.connected) return next()
    pendingNext = next
    Messenger.connectInBackground()
    startTicker()
}

internal fun MainActivity.accountForm(title: String, signup: Boolean) {
    screen = if (signup) "signup" else "login"
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(24), dp(28), dp(24))
    }
    col.addView(tv(if (signup) "Your Account" else "Log In", 24f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    col.addView(gap(8))
    col.addView(tv(if (signup) "Pick a username. Friends find you with it." else "Enter your username and password.", 15f, dim)
        .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    col.addView(gap(20))
    val name = field("Your name")
    if (signup) col.addView(name, LinearLayout.LayoutParams(-1, -2))
    val user = usernameField("Username")
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(tv("@", 17f, dim))
        addView(user, LinearLayout.LayoutParams(0, -2, 1f))
    }, LinearLayout.LayoutParams(-1, -2))
    val pass = field("Password", password = true)
    col.addView(pass, LinearLayout.LayoutParams(-1, -2))
    val pass2 = field("Repeat password", password = true)
    if (signup) col.addView(pass2, LinearLayout.LayoutParams(-1, -2))
    col.addView(gap(14))
    col.addView(tv(
        if (signup) "No phone number or email. Use at least 10 characters. If you forget your password, nobody can get your account back, not even us."
        else "Logging in here signs you out on your other phone. Your friends come back, but old messages stay on the old phone.",
        13.5f, dim,
    ).apply { setLineSpacing(0f, 1.15f); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    col.addView(gap(24))
    val go = cta(if (signup) "Create Account" else "Log In") {}
    go.setOnClickListener {
        val u = user.text.toString().trim().removePrefix("@").lowercase()
        val p = pass.text.toString()
        if (signup && p != pass2.text.toString()) { toast("The passwords don't match"); return@setOnClickListener }
        dismissKeyboard()
        go.text = if (signup) "Making your keys…" else "Logging in…"
        go.isEnabled = false
        Messenger.run({
            if (signup) Messenger.core.signup(u, p, name.text.toString()) else Messenger.logIn(u, p)
        }, {
            Messenger.signedOutReason = null
            startForegroundService(Intent(this, SyncService::class.java))
            showChats()
        }) { e ->
            go.text = if (signup) "Create Account" else "Log In"
            go.isEnabled = true
            toast(errorText(e))
        }
    }
    col.addView(go, ctaParams())
    setContentView(page(topBar(title, left = icon(R.drawable.ic_back) { showWelcome() }), ScrollView(this).apply {
        isFillViewport = true
        addView(col, android.widget.FrameLayout.LayoutParams(-1, -1))
    }))
}

internal fun MainActivity.showSignup() = accountForm("Sign Up", signup = true)

internal fun MainActivity.showLogin() = accountForm("Log In", signup = false)

// ---------- chat list ----------

// ---------- ringing when the app is closed ----------

/**
 * What the phone still needs so a call rings like a normal phone call when Tellem is closed:
 * each item is (what, why, fix). Empty = all set.
 */
internal fun MainActivity.ringFixes(): List<Triple<String, String, () -> Unit>> {
    val out = mutableListOf<Triple<String, String, () -> Unit>>()
    val nm = getSystemService(android.app.NotificationManager::class.java)
    if (!Messenger.background) out.add(Triple("Get messages when the app is closed", "Without it, nothing can reach this phone while $APP_NAME is closed.") {
        Messenger.background = true; Messenger.settingsChanged(); startForegroundService(Intent(this, SyncService::class.java)); showSettings()
    })
    if (!nm.areNotificationsEnabled()) out.add(Triple("Notifications", "Calls and messages show as notifications.") {
        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    })
    if (!getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) out.add(Triple("Battery: unrestricted",
        "Otherwise Android cuts $APP_NAME's internet when the screen is off, and calls never arrive.") {
        @android.annotation.SuppressLint("BatteryLife")
        val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }.onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    })
    if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) out.add(Triple("Full-screen call alerts",
        "Lets an incoming call take over the screen (even when locked), like a normal phone call.") {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")))
    })
    return out
}

/** Once, after signing in: offer to set the phone up so calls ring when Tellem is closed. */
internal fun MainActivity.maybeAskRingSetup() {
    if (Messenger.ringSetupAsked || !Messenger.signedIn) return
    val fixes = ringFixes()
    Messenger.ringSetupAsked = true; Messenger.settingsChanged()
    if (fixes.isEmpty()) return
    popup("Ring like a phone call", "So calls ring this phone even when $APP_NAME is closed, allow: " + fixes.joinToString(", ") { it.first } +
        ". You can do this later in Settings > Calls.", null, "Later" to dim, "Set up" to white) { i -> if (i == 1) showSettings() }
}
