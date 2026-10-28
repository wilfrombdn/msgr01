package com.wilfrombdn.telegramnt

import com.google.crypto.tink.KeysetHandle
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import java.util.UUID
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

class Msg(val id: String, val mine: Boolean, var text: String, val ts: Long, var state: Int) {
    /** Attachment: id (on server), k (key), n (name), m (type), s (size), th (preview), local (saved here). */
    var file: JSONObject? = null
    /** Disappearing messages: epoch ms when this is wiped from both phones, or 0 = never. */
    var expires: Long = 0
    /** In a group, the @username of whoever sent it (shown above the bubble). */
    var sender: String? = null
    /** Emoji reactions: @username -> emoji (one per person). */
    val reactions: MutableMap<String, String> = mutableMapOf()
    /** The message this one replies to: id, u (its author's @username), p (short preview, used if it's gone). */
    var reply: JSONObject? = null
    /** A call summary instead of a message: d = "out"/"in", s = ok/missed/declined/cancelled/busy/noanswer/failed, dur = seconds. */
    var call: JSONObject? = null
    /** The sender changed the text after sending. */
    var edited = false
    /** Forwarded: the @username of whoever first wrote it. */
    var fwd: String? = null
    /** Link preview card made by the sender: u, s (site), t (title), d (description). */
    var lp: JSONObject? = null
    /** A group poll: q (question), o (options). Votes: @username -> option index. */
    var poll: JSONObject? = null
    val votes: MutableMap<String, Int> = mutableMapOf()
    /** A shared location: la, lo (degrees), acc (metres). */
    var loc: JSONObject? = null
    /** Sent without sound: the other phone doesn't notify. */
    var silent = false
    /** SCHEDULED messages go out at this time (epoch ms). */
    var sendAt = 0L

    fun json(): JSONObject = JSONObject().put("id", id).put("mine", mine).put("text", text).put("ts", ts).put("state", state)
        .put("file", file).put("expires", expires).put("sender", sender)
        .put("reactions", JSONObject(reactions as Map<*, *>)).put("reply", reply).put("call", call)
        .put("edited", edited).put("fwd", fwd).put("lp", lp).put("sil", silent).put("at", sendAt).put("loc", loc)
        .put("poll", poll).put("votes", JSONObject(votes as Map<*, *>))

    companion object {
        const val SENDING = 0
        const val SENT = 1
        const val RECEIVED = 2
        const val FAILED = 3
        const val NOTE = 4 // grey line in the middle, e.g. "You are now friends"
        const val SCHEDULED = 5 // mine, waiting on this phone until [sendAt]
        /** A reply reference built by us, or received (then only well-formed, short fields are kept). */
        fun replyRef(r: JSONObject?): JSONObject? {
            r ?: return null
            val id = r.optString("id"); val u = r.optString("u")
            if (id.isEmpty() || id.length > 64 || u.length > 40) return null
            return JSONObject().put("id", id).put("u", u).put("p", r.optString("p").take(160))
        }
        fun from(o: JSONObject) = Msg(o.getString("id"), o.getBoolean("mine"), o.getString("text"), o.getLong("ts"), o.getInt("state"))
            .also {
                it.file = o.optJSONObject("file"); it.expires = o.optLong("expires"); it.sender = o.optString("sender").ifEmpty { null }
                o.optJSONObject("reactions")?.let { r -> for (k in r.keys()) it.reactions[k] = r.getString(k) }
                it.reply = o.optJSONObject("reply")
                it.call = o.optJSONObject("call")
                it.edited = o.optBoolean("edited"); it.fwd = o.optString("fwd").ifEmpty { null }
                it.lp = o.optJSONObject("lp")
                it.silent = o.optBoolean("sil"); it.sendAt = o.optLong("at")
                it.loc = o.optJSONObject("loc")
                it.poll = o.optJSONObject("poll")
                o.optJSONObject("votes")?.let { v -> for (k in v.keys()) it.votes[k] = v.optInt(k) }
            }
        /** A poll off the wire: a short question and 2-10 short options. */
        fun pollOf(o: JSONObject?): JSONObject? {
            o ?: return null
            val q = o.optString("q").trim().take(300).ifEmpty { return null }
            val arr = o.optJSONArray("o") ?: return null
            val opts = (0 until minOf(arr.length(), 10)).map { arr.optString(it).trim().take(100) }.filter { it.isNotEmpty() }
            if (opts.size < 2) return null
            return JSONObject().put("q", q).put("o", JSONArray(opts))
        }
        /** A location off the wire: only real coordinates are kept. */
        fun locOf(o: JSONObject?): JSONObject? {
            o ?: return null
            val la = o.optDouble("la", Double.NaN); val lo = o.optDouble("lo", Double.NaN)
            if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) return null
            return JSONObject().put("la", la).put("lo", lo).put("acc", o.optDouble("acc", 0.0).coerceIn(0.0, 100_000.0))
        }
        /** A forwarded-from name off the wire: only a well-formed @username is kept. */
        fun fwdOf(o: JSONObject?) = o?.optString("fw")?.takeIf { Core.USERNAME.matches(it) }
    }
}

/**
 * A friend (or friend request). Each friendship has its own pair of mailboxes,
 * so the server can't tell from the addresses who is talking to whom.
 */
class Contact(
    val user: String,
    var name: String,
    var status: Int,
    val myToken: String,
    var theirAddr: String?,
    val theirEnc: String,
    val theirSig: String,
    var verified: Boolean = false,
    var unread: Int = 0,
    val created: Long = System.currentTimeMillis(),
    val msgs: MutableList<Msg> = mutableListOf(),
    var avatar: ByteArray? = null,
    var theirInbox: String? = null,   // where message requests go before they accept
    var waitingForAdd: Boolean = false, // they accepted my messages but didn't add me as a friend
    var theirIdent: String? = null,     // their Signal identity key, from the directory
    var ttl: Int = 0,                   // disappearing-messages timer in seconds (0 = off), shared by both sides
    var theirReadUpto: Long = 0,        // my sent messages up to this time have been read by them
    var net: String = Core.NET_TOR,     // how they connect ("tor" / "direct"); calls need both on the same one
    var theirSeen: Long = 0,            // when they last had the app open (0 = unknown / they hide it)
    var pinned: String? = null,         // id of the message pinned at the top of the chat (shared by both)
) {
    @Volatile var theirTypingUntil = 0L  // show "typing…" until then
    @Volatile var theirOnlineUntil = 0L  // show "online" until then (refreshed while their app is open)
    val myAddr get() = Crypto.addressOf(myToken)
    val lastTs get() = msgs.lastOrNull()?.ts ?: created
    val canChat get() = status == Contact.FRIENDS || status == Contact.CHATTING || status == Contact.SAVED

    fun json(withMsgs: Boolean): JSONObject = JSONObject()
        .put("user", user).put("name", name).put("status", status).put("myToken", myToken)
        .put("theirAddr", theirAddr).put("theirEnc", theirEnc).put("theirSig", theirSig)
        .put("verified", verified).put("created", created).put("theirInbox", theirInbox).put("waitingForAdd", waitingForAdd).put("theirIdent", theirIdent).put("ttl", ttl).put("net", net).put("seen", theirSeen).put("pinned", pinned)
        .apply {
            if (withMsgs) put("unread", unread).put("msgs", JSONArray(msgs.map { it.json() }))
                .put("avatar", avatar?.let { Base64.getEncoder().encodeToString(it) })
        }

    companion object {
        const val ASKED = 0    // I sent a friend request
        const val ASKING = 1   // they sent me one
        const val FRIENDS = 2
        const val CHATTING = 3 // message request accepted, but not friends
        const val SAVED = 4    // "Saved Messages": a chat with yourself, kept only on this phone (nothing is sent)

        fun from(o: JSONObject): Contact {
            val c = Contact(
                o.getString("user"), o.getString("name"), o.getInt("status"), o.getString("myToken"),
                o.optString("theirAddr").ifEmpty { null }, o.getString("theirEnc"), o.getString("theirSig"),
                o.optBoolean("verified"), o.optInt("unread"), o.optLong("created"),
            )
            val a = o.optJSONArray("msgs") ?: JSONArray()
            for (i in 0 until a.length()) c.msgs.add(Msg.from(a.getJSONObject(i)))
            c.theirInbox = o.optString("theirInbox").ifEmpty { null }
            c.waitingForAdd = o.optBoolean("waitingForAdd")
            c.theirIdent = o.optString("theirIdent").ifEmpty { null }
            c.ttl = o.optInt("ttl")
            c.net = o.optString("net", Core.NET_TOR).ifEmpty { Core.NET_TOR }
            c.theirSeen = o.optLong("seen", 0)
            c.pinned = o.optString("pinned").ifEmpty { null }
            c.avatar = o.optString("avatar").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            return c
        }
    }
}

/** The signed-in account. The keys and backup key stay on this phone. */
class Me(
    val user: String,
    var name: String,
    var device: String,
    val enc: KeysetHandle,
    val sig: KeysetHandle,
    val inboxToken: String,
    val backupKey: ByteArray,
    var avatar: ByteArray? = null,
    var signal: SignalStore = SignalStore.create(),
    var keysPublished: Boolean = false,
    var cloudBackup: Boolean = true, // false = account only works on this phone, server keeps no backup
) {
    val address get() = SignalProtocolAddress(user, 1)
    val inboxAddr get() = Crypto.addressOf(inboxToken)
    val sigPub: ByteArray get() = Crypto.publicBytes(sig)
}

/**
 * A group chat. Encryption uses Signal "sender keys": each member sends one sealed distribution
 * message to the group's shared mailbox, after which everyone can read everyone's messages. The
 * server only ever sees blobs sealed with the group key (derived from the group's secret).
 */
class Group(
    val id: String,
    var name: String,
    var secret: String,          // the shared secret; whoever has it is in the group (rotated on a re-key)
    val public: Boolean,         // true = joinable by invite link; false = friends added directly
    var cursor: Long = 0,        // last group-mailbox position read
    var unread: Int = 0,
    val created: Long = System.currentTimeMillis(),
    val members: MutableSet<String> = mutableSetOf(),
    val msgs: MutableList<Msg> = mutableListOf(),
    val owner: String = "",      // creator @username; anchored into the address so a tampered link can't claim it. "" = legacy group (no roles)
) {
    @Volatile var distSent = false   // have I posted my sender key this session (not persisted)
    @Volatile var distAt = 0L        // when I last posted it (not persisted); re-posted so late joiners can read me
    val leaders = mutableSetOf<String>()  // @usernames allowed to change settings (owner is always one)
    var readOnly = false                  // true = only leaders can post (client-enforced)
    var setVer = 0                        // version counter for settings changes (newer wins)
    var epoch = 0                         // re-key generation; a higher epoch's secret replaces a lower one
    var pinned: String? = null            // id of the pinned message (set by an admin)
    var mentioned = false                 // someone @mentioned me in a message I haven't seen yet
    val typing = java.util.concurrent.ConcurrentHashMap<String, Long>()   // @username -> show "typing…" until (not saved)
    val pending = mutableListOf<Triple<String, String, ByteArray>>() // gmsgs waiting for a sender key
    val pendingCtl = mutableListOf<Pair<String, ByteArray>>()        // gctl controls waiting for a sender key
    val rekeyDue = mutableSetOf<String>()               // owner: members a leave means I still have to re-key out
    val rekeyResend = mutableListOf<ResendItem>()       // owner: re-key blobs to re-post so offline members still migrate
    /** A sealed re-key announcement the owner re-posts to the old mailbox until [until], so a member who was
     *  offline during the re-key still finds it when they come back (the group mailbox TTL is short). */
    class ResendItem(val addr: String, val blob: String, val until: Long, var last: Long)
    val lastTs get() = msgs.lastOrNull()?.ts ?: created
    // owner-anchored v2 derivation for new groups; legacy groups (no owner) keep the old one
    val addr get() = Crypto.sha256((if (owner.isEmpty()) "msgr01-group|$secret" else "msgr01-group-v2|$owner|$secret").toByteArray()).hex()
    val key get() = Crypto.sha256((if (owner.isEmpty()) "msgr01-group-key|$secret" else "msgr01-group-key-v2|$owner|$secret").toByteArray())
    val distId: UUID get() = UUID.nameUUIDFromBytes(secret.unhex())

    /** Legacy groups have no roles (everyone equal); new groups: the owner and named leaders. */
    fun isLeader(user: String) = owner.isEmpty() || user == owner || user in leaders
    fun canPost(user: String) = !readOnly || isLeader(user)

    fun json(withMsgs: Boolean): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("secret", secret).put("public", public)
        .put("cursor", cursor).put("created", created).put("members", JSONArray(members.toList()))
        .put("owner", owner).put("leaders", JSONArray(leaders.toList())).put("readOnly", readOnly).put("setVer", setVer).put("epoch", epoch).put("pinned", pinned).put("mentioned", mentioned)
        .put("rekeyDue", JSONArray(rekeyDue.toList()))
        .put("rekeyResend", JSONArray(rekeyResend.map { JSONObject().put("addr", it.addr).put("blob", it.blob).put("until", it.until).put("last", it.last) }))
        .apply { if (withMsgs) put("unread", unread).put("msgs", JSONArray(msgs.map { it.json() })) }

    companion object {
        fun from(o: JSONObject): Group {
            val g = Group(o.getString("id"), o.getString("name"), o.getString("secret"), o.getBoolean("public"),
                o.optLong("cursor"), o.optInt("unread"), o.optLong("created"), owner = o.optString("owner"))
            o.optJSONArray("members")?.let { for (i in 0 until it.length()) g.members.add(it.getString(i)) }
            o.optJSONArray("leaders")?.let { for (i in 0 until it.length()) g.leaders.add(it.getString(i)) }
            g.readOnly = o.optBoolean("readOnly"); g.setVer = o.optInt("setVer"); g.epoch = o.optInt("epoch")
            g.pinned = o.optString("pinned").ifEmpty { null }
            g.mentioned = o.optBoolean("mentioned")
            o.optJSONArray("rekeyDue")?.let { for (i in 0 until it.length()) g.rekeyDue.add(it.getString(i)) }
            o.optJSONArray("rekeyResend")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { r ->
                g.rekeyResend.add(ResendItem(r.getString("addr"), r.getString("blob"), r.getLong("until"), r.optLong("last")))
            } }
            o.optJSONArray("msgs")?.let { for (i in 0 until it.length()) g.msgs.add(Msg.from(it.getJSONObject(i))) }
            return g
        }
    }
}

class UserError(msg: String) : IOException(msg)

/**
 * All the chat rules, with no Android in it, so it can be tested on a computer.
 * Network calls happen on the caller's thread; state changes are synchronized.
 */
class Core(server: String, private val socks: () -> Int? = { null }) {
    var relay = Relay(server, socks)
    @Volatile var me: Me? = null
    val contacts = mutableListOf<Contact>()
    val groups = mutableListOf<Group>()

    /** Friend whose chat is on screen (no unread count for it). */
    @Volatile var openChat: String? = null
    @Volatile var backupDirty = false
    @Volatile var unreadable = 0   // diagnostic: messages fetched but not decryptable (dropped). Shown in Settings.
    @Volatile var lastDrop = ""    // diagnostic: why the last fetched message was dropped (which step). Shown in Settings.
    private fun drop(reason: String) { lastDrop = reason }
    var readReceipts = true   // tell friends when I've read their messages (and so see theirs)
    var showTyping = true     // tell a friend when I'm typing to them
    var showSeen = true       // tell friends when I'm online / last seen
    /** @usernames I've blocked: nothing from them gets through and they get no signals from me. They aren't told. */
    val blocked: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    var onChange: () -> Unit = {}
    var onNew: (Contact) -> Unit = {}
    /** Someone @mentioned me in group [g] (groups don't notify otherwise). */
    var onMention: (Group) -> Unit = {}
    /** Where encrypted attachments are kept on this device (Android: files folder; tests: memory). */
    var saveBlob: (String, ByteArray) -> Unit = { _, _ -> }
    var loadBlob: (String) -> ByteArray? = { null }
    var deleteBlob: (String) -> Unit = {}
    var blobSize: (String) -> Long = { 0L }

    /** Bytes this phone stores for these messages' photos, videos and files. */
    fun mediaBytes(msgs: List<Msg>): Long =
        msgs.sumOf { m -> m.file?.optString("local")?.takeIf { it.isNotEmpty() }?.let(blobSize) ?: 0L }

    /**
     * Removes attachments (sent before [olderThan]) from this phone to free space. The message and
     * its small blurry preview stay; it's marked "cleared" so nothing tries to download it again.
     * Attachments still waiting to be sent are left alone.
     */
    fun clearMedia(msgs: List<Msg>, olderThan: Long = Long.MAX_VALUE): Int {
        var n = 0
        synchronized(this) {
            for (m in msgs) {
                val f = m.file ?: continue
                val local = f.optString("local")
                if (local.isEmpty() || m.ts > olderThan || (m.mine && !f.has("id"))) continue
                deleteBlob(local)
                f.remove("local"); f.put("cleared", true)
                toFetch.remove(m)
                n++
            }
        }
        if (n > 0) onChange()
        return n
    }

    /** Every message on this phone (all chats and groups). */
    fun allMsgs(): List<Msg> = synchronized(this) { contacts.flatMap { it.msgs } + groups.flatMap { it.msgs } }
    private val toFetch = mutableListOf<Msg>()

    private val acks = mutableSetOf<String>()

    @Synchronized fun contact(user: String) = contacts.firstOrNull { it.user == user }
    @Synchronized fun group(id: String) = groups.firstOrNull { it.id == id }
    @Synchronized fun sorted(): List<Contact> = contacts.sortedByDescending { it.lastTs }

    // ---------- account ----------

    fun signup(user: String, password: String, name: String) {
        checkUsername(user)
        if (password.length < 10) throw UserError("Password needs at least 10 characters")
        val keys = Crypto.passwordKeys(user, password)
        val m = Me(user, name.trim().take(60).ifEmpty { user }, "", Crypto.newKeyset(Crypto.ENC_TEMPLATE),
            Crypto.newKeyset(Crypto.SIG_TEMPLATE), Crypto.randomHex(), keys.backup)
        try {
            m.device = relay.signup(user, keys.auth, Crypto.publicBytes(m.enc), m.sigPub, m.inboxToken, lockedBackup(m, emptyList()))
        } catch (e: Relay.RelayError) {
            throw UserError(if (e.code == 409) "That username is taken" else e.reason.ifEmpty { "Server error" })
        }
        synchronized(this) { me = m; contacts.clear(); acks.clear() }
        runCatching { publishKeys(m, replace = true) }
        onChange()
    }

    /**
     * [keepStore] is this account's full Signal store, kept from a log-out on THIS phone. When present we
     * reuse it (same prekeys + sessions) and only top up one-time keys — so a friend request another
     * account already encrypted to our old prekeys can still be read after we log back in. Without it
     * (a genuine new phone) we start a fresh store with new prekeys, as before.
     */
    fun login(user: String, password: String, keepStore: SignalStore? = null) {
        checkUsername(user)
        val keys = Crypto.passwordKeys(user, password)
        val (device, locked) = try {
            relay.login(user, keys.auth)
        } catch (e: Relay.RelayError) {
            throw UserError(if (e.code == 403) e.reason.replaceFirstChar { it.uppercase() } else "Server error")
        }
        if (locked.isEmpty()) throw UserError("This account keeps no cloud backup, so it can only be used on the phone that made it.")
        val plain = Crypto.unlockBackup(keys.backup, user, locked) ?: throw UserError("Couldn't open your backup")
        val o = JSONObject(String(plain))
        val enc = Crypto.parsePrivate(unb64(o.getString("enc")), Crypto.ENC_TEMPLATE) ?: throw UserError("Backup damaged")
        val sig = Crypto.parsePrivate(unb64(o.getString("sig")), Crypto.SIG_TEMPLATE) ?: throw UserError("Backup damaged")
        val m = Me(user, o.optString("name", user), device, enc, sig, o.getString("inbox"), keys.backup,
            o.optString("avatar").takeIf { it.isNotEmpty() }?.let { unb64(it) })
        if (keepStore != null) {
            m.signal = keepStore                     // same phone: keep prekeys/sessions so in-flight messages still decrypt
        } else {
            // Same Signal identity as before (so safety codes don't change), but fresh prekeys and sessions.
            o.optString("sident").takeIf { it.isNotEmpty() }?.let { m.signal = SignalStore(IdentityKeyPair(unb64(it)), o.getInt("sreg")) }
        }
        publishKeys(m, replace = keepStore == null)  // rotate keys only on a genuine new-phone login
        val list = o.optJSONArray("contacts") ?: JSONArray()
        synchronized(this) {
            me = m
            contacts.clear()
            acks.clear()
            for (i in 0 until list.length()) contacts.add(Contact.from(list.getJSONObject(i)))
            for (c in contacts) if (c.status == Contact.FRIENDS) c.msgs.add(note("Chat history stays on your old phone"))
            groups.clear()
            o.optJSONArray("groups")?.let { for (i in 0 until it.length()) groups.add(Group.from(it.getJSONObject(i)).also { g -> g.cursor = 0 }) }
        }
        onChange()
        // Friends' photos aren't in the backup: send ours and ask for theirs. This also starts
        // new Signal sessions, since the old ones stayed on the old phone.
        for (c in synchronized(this) { contacts.filter { it.status == Contact.FRIENDS } }) runCatching { sendAvatar(c, ask = true) }
        for (g in synchronized(this) { groups.toList() }) runCatching { postMyDist(m, g) }
    }

    fun deleteAccount(password: String) {
        val m = me ?: return
        relay.deleteAccount(m.user, Crypto.passwordKeys(m.user, password).auth)
        signOut()
    }

    fun signOut() {
        synchronized(this) { me = null; contacts.clear(); acks.clear() }
        onChange()
    }

    /** False when someone signed in to this account on another phone. */
    fun sessionOk(): Boolean {
        val m = me ?: return true
        return relay.sessionOk(m.user, m.device)
    }

    /** Puts my Signal prekeys on the server. [replace] = new phone: forget the old phone's keys. */
    fun publishKeys(m: Me, replace: Boolean) {
        val count = relay.publishKeys(m.user, m.device, m.signal.freshKeys(if (replace) 50 else 40, rotateSigned = replace), replace)
        m.keysPublished = count > 0
        onChange()
    }

    /** Called now and then: tops up one-time prekeys when they run low. */
    fun keepKeysStocked() {
        val m = me ?: return
        if (!m.keysPublished) return publishKeys(m, replace = true)
        if (relay.keyCount(m.user, m.device) < 15) publishKeys(m, replace = false)
    }

    /** Turns the server-side encrypted backup on or off. Off also wipes the copy already there. */
    fun setCloudBackup(on: Boolean) {
        val m = me ?: return
        m.cloudBackup = on
        backupDirty = false
        relay.saveBackup(m.user, m.device, lockedBackup(m, synchronized(this) { contacts.filter { it.status != Contact.SAVED } }))
        changed()
    }

    fun uploadBackup() {
        val m = me ?: return
        backupDirty = false
        try {
            relay.saveBackup(m.user, m.device, lockedBackup(m, synchronized(this) { contacts.filter { it.status != Contact.SAVED } }))
        } catch (e: Exception) {
            backupDirty = true
            throw e
        }
    }

    private fun lockedBackup(m: Me, list: List<Contact>): ByteArray {
        if (!m.cloudBackup) return ByteArray(0) // user chose to keep no cloud backup
        val o = JSONObject().put("v", 2).put("name", m.name)
            .put("enc", b64(Crypto.privateBytes(m.enc))).put("sig", b64(Crypto.privateBytes(m.sig)))
            .put("inbox", m.inboxToken)
            .put("avatar", m.avatar?.let { b64(it) })
            .put("sident", b64(m.signal.identity.serialize())).put("sreg", m.signal.regId)
            .put("groups", JSONArray(synchronized(this) { groups.map { it.json(withMsgs = false) } }))
            .put("contacts", JSONArray(list.map { it.json(withMsgs = false) }))
        return Crypto.lockBackup(m.backupKey, m.user, o.toString().toByteArray())
    }

    // ---------- friends ----------

    /** Sends a friend request to @user. Returns the new (or existing) chat. */
    fun addFriend(raw: String): Contact {
        val m = me ?: throw UserError("Not signed in")
        val user = raw.trim().removePrefix("@").lowercase()
        checkUsername(user)
        if (user == m.user) throw UserError("That's you!")
        contact(user)?.let { return it }
        val p = relay.lookup(user) ?: throw UserError("No one has the username @$user")
        if (p.ident.isEmpty()) throw UserError("@$user needs to update MSNGR first")
        val c = Contact(user, "@$user", Contact.ASKED, Crypto.randomHex(), null, p.enc, p.sig, theirInbox = p.inbox, theirIdent = p.ident)
        relay.fetch(listOf(c.myToken), emptyList(), 0) // my mailbox exists before they can answer
        sealSignal(m, c, p.inbox, JSONObject().put("t", "request").put("u", m.user).put("n", m.name).put("a", c.myAddr))
        c.msgs.add(note("Friend request sent. Anything you write now arrives as a message request."))
        synchronized(this) { if (contact(user) == null) contacts.add(c) }
        changed()
        return contact(user)!!
    }

    /** Accepts a request: [friend] = also add them as a friend, otherwise just allow messages. */
    fun accept(c: Contact, friend: Boolean = true) {
        val m = me ?: return
        relay.fetch(listOf(c.myToken), emptyList(), 0)
        sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "accept").put("u", m.user).put("n", m.name)
            .put("a", c.myAddr).put("f", friend))
        synchronized(this) {
            c.status = if (friend) Contact.FRIENDS else Contact.CHATTING
            c.msgs.add(note(if (friend) "You are now friends. Check the safety code to be sure it's really them."
            else "Message request accepted. You can add @${c.user} as a friend any time."))
        }
        changed()
        flush(c)
        if (friend) runCatching { sendAvatar(c, ask = false) }
    }

    /** From a message-request chat I accepted: add them as a friend after all. */
    fun makeFriend(c: Contact) = accept(c, friend = true)

    // ---------- groups ----------

    fun createGroup(name: String, public: Boolean, invite: List<String> = emptyList()): Group {
        val m = me ?: throw UserError("Not signed in")
        val g = Group(Crypto.randomHex(8), name.trim().take(80).ifEmpty { "Group" }, Crypto.randomHex(), public, owner = m.user)
        g.members.add(m.user)
        g.leaders.add(m.user)
        postMyDist(m, g)
        synchronized(this) { groups.add(g) }
        g.msgs.add(note(if (public) "Group created. Share the invite link to let people join." else "Group created. Add friends from the group menu."))
        changed()
        for (u in invite) runCatching { inviteToGroup(g, u) }
        return g
    }

    /** Private group: send a friend a sealed 1:1 invite. */
    fun inviteToGroup(g: Group, rawUser: String) {
        val m = me ?: return
        val user = rawUser.trim().removePrefix("@").lowercase()
        val c = contact(user) ?: throw UserError("@$user isn't in your friends")
        if (!c.canChat) throw UserError("@$user hasn't accepted you yet")
        sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "ginvite").put("secret", g.secret).put("n", g.name).put("public", g.public).put("o", g.owner))
        synchronized(this) { g.members.add(user) }
        changed()
    }

    fun groupInviteLink(g: Group): String {
        val o = JSONObject().put("s", g.secret).put("n", g.name).put("o", g.owner)
        return "msgr01://group#" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    fun joinGroupLink(link: String): Group {
        val frag = link.trim().substringAfter('#', "")
        val o = try { JSONObject(String(Base64.getUrlDecoder().decode(frag))) } catch (e: Exception) { throw UserError("That isn't a valid group link") }
        val secret = o.optString("s")
        if (secret.length != 64 || !secret.all { it in "0123456789abcdef" }) throw UserError("That isn't a valid group link")
        return joinGroup(secret, o.optString("n").take(80).ifBlank { "Group" }, public = true, owner = o.optString("o"))
    }

    private fun joinGroup(secret: String, name: String, public: Boolean, owner: String = ""): Group {
        val m = me ?: throw UserError("Not signed in")
        synchronized(this) { groups.firstOrNull { it.secret == secret } }?.let { return it }
        val g = Group(Crypto.randomHex(8), name, secret, public, owner = owner)
        if (owner.isNotEmpty()) g.leaders.add(owner)   // seed the root of trust from the invite
        g.members.add(m.user)
        postMyDist(m, g)
        synchronized(this) { groups.add(g) }
        g.msgs.add(note("You joined \"$name\"."))
        changed()
        return g
    }

    fun leaveGroup(g: Group) {
        val m = me
        if (m != null) runCatching {
            // authenticated leave (gctl): proves it's really me, so the owner can safely re-key me out.
            gctl(g, m, JSONObject().put("t", "leave"))
            // legacy outer leave too, so clients older than 4.2 still drop me from their member list.
            relay.gsend(g.addr, gseal(g, JSONObject().put("t", "leave").put("u", m.user)))
        }
        synchronized(this) { groups.remove(g) }
        changed()
    }

    /**
     * Owner-only: rotate the group to a fresh secret, excluding everyone in [remove]. The new secret is
     * sealed individually to each remaining member's directory key inside an authenticated gctl posted to
     * the OLD mailbox — a removed member can read the announcement but can't open any member's piece, and
     * can't forge one (the gctl is encrypted with the owner's sender key). After this, new messages go to a
     * new mailbox the removed member can't read. Everyone must be on 4.2+ or they're effectively removed.
     */
    fun rekeyGroup(g: Group, remove: Set<String>) = synchronized(g.rekeyDue) {   // one re-key at a time per group
        val m = me ?: throw UserError("Not signed in")
        if (g.owner.isEmpty() || m.user != g.owner) throw UserError("Only the group owner can remove members")
        val removeN = remove.map { it.removePrefix("@").lowercase() }.toSet()
        val kept = synchronized(this) { g.members.filter { it != m.user && it !in removeN } }
        val newSecret = Crypto.randomHex()
        val newEpoch = g.epoch + 1
        val keys = JSONObject()
        for (u in kept) {
            // a friend's key is pinned; otherwise take it from the directory. lookup() returns null only for a
            // deregistered @user (safe to drop); a network/429 error throws and aborts the whole re-key.
            val encStr = contact(u)?.takeIf { it.status == Contact.FRIENDS }?.theirEnc ?: relay.lookup(u)?.enc ?: continue
            val encKey = Crypto.parsePublic(unb64(encStr), Crypto.ENC_TEMPLATE) ?: throw IOException("bad key for @$u")
            keys.put(u, b64(Crypto.seal(m.sig, encKey, rekeyBind(g.owner, g.secret, newEpoch, u), newSecret.toByteArray())))
        }
        val newLeaders = synchronized(this) { g.leaders.filter { it !in removeN } }
        val oldAddr = g.addr
        val bytes = gctlBytes(g, m, JSONObject().put("t", "rekey").put("ep", newEpoch).put("n", g.name)
            .put("public", g.public).put("ro", g.readOnly).put("ldr", JSONArray(newLeaders)).put("keys", keys))
        relay.gsend(oldAddr, bytes)
        val now = System.currentTimeMillis()
        // keep re-posting to the old mailbox so a member offline during the re-key still finds it (up to MSG_TTL)
        synchronized(this) { g.rekeyResend.add(Group.ResendItem(oldAddr, b64(bytes), now + REKEY_RESEND_WINDOW, now)) }
        migrateGroup(g, newSecret, newEpoch, removeN, g.name, g.readOnly, newLeaders.toSet())
    }

    /** Owner: if a leave (or a failed earlier attempt) left members to re-key out, do it now. Durable: on
     *  failure the names stay in rekeyDue and the next poll retries. */
    private fun drainRekeyDue(g: Group) {
        val due = synchronized(g.rekeyDue) { g.rekeyDue.toSet() }
        if (due.isEmpty()) return
        try {
            rekeyGroup(g, due)
            synchronized(g.rekeyDue) { g.rekeyDue.removeAll(due) }
        } catch (e: Exception) { /* keep them queued; retry next poll */ }
    }

    /** Owner: re-post each buffered re-key announcement to its old mailbox about once a day until it expires,
     *  so a member who was offline during the re-key still finds it. */
    private fun resendRekeys(m: Me, g: Group) {
        val now = System.currentTimeMillis()
        val items = synchronized(this) { g.rekeyResend.toList() }
        for (item in items) {
            if (now > item.until) { synchronized(this) { g.rekeyResend.remove(item) }; continue }
            if (now - item.last < REKEY_RESEND_EVERY) continue
            runCatching { relay.gsend(item.addr, unb64(item.blob)); item.last = now }
        }
    }

    private fun rekeyBind(owner: String, oldSecret: String, epoch: Int, user: String) =
        "msgr01-rekey-v1|$owner|$oldSecret|$epoch|$user"

    /** Switch a group to a new secret/epoch: fresh mailbox, fresh sender keys, dropped members. */
    private fun migrateGroup(g: Group, newSecret: String, newEpoch: Int, removed: Set<String>, name: String, ro: Boolean, leaders: Set<String>) {
        val m = me ?: return
        synchronized(this) {
            g.secret = newSecret; g.epoch = newEpoch
            g.name = name.take(80).ifEmpty { g.name }; g.readOnly = ro
            g.members.removeAll(removed)
            g.leaders.clear(); g.leaders.addAll(leaders); if (g.owner.isNotEmpty()) g.leaders.add(g.owner)
            g.cursor = 0; g.distSent = false
            g.pending.clear(); g.pendingCtl.clear()
            g.msgs.add(note(if (removed.isNotEmpty()) "Group security was updated and a member was removed." else "Group security was updated."))
        }
        runCatching { postMyDist(m, g) }   // publish my sender key in the new mailbox
        changed()
    }

    fun sendGroup(g: Group, text: String, reply: JSONObject? = null, fwd: String? = null, lp: JSONObject? = null): Msg =
        queueGroup(g, text, reply, fwd, lp).also { deliverGroup(g, it) }

    fun queueGroup(g: Group, text: String, reply: JSONObject? = null, fwd: String? = null, lp: JSONObject? = null): Msg {
        val msg = Msg(Crypto.randomHex(16), true, text.take(MAX_TEXT), System.currentTimeMillis(), Msg.SENDING)
        msg.reply = Msg.replyRef(reply)
        msg.fwd = fwd
        msg.lp = lp
        synchronized(this) { g.msgs.add(msg) }
        onChange()
        return msg
    }

    // ---------- polls (groups) ----------

    fun sendPoll(g: Group, question: String, options: List<String>): Msg {
        val m = me ?: throw UserError("Not signed in")
        val poll = Msg.pollOf(JSONObject().put("q", question).put("o", JSONArray(options))) ?: throw UserError("A poll needs a question and at least 2 options")
        if (!g.canPost(m.user)) throw UserError("Only admins can post in this group")
        val msg = Msg(Crypto.randomHex(16), true, "", System.currentTimeMillis(), Msg.SENDING).also { it.poll = poll; it.sender = m.user }
        synchronized(this) { g.msgs.add(msg) }
        onChange()
        deliverGroup(g, msg)
        return msg
    }

    /** Votes for option [i] (-1 takes the vote back). */
    fun vote(g: Group, msg: Msg, i: Int) {
        val m = me ?: return
        synchronized(this) { if (i < 0) msg.votes.remove(m.user) else msg.votes[m.user] = i }
        onChange()
        runCatching { gctl(g, m, JSONObject().put("t", "vote").put("id", msg.id).put("i", i)) }
    }

    // ---------- scheduled messages ----------

    /** Turns a queued message into a scheduled one (kept here until [at]). */
    fun schedule(msg: Msg, at: Long) {
        synchronized(this) { msg.state = Msg.SCHEDULED; msg.sendAt = at }
        onChange()
    }

    /**
     * Scheduled messages whose time has come: each moves to the end of its chat with the current time
     * and is returned for sending. [now] = send them all regardless (Send now).
     */
    fun dueScheduled(now: Long = System.currentTimeMillis(), only: Msg? = null): List<Pair<Any, Msg>> {
        val out = ArrayList<Pair<Any, Msg>>()
        fun take(list: MutableList<Msg>, owner: Any) {
            for (old in list.filter { it.state == Msg.SCHEDULED && (if (only != null) it === only else it.sendAt <= now) }) {
                val m = Msg(old.id, true, old.text, System.currentTimeMillis(), Msg.SENDING)
                m.reply = old.reply; m.fwd = old.fwd; m.lp = old.lp; m.file = old.file; m.silent = old.silent; m.expires = old.expires; m.loc = old.loc
                list.remove(old); list.add(m)
                out.add(owner to m)
            }
        }
        synchronized(this) { contacts.forEach { take(it.msgs, it) }; groups.forEach { take(it.msgs, it) } }
        if (out.isNotEmpty()) onChange()
        return out
    }


    /** A leader changes the group's name / read-only / leader list; broadcast as an authenticated set. */
    fun setGroup(g: Group, name: String, readOnly: Boolean, leaders: Set<String>) {
        val m = me ?: return
        if (!g.isLeader(m.user)) throw UserError("Only an admin can change this group")
        synchronized(this) {
            g.name = name.trim().take(80).ifEmpty { g.name }
            g.readOnly = readOnly
            g.leaders.clear(); g.leaders.addAll(leaders); if (g.owner.isNotEmpty()) g.leaders.add(g.owner)
            g.setVer++
        }
        changed()
        runCatching {
            gctl(g, m, JSONObject().put("t", "set").put("ver", g.setVer).put("n", g.name)
                .put("ro", g.readOnly).put("ldr", JSONArray(g.leaders.toList())))
        }
    }

    fun deliverGroup(g: Group, msg: Msg) {
        val m = me ?: return
        if (!g.canPost(m.user)) { msg.state = Msg.FAILED; onChange(); return }   // read-only: only leaders post
        msg.poll?.let { poll ->   // polls go on the authenticated control channel
            msg.state = try { gctl(g, m, JSONObject().put("t", "poll").put("id", msg.id).put("poll", poll)); Msg.SENT } catch (e: Exception) { Msg.FAILED }
            onChange(); return
        }
        var editedMeanwhile = false
        msg.state = try {
            if (!g.distSent) postMyDist(m, g)
            val sentText = msg.text
            val ct = synchronized(m.signal) { GroupCipher(m.signal, m.address).encrypt(g.distId, sentText.toByteArray()) }
            relay.gsend(g.addr, gseal(g, JSONObject().put("t", "gmsg").put("u", m.user).put("id", msg.id).put("c", b64(ct.serialize()))
                .apply { msg.reply?.let { put("r", it) }; msg.fwd?.let { put("fw", it) }; msg.lp?.let { put("lp", it) }; msg.loc?.let { put("loc", it) } }))
            synchronized(this) { msg.state = Msg.SENT; editedMeanwhile = msg.text != sentText; if (editedMeanwhile) msg.edited = true }
            Msg.SENT
        } catch (e: Exception) {
            Msg.FAILED
        }
        onChange()
        if (editedMeanwhile) runCatching { gctl(g, m, JSONObject().put("t", "edit").put("id", msg.id).put("x", msg.text)) }
    }

    /** Posts my sender-key distribution message, signed with my identity so nobody can post as me. */
    private fun postMyDist(m: Me, g: Group) {
        val skdm = synchronized(m.signal) { GroupSessionBuilder(m.signal).create(m.address, g.distId) }.serialize()
        val sig = m.signal.identity.privateKey.calculateSignature(skdm)
        relay.gsend(g.addr, gseal(g, JSONObject().put("t", "skdm").put("u", m.user).put("d", b64(skdm)).put("sig", b64(sig))))
        g.distSent = true
        g.distAt = System.currentTimeMillis()
    }

    private fun gseal(g: Group, inner: JSONObject) = Crypto.gcmSeal(g.key, "msgr01-group", inner.toString().toByteArray())

    /**
     * Sends an authenticated group control (file, reaction, settings). The payload is encrypted
     * with my sender key, so the receiver's successful decrypt under my address proves it's me —
     * unlike the outer "u" field, which any member could forge.
     */
    private fun gctl(g: Group, m: Me, payload: JSONObject) = relay.gsend(g.addr, gctlBytes(g, m, payload))

    /** Builds a sealed gctl without sending it, so a re-key blob can be buffered and re-posted later. */
    private fun gctlBytes(g: Group, m: Me, payload: JSONObject): ByteArray {
        if (!g.distSent) postMyDist(m, g)
        val ct = synchronized(m.signal) { GroupCipher(m.signal, m.address).encrypt(g.distId, payload.toString().toByteArray()) }
        return gseal(g, JSONObject().put("t", "gctl").put("u", m.user).put("c", b64(ct.serialize())))
    }

    /** Queues a group attachment: locked with its own key, same as a 1:1 attachment. */
    fun queueGroupFile(g: Group, name: String, mime: String, data: ByteArray, thumb: ByteArray?): Msg {
        val m = me ?: throw UserError("Not signed in")
        if (data.size > MAX_FILE) throw UserError("Files can be up to ${MAX_FILE / 1024 / 1024} MB")
        val key = Crypto.randomHex(32)
        val local = Crypto.randomHex(16)
        saveBlob(local, Crypto.gcmSeal(key.unhex(), FILE_LABEL, padFile(data)))
        val f = JSONObject().put("k", key).put("n", cleanName(name)).put("m", cleanMime(mime)).put("s", data.size).put("local", local).put("grp", true)
        thumb?.let { f.put("th", b64(it)) }
        val msg = Msg(Crypto.randomHex(16), true, "", System.currentTimeMillis(), Msg.SENDING).also { it.file = f; it.sender = m.user }
        synchronized(this) { g.msgs.add(msg) }
        onChange()
        return msg
    }

    fun deliverGroupFile(g: Group, msg: Msg) {
        val m = me ?: return
        if (!g.canPost(m.user)) { msg.state = Msg.FAILED; onChange(); return }
        val f = msg.file ?: return
        msg.state = try {
            // group blobs fan out with no per-reader delete, so ask the server to drop them after a week
            if (!f.has("id")) f.put("id", relay.upload(loadBlob(f.getString("local")) ?: throw IOException("attachment missing"), GROUP_FILE_TTL))
            // what others receive: no "local" (that's my private blob key), but the server id + file key
            val out = JSONObject().put("id", f.getString("id")).put("k", f.getString("k")).put("n", f.optString("n"))
                .put("m", f.optString("m")).put("s", f.optInt("s")).put("grp", true)
            if (f.has("th")) out.put("th", f.getString("th"))
            gctl(g, m, JSONObject().put("t", "file").put("id", msg.id).put("f", out).apply { msg.fwd?.let { put("fw", it) } })
            Msg.SENT
        } catch (e: Exception) {
            Msg.FAILED
        }
        onChange()
    }

    // test hooks (used only by unit tests to simulate a malicious member)
    fun signalFor() = me!!.signal
    fun postRaw(g: Group, inner: JSONObject) = relay.gsend(g.addr, gseal(g, inner))

    /** Reads new group-mailbox messages for [g]. */
    private fun pollGroup(m: Me, g: Group, wait: Int) {
        // re-post my sender key before it ages out of the short-lived group mailbox, so anyone who joins
        // (or re-syncs) later can still read my messages.
        if (g.distSent && System.currentTimeMillis() - g.distAt > GROUP_FILE_TTL * 500L) runCatching { postMyDist(m, g) }
        if (m.user == g.owner && g.owner.isNotEmpty()) { drainRekeyDue(g); resendRekeys(m, g) }
        val got = relay.gfetch(g.addr, g.cursor, wait)
        if (got.isEmpty()) return
        // decode everything, then handle sender-key announcements before chat messages so a
        // message and its sender's key that arrive in the same batch still decrypt
        data class Decoded(val seq: Long, val u: String, val inner: JSONObject)
        val decoded = got.mapNotNull { gm ->
            val plain = Crypto.gcmOpen(g.key, "msgr01-group", gm.blob) ?: return@mapNotNull null
            val inner = try { JSONObject(String(plain)) } catch (e: Exception) { return@mapNotNull null }
            val u = inner.optString("u")
            if (!USERNAME.matches(u) || u == m.user) null else Decoded(gm.seq, u, inner)
        }
        // sender-key announcements first, so a message/control and its sender's key in the same batch still decrypt
        for (d in decoded.sortedBy { if (it.inner.optString("t") == "skdm") 0 else 1 }) {
            try { onGroupInner(m, g, d.u, d.inner) } catch (e: IOException) { continue }
        }
        g.cursor = maxOf(g.cursor, got.maxOf { it.seq })
        retryPending(m, g)
        onChange()
    }

    private fun onGroupInner(m: Me, g: Group, u: String, inner: JSONObject) {
        when (inner.optString("t")) {
            "skdm" -> {
                val p = relay.lookup(u) ?: return
                if (p.ident.isEmpty()) return
                val skdm = unb64(inner.optString("d"))
                val sig = unb64(inner.optString("sig"))
                val identKey = try { IdentityKey(unb64(p.ident)) } catch (e: Exception) { return }
                if (!identKey.publicKey.verifySignature(skdm, sig)) return // someone posting as @u
                synchronized(m.signal) { GroupSessionBuilder(m.signal).process(SignalProtocolAddress(u, 1), SenderKeyDistributionMessage(skdm)) }
                val isNew = synchronized(this) { g.members.add(u) }
                // a new member just arrived: re-post my sender key so they can read my messages
                // (the short group TTL may have swept my earlier one). u != me guards against self-echo.
                if (isNew && g.distSent && u != m.user) runCatching { postMyDist(m, g) }
            }
            "gmsg" -> {
                val id = inner.optString("id")
                val ct = unb64(inner.optString("c"))
                if (id.isEmpty() || synchronized(this) { g.msgs.any { it.id == id } }) return
                val text = try {
                    synchronized(m.signal) { GroupCipher(m.signal, SignalProtocolAddress(u, 1)).decrypt(ct) }
                } catch (e: org.signal.libsignal.protocol.NoSessionException) {
                    synchronized(g.pending) { if (g.pending.none { it.second == id }) g.pending.add(Triple(u, id, ct)) }
                    return
                } catch (e: Exception) {
                    return
                }
                if (!g.canPost(u)) return   // read-only: ignore posts from non-leaders
                g.typing.remove(u)
                addGroupMsg(g, u, id, String(text), Msg.replyRef(inner.optJSONObject("r")), Msg.fwdOf(inner), LinkPreview.clean(inner.optJSONObject("lp")), Msg.locOf(inner.optJSONObject("loc")))
            }
            "gdel" -> {
                val id = inner.optString("id")
                // only the original sender may erase a group message
                synchronized(this) { g.msgs.removeAll { it.id == id && it.sender == u } }
                onChange()
            }
            // The outer "leave" trusts the forgeable "u", so in owner-anchored groups it must NOT change
            // membership (a member could forge a leave for anyone). Those groups rely on the authenticated
            // gctl "leave" instead. Legacy groups (no owner) keep the old behaviour.
            "leave" -> if (g.owner.isEmpty()) { synchronized(this) { if (g.members.remove(u)) g.msgs.add(note("@$u left the group")) }; onChange() }
            "gctl" -> {
                val ct = unb64(inner.optString("c"))
                val plain = try {
                    synchronized(m.signal) { GroupCipher(m.signal, SignalProtocolAddress(u, 1)).decrypt(ct) }
                } catch (e: org.signal.libsignal.protocol.NoSessionException) {
                    synchronized(g.pendingCtl) { if (g.pendingCtl.none { it.second.contentEquals(ct) }) g.pendingCtl.add(u to ct) }
                    return
                } catch (e: Exception) { return }
                handleCtl(m, g, u, try { JSONObject(String(plain)) } catch (e: Exception) { return })
            }
        }
    }

    /** An authenticated group control (sender proven by the gctl decrypt): file, reaction, settings. */
    private fun handleCtl(m: Me, g: Group, u: String, p: JSONObject) {
        synchronized(this) { g.members.add(u) }
        when (p.optString("t")) {
            "file" -> {
                if (!g.canPost(u)) return   // read-only: ignore posts from non-leaders
                val id = p.optString("id")
                val f = p.optJSONObject("f")?.let { checkFile(it) } ?: return
                if (id.isEmpty() || synchronized(this) { g.msgs.any { it.id == id } }) return
                val msg = Msg(id, false, "", System.currentTimeMillis(), Msg.RECEIVED).also { it.sender = u; it.file = f; it.fwd = Msg.fwdOf(p) }
                synchronized(this) { g.msgs.add(msg); if (openChat != "g:${g.id}") g.unread++ }
                toFetch.add(msg)   // poll() downloads it; fetchFile skips doneWith for group files
            }
            "typing" -> {
                if (System.currentTimeMillis() - p.optLong("ts", 0) > 15_000) return   // old news (re-read from the mailbox)
                g.typing[u] = System.currentTimeMillis() + TYPING_MS
                onChange()
            }
            "pinm" -> {   // admins pin (any member in a legacy group without roles)
                if (g.owner.isNotEmpty() && !g.isLeader(u)) return
                val id = p.optString("id")
                synchronized(this) { g.pinned = if (id.isEmpty() || g.msgs.none { it.id == id }) null else id }
                onChange()
            }
            "poll" -> {
                if (!g.canPost(u)) return
                val id = p.optString("id")
                val poll = Msg.pollOf(p.optJSONObject("poll")) ?: return
                if (id.isEmpty() || synchronized(this) { g.msgs.any { it.id == id } }) return
                val msg = Msg(id, false, "", System.currentTimeMillis(), Msg.RECEIVED).also { it.sender = u; it.poll = poll }
                synchronized(this) { g.msgs.add(msg); if (openChat != "g:${g.id}") g.unread++ }
                onChange()
            }
            "vote" -> {   // the gctl proves who voted, so nobody can vote for someone else
                val id = p.optString("id"); val i = p.optInt("i", -1)
                synchronized(this) {
                    val msg = g.msgs.firstOrNull { it.id == id && it.poll != null } ?: return
                    val n = msg.poll!!.optJSONArray("o")?.length() ?: 0
                    if (i in 0 until n) msg.votes[u] = i else if (i == -1) msg.votes.remove(u)
                }
                onChange()
            }
            "edit" -> {   // only the original sender (proven by the gctl decrypt) may change a message
                val id = p.optString("id"); val x = p.optString("x").take(MAX_TEXT)
                if (x.isBlank()) return
                synchronized(this) { g.msgs.firstOrNull { it.id == id && it.sender == u && !it.mine }?.let { it.text = x; it.edited = true } }
                onChange()
            }
            "react" -> {   // reactions are allowed even in read-only groups
                val id = p.optString("id"); val e = p.optString("e").take(16)
                synchronized(this) { g.msgs.firstOrNull { it.id == id }?.let { if (e.isEmpty()) it.reactions.remove(u) else it.reactions[u] = e } }
                onChange()
            }
            "set" -> {
                val ver = p.optInt("ver", 0)
                synchronized(this) {
                    if (!g.isLeader(u) || ver <= g.setVer) return   // only a current leader, and only newer settings
                    g.setVer = ver
                    g.name = p.optString("n", g.name).take(80).ifEmpty { g.name }
                    g.readOnly = p.optBoolean("ro", g.readOnly)
                    p.optJSONArray("ldr")?.let { arr ->
                        g.leaders.clear(); for (i in 0 until arr.length()) g.leaders.add(arr.getString(i))
                        if (g.owner.isNotEmpty()) g.leaders.add(g.owner)
                    }
                }
                onChange()
            }
            "leave" -> {   // authenticated departure: drop them, and if I'm the owner, re-key so they lose access
                if (u == g.owner) return
                // a grey line in the chat so everyone sees who left (once, even if the leave arrives twice)
                synchronized(this) { if (g.members.remove(u)) g.msgs.add(note("@$u left the group")) }
                // queue the re-key durably; drainRekeyDue (in pollGroup) retries if the network call fails now
                if (me?.user == g.owner && g.owner.isNotEmpty()) { synchronized(g.rekeyDue) { g.rekeyDue.add(u) }; drainRekeyDue(g) }
                onChange()
            }
            "rekey" -> {   // only the owner may re-key, and only to a newer epoch
                val ep = p.optInt("ep", 0)
                if (u != g.owner || g.owner.isEmpty() || ep <= g.epoch) return
                val keys = p.optJSONObject("keys") ?: return
                val mine = keys.optString(m.user)
                if (mine.isEmpty()) {   // no piece for me => I was removed
                    synchronized(this) { g.msgs.add(note("You were removed from this group.")) }
                    synchronized(this) { groups.remove(g) }
                    onChange(); return
                }
                val opened = Crypto.open(m.enc, rekeyBind(g.owner, g.secret, ep, m.user), unb64(mine)) ?: return
                val newSecret = opened.inner?.let { String(it) } ?: return
                if (newSecret.length != 64 || !newSecret.all { it in "0123456789abcdef" }) return
                val leaders = mutableSetOf<String>().apply { p.optJSONArray("ldr")?.let { for (i in 0 until it.length()) add(it.getString(i)) } }
                val kept = mutableSetOf(g.owner, m.user).apply { keys.keys().forEach { add(it) } }
                val removed = synchronized(this) { g.members.filter { it !in kept } }.toSet()
                migrateGroup(g, newSecret, ep, removed, p.optString("n", g.name), p.optBoolean("ro", g.readOnly), leaders)
            }
        }
    }

    private fun retryPending(m: Me, g: Group) {
        val waiting = synchronized(g.pending) { g.pending.toList() }
        for ((u, id, ct) in waiting) {
            val text = try { synchronized(m.signal) { GroupCipher(m.signal, SignalProtocolAddress(u, 1)).decrypt(ct) } } catch (e: Exception) { continue }
            synchronized(g.pending) { g.pending.removeAll { it.second == id } }
            if (synchronized(this) { g.msgs.none { it.id == id } }) addGroupMsg(g, u, id, String(text))
        }
        val waitingCtl = synchronized(g.pendingCtl) { g.pendingCtl.toList() }
        for ((u, ct) in waitingCtl) {
            val plain = try { synchronized(m.signal) { GroupCipher(m.signal, SignalProtocolAddress(u, 1)).decrypt(ct) } } catch (e: Exception) { continue }
            synchronized(g.pendingCtl) { g.pendingCtl.removeAll { it.second.contentEquals(ct) } }
            handleCtl(m, g, u, try { JSONObject(String(plain)) } catch (e: Exception) { continue })
        }
    }

    private fun addGroupMsg(g: Group, u: String, id: String, text: String, reply: JSONObject? = null, fwd: String? = null, lp: JSONObject? = null, loc: JSONObject? = null) {
        synchronized(this) {
            g.members.add(u)
            val msg = Msg(id, false, text.take(MAX_TEXT), System.currentTimeMillis(), Msg.RECEIVED)
            msg.sender = u
            msg.reply = reply
            msg.fwd = fwd
            msg.lp = lp
            msg.loc = loc
            g.msgs.add(msg)
            if (openChat != "g:${g.id}") g.unread++
        }
        val mine = me?.user
        if (mine != null && openChat != "g:${g.id}" && Format.MENTION.findAll(text).any { it.groupValues[1] == mine }) { g.mentioned = true; onMention(g) }
    }

    /** Marks a chat read and, if read receipts are on, tells the other side. */
    fun markRead(c: Contact) {
        val had = c.unread != 0
        c.unread = 0
        val lastTheirs = synchronized(this) { c.msgs.lastOrNull { !it.mine && it.state == Msg.RECEIVED }?.ts ?: 0 }
        if (had) onChange()
        val m = me ?: return
        if (readReceipts && c.canChat && lastTheirs > 0 && c.user !in blocked) {
            runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "read").put("ts", lastTheirs)) }
        }
    }

    // ---------- typing / online / last seen ----------
    // Sent straight to friends over the encrypted channel like read receipts, so the server never learns
    // when anyone is online. Each side can switch theirs off in Settings.

    /** Tells [c] I'm typing (the caller limits how often). Network: off the main thread. */
    fun sendTyping(c: Contact) {
        val m = me ?: return
        if (!showTyping || c.status != Contact.FRIENDS || c.user in blocked) return
        runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "typing").put("ts", System.currentTimeMillis())) }
    }

    /** Tells the group I'm typing (the caller limits how often). Network. */
    fun sendGroupTyping(g: Group) {
        val m = me ?: return
        if (!showTyping || !g.canPost(m.user)) return
        runCatching { gctl(g, m, JSONObject().put("t", "typing").put("ts", System.currentTimeMillis())) }
    }

    /** Tells every friend I'm [online] now, or that I just left. "hide" clears what they show. Network. */
    fun sendPresence(online: Boolean, hide: Boolean = false) {
        val m = me ?: return
        if (!showSeen && !hide) return
        val o = JSONObject().put("t", "seen").put("ts", System.currentTimeMillis()).put("on", online).put("hide", hide)
        val friends = synchronized(this) { contacts.filter { it.status == Contact.FRIENDS && it.user !in blocked } }
        for (c in friends) runCatching { sealSignal(m, c, c.theirAddr!!, o) }
    }

    // ---------- disappearing messages ----------

    // ---------- voice calls ----------
    // Set up over the encrypted chat (offer -> ring -> answer by joining the call room -> end). The audio
    // itself runs through the server's call room on the same route as messages (Tor today), encrypted
    // end to end with the call key that only the two phones have. Each phone writes its own summary.

    class Call(val id: String, val user: String, val outgoing: Boolean, val key: String, val room: String) {
        @Volatile var phase = DIALING
        @Volatile var connectedAt = 0L
        @Volatile var muted = false
        /** Why the call couldn't go through, shown on the call screen. */
        @Volatile var problem: String? = null
        val startedAt = System.currentTimeMillis()
        companion object { const val DIALING = 0; const val RINGING = 1; const val INCOMING = 2; const val CONNECTING = 3; const val ACTIVE = 4; const val ENDED = 5 }
    }

    /** How this phone reaches the server; calls follow the same route. */
    var netMode = NET_TOR
    @Volatile var call: Call? = null
        private set
    /** Every change to the current call (any thread); the Android side shows the call screen / rings. */
    var onCall: (Call) -> Unit = {}
    private val seenCalls = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Why the call button is greyed out for [c], or null if you can call them. */
    fun callBlocked(c: Contact): String? = when {
        c.status != Contact.FRIENDS -> "Only friends can call"
        c.net != netMode -> "Your network differs — switch to ${if (c.net == NET_DIRECT) "Direct" else "Tor"} to call"
        else -> null
    }

    private fun callCtl(c: Contact, o: JSONObject) { val m = me ?: return; sealSignal(m, c, c.theirAddr!!, o) }

    /** Starts a call to [c]. Network: call off the main thread. */
    fun startCall(c: Contact): Call = prepareCall(c).also { offerCall(c, it) }

    /** Makes the outgoing call (no network), so the call screen can show "Calling…" straight away. */
    fun prepareCall(c: Contact): Call {
        callBlocked(c)?.let { throw UserError(it) }
        if (call != null) throw UserError("You're already in a call")
        val k = Call(Crypto.randomHex(16), c.user, true, Crypto.randomHex(32), Crypto.randomHex(32))
        seenCalls.add(k.id)
        call = k; onCall(k)
        return k
    }

    /** Sends the call invite for [k] to [c]. Network: call off the main thread. */
    fun offerCall(c: Contact, k: Call, why: (Exception) -> String? = { null }) {
        try { sendOffer(c, k) } catch (e: Exception) { k.problem = why(e); endCall(k, "failed"); throw e }
    }

    /** Just sends the invite (throws if it can't); the receiver ignores repeats of the same call id. */
    fun sendOffer(c: Contact, k: Call) {
        callCtl(c, JSONObject().put("t", "call").put("id", k.id).put("k", k.key).put("r", k.room)
            .put("ts", System.currentTimeMillis()).put("n", netMode))
    }

    fun answerCall() { val k = call ?: return; if (k.phase == Call.INCOMING) { k.phase = Call.CONNECTING; onCall(k) } }

    /** The audio connected (both phones are in the call room). */
    fun callConnected() { val k = call ?: return; if (k.phase < Call.ACTIVE) { k.phase = Call.ACTIVE; k.connectedAt = System.currentTimeMillis(); onCall(k) } }

    /** Hang up / decline / cancel from this phone. Network: off the main thread. */
    fun hangUp(status: String? = null) {
        val k = call ?: return
        val c = contact(k.user)
        val s = status ?: when {
            k.phase == Call.ACTIVE -> "ok"
            k.phase == Call.INCOMING -> "declined"
            k.outgoing -> "cancelled"
            else -> "failed"
        }
        endCall(k, s)
        if (c != null) runCatching { callCtl(c, JSONObject().put("t", if (s == "declined") "dec" else "end").put("id", k.id)) }
    }

    /** Ends [k] here and writes this phone's summary into the chat. */
    fun endCall(k: Call, status: String) {
        if (k.phase == Call.ENDED) return
        k.phase = Call.ENDED
        if (call === k) call = null
        val c = contact(k.user)
        if (c != null) {
            val dur = if (k.connectedAt > 0) ((System.currentTimeMillis() - k.connectedAt) / 1000).toInt() else 0
            val missed = status == "missed"
            val msg = Msg(Crypto.randomHex(16), k.outgoing, "", System.currentTimeMillis(), if (k.outgoing) Msg.SENT else Msg.RECEIVED)
            msg.call = JSONObject().put("d", if (k.outgoing) "out" else "in").put("s", status).put("dur", dur).put("id", k.id)
            synchronized(this) { c.msgs.add(msg); if (missed && openChat != c.user) c.unread++ }
            onChange()
            if (missed && openChat != c.user) onNew(c)
        }
        onCall(k)
    }

    private fun onCallCtl(m: Me, c: Contact, o: JSONObject) {
        val id = o.optString("id")
        if (id.length != 32) return
        val k = call
        when (o.optString("t")) {
            "call" -> {
                if (!seenCalls.add(id)) return                               // redelivered: ring only once
                val key = o.optString("k"); val room = o.optString("r")
                if (key.length != 64 || room.length != 64 || !key.all { it in "0123456789abcdef" } || !room.all { it in "0123456789abcdef" }) return
                o.optString("n").takeIf { it == NET_TOR || it == NET_DIRECT }?.let { c.net = it }
                if (c.status != Contact.FRIENDS) return                        // only friends can call
                val incoming = Call(id, c.user, false, key, room)
                // picked up late (phone was offline): it's a missed call, don't ring now
                if (System.currentTimeMillis() - o.optLong("ts", 0) > 60_000) { endCall(incoming, "missed"); return }
                if (k != null && k.phase != Call.ENDED) {
                    // both called each other at once: the call from the alphabetically-first username wins
                    if (k.outgoing && k.user == c.user && k.phase <= Call.RINGING && m.user > c.user) {
                        k.phase = Call.ENDED; call = null; onCall(k)             // drop mine quietly, take theirs
                    } else {
                        if (!(k.outgoing && k.user == c.user)) {
                            endCall(incoming, "missed")
                            Thread { runCatching { callCtl(c, JSONObject().put("t", "busy").put("id", id)) } }.start()
                        }
                        return
                    }
                }
                incoming.phase = Call.INCOMING
                call = incoming; onCall(incoming)
                Thread { runCatching { callCtl(c, JSONObject().put("t", "ring").put("id", id)) } }.start()
            }
            "ring" -> if (k != null && k.id == id && k.phase == Call.DIALING) { k.phase = Call.RINGING; onCall(k) }
            "dec" -> if (k != null && k.id == id) endCall(k, "declined")
            "busy" -> if (k != null && k.id == id) endCall(k, "busy")
            "end" -> if (k != null && k.id == id) endCall(k, when {
                k.phase == Call.INCOMING -> "missed"
                k.phase == Call.ACTIVE -> "ok"
                k.outgoing -> "failed"
                else -> "missed"
            })
        }
    }

    /** Sets (or clears, with 0) the disappearing-messages timer for this chat and tells the other side. */
    fun setTimer(c: Contact, seconds: Int) {
        val m = me ?: return
        synchronized(this) {
            c.ttl = seconds
            c.msgs.add(note(if (seconds == 0) "Disappearing messages turned off."
            else "Disappearing messages: on, ${timerLabel(seconds)}. New messages delete on both phones."))
        }
        changed()
        if (c.canChat) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "ttl").put("s", seconds)) }
    }

    /** Removes messages whose time is up, on this phone. Returns true if any were removed. */
    fun purgeExpired(): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(this) {
            for (c in contacts) if (c.msgs.removeAll { it.expires in 1..now }) changed = true
        }
        if (changed) onChange()
        return changed
    }

    private fun timerLabel(s: Int) = when {
        s >= 86400 -> "${s / 86400} day" + if (s / 86400 > 1) "s" else ""
        s >= 3600 -> "${s / 3600} hour" + if (s / 3600 > 1) "s" else ""
        s >= 60 -> "${s / 60} minute" + if (s / 60 > 1) "s" else ""
        else -> "$s seconds"
    }

    // ---------- profile photo ----------

    /** Sets (or with null, removes) my photo and sends it, sealed, to every friend. */
    fun setAvatar(jpeg: ByteArray?) {
        require(jpeg == null || jpeg.size <= MAX_AVATAR) { "photo too big" }
        me?.avatar = jpeg
        changed()
        for (c in synchronized(this) { contacts.filter { it.status == Contact.FRIENDS } }) runCatching { sendAvatar(c, ask = false) }
    }

    private fun sendAvatar(c: Contact, ask: Boolean) {
        val m = me ?: return
        sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "avatar").put("d", m.avatar?.let { b64(padAvatar(it)) } ?: "").put("ask", ask))
    }

    fun remove(c: Contact) {
        synchronized(this) { contacts.remove(c) }
        changed()
    }

    // ---------- messages ----------

    fun queue(c: Contact, text: String, reply: JSONObject? = null, lp: JSONObject? = null): Msg {
        val msg = Msg(Crypto.randomHex(16), true, text.take(MAX_TEXT), System.currentTimeMillis(), Msg.SENDING)
        msg.reply = Msg.replyRef(reply)
        msg.lp = lp
        if (c.ttl > 0) msg.expires = System.currentTimeMillis() + c.ttl * 1000L
        synchronized(this) { c.msgs.add(msg) }
        onChange()
        return msg
    }

    /**
     * Queues an attachment. It's locked here with its own random key; the server only
     * ever stores the locked copy, and the key travels inside the sealed message.
     */
    fun queueFile(c: Contact, name: String, mime: String, data: ByteArray, thumb: ByteArray?): Msg {
        if (data.size > MAX_FILE) throw UserError("Files can be up to ${MAX_FILE / 1024 / 1024} MB")
        val key = Crypto.randomHex(32)
        val local = Crypto.randomHex(16)
        saveBlob(local, Crypto.gcmSeal(key.unhex(), FILE_LABEL, padFile(data)))
        val f = JSONObject().put("k", key).put("n", cleanName(name)).put("m", cleanMime(mime)).put("s", data.size).put("local", local)
        thumb?.let { f.put("th", b64(it)) }
        val msg = Msg(Crypto.randomHex(16), true, "", System.currentTimeMillis(), Msg.SENDING).also { it.file = f }
        if (c.ttl > 0) msg.expires = System.currentTimeMillis() + c.ttl * 1000L
        synchronized(this) { c.msgs.add(msg) }
        onChange()
        return msg
    }

    /** The attachment's bytes, unlocked, or null if it isn't here yet. */
    fun openFile(msg: Msg): ByteArray? {
        val f = msg.file ?: return null
        val blob = loadBlob(f.optString("local").ifEmpty { return null }) ?: return null
        return Crypto.gcmOpen(f.getString("k").unhex(), FILE_LABEL, blob)?.let { unpadFile(it) }
    }

    /** Sends one message (or attachment). Before they accept, it goes as a message request. */
    fun deliver(c: Contact, msg: Msg) {
        val m = me ?: return
        if (c.status == Contact.ASKING) return
        if (c.status == Contact.SAVED) { msg.state = Msg.SENT; onChange(); return }
        msg.state = Msg.SENDING
        var editedMeanwhile = false
        msg.state = try {
            val sentText = msg.text
            val body = JSONObject().put("id", msg.id).put("x", sentText)
            msg.reply?.let { body.put("r", it) }
            msg.fwd?.let { body.put("fw", it) }
            msg.lp?.let { body.put("lp", it) }
            if (msg.silent) body.put("sil", true)
            msg.loc?.let { body.put("loc", it) }
            msg.file?.let { f ->
                if (!f.has("id")) {
                    val blob = loadBlob(f.getString("local")) ?: throw IOException("attachment missing")
                    f.put("id", relay.upload(blob))
                }
                body.put("f", JSONObject().put("id", f.getString("id")).put("k", f.getString("k")).put("n", f.getString("n"))
                    .put("m", f.getString("m")).put("s", f.getInt("s")).put("th", f.optString("th")))
            }
            if (c.canChat) {
                sealSignal(m, c, c.theirAddr!!, body.put("t", "msg"))
            } else {
                // not accepted yet: goes to their request inbox as a message request
                val inbox = c.theirInbox ?: relay.lookup(c.user)?.inbox?.also { c.theirInbox = it } ?: throw IOException("no inbox")
                sealSignal(m, c, inbox, body.put("t", "rmsg").put("u", m.user))
            }
            // edited while it was on its way: send the new text right behind it
            synchronized(this) { msg.state = Msg.SENT; editedMeanwhile = msg.text != sentText; if (editedMeanwhile) msg.edited = true }
            Msg.SENT
        } catch (e: Exception) {
            Msg.FAILED
        }
        onChange()
        if (editedMeanwhile && c.canChat) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "edit").put("id", msg.id).put("x", msg.text)) }
    }

    // ---------- pinned message, block, saved messages ----------

    /** Pins [msg] at the top of the chat for both of us (null unpins). Network: off the main thread. */
    fun pinMessage(c: Contact, msg: Msg?) {
        synchronized(this) { c.pinned = msg?.id }
        onChange()
        val m = me ?: return
        if (c.canChat) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "pinm").put("id", msg?.id ?: "")) }
    }

    fun canPin(g: Group) = me?.user?.let { g.owner.isEmpty() || g.isLeader(it) } == true

    fun pinGroupMessage(g: Group, msg: Msg?) {
        val m = me ?: return
        if (!canPin(g)) throw UserError("Only an admin can pin messages")
        synchronized(this) { g.pinned = msg?.id }
        onChange()
        runCatching { gctl(g, m, JSONObject().put("t", "pinm").put("id", msg?.id ?: "")) }
    }

    fun block(user: String, on: Boolean) {
        if (on) blocked.add(user) else blocked.remove(user)
        contact(user)?.let { it.theirTypingUntil = 0; it.theirOnlineUntil = 0 }
        changed()
    }

    /** "Saved Messages": a chat with myself, kept only on this phone. Made the first time it's opened. */
    fun savedChat(): Contact {
        val m = me ?: throw UserError("Not signed in")
        synchronized(this) {
            contacts.firstOrNull { it.status == Contact.SAVED }?.let { return it }
            val c = Contact(m.user, "Saved Messages", Contact.SAVED, Crypto.randomHex(32), null, "", "")
            c.msgs.add(note("Save messages, photos and files here. They stay on this phone only."))
            contacts.add(c)
            changed()
            return c
        }
    }

    /** Changes the text of my sent message here and on their phone. Network: off the main thread. */
    fun editMessage(c: Contact, msg: Msg, text: String) {
        val t = text.trim().take(MAX_TEXT)
        if (!msg.mine || t.isEmpty() || t == msg.text) return
        // not out yet: the new text simply goes instead (deliver sends an edit if it was mid-way)
        val sent = synchronized(this) { msg.text = t; if (msg.state == Msg.SENT) msg.edited = true; msg.state == Msg.SENT }
        onChange()
        val m = me ?: return
        if (sent && c.canChat) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "edit").put("id", msg.id).put("x", t)) }
    }

    fun editGroupMessage(g: Group, msg: Msg, text: String) {
        val t = text.trim().take(MAX_TEXT)
        if (!msg.mine || t.isEmpty() || t == msg.text) return
        val sent = synchronized(this) { msg.text = t; if (msg.state == Msg.SENT) msg.edited = true; msg.state == Msg.SENT }
        onChange()
        val m = me ?: return
        if (sent) runCatching { gctl(g, m, JSONObject().put("t", "edit").put("id", msg.id).put("x", t)) }
    }

    /** Removes a message here; [forEveryone] also tells the other side to erase it. */
    fun deleteMessage(c: Contact, msg: Msg, forEveryone: Boolean) {
        synchronized(this) { c.msgs.remove(msg) }
        if (forEveryone && msg.mine && c.canChat) {
            val m = me
            if (m != null) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "del").put("id", msg.id)) }
        }
        changed()
    }

    fun deleteGroupMessage(g: Group, msg: Msg, forEveryone: Boolean) {
        synchronized(this) { g.msgs.remove(msg) }
        if (forEveryone && msg.mine) {
            val m = me
            if (m != null) runCatching { relay.gsend(g.addr, gseal(g, JSONObject().put("t", "gdel").put("u", m.user).put("id", msg.id))) }
        }
        changed()
    }

    /** Add/replace/remove my emoji reaction on a 1:1 message. An empty emoji removes it. */
    fun react(c: Contact, msg: Msg, emoji: String) {
        val m = me ?: return
        synchronized(this) { if (emoji.isEmpty()) msg.reactions.remove(m.user) else msg.reactions[m.user] = emoji }
        changed()
        if (c.canChat) runCatching { sealSignal(m, c, c.theirAddr!!, JSONObject().put("t", "react").put("id", msg.id).put("e", emoji)) }
    }

    /** Add/replace/remove my emoji reaction on a group message (authenticated via gctl). */
    fun reactGroup(g: Group, msg: Msg, emoji: String) {
        val m = me ?: return
        synchronized(this) { if (emoji.isEmpty()) msg.reactions.remove(m.user) else msg.reactions[m.user] = emoji }
        changed()
        runCatching { gctl(g, m, JSONObject().put("t", "react").put("id", msg.id).put("e", emoji)) }
    }

    private fun flush(c: Contact) {
        for (msg in synchronized(this) { c.msgs.filter { it.mine && it.state == Msg.SENDING } }) deliver(c, msg)
    }

    private fun seal(m: Me, address: String, theirEnc: String, inner: JSONObject) {
        val key = Crypto.parsePublic(unb64(theirEnc), Crypto.ENC_TEMPLATE) ?: throw IOException("bad key")
        relay.send(address, Crypto.seal(m.sig, key, address, inner.toString().toByteArray()))
    }

    // ---------- Signal ----------
    // Every message is encrypted with Signal's Double Ratchet (libsignal), then sealed again for the
    // mailbox, so the server sees neither the content nor who it's from.

    /** Encrypts [inner] in the Signal session with [c] (starting one if needed) and sends it to [address]. */
    private fun sealSignal(m: Me, c: Contact, address: String, inner: JSONObject) {
        if (c.status == Contact.SAVED) return   // my own notes: nothing leaves the phone
        val remote = SignalProtocolAddress(c.user, 1)
        val ct = synchronized(m.signal) {
            if (!m.signal.containsSession(remote)) startSession(m, c)
            SessionCipher(m.signal, m.address, remote).encrypt(inner.toString().toByteArray())
        }
        seal(m, address, c.theirEnc, JSONObject().put("t", "s").put("u", m.user).put("ty", ct.type).put("c", b64(ct.serialize())))
    }

    private fun startSession(m: Me, c: Contact) {
        val b = relay.bundle(c.user) ?: throw UserError("@${c.user} needs to update MSNGR first")
        val ident = b.getString("ident")
        // the identity must match what the directory listed when we added them
        if (c.theirIdent != null && c.theirIdent != ident) throw IOException("@${c.user}'s key changed")
        c.theirIdent = ident
        val spk = b.getJSONObject("spk")
        val kpk = b.getJSONObject("kpk")
        val one = b.optJSONObject("otpk")
        val bundle = PreKeyBundle(
            b.getInt("reg"), 1,
            one?.getInt("id") ?: PreKeyBundle.NULL_PRE_KEY_ID, one?.let { ECPublicKey(unb64(it.getString("pub"))) },
            spk.getInt("id"), ECPublicKey(unb64(spk.getString("pub"))), unb64(spk.getString("sig")),
            IdentityKey(unb64(ident)),
            kpk.getInt("id"), KEMPublicKey(unb64(kpk.getString("pub"))), unb64(kpk.getString("sig")),
        )
        SessionBuilder(m.signal, SignalProtocolAddress(c.user, 1), m.address).process(bundle)
    }

    /**
     * Opens a Signal envelope from @[user]. [ident] is their identity from the directory; a first
     * message signed by any other identity is refused. Returns null for anything that doesn't open
     * (duplicates, replays, broken messages) so it gets dropped instead of jamming the mailbox.
     */
    private fun openSignal(m: Me, user: String, ident: String?, env: JSONObject): JSONObject? {
        if (env.optString("t") != "s" || env.optString("u") != user) return null
        val bytes = unb64(env.optString("c"))
        val remote = SignalProtocolAddress(user, 1)
        val plain = try {
            synchronized(m.signal) {
                val cipher = SessionCipher(m.signal, m.address, remote)
                when (env.optInt("ty")) {
                    CiphertextMessage.PREKEY_TYPE -> {
                        val pm = PreKeySignalMessage(bytes)
                        if (ident.isNullOrEmpty() || !pm.identityKey.serialize().contentEquals(unb64(ident))) return null
                        cipher.decrypt(pm)
                    }
                    CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
                    else -> return null
                }
            }
        } catch (e: Exception) {
            return null
        }
        return try { JSONObject(String(plain)) } catch (e: Exception) { null }
    }

    /** One round of picking up mail. Waits up to [wait] seconds for something to arrive. */
    fun poll(wait: Int) {
        val m = me ?: return
        val byToken = synchronized(this) { contacts.filter { it.status != Contact.SAVED }.associateBy { it.myToken } }
        // The friend-request inbox is listed under my @username, so it's checked in its own request:
        // the server never sees it fetched together with my chat mailboxes.
        val got = fetchFrom(listOf(m.inboxToken), inboxAcks, if (byToken.isEmpty()) wait else 0) +
            if (byToken.isEmpty()) emptyList() else fetchFrom(byToken.keys.toList(), acks, wait)
        for (inc in got) {
            if (me !== m) return
            try {
                if (inc.token == m.inboxToken) onRequest(m, inc.blob) else byToken[inc.token]?.let { onChat(m, it, inc.blob) }
            } catch (e: IOException) {
                continue // e.g. lookup failed; message stays unacked and comes back next round
            } catch (e: Exception) {
                // A message we can't handle must never jam the mailbox: before, it threw out of poll(),
                // the app showed "Connecting…" forever and every retry hit the same message. Skip it.
                unreadable++
                drop("skipped:${e.javaClass.simpleName}")
            }
            synchronized(this) { (if (inc.token == m.inboxToken) inboxAcks else acks).add(inc.id) }
        }
        if (got.isNotEmpty()) onChange()
        for (g in synchronized(this) { groups.toList() }) runCatching { pollGroup(m, g, 0) }
        purgeExpired()
        for (msg in synchronized(this) { toFetch.toList() }) {
            val ok = runCatching { fetchFile(msg) }.getOrDefault(false)
            synchronized(this) { if (ok || msg.file?.has("local") == true) toFetch.remove(msg) }
        }
    }

    private val inboxAcks = mutableSetOf<String>()

    private fun fetchFrom(tokens: List<String>, ackSet: MutableSet<String>, wait: Int): List<Relay.Incoming> {
        val sent = synchronized(this) { ackSet.toList() }
        val got = relay.fetch(tokens, sent, wait)
        synchronized(this) { ackSet.removeAll(sent.toSet()) }
        return got
    }

    private fun onRequest(m: Me, blob: ByteArray) {
        val o = Crypto.open(m.enc, m.inboxAddr, blob) ?: run { unreadable++; drop("req:hpke"); return }
        val env = parse(o) ?: run { drop("req:parse"); return }
        val user = env.optString("u")
        if (!USERNAME.matches(user) || user == m.user) { drop("req:user?$user"); return }
        // The sender's keys come from the directory, so nobody can pretend to be @user.
        val p = relay.lookup(user) ?: run { drop("req:lookup-null"); return }
        val sigKey = Crypto.parsePublic(unb64(p.sig), Crypto.SIG_TEMPLATE) ?: run { drop("req:sigkey"); return }
        if (!Crypto.verify(sigKey, o)) { drop("req:verify"); return }
        val inner = openSignal(m, user, p.ident, env) ?: run { unreadable++; drop("req:signal"); return }
        if (user in blocked) { drop("req:blocked"); return }
        val kind = inner.optString("t")
        if (kind != "request" && kind != "rmsg") { drop("req:kind?$kind"); return }
        val addr = inner.optString("a")
        if (inner.optString("u") != user || (kind == "request" && !isAddr(addr))) { drop("req:addr"); return }
        drop("req:OK->$kind")
        if (kind == "rmsg") {
            val c = contact(user) ?: return
            if (c.theirSig != p.sig || c.status == Contact.ASKED) return
            val id = inner.optString("id")
            synchronized(this) {
                if (id.isEmpty() || c.msgs.any { it.id == id }) return
                c.msgs.add(incoming(id, inner, c.ttl))
                if (openChat != c.user) c.unread++
            }
            if (openChat != c.user) onNew(c)
            return
        }
        val name = inner.optString("n").take(60).ifBlank { "@$user" }
        val existing = contact(user)
        when {
            existing == null -> {
                val c = Contact(user, name, Contact.ASKING, Crypto.randomHex(), addr, p.enc, p.sig, theirIdent = p.ident)
                synchronized(this) { contacts.add(c) }
                changed()
                if (openChat != user) onNew(c)
            }
            existing.theirSig != p.sig -> return
            existing.status == Contact.ASKED -> { // we both asked: that's a yes
                existing.theirAddr = addr
                existing.name = name
                accept(existing)
            }
            existing.status == Contact.ASKING -> { existing.theirAddr = addr; changed() }
            else -> { existing.theirAddr = addr; accept(existing, friend = existing.status == Contact.FRIENDS) } // they re-added us
        }
    }

    private fun onChat(m: Me, c: Contact, blob: ByteArray) {
        val o = Crypto.open(m.enc, c.myAddr, blob) ?: run { unreadable++; drop("chat:hpke"); return }
        val sigKey = Crypto.parsePublic(unb64(c.theirSig), Crypto.SIG_TEMPLATE) ?: run { drop("chat:sigkey"); return }
        if (!Crypto.verify(sigKey, o)) { drop("chat:verify"); return }
        val env = parse(o) ?: run { drop("chat:parse"); return }
        if (c.theirIdent == null) c.theirIdent = relay.lookup(c.user)?.ident?.ifEmpty { null }
        val inner = openSignal(m, c.user, c.theirIdent, env) ?: run { unreadable++; drop("chat:signal"); return }
        if (c.user in blocked) { drop("chat:blocked"); return }   // decrypted (keeps the ratchet in step), then ignored
        drop("chat:OK->${inner.optString("t")}")
        when (inner.optString("t")) {
            "accept" -> {
                val addr = inner.optString("a")
                if (!isAddr(addr) || c.status == Contact.ASKING) return
                val friend = inner.optBoolean("f", true)
                val before = c.status
                synchronized(this) {
                    c.theirAddr = addr
                    c.name = inner.optString("n").take(60).ifBlank { c.name }
                    c.status = if (friend) Contact.FRIENDS else Contact.CHATTING
                    c.waitingForAdd = !friend
                    if (before != c.status) c.msgs.add(note(
                        if (friend) "@${c.user} added you. Check the safety code to be sure it's really them."
                        else "@${c.user} accepted your messages but hasn't added you as a friend yet."))
                }
                changed()
                flush(c)
                if (friend) runCatching { sendAvatar(c, ask = false) }
            }
            "avatar" -> {
                if (c.status != Contact.FRIENDS) return
                val d = inner.optString("d")
                val bytes = if (d.isEmpty()) null else unpadAvatar(unb64(d))
                // only small JPEGs (FF D8 at the start)
                if (d.isNotEmpty() && bytes == null) return
                if (bytes != null && (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte())) return
                synchronized(this) { c.avatar = bytes }
                onChange()
                if (inner.optBoolean("ask")) runCatching { sendAvatar(c, ask = false) }
            }
            "ginvite" -> {
                val secret = inner.optString("secret")
                if (secret.length == 64 && secret.all { it in "0123456789abcdef" }) {
                    val g = joinGroup(secret, inner.optString("n").take(80).ifBlank { "Group" }, inner.optBoolean("public"), inner.optString("o"))
                    synchronized(this) { g.members.add(c.user) }
                }
            }
            "pinm" -> {
                val id = inner.optString("id")
                synchronized(this) { c.pinned = if (id.isEmpty() || c.msgs.none { it.id == id }) null else id }
                onChange()
            }
            "edit" -> {
                val id = inner.optString("id"); val x = inner.optString("x").take(MAX_TEXT)
                if (x.isBlank()) return
                synchronized(this) { c.msgs.firstOrNull { it.id == id && !it.mine }?.let { it.text = x; it.edited = true } } // only their own
                onChange()
            }
            "del" -> {
                val id = inner.optString("id")
                synchronized(this) { c.msgs.removeAll { it.id == id && !it.mine } } // only lets them erase their own
                onChange()
            }
            "call", "ring", "dec", "busy", "end" -> onCallCtl(m, c, inner)
            "net" -> { val n = inner.optString("n"); if (n == NET_TOR || n == NET_DIRECT) { c.net = n; onChange() } }
            "typing" -> {
                if (c.status != Contact.FRIENDS) return
                if (System.currentTimeMillis() - inner.optLong("ts", System.currentTimeMillis()) > 15_000) return   // old news
                c.theirTypingUntil = System.currentTimeMillis() + TYPING_MS
                onChange()
            }
            "seen" -> {
                if (c.status != Contact.FRIENDS) return
                val now = System.currentTimeMillis()
                if (inner.optBoolean("hide")) { c.theirSeen = 0; c.theirOnlineUntil = 0; onChange(); return }
                // their clock, but never in the future; an old "online" (sat in the mailbox) only means "last seen then"
                val ts = inner.optLong("ts", 0).coerceAtMost(now)
                if (ts <= c.theirSeen) return
                c.theirSeen = ts
                c.theirOnlineUntil = if (inner.optBoolean("on")) ts + ONLINE_MS else 0
                if (!inner.optBoolean("on")) c.theirTypingUntil = 0
                onChange()
            }
            "read" -> {
                val ts = inner.optLong("ts", 0)
                if (ts > c.theirReadUpto) { c.theirReadUpto = ts; onChange() }
            }
            "react" -> {
                val id = inner.optString("id")
                val e = inner.optString("e").take(16)
                synchronized(this) {
                    c.msgs.firstOrNull { it.id == id }?.let { if (e.isEmpty()) it.reactions.remove(c.user) else it.reactions[c.user] = e }
                }
                onChange()
            }
            "ttl" -> {
                val sec = inner.optInt("s", -1)
                if (sec < 0 || sec > 30 * 86400 || !c.canChat) return
                synchronized(this) {
                    c.ttl = sec
                    c.msgs.add(note(if (sec == 0) "@${c.user} turned off disappearing messages."
                    else "@${c.user} set disappearing messages: ${timerLabel(sec)}."))
                }
                onChange()
            }
            "msg" -> {
                if (!c.canChat) return
                val id = inner.optString("id")
                synchronized(this) {
                    if (id.isEmpty() || c.msgs.any { it.id == id }) return // replayed
                    c.msgs.add(incoming(id, inner, c.ttl))
                    if (openChat != c.user) c.unread++
                }
                c.theirTypingUntil = 0   // the message they were typing has arrived
                if (openChat != c.user && !inner.optBoolean("sil")) onNew(c)   // sent "without sound": no notification
            }
        }
    }

    private fun incoming(id: String, inner: JSONObject, ttl: Int): Msg {
        val msg = Msg(id, false, inner.optString("x").take(MAX_TEXT), System.currentTimeMillis(), Msg.RECEIVED)
        msg.file = inner.optJSONObject("f")?.let { checkFile(it) }
        msg.reply = Msg.replyRef(inner.optJSONObject("r"))
        msg.fwd = Msg.fwdOf(inner)
        msg.lp = LinkPreview.clean(inner.optJSONObject("lp"))
        msg.loc = Msg.locOf(inner.optJSONObject("loc"))
        if (ttl > 0) msg.expires = System.currentTimeMillis() + ttl * 1000L
        if (msg.file != null) toFetch.add(msg)
        return msg
    }

    /** Only accept attachment details that look right. */
    private fun checkFile(f: JSONObject): JSONObject? {
        val id = f.optString("id")
        val k = f.optString("k")
        val size = f.optInt("s", -1)
        if (id.length != 32 || !id.all { it in "0123456789abcdef" } || k.length != 64 || !k.all { it in "0123456789abcdef" }) return null
        if (size !in 0..MAX_FILE) return null
        val out = JSONObject().put("id", id).put("k", k).put("n", cleanName(f.optString("n"))).put("m", cleanMime(f.optString("m"))).put("s", size)
        f.optString("th").takeIf { it.isNotEmpty() && it.length <= 8000 }?.let { out.put("th", it) }
        if (f.optBoolean("grp")) out.put("grp", true)   // keep: tells fetchFile not to delete-on-pickup (group fan-out)
        return out
    }

    /** Downloads one received attachment, checks it opens, keeps the locked copy, tells the server to forget it. */
    fun fetchFile(msg: Msg): Boolean {
        val f = msg.file ?: return false
        if (f.has("local") || f.optBoolean("cleared")) return true
        val id = f.getString("id")
        val blob = relay.download(id)
        if (Crypto.gcmOpen(f.getString("k").unhex(), FILE_LABEL, blob) == null) return false
        saveBlob(id, blob)
        synchronized(this) { f.put("local", id) }
        // group files fan out to many members, so leave the server copy for its TTL instead of
        // deleting on first pickup (which would deny it to everyone else)
        if (!f.optBoolean("grp")) runCatching { relay.doneWith(id) }
        onChange()
        return true
    }

    /** Signal's safety number: 60 digits from both identity keys, the same on both phones. */
    /** The whole local state, encrypted with the password-derived key. Survives an uninstall when
     *  saved to a file the user keeps. Header is cleartext so a restore screen can name the account. */
    fun fullBackup(): ByteArray {
        val m = me ?: throw UserError("Not signed in")
        val cipher = Crypto.gcmSeal(m.backupKey, "msgr01-device|${m.user}", toJson().toString().toByteArray())
        return ("MSGR01\n${m.user}\n").toByteArray() + cipher
    }

    /** Reads who a backup file belongs to (from its cleartext header), or null if it isn't one. */
    fun backupOwner(bytes: ByteArray): String? {
        val head = String(bytes.copyOf(minOf(bytes.size, 80)))
        if (!head.startsWith("MSGR01\n")) return null
        val nl = head.indexOf('\n', 7)
        return if (nl > 7) head.substring(7, nl) else null
    }

    /** Restores a device backup with the account password. Returns true on success. */
    fun restoreFull(bytes: ByteArray, password: String): Boolean {
        val user = backupOwner(bytes) ?: return false
        val header = "MSGR01\n$user\n".toByteArray()
        if (bytes.size <= header.size) return false
        val key = Crypto.passwordKeys(user, password).backup
        val plain = Crypto.gcmOpen(key, "msgr01-device|$user", bytes.copyOfRange(header.size, bytes.size)) ?: return false
        synchronized(this) {
            loadJson(JSONObject(String(plain)))
            me?.keysPublished = false // re-announce prekeys; the old phone's are stale
        }
        onChange()
        return me != null
    }

    fun safetyCode(c: Contact): String? {
        val m = me ?: return null
        val theirs = c.theirIdent ?: return null
        val f = NumericFingerprintGenerator(5200).createFor(2, m.user.toByteArray(), m.signal.identity.publicKey,
            c.user.toByteArray(), IdentityKey(unb64(theirs)))
        return f.displayableFingerprint.displayText.chunked(5).joinToString(" ")
    }

    private fun changed() {
        backupDirty = true
        onChange()
    }

    private fun note(text: String) = Msg(Crypto.randomHex(16), false, text, System.currentTimeMillis(), Msg.NOTE)
    private fun parse(o: Crypto.Opened): JSONObject? {
        val inner = o.inner ?: return null
        return try { JSONObject(String(inner)) } catch (e: Exception) { null }
    }

    // ---------- saving on this phone ----------

    @Synchronized
    fun toJson(): JSONObject {
        val o = JSONObject()   // the server address is fixed in the app now (hardcoded onion), not saved
        me?.let { m ->
            o.put("me", JSONObject().put("user", m.user).put("name", m.name).put("device", m.device)
                .put("enc", b64(Crypto.privateBytes(m.enc))).put("sig", b64(Crypto.privateBytes(m.sig)))
                .put("inbox", m.inboxToken).put("backupKey", m.backupKey.hex())
                .put("avatar", m.avatar?.let { b64(it) })
                .put("signal", m.signal.toJson()).put("keysPublished", m.keysPublished)
                .put("cloudBackup", m.cloudBackup))
        }
        return o.put("contacts", JSONArray(contacts.map { it.json(withMsgs = true) })).put("backupDirty", backupDirty)
            .put("readReceipts", readReceipts).put("showTyping", showTyping).put("showSeen", showSeen).put("blocked", JSONArray(blocked.toList())).put("groups", JSONArray(groups.map { it.json(withMsgs = true) }))
    }

    @Synchronized
    fun loadJson(o: JSONObject) {
        // (server address is hardcoded in the app now; any old saved "server" is ignored)
        val mo = o.optJSONObject("me") ?: return
        val enc = Crypto.parsePrivate(unb64(mo.getString("enc")), Crypto.ENC_TEMPLATE) ?: return
        val sig = Crypto.parsePrivate(unb64(mo.getString("sig")), Crypto.SIG_TEMPLATE) ?: return
        me = Me(mo.getString("user"), mo.getString("name"), mo.getString("device"), enc, sig,
            mo.getString("inbox"), mo.getString("backupKey").unhex(),
            mo.optString("avatar").takeIf { it.isNotEmpty() }?.let { unb64(it) })
        val signal = mo.optJSONObject("signal")
        signal?.let { me!!.signal = SignalStore.fromJson(it); me!!.keysPublished = mo.optBoolean("keysPublished") }
        me!!.cloudBackup = mo.optBoolean("cloudBackup", true)
        val a = o.optJSONArray("contacts") ?: JSONArray()
        contacts.clear()
        for (i in 0 until a.length()) contacts.add(Contact.from(a.getJSONObject(i)))
        if (signal == null) for (c in contacts) {
            // upgrading from 2.x: new encryption, so new safety codes
            c.verified = false
            c.msgs.add(note("MSNGR now uses Signal's encryption. Your safety code with ${c.name} changed; check it again."))
        }
        backupDirty = o.optBoolean("backupDirty")
        readReceipts = o.optBoolean("readReceipts", true)
        showTyping = o.optBoolean("showTyping", true)
        showSeen = o.optBoolean("showSeen", true)
        blocked.clear(); o.optJSONArray("blocked")?.let { for (i in 0 until it.length()) blocked.add(it.getString(i)) }
        groups.clear()
        o.optJSONArray("groups")?.let { for (i in 0 until it.length()) groups.add(Group.from(it.getJSONObject(i))) }
        // attachments that arrived but weren't downloaded yet
        for (c in contacts) for (msg in c.msgs) if (!msg.mine && msg.file?.has("local") == false && msg.file?.optBoolean("cleared") != true) toFetch.add(msg)
    }

    companion object {
        const val MAX_TEXT = 4000
        const val MAX_AVATAR = 8000 // bytes of JPEG; fits in one sealed message
        const val MAX_FILE = 25 * 1024 * 1024
        const val TYPING_MS = 6_000L
        const val ONLINE_MS = 5 * 60_000L   // "online" lasts this long unless refreshed (the app re-sends every 4 min)
        const val NET_TOR = "tor"
        const val NET_DIRECT = "direct"
        const val GROUP_FILE_TTL = 7 * 24 * 3600   // group blobs fan out (no per-reader delete); keep them a week
        const val REKEY_RESEND_WINDOW = 30L * 24 * 3600 * 1000  // owner re-posts a re-key this long so offline members migrate
        const val REKEY_RESEND_EVERY = 24L * 3600 * 1000        // ...about once a day
        private const val FILE_LABEL = "telegramnt-file"

        fun cleanName(n: String) = n.replace(Regex("[/\\\\\\u0000-\\u001f]"), "_").take(120).ifBlank { "file" }
        fun cleanMime(m: String) = m.lowercase().takeIf { Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(it) } ?: "application/octet-stream"
        val USERNAME = Regex("^[a-z0-9_]{5,32}$")

        fun checkUsername(u: String) {
            if (!USERNAME.matches(u)) throw UserError("Usernames are 5-32 characters: a-z, 0-9 and _")
        }

        const val AVATAR_PAD = 8192 // every avatar is sent at this exact size, so size reveals nothing

        fun padAvatar(jpeg: ByteArray): ByteArray {
            require(jpeg.size <= AVATAR_PAD - 4)
            return ByteBuffer.allocate(AVATAR_PAD).putInt(jpeg.size).put(jpeg).array()
        }

        fun unpadAvatar(p: ByteArray): ByteArray? {
            if (p.size != AVATAR_PAD) return null
            val n = ByteBuffer.wrap(p).int
            return if (n in 1..(AVATAR_PAD - 4)) p.copyOfRange(4, 4 + n) else null
        }

        /** Attachments are padded up to size steps so their exact size doesn't give them away. */
        fun padFile(data: ByteArray): ByteArray {
            val need = data.size + 4
            var step = 16 * 1024
            while (step < need && step < 1024 * 1024) step *= 2
            val total = if (need <= step) step else ((need + 1048575) / 1048576) * 1048576
            return ByteBuffer.allocate(total).putInt(data.size).put(data).array()
        }

        fun unpadFile(p: ByteArray): ByteArray? {
            if (p.size < 4) return null
            val n = ByteBuffer.wrap(p).int
            return if (n < 0 || n > p.size - 4) null else p.copyOfRange(4, 4 + n)
        }

        private fun isAddr(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = try { Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { ByteArray(0) }
    }
}
