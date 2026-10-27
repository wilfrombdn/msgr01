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
            rt.enqueue(Action.StartDaemon, OnFailure { t -> err = "start: ${(t.message ?: t.toString()).take(130)}" }, OnSuccess.noOp())
        } catch (t: Throwable) {
            err = "init: ${(t.message ?: t.toString()).take(130)}"
        }
    }

    private fun note(line: String) {
        if (line.contains("Bootstrapped")) boot = line.substringAfter("Bootstrapped ").substringBefore(" (").take(12)
        else if (line.contains("[err]") || line.contains("[warn]")) err = line.takeLast(140)
    }
}
