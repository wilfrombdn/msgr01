package com.wilfrombdn.telegramnt

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Self-destruct (Settings > Privacy): if Tellem isn't opened for the chosen time, everything it keeps on
 * this phone is wiped, the same as Android's "Clear storage" for the app: chats, photos, keys, settings.
 *
 * The time and the last-opened moment live in plain app preferences (not the locked store), so the check
 * works before anything is unlocked. It runs when Tellem starts (including after a reboot), on every round
 * of the background loop, and from an alarm set for the deadline.
 */
object Destruct {
    private const val PREFS = "destruct"
    private const val RECHECK_MS = 15 * 60_000L
    const val ACTION = "com.wilfrombdn.telegramnt.SELF_DESTRUCT"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hours without opening the app before the wipe; 0 = off. */
    fun hours(ctx: Context) = prefs(ctx).getInt("hours", 0)

    fun setHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt("hours", h).putLong("last", System.currentTimeMillis()).commit()
        schedule(ctx)
    }

    /** The app is in use: start the countdown again from now. */
    fun touch(ctx: Context) {
        if (hours(ctx) == 0) return
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
        schedule(ctx)
    }

    /** Due when it's on, the app isn't in use right now, and it's been [hours] since it was last opened. */
    fun due(now: Long, last: Long, hours: Int, inUse: Boolean): Boolean =
        hours > 0 && !inUse && last > 0 && now - last >= hours * 3_600_000L

    /** Wipes now if it's due. If it does, the app closes and this never returns. */
    fun check(ctx: Context, inUse: Boolean = inUse()): Boolean {
        val p = prefs(ctx)
        if (!due(System.currentTimeMillis(), p.getLong("last", 0), p.getInt("hours", 0), inUse)) return false
        wipe(ctx)
        return true
    }

    /** On screen right now, or in a call: never wipe mid-use. */
    private fun inUse(): Boolean = runCatching { Messenger.visible || Messenger.core.call != null }.getOrDefault(false)

    /** An alarm for the deadline, so it happens even if Tellem is never opened again. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 40, Intent(ctx, DestructReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val h = hours(ctx)
        if (h == 0) { am.cancel(pi); return }
        val at = prefs(ctx).getLong("last", System.currentTimeMillis()) + h * 3_600_000L
        // already past (it came due while the app was in use): look again in a while, not straight away in a loop
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(at, System.currentTimeMillis() + RECHECK_MS), pi)
    }

    /**
     * Everything goes: notifications, then all of the app's data (files, keys, settings), like "Clear storage".
     * Ends the app's process, so callers never carry on with half-wiped state.
     */
    fun wipe(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { app.getSystemService(NotificationManager::class.java).cancelAll() }
        // the keystore keys first, so even a leftover copy of the store can't be unlocked
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.aliases().toList().forEach { runCatching { ks.deleteEntry(it) } }
        }
        // what "Clear storage" does; it also closes the app
        val cleared = runCatching { app.getSystemService(ActivityManager::class.java).clearApplicationUserData() }.getOrDefault(false)
        if (!cleared) {
            // the same by hand: everything under the app's own folders
            runCatching { prefs(app).edit().clear().commit() }
            (listOf(File(app.applicationInfo.dataDir)) + app.getExternalFilesDirs(null).filterNotNull() + listOfNotNull(app.externalCacheDir))
                .forEach { d -> d.listFiles()?.forEach { runCatching { it.deleteRecursively() } } }
        }
        // Android closes the app after clearing, but not at once: stop here so nothing runs on in between
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** The deadline alarm (and phone restart, which clears alarms): wipe if due, otherwise set the alarm again. */
class DestructReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Destruct.check(ctx)) Destruct.schedule(ctx)
    }
}
