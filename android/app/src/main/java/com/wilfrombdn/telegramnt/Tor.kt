package com.wilfrombdn.telegramnt

import android.content.Context
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption

/**
 * Embedded Tor. Runs the tor daemon inside the app (no Orbot) and exposes the local SOCKS port it
 * listens on. [Relay] routes every request through that port to the server's .onion.
 *
 * Also keeps a human-readable [status] so the app can show what Tor is doing (bootstrap %, ready, or
 * the error) — the daemon can only be tested on a real device, so this is how we see what happened.
 */
object Tor {
    @Volatile private var socks: Int? = null
    @Volatile private var runtime: TorRuntime? = null
    @Volatile private var boot: String = ""        // last "Bootstrapped X%" line
    @Volatile private var err: String? = null       // last error (start failure or runtime error)
    @Volatile private var started = false
    /**
     * The Tor network told us this app's built-in Tor is too old ("missing protocols"). Retrying can't fix
     * that; only an app update with a newer Tor can, so the updater then fetches without Tor.
     */
    @Volatile var outdated = false
        private set
    @Volatile private var tries = 0                // failed starts since the last success
    @Volatile private var lastProgress = 0L         // last start, or last new bootstrap step
    private var base: java.io.File? = null
    private val recent = ArrayDeque<String>()       // Tor's latest messages, for Settings > Connection

    /** Tor's latest messages, oldest first, so a stuck start can be copied and looked at. */
    fun log(): List<String> = synchronized(recent) { recent.toList() }

    fun socksPort(): Int? = socks

    /** Short line for the UI, so the user (and I) can see where Tor is. */
    fun status(): String = when {
        outdated -> "Tor in this app is out of date. Update quipu, or use Direct for now."
        socks != null -> "Tor ready"
        err != null -> "Tor problem" + (if (tries > 1) " (try $tries)" else "") + ": $err"
        boot.isNotEmpty() -> "Starting Tor… $boot"
        started -> "Starting Tor…"
        else -> "Starting Tor…"
    }

    @Synchronized
    fun start(ctx: Context) {
        if (runtime != null) return
        started = true
        try {
            this.base = java.io.File(ctx.applicationContext.filesDir, "kmptor")
            val base = ctx.applicationContext.filesDir.absolutePath.toFile().resolve("kmptor")
            val env = TorRuntime.Environment.Builder(
                workDirectory = base.resolve("work"),
                cacheDirectory = base.resolve("cache"),
                loader = ResourceLoaderTorExec::getOrCreate,
            ) {}
            val exec = OnEvent.Executor.Immediate
            val rt = TorRuntime.Builder(env) {
                config { TorOption.__SocksPort.configure { auto() } }
                observerStatic(RuntimeEvent.LISTENERS, exec) { listeners ->
                    socks = listeners.socks.firstOrNull()?.port?.value
                }
                observerStatic(RuntimeEvent.ERROR, exec) { t -> err = (t.message ?: t.toString()).take(140) }
                observerStatic(RuntimeEvent.LOG.WARN, exec) { line -> note(line) }
                observerStatic(RuntimeEvent.LOG.INFO, exec) { line -> note(line) }
                observerStatic(RuntimeEvent.STATE, exec) { st -> note("state: $st") }
                // tor's own messages (bootstrap steps and its errors)
                observerStatic(TorEvent.NOTICE, exec) { line -> note(line) }
                observerStatic(TorEvent.WARN, exec) { line -> note("[warn] $line") }
                observerStatic(TorEvent.ERR, exec) { line -> note("[err] $line") }
                required(TorEvent.ERR)
                required(TorEvent.WARN)
                required(TorEvent.NOTICE)
            }
            runtime = rt
            startDaemon(rt, 0)
            watchdog(rt)
        } catch (t: Throwable) {
            err = "init: ${(t.message ?: t.toString()).take(130)}"
        }
    }

    /**
     * Starts the daemon, and keeps retrying if it fails. Right after an in-app update the old copy's
     * Tor can still be shutting down for a few seconds and holds its folder, so the first start fails;
     * before, that left the app on "Connecting…" until it was force-closed.
     */
    private fun startDaemon(rt: TorRuntime, attempt: Int) {
        lastProgress = System.currentTimeMillis()
        rt.enqueue(Action.StartDaemon, OnFailure { t ->
            tries++
            err = "start: ${(t.message ?: t.toString()).take(130)}"
            note("[err] $err")
            val wait = minOf(3_000L shl minOf(attempt, 3), 20_000L)   // 3, 6, 12, then every 20 s
            Thread({
                try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
                if (socks == null) startDaemon(rt, attempt + 1)   // keep showing the error until a start works
            }, "tor-retry").apply { isDaemon = true; start() }
        }, OnSuccess { err = null; tries = 0 })
    }

    /**
     * If Tor makes no progress for 2 minutes (no new bootstrap step, no SOCKS port), start it again from
     * a fresh folder. That covers a start that hangs without failing, a leftover Tor from the old copy of
     * the app still holding the folder after an update, and saved Tor files that went bad. Only Tor's own
     * folder is cleared; accounts and chats live elsewhere.
     */
    private fun watchdog(rt: TorRuntime) = Thread({
        while (true) {
            try { Thread.sleep(20_000) } catch (e: InterruptedException) { return@Thread }
            if (!startStuck(System.currentTimeMillis(), socks != null, lastProgress)) continue
            note("no progress for 2 min: starting Tor again from a fresh folder")
            lastProgress = System.currentTimeMillis()
            boot = ""
            val fresh = {
                base?.let { b -> java.io.File(b, "work").deleteRecursively(); java.io.File(b, "cache").deleteRecursively() }
                startDaemon(rt, 0)
            }
            rt.enqueue(Action.StopDaemon, OnFailure { fresh() }, OnSuccess { fresh() })
        }
    }, "tor-watchdog").apply { isDaemon = true; start() }

    /** Tor's warning that the network now needs protocols this Tor version doesn't have. */
    fun tooOld(line: String): Boolean {
        val l = line.lowercase()
        return "protocol" in l && ("missing" in l || "upgrade" in l || "not supported" in l || "will not work" in l)
    }

    /** True when Tor isn't ready and nothing has moved for 2 minutes. */
    fun startStuck(now: Long, ready: Boolean, lastProgress: Long): Boolean =
        !ready && lastProgress > 0 && now - lastProgress >= 2 * 60_000L

    /**
     * Restarts the daemon: fresh circuits, and it forgets what it cached about the .onion. After the
     * server has been down a long time, Tor can keep trying dead routes to it even once it's back up.
     */
    fun restart() {
        val rt = runtime ?: return
        lastProgress = System.currentTimeMillis()
        rt.enqueue(Action.RestartDaemon, OnFailure { t ->
            err = "restart: ${(t.message ?: t.toString()).take(130)}"
            startDaemon(rt, 0)
        }, OnSuccess { err = null })
    }

    /**
     * Whether to [restart] Tor because the server hasn't answered since [lastOk] although Tor is ready.
     * Waits 3 minutes, then spaces restarts out (10, then every 30 minutes) so a server that is really
     * off for hours doesn't cost a full Tor start every few minutes.
     */
    fun restartDue(now: Long, lastOk: Long, lastRestart: Long, restarts: Int): Boolean {
        if (now - lastOk < 3 * 60_000L) return false
        if (restarts == 0) return true
        val gap = if (restarts == 1) 10 * 60_000L else 30 * 60_000L
        return now - lastRestart >= gap
    }

    private fun note(line: String) {
        synchronized(recent) { recent.addLast(line.take(200)); while (recent.size > 30) recent.removeFirst() }
        if (tooOld(line)) outdated = true
        if (line.contains("Bootstrapped")) {
            val b = line.substringAfter("Bootstrapped ").substringBefore(" (").take(12)
            if (b != boot) { boot = b; lastProgress = System.currentTimeMillis() }
        }
        else if (line.contains("[err]") || line.contains("[warn]")) err = line.takeLast(140)
    }
}
