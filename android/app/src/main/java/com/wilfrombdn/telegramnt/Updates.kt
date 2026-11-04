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
    val col = popupPanel().apply { layoutTransition = android.animation.LayoutTransition() }   // the panel grows smoothly when it expands
    col.addView(popupHeader("Update available", popupIcon(R.drawable.ic_download), tagChip(u.name)), LinearLayout.LayoutParams(-1, -2))
    val question = tv("A new version of $APP_NAME is ready. Update now?", 14.5f, soft).apply { setPadding(dp(14), dp(12), dp(14), 0) }
    col.addView(question)
    val d = showPanel(col, scroll = true)
    fun buttons(vararg b: Pair<Pair<String, Int>, () -> Unit>) = LinearLayout(this).apply {
        gravity = Gravity.END; setPadding(dp(4), dp(6), dp(4), dp(4))
        b.forEachIndexed { i, (lc, act) ->
            addView(tv(lc.first, 15f, lc.second, i == b.size - 1).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = pressable(); setOnClickListener { tapBuzz(); act() }
            })
        }
    }
    // progress: a thin bar, then what's happening on the left and the percent on the right
    val status = tv("", 12.5f, dim)
    val pct = tv("", 12.5f, white, true)
    val statusRow = LinearLayout(this).apply {
        setPadding(dp(14), dp(6), dp(14), 0); visibility = View.GONE
        addView(status, LinearLayout.LayoutParams(0, -2, 1f)); addView(pct)
    }
    val fill = View(this).apply { setBackgroundColor(white); pivotX = 0f; scaleX = 0f }
    val track = FrameLayout(this).apply {
        setBackgroundColor(chip); visibility = View.GONE
        addView(fill, FrameLayout.LayoutParams(-1, -1))
    }
    lateinit var ask: View
    lateinit var go: View
    lateinit var updateBtn: TextView
    fun startUpdate() {
        go.visibility = View.GONE; track.visibility = View.VISIBLE; statusRow.visibility = View.VISIBLE
        val resuming = Updater.hasPartial(this, u)
        status.text = if (resuming) "Picking up where it left off" else "Downloading"; pct.text = "0%"
        d.setCancelable(false)
        Updater.onStatus = { err -> runOnUiThread {
            if (err != null) { status.text = "Couldn't update: $err"; pct.text = ""; go.visibility = View.VISIBLE; d.setCancelable(true) }
        } }
        var apk: File? = null
        Messenger.run({
            apk = Updater.download(this, u, { p -> runOnUiThread { fill.scaleX = p; status.text = "Downloading"; pct.text = "${(p * 100).toInt()}%" } },
                { msg -> runOnUiThread { status.text = msg; pct.text = "" } })
        }, {
            fill.scaleX = 1f; pct.text = ""
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
            pct.text = ""
            updateBtn.text = "Continue"
            go.visibility = View.VISIBLE; d.setCancelable(true)
        }
    }
    go = buttons(("Not now" to dim) to { d.dismiss() }, ((if (Updater.hasPartial(this, u)) "Continue" else "Update") to white) to { startUpdate() })
    updateBtn = (go as LinearLayout).getChildAt(1) as TextView
    ask = buttons(("No" to dim) to { d.dismiss() }, ("Yes" to white) to {
        col.removeView(ask); col.removeView(question)
        col.addView(tv("WHAT'S NEW", 11f, dim, true).apply { letterSpacing = 0.08f; setPadding(dp(14), dp(12), dp(14), dp(4)) })
        (u.notes.ifEmpty { listOf("Fixes and improvements") }).forEachIndexed { i, n ->
            if (i > 0) col.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(14), 0, dp(14), 0) })
            col.addView(LinearLayout(this).apply {   // a small square bullet, then the note
                setPadding(dp(14), dp(6), dp(14), dp(6))
                addView(View(context).apply { setBackgroundColor(dim) }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(8); marginEnd = dp(9) })
                addView(tv(n, 14f, soft).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        col.addView(track, LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(dp(14), dp(12), dp(14), 0) })
        col.addView(statusRow)
        col.addView(go)
    })
    col.addView(ask)
    updateDialogOpen = true; updateShown = maxOf(updateShown, u.code.toLong())
    d.setOnDismissListener { updateDialogOpen = false }
    presentDialog(d, col)
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
