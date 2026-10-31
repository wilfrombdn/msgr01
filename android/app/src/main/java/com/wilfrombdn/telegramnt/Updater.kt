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
    private fun route(): Net.Route = Net.Route(HOST, 443, true, if (Messenger.direct) null else Tor.socksPort() ?: throw IOException("Tor not ready"))

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

    /** Downloads the update into the app's cache, reporting 0..1 progress. Off the main thread. */
    fun download(ctx: Context, u: Update, progress: (Float) -> Unit): File {
        val f = File(File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }, "msngr.apk")
        f.outputStream().use { out -> Net.download(route(), u.path, out) { got, total -> if (total > 0) progress(got.toFloat() / total) } }
        if (u.sha256.isNotEmpty() && Crypto.sha256(f.readBytes()).hex() != u.sha256.lowercase()) {
            f.delete(); throw IOException("download damaged")
        }
        return f
    }

    /** Hands the APK to Android's installer; Android closes the app while it replaces it. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Android 12+: no extra confirmation once quipu installed itself before
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
                // the new version is in: keep messages flowing, and reopen the app (or offer to)
                runCatching { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) }
                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(open) }
                SyncService.channels(ctx)
                val tap = PendingIntent.getActivity(ctx, 8, open, PendingIntent.FLAG_IMMUTABLE)
                val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
                ctx.getSystemService(NotificationManager::class.java).notify(77, Notification.Builder(ctx, SyncService.CH_MSG)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle("quipu updated to $name").setContentText("Tap to open")
                    .setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(10 * 60_000).build())
            }
        }
    }
}
