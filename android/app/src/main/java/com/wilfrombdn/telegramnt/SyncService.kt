package com.wilfrombdn.telegramnt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Keeps the mailbox connection open so messages arrive while the app is closed.
 * No Google push service is used, because that would tell Google who gets messages and when.
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Messenger.init(this)
        channels(this)
        Messenger.onNotify = { c -> notifyMessage(this, c) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_CONN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("MSNGR connected")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Messenger.startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        Messenger.stopLoop()
        Messenger.onNotify = null
        super.onDestroy()
    }

    companion object {
        const val CH_CONN = "connection"
        const val CH_MSG = "messages"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_CONN, "Connection", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        }

        /** Shows only who wrote, never the text, so it can't leak onto the lock screen. */
        fun notifyMessage(ctx: Context, c: Contact) {
            val open = PendingIntent.getActivity(
                ctx, c.user.hashCode(),
                Intent(ctx, MainActivity::class.java).putExtra("chat", c.user),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(c.name)
                .setContentText(if (c.status == Contact.ASKING) "Wants to be your friend"
                    else if (c.msgs.lastOrNull()?.call?.optString("s") == "missed") "Missed call" else "New message")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(c.user.hashCode(), n)
        }
    }
}
