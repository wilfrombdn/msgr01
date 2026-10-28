package com.wilfrombdn.telegramnt

import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Wake-up pings through Google's push service. The server sends an EMPTY ping when this phone has mail
 * waiting but isn't connected (app closed, phone asleep). We wake, connect through Tor as usual and pick it
 * up: that's how a call can ring a closed app, like Telegram. Nothing in the ping says who or what.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Messenger.init(this)
        Messenger.setPushToken(token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        Messenger.init(this)
        if (!Messenger.signedIn || !Messenger.pushOn) return
        // a high-priority ping lets us start the foreground service, which connects and fetches the mail
        runCatching { startForegroundService(Intent(this, SyncService::class.java).putExtra(SyncService.WAKE, true)) }
        Messenger.kick()
    }

    companion object {
        /** Asks Google for this phone's ping address (off = tell the server to stop pinging). */
        fun register(ctx: Context) {
            if (!Messenger.pushOn) { Messenger.setPushToken(""); return }
            runCatching {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> if (!t.isNullOrEmpty()) Messenger.setPushToken(t) }
            }   // no Google Play services on this phone: it just keeps its own background connection
        }
    }
}
