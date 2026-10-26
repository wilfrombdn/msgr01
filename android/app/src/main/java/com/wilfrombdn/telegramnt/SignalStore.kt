package com.wilfrombdn.telegramnt

import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID

/**
 * Everything Signal's library needs to remember (sessions, prekeys, sender keys), kept as
 * serialized records so it can be saved inside the app's locked store file.
 */
class SignalStore(val identity: IdentityKeyPair, val regId: Int) : SignalProtocolStore {
    private val identities = HashMap<String, ByteArray>()
    private val prekeys = HashMap<Int, ByteArray>()
    private val signed = HashMap<Int, ByteArray>()
    private val kyber = HashMap<Int, ByteArray>()
    private val usedKyber = HashSet<String>()
    private val sessions = HashMap<String, ByteArray>()
    private val senderKeys = HashMap<String, ByteArray>()
    private var nextId = 1

    private fun key(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    // ---- identity ----
    override fun getIdentityKeyPair() = identity
    override fun getLocalRegistrationId() = regId

    @Synchronized
    override fun saveIdentity(a: SignalProtocolAddress, k: IdentityKey): IdentityKeyStore.IdentityChange {
        val old = identities.put(a.name, k.serialize())
        return if (old == null || old.contentEquals(k.serialize())) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    /** The server won't let anyone's identity change, so a different key here means something is wrong. */
    @Synchronized
    override fun isTrustedIdentity(a: SignalProtocolAddress, k: IdentityKey, d: IdentityKeyStore.Direction) =
        identities[a.name]?.contentEquals(k.serialize()) ?: true

    @Synchronized
    override fun getIdentity(a: SignalProtocolAddress) = identities[a.name]?.let { IdentityKey(it) }

    // ---- one-time prekeys ----
    @Synchronized override fun loadPreKey(id: Int) = PreKeyRecord(prekeys[id] ?: throw InvalidKeyIdException("no prekey $id"))
    @Synchronized override fun storePreKey(id: Int, r: PreKeyRecord) { prekeys[id] = r.serialize() }
    @Synchronized override fun containsPreKey(id: Int) = id in prekeys
    @Synchronized override fun removePreKey(id: Int) { prekeys.remove(id) }

    // ---- signed prekeys ----
    @Synchronized override fun loadSignedPreKey(id: Int) = SignedPreKeyRecord(signed[id] ?: throw InvalidKeyIdException("no signed prekey $id"))
    @Synchronized override fun loadSignedPreKeys() = signed.values.map { SignedPreKeyRecord(it) }
    @Synchronized override fun storeSignedPreKey(id: Int, r: SignedPreKeyRecord) { signed[id] = r.serialize() }
    @Synchronized override fun containsSignedPreKey(id: Int) = id in signed
    @Synchronized override fun removeSignedPreKey(id: Int) { signed.remove(id) }

    // ---- Kyber (post-quantum) prekeys: one long-lived "last resort" key ----
    @Synchronized override fun loadKyberPreKey(id: Int) = KyberPreKeyRecord(kyber[id] ?: throw InvalidKeyIdException("no kyber prekey $id"))
    @Synchronized override fun loadKyberPreKeys() = kyber.values.map { KyberPreKeyRecord(it) }
    @Synchronized override fun storeKyberPreKey(id: Int, r: KyberPreKeyRecord) { kyber[id] = r.serialize() }
    @Synchronized override fun containsKyberPreKey(id: Int) = id in kyber

    /** A last-resort key may be used many times, but never twice with the same base key (that'd be a replay). */
    @Synchronized
    override fun markKyberPreKeyUsed(id: Int, signedId: Int, base: ECPublicKey) {
        if (!usedKyber.add("$id:$signedId:" + base.serialize().hex())) throw ReusedBaseKeyException("replayed first message")
    }

    // ---- sessions ----
    @Synchronized override fun loadSession(a: SignalProtocolAddress) = sessions[key(a)]?.let { SessionRecord(it) } ?: SessionRecord()

    @Synchronized
    override fun loadExistingSessions(list: List<SignalProtocolAddress>) =
        list.map { a -> sessions[key(a)]?.let { SessionRecord(it) } ?: throw NoSessionException("no session with ${a.name}") }

    @Synchronized override fun getSubDeviceSessions(name: String) = sessions.keys.filter { it.startsWith("$name.") && !it.endsWith(".1") }.map { it.substringAfterLast('.').toInt() }
    @Synchronized override fun storeSession(a: SignalProtocolAddress, r: SessionRecord) { sessions[key(a)] = r.serialize() }
    @Synchronized override fun containsSession(a: SignalProtocolAddress) = key(a) in sessions
    @Synchronized override fun deleteSession(a: SignalProtocolAddress) { sessions.remove(key(a)) }
    @Synchronized override fun deleteAllSessions(name: String) { sessions.keys.removeAll { it.startsWith("$name.") } }

    // ---- group sender keys ----
    @Synchronized override fun storeSenderKey(a: SignalProtocolAddress, id: UUID, r: SenderKeyRecord) { senderKeys["${key(a)}|$id"] = r.serialize() }
    @Synchronized override fun loadSenderKey(a: SignalProtocolAddress, id: UUID) = senderKeys["${key(a)}|$id"]?.let { SenderKeyRecord(it) }

    // ---- making keys to publish ----

    /** New signed + Kyber prekeys and [count] one-time prekeys; returns what goes to the server. */
    @Synchronized
    fun freshKeys(count: Int, rotateSigned: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val priv = identity.privateKey
        if (rotateSigned || signed.isEmpty()) {
            val kp = ECKeyPair.generate()
            val id = nextId++
            signed[id] = SignedPreKeyRecord(id, now, kp, priv.calculateSignature(kp.publicKey.serialize())).serialize()
            // keep the previous one a while for messages already on their way
            signed.keys.sorted().dropLast(2).forEach { signed.remove(it) }
        }
        if (kyber.isEmpty()) {
            val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val id = nextId++
            kyber[id] = KyberPreKeyRecord(id, now, kp, priv.calculateSignature(kp.publicKey.serialize())).serialize()
        }
        val onetime = JSONArray()
        repeat(count) {
            val id = nextId++
            val r = PreKeyRecord(id, ECKeyPair.generate())
            prekeys[id] = r.serialize()
            onetime.put(JSONObject().put("id", id).put("pub", b64(r.keyPair.publicKey.serialize())))
        }
        val s = SignedPreKeyRecord(signed[signed.keys.max()]!!)
        val k = KyberPreKeyRecord(kyber[kyber.keys.max()]!!)
        return JSONObject()
            .put("ident", b64(identity.publicKey.serialize())).put("reg", regId)
            .put("spk", JSONObject().put("id", s.id).put("pub", b64(s.keyPair.publicKey.serialize())).put("sig", b64(s.signature)))
            .put("kpk", JSONObject().put("id", k.id).put("pub", b64(k.keyPair.publicKey.serialize())).put("sig", b64(k.signature)))
            .put("otpk", onetime)
    }

    // ---- saving ----

    @Synchronized
    fun toJson(): JSONObject {
        fun <K> map(m: Map<K, ByteArray>) = JSONObject().apply { for ((k, v) in m) put(k.toString(), b64(v)) }
        return JSONObject().put("identity", b64(identity.serialize())).put("reg", regId).put("next", nextId)
            .put("identities", map(identities)).put("prekeys", map(prekeys)).put("signed", map(signed))
            .put("kyber", map(kyber)).put("sessions", map(sessions)).put("senderKeys", map(senderKeys))
            .put("usedKyber", JSONArray(usedKyber.toList()))
    }

    companion object {
        fun create() = SignalStore(IdentityKeyPair.generate(), 1 + java.security.SecureRandom().nextInt(16000))

        fun fromJson(o: JSONObject): SignalStore {
            val s = SignalStore(IdentityKeyPair(unb64(o.getString("identity"))), o.getInt("reg"))
            s.nextId = o.optInt("next", 1)
            fun load(name: String, into: MutableMap<String, ByteArray>) {
                val m = o.optJSONObject(name) ?: return
                for (k in m.keys()) into[k] = unb64(m.getString(k))
            }
            fun loadInt(name: String, into: MutableMap<Int, ByteArray>) {
                val m = o.optJSONObject(name) ?: return
                for (k in m.keys()) into[k.toInt()] = unb64(m.getString(k))
            }
            load("identities", s.identities); loadInt("prekeys", s.prekeys); loadInt("signed", s.signed)
            loadInt("kyber", s.kyber); load("sessions", s.sessions); load("senderKeys", s.senderKeys)
            o.optJSONArray("usedKyber")?.let { for (i in 0 until it.length()) s.usedKyber.add(it.getString(i)) }
            return s
        }

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)
    }
}
