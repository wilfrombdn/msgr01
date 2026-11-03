package com.wilfrombdn.telegramnt

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * In-app updates. The app asks GitHub Pages (through Tor, so nobody sees who's updating) for
 * telegramnt/update.json, and if there's a newer version downloads the APK with a progress bar and
 * hands it to Android's installer. Android itself checks the update is signed with the same key as
 * the installed app, so a tampered download can't install.
 */
object Updater {
    private const val HOST = "wilfrombdn.github.io"
    private const val MANIFEST = "/telegramnt/update.json"

    class Update(val code: Int, val name: String, val notes: List<String>, val path: String, val sha256: String)

    /** Version code of this install. */
    fun current(ctx: Context): Long =
        if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong()

    // Direct mode (Settings > Connection) skips Tor here too, so updates still arrive where Tor is blocked
    private fun route(): Net.Route = Net.Route(HOST, 443, true, if (Messenger.direct || Tor.outdated) null else Tor.socksPort() ?: throw IOException("Tor not ready"))

    /** The newer version on offer, or null. Off the main thread. */
    fun check(ctx: Context): Update? {
        val buf = ByteArrayOutputStream()
        Net.download(route(), MANIFEST + "?t=" + System.currentTimeMillis() / 60_000, buf)
        val o = JSONObject(buf.toString("UTF-8"))
        val u = Update(o.getInt("code"), o.getString("name"),
            o.optJSONArray("notes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            o.getString("apk"), o.optString("sha256"))
        return u.takeIf { it.code > current(ctx) }
    }

    private fun apkFile(ctx: Context, u: Update) = File(File(ctx.cacheDir, "update"), "tellem-${u.code}.apk")

    /** True when part of [u] is already downloaded, so the button can say "Continue". */
    fun hasPartial(ctx: Context, u: Update): Boolean = apkFile(ctx, u).length() > 0

    /** Deletes downloaded updates (once one is installed they're just taking up space). */
    fun clear(ctx: Context) { File(ctx.cacheDir, "update").deleteRecursively() }

    /**
     * Downloads the update into the app's cache, reporting 0..1 progress. Off the main thread.
     * Keeps what it already has: if the connection drops it waits a little and carries on from
     * there by itself ([waiting] gets a line to show meanwhile), and gives up only after several
     * tries in a row get nowhere. Calling it again later continues the same file.
     */
    fun download(ctx: Context, u: Update, progress: (Float) -> Unit, waiting: (String) -> Unit = {}): File {
        val f = apkFile(ctx, u)
        f.parentFile!!.mkdirs()
        f.parentFile!!.listFiles()?.filter { it != f }?.forEach { it.deleteRecursively() }   // older versions' leftovers
        var fails = 0
        var restarted = false
        while (true) {
            val before = f.length()
            try {
                try {
                    Net.download(route(), u.path, before, { at -> java.io.FileOutputStream(f, at > 0) }) { got, total ->
                        if (total > 0) progress(got.toFloat() / total)
                    }
                } catch (e: IOException) {
                    if (e.message != "HTTP 416" || before == 0L) throw e   // 416 = we already have the whole file
                }
                if (u.sha256.isEmpty() || Crypto.sha256(f.readBytes()).hex() == u.sha256.lowercase()) return f
                // damaged: start over from scratch once, then give up
                f.delete()
                if (restarted) throw Damaged()
                restarted = true
                continue
            } catch (e: Damaged) {
                throw e
            } catch (e: IOException) {
                if (f.length() > before) fails = 0   // it got further: the line is just shaky, keep going
                if (++fails > RETRIES) throw e
                val wait = RETRY_WAIT[minOf(fails, RETRY_WAIT.size) - 1]
                for (left in wait downTo 1) { waiting("Connection dropped — trying again in ${left}s…"); Thread.sleep(1000) }
                waiting("Reconnecting…")
            }
        }
    }

    class Damaged : IOException("download damaged")

    /** Tries in a row that get no further before the download stops and offers Continue. */
    private const val RETRIES = 6
    private val RETRY_WAIT = intArrayOf(3, 5, 10, 20, 30, 30)

    /** Hands the APK to Android's installer; Android closes the app while it replaces it. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Android 12+: no extra confirmation once Tellem installed itself before
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("msngr.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS), flags)
            s.commit(status.intentSender)
        }
    }

    const val ACTION_STATUS = "com.wilfrombdn.telegramnt.UPDATE_STATUS"
    /** Set while an install is underway so the screen can show its outcome. */
    @Volatile var onStatus: ((String?) -> Unit)? = null
}

/** Installer status (asks for confirmation when Android needs it), and reopening after the update. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Updater.ACTION_STATUS -> when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> Updater.onStatus?.invoke(null)
                else -> Updater.onStatus?.invoke(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update didn't install")
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Updater.clear(ctx)
                // the new version is in: keep messages flowing, and reopen the app (or offer to)
                runCatching { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) }
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(open) }
                SyncService.channels(ctx)
                val tap = PendingIntent.getActivity(ctx, 8, open, PendingIntent.FLAG_IMMUTABLE)
                val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
                ctx.getSystemService(NotificationManager::class.java).notify(77, Notification.Builder(ctx, SyncService.CH_MSG)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle("$APP_NAME updated to $name").setContentText("Tap to open")
                    .setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(10 * 60_000).build())
            }
        }
    }
}
