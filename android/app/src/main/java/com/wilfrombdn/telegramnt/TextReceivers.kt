package com.wilfrombdn.telegramnt

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SubscriptionManager
import java.io.File
import java.util.concurrent.Executors

// What Android hands the default texting app. None of these open Tellem's locked store or start Tor,
// so texts are saved even when Tellem is locked, signed out or wiped.

private val work = Executors.newSingleThreadExecutor()

/** Run [job] off the main thread, keeping the receiver alive until it's done. */
private fun BroadcastReceiver.later(job: () -> Unit) {
    val pending = goAsync()
    work.execute { try { runCatching(job) } finally { pending.finish() } }
}

private fun Intent.sub() = getIntExtra("subscription", SubscriptionManager.getDefaultSmsSubscriptionId())

/** A normal text arrived: save it, then notify. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val sub = intent.sub()
        later {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put("address", from); put("body", body); put("date", System.currentTimeMillis()); put("date_sent", parts[0].timestampMillis)
                put("read", 0); put("seen", 0); put("sub_id", sub)
                put("thread_id", Texts.threadFor(ctx, listOf(from)))
            })
            Texts.notify(ctx, Texts.threadFor(ctx, listOf(from)))
        }
    }
}

/** The carrier says a picture/group text is waiting: note it, then download it. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val sub = intent.sub()
        later {
            val n = Mms.parse(data)?.takeIf { it.type == Mms.NOTIFICATION_IND } ?: return@later
            val loc = n.location ?: return@later
            val cr = ctx.contentResolver
            // the carrier repeats notices it thinks we missed
            val seen = cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), "ct_l = ?", arrayOf(loc), null)?.use { it.count > 0 } == true
            if (seen) return@later
            val from = n.from ?: ""
            val row = cr.insert(Telephony.Mms.CONTENT_URI, ContentValues().apply {
                put("thread_id", Texts.threadFor(ctx, listOf(from.ifEmpty { "Unknown" })))
                put("date", System.currentTimeMillis() / 1000); put("msg_box", Telephony.Mms.MESSAGE_BOX_INBOX)
                put("read", 1); put("seen", 1); put("m_type", Mms.NOTIFICATION_IND); put("v", 0x12)
                put("ct_l", loc); n.tid?.let { put("tr_id", it) }
                if (n.expiry > 0) put("exp", n.expiry); if (n.size > 0) put("m_size", n.size); put("sub_id", sub)
            })?.lastPathSegment?.toLongOrNull() ?: return@later
            if (from.isNotEmpty()) cr.insert(Uri.parse("content://mms/$row/addr"), ContentValues().apply { put("address", from); put("type", 137); put("charset", Mms.UTF8) })
            Texts.download(ctx, row, loc, sub)
        }
    }
}

/** A downloaded picture/group text: store it in its thread, tell the carrier we got it, notify. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val row = intent.getLongExtra("row", -1)
        val file = File(intent.getStringExtra("file") ?: return)
        val sub = intent.getIntExtra("sub", -1)
        val ok = resultCode == Activity.RESULT_OK
        later {
            val cr = ctx.contentResolver
            val pdu = if (ok && file.length() > 0) Mms.parse(file.readBytes())?.takeIf { it.type == Mms.RETRIEVE_CONF } else null
            file.delete()
            if (pdu == null) {   // leave the notice: the chat shows "didn't download, tap to retry"
                val thread = cr.update(Uri.parse("content://mms/$row"), ContentValues().apply { put("st", Texts.DOWNLOAD_FAILED); put("read", 0) }, null, null)
                if (thread > 0) threadOf(ctx, row)?.let { Texts.notify(ctx, it) }
                return@later
            }
            val (tid, loc) = cr.query(Uri.parse("content://mms/$row"), arrayOf("tr_id", "ct_l"), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: (null to null)
            val from = pdu.from ?: ""
            if (pdu.to.size == 1 && pdu.cc.isEmpty() && !Texts.same(pdu.to[0], from)) Texts.learnOwnNumber(ctx, pdu.to[0])
            val mine = Texts.ownNumbers(ctx)
            // already saved (the carrier sent the notice twice): just clean up and confirm again
            val dup = pdu.messageId?.let { id -> cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), "m_id = ?", arrayOf(id), null)?.use { it.count > 0 } } == true
            if (dup) { cr.delete(Uri.parse("content://mms/$row"), null, null); tid?.let { runCatching { sendReceipt(ctx, it, sub) } }; return@later }
            val others = (pdu.to + pdu.cc).filter { a -> mine.none { Texts.same(a, it) } && !Texts.same(a, from) }
            // without our own number we can't tell which "to" is us; a single one is, though
            val people = listOf(from) + if (mine.isEmpty() && pdu.to.size + pdu.cc.size <= 1) emptyList() else others
            val saved = Texts.saveMms(ctx, Telephony.Mms.MESSAGE_BOX_INBOX, pdu, people.filter { it.isNotEmpty() }.ifEmpty { listOf("Unknown") }, sub, read = false, location = loc)
            cr.delete(Uri.parse("content://mms/$row"), null, null)
            tid?.let { runCatching { sendReceipt(ctx, it, sub) } }
            threadOf(ctx, saved)?.let { Texts.notify(ctx, it) }
        }
    }

    private fun threadOf(ctx: Context, row: Long) =
        ctx.contentResolver.query(Uri.parse("content://mms/$row"), arrayOf("thread_id"), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun sendReceipt(ctx: Context, tid: String, sub: Int) {
        val f = File(Texts.mmsDir(ctx), "ack-${System.nanoTime()}.pdu").apply { writeBytes(Mms.notifyResp(tid)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        for (pkg in listOf("com.android.phone", "com.android.mms.service")) runCatching { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        @Suppress("DEPRECATION")
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java) else android.telephony.SmsManager.getDefault()
        (if (sub >= 0) sm.createForSubscriptionId(sub) else sm).sendMultimediaMessage(ctx, uri, null, null, null)
        // the file is tiny; old ones are cleared with the cache
    }
}

/** A normal text left the phone (or didn't). */
class TextSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val last = intent.getBooleanExtra("last", true)
        later {
            if (!ok) Texts.setSmsType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            else if (last) {
                val failed = ctx.contentResolver.query(uri, arrayOf("type"), null, null, null)?.use { it.moveToFirst() && it.getInt(0) == Telephony.Sms.MESSAGE_TYPE_FAILED } == true
                if (!failed) Texts.setSmsType(ctx, uri, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
        }
    }
}

/** A picture/group text left the phone (or didn't). */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ok = resultCode == Activity.RESULT_OK
        val row = intent.getLongExtra("row", -1)
        later {
            intent.getStringExtra("file")?.let { File(it).delete() }
            Texts.setMmsBox(ctx, row, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
        }
    }
}

/** "Reply" typed in a text notification. */
class TextReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        val thread = intent.getLongExtra("thread", -1)
        later {
            val people = Texts.addressesOf(ctx, thread)
            if (text.isNotEmpty() && people.isNotEmpty()) Texts.send(ctx, people, text)
            Texts.markRead(ctx, thread)
        }
    }

    companion object { const val KEY = "reply" }
}

/** "Reply with a message" on the incoming call screen (Android requires a texting app to offer it). */
class TextRespondService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (to.isNotEmpty() && body.isNotEmpty() && Texts.isDefault(this)) Texts.send(this, to, body)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
