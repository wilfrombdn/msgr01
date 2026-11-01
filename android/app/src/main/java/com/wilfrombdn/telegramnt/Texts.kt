package com.wilfrombdn.telegramnt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Normal texts (SMS) and picture/group texts (MMS), when Tellem is the phone's texting app.
 *
 * These are NOT Tellem messages: they go through the phone carrier, unencrypted, and live in
 * Android's shared text storage (outside Tellem's locked store; self-destruct leaves them alone).
 * Nothing here touches Tor, the relay or [Messenger], and there is no path from one kind to the
 * other: a private message never falls back to a text, and a text is never sent as a private message.
 */
object Texts {
    class Thread(val id: Long, val addresses: List<String>, val snippet: String, val date: Long, val unread: Int,
                 val spam: Boolean = false, val blocked: Boolean = false)
    class Attachment(val uri: Uri, val mime: String)
    class Text(
        val id: Long, val mms: Boolean, val mine: Boolean, val from: String?, val body: String, val date: Long,
        val state: Int, val attachments: List<Attachment> = emptyList(),
    )
    const val OK = 0; const val SENDING = 1; const val FAILED = 2; const val NOT_DOWNLOADED = 3; const val DOWNLOADING = 4
    const val DOWNLOAD_FAILED = 0x82   // our mark on a picture text's notice when fetching it failed

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** The thread on screen right now (no notification for it). */
    @Volatile var openThread = 0L

    /** Tellem is the phone's texting app (only that app may send and save texts). */
    fun isDefault(ctx: Context) = runCatching {
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
        else Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
    }.getOrDefault(false)
    fun canRead(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    // ---------- settings the receivers need (they never open Tellem's locked store) ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("texts", Context.MODE_PRIVATE)
    fun mixed(ctx: Context) = prefs(ctx).getBoolean("mixed", false)
    fun setMixed(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("mixed", on).apply()

    /** Copies of Tellem's notification privacy choices, so text notifications follow the same rules. */
    fun mirror(ctx: Context, names: Boolean, text: Boolean, lock: Boolean) =
        prefs(ctx).edit().putBoolean("names", names).putBoolean("text", text).putBoolean("lock", lock).apply()

    // ---------- reading (always off the main thread) ----------

    @Volatile var threads: List<Thread> = emptyList(); internal set   // set directly only by screenshot tests
    private val listeners = LinkedHashSet<() -> Unit>()
    private var observer: ContentObserver? = null

    /** Keep [threads] current while the app is open; [onChange] runs on the main thread. */
    fun listen(ctx: Context, onChange: () -> Unit) {
        val app = ctx.applicationContext
        synchronized(listeners) { listeners += onChange }
        if (observer == null && canRead(app)) {
            val reload = Runnable { reload(app) }
            observer = object : ContentObserver(ui) {
                override fun onChange(self: Boolean) { ui.removeCallbacks(reload); ui.postDelayed(reload, 300) }
            }.also { runCatching { app.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, it) } }
        }
        reload(app)
    }

    fun unlisten(onChange: () -> Unit) = synchronized(listeners) { listeners -= onChange }

    fun reload(ctx: Context) {
        val app = ctx.applicationContext
        if (!canRead(app)) { threads = emptyList(); return }
        io.execute {
            val t = runCatching { loadThreads(app) }.getOrDefault(threads)
            ui.post { threads = t; synchronized(listeners) { listeners.toList() }.forEach { it() } }
        }
    }

    private fun loadThreads(ctx: Context): List<Thread> {
        val cr = ctx.contentResolver
        val addr = HashMap<Long, String>()
        cr.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            // this list ignores the columns asked for, so read them by name
            val id = c.getColumnIndexOrThrow("_id"); val a = c.getColumnIndexOrThrow("address")
            while (c.moveToNext()) addr[c.getLong(id)] = c.getString(a) ?: ""
        }
        val unread = HashMap<Long, Int>()
        for (u in listOf(Telephony.Sms.Inbox.CONTENT_URI, Telephony.Mms.Inbox.CONTENT_URI)) {
            cr.query(u, arrayOf("thread_id"), "read = 0", null, null)?.use { c ->
                while (c.moveToNext()) c.getLong(0).let { unread[it] = (unread[it] ?: 0) + 1 }
            }
        }
        val replied = HashSet<Long>()   // threads you've written in are never spam
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("thread_id"), "type != ${Telephony.Sms.MESSAGE_TYPE_INBOX}", null, null)?.use { c -> while (c.moveToNext()) replied += c.getLong(0) }
        cr.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id"), "msg_box != ${Telephony.Mms.MESSAGE_BOX_INBOX}", null, null)?.use { c -> while (c.moveToNext()) replied += c.getLong(0) }
        val out = ArrayList<Thread>()
        cr.query(Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "date", "recipient_ids", "snippet", "snippet_cs", "message_count", "has_attachment"), null, null, "date DESC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue   // an empty thread left behind
                val id = c.getLong(0)
                val people = (c.getString(2) ?: "").split(' ').mapNotNull { it.toLongOrNull()?.let(addr::get) }.filter { it.isNotEmpty() }
                var snip = c.getString(3) ?: ""
                if (c.getInt(4) != 0 && snip.isNotEmpty()) snip = String(snip.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                if (snip.isEmpty() && c.getInt(6) != 0) snip = "📷 Picture"
                val blocked = people.any { isBlocked(ctx, it) }
                out += Thread(id, people, snip, c.getLong(1), unread[id] ?: 0, blocked || spamVerdict(ctx, id, people, snip, id in replied), blocked)
            }
        }
        out.forEach { t -> t.addresses.forEach { name(ctx, it) } }   // look names up here, not while drawing the list
        return out
    }

    /** The newest [limit] texts in a thread, oldest first. Blocking: call from a background thread. */
    fun messages(ctx: Context, thread: Long, limit: Int = 300): List<Text> {
        val cr = ctx.contentResolver
        val out = ArrayList<Text>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "type", "address", "body", "date"), "thread_id = ?", arrayOf("$thread"), "date DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(1)
                val state = when (type) { Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> SENDING; Telephony.Sms.MESSAGE_TYPE_FAILED -> FAILED; else -> OK }
                out += Text(c.getLong(0), false, type != Telephony.Sms.MESSAGE_TYPE_INBOX, c.getString(2), c.getString(3) ?: "", c.getLong(4), state)
            }
        }
        val mms = ArrayList<Triple<Long, Int, Long>>()   // id, box, date
        val notified = HashSet<Long>()
        val stuck = HashSet<Long>()
        cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "msg_box", "date", "m_type", "st"), "thread_id = ?", arrayOf("$thread"), "date DESC LIMIT $limit")?.use { c ->
            while (c.moveToNext()) {
                mms += Triple(c.getLong(0), c.getInt(1), c.getLong(2) * 1000)
                if (c.getInt(3) == Mms.NOTIFICATION_IND) { notified += c.getLong(0); if (c.getInt(4) == DOWNLOAD_FAILED) stuck += c.getLong(0) }
            }
        }
        if (mms.isNotEmpty()) {
            val text = HashMap<Long, StringBuilder>(); val att = HashMap<Long, MutableList<Attachment>>()
            cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct", "text"), "mid IN (${mms.joinToString(",") { "${it.first}" }})", null, "_id")?.use { c ->
                while (c.moveToNext()) {
                    val mid = c.getLong(1); val ct = c.getString(2) ?: ""
                    when {
                        ct == "text/plain" -> text.getOrPut(mid) { StringBuilder() }.apply { if (isNotEmpty()) append('\n') }.append(c.getString(3) ?: "")
                        ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ->
                            att.getOrPut(mid) { ArrayList() } += Attachment(Uri.parse("content://mms/part/${c.getLong(0)}"), ct)
                    }
                }
            }
            for ((id, box, date) in mms) {
                val mine = box != Telephony.Mms.MESSAGE_BOX_INBOX
                val state = when {
                    id in stuck -> NOT_DOWNLOADED
                    id in notified -> DOWNLOADING
                    box == Telephony.Mms.MESSAGE_BOX_OUTBOX -> SENDING
                    box == Telephony.Mms.MESSAGE_BOX_FAILED -> FAILED
                    else -> OK
                }
                out += Text(id, true, mine, if (mine) null else mmsFrom(ctx, id), text[id]?.toString() ?: "", date, state, att[id] ?: emptyList())
            }
        }
        return out.sortedBy { it.date }.takeLast(limit)
    }

    private fun mmsFrom(ctx: Context, id: Long): String? =
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = 137", null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun markRead(ctx: Context, thread: Long) = io.execute {
        val v = ContentValues().apply { put("read", 1); put("seen", 1) }
        runCatching { ctx.contentResolver.update(Telephony.Sms.Inbox.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf("$thread")) }
        runCatching { ctx.contentResolver.update(Telephony.Mms.Inbox.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf("$thread")) }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTE_TAG, thread.toInt())
    }

    fun deleteThread(ctx: Context, thread: Long) = io.execute {
        runCatching { ctx.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$thread"), null, null) }
    }

    fun deleteText(ctx: Context, t: Text) = io.execute {
        runCatching { ctx.contentResolver.delete(Uri.parse(if (t.mms) "content://mms/${t.id}" else "content://sms/${t.id}"), null, null) }
    }

    fun threadFor(ctx: Context, addresses: Collection<String>): Long = Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet())

    /** Everyone in a thread (from the cache, or Android's own list when the app isn't open). Blocking if not cached. */
    fun addressesOf(ctx: Context, thread: Long): List<String> =
        threads.firstOrNull { it.id == thread }?.addresses ?: runCatching {
            val cr = ctx.contentResolver
            val ids = cr.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("recipient_ids"), "_id = ?", arrayOf("$thread"), null)
                ?.use { if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("recipient_ids")) else null }.orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
            ids.mapNotNull { id ->
                cr.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("address"), "_id = ?", arrayOf("$id"), null)
                    ?.use { if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow("address")) else null }   // ignores the columns asked for
            }
        }.getOrNull().orEmpty()

    // ---------- spam (decided on this phone only; nothing is sent anywhere) ----------

    private val LINK = Regex("""(https?://|www\.|\b[a-z0-9-]+\.(ly|link|xyz|top|info|club|site|online|shop|cc|me|io|co)/)""", RegexOption.IGNORE_CASE)
    private val SCAM = Regex(
        """\b(congrat\w*|you('ve| have)? won|winner|prize|gift ?card|claim (your|now)|reward points?|""" +
        """package|parcel|delivery (attempt|failed|issue)|redeliver\w*|usps|fedex|canada ?post|purolator|""" +
        """unpaid|overdue|outstanding (toll|balance)|toll (fee|bill)|final notice|""" +
        """(account|card) (has been |is |was )?(locked|suspended|restricted|disabled)|verify your|confirm your (identity|account)|""" +
        """refund|crypto|bitcoin|investment opportunit\w*|pre-?approved|loan offer|""" +
        """act now|limited time|urgent(ly)?|reply stop|txt stop|text stop)\b""", RegexOption.IGNORE_CASE)

    /** A text that reads like a typical scam or ad: has a link, or scam wording. */
    fun looksLikeSpam(text: String) = LINK.containsMatchIn(text) || SCAM.containsMatchIn(text)

    private fun idSet(ctx: Context, key: String) = prefs(ctx).getStringSet(key, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    private fun setId(ctx: Context, key: String, id: Long, on: Boolean) {
        val now = prefs(ctx).getStringSet(key, emptySet())!!.toMutableSet()
        if (on) now += "$id" else now -= "$id"
        prefs(ctx).edit().putStringSet(key, now).apply()
    }

    /** Careful filter: strangers only (not in contacts, never replied to), with a link or scam wording. Your choice always wins. */
    fun spamVerdict(ctx: Context, thread: Long, people: List<String>, latest: String, replied: Boolean): Boolean {
        if (thread in idSet(ctx, "notspam")) return false
        if (thread in idSet(ctx, "spam")) return true
        return !replied && people.none { contact(ctx, it) != null } && looksLikeSpam(latest)
    }

    /** Move a conversation to Spam, or back out of it (and keep it out). */
    fun markSpam(ctx: Context, thread: Long, spam: Boolean) {
        setId(ctx, "spam", thread, spam)
        setId(ctx, "notspam", thread, !spam)
        reload(ctx)
    }

    /** Numbers on the phone's own block list (shared with the Phone app; Google Messages blocks land here too). */
    fun isBlocked(ctx: Context, number: String) = runCatching { android.provider.BlockedNumberContract.isBlocked(ctx, number) }.getOrDefault(false)

    /** Block or unblock everyone in a conversation. Android then drops their texts and calls before they reach any app. */
    fun block(ctx: Context, numbers: List<String>, on: Boolean) = io.execute {
        val uri = android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI
        for (n in numbers) runCatching {
            if (on) ctx.contentResolver.insert(uri, ContentValues().apply { put(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, n) })
            else android.provider.BlockedNumberContract.unblock(ctx, n)
        }
        ui.post { reload(ctx) }
    }

    // ---------- names ----------

    private val names = HashMap<String, String>()
    private val contacts = HashMap<String, String?>()

    /** The contact's name, or null if they aren't in your contacts (or Tellem may not read them). */
    fun contact(ctx: Context, address: String): String? = synchronized(contacts) {
        if (address in contacts) return contacts[address]
        val n = if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() else null
        contacts[address] = n
        n
    }

    /** The contact's name if Tellem may read contacts, else the number, nicely spaced. */
    fun name(ctx: Context, address: String): String = synchronized(names) {
        names.getOrPut(address) { contact(ctx, address) ?: PhoneNumberUtils.formatNumber(address, Locale.getDefault().country) ?: address }
    }

    fun title(ctx: Context, addresses: List<String>) = addresses.joinToString(", ") { name(ctx, it) }.ifEmpty { "Unknown" }

    fun forgetNames() { synchronized(names) { names.clear() }; synchronized(contacts) { contacts.clear() } }

    // ---------- sending ----------

    private fun smsManager(ctx: Context, sub: Int = -1): SmsManager {
        @Suppress("DEPRECATION")
        val base = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        return if (sub >= 0) base.createForSubscriptionId(sub) else base
    }

    /** A plain text to one number (long ones go as several joined parts). */
    fun sendSms(ctx: Context, to: String, body: String) = io.execute {
        val app = ctx.applicationContext
        val uri = runCatching {
            app.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
                put("address", to); put("body", body); put("date", System.currentTimeMillis()); put("read", 1); put("seen", 1)
                put("thread_id", threadFor(app, listOf(to)))
            })
        }.getOrNull()
        runCatching {
            val sm = smsManager(app)
            val parts = sm.divideMessage(body)
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) sent += PendingIntent.getBroadcast(app, (uri.hashCode() * 31) + i,
                Intent(app, TextSentReceiver::class.java).setData(uri).putExtra("last", i == parts.size - 1),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (parts.size == 1) sm.sendTextMessage(to, null, body, sent[0], null)
            else sm.sendMultipartTextMessage(to, null, parts, sent, null)
        }.onFailure { uri?.let { u -> setSmsType(app, u, Telephony.Sms.MESSAGE_TYPE_FAILED) } }
    }

    fun setSmsType(ctx: Context, uri: Uri, type: Int) {
        runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put("type", type) }, null, null) }
    }

    /** A picture and/or group text. [image] is JPEG/PNG/GIF bytes, already small enough. */
    fun sendMms(ctx: Context, to: List<String>, body: String, image: Pair<ByteArray, String>?) = io.execute {
        val app = ctx.applicationContext
        val parts = ArrayList<Mms.Part>()
        image?.let { (b, mime) -> parts += Mms.Part(mime, b, "image." + mime.substringAfter('/').replace("jpeg", "jpg")) }
        if (body.isNotEmpty()) parts += Mms.Part("text/plain", body.toByteArray(), "text.txt")
        val row = runCatching { saveMms(app, Telephony.Mms.MESSAGE_BOX_OUTBOX, Mms.Pdu(Mms.SEND_REQ, to = to, parts = parts), to) }.getOrNull()
        runCatching {
            val f = File(mmsDir(app), "send-${System.nanoTime()}.pdu").apply { writeBytes(Mms.sendReq(to, parts)) }
            val sent = PendingIntent.getBroadcast(app, f.name.hashCode(),
                Intent(app, MmsSentReceiver::class.java).putExtra("row", row ?: -1L).putExtra("file", f.path),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            smsManager(app).sendMultimediaMessage(app, share(app, f, write = false), null, null, sent)
        }.onFailure { row?.let { setMmsBox(app, it, Telephony.Mms.MESSAGE_BOX_FAILED) } }
    }

    fun setMmsBox(ctx: Context, row: Long, box: Int) {
        if (row < 0) return
        runCatching { ctx.contentResolver.update(Uri.parse("content://mms/$row"), ContentValues().apply { put("msg_box", box) }, null, null) }
    }

    /** Fetch a notified picture/group text from the carrier (Android's MMS service does the download). */
    fun download(ctx: Context, row: Long, location: String, sub: Int) {
        val app = ctx.applicationContext
        val f = File(mmsDir(app), "get-$row.pdu").apply { delete() }
        val done = PendingIntent.getBroadcast(app, row.toInt(),
            Intent(app, MmsDownloadedReceiver::class.java).putExtra("row", row).putExtra("file", f.path).putExtra("sub", sub),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { smsManager(app, sub).downloadMultimediaMessage(app, location, share(app, f, write = true), null, done) }
            .onFailure { app.contentResolver.update(Uri.parse("content://mms/$row"), ContentValues().apply { put("st", DOWNLOAD_FAILED) }, null, null) }
    }

    /** Try a picture text that didn't download again. */
    fun retryDownload(ctx: Context, row: Long) = io.execute {
        ctx.contentResolver.query(Uri.parse("content://mms/$row"), arrayOf("ct_l", "sub_id"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let {
                ctx.contentResolver.update(Uri.parse("content://mms/$row"), ContentValues().apply { put("st", 0) }, null, null)
                download(ctx, row, it, c.getInt(1))
            }
        }
    }

    fun mmsDir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }

    /** A file Android's MMS service (in the phone process) may read, or write for downloads. */
    private fun share(ctx: Context, f: File, write: Boolean): Uri {
        if (write && !f.exists()) f.createNewFile()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        for (pkg in listOf("com.android.phone", "com.android.mms.service")) runCatching { ctx.grantUriPermission(pkg, uri, flags) }
        return uri
    }

    /** Store one MMS in Android's text storage, with its people and parts. Returns its row id. */
    fun saveMms(ctx: Context, box: Int, pdu: Mms.Pdu, people: List<String>, sub: Int = -1, read: Boolean = true, location: String? = null): Long {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put("thread_id", threadFor(ctx, people))
            put("date", if (pdu.date > 0) pdu.date else System.currentTimeMillis() / 1000)
            put("msg_box", box); put("read", if (read) 1 else 0); put("seen", if (read) 1 else 0)
            put("m_type", if (box == Telephony.Mms.MESSAGE_BOX_INBOX) Mms.RETRIEVE_CONF else Mms.SEND_REQ)
            put("v", 0x12); put("ct_t", pdu.contentType ?: "application/vnd.wap.multipart.related")
            pdu.messageId?.let { put("m_id", it) }; pdu.tid?.let { put("tr_id", it) }
            location?.let { put("ct_l", it) }   // so a repeated carrier notice is recognised after the download
            pdu.subject?.let { put("sub", it); put("sub_cs", Mms.UTF8) }
            if (sub >= 0) put("sub_id", sub)
        }
        val row = cr.insert(Telephony.Mms.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull() ?: error("not saved")
        fun addr(a: String, type: Int) = cr.insert(Uri.parse("content://mms/$row/addr"), ContentValues().apply { put("address", a); put("type", type); put("charset", Mms.UTF8) })
        if (box == Telephony.Mms.MESSAGE_BOX_INBOX) {
            addr(pdu.from ?: people.firstOrNull() ?: "", 137)
            pdu.to.forEach { addr(it, 151) }; pdu.cc.forEach { addr(it, 130) }
        } else {
            addr("insert-address-token", 137)
            people.forEach { addr(it, 151) }
        }
        for (p in pdu.parts) {
            if (p.mime == "application/smil") continue
            val pv = ContentValues().apply {
                put("ct", p.mime); p.name?.let { put("name", it); put("cl", it) }; p.cid?.let { put("cid", "<$it>") }
                if (p.mime.startsWith("text/")) { put("chset", Mms.UTF8); put("text", p.text) }
            }
            val pu = cr.insert(Uri.parse("content://mms/$row/part"), pv) ?: continue
            if (!p.mime.startsWith("text/")) cr.openOutputStream(pu)?.use { it.write(p.data) }
        }
        return row
    }

    /** Numbers that are this phone's own (left out of group threads), if Android lets us see them. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun ownNumbers(ctx: Context): List<String> = runCatching {
        val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
        sm.activeSubscriptionInfoList.orEmpty().mapNotNull {
            if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(it.subscriptionId) else it.number
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList()) + listOfNotNull(prefs(ctx).getString("self", null))

    /** Many SIMs don't report their own number: learn it from a picture text sent only to us. */
    fun learnOwnNumber(ctx: Context, n: String) { if (n.any(Char::isDigit)) prefs(ctx).edit().putString("self", n).apply() }

    fun same(a: String, b: String) = PhoneNumberUtils.compare(a, b)

    // ---------- notifications ----------

    const val CHANNEL = "texts"
    const val NOTE_TAG = "text"

    const val SPAM_CHANNEL = "texts_spam"

    fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
        NotificationChannel(CHANNEL, "Text messages", NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel(SPAM_CHANNEL, "Possible spam texts", NotificationManager.IMPORTANCE_MIN),   // no sound, no pop-up
    ))

    /** A new text, shown like Tellem's messages and following the same privacy choices. Blocking. */
    fun notify(ctx: Context, thread: Long) {
        if (thread == openThread) return
        channel(ctx)
        val pr = prefs(ctx)
        val showNames = pr.getBoolean("names", true)
        val showText = pr.getBoolean("text", true) && !pr.getBoolean("lock", false)
        val people = addressesOf(ctx, thread)
        val recent = messages(ctx, thread, 30)
        val spam = spamVerdict(ctx, thread, people, recent.lastOrNull { !it.mine }?.body.orEmpty(), recent.any { it.mine })
        val title = (if (spam) "Possible spam · " else "") + if (showNames) title(ctx, people) else APP_NAME
        val open = PendingIntent.getActivity(ctx, ("t$thread").hashCode(), Intent(ctx, MainActivity::class.java).putExtra("textThread", thread),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, if (spam) SPAM_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText("New text message").build())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setContentTitle(title)
        // what they sent since your last reply (at most 6 lines)
        val unread = if (showNames && showText) recent.takeLastWhile { !it.mine }.takeLast(6) else emptyList()
        if (unread.isEmpty()) b.setContentText("New text message")
        else {
            @Suppress("DEPRECATION")
            val style = if (Build.VERSION.SDK_INT >= 28) Notification.MessagingStyle(android.app.Person.Builder().setName("You").build())
            else Notification.MessagingStyle("You")
            if (people.size > 1) style.conversationTitle = title.also { style.isGroupConversation = true }
            for (t in unread) {
                val who = t.from?.let { name(ctx, it) } ?: title
                val line = t.body.ifEmpty { if (t.attachments.isNotEmpty()) "📷 Picture" else "Picture message" }
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 28) style.addMessage(line, t.date, android.app.Person.Builder().setName(who).build())
                else style.addMessage(line, t.date, who)
            }
            b.setStyle(style).setContentText(unread.last().body)
        }
        if (showNames && people.isNotEmpty() && !spam) {
            val pi = PendingIntent.getBroadcast(ctx, ("r$thread").hashCode(), Intent(ctx, TextReplyReceiver::class.java).putExtra("thread", thread),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)   // mutable: Android fills in the typed reply
            b.addAction(Notification.Action.Builder(null, "Reply", pi).addRemoteInput(RemoteInput.Builder(TextReplyReceiver.KEY).setLabel("Text message").build()).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(NOTE_TAG, thread.toInt(), b.build())
    }

    /** One text or picture text to everyone in [people] (several people = a group text). */
    fun send(ctx: Context, people: List<String>, body: String, image: Pair<ByteArray, String>? = null) {
        if (people.size == 1 && image == null) sendSms(ctx, people[0], body) else sendMms(ctx, people, body, image)
    }

    /** Carrier size limit for one picture text (bytes). */
    fun mmsLimit(ctx: Context): Int = runCatching {
        smsManager(ctx).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 300_000)
    }.getOrDefault(300_000).coerceIn(100_000, 1_000_000)
}
