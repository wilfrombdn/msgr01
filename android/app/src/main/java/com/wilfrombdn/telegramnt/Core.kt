package com.wilfrombdn.telegramnt

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
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

/** The app's name as people see it. Change it here and in res/values/strings.xml (the launcher label). */
const val APP_NAME = "Tellem"

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
        PasswordRules.check(user, password)?.let { throw UserError(it) }
        val keys = Crypto.passwordKeys(user, password, Crypto.KDF_ARGON2)
        val m = Me(user, name.trim().take(60).ifEmpty { user }, "", Crypto.newKeyset(Crypto.ENC_TEMPLATE),
            Crypto.newKeyset(Crypto.SIG_TEMPLATE), Crypto.randomHex(), keys.backup)
        try {
            m.device = relay.signup(user, keys.auth, Crypto.publicBytes(m.enc), m.sigPub, m.inboxToken, lockedBackup(m, emptyList()), Crypto.KDF_ARGON2)
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
        // old accounts still use PBKDF2; the server says which, then it's moved to Argon2id after this login
        val kdf = try { relay.kdf(user) } catch (e: Relay.RelayError) { throw UserError("Server error") }
        val keys = Crypto.passwordKeys(user, password, kdf)
        val (device, locked) = try {
            relay.login(user, keys.auth).let { r ->
                if (r.kdf != kdf) throw Relay.RelayError(409, "retry")   // moved to the new hashing while we were asking
                r.device to r.backup
            }
        } catch (e: Relay.RelayError) {
            throw UserError(if (e.code == 403) e.reason.replaceFirstChar { it.uppercase() } else if (e.code == 409) "Please try again" else "Server error")
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
        // an account made before 9.0: re-lock the backup with the Argon2id key (quietly; it tries again next login if this fails)
        if (kdf == Crypto.KDF_PBKDF2) runCatching { upgradeKdf(m, password) }
        // Friends' photos aren't in the backup: send ours and ask for theirs. This also starts
        // new Signal sessions, since the old ones stayed on the old phone.
        for (c in synchronized(this) { contacts.filter { it.status == Contact.FRIENDS } }) runCatching { sendAvatar(c, ask = true) }
        for (g in synchronized(this) { groups.toList() }) runCatching { postMyDist(m, g) }
    }

    /** Moves an account from PBKDF2 to Argon2id: a new login hash, and the backup locked with the new key. */
    private fun upgradeKdf(m: Me, password: String) {
        val fresh = Crypto.passwordKeys(m.user, password, Crypto.KDF_ARGON2)
        val old = m.backupKey
        m.backupKey = fresh.backup
        try {
            relay.upgrade(m.user, m.device, fresh.auth, lockedBackup(m, synchronized(this) { contacts.filter { it.status != Contact.SAVED } }))
        } catch (e: Exception) {
            m.backupKey = old   // stay on the old key; the server still has the old backup
            throw e
        }
        backupDirty = false
        onChange()
    }

    fun deleteAccount(password: String) {
        val m = me ?: return
        val kdf = try { relay.kdf(m.user) } catch (e: Relay.RelayError) { throw UserError("Server error") }
        relay.deleteAccount(m.user, Crypto.passwordKeys(m.user, password, kdf).auth)
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

    /** What my QR code carries, so a scanner can check the directory gave it my real keys. */
    fun myKeyFingerprint(): String? {
        val m = me ?: return null
        return Crypto.keyFingerprint(m.signal.identity.publicKey.serialize(), m.sigPub, Crypto.publicBytes(m.enc))
    }

    /**
     * Sends a friend request to @user. Returns the new (or existing) chat. [expectKeys] comes from their QR code: when given,
     * the directory's answer must match it, or nothing is sent (that would mean the server, or someone in between, is handing out wrong keys).
     */
    fun addFriend(raw: String, expectKeys: String? = null): Contact {
        val m = me ?: throw UserError("Not signed in")
        val user = raw.trim().removePrefix("@").lowercase()
        checkUsername(user)
        if (user == m.user) throw UserError("That's you!")
        contact(user)?.let { return it }
        val p = relay.lookup(user) ?: throw UserError("No one has the username @$user")
        if (p.ident.isEmpty()) throw UserError("@$user needs to update $APP_NAME first")
        if (expectKeys != null && expectKeys != Crypto.keyFingerprint(unb64(p.ident), unb64(p.sig), unb64(p.enc)))
            throw UserError("That code doesn't match @$user's keys on the server. Someone may be pretending to be them, so nothing was sent.")
        val c = Contact(user, "@$user", Contact.ASKED, Crypto.randomHex(), null, p.enc, p.sig, theirInbox = p.inbox, theirIdent = p.ident)
        if (expectKeys != null) c.verified = true   // their own code vouched for these keys, so the safety codes will match
        relay.fetch(listOf(c.myToken), emptyList(), 0) // my mailbox exists before they can answer
        sealSignal(m, c, p.inbox, JSONObject().put("t", "request").put("u", m.user).put("n", m.name).put("a", c.myAddr))
        c.msgs.add(note("Friend request sent. Anything you write now arrives as a message request."))
        if (expectKeys != null) c.msgs.add(note("Their QR code matched their keys, so this really is them."))
        synchronized(this) { if (contact(user) == null) contacts.add(c) }
        contact(user)?.let { takeEarly(it) }
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

    /**
     * Answers a request that has already been opened. That can't be read a second time, so if the answer
     * can't go out now (network), it's kept and sent on a later round instead of losing the request.
     */
    private fun acceptSoon(c: Contact, friend: Boolean) {
        try { accept(c, friend) } catch (e: Exception) {
            synchronized(this) { acceptDue[c.user] = friend }
            changed()
        }
    }

    /** Answers still to send: username to "also as a friend". */
    private val acceptDue = HashMap<String, Boolean>()

    private fun sendDueAccepts() {
        for ((user, friend) in synchronized(this) { acceptDue.toMap() }) {
            val c = contact(user)
            if (c == null || runCatching { accept(c, friend) }.isSuccess) { synchronized(this) { acceptDue.remove(user) }; changed() }
        }
    }

    /** From a message-request chat I accepted: add them as a friend after all. */
    fun makeFriend(c: Contact) = accept(c, friend = true)

    // ---------- group invitations ----------

    /** A friend asked me into a group. Nothing happens until I say yes: joining shows my username to the whole group. */
    class Invite(val from: String, val secret: String, val name: String, val public: Boolean, val owner: String, val at: Long = System.currentTimeMillis())

    private val invites = mutableListOf<Invite>()

    /** Invitations waiting for an answer, newest first. */
    fun pendingInvites(): List<Invite> = synchronized(this) { invites.sortedByDescending { it.at } }

    fun acceptInvite(inv: Invite) {
        val g = joinGroup(inv.secret, inv.name, inv.public, inv.owner)
        synchronized(this) { g.members.add(inv.from); invites.removeAll { it.secret == inv.secret } }
        changed()
    }

    /** Local only: the person who asked isn't told. */
    fun declineInvite(inv: Invite) {
        synchronized(this) { invites.removeAll { it.secret == inv.secret } }
        changed()
    }

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

    /** A group link joins at once; a join link only sends a request (returns null, an admin has to approve). */
    fun joinGroupLink(link: String): Group? {
        if (link.contains("msgr01://join")) { requestJoin(link); return null }
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
        // the invite is already opened (it can't be read twice), so never lose the group over a network blip:
        // my sender key goes out later, before my first post (distSent stays false)
        runCatching { postMyDist(m, g) }
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
        if (g.owner.isEmpty() || !g.isLeader(m.user)) throw UserError("Only an admin can remove members")
        val removeN = remove.map { it.removePrefix("@").lowercase() }.toSet()
        // admins can remove members; only the owner can remove an admin (and nobody can remove the owner)
        if (m.user != g.owner && removeN.any { it == g.owner || it in g.leaders }) throw UserError("Only the owner can remove an admin")
        if (g.owner in removeN) throw UserError("The owner can't be removed")
        val kept = synchronized(this) { g.members.filter { it != m.user && it !in removeN } + listOfNotNull(g.owner.takeIf { it != m.user }) }.distinct()
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
        synchronized(this) { g.msgs.add(timed(g, msg)) }
        onChange()
        return msg
    }

    // ---------- polls (groups) ----------

    fun sendPoll(g: Group, question: String, options: List<String>): Msg {
        val m = me ?: throw UserError("Not signed in")
        val poll = Msg.pollOf(JSONObject().put("q", question).put("o", JSONArray(options))) ?: throw UserError("A poll needs a question and at least 2 options")
        if (!g.canPost(m.user)) throw UserError("Only admins can post in this group")
        val msg = Msg(Crypto.randomHex(16), true, "", System.currentTimeMillis(), Msg.SENDING).also { it.poll = poll; it.sender = m.user }
        synchronized(this) { g.msgs.add(timed(g, msg)) }
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
    fun setGroup(g: Group, name: String, readOnly: Boolean, leaders: Set<String>, desc: String = g.desc, ttl: Int = g.ttl) {
        val m = me ?: return
        if (!g.isLeader(m.user)) throw UserError("Only an admin can change this group")
        synchronized(this) {
            g.name = name.trim().take(80).ifEmpty { g.name }
            g.readOnly = readOnly
            g.desc = desc.trim().take(MAX_GROUP_DESC)
            if (ttl != g.ttl) { g.ttl = ttl.coerceIn(0, 31 * 86400); g.msgs.add(note(if (g.ttl == 0) "Disappearing messages turned off." else "Disappearing messages: on, ${timerLabel(g.ttl)}. New messages delete on every phone.")) }
            if (m.user == g.owner) g.leaders.clear()   // only the owner can take admin rights away
            g.leaders.addAll(leaders); if (g.owner.isNotEmpty()) g.leaders.add(g.owner)
        }
        broadcastSet(g, m)
    }

    /** In a group with a disappearing-messages timer, a new message gets its deadline when it is added. */
    private fun timed(g: Group, msg: Msg): Msg {
        if (g.ttl > 0 && msg.state != Msg.NOTE) msg.expires = System.currentTimeMillis() + g.ttl * 1000L
        return msg
    }

    /** Admin: set (or clear, with null) the group photo and send it to everyone. */
    fun setGroupPhoto(g: Group, jpeg: ByteArray?) {
        val m = me ?: return
        if (!g.isLeader(m.user) || g.owner.isEmpty()) throw UserError("Only an admin can change the group photo")
        require(jpeg == null || jpeg.size <= MAX_AVATAR) { "photo too big" }
        synchronized(this) { g.avatar = jpeg }
        changed()
        runCatching { sendGroupPhoto(g, m) }
    }

    private fun sendGroupPhoto(g: Group, m: Me) {
        gctl(g, m, JSONObject().put("t", "gpic").put("d", g.avatar?.let { b64(it) } ?: ""))
    }

    /** A new member just appeared: an admin hands them the description, timer and photo (they missed the earlier broadcasts). */
    private fun resendProfile(m: Me, g: Group) {
        if (!g.isLeader(m.user) || g.owner.isEmpty()) return
        if (g.setVer > 0) gctl(g, m, JSONObject().put("t", "set").put("ver", g.setVer).put("n", g.name)
            .put("ro", g.readOnly).put("ldr", JSONArray(g.leaders.toList()))
            .put("jm", g.joinMode).put("lk", g.linkSecret).put("d", g.desc).put("tl", g.ttl))
        if (g.avatar != null) sendGroupPhoto(g, m)
    }

    private fun broadcastSet(g: Group, m: Me) {
        synchronized(this) { g.setVer++ }
        changed()
        runCatching {
            gctl(g, m, JSONObject().put("t", "set").put("ver", g.setVer).put("n", g.name)
                .put("ro", g.readOnly).put("ldr", JSONArray(g.leaders.toList()))
                .put("jm", g.joinMode).put("lk", g.linkSecret).put("d", g.desc).put("tl", g.ttl))
        }
    }

    // ---------- joining by link, with admin approval ----------
    // The link holds only a "join ticket", never the group's secret. A person who uses it posts a signed request to the
    // group's join mailbox; an admin approves, and only then is the secret sealed to that one person's key.

    private fun joinAddr(owner: String, ls: String) = Crypto.sha256("msgr01-join|$owner|$ls".toByteArray()).hex()
    private fun joinKey(owner: String, ls: String) = Crypto.sha256("msgr01-join-key|$owner|$ls".toByteArray())
    private fun joinReplyAddr(owner: String, ls: String, user: String) = Crypto.sha256("msgr01-join-ok|$owner|$ls|$user".toByteArray()).hex()
    private fun joinReqBytes(addr: String, user: String, ts: Long) = "msgr01-join-req|$addr|$user|$ts".toByteArray()

    /** Admin: turn approval-by-link on (1) or off (0). Turning it off cancels the link. */
    fun setJoinMode(g: Group, mode: Int) {
        val m = me ?: return
        if (!g.isLeader(m.user) || g.owner.isEmpty()) throw UserError("Only an admin can change this")
        synchronized(this) {
            g.joinMode = if (mode == 1) 1 else 0
            if (g.joinMode == 1 && g.linkSecret.isEmpty()) { g.linkSecret = Crypto.randomHex(); g.joinCursor = 0 }
            if (g.joinMode == 0) { g.linkSecret = ""; g.requests.clear() }
        }
        broadcastSet(g, m)
    }

    /** Admin: cancel the current link and make a new one. People already approved stay in. */
    fun revokeJoinLink(g: Group) {
        val m = me ?: return
        if (!g.isLeader(m.user) || g.joinMode != 1) throw UserError("Turn on join requests first")
        synchronized(this) { g.linkSecret = Crypto.randomHex(); g.joinCursor = 0; g.requests.clear() }
        broadcastSet(g, m)
    }

    fun groupJoinLink(g: Group): String {
        val o = JSONObject().put("l", g.linkSecret).put("n", g.name).put("o", g.owner)
        return "msgr01://join#" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    /** A person waiting for an answer to a join request, remembered until approved or a week passes. */
    class JoinWait(val link: String, val name: String, val owner: String, val at: Long = System.currentTimeMillis(), var cursor: Long = 0)
    private val joinWaits = mutableListOf<JoinWait>()
    fun pendingJoins(): List<JoinWait> = synchronized(this) { joinWaits.toList() }

    /** Uses a join link: sends a signed request to the group's admins. Returns the group name. */
    fun requestJoin(link: String): String {
        val m = me ?: throw UserError("Not signed in")
        val frag = link.trim().substringAfter('#', "")
        val o = try { JSONObject(String(Base64.getUrlDecoder().decode(frag))) } catch (e: Exception) { throw UserError("That isn't a valid group link") }
        val ls = o.optString("l"); val owner = o.optString("o")
        if (ls.length != 64 || !ls.all { it in "0123456789abcdef" } || !USERNAME.matches(owner)) throw UserError("That isn't a valid group link")
        val name = o.optString("n").take(80).ifBlank { "Group" }
        if (synchronized(this) { groups.any { it.owner == owner && it.joinMode == 1 && it.linkSecret == ls } }) throw UserError("You're already in that group")
        val addr = joinAddr(owner, ls)
        val ts = System.currentTimeMillis()
        val sig = m.signal.identity.privateKey.calculateSignature(joinReqBytes(addr, m.user, ts))
        val req = JSONObject().put("u", m.user).put("ts", ts).put("sig", b64(sig))
        relay.gsend(addr, Crypto.gcmSeal(joinKey(owner, ls), "msgr01-join", req.toString().toByteArray()))
        synchronized(this) { if (joinWaits.none { it.link == ls }) joinWaits.add(JoinWait(ls, name, owner)) }
        changed()
        return name
    }

    /** Joiner: look for an approval. Called from the poll loop. */
    private fun pollJoins(m: Me) {
        val now = System.currentTimeMillis()
        synchronized(this) { joinWaits.removeAll { now - it.at > 7 * 24 * 3600_000L } }
        for (w in synchronized(this) { joinWaits.toList() }) runCatching {
            val reply = joinReplyAddr(w.owner, w.link, m.user)
            val got = relay.gfetch(reply, w.cursor, 0)
            for (gm in got) {
                w.cursor = maxOf(w.cursor, gm.seq)
                val o = Crypto.open(m.enc, reply, gm.blob) ?: continue
                val inner = try { JSONObject(String(o.inner ?: continue)) } catch (e: Exception) { continue }
                val a = inner.optString("a"); val secret = inner.optString("s")
                if (!USERNAME.matches(a) || inner.optString("o") != w.owner) continue
                if (secret.length != 64 || !secret.all { it in "0123456789abcdef" }) continue
                val p = relay.lookup(a) ?: continue
                val sigKey = Crypto.parsePublic(unb64(p.sig), Crypto.SIG_TEMPLATE) ?: continue
                if (!Crypto.verify(sigKey, o)) continue   // only the person named as approver can have made this
                val g = joinGroup(secret, inner.optString("n").take(80).ifBlank { w.name }, inner.optBoolean("p"), w.owner)
                synchronized(this) { g.members.add(a); joinWaits.remove(w) }
                changed()
                return@runCatching
            }
        }
    }

    /** Admin: look for new join requests. */
    private fun pollJoinRequests(m: Me, g: Group) {
        if (g.joinMode != 1 || g.linkSecret.isEmpty() || !g.isLeader(m.user)) return
        val addr = joinAddr(g.owner, g.linkSecret)
        val key = joinKey(g.owner, g.linkSecret)
        val got = relay.gfetch(addr, g.joinCursor, 0)
        for (gm in got) {
            g.joinCursor = maxOf(g.joinCursor, gm.seq)
            val plain = Crypto.gcmOpen(key, "msgr01-join", gm.blob) ?: continue
            val r = try { JSONObject(String(plain)) } catch (e: Exception) { continue }
            val u = r.optString("u"); val ts = r.optLong("ts")
            if (!USERNAME.matches(u) || u == m.user) continue
            if (synchronized(this) { u in g.members || g.requests.any { it.user == u } || g.requests.size >= MAX_JOIN_REQUESTS }) continue
            val p = relay.lookup(u) ?: continue
            val ident = try { IdentityKey(unb64(p.ident)) } catch (e: Exception) { continue }
            if (!ident.publicKey.verifySignature(joinReqBytes(addr, u, ts), unb64(r.optString("sig")))) continue   // someone posting as @u
            synchronized(this) { g.requests.add(JoinReq(u, System.currentTimeMillis())) }
        }
        if (got.isNotEmpty()) changed()
    }

    /** Admin: let [user] in. Only now is the group's secret sealed to them. */
    fun approveJoin(g: Group, user: String) {
        val m = me ?: return
        if (!g.isLeader(m.user)) throw UserError("Only an admin can approve")
        val p = relay.lookup(user) ?: throw UserError("@$user isn't on Tellem any more")
        val enc = Crypto.parsePublic(unb64(p.enc), Crypto.ENC_TEMPLATE) ?: throw UserError("@$user's keys look wrong")
        val reply = joinReplyAddr(g.owner, g.linkSecret, user)
        val inner = JSONObject().put("a", m.user).put("s", g.secret).put("n", g.name).put("p", g.public).put("o", g.owner)
        relay.gsend(reply, Crypto.seal(m.sig, enc, reply, inner.toString().toByteArray()))
        synchronized(this) { g.members.add(user); g.requests.removeAll { it.user == user }; g.msgs.add(note("@$user was approved to join.")) }
        changed()
        runCatching { gctl(g, m, JSONObject().put("t", "jdone").put("u", user).put("ok", true)) }
    }

    fun denyJoin(g: Group, user: String) {
        val m = me ?: return
        if (!g.isLeader(m.user)) throw UserError("Only an admin can do this")
        synchronized(this) { g.requests.removeAll { it.user == user } }
        changed()
        runCatching { gctl(g, m, JSONObject().put("t", "jdone").put("u", user)) }
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
        synchronized(this) { g.msgs.add(timed(g, msg)) }
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
        runCatching { pollJoinRequests(m, g) }
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
                if (isNew && u != m.user) runCatching { resendProfile(m, g) }
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
            // The outer "gdel" trusts the forgeable "u", so any member could erase anyone's message with it.
            // Deletes now come only through the authenticated gctl "gdel" (handleCtl); this one is ignored.
            "gdel" -> {}
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
            "gdel" -> {
                val id = p.optString("id")
                // u is proven by the sender key, and only the original sender may erase a group message
                synchronized(this) { g.msgs.removeAll { it.id == id && it.sender == u } }
                onChange()
            }
            "file" -> {
                if (!g.canPost(u)) return   // read-only: ignore posts from non-leaders
                val id = p.optString("id")
                val f = p.optJSONObject("f")?.let { checkFile(it) } ?: return
                if (id.isEmpty() || synchronized(this) { g.msgs.any { it.id == id } }) return
                val msg = Msg(id, false, "", System.currentTimeMillis(), Msg.RECEIVED).also { it.sender = u; it.file = f; it.fwd = Msg.fwdOf(p) }
                synchronized(this) { g.msgs.add(timed(g, msg)); if (openChat != "g:${g.id}") g.unread++ }
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
                synchronized(this) { g.msgs.add(timed(g, msg)); if (openChat != "g:${g.id}") g.unread++ }
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
                        // an admin can add admins; only the owner can take admin rights away
                        if (u == g.owner) g.leaders.clear()
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { USERNAME.matches(it) }?.let { g.leaders.add(it) }
                        if (g.owner.isNotEmpty()) g.leaders.add(g.owner)
                    }
                    if (p.has("d")) g.desc = p.optString("d").take(MAX_GROUP_DESC)
                    if (p.has("tl")) {
                        val tl = p.optInt("tl").coerceIn(0, 31 * 86400)
                        if (tl != g.ttl) { g.ttl = tl; g.msgs.add(note(if (tl == 0) "@$u turned off disappearing messages." else "@$u set disappearing messages: ${timerLabel(tl)}.")) }
                    }
                    if (p.has("jm")) {
                        g.joinMode = if (p.optInt("jm") == 1) 1 else 0
                        val lk = p.optString("lk")
                        val ok = lk.length == 64 && lk.all { it in "0123456789abcdef" }
                        val mine = if (g.isLeader(m.user) && g.joinMode == 1 && ok) lk else ""   // only admins keep the link secret
                        if (mine != g.linkSecret) { g.linkSecret = mine; g.joinCursor = 0 }
                        if (g.joinMode == 0) g.requests.clear()
                    }
                }
                onChange()
            }
            "gpic" -> {   // the group photo: admins only, small JPEGs only
                if (!g.isLeader(u) || g.owner.isEmpty()) return
                val d = p.optString("d")
                val bytes = if (d.isEmpty()) null else unb64(d)
                if (bytes != null && (bytes.size < 4 || bytes.size > MAX_AVATAR || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte())) return
                synchronized(this) { g.avatar = bytes }
                onChange()
            }
            "jdone" -> {   // another admin answered a join request
                if (!g.isLeader(u)) return
                val who = p.optString("u")
                synchronized(this) { g.requests.removeAll { it.user == who }; if (p.optBoolean("ok")) g.members.add(who) }
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
            "rekey" -> {   // the owner or an admin may re-key, and only to a newer epoch
                val ep = p.optInt("ep", 0)
                if (g.owner.isEmpty() || !g.isLeader(u) || ep <= g.epoch) return
                val keys = p.optJSONObject("keys") ?: return
                val mine = keys.optString(m.user)
                if (mine.isEmpty()) {   // no piece for me => I was removed
                    if (u != g.owner && (m.user == g.owner || m.user in g.leaders)) return   // an admin can't remove the owner or another admin
                    synchronized(this) { g.msgs.add(note("You were removed from this group.")) }
                    synchronized(this) { groups.remove(g) }
                    onChange(); return
                }
                val opened = Crypto.open(m.enc, rekeyBind(g.owner, g.secret, ep, m.user), unb64(mine)) ?: return
                val newSecret = opened.inner?.let { String(it) } ?: return
                if (newSecret.length != 64 || !newSecret.all { it in "0123456789abcdef" }) return
                val leaders = mutableSetOf<String>().apply { p.optJSONArray("ldr")?.let { for (i in 0 until it.length()) add(it.getString(i)) } }
                val kept = mutableSetOf(g.owner, u, m.user).apply { keys.keys().forEach { add(it) } }
                val removed = synchronized(this) { g.members.filter { it !in kept } }.toSet()
                // an admin can't push out the owner or another admin; only the owner can
                if (u != g.owner && removed.any { it == g.owner || it in g.leaders }) return
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
            g.msgs.add(timed(g, msg))
            if (openChat != "g:${g.id}") g.unread++
        }
        val mine = me?.user
        val toMe = mine != null && (Format.MENTION.findAll(text).any { it.groupValues[1] == mine || (it.groupValues[1] == "everyone" && g.owner.isNotEmpty() && g.isLeader(u)) } || reply?.optString("u") == mine)
        if (toMe && openChat != "g:${g.id}") { g.mentioned = true; if (g.mentionMsg == null) g.mentionMsg = id; onMention(g) }
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

    /** The invite reached the server: the caller now sees "Ringing…" (like Telegram), until answered or timed out. */
    fun offerDelivered(k: Call) { if (k.phase == Call.DIALING) { k.phase = Call.RINGING; onCall(k) } }

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

    /**
     * Ends [k] here and writes this phone's summary into the chat. Only the live call is reported to the
     * call screen: a missed call logged while I'm on another one must not hang that one up.
     */
    fun endCall(k: Call, status: String) {
        if (k.phase == Call.ENDED) return
        k.phase = Call.ENDED
        val live = call === k
        if (live) call = null
        val c = contact(k.user)
        // never answered: make sure their phone shows the missed call too, even if my invite never reached it
        if (k.outgoing && status in UNANSWERED) {
            synchronized(this) { callNotes.add(JSONObject().put("u", k.user).put("id", k.id).put("ts", k.startedAt)) }
            Thread { sendCallNotes() }.start()
        }
        if (c != null && !hasCallRecord(c, k.id)) {
            val dur = if (k.connectedAt > 0) ((System.currentTimeMillis() - k.connectedAt) / 1000).toInt() else 0
            val missed = status == "missed"
            val msg = Msg(Crypto.randomHex(16), k.outgoing, "", System.currentTimeMillis(), if (k.outgoing) Msg.SENT else Msg.RECEIVED)
            msg.call = JSONObject().put("d", if (k.outgoing) "out" else "in").put("s", status).put("dur", dur).put("id", k.id)
            synchronized(this) { c.msgs.add(msg); if (missed && openChat != c.user) c.unread++ }
            onChange()
            if (missed && openChat != c.user) onNew(c)
        }
        if (live) onCall(k)
    }

    private fun hasCallRecord(c: Contact, id: String) = synchronized(this) { c.msgs.any { it.call?.optString("id") == id } }

    /** "You missed a call from me" notices still to send (kept until they go out, for up to a week). */
    private val callNotes = mutableListOf<JSONObject>()

    /** Sends the waiting missed-call notices. Network: off the main thread. Safe to call any time. */
    fun sendCallNotes() {
        val m = me ?: return
        for (n in synchronized(this) { callNotes.toList() }) {
            val c = contact(n.optString("u"))
            val sent = c != null && c.status == Contact.FRIENDS &&
                runCatching { callCtl(c, JSONObject().put("t", "cnote").put("id", n.optString("id")).put("ts", n.optLong("ts"))) }.isSuccess
            if (sent || c == null || System.currentTimeMillis() - n.optLong("ts") > 7 * 86_400_000L) {
                synchronized(this) { callNotes.remove(n) }
                changed()
            }
            if (me !== m) return
        }
    }

    /** Their call to me never got answered: log it as missed unless this phone already has it. */
    private fun onCallNote(c: Contact, o: JSONObject) {
        val id = o.optString("id")
        if (id.length != 32 || c.status != Contact.FRIENDS) return
        val k = call
        if (k != null && k.id == id) { if (k.phase == Call.INCOMING) endCall(k, "missed"); return }
        if (!seenCalls.add(id) || hasCallRecord(c, id)) return
        endCall(Call(id, c.user, false, "", ""), "missed")
    }

    private fun onCallCtl(m: Me, c: Contact, o: JSONObject) {
        val id = o.optString("id")
        if (id.length != 32) return
        val k = call
        when (o.optString("t")) {
            "call" -> {
                if (!seenCalls.add(id) || hasCallRecord(c, id)) return       // redelivered (or already logged): ring only once
                val key = o.optString("k"); val room = o.optString("r")
                if (key.length != 64 || room.length != 64 || !key.all { it in "0123456789abcdef" } || !room.all { it in "0123456789abcdef" }) return
                o.optString("n").takeIf { it == NET_TOR || it == NET_DIRECT }?.let { c.net = it }
                if (c.status != Contact.FRIENDS) return                        // only friends can call
                val incoming = Call(id, c.user, false, key, room)
                // picked up late (phone was offline): it's a missed call, don't ring now
                if (System.currentTimeMillis() - o.optLong("ts", 0) > RING_MS) { endCall(incoming, "missed"); return }
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
            for (g in groups) if (g.msgs.removeAll { it.expires in 1..now }) changed = true
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
    fun queueFile(c: Contact, name: String, mime: String, data: ByteArray, thumb: ByteArray?, once: Boolean = false): Msg {
        if (data.size > MAX_FILE) throw UserError("Files can be up to ${MAX_FILE / 1024 / 1024} MB")
        val key = Crypto.randomHex(32)
        val local = Crypto.randomHex(16)
        saveBlob(local, Crypto.gcmSeal(key.unhex(), FILE_LABEL, padFile(data)))
        val f = JSONObject().put("k", key).put("n", cleanName(name)).put("m", cleanMime(mime)).put("s", data.size).put("local", local)
        // view once: no preview travels with it (it would stay on their phone)
        if (once) f.put("vo", true) else thumb?.let { f.put("th", b64(it)) }
        val msg = Msg(Crypto.randomHex(16), true, "", System.currentTimeMillis(), Msg.SENDING).also { it.file = f }
        if (c.ttl > 0) msg.expires = System.currentTimeMillis() + c.ttl * 1000L
        synchronized(this) { c.msgs.add(msg) }
        onChange()
        return msg
    }

    /**
     * Opens a view-once photo: returns its bytes and, before anyone sees it, deletes it from the phone
     * and marks it opened (the caller saves at once), so closing the app mid-view can't give a second look.
     */
    fun openViewOnce(msg: Msg): ByteArray? {
        val f = msg.file ?: return null
        if (!f.optBoolean("vo") || msg.mine || f.optBoolean("opened")) return null
        val bytes = openFile(msg) ?: return null
        synchronized(this) {
            f.optString("local").takeIf { it.isNotEmpty() }?.let { deleteBlob(it) }
            f.remove("local"); f.remove("th"); f.put("opened", true)
        }
        onChange()
        return bytes
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
                    .put("m", f.getString("m")).put("s", f.getInt("s")).put("th", f.optString("th"))
                    .apply { if (f.optBoolean("vo")) put("vo", true) })
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
            // view once: it's on its way, so my own copy goes too (I can't reopen it either)
            msg.file?.takeIf { it.optBoolean("vo") }?.let { f -> f.optString("local").takeIf { it.isNotEmpty() }?.let { deleteBlob(it) }; f.remove("local") }
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
            if (m != null) runCatching {
                gctl(g, m, JSONObject().put("t", "gdel").put("id", msg.id))   // authenticated: can't be forged
                // the old unsigned form too, so members still on 6.3 or older also remove it
                relay.gsend(g.addr, gseal(g, JSONObject().put("t", "gdel").put("u", m.user).put("id", msg.id)))
            }
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
        val b = relay.bundle(c.user) ?: throw UserError("@${c.user} needs to update $APP_NAME first")
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
            if (byToken.isEmpty()) emptyList() else fetchFrom(byToken.keys.toList(), acks, wait, withPush = true)
        for (inc in got) {
            if (me !== m) return
            try {
                if (inc.token == m.inboxToken) onRequest(m, inc.blob) else byToken[inc.token]?.let { onChat(m, it, inc.blob) }
            } catch (e: IOException) {
                drop("retry:${e.message}")
                continue // e.g. lookup failed before it was opened; it stays unacked and comes back next round
            } catch (e: Exception) {
                // A message we can't handle must never jam the mailbox: before, it threw out of poll(),
                // the app showed "Connecting…" forever and every retry hit the same message. Skip it.
                unreadable++
                drop("skipped:${e.javaClass.simpleName}")
            }
            synchronized(this) { (if (inc.token == m.inboxToken) inboxAcks else acks).add(inc.id) }
        }
        if (got.isNotEmpty()) onChange()
        if (synchronized(this) { callNotes.isNotEmpty() }) sendCallNotes()
        if (synchronized(this) { acceptDue.isNotEmpty() }) sendDueAccepts()
        for (g in synchronized(this) { groups.toList() }) runCatching { pollGroup(m, g, 0) }
        if (synchronized(this) { joinWaits.isNotEmpty() }) pollJoins(m)
        purgeExpired()
        for (msg in synchronized(this) { toFetch.toList() }) {
            if (msg.file?.let { autoFetch(it) } == false) continue   // waits for a tap
            val ok = runCatching { fetchFile(msg) }.getOrDefault(false)
            synchronized(this) { if (ok || msg.file?.has("local") == true) toFetch.remove(msg) }
        }
    }

    private val inboxAcks = mutableSetOf<String>()

    /** This phone's wake-up ping address ("" = pings off, null = not known yet). Registered for chat mailboxes only. */
    @Volatile var pushToken: String? = null
    private var pushSent = ""   // token + mailbox list last registered, so it's only sent when something changed
    private var pushSentAt = 0L

    private fun fetchFrom(tokens: List<String>, ackSet: MutableSet<String>, wait: Int, withPush: Boolean = false): List<Relay.Incoming> {
        val sent = synchronized(this) { ackSet.toList() }
        val key = "$pushToken|${tokens.hashCode()}"
        val push = pushToken?.takeIf { withPush && (key != pushSent || System.currentTimeMillis() - pushSentAt > 6 * 3_600_000L) }
        val got = relay.fetch(tokens, sent, wait, push)
        if (push != null) { pushSent = key; pushSentAt = System.currentTimeMillis() }
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
            val c = contact(user)
            // Arrived before their friend request (or that one is still being retried): keep it here, already
            // opened, and add it when the chat appears. Signal opens each message only once, so dropping it
            // would lose it for good.
            if (c == null) { keepEarly(user, inner); return }
            if (c.theirSig != p.sig) return
            // ASKED = I sent them a request too: they're writing to me while theirs crosses mine. Keep it.
            if (addIncoming(c, inner) && openChat != c.user) onNew(c)
            return
        }
        val name = inner.optString("n").take(60).ifBlank { "@$user" }
        val existing = contact(user)
        when {
            existing == null -> {
                val c = Contact(user, name, Contact.ASKING, Crypto.randomHex(), addr, p.enc, p.sig, theirIdent = p.ident)
                synchronized(this) { contacts.add(c) }
                takeEarly(c)
                changed()
                if (openChat != user) onNew(c)
            }
            existing.theirSig != p.sig -> return
            existing.status == Contact.ASKED -> { // we both asked: that's a yes
                existing.theirAddr = addr
                existing.name = name
                acceptSoon(existing, friend = true)
            }
            existing.status == Contact.ASKING -> { existing.theirAddr = addr; changed() }
            else -> { existing.theirAddr = addr; acceptSoon(existing, friend = existing.status == Contact.FRIENDS) } // they re-added us
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
                // only friends can invite, and only a limited number may wait; each secret is listed once
                if (c.status != Contact.FRIENDS || secret.length != 64 || !secret.all { it in "0123456789abcdef" }) return
                val inv = Invite(c.user, secret, inner.optString("n").take(80).ifBlank { "Group" }, inner.optBoolean("public"), inner.optString("o").take(40))
                val fresh = synchronized(this) { groups.none { it.secret == secret } && invites.none { it.secret == secret } && invites.size < MAX_INVITES }
                if (!fresh) return
                if (testAutoJoin) { synchronized(this) { invites.add(inv) }; acceptInvite(inv); return }
                synchronized(this) {
                    invites.add(inv)
                    c.msgs.add(note("@${c.user} invited you to the group \"${inv.name}\". Join or decline it at the top of your chat list."))
                    if (openChat != c.user) c.unread++
                }
                onChange()
                if (openChat != c.user) onNew(c)
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
            "cnote" -> onCallNote(c, inner)
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

    /** Adds a received message unless it's already there (redelivered). Returns whether it was new. */
    private fun addIncoming(c: Contact, inner: JSONObject, at: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        val id = inner.optString("id")
        if (id.isEmpty() || c.msgs.any { it.id == id }) return false
        c.msgs.add(incoming(id, inner, c.ttl, at))
        if (openChat != c.user) c.unread++
        true
    }

    /** Message requests from people with no chat here yet, by username (opened; kept up to [EARLY_DAYS]). */
    private val early = HashMap<String, MutableList<JSONObject>>()

    private fun keepEarly(user: String, inner: JSONObject) {
        synchronized(this) {
            if (user !in early && early.size >= EARLY_PEOPLE) return
            val list = early.getOrPut(user) { mutableListOf() }
            if (list.size < EARLY_PER_PERSON) list.add(JSONObject().put("at", System.currentTimeMillis()).put("m", inner))
        }
        changed()
    }

    /** The chat with [c] now exists: put in what they sent before it did. */
    private fun takeEarly(c: Contact) {
        val kept = synchronized(this) { early.remove(c.user) } ?: return
        val cutoff = System.currentTimeMillis() - EARLY_DAYS * 86_400_000L
        val added = kept.filter { it.optLong("at") > cutoff }.count { addIncoming(c, it.getJSONObject("m"), it.optLong("at")) }
        if (added > 0 && openChat != c.user) onNew(c)
    }

    private fun incoming(id: String, inner: JSONObject, ttl: Int, at: Long = System.currentTimeMillis()): Msg {
        val msg = Msg(id, false, inner.optString("x").take(MAX_TEXT), at, Msg.RECEIVED)
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
        if (f.optBoolean("vo")) { out.put("vo", true); out.remove("th") }   // view once: never keep a preview
        return out
    }

    /** Whether a received attachment downloads by itself; false = it waits until it's tapped (Settings > Storage). */
    @Volatile var autoFetch: (JSONObject) -> Boolean = { true }

    /** Downloads one received attachment, checks it opens, keeps the locked copy, tells the server to forget it. */
    fun fetchFile(msg: Msg): Boolean {
        val f = msg.file ?: return false
        if (f.has("local") || f.optBoolean("cleared") || f.optBoolean("opened")) return true   // opened view-once: gone for good
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
        // "MSGR02": the username is no longer written on the outside (MSGR01 files had it in plain text)
        return "MSGR02\n".toByteArray() + cipher
    }

    /** Who a backup file belongs to: the username for old MSGR01 files, "" for MSGR02 (you type it), null if it isn't one. */
    fun backupOwner(bytes: ByteArray): String? {
        val head = String(bytes.copyOf(minOf(bytes.size, 80)))
        if (head.startsWith("MSGR02\n")) return ""
        if (!head.startsWith("MSGR01\n")) return null
        val nl = head.indexOf('\n', 7)
        return if (nl > 7) head.substring(7, nl) else null
    }

    /** Restores a device backup with the account [password] ([username] is needed for MSGR02 files). Returns true on success. */
    fun restoreFull(bytes: ByteArray, password: String, username: String = ""): Boolean {
        val owner = backupOwner(bytes) ?: return false
        val user = owner.ifEmpty { username.trim().removePrefix("@").lowercase() }
        if (user.isEmpty()) return false
        val header = (if (owner.isEmpty()) "MSGR02\n" else "MSGR01\n$user\n").toByteArray()
        if (bytes.size <= header.size) return false
        // the file is locked with whichever key the account had when it was written: Argon2id first, then the old PBKDF2
        val locked = bytes.copyOfRange(header.size, bytes.size)
        val plain = listOf(Crypto.KDF_ARGON2, Crypto.KDF_PBKDF2).firstNotNullOfOrNull { kdf ->
            Crypto.gcmOpen(Crypto.passwordKeys(user, password, kdf).backup, "msgr01-device|$user", locked)
        } ?: return false
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
            .put("callNotes", JSONArray(callNotes))
            .put("joinWaits", JSONArray(joinWaits.map { JSONObject().put("l", it.link).put("n", it.name).put("o", it.owner).put("t", it.at).put("c", it.cursor) }))
            .put("invites", JSONArray(invites.map { JSONObject().put("f", it.from).put("s", it.secret).put("n", it.name).put("p", it.public).put("o", it.owner).put("t", it.at) }))
            .put("acceptDue", JSONObject(acceptDue as Map<*, *>))
            .put("early", JSONObject().apply { early.forEach { (u, l) -> put(u, JSONArray(l)) } })
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
            c.msgs.add(note("$APP_NAME now uses Signal's encryption. Your safety code with ${c.name} changed; check it again."))
        }
        backupDirty = o.optBoolean("backupDirty")
        readReceipts = o.optBoolean("readReceipts", true)
        showTyping = o.optBoolean("showTyping", true)
        showSeen = o.optBoolean("showSeen", true)
        blocked.clear(); o.optJSONArray("blocked")?.let { for (i in 0 until it.length()) blocked.add(it.getString(i)) }
        groups.clear()
        o.optJSONArray("groups")?.let { for (i in 0 until it.length()) groups.add(Group.from(it.getJSONObject(i))) }
        early.clear()
        o.optJSONObject("early")?.let { e -> for (u in e.keys()) e.optJSONArray(u)?.let { a -> early[u] = MutableList(a.length()) { a.getJSONObject(it) } } }
        acceptDue.clear()
        o.optJSONObject("acceptDue")?.let { a -> for (u in a.keys()) acceptDue[u] = a.optBoolean(u, true) }
        invites.clear()
        o.optJSONArray("invites")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { j ->
            invites.add(Invite(j.optString("f"), j.optString("s"), j.optString("n"), j.optBoolean("p"), j.optString("o"), j.optLong("t")))
        } }
        joinWaits.clear()
        o.optJSONArray("joinWaits")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { j -> joinWaits.add(JoinWait(j.optString("l"), j.optString("n"), j.optString("o"), j.optLong("t"), j.optLong("c"))) } }
        callNotes.clear()
        o.optJSONArray("callNotes")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let(callNotes::add) }
        // attachments that arrived but weren't downloaded yet
        for (c in contacts) for (msg in c.msgs) if (!msg.mine && msg.file?.has("local") == false && msg.file?.optBoolean("cleared") != true) toFetch.add(msg)
    }

    companion object {
        const val MAX_TEXT = 4000
        const val MAX_INVITES = 20           // group invitations kept waiting for an answer
        /** Tests only: join invitations at once (the app never does). */
        @Volatile internal var testAutoJoin = false
        const val MAX_AVATAR = 8000 // bytes of JPEG; fits in one sealed message
        const val MAX_FILE = 25 * 1024 * 1024
        const val TYPING_MS = 6_000L
        /** How a call I made can end without them ever picking up (their phone may not even know it rang). */
        val UNANSWERED = setOf("noanswer", "cancelled", "failed")
        const val EARLY_PEOPLE = 50        // message requests kept for people whose request hasn't arrived yet
        const val EARLY_PER_PERSON = 50
        const val EARLY_DAYS = 30L
        const val RING_MS = 75_000L         // a call rings this long (time for a sleeping phone to be woken and connect)
        const val ONLINE_MS = 5 * 60_000L   // "online" lasts this long unless refreshed (the app re-sends every 4 min)
        const val NET_TOR = "tor"
        const val NET_DIRECT = "direct"
        const val GROUP_FILE_TTL = 7 * 24 * 3600   // group blobs fan out (no per-reader delete); keep them a week
        const val MAX_JOIN_REQUESTS = 50   // waiting join requests kept per group (stops a flood)
        const val MAX_GROUP_DESC = 300
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
