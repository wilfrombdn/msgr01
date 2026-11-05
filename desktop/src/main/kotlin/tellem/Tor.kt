package tellem

import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import java.io.File

/** Embedded Tor for the desktop app, same idea as on the phone: every request to the relay goes through it. */
object Tor {
    @Volatile private var socks: Int? = null
    @Volatile private var runtime: TorRuntime? = null
    @Volatile private var boot = ""
    @Volatile private var err: String? = null
    @Volatile private var lastProgress = 0L
    private var base: File? = null

    fun socksPort(): Int? = socks

    fun status(): String = when {
        socks != null -> "Tor ready"
        err != null -> "Tor problem: $err"
        boot.isNotEmpty() -> "Starting Tor… $boot"
        else -> "Starting Tor…"
    }

    @Synchronized
    fun start(dir: File) {
        if (runtime != null) return
        try {
            base = File(dir, "tor")
            val env = TorRuntime.Environment.Builder(File(base, "work"), File(base, "cache"), ResourceLoaderTorExec::getOrCreate) {}
            val exec = OnEvent.Executor.Immediate
            val rt = TorRuntime.Builder(env) {
                config { TorOption.__SocksPort.configure { auto() } }
                observerStatic(RuntimeEvent.LISTENERS, exec) { l -> socks = l.socks.firstOrNull()?.port?.value }
                observerStatic(RuntimeEvent.ERROR, exec) { t -> err = (t.message ?: t.toString()).take(140) }
                observerStatic(TorEvent.NOTICE, exec) { note(it) }
                observerStatic(TorEvent.WARN, exec) { note(it) }
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

    private fun startDaemon(rt: TorRuntime, attempt: Int) {
        lastProgress = System.currentTimeMillis()
        rt.enqueue(Action.StartDaemon, OnFailure { t ->
            err = "start: ${(t.message ?: t.toString()).take(130)}"
            Thread({
                try { Thread.sleep(minOf(3_000L shl minOf(attempt, 3), 20_000L)) } catch (e: InterruptedException) { return@Thread }
                if (socks == null) startDaemon(rt, attempt + 1)
            }, "tor-retry").apply { isDaemon = true; start() }
        }, OnSuccess { err = null })
    }

    /** No progress for 2 minutes: start again from a fresh Tor folder (chats live elsewhere). */
    private fun watchdog(rt: TorRuntime) = Thread({
        while (true) {
            try { Thread.sleep(20_000) } catch (e: InterruptedException) { return@Thread }
            if (socks != null || System.currentTimeMillis() - lastProgress < 120_000) continue
            lastProgress = System.currentTimeMillis(); boot = ""
            val fresh = { base?.let { File(it, "work").deleteRecursively(); File(it, "cache").deleteRecursively() }; startDaemon(rt, 0) }
            rt.enqueue(Action.StopDaemon, OnFailure { fresh() }, OnSuccess { fresh() })
        }
    }, "tor-watchdog").apply { isDaemon = true; start() }

    fun restart() {
        val rt = runtime ?: return
        lastProgress = System.currentTimeMillis()
        rt.enqueue(Action.RestartDaemon, OnFailure { startDaemon(rt, 0) }, OnSuccess { err = null })
    }

    private fun note(line: String) {
        if (line.contains("Bootstrapped")) {
            val b = line.substringAfter("Bootstrapped ").substringBefore(" (").take(12)
            if (b != boot) { boot = b; lastProgress = System.currentTimeMillis() }
        }
    }
}
