package com.wilfrombdn.telegramnt

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.net.URL
import java.util.Base64

/**
 * Talks to the MSNGR server. Messages only ever travel sealed.
 *
 * All traffic goes through [Net], which connects either directly (tests, and the future "Direct" path)
 * or through Tor's SOCKS5 proxy to a .onion address. [socks] returns the live Tor SOCKS port, or null
 * for a direct connection; for a .onion server it must be non-null or the request fails (fail-closed —
 * we never silently fall back to a non-Tor route).
 */
class Relay(val server: String, private val socks: () -> Int? = { null }) {
    class Incoming(val token: String, val id: String, val blob: ByteArray)
    class Profile(val user: String, val enc: String, val sig: String, val inbox: String, val ident: String)

    @Volatile private var live: Socket? = null

    private fun route(): Net.Route {
        val u = URL(server)
        val tls = u.protocol == "https"
        val port = if (u.port != -1) u.port else if (tls) 443 else 80
        // a .onion can only be reached through Tor; refuse rather than leak the request to a DNS lookup
        val sp = if (u.host.endsWith(".onion")) (socks() ?: throw IOException("Tor is not ready yet")) else null
        return Net.Route(u.host, port, tls, sp)
    }

    /** Breaks off a waiting fetch so the next one can include new mailboxes. */
    fun kick() {
        runCatching { live?.close() }
    }

    class GroupMsg(val seq: Long, val blob: ByteArray)

    /** Posts a sealed blob to a group mailbox (kept until TTL; every member reads it). */
    fun gsend(address: String, blob: ByteArray) {
        post("/gsend", JSONObject().put("to", address).put("blob", Base64.getEncoder().encodeToString(blob)), 20)
    }

    /** Group messages with seq greater than [since]. */
    fun gfetch(address: String, since: Long, waitSec: Int): List<GroupMsg> {
        val body = JSONObject().put("addr", address).put("since", since).put("wait", waitSec)
        val arr = post("/gfetch", body, waitSec + 20, track = true).optJSONArray("msgs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            try { GroupMsg(m.getLong("seq"), Base64.getDecoder().decode(m.getString("blob"))) }
            catch (e: IllegalArgumentException) { null }
        }
    }

    fun send(address: String, blob: ByteArray) {
        post("/send", JSONObject().put("to", address).put("blob", b64(blob)), 20)
    }

    /** Returns waiting messages for these mailboxes; also creates them on the server. */
    fun fetch(tokens: List<String>, acks: Collection<String>, waitSec: Int): List<Incoming> {
        val body = JSONObject().put("tokens", JSONArray(tokens)).put("ack", JSONArray(acks)).put("wait", waitSec)
        val arr = post("/fetch", body, waitSec + 20, track = waitSec > 0).optJSONArray("msgs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val box = m.optInt("box", -1)
            if (box !in tokens.indices) return@mapNotNull null
            try {
                Incoming(tokens[box], m.getString("id"), Base64.getDecoder().decode(m.getString("blob")))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /** Returns the device token. */
    fun signup(user: String, auth: ByteArray, enc: ByteArray, sig: ByteArray, inboxToken: String, backup: ByteArray): String =
        post("/signup", JSONObject().put("user", user).put("auth", auth.hex()).put("enc", b64(enc)).put("sig", b64(sig))
            .put("inboxToken", inboxToken).put("backup", b64(backup)), 20).getString("device")

    /** Returns (device token, locked backup). */
    fun login(user: String, auth: ByteArray): Pair<String, ByteArray> {
        val r = post("/login", JSONObject().put("user", user).put("auth", auth.hex()), 20)
        return r.getString("device") to Base64.getDecoder().decode(r.getString("backup"))
    }

    /** False when this phone has been signed out by a login somewhere else. */
    fun sessionOk(user: String, device: String): Boolean = try {
        post("/session", JSONObject().put("user", user).put("device", device), 20)
        true
    } catch (e: RelayError) {
        if (e.code == 401) false else throw e
    }

    fun saveBackup(user: String, device: String, backup: ByteArray) {
        post("/backup", JSONObject().put("user", user).put("device", device).put("backup", b64(backup)), 20)
    }

    fun lookup(user: String): Profile? = try {
        val r = post("/lookup", JSONObject().put("user", user), 20)
        Profile(r.getString("user"), r.getString("enc"), r.getString("sig"), r.getString("inbox"), r.optString("ident"))
    } catch (e: RelayError) {
        if (e.code == 404) null else throw e
    }

    /** Publishes my Signal keys; returns how many one-time keys the server holds. */
    fun publishKeys(user: String, device: String, keys: JSONObject, replace: Boolean): Int =
        post("/keys", JSONObject(keys.toString()).put("user", user).put("device", device).put("replace", replace), 20).getInt("count")

    fun keyCount(user: String, device: String): Int =
        post("/keycount", JSONObject().put("user", user).put("device", device), 20).getInt("count")

    /** What's needed to start a Signal session with @user (or null if they have no keys yet). */
    fun bundle(user: String): JSONObject? = try {
        post("/bundle", JSONObject().put("user", user), 20)
    } catch (e: RelayError) {
        if (e.code == 404) null else throw e
    }

    fun deleteAccount(user: String, auth: ByteArray) {
        post("/delete", JSONObject().put("user", user).put("auth", auth.hex()), 20)
    }

    /** Uploads an already-encrypted attachment; returns its id. */
    /** [ttlSeconds] asks the server to drop the blob after that long (group files live a short time). */
    fun upload(blob: ByteArray, ttlSeconds: Int = 0): String {
        val headers = if (ttlSeconds > 0) mapOf("X-TTL" to ttlSeconds.toString()) else emptyMap()
        val r = Net.request(route(), "POST", "/upload", "application/octet-stream", blob, headers, readMs = 120_000)
        val text = String(r.body).ifEmpty { "{}" }
        if (r.code != 200) throw RelayError(r.code, runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "")
        return JSONObject(text).getString("id")
    }

    fun download(id: String): ByteArray {
        val r = Net.request(route(), "POST", "/download", "application/json",
            JSONObject().put("id", id).toString().toByteArray(), readMs = 120_000)
        if (r.code != 200) throw RelayError(r.code, "")
        return r.body
    }

    fun doneWith(id: String) {
        post("/done", JSONObject().put("id", id), 20)
    }

    fun ping(): Boolean = try {
        val r = Net.request(route(), "GET", "/", "text/plain", ByteArray(0), readMs = 10_000, connectMs = 10_000)
        r.code == 200 && String(r.body).contains("telegramnt")
    } catch (e: Exception) {
        false
    }

    private fun post(path: String, body: JSONObject, readTimeoutSec: Int, track: Boolean = false): JSONObject {
        val r = Net.request(route(), "POST", path, "application/json", body.toString().toByteArray(),
            readMs = readTimeoutSec * 1000, onSocket = { if (track) live = it })
        try {
            val text = String(r.body).ifEmpty { "{}" }
            if (r.code != 200) throw RelayError(r.code, runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "")
            return JSONObject(text)
        } finally {
            if (track) live = null
        }
    }

    /** [reason] is the server's plain-words error, e.g. "username taken". */
    class RelayError(val code: Int, val reason: String) : IOException("server said $code $reason")

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    companion object {
        /** "192.168.1.98:8080" -> "http://192.168.1.98:8080" */
        fun normalize(s: String): String {
            var t = s.trim().trimEnd('/')
            if (t.isEmpty()) return t
            if (!t.startsWith("http://") && !t.startsWith("https://")) t = "http://$t"
            return t
        }
    }
}
