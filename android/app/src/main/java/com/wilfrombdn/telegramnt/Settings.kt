package com.wilfrombdn.telegramnt

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

// Settings, storage and appearance screens.

internal fun MainActivity.showSettings() {
    screen = Screen.SETTINGS
    connLabel = null
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(gap(8))
    col.addView(settingRow(T("Language"), languageLabel()) { pickLanguage() })
    col.addView(settingRow(T("Appearance"), T("Bubble colours, text size, corners, animations")) { showAppearance() })
    col.addView(settingRow(T("Storage"), T("Photos, videos and files on this phone")) { showStorage() })
    val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
    col.addView(settingRow(T("Check for updates"), T("You have $APP_NAME $ver")) { checkUpdateNow() })
    col.addView(tv(T("Connection"), 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
    val direct = Messenger.direct
    val on = Messenger.connected
    fun choice(title: String, state: String, picked: Boolean, go: () -> Unit) = col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(2)); background = pressable()
        addView(tv(if (picked) "◉" else "○", 18f, if (picked) blue else dim).apply { setPadding(0, 0, dp(12), 0) })
        addView(tv(title, 16f, white), LinearLayout.LayoutParams(0, -2, 1f))
        if (picked) addView(tv(state, 13.5f, if (on) green else amber).also { connLabel = it })
        setOnClickListener { go() }
    })
    choice(T("Direct (faster, less private)"), if (on) T("✓ active") else (Messenger.directProblem ?: "connecting…"), direct) {
        if (!direct) confirmDirect { Messenger.useDirect(true); showSettings() }
    }
    col.addView(settingNote(T("Skips Tor and goes through a Cloudflare tunnel. Cloudflare and the server can see your phone's internet address. Messages stay end-to-end encrypted. Only use it if Tor is blocked on your network.")))
    choice(T("Tor (most private)"), if (on) T("✓ active") else Tor.status(), !direct) { if (direct) { Messenger.useDirect(false); showSettings() } }
    col.addView(settingNote(T("Everything goes through the Tor network, so no one, not even the server, ever sees your phone's internet address.")))
    col.addView(settingRow(T("Tor details"), T("What Tor is doing, to copy and send if it won't connect")) { torDetails() })
    if (Messenger.core.unreadable > 0) col.addView(tv(T("⚠ ${Messenger.core.unreadable} message(s) arrived but couldn't be decrypted — the two accounts' keys are out of sync (usually from reinstalling/restoring). Fresh accounts fix this."), 13.5f, amber).apply { setPadding(dp(20), dp(2), dp(20), dp(10)) })
    if (Messenger.core.lastDrop.isNotEmpty()) col.addView(tv(T("diag · last fetched message: ${Messenger.core.lastDrop}"), 12.5f, dim).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
    col.addView(gap(8))
    col.addView(settingSwitch(T("Get messages when the app is closed"), Messenger.background) { on -> Messenger.background = on; Messenger.settingsChanged(); showSettings() })
    col.addView(tv(T("Privacy"), 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
    col.addView(settingSwitch(T("Allow screenshots"), shotsAllowed()) { on -> UiPrefs.setScreenshots(this, on); applyShotPolicy() })
    col.addView(settingNote(T("Lets you screenshot and screen-record the chat screens (they also show in the recent-apps preview). Calls, camera and QR codes stay blocked.")))
    col.addView(settingRow(T("App lock"), if (Messenger.appLock) T("On: asks for your fingerprint, face or PIN after ${when (Messenger.lockAfterMin) { 1 -> "1 minute"; 60 -> "1 hour"; else -> "${Messenger.lockAfterMin} minutes" }} away") else T("Off")) { appLockMenu() })
    val sd = Destruct.hours(this)
    col.addView(settingRow(T("Self-destruct"), if (sd == 0) T("Off") else T("On: everything in $APP_NAME on this phone is wiped if it isn't opened for ${destructLabel(sd)}"), if (sd == 0) white else red) { selfDestructMenu() })
    col.addView(settingSwitch(T("Show names in notifications"), Messenger.notifyNames) { on -> Messenger.notifyNames = on; Messenger.settingsChanged() })
    col.addView(settingSwitch(T("Show message text in notifications"), Messenger.notifyText) { on -> Messenger.notifyText = on; Messenger.settingsChanged() })
    col.addView(settingSwitch(T("Reply from the lock screen without unlocking"), ReplyReceiver.lockReply(this)) { on -> UiPrefs.setLockReply(this, on) })
    col.addView(settingNote(T("When off, replying to a notification asks for your phone passcode first. Always asked while app lock is on.")))
    if (Build.VERSION.SDK_INT >= 29 || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
        col.addView(tv(T("Phone calls"), 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
        val calling = PhoneCalls.isDefaultDialer(this)
        col.addView(settingRow(T("Use $APP_NAME for phone calls"), if (calling) T("On: $APP_NAME answers and places normal phone calls") else T("Off: calls use your usual phone app")) {
            if (!calling) askCallRole()
            else popup(T("Switch calling apps?"), T("Pick a different phone app in your phone's Default apps settings."), null, T("Cancel") to dim, T("Open settings") to white) { b ->
                if (b == 1) runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            }
        })
        col.addView(settingNote(T("Normal phone calls go through your carrier, not Tor, and aren't encrypted. $APP_NAME's own calls with friends are separate and stay private.")))
    }
    col.addView(tv(T("Texting"), 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
    val texting = Texts.isDefault(this)
    col.addView(settingRow(T("Use $APP_NAME for normal texts"), if (texting) T("On: $APP_NAME is this phone's texting app") else T("Off: texts stay in your usual texting app")) {
        if (!texting) askTextRole()
        else popup(T("Switch texting apps?"), T("Pick a different texting app in your phone's Default apps settings. Your texts stay on the phone either way."),
            null, T("Cancel") to dim, T("Open settings") to white) { b -> if (b == 1) runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
    })
    if (Texts.canRead(this)) col.addView(settingSwitch(T("Show texts together with chats"), Texts.mixed(this)) { on -> Texts.setMixed(this, on); textsTab = false })
    col.addView(settingSwitch(T("Keep saved messages in the $APP_NAME window only"), savedTellemOnly()) { on -> UiPrefs.setSavedTellemOnly(this, on) })
    col.addView(settingNote(T("Saved Messages appears after you save your first message. Off: it also shows in the Phone window's texts.")))
    if (Texts.canRead(this)) {
        val places = listOf("top" to T("Top of your texts"), "bottom" to T("Bottom of your texts"), "menu" to T("In the side menu"), "tab" to T("Their own Folders tab"))
        col.addView(settingRow(T("Archive, Codes and Spam folders"), T("Shown: ") + places.first { it.first == folderPlace() }.second.replaceFirstChar { it.lowercase() }) {
            pickList(T("Where should these folders go?"), T("Archive, Codes and Spam & blocked"), places.map { it.second }.toTypedArray()) { i ->
                UiPrefs.setFolderPlace(this, places[i].first); showSettings()
            }
        })
    }
    col.addView(settingNote(T("Normal texts go through your phone carrier, not Tor, and aren't encrypted: the carrier can read them. They're kept in the phone's own text storage, so self-destruct doesn't erase them. In $APP_NAME they always have green bubbles and an SMS tag.")))
    col.addView(settingNote(T("Like a normal text: who wrote and what they said (not while app lock is on). On the lock screen your phone's own setting decides how much shows. ") +
        T("Turn names off to just see \"$APP_NAME · New message\".")))
    // Calls when closed: each missing phone setting, tap to fix
    col.addView(tv(T("Calls"), 14f, dim, true).apply { setPadding(dp(20), dp(18), dp(20), dp(6)) })
    col.addView(settingSwitch(T("Wake-up pings (uses Google)"), Messenger.pushOn) { on ->
        if (!on) { setWakeUpPings(false); return@settingSwitch }
        popup(T("Turn on wake-up pings?"),
            T("Pings let calls and messages wake $APP_NAME when it's closed, like Telegram. They go through Google's push service, so:\n\n") +
            T("• Google learns that this phone uses $APP_NAME, and when mail arrives for it (never who it's from or what it says).\n") +
            T("• Your phone talks to Google directly, not through Tor, to set this up.\n") +
            T("• The server keeps a code for your phone next to your chats' mailboxes.\n\n") +
            T("Left off, $APP_NAME keeps its own connection open instead, and a call or message can arrive late while the phone is asleep."),
            null, T("Keep off") to dim, T("Turn on") to white) { i -> if (i == 1) setWakeUpPings(true); showSettings() }
    })
    col.addView(settingNote(T("Off by default. The ping itself is empty. A pings setup that doesn't need Google is planned for later.")))
    val fixes = ringFixes()
    if (fixes.isEmpty()) col.addView(tv(T("✓ Calls will ring this phone even when $APP_NAME is closed"), 15f, green).apply { setPadding(dp(20), dp(6), dp(20), dp(6)) })
    else {
        col.addView(settingNote(T("To ring like a normal phone call when $APP_NAME is closed, tap each one:")))
        for ((what, why, fix) in fixes) col.addView(settingRow("⚠  $what", why) { fix() })
    }
    col.addView(settingNote(T("Keeps a small \"connected\" notification. No Google push service is used.")))
    col.addView(settingSwitch(T("Read receipts"), Messenger.core.readReceipts) { on -> Messenger.core.readReceipts = on; Messenger.settingsChanged() })
    col.addView(settingNote(T("When on, friends see when you've read their messages, and you see when they've read yours.")))
    col.addView(settingSwitch(T("Show when I'm typing"), Messenger.core.showTyping) { on -> Messenger.core.showTyping = on; Messenger.settingsChanged() })
    col.addView(settingNote(T("When on, a friend sees \"typing…\" while you write to them.")))
    col.addView(settingSwitch(T("Show when I'm online"), Messenger.core.showSeen) { on -> Messenger.setShowSeen(on) })
    col.addView(settingNote(T("When on, friends see \"online\" while you have $APP_NAME open, and \"last seen\" after.")))
    col.addView(gap(20))
    col.addView(tv(T("$APP_NAME ${packageManager.getPackageInfo(packageName, 0).versionName}\nEvery chat is end-to-end encrypted. The server can't read your messages and keeps no logs."),
        13f, dim).apply { setPadding(dp(20), 0, dp(20), dp(20)) })
    setContentView(page(topBar(T("Settings"), left = icon(R.drawable.ic_back) { showChats() }), ScrollView(this).apply { addView(col) }))
    connLabel?.post(connTick)
}

// ---------- storage ----------

internal fun MainActivity.mb(n: Long) = when {
    n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / 1073741824f)
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

/** How much each chat's photos/videos/files take, clear them per chat, and auto-delete old ones. */
internal fun MainActivity.showStorage() {
    screen = Screen.STORAGE
    val core = Messenger.core
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    data class Item(val name: String, val avatar: ByteArray?, val msgs: List<Msg>, val bytes: Long)
    val items = synchronized(core) {
        core.contacts.map { Item(it.name, it.avatar, it.msgs.toList(), 0) } + core.groups.map { Item(it.name, null, it.msgs.toList(), 0) }
    }.map { it.copy(bytes = core.mediaBytes(it.msgs)) }.sortedByDescending { it.bytes }
    val total = items.sumOf { it.bytes }
    col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(14))
        addView(tv(mb(total), 28f, white, true))
        addView(tv(T("used by photos, videos and files on this phone. Your messages themselves take almost nothing."), 14f, dim).apply { setPadding(0, dp(2), 0, 0) })
    })

    col.addView(tv(T("AUTO-DOWNLOAD"), 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(2)) })
    col.addView(settingSwitch(T("Download photos, GIFs and videos automatically"), Messenger.autoMedia) { on -> Messenger.autoMedia = on; Messenger.settingsChanged(); Messenger.kick() })
    col.addView(settingNote(T("When off, each one shows as a box until you tap it, so nothing big downloads without you saying so. Voice messages and files still download.")))
    col.addView(tv(T("AUTO-DELETE MEDIA"), 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(8)) })
    val opts = listOf(T("Never") to 0, T("1 year") to 365, T("30 days") to 30)
    col.addView(LinearLayout(this).apply {
        setPadding(dp(16), 0, dp(16), 0)
        opts.forEach { (label, days) ->
            val on = Messenger.keepMediaDays == days
            addView(tv(label, 15f, if (on) ink else white, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply { setColor(if (on) white else Color.TRANSPARENT); setStroke(dp(1), if (on) white else raised); cornerRadius = dp(4).toFloat() }
                setOnClickListener {
                    Messenger.keepMediaDays = days; Messenger.settingsChanged()
                    Messenger.run({ Messenger.sweepMedia(force = true) }, { showStorage() })
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    })
    col.addView(settingNote(T("Photos, videos and files older than this are removed from this phone. The messages stay, with a small blurry preview so you can see something was sent.")).apply { setPadding(dp(20), dp(8), dp(20), dp(10)) })

    col.addView(tv(T("BY CHAT"), 14f, dim, true).apply { setPadding(dp(20), dp(12), dp(20), dp(4)) })
    val withMedia = items.filter { it.bytes > 0 }
    if (withMedia.isEmpty()) col.addView(settingNote(T("No photos, videos or files stored.")))
    withMedia.forEach { it ->
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)); background = pressable(); isClickable = true
            addView(avatar(it.name, 40, it.avatar))
            addView(tv(it.name, 16f, white).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv(mb(it.bytes), 15f, dim))
            setOnClickListener { _ ->
                popup(T("Clear photos and videos?"), T("Removes ${mb(it.bytes)} of photos, videos and files in \"${it.name}\" from this phone. The text stays, and each one keeps a small blurry preview. They can't be downloaded again."),
                    null, T("Cancel") to dim, T("Clear") to red) { i ->
                    if (i == 1) Messenger.run({ core.clearMedia(it.msgs) }, { it.msgs.forEach { m -> pictures.remove(m.id); gifCache.remove(m.id) }; toast(T("Cleared")); showStorage() })
                }
            }
        })
    }
    col.addView(gap(20))
    setContentView(page(topBar(T("Storage"), left = icon(R.drawable.ic_back) { showSettings() }), ScrollView(this).apply { addView(col) }))
}

// ---------- appearance ----------

internal fun MainActivity.showAppearance() {
    screen = Screen.APPEARANCE
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // live preview
    val preview = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(wall)
        setPadding(dp(8), dp(14), dp(8), dp(14))
    }
    val now = System.currentTimeMillis()
    val demo = Contact("preview", "Sam", Contact.FRIENDS, "00", null, "", "")
    preview.addView(bubble(demo, Msg("a", false, T("Hey! Did you pick a colour yet?"), now - 120_000, Msg.RECEIVED)))
    preview.addView(bubble(demo, Msg("b", true, T("Trying a few now"), now, Msg.SENT)))
    col.addView(preview)
    col.addView(settingSwitch(T("Animations"), UiPrefs.animations(this)) { on -> UiPrefs.setAnimations(this, on) })
    col.addView(settingNote(T("Sliding between screens and tabs, and the round button growing into a camera. Off: everything changes instantly.")))

    fun section(t: String) = col.addView(tv(t, 14f, dim, true).apply { setPadding(dp(20), dp(20), dp(20), dp(8)) })
    fun chips(vararg items: Pair<String, Boolean>, onPick: (Int) -> Unit) = col.addView(LinearLayout(this).apply {
        setPadding(dp(16), 0, dp(16), 0)
        items.forEachIndexed { i, (label, on) ->
            addView(tv(label, 15f, if (on) ink else white, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply {
                    setColor(if (on) white else Color.TRANSPARENT)
                    setStroke(dp(1), if (on) white else raised)
                    cornerRadius = dp(4).toFloat()
                }
                setOnClickListener { onPick(i); Messenger.settingsChanged(); showAppearance() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    })
    fun swatches(current: Int, onPick: (Int) -> Unit) = col.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            setPadding(dp(16), 0, dp(16), 0)
            for ((name, color) in Look.BUBBLES) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(if (color == current) 3 else 1), if (color == current) white else raised)
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(tv(name, 12f, if (color == current) white else dim).apply { setPadding(0, dp(4), 0, 0); maxLines = 1 })
                setOnClickListener { onPick(color); Messenger.settingsChanged(); showAppearance() }
            }, LinearLayout.LayoutParams(dp(66), -2))
        })
    })

    section(T("THEME"))
    chips(T("Dark") to (look.theme == "dark"), T("Light") to (look.theme == "light"), T("Match phone") to (look.theme == "system")) { i ->
        look.theme = listOf("dark", "light", "system")[i]
        // the default dark "their" bubble is heavy on a light background; swap it for the light one
        if (!dark && look.inn == Look.BUBBLES[1].second) look.inn = Look.BUBBLES.last().second
        if (dark && look.inn == Look.BUBBLES.last().second) look.inn = Look.BUBBLES[1].second
        applyBars()
    }
    section(T("YOUR BUBBLES"))
    swatches(look.out) { look.out = it }
    section(T("THEIR BUBBLES"))
    swatches(look.inn) { look.inn = it }
    section(T("BUBBLE CORNERS"))
    chips(T("Rounded") to !look.square, T("Square") to look.square) { look.square = it == 1 }
    section(T("MESSAGE TEXT SIZE"))
    chips(*Look.MSG_SIZES.map { "A" to (it == look.msg) }.toTypedArray()) { look.msg = Look.MSG_SIZES[it] }
    // make each "A" bigger than the last so the sizes read at a glance
    (col.getChildAt(col.childCount - 1) as LinearLayout).let { row ->
        for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).textSize = 12f + i * 3f
    }
    section(T("APP TEXT SIZE"))
    chips(*Look.UI_SIZES.map { (label, v) -> label to (v == look.ui) }.toTypedArray()) { look.ui = Look.UI_SIZES[it].second }
    col.addView(tv(T("Reset to default"), 15f, white, true).apply {
        setPadding(dp(20), dp(24), dp(20), dp(24))
        setOnClickListener { Messenger.look.load(org.json.JSONObject()); Messenger.look.out = Look.BUBBLES[0].second; Messenger.look.inn = Look.BUBBLES[1].second; Messenger.settingsChanged(); showAppearance() }
    })
    setContentView(page(topBar(T("Appearance"), left = icon(R.drawable.ic_back) { showSettings() }), ScrollView(this).apply { addView(col) }))
}


/** Direct mode gives up Tor's protection, so it is never switched on without this plain-words warning. */
internal fun MainActivity.confirmDirect(go: () -> Unit) {
    popup(T("Use Direct mode?"),
        T("Direct skips Tor. Before you turn it on, know what that means:\n\n") +
        T("• Cloudflare and the server can see your phone's internet address (roughly where you are) every time you connect.\n") +
        T("• They can see when you're online and how much you send. They can never read your messages: those stay end-to-end encrypted.\n") +
        T("• Anyone watching your network can see that you use $APP_NAME.\n") +
        T("• Calls still go through Tor.\n\n") +
        T("It's faster and works where Tor is blocked. If Tor works for you, stay on Tor."),
        null, T("Stay on Tor") to dim, T("I understand, use Direct") to red) { i -> if (i == 1) go() }
}

/** Turns wake-up pings on or off; off also tells Google to forget this phone's ping address. */
internal fun MainActivity.setWakeUpPings(on: Boolean) {
    Messenger.pushOn = on; Messenger.pushChosen = true
    Messenger.settingsChanged()
    PushService.register(this)
}


/** What the Language row shows: "Automatic (Русский)" when following the phone, else the chosen language's own name. */
internal fun MainActivity.languageLabel(): String {
    val own = I18n.SUPPORTED.firstOrNull { it.first == I18n.lang }?.second ?: T("English")
    return if (UiPrefs.language(this) == "auto") T("Automatic") + " ($own)" else own
}

/** Settings > Language: follow the phone, or pick one. Each language is named in itself so it can always be found. */
internal fun MainActivity.pickLanguage() {
    val codes = listOf("auto") + I18n.SUPPORTED.map { it.first }
    val names = listOf(T("Automatic (follow the phone)")) + I18n.SUPPORTED.map { it.second }
    pickList(T("Language"), T("The language of Tellem's own screens. Your messages are never translated."), names.toTypedArray()) { i ->
        UiPrefs.setLanguage(this, codes[i])
        Messenger.applyLanguage(codes[i])
        showSettings()
    }
}
