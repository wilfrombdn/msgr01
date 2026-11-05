package tellem

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.wilfrombdn.telegramnt.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The desktop app's glue around the shared [Core]: where things are saved, Tor, and the background
 * loop that fetches messages. (The phone's Messenger does the same jobs with Android pieces.)
 */
object Session {
    const val ONION = "http://b5yezgiz6sehrwie3qcnmplgbxcc7lfrclqukbmlnya332gh53wplzad.onion"

    /** Bumped on every change; the UI reads it so it redraws. */
    val tick = mutableIntStateOf(0)
    val connected = mutableStateOf(false)
    val torStatus = mutableStateOf("Starting Tor…")
    val signedOutReason = mutableStateOf<String?>(null)

    lateinit var core: Core
    val io = Executors.newCachedThreadPool { r -> Thread(r, "tellem-io").apply { isDaemon = true } }

    /** TELLEM_DEMO=1 opens fake chats offline (for screenshots); nothing real is touched or saved. */
    val demo = System.getenv("TELLEM_DEMO") != null

    val dir: File = if (demo) File(System.getProperty("java.io.tmpdir"), "tellem-demo").apply { mkdirs() } else run {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            "win" in os -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            "mac" in os -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        File(base, "Tellem").apply { mkdirs() }
    }
    private val storeFile = File(dir, "store.bin")
    private val keyFile = File(dir, "disk-key.json")
    private lateinit var disk: Aead
    private var loop: Thread? = null
    @Volatile private var saveQueued = false

    val signedIn get() = core.me != null
    fun changed() { tick.intValue++ }

    fun init() {
        Crypto.ensureInit()
        disk = loadDiskKey()
        if (!demo) Tor.start(dir)
        core = Core(ONION) { Tor.socksPort() }
        val blobs = File(dir, "blobs").apply { mkdirs() }
        core.saveBlob = { id, b -> File(blobs, id).writeBytes(b) }
        core.loadBlob = { id -> File(blobs, id).takeIf { it.exists() }?.readBytes() }
        core.deleteBlob = { id -> File(blobs, id).delete() }
        core.blobSize = { id -> File(blobs, id).length() }
        core.onChange = { requestSave(); changed() }
        runCatching {
            if (storeFile.exists()) core.loadJson(JSONObject(String(disk.decrypt(storeFile.readBytes(), "store".toByteArray()))))
        }
        Thread({ while (true) { torStatus.value = Tor.status(); Thread.sleep(1000) } }, "tor-status").apply { isDaemon = true; start() }
        if (demo) { Demo.fill(core); return }
        Runtime.getRuntime().addShutdownHook(Thread { save() })
        startLoop()
    }

    /**
     * The key that locks the saved chats lives next to them. That keeps the files unreadable to other
     * programs and backups, but anyone who can log in as this user on this computer can read it, so use
     * your OS disk encryption (BitLocker / LUKS) for real protection.
     */
    private fun loadDiskKey(): Aead {
        val h: KeysetHandle = if (keyFile.exists()) TinkJsonProtoKeysetFormat.parseKeyset(keyFile.readText(), InsecureSecretKeyAccess.get())
        else KeysetHandle.generateNew(KeyTemplates.get(Crypto.AEAD_TEMPLATE)).also {
            keyFile.writeText(TinkJsonProtoKeysetFormat.serializeKeyset(it, InsecureSecretKeyAccess.get()))
            runCatching { keyFile.setReadable(false, false); keyFile.setReadable(true, true); keyFile.setWritable(true, true) }
        }
        return h.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun requestSave() {
        if (saveQueued) return
        saveQueued = true
        io.execute { Thread.sleep(400); saveQueued = false; save() }
    }

    @Synchronized fun save() {
        if (demo || !::disk.isInitialized || !::core.isInitialized) return
        val tmp = File(storeFile.path + ".tmp")
        tmp.writeBytes(disk.encrypt(core.toJson().toString().toByteArray(), "store".toByteArray()))
        if (!tmp.renameTo(storeFile)) { storeFile.delete(); tmp.renameTo(storeFile) }
    }

    fun logOut() {
        core.signOut()
        storeFile.delete()
        File(dir, "blobs").apply { deleteRecursively(); mkdirs() }
        changed()
    }

    /** Runs [task] off the UI thread, then calls [done] with null or the problem in plain words. */
    fun run(task: () -> Unit, done: (String?) -> Unit = {}) = io.execute {
        val err = try { task(); null } catch (e: UserError) { e.message } catch (e: Exception) {
            if (e is java.io.IOException) "Can't reach the server yet (${Tor.status()})" else (e.message ?: "Something went wrong")
        }
        done(err)
    }

    fun findServer(): Boolean {
        val ok = core.relay.ping()
        connected.value = ok
        return ok
    }

    @Synchronized fun startLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({
            var backoff = 2_000L
            var lastCheck = 0L
            var lastOk = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (!signedIn) { if (!connected.value) runCatching { findServer() }; Thread.sleep(3_000); continue }
                    if (System.currentTimeMillis() - lastCheck > 5 * 60_000) {
                        lastCheck = System.currentTimeMillis()
                        if (!core.sessionOk()) {
                            signedOutReason.value = "You signed in somewhere else, so this computer was signed out."
                            logOut(); continue
                        }
                        runCatching { core.keepKeysStocked() }
                    }
                    if (core.backupDirty) runCatching { core.uploadBackup() }
                    if (!connected.value && !findServer()) throw java.io.IOException("offline")
                    core.dueScheduled().forEach { (o, m) -> io.execute { if (o is Contact) core.deliver(o, m) else if (o is Group) core.deliverGroup(o, m) } }
                    core.poll(25)
                    core.purgeExpired()
                    connected.value = true; lastOk = System.currentTimeMillis(); backoff = 2_000L
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (!(e is java.io.IOException && e.message?.contains("closed") == true)) connected.value = false
                    if (Tor.socksPort() != null && System.currentTimeMillis() - lastOk > 3 * 60_000) { lastOk = System.currentTimeMillis(); Tor.restart() }
                    try { Thread.sleep(backoff) } catch (i: InterruptedException) { return@Thread }
                    backoff = (backoff * 2).coerceAtMost(8_000L)
                }
            }
        }, "mailbox").apply { isDaemon = true; start() }
    }
}
