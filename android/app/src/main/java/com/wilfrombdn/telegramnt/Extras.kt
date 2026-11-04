package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

// ---------- polls ----------

/** Attach > Poll (groups): a question and 2-10 options. */
internal fun MainActivity.createPoll(g: Group) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val q = field("Ask a question", "")
    col.addView(q)
    val opts = mutableListOf<EditText>()
    val optCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addOpt() { if (opts.size < 10) field("Option ${opts.size + 1}", "").also { opts.add(it); optCol.addView(it) } }
    addOpt(); addOpt()
    col.addView(optCol)
    col.addView(tv("+ Add an option", 15f, blue, true).apply { setPadding(dp(4), dp(10), dp(4), dp(4)); setOnClickListener { addOpt() } })
    popup("New poll", null, ScrollView(this).apply { addView(col) }, "Cancel" to dim, "Create" to white) { i ->
        if (i != 1) return@popup
        val options = opts.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        stick = true
        Messenger.run({ Messenger.core.sendPoll(g, q.text.toString(), options) }, {}) { e -> toast(errorText(e)) }
    }
}

/** A poll in a group: tap an option to vote (tap your vote again to take it back); bars show results once you've voted. */
internal fun MainActivity.pollView(g: Group, m: Msg, ink: Int): View {
    val poll = m.poll!!
    val me = Messenger.core.me?.user ?: ""
    val options = poll.optJSONArray("o") ?: org.json.JSONArray()
    val mine = m.votes[me]
    val total = m.votes.size
    val faded = (ink and 0x00FFFFFF) or (0x99 shl 24)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(dp(250), -2)
        addView(tv(poll.optString("q"), 16f, ink, true).apply { setPadding(0, dp(2), 0, dp(2)) })
        addView(tv("Poll", 12.5f, faded).apply { setPadding(0, 0, 0, dp(6)) })
        for (i in 0 until options.length()) {
            val count = m.votes.values.count { it == i }
            val pct = if (total > 0) count * 100 / total else 0
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)); background = pressable()
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(tv(if (mine == null) "○" else if (mine == i) "✓" else "", 15f, if (mine == i) blue else faded).apply { minWidth = dp(22) })
                    addView(tv(options.optString(i), 15f, ink), LinearLayout.LayoutParams(0, -2, 1f))
                    if (mine != null) addView(tv("$pct%", 13.5f, faded, true))
                })
                if (mine != null) addView(FrameLayout(context).apply {
                    addView(View(context).apply { background = round((ink and 0x00FFFFFF) or (0x22 shl 24), 3) }, FrameLayout.LayoutParams(-1, dp(5)))
                    addView(View(context).apply { background = round(blue, 3) }, FrameLayout.LayoutParams(maxOf(dp(5), dp(228) * pct / 100), dp(5)))
                }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(4); leftMargin = dp(22) })
                setOnClickListener { Messenger.run({ Messenger.core.vote(g, m, if (mine == i) -1 else i) }) }
                setOnLongClickListener { groupMessageMenu(g, m); true }
            })
        }
        addView(tv(if (total == 1) "1 vote" else "$total votes", 13f, faded).apply { setPadding(0, dp(6), 0, 0) })
    }
}

// ---------- location ----------

/** Attach > Location: asks first, then finds where you are once and sends it. Nothing is tracked. */
internal fun MainActivity.shareLocation() {
    val c = attachTo?.let { Messenger.core.contact(it) }
    val g = attachGroupId?.let { Messenger.core.group(it) }
    if (c == null && g == null) return
    val fine = Manifest.permission.ACCESS_FINE_LOCATION; val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    if (checkSelfPermission(fine) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(coarse) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(fine, coarse), LOCATION_FOR_SHARE); return
    }
    popup("Send your location?", "Sends where you are right now to ${c?.name ?: g!!.name}. Just this once: it isn't tracked or updated.",
        null, "Cancel" to dim, "Send" to white) { i -> if (i == 1) locateAndSend(c, g) }
}

@android.annotation.SuppressLint("MissingPermission")   // checked in shareLocation
internal fun MainActivity.locateAndSend(c: Contact?, g: Group?) {
    val lm = getSystemService(android.location.LocationManager::class.java)
    val provider = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return toast("Turn on Location in your phone's settings first")
    toast("Finding your location…")
    var done = false
    fun send(l: android.location.Location?) {
        if (done) return
        done = true
        val best = l ?: listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (best == null) { toast("Couldn't find your location. Try again outside or with Wi-Fi on."); return }
        stick = true
        Messenger.sendLocation(c, g, org.json.JSONObject().put("la", best.latitude).put("lo", best.longitude).put("acc", best.accuracy.toDouble()))
    }
    if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { send(it) }
    else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { send(it) }, mainLooper)
    window.decorView.postDelayed({ send(null) }, 25_000)   // GPS can take a while indoors: fall back to the last fix
}

/** A shared location: the place and how exact it is; tap opens it in a maps app. */
internal fun MainActivity.locationCard(loc: org.json.JSONObject, ink: Int): View = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), dp(6), dp(4))
    val la = loc.optDouble("la"); val lo = loc.optDouble("lo")
    addView(icon(R.drawable.ic_location, ink, 26, 8).apply { background = circle(Color.parseColor("#22FFFFFF")) })
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
        addView(tv("Location", 15f, ink, true))
        addView(tv("%.5f, %.5f".format(Locale.US, la, lo) + (loc.optDouble("acc", 0.0).takeIf { it > 0 }?.let { " · ±${it.toInt()} m" } ?: ""), 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
        addView(tv("Open in Maps", 13.5f, blue, true).apply { setPadding(0, dp(2), 0, 0) })
    })
    setOnClickListener {
        val uri = android.net.Uri.parse("geo:$la,$lo?q=$la,$lo")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No maps app on this phone") }
    }
}

/** The link card under a message: site, title, a little description. Tap opens the link. */
internal fun MainActivity.linkCard(lp: org.json.JSONObject, ink: Int): View = LinearLayout(this).apply {
    val faded = (ink and 0x00FFFFFF) or (0xB0 shl 24)
    setPadding(0, dp(4), 0, dp(2))
    addView(View(context).apply { setBackgroundColor(blue) }, LinearLayout.LayoutParams(dp(3), -1))
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0)
        lp.optString("s").takeIf { it.isNotEmpty() }?.let { addView(tv(it, 13.5f, blue, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
        addView(tv(lp.optString("t"), 14.5f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        lp.optString("d").takeIf { it.isNotEmpty() }?.let { addView(tv(it, 13.5f, faded).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }) }
    }, LinearLayout.LayoutParams(-2, -2))
    layoutParams = LinearLayout.LayoutParams(dp(250), -2).apply { bottomMargin = dp(2) }
    setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(lp.optString("u")))) } }
}
