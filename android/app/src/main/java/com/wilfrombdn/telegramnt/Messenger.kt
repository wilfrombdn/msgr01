package com.wilfrombdn.telegramnt

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The Android side of [Core]: saves everything to one file locked with a key in the
 * Android Keystore, finds the server by itself, and keeps the mailbox connection open.
 */
object Messenger {
    /** Where the app looks up the current server, so it can move without an app update. */
    const val SERVER_LIST = "https://wilfrombdn.github.io/telegramnt/server.json"
    /** Used if the list can't be reached. Empty until the cloud server is set up. */
    const val BUILT_IN = ""

    lateinit var core: Core
    var background = true
    val look = Look()
    /** A user-chosen file (via the system picker) we keep an encrypted full backup in, so chats
     *  survive uninstall/reinstall. Empty = off. Nothing goes to the server. */
    var recoveryUri: String = ""
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
        start(Core(BUILT_IN))
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
        }
        core.onChange = { save(); changed() }
        core.onNew = { contact -> if (!visible) ui.post { onNotify?.invoke(contact) } }
    }

    val signedIn get() = core.me != null

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        ui.post { ls.forEach { it() } }
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
            core.loadJson(o)
        } catch (e: Exception) {
            // unreadable (e.g. keystore reset): start signed out; the backup restores friends
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        val d = disk ?: return
        val o = core.toJson().put("background", background).put("look", look.json()).put("recoveryUri", recoveryUri)
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

    fun settingsChanged() {
        save()
        changed()
    }

    // ---------- account (keeps chats across log out / log in on this phone) ----------

    private fun archiveFile(user: String): File? =
        file?.let { File(it.parentFile, "archive-" + Crypto.sha256(user.toByteArray()).hex().take(16) + ".bin") }

    /** Logs out but keeps this account's chats, locked, for the next log in on this phone. */
    fun logOut() {
        val m = core.me ?: return
        val d = disk
        val f = archiveFile(m.user)
        if (d != null && f != null) {
            val o = org.json.JSONObject().put("contacts", core.toJson().getJSONArray("contacts"))
            f.writeBytes(d.encrypt(o.toString().toByteArray(), "archive|${m.user}".toByteArray()))
        }
        core.signOut()
    }

    fun logIn(user: String, password: String) {
        core.login(user, password)
        restoreArchive(user)
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

    /** Looks up the server and checks it answers. Network: call off the main thread. */
    fun findServer(): Boolean {
        val candidates = listOfNotNull(core.relay.server.ifBlank { null }, fetchServerList(), BUILT_IN.ifBlank { null })
        for (s in candidates.distinct()) {
            if (Relay(s).ping()) {
                if (s != core.relay.server) core.relay = Relay(s)
                setConnected(true)
                return true
            }
        }
        setConnected(false)
        return false
    }

    private var finding: Thread? = null

    /** Before sign-in: keep looking for the server quietly until it answers. */
    @Synchronized
    fun connectInBackground() {
        if (connected || finding?.isAlive == true) return
        finding = Thread({
            var wait = 3_000L
            while (!signedIn && !findServer()) {
                try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }, "find-server").apply { isDaemon = true; start() }
    }

    private fun fetchServerList(): String? = try {
        val c = URL(SERVER_LIST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            val s = JSONObject(c.inputStream.bufferedReader().readText()).optString("server").trimEnd('/')
            s.takeIf { it.startsWith("https://") } // only ever auto-connect over https
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
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

    fun send(c: Contact, text: String) {
        val m = core.queue(c, text)
        io.execute { core.deliver(c, m) }
    }

    fun sendGroupFile(g: Group, name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
        val m = core.queueGroupFile(g, name, mime, data, thumb)
        io.execute { core.deliverGroupFile(g, m) }
    }

    fun react(c: Contact, msg: Msg, emoji: String) = io.execute { core.react(c, msg, emoji) }
    fun reactGroup(g: Group, msg: Msg, emoji: String) = io.execute { core.reactGroup(g, msg, emoji) }

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
                    setConnected(true)
                    backoff = 2_000L
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (!(e is java.io.IOException && e.message?.contains("closed") == true)) setConnected(false)
                    try { Thread.sleep(backoff) } catch (i: InterruptedException) { return@Thread }
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                    // Server unreachable for a while: it may have moved, so look it up again.
                    // (an old Cloudflare address answers 530, so server errors count too)
                    if (backoff >= 16_000L && (e !is Relay.RelayError || e.code >= 500)) runCatching { findServer() }
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

    fun json(): JSONObject = JSONObject().put("out", out).put("in", inn).put("ui", ui.toDouble())
        .put("msg", msg.toDouble()).put("square", square)

    fun load(o: JSONObject) {
        out = o.optInt("out", out)
        inn = o.optInt("in", inn)
        ui = o.optDouble("ui", 1.0).toFloat().coerceIn(0.8f, 1.5f)
        msg = o.optDouble("msg", 16.0).toFloat().coerceIn(12f, 28f)
        square = o.optBoolean("square")
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
