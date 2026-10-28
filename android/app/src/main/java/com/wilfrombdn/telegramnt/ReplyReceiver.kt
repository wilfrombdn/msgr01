package com.wilfrombdn.telegramnt

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Reply" typed right in a message notification: sends it and marks the chat read. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        val user = intent.getStringExtra("chat") ?: return
        Messenger.init(ctx)
        val c = Messenger.core.contact(user) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (text.isNotEmpty() && c.canChat && user !in Messenger.core.blocked) {
            Messenger.send(c, text.take(Core.MAX_TEXT))
            Messenger.drafts.remove("u:$user")
            Messenger.run({ Messenger.core.markRead(c) })
        }
        nm.cancel(user.hashCode())
    }

    companion object {
        const val KEY = "reply"

        fun action(ctx: Context, c: Contact): Notification.Action {
            val pi = PendingIntent.getBroadcast(ctx, c.user.hashCode(), Intent(ctx, ReplyReceiver::class.java).putExtra("chat", c.user),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)   // mutable: Android fills in the typed reply
            return Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(RemoteInput.Builder(KEY).setLabel("Message").build())
                .build()
        }
    }
}
