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
 * Embedded Tor. Runs the tor daemon inside the app (no Orbot, no separate app) and exposes the
 * local SOCKS port it listens on. [Relay] routes every request through that port to the server's
 * .onion, so no middleman ever sees an IP. The daemon lives with the app process (kept up by
 * SyncService when background delivery is on), so there's no second foreground service/notification.
 */
object Tor {
    @Volatile private var socks: Int? = null
    @Volatile private var runtime: TorRuntime? = null

    /** The live SOCKS port, or null until Tor has bootstrapped and opened its listener. */
    fun socksPort(): Int? = socks

    /** Starts the daemon once. Safe to call repeatedly. Bootstrapping runs on a background thread. */
    @Synchronized
    fun start(ctx: Context) {
        if (runtime != null) return
        val base = ctx.applicationContext.filesDir.absolutePath.toFile().resolve("kmptor")
        val env = TorRuntime.Environment.Builder(
            workDirectory = base.resolve("work"),
            cacheDirectory = base.resolve("cache"),
            loader = ResourceLoaderTorExec::getOrCreate,
        ) {}
        val rt = TorRuntime.Builder(env) {
            config { TorOption.__SocksPort.configure { auto() } }   // let Tor pick a free local port
            // track the SOCKS listener: set when it opens, cleared when Tor stops
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                socks = listeners.socks.firstOrNull()?.port?.value
            }
            required(TorEvent.ERR)
            required(TorEvent.WARN)
        }
        runtime = rt
        // non-blocking: Tor bootstraps in the background; the LISTENERS observer sets `socks` when ready
        rt.enqueue(Action.StartDaemon, OnFailure {}, OnSuccess.noOp())
    }
}
