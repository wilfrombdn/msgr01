package com.wilfrombdn.telegramnt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the mailbox connection open so messages arrive while the app is closed.
 * (Wake-up pings through Google, see [PushService], are empty: they never say who wrote or what.)
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Messenger.init(this)
        channels(this)
        Messenger.onNotify = { c -> notifyMessage(this, c) }
        Messenger.onNotifyMention = { g -> notifyMention(this, g) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_CONN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$APP_NAME connected")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Messenger.startLoop()
        // woken by a ping while "get messages when closed" is off: stay just long enough to pick up the mail / ring
        if (intent?.getBooleanExtra(WAKE, false) == true && !Messenger.background) {
            android.os.Handler(mainLooper).postDelayed({ if (!Messenger.visible && Messenger.core.call == null && !Messenger.background) stopSelf() }, 120_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Messenger.stopLoop()
        Messenger.onNotify = null
        Messenger.onNotifyMention = null
        super.onDestroy()
    }

    companion object {
        const val WAKE = "wake"
        const val CH_CONN = "connection"
        const val CH_MSG = "messages"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_CONN, "Connection", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        }

        /** "You were mentioned" in a group (no text, like other notifications). */
        fun notifyMention(ctx: Context, g: Group) {
            val open = PendingIntent.getActivity(ctx, g.id.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("group", g.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CH_MSG).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (Messenger.notifyNames) g.name else APP_NAME).setContentText(if (Messenger.notifyNames) "You were mentioned" else "New message")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(g.id.hashCode(), n)
        }

        /**
         * A new message, shown like a normal text: who wrote and their latest unread messages ([Messenger.notifyText]),
         * with Reply. The lock screen gets [publicNote] unless the phone is set to show everything there.
         * With "show names" off it says only "$APP_NAME · New message".
         */
        fun notifyMessage(ctx: Context, c: Contact) {
            val open = PendingIntent.getActivity(
                ctx, c.user.hashCode(),
                Intent(ctx, MainActivity::class.java).putExtra("chat", c.user),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val last = synchronized(Messenger.core) { c.msgs.lastOrNull() }
            val summary = when {
                c.status == Contact.ASKING && last?.mine != false -> "Wants to be your friend"
                last?.call?.optString("s") == "missed" -> "Missed call"
                else -> "New message"
            }
            val b = Notification.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_notify)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNote(ctx, if (Messenger.notifyNames) c.name else APP_NAME, summary))
                .setContentIntent(open)
                .setAutoCancel(true)
            if (!Messenger.notifyNames) {
                b.setContentTitle(APP_NAME).setContentText("New message")
            } else {
                b.setContentTitle(c.name)
                // with app lock on, the chats stay behind it: no text in notifications either
                val unread = if (Messenger.notifyText && !Messenger.appLock) synchronized(Messenger.core) {
                    c.msgs.filter { !it.mine && it.state != Msg.NOTE }.sortedBy { it.ts }.takeLast(c.unread.coerceIn(1, 6))
                } else emptyList()
                if (unread.isEmpty()) b.setContentText(summary)
                else b.setStyle(chatStyle(c.name, unread)).setContentText(preview(unread.last()))
                if (c.canChat && c.status != Contact.SAVED) b.addAction(ReplyReceiver.action(ctx, c))
            }
            ctx.getSystemService(NotificationManager::class.java).notify(c.user.hashCode(), b.build())
        }

        /** The conversation look (sender + their recent lines). Person needs Android 9; older phones use names. */
        @Suppress("DEPRECATION")
        private fun chatStyle(name: String, msgs: List<Msg>): Notification.MessagingStyle =
            if (Build.VERSION.SDK_INT >= 28) {
                val them = android.app.Person.Builder().setName(name).build()
                Notification.MessagingStyle(android.app.Person.Builder().setName("You").build())
                    .apply { msgs.forEach { addMessage(preview(it), it.ts, them) } }
            } else {
                Notification.MessagingStyle("You").apply { msgs.forEach { addMessage(preview(it), it.ts, name) } }
            }

        /** What the lock screen shows when the phone hides private notification content. */
        private fun publicNote(ctx: Context, title: String, text: String): Notification =
            Notification.Builder(ctx, CH_MSG).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text).build()
    }
}
