package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

// In-app updates: checking, the "update available" pop-up and installing.

/** Once Tor is up, look for a newer version (at most every 6 hours) and offer it. */
internal fun MainActivity.maybeCheckUpdate() {
    if (Tor.socksPort() == null && !Messenger.direct && !Tor.outdated) return   // Tor still starting
    if (System.currentTimeMillis() - updateChecked < 10 * 60_000L) return   // every app open, at most every 10 minutes
    updateChecked = System.currentTimeMillis()
    var u: Updater.Update? = null
    Messenger.run({ u = Updater.check(this) }, { u?.let { if (screen != Screen.VIEWER && screen != Screen.CROP) updatePrompt(it) } }) {
        updateChecked = System.currentTimeMillis() - 10 * 60_000L + 60_000L   // couldn't reach it: try again in a minute
    }
}

/** Settings > Check for updates: says exactly what happened. */
internal fun MainActivity.checkUpdateNow() {
    if (Tor.socksPort() == null) { toast("Still connecting to Tor — try again in a moment"); return }
    toast("Checking for updates…")
    var u: Updater.Update? = null
    Messenger.run({ u = Updater.check(this) }, {
        updateChecked = System.currentTimeMillis()
        u?.let { updatePrompt(it) } ?: toast("You're up to date")
    }) { e -> toast("Couldn't check for updates (${e.message ?: e.javaClass.simpleName})") }
}

/** "Update available — Yes/No"; Yes opens what's new and an Update button with a progress bar. */
internal fun MainActivity.updatePrompt(u: Updater.Update) {
    if (updateDialogOpen) return   // one update pop-up at a time
    val d = android.app.Dialog(this)
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(12), dp(4))
        background = round(bar, 4)
        layoutTransition = android.animation.LayoutTransition()   // the panel grows smoothly when it expands
    }
    col.addView(tv("Update available", 17.5f, white, true).apply { setPadding(0, 0, dp(8), dp(6)) })
    col.addView(tv("$APP_NAME ${u.name} is ready. Update now?", 14.5f, dim).apply { setPadding(0, 0, dp(8), dp(4)) })
    fun buttons(vararg b: Pair<Pair<String, Int>, () -> Unit>) = LinearLayout(this).apply {
        gravity = Gravity.END; setPadding(0, dp(4), 0, 0)
        b.forEach { (lc, act) -> addView(tv(lc.first.uppercase(), 14f, lc.second, true).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { act() } }) }
    }
    val status = tv("", 13.5f, dim).apply { setPadding(0, dp(6), dp(8), 0); visibility = View.GONE }
    val fill = View(this).apply { setBackgroundColor(white); pivotX = 0f; scaleX = 0f }
    val track = FrameLayout(this).apply {
        setBackgroundColor(panel2); visibility = View.GONE
        addView(fill, FrameLayout.LayoutParams(-1, -1))
    }
    lateinit var ask: View
    lateinit var go: View
    lateinit var updateBtn: TextView
    fun startUpdate() {
        go.visibility = View.GONE; track.visibility = View.VISIBLE; status.visibility = View.VISIBLE
        val resuming = Updater.hasPartial(this, u)
        status.text = if (resuming) "Picking up where it left off…" else "Downloading… 0%"
        d.setCancelable(false)
        Updater.onStatus = { err -> runOnUiThread {
            if (err != null) { status.text = "Couldn't update: $err"; go.visibility = View.VISIBLE; d.setCancelable(true) }
        } }
        var apk: File? = null
        Messenger.run({
            apk = Updater.download(this, u, { p -> runOnUiThread { fill.scaleX = p; status.text = "Downloading… ${(p * 100).toInt()}%" } },
                { msg -> runOnUiThread { status.text = msg } })
        }, {
            fill.scaleX = 1f
            status.text = "Installing… $APP_NAME will close and reopen."
            val f = apk!!
            if (!packageManager.canRequestPackageInstalls()) {
                // Android asks once whether Tellem may install its own updates
                pendingApk = f
                status.text = "Allow $APP_NAME to install updates, then come back here."
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            } else Messenger.run({ Updater.install(this, f) }, {}) { e -> status.text = "Couldn't update: ${e.message}"; go.visibility = View.VISIBLE; d.setCancelable(true) }
        }) { e ->
            status.text = if (e is java.io.IOException) {
                val at = (fill.scaleX * 100).toInt()
                if (e is Updater.Damaged) "The download came through damaged. Tap Continue to try again."
                else if (Updater.hasPartial(this, u)) "The connection keeps dropping. Tap Continue to carry on from ${if (at > 0) "$at%" else "where it stopped"} — nothing downloaded is lost."
                else "Couldn't reach the update (${e.message}). Tap Continue to try again."
            } else errorText(e)
            updateBtn.text = "CONTINUE"
            go.visibility = View.VISIBLE; d.setCancelable(true)
        }
    }
    go = buttons(("Not now" to dim) to { d.dismiss() }, ((if (Updater.hasPartial(this, u)) "Continue" else "Update") to white) to { startUpdate() })
    updateBtn = (go as LinearLayout).getChildAt(1) as TextView
    ask = buttons(("No" to dim) to { d.dismiss() }, ("Yes" to white) to {
        col.removeView(ask)
        col.addView(tv("What's new", 15f, white, true).apply { setPadding(0, dp(8), dp(8), dp(4)) })
        (u.notes.ifEmpty { listOf("Fixes and improvements") }).forEach { n ->
            col.addView(tv("•  $n", 14f, dim).apply { setLineSpacing(0f, 1.06f); setPadding(0, dp(2), dp(8), dp(2)) })
        }
        col.addView(gap(10))
        col.addView(track, LinearLayout.LayoutParams(-1, dp(5)).apply { rightMargin = dp(8) })
        col.addView(status)
        col.addView(go)
    })
    col.addView(ask)
    d.setContentView(ScrollView(this).apply { addView(col) })
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(minOf((resources.displayMetrics.widthPixels * 0.86).toInt(), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
    updateDialogOpen = true; updateShown = maxOf(updateShown, u.code.toLong())
    d.setOnDismissListener { updateDialogOpen = false }
    d.show()
}

/**
 * While the app is open, an update that drops pops up by itself within about a minute (no need to reopen the app).
 * Offered once per version per run: "Not now" isn't nagged again until the next app open or the next version.
 * Not over the photo viewer / crop screens, and never on top of an update pop-up that is already open.
 */
internal fun MainActivity.pollUpdate() {
    if (updateDialogOpen || isFinishing) return
    if (Tor.socksPort() == null && !Messenger.direct && !Tor.outdated) return   // Tor still starting
    var u: Updater.Update? = null
    Messenger.run({ u = Updater.check(this) }, {
        u?.let { if (it.code > updateShown && !updateDialogOpen && !isFinishing && screen != Screen.VIEWER && screen != Screen.CROP) updatePrompt(it) }
    }) { /* couldn't reach it: the next minute tries again */ }
}
