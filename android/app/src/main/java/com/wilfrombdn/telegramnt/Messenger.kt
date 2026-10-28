package com.wilfrombdn.telegramnt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android side of [Core]: saves everything to one file locked with a key in the
 * Android Keystore, finds the server by itself, and keeps the mailbox connection open.
 */
object Messenger {
    /** The server's permanent Tor address, baked in. The .onion IS the server's public key, so the app
     *  can only ever talk to the real server, and nothing (no Cloudflare, no directory fetch) sees an IP. */
    const val ONION = "http://b5yezgiz6sehrwie3qcnmplgbxcc7lfrclqukbmlnya332gh53wplzad.onion"

    lateinit var core: Core
    var background = true
    val look = Look()
    /** A user-chosen file (via the system picker) we keep an encrypted full backup in, so chats
     *  survive uninstall/reinstall. Empty = off. Nothing goes to the server. */
    var recoveryUri: String = ""
    /** How often each emoji was used (reactions + emoji panel); fills the last slots of the react bar. */
    val emojiUse = HashMap<String, Int>()
    /** Chat folders per account on this phone (not in any backup). */
    val allFolders = HashMap<String, MutableList<Folder>>()
    val folders: MutableList<Folder> get() = synchronized(allFolders) { allFolders.getOrPut(core.me?.user ?: "") { mutableListOf() } }
    /** Where you were when the app last went to the background, so reopening puts you back there. */
    var spot = Spot()
    /** Auto-delete photos/videos/files older than this many days from this phone (0 = never). */
    var keepMediaDays = 0
    private var lastMediaSweep = 0L

    /** Applies the auto-delete setting (at most hourly unless [now] is set). */
    fun sweepMedia(force: Boolean = false) {
        if (keepMediaDays <= 0 || !signedIn) return
        val t = System.currentTimeMillis()
        if (!force && t - lastMediaSweep < 3600_000L) return
        lastMediaSweep = t
        core.clearMedia(core.allMsgs(), t - keepMediaDays * 86_400_000L)
    }
    /** False while the server can't be reached (top bar says "Connecting…"). */
    @Volatile var connected = false
        private set
    /** Shown on the welcome screen after being signed out remotely. */
    var signedOutReason: String? = null
    @Volatile var visible = false
    var onNotify: ((Contact) -> Unit)? = null

    private lateinit var app: Context
    private var disk: Aead? = null
    private var file: File? = null
    private val recoveryPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var ready = false
    private val ui = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    val io = Executors.newCachedThreadPool()

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        Crypto.ensureInit()
        app = ctx.applicationContext
        disk = AndroidKeysetManager.Builder()
            .withSharedPref(app, "disk", "telegramnt_keys")
            .withKeyTemplate(KeyTemplates.get(Crypto.AEAD_TEMPLATE))
            .withMasterKeyUri("android-keystore://telegramnt_master")
            .build().keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        file = File(app.filesDir, "store2.bin")
        File(app.filesDir, "store.bin").delete() // 1.0 test chats; that version had no accounts
        Tor.start(app)                                  // start the embedded Tor daemon (bootstraps in the background)
        start(Core(ONION) { Tor.socksPort() })          // all traffic goes to the .onion through Tor's SOCKS port
        load()
    }

    /** For screenshots/tests: no Keystore, nothing saved. */
    fun initForPreview(c: Core) {
        start(c)
        ready = true
    }

    private fun start(c: Core) {
        core = c
        val dir = file?.let { File(it.parentFile, "blobs").apply { mkdirs() } }
        if (dir != null) {
            // attachments are stored still locked with their own keys (the keys live in the locked store)
            core.saveBlob = { id, bytes -> File(dir, id).writeBytes(bytes) }
            core.loadBlob = { id -> File(dir, id).takeIf { it.exists() }?.readBytes() }
            core.deleteBlob = { id -> File(dir, id).delete() }
            core.blobSize = { id -> File(dir, id).length() }
        }
        core.onChange = { save(); changed(); ui.post { scheduleExpiry() } }
        core.onNew = { contact -> if (!visible) ui.post { onNotify?.invoke(contact) } }
        core.onCall = { k -> ui.post { callChanged(k) } }
    }

    val signedIn get() = core.me != null

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        ui.post { ls.forEach { it() } }
    }

    // ---------- typing / online ----------

    private val typingSent = HashMap<String, Long>()
    private var onlineSent = 0L

    /** Called as you type to [c]: tells them at most every 4 s. */
    fun typing(c: Contact) {
        val now = System.currentTimeMillis()
        if (!core.showTyping || now - (typingSent[c.user] ?: 0) < 4000) return
        typingSent[c.user] = now
        io.execute { core.sendTyping(c) }
    }

    /** Typing stopped (message sent / box cleared): the next keystroke tells them again straight away. */
    fun typingDone(c: Contact) { typingSent.remove(c.user) }

    private val heartbeat = object : Runnable {
        override fun run() { if (visible) { onlineSent = 0; appShown() } }
    }

    /** App came on screen: friends see "online" (refreshed every 4 min while it stays open). */
    fun appShown() {
        if (!signedIn || !core.showSeen) return
        ui.removeCallbacks(heartbeat)
        ui.postDelayed(heartbeat, 4 * 60_000L)
        val now = System.currentTimeMillis()
        if (now - onlineSent < 60_000) return   // flipping in and out of the app: don't spam friends
        onlineSent = now
        io.execute { core.sendPresence(true) }
    }

    /** App left the screen: friends see "last seen" from now. */
    fun appHidden() {
        ui.removeCallbacks(heartbeat)
        if (!signedIn || !core.showSeen || onlineSent == 0L) return
        onlineSent = 0
        io.execute { core.sendPresence(false) }
    }

    fun setShowSeen(on: Boolean) {
        core.showSeen = on
        settingsChanged()
        if (on) { onlineSent = 0; appShown() } else { ui.removeCallbacks(heartbeat); onlineSent = 0; io.execute { core.sendPresence(false, hide = true) } }
    }

    // redraw when someone's "typing…" / "online" runs out
    private val expire = Runnable { changed(); scheduleExpiry() }
    private fun scheduleExpiry() {
        ui.removeCallbacks(expire)
        val now = System.currentTimeMillis()
        val next = synchronized(core) { core.contacts.flatMap { listOf(it.theirTypingUntil, it.theirOnlineUntil) } }.filter { it > now }.minOrNull() ?: return
        ui.postDelayed(expire, next - now + 200)
    }

    // ---------- calls ----------

    val callListeners = java.util.concurrent.CopyOnWriteArrayList<(Core.Call) -> Unit>()
    private var ringtone: android.media.Ringtone? = null
    private var ringback: android.media.ToneGenerator? = null
    private const val INCOMING_NOTE = 90

    /** Reacts to every call change: rings, shows the call screen or alert, stops sound, and times out. */
    private fun callChanged(k: Core.Call) {
        if (!::app.isInitialized) return
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        if (k.phase == Core.Call.INCOMING) {
            startRinging()
            if (visible) app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            nm.notify(INCOMING_NOTE, CallActivity.incomingNotice(app, k))
            ui.postDelayed({ if (core.call === k && k.phase == Core.Call.INCOMING) core.endCall(k, "missed") }, 65_000)
        } else stopRinging()
        if (k.phase != Core.Call.INCOMING) nm.cancel(INCOMING_NOTE)
        // the caller hears a ringing tone until they answer
        if (k.outgoing && k.phase == Core.Call.RINGING) {
            if (ringback == null) ringback = runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
        } else { ringback?.release(); ringback = null }
        if (k.phase == Core.Call.ENDED) {
            CallAudio.stop()
            app.stopService(Intent(app, CallService::class.java))
        }
        callListeners.forEach { it(k) }
    }

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        ringtone = runCatching {
            android.media.RingtoneManager.getRingtone(app, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull()
        runCatching {
            app.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { app.getSystemService(android.os.Vibrator::class.java)?.cancel() }
    }

    /** The call screen opens at once ("Calling…"); the invite goes out through Tor meanwhile. */
    fun startCall(ctx: Context, c: Contact, fail: (Exception) -> Unit, problem: (Exception) -> String) {
        val k = try { core.prepareCall(c) } catch (e: Exception) { fail(e); return }
        ctx.startActivity(Intent(ctx, CallActivity::class.java))
        runCatching { ctx.startForegroundService(Intent(ctx, CallService::class.java)) }
        CallAudio.start(ctx, k)
        ui.postDelayed({ if (core.call === k && k.phase <= Core.Call.RINGING) io.execute { core.hangUp("noanswer") } }, 60_000)
        io.execute {
            runCatching { core.offerCall(c, k, problem) }
        }
    }

    fun answer(ctx: Context) {
        val k = core.call ?: return
        core.answerCall()
        runCatching { ctx.startForegroundService(Intent(ctx, CallService::class.java)) }
        CallAudio.start(ctx, k)
    }

    fun hangUp() {
        CallAudio.stop()   // marks it as hung up on purpose, so the summary isn't "couldn't connect"
        io.execute { core.hangUp() }
    }

    // ---------- saving ----------

    private fun load() {
        val f = file ?: return
        val d = disk ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(String(d.decrypt(f.readBytes(), "store".toByteArray())))
            background = o.optBoolean("background", true)
            recoveryUri = o.optString("recoveryUri")
            o.optJSONObject("look")?.let { look.load(it) }
            loadExtras(o)
            core.loadJson(o)
        } catch (e: Exception) {
            // unreadable (e.g. keystore reset): start signed out; the backup restores friends
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        val d = disk ?: return
        val o = extras(core.toJson().put("background", background).put("look", look.json()).put("recoveryUri", recoveryUri))
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(d.encrypt(o.toString().toByteArray(), "store".toByteArray()))
        tmp.renameTo(f)
        scheduleRecovery()
    }

    /** Writes the encrypted full backup to the user's chosen file, at most one pending at a time. */
    private fun scheduleRecovery() {
        if (recoveryUri.isEmpty() || !signedIn) return
        if (!recoveryPending.compareAndSet(false, true)) return
        io.execute {
            Thread.sleep(1500) // coalesce bursts of saves into one write
            recoveryPending.set(false)
            writeRecovery()
        }
    }

    /** Returns null on success, or a message if the backup file couldn't be written. */
    fun writeRecovery(): String? {
        if (recoveryUri.isEmpty() || !signedIn) return null
        return try {
            app.contentResolver.openOutputStream(Uri.parse(recoveryUri), "wt")?.use { it.write(core.fullBackup()) }
            null
        } catch (e: Exception) {
            "Couldn't update your recovery file. Pick it again in Settings."
        }
    }

    fun useEmoji(e: String) { if (e.isNotEmpty()) synchronized(emojiUse) { emojiUse[e] = (emojiUse[e] ?: 0) + 1 } }

    private fun extras(o: JSONObject): JSONObject {
        o.put("emojiUse", JSONObject(synchronized(emojiUse) { emojiUse.toMap() }))
        o.put("folders", JSONObject().also { j -> synchronized(allFolders) { allFolders.forEach { (u, fs) -> j.put(u, Folder.toJson(fs)) } } })
        o.put("keepMediaDays", keepMediaDays)
        return o.put("spot", spot.json())
    }

    fun loadExtras(o: JSONObject) {
        o.optJSONObject("emojiUse")?.let { j -> synchronized(emojiUse) { j.keys().forEach { emojiUse[it] = j.optInt(it) } } }
        o.optJSONObject("folders")?.let { j -> synchronized(allFolders) { j.keys().forEach { allFolders[it] = Folder.fromJson(j.optJSONArray(it)) } } }
        o.optJSONObject("spot")?.let { spot = Spot.from(it) }
        keepMediaDays = o.optInt("keepMediaDays", 0)
    }

    fun settingsChanged() {
        save()
        changed()
    }

    // ---------- account (keeps chats across log out / log in on this phone) ----------

    private fun archiveFile(user: String): File? =
        file?.let { File(it.parentFile, "archive-" + Crypto.sha256(user.toByteArray()).hex().take(16) + ".bin") }

    /** Logs out but keeps this account's chats AND Signal keys, locked, for the next log in on this phone. */
    fun logOut() {
        val m = core.me ?: return
        val d = disk
        val f = archiveFile(m.user)
        if (d != null && f != null) {
            val o = org.json.JSONObject().put("contacts", core.toJson().getJSONArray("contacts"))
                .put("signal", m.signal.toJson())   // keep the full Signal store so a log-in here keeps the keys/sessions
            f.writeBytes(d.encrypt(o.toString().toByteArray(), "archive|${m.user}".toByteArray()))
        }
        core.signOut()
    }

    fun logIn(user: String, password: String) {
        core.login(user, password, archivedStore(user))   // reuse this phone's kept keys if we have them
        restoreArchive(user)
    }

    /** The full Signal store a previous log-out on this phone kept for [user], or null (new phone). */
    private fun archivedStore(user: String): SignalStore? {
        val f = archiveFile(user) ?: return null
        val d = disk ?: return null
        if (!f.exists()) return null
        return try {
            org.json.JSONObject(String(d.decrypt(f.readBytes(), "archive|$user".toByteArray())))
                .optJSONObject("signal")?.let { SignalStore.fromJson(it) }
        } catch (e: Exception) { null }
    }

    fun deleteAccount(password: String) {
        val user = core.me?.user ?: return
        core.deleteAccount(password)
        archiveFile(user)?.delete()
    }

    private fun restoreArchive(user: String) {
        val f = archiveFile(user) ?: return
        val d = disk ?: return
        if (!f.exists()) return
        val old = try {
            org.json.JSONObject(String(d.decrypt(f.readBytes(), "archive|$user".toByteArray()))).getJSONArray("contacts")
        } catch (e: Exception) { return }
        synchronized(core) {
            for (i in 0 until old.length()) {
                val was = Contact.from(old.getJSONObject(i))
                val c = core.contact(was.user) ?: continue
                val now = c.msgs.filter { m -> m.state != Msg.NOTE && was.msgs.none { it.id == m.id } }
                c.msgs.clear()
                c.msgs.addAll(was.msgs)
                c.msgs.addAll(now)
                if (c.avatar == null) c.avatar = was.avatar
                c.verified = c.verified || was.verified
            }
        }
        f.delete()
        core.onChange()
    }

    // ---------- finding the server ----------

    private fun setConnected(on: Boolean) {
        if (connected != on) { connected = on; changed() }
    }

    /** Checks the server answers over Tor. Returns false while Tor is still bootstrapping (fail-closed:
     *  the request only ever goes through Tor, never a fallback that would expose an IP). Off-main only. */
    fun findServer(): Boolean {
        val ok = core.relay.ping()
        setConnected(ok)
        return ok
    }

    private var finding: Thread? = null

    /** Before sign-in: keep trying the server (via Tor) quietly until it answers. */
    @Synchronized
    fun connectInBackground() {
        if (connected || finding?.isAlive == true) return
        Tor.start(app)   // make sure the daemon is coming up
        finding = Thread({
            var wait = 3_000L
            while (!signedIn && !findServer()) {
                try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }, "find-server").apply { isDaemon = true; start() }
    }

    // ---------- background work ----------

    /** Runs [task] off the main thread, then [done] or [fail] back on it. */
    fun run(task: () -> Unit, done: () -> Unit = {}, fail: (Exception) -> Unit = {}) {
        io.execute {
            try {
                task()
                ui.post(done)
            } catch (e: Exception) {
                ui.post { fail(e) }
            }
        }
    }

    fun send(c: Contact, text: String, reply: JSONObject? = null) {
        val m = core.queue(c, text, reply)
        io.execute { core.deliver(c, m) }
    }

    fun sendGroupFile(g: Group, name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
        val m = core.queueGroupFile(g, name, mime, data, thumb)
        io.execute { core.deliverGroupFile(g, m) }
    }

    fun react(c: Contact, msg: Msg, emoji: String) { useEmoji(emoji); io.execute { core.react(c, msg, emoji) } }
    fun reactGroup(g: Group, msg: Msg, emoji: String) { useEmoji(emoji); io.execute { core.reactGroup(g, msg, emoji) } }

    fun sendFile(c: Contact, name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
        val m = core.queueFile(c, name, mime, data, thumb)
        io.execute { core.deliver(c, m) }
    }

    fun retry(c: Contact, m: Msg) {
        m.state = Msg.SENDING
        changed()
        io.execute { core.deliver(c, m) }
    }

    /** New mailbox: break off the current wait so the next round includes it. */
    fun kick() = core.relay.kick()

    private var loop: Thread? = null

    @Synchronized
    fun startLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({
            var backoff = 2_000L
            var lastCheck = 0L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (!signedIn) { Thread.sleep(3_000); continue }
                    if (System.currentTimeMillis() - lastCheck > 5 * 60_000) {
                        lastCheck = System.currentTimeMillis()
                        if (!core.sessionOk()) {
                            signedOutReason = "You signed in on another phone, so this one was signed out."
                            core.signOut()
                            continue
                        }
                        runCatching { core.keepKeysStocked() }
                    }
                    if (core.backupDirty) runCatching { core.uploadBackup() }
                    if (!connected && !findServer()) throw java.io.IOException("offline")
                    core.poll(25)
                    core.purgeExpired()
                    sweepMedia()
                    setConnected(true)
                    backoff = 2_000L
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (!(e is java.io.IOException && e.message?.contains("closed") == true)) setConnected(false)
                    try { Thread.sleep(backoff) } catch (i: InterruptedException) { return@Thread }
                    // Retry quickly: the usual reason we're not connected is Tor still building its first
                    // circuit (~a minute). A short cap means we connect within seconds of Tor becoming ready,
                    // instead of being stuck in a long backoff. The server address is fixed (the onion), so
                    // there's nothing to re-look-up.
                    backoff = (backoff * 2).coerceAtMost(8_000L)
                }
            }
        }, "mailbox").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopLoop() {
        loop?.interrupt()
        kick()
        loop = null
    }
}

/** Appearance choices (Settings > Appearance). Kept on this phone only. */
class Look {
    var out = BUBBLES[0].second
    var inn = BUBBLES[1].second
    var ui = 1f          // scales all app text
    var msg = 16f        // message text size in sp
    var square = false   // square bubble corners
    var theme = "dark"   // "dark", "light" or "system" (match the phone)

    fun json(): JSONObject = JSONObject().put("out", out).put("in", inn).put("ui", ui.toDouble())
        .put("msg", msg.toDouble()).put("square", square).put("theme", theme)

    fun load(o: JSONObject) {
        out = o.optInt("out", out)
        inn = o.optInt("in", inn)
        ui = o.optDouble("ui", 1.0).toFloat().coerceIn(0.8f, 1.5f)
        msg = o.optDouble("msg", 16.0).toFloat().coerceIn(12f, 28f)
        square = o.optBoolean("square")
        theme = o.optString("theme", "dark").takeIf { it in listOf("dark", "light", "system") } ?: "dark"
    }

    companion object {
        val BUBBLES = listOf(
            "Graphite" to 0xFF3A3A3C.toInt(), "Charcoal" to 0xFF242426.toInt(), "Black" to 0xFF000000.toInt(),
            "Blue" to 0xFF2B5278.toInt(), "Green" to 0xFF2E5E3E.toInt(), "Teal" to 0xFF1F5C5C.toInt(),
            "Purple" to 0xFF4A3A6B.toInt(), "Red" to 0xFF6B2E2E.toInt(), "Amber" to 0xFF8A5A12.toInt(),
            "White" to 0xFFE8E8EA.toInt(),
        )
        val UI_SIZES = listOf("Small" to 0.9f, "Default" to 1f, "Large" to 1.15f, "Larger" to 1.3f)
        val MSG_SIZES = listOf(14f, 16f, 18f, 20f, 23f)

        /** Black text on light bubbles, white on dark. */
        fun textOn(color: Int): Int {
            val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
            return if (0.299 * r + 0.587 * g + 0.114 * b > 160) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        }
    }
}

/** A chat-list folder: just a named, collapsible bunch of chats ("u:<user>" / "g:<group id>"). */
class Folder(val id: String, var name: String, var open: Boolean = true, val items: MutableList<String> = mutableListOf()) {
    companion object {
        fun toJson(fs: List<Folder>) = org.json.JSONArray().also { a ->
            fs.forEach { f -> a.put(JSONObject().put("id", f.id).put("n", f.name).put("open", f.open).put("items", org.json.JSONArray(f.items))) }
        }
        fun fromJson(a: org.json.JSONArray?): MutableList<Folder> {
            val out = mutableListOf<Folder>()
            if (a != null) for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val items = o.optJSONArray("items")
                out += Folder(o.getString("id"), o.optString("n"), o.optBoolean("open", true),
                    MutableList(items?.length() ?: 0) { items!!.getString(it) })
            }
            return out
        }
    }
}

/** The open chat ("chat"/"group" + id) and the message at the top of the screen, or atBottom. */
data class Spot(val screen: String = "", val id: String = "", val anchor: String = "", val offset: Int = 0, val atBottom: Boolean = true) {
    fun json(): JSONObject = JSONObject().put("s", screen).put("id", id).put("a", anchor).put("o", offset).put("b", atBottom)
    companion object {
        fun from(o: JSONObject) = Spot(o.optString("s"), o.optString("id"), o.optString("a"), o.optInt("o"), o.optBoolean("b", true))
    }
}
