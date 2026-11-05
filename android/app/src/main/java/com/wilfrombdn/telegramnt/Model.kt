package com.wilfrombdn.telegramnt

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import com.google.crypto.tink.KeysetHandle
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.util.UUID

// The plain data of the app: a message, a friend, my account, a group. No network or crypto in here.

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
    var backupKey: ByteArray,   // changes once, when an old account moves from PBKDF2 to Argon2id
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
    var desc = ""                         // description (set by an admin)
    var avatar: ByteArray? = null         // group photo (small JPEG, set by an admin)
    var ttl = 0                           // disappearing-messages timer in seconds for the whole group (0 = off)
    var joinMode = 0                      // 0 = friends added by an admin only; 1 = admins share a link and approve each request
    var linkSecret = ""                   // join-link secret (admins only; changing it cancels every old link)
    var joinCursor = 0L                   // last join-request mailbox position read (admins)
    val requests = mutableListOf<JoinReq>()   // people waiting for an admin to approve them
    var mentioned = false                 // someone @mentioned me in a message I haven't seen yet
    @Volatile var mentionMsg: String? = null   // the first message that mentioned me and I have not seen (not saved)
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
        .put("desc", desc).put("avatar", avatar?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "").put("ttl", ttl)
        .put("joinMode", joinMode).put("linkSecret", linkSecret).put("joinCursor", joinCursor)
        .put("requests", JSONArray(requests.map { JSONObject().put("u", it.user).put("t", it.at) }))
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
            g.desc = o.optString("desc"); g.ttl = o.optInt("ttl")
            g.avatar = o.optString("avatar").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            g.joinMode = o.optInt("joinMode"); g.linkSecret = o.optString("linkSecret"); g.joinCursor = o.optLong("joinCursor")
            o.optJSONArray("requests")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { r -> g.requests.add(JoinReq(r.getString("u"), r.optLong("t"))) } }
            o.optJSONArray("rekeyDue")?.let { for (i in 0 until it.length()) g.rekeyDue.add(it.getString(i)) }
            o.optJSONArray("rekeyResend")?.let { for (i in 0 until it.length()) it.getJSONObject(i).let { r ->
                g.rekeyResend.add(ResendItem(r.getString("addr"), r.getString("blob"), r.getLong("until"), r.optLong("last")))
            } }
            o.optJSONArray("msgs")?.let { for (i in 0 until it.length()) g.msgs.add(Msg.from(it.getJSONObject(i))) }
            return g
        }
    }
}

/** Someone who used a group's join link and is waiting for an admin to approve them. */
class JoinReq(val user: String, val at: Long)

class UserError(msg: String) : IOException(msg)
