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
    /** Settings > Connection: Direct (through the Cloudflare tunnel, faster, works where Tor is blocked) instead of Tor. */
    @Volatile var direct = false
    /** The Direct address last read from server.json (it changes whenever the laptop server restarts). */
    @Volatile var directUrl = ""
    /** Settings > Calls: empty wake-up pings through Google's push service (on unless switched off). */
    @Volatile var pushOn = true

    fun setPushToken(t: String) { core.pushToken = t; kick() }

    /** Asked once to set the phone up so calls ring when quipu is closed. */
    var ringSetupAsked = false
    val look = Look()
    /** A user-chosen file (via the system picker) we keep an encrypted full backup in, so chats
     *  survive uninstall/reinstall. Empty = off. Nothing goes to the server. */
    var recoveryUri: String = ""
    /** How often each emoji was used (reactions + emoji panel); fills the last slots of the react bar. */
    val emojiUse = HashMap<String, Int>()
    /** Chat folders per account on this phone (not in any backup). */
    val allFolders = HashMap<String, MutableList<Folder>>()
    val folders: MutableList<Folder> get() = synchronized(allFolders) { allFolders.getOrPut(core.me?.user ?: "") { mutableListOf() } }
    /** Pinned chats ("u:<user>" / "g:<group>"), shown above everything in this order. Per account, this phone only. */
    val allPins = HashMap<String, MutableList<String>>()
    val pins: MutableList<String> get() = synchronized(allPins) { allPins.getOrPut(core.me?.user ?: "") { mutableListOf() } }
    /** Muted chats (no notifications), same keys. Per account, this phone only. */
    val allMuted = HashMap<String, MutableSet<String>>()
    val muted: MutableSet<String> get() = synchronized(allMuted) { allMuted.getOrPut(core.me?.user ?: "") { mutableSetOf() } }
    /** My stickers ("<id>|<mime>"), newest first; the images are kept encrypted in files/stickers. Per account. */
    val allStickers = HashMap<String, MutableList<String>>()
    val stickers: MutableList<String> get() = synchronized(allStickers) { allStickers.getOrPut(core.me?.user ?: "") { mutableListOf() } }
    private fun stickerFile(id: String) = File(File(app.filesDir, "stickers").apply { mkdirs() }, id.substringBefore('|'))

    fun addSticker(bytes: ByteArray, mime: String) {
        val id = Crypto.randomHex(16) + "|" + mime
        stickerFile(id).writeBytes(disk!!.encrypt(bytes, "sticker".toByteArray()))
        stickers.add(0, id)
        settingsChanged()
    }

    fun stickerBytes(id: String): ByteArray? = runCatching { disk!!.decrypt(stickerFile(id).readBytes(), "sticker".toByteArray()) }.getOrNull()

    fun removeSticker(id: String) { stickers.remove(id); stickerFile(id).delete(); settingsChanged() }

    /** Archived chats (hidden from the main list), same keys. Per account, this phone only. */
    val allArchived = HashMap<String, MutableSet<String>>()
    val archived: MutableSet<String> get() = synchronized(allArchived) { allArchived.getOrPut(core.me?.user ?: "") { mutableSetOf() } }
    /** Unsent text left in a chat's message box, same keys. Per account, this phone only. */
    val allDrafts = HashMap<String, MutableMap<String, String>>()
    val drafts: MutableMap<String, String> get() = synchronized(allDrafts) { allDrafts.getOrPut(core.me?.user ?: "") { mutableMapOf() } }
    /** Where you were when the app last went to the background, so reopening puts you back there. */
    var spot = Spot()
    /** Auto-delete photos/videos/files older than this many days from this phone (0 = never). */
    var keepMediaDays = 0
    /** Download photos, GIFs and videos as they arrive (off = each waits for a tap, to save data). */
    var autoMedia = true

    /** Photos, GIFs and videos (incl. round video messages): the ones [autoMedia] covers. Voice and files always download. */
    fun isMedia(f: JSONObject): Boolean = f.optString("m").let { it.startsWith("image/") || it.startsWith("video/") }
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
    var onNotifyMention: ((Group) -> Unit)? = null

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
        applyRoute()
        PushService.register(app)
        // once only: every screen and service calls init, and running it again swapped in a fresh Core,
        // which dropped a call in progress (the call screen saw "no call" and closed; Hang up did nothing)
        ready = true
    }

    /** For screenshots/tests: no Keystore, nothing saved. */
    fun initForPreview(c: Core) {
        start(c)
        ready = true
    }

    private fun start(c: Core) {
        core = c
        core.autoFetch = { f -> autoMedia || !isMedia(f) }
        val dir = file?.let { File(it.parentFile, "blobs").apply { mkdirs() } }
        if (dir != null) {
            // attachments are stored still locked with their own keys (the keys live in the locked store)
            core.saveBlob = { id, bytes -> File(dir, id).writeBytes(bytes) }
            core.loadBlob = { id -> File(dir, id).takeIf { it.exists() }?.readBytes() }
            core.deleteBlob = { id -> File(dir, id).delete() }
            core.blobSize = { id -> File(dir, id).length() }
        }
        core.onChange = { save(); changed(); ui.post { scheduleExpiry() } }
        core.onNew = { contact -> if (!visible && "u:${contact.user}" !in muted) ui.post { onNotify?.invoke(contact) } }
        core.onCall = { k -> ui.post { callChanged(k) } }
        core.onMention = { g -> if (!visible && "g:${g.id}" !in muted) ui.post { onNotifyMention?.invoke(g) } }
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

    /** Called as you type in group [g]: tells the group at most every 5 s. */
    fun groupTyping(g: Group) {
        val now = System.currentTimeMillis()
        if (!core.showTyping || now - (typingSent["g:${g.id}"] ?: 0) < 5000) return
        typingSent["g:${g.id}"] = now
        io.execute { core.sendGroupTyping(g) }
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
        val next = synchronized(core) { core.contacts.flatMap { listOf(it.theirTypingUntil, it.theirOnlineUntil) } + core.groups.flatMap { it.typing.values } }
            .filter { it > now }.minOrNull() ?: return
        ui.postDelayed(expire, next - now + 200)
    }

    // ---------- calls ----------

    val callListeners = java.util.concurrent.CopyOnWriteArrayList<(Core.Call) -> Unit>()
    /** The latest call, kept after it ends so the call screen can still show how it ended. */
    @Volatile var lastCall: Core.Call? = null
    private var ringtone: android.media.Ringtone? = null
    private var ringback: android.media.ToneGenerator? = null
    private const val INCOMING_NOTE = 90

    /** Reacts to every call change: rings, shows the call screen or alert, stops sound, and times out. */
    private fun callChanged(k: Core.Call) {
        if (!::app.isInitialized) return
        lastCall = k
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        if (k.phase == Core.Call.INCOMING) {
            startRinging()
            if (visible) app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            nm.notify(INCOMING_NOTE, CallActivity.incomingNotice(app, k))
            ui.postDelayed({ if (core.call === k && k.phase == Core.Call.INCOMING) core.endCall(k, "missed") }, Core.RING_MS)
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
        lastCall = k
        ctx.startActivity(Intent(ctx, CallActivity::class.java))
        runCatching { ctx.startForegroundService(Intent(ctx, CallService::class.java)) }
        CallAudio.start(ctx, k)
        ui.postDelayed({
            if (core.call === k && k.phase <= Core.Call.RINGING) {
                if (!connected) k.problem = "Couldn't connect. Check your internet and try again."
                io.execute { core.hangUp("noanswer") }
            }
        }, Core.RING_MS)
        io.execute {
            // still connecting (e.g. Tor warming up): keep trying for a while instead of failing at once;
            // the call screen says "Calling…" meanwhile
            val until = System.currentTimeMillis() + 45_000
            while (true) {
                if (core.call !== k || k.phase == Core.Call.ENDED) return@execute   // hung up meanwhile
                try { core.sendOffer(c, k); core.offerDelivered(k); return@execute } catch (e: Exception) {
                    if (e is UserError || System.currentTimeMillis() > until) {
                        k.problem = problem(e); core.endCall(k, "failed"); return@execute
                    }
                }
                try { Thread.sleep(3_000) } catch (e: InterruptedException) { return@execute }
            }
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
        o.put("keepMediaDays", keepMediaDays).put("autoMedia", autoMedia)
        o.put("direct", direct).put("directUrl", directUrl).put("ringSetupAsked", ringSetupAsked).put("pushOn", pushOn)
        o.put("pins", JSONObject().also { j -> synchronized(allPins) { allPins.forEach { (u, ps) -> j.put(u, org.json.JSONArray(ps)) } } })
        o.put("stickers", JSONObject().also { j -> synchronized(allStickers) { allStickers.forEach { (u, ss) -> j.put(u, org.json.JSONArray(ss)) } } })
        o.put("archived", JSONObject().also { j -> synchronized(allArchived) { allArchived.forEach { (u, ms) -> j.put(u, org.json.JSONArray(ms.toList())) } } })
        o.put("muted", JSONObject().also { j -> synchronized(allMuted) { allMuted.forEach { (u, ms) -> j.put(u, org.json.JSONArray(ms.toList())) } } })
        o.put("drafts", JSONObject().also { j -> synchronized(allDrafts) { allDrafts.forEach { (u, ds) -> j.put(u, JSONObject(ds.toMap() as Map<*, *>)) } } })
        return o.put("spot", spot.json())
    }

    fun loadExtras(o: JSONObject) {
        o.optJSONObject("emojiUse")?.let { j -> synchronized(emojiUse) { j.keys().forEach { emojiUse[it] = j.optInt(it) } } }
        o.optJSONObject("folders")?.let { j -> synchronized(allFolders) { j.keys().forEach { allFolders[it] = Folder.fromJson(j.optJSONArray(it)) } } }
        o.optJSONObject("spot")?.let { spot = Spot.from(it) }
        keepMediaDays = o.optInt("keepMediaDays", 0); autoMedia = o.optBoolean("autoMedia", true)
        direct = o.optBoolean("direct"); directUrl = o.optString("directUrl"); ringSetupAsked = o.optBoolean("ringSetupAsked"); pushOn = o.optBoolean("pushOn", true)
        o.optJSONObject("pins")?.let { j -> synchronized(allPins) { j.keys().forEach { u ->
            val a = j.optJSONArray(u) ?: return@forEach
            allPins[u] = MutableList(a.length()) { a.getString(it) }
        } } }
        o.optJSONObject("stickers")?.let { j -> synchronized(allStickers) { j.keys().forEach { u ->
            val a = j.optJSONArray(u) ?: return@forEach
            allStickers[u] = MutableList(a.length()) { a.getString(it) }
        } } }
        o.optJSONObject("archived")?.let { j -> synchronized(allArchived) { j.keys().forEach { u ->
            val a = j.optJSONArray(u) ?: return@forEach
            allArchived[u] = MutableList(a.length()) { a.getString(it) }.toMutableSet()
        } } }
        o.optJSONObject("muted")?.let { j -> synchronized(allMuted) { j.keys().forEach { u ->
            val a = j.optJSONArray(u) ?: return@forEach
            allMuted[u] = MutableList(a.length()) { a.getString(it) }.toMutableSet()
        } } }
        o.optJSONObject("drafts")?.let { j -> synchronized(allDrafts) { j.keys().forEach { u ->
            val d = j.optJSONObject(u) ?: return@forEach
            allDrafts[u] = d.keys().asSequence().associateWith { d.optString(it) }.toMutableMap()
        } } }
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
        var ok = core.relay.ping()
        // Direct: the tunnel address changes when the server restarts; look up the new one and try again
        if (!ok && direct && refreshDirect()) ok = core.relay.ping()
        val now = System.currentTimeMillis()
        if (ok || Tor.socksPort() == null) lastServerOk = now   // only count time Tor was ready and the server still didn't answer
        if (ok) torRestarts = 0
        else if (Tor.socksPort() != null && core.call.let { it == null || it.phase == Core.Call.ENDED } &&
            Tor.restartDue(now, lastServerOk, lastTorRestart, torRestarts)) {
            // Tor is up but the server still doesn't answer: Tor may be stuck on dead routes from an outage
            lastTorRestart = now; torRestarts++
            Tor.restart()
        }
        setConnected(ok)
        return ok
    }

    @Volatile private var lastServerOk = System.currentTimeMillis()
    @Volatile private var lastTorRestart = 0L
    @Volatile private var torRestarts = 0

    // ---------- Tor or Direct ----------

    /** Points the app at the .onion (Tor) or the Direct address. Calls always stay on Tor. */
    fun applyRoute() {
        val r = core.relay
        if (direct && directUrl.isNotEmpty()) { r.server = directUrl; r.callServer = ONION }
        else { r.server = ONION; r.callServer = null }
    }

    /** Reads the current Direct address from the website (straight, not through Tor). */
    fun refreshDirect(): Boolean {
        val out = java.io.ByteArrayOutputStream()
        runCatching {
            Net.download(Net.Route("wilfrombdn.github.io", 443, true, null), "/telegramnt/server.json?t=${System.currentTimeMillis() / 60_000}", out)
        }.onFailure { return false }
        val url = runCatching { JSONObject(String(out.toByteArray())).optString("server") }.getOrDefault("")
        if (!Regex("^https://[a-z0-9-]+\\.trycloudflare\\.com$").matches(url)) return false
        if (url != directUrl) { directUrl = url; io.execute { save() } }
        applyRoute()
        return true
    }

    fun useDirect(on: Boolean) {
        direct = on
        applyRoute()
        settingsChanged()
        setConnected(false)
        io.execute { if (on && directUrl.isEmpty()) refreshDirect(); findServer() }
        kick()
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

    /** [at] > 0 schedules it instead (it waits on this phone until then). */
    fun send(c: Contact, text: String, reply: JSONObject? = null, lp: JSONObject? = null, silent: Boolean = false, at: Long = 0) {
        val m = core.queue(c, text, reply, lp)
        m.silent = silent
        if (at > 0) { core.schedule(m, at); return }
        io.execute { core.deliver(c, m) }
    }

    fun sendGroup(g: Group, text: String, reply: JSONObject? = null, lp: JSONObject? = null, silent: Boolean = false, at: Long = 0) {
        val m = core.queueGroup(g, text, reply, lp = lp)
        m.silent = silent
        if (at > 0) { core.schedule(m, at); return }
        io.execute { core.deliverGroup(g, m) }
    }

    /** Shares a location ({la, lo, acc}) with a friend or group. */
    fun sendLocation(c: Contact?, g: Group?, loc: JSONObject) {
        if (c != null) { val m = core.queue(c, ""); m.loc = loc; io.execute { core.deliver(c, m) } }
        else if (g != null) { val m = core.queueGroup(g, ""); m.loc = loc; io.execute { core.deliverGroup(g, m) } }
    }

    /** Sends scheduled messages that are due (or [only] that one, right now). */
    fun sendScheduled(only: Msg? = null) {
        for ((owner, m) in core.dueScheduled(only = only)) io.execute {
            if (owner is Contact) core.deliver(owner, m) else if (owner is Group) core.deliverGroup(owner, m)
        }
    }

    fun sendGroupFile(g: Group, name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
        val m = core.queueGroupFile(g, name, mime, data, thumb)
        io.execute { core.deliverGroupFile(g, m) }
    }

    /** Sends a copy of [m] to a friend or group, marked "Forwarded from [from]". Network: off the main thread. */
    fun forward(m: Msg, from: String, c: Contact?, g: Group?) {
        val f = m.file
        if (f != null) {
            val data = core.openFile(m) ?: throw UserError("That isn't on this phone")
            val th = f.optString("th").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) }
            if (g != null) {
                val msg = core.queueGroupFile(g, f.optString("n"), f.optString("m"), data, th)
                msg.fwd = from
                core.deliverGroupFile(g, msg)
                if (m.text.isNotEmpty()) core.sendGroup(g, m.text, null, from)   // group files carry no caption
            } else if (c != null) {
                val msg = core.queueFile(c, f.optString("n"), f.optString("m"), data, th)
                msg.fwd = from; msg.text = m.text
                core.deliver(c, msg)
            }
        } else if (g != null) core.sendGroup(g, m.text, null, from)
        else if (c != null) { val msg = core.queue(c, m.text); msg.fwd = from; core.deliver(c, msg) }
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
                    sendScheduled()
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
