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

    fun socksPort(): Int? = socks

    /** Short line for the UI, so the user (and I) can see where Tor is. */
    fun status(): String = when {
        socks != null -> "Tor ready"
        err != null -> "Tor problem: $err"
        boot.isNotEmpty() -> "Starting Tor… $boot"
        started -> "Starting Tor…"
        else -> "Starting Tor…"
    }

    @Synchronized
    fun start(ctx: Context) {
        if (runtime != null) return
        started = true
        try {
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
                required(TorEvent.ERR)
                required(TorEvent.WARN)
                required(TorEvent.NOTICE)
            }
            runtime = rt
            startDaemon(rt, 0)
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
        rt.enqueue(Action.StartDaemon, OnFailure { t ->
            err = "start: ${(t.message ?: t.toString()).take(130)}"
            val wait = minOf(3_000L shl minOf(attempt, 3), 20_000L)   // 3, 6, 12, then every 20 s
            Thread({
                try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
                if (socks == null) { err = null; startDaemon(rt, attempt + 1) }
            }, "tor-retry").apply { isDaemon = true; start() }
        }, OnSuccess { err = null })
    }

    /**
     * Restarts the daemon: fresh circuits, and it forgets what it cached about the .onion. After the
     * server has been down a long time, Tor can keep trying dead routes to it even once it's back up.
     */
    fun restart() {
        val rt = runtime ?: return
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
        if (line.contains("Bootstrapped")) boot = line.substringAfter("Bootstrapped ").substringBefore(" (").take(12)
        else if (line.contains("[err]") || line.contains("[warn]")) err = line.takeLast(140)
    }
}
