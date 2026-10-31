package com.wilfrombdn.telegramnt

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * A tiny HTTP/1.1 client over a raw socket, so the exact same code can reach the relay two ways:
 *   - directly (future "Direct" path, over TLS), or
 *   - through Tor's SOCKS5 proxy to a .onion address.
 *
 * Why not HttpURLConnection: when it's pointed at a SOCKS proxy it resolves the hostname locally
 * first, which fails for .onion (there's no DNS for it). A raw Socket connected to an *unresolved*
 * address hands the name to the SOCKS5 proxy to resolve — which is exactly how a .onion is reached.
 * The request/response shape here is trivial (small JSON, or one binary blob) and the server always
 * sends Content-Length, so a hand-rolled client is simple and behaves identically on JVM and Android.
 */
object Net {
    class Response(val code: Int, val body: ByteArray)
    class Route(val host: String, val port: Int, val tls: Boolean, val socksPort: Int?)

    /** Opens a socket to [host]:[port], directly or (when [socksPort] != null) through Tor's SOCKS5. */
    private fun connect(host: String, port: Int, tls: Boolean, socksPort: Int?, connectMs: Int): Socket {
        val raw = if (socksPort == null) {
            Socket().apply { connect(InetSocketAddress(host, port), connectMs) }
        } else {
            // createUnresolved => the SOCKS5 proxy (Tor) resolves the name; required for .onion.
            // Building a fresh onion rendezvous circuit can take far longer than a normal connect
            // (tens of seconds, especially the first time), so give SOCKS connects a generous floor —
            // too short a timeout here looks exactly like "messages never arrive".
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .apply { connect(InetSocketAddress.createUnresolved(host, port), maxOf(connectMs, 90_000)) }
        }
        return if (tls) (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, port, true).apply { (this as javax.net.ssl.SSLSocket).startHandshake() }
        else raw
    }

    /**
     * Sends one request and reads the full response. [onSocket] receives the live socket so a caller
     * can close it to break off a long-poll (see Relay.kick).
     */
    fun request(
        route: Route, method: String, path: String, contentType: String, body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(), connectMs: Int = 15_000, readMs: Int,
        onSocket: (Socket) -> Unit = {},
    ): Response {
        val s = connect(route.host, route.port, route.tls, route.socksPort, connectMs)
        onSocket(s)
        try {
            s.soTimeout = readMs
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\n")
            head.append("Host: ${route.host}\r\n")
            head.append("Content-Type: $contentType\r\n")
            head.append("Content-Length: ${body.size}\r\n")
            for ((k, v) in extraHeaders) head.append("$k: $v\r\n")
            head.append("Connection: close\r\n\r\n")   // one request per socket: read to Content-Length / EOF
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()

            val inp = s.getInputStream()
            val status = readLine(inp) ?: throw IOException("no response")
            val parts = status.split(" ")
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("bad status line")
            val code = parts[1].toIntOrNull() ?: throw IOException("bad status code")
            var length = -1
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0 && line.substring(0, i).trim().equals("Content-Length", true))
                    length = line.substring(i + 1).trim().toIntOrNull() ?: -1
            }
            val payload = if (length >= 0) readExactly(inp, length) else inp.readBytes()
            return Response(code, payload)
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * GETs [path] and streams the body into [out] (handles Content-Length or chunked replies), calling
     * [progress] with (bytes so far, total or -1). Used for the app's own updates from GitHub Pages.
     */
    fun download(route: Route, path: String, out: java.io.OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val s = connect(route.host, route.port, route.tls, route.socksPort, 15_000)
        try {
            s.soTimeout = 60_000
            s.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: ${route.host}\r\nUser-Agent: quipu\r\nAccept-Encoding: identity\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val inp = java.io.BufferedInputStream(s.getInputStream(), 64 * 1024)
            val code = readLine(inp)?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status")
            var length = -1L
            var chunked = false
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':'); if (i <= 0) continue
                val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim()
                if (k.equals("Content-Length", true)) length = v.toLongOrNull() ?: -1
                if (k.equals("Transfer-Encoding", true) && v.contains("chunked", true)) chunked = true
            }
            if (code != 200) throw IOException("HTTP $code")
            val buf = ByteArray(64 * 1024)
            var got = 0L
            fun copy(n: Long) {   // n < 0 = until EOF
                var left = n
                while (n < 0 || left > 0) {
                    val r = inp.read(buf, 0, if (n < 0) buf.size else minOf(buf.size.toLong(), left).toInt())
                    if (r == -1) { if (n < 0) return else throw IOException("connection dropped") }
                    out.write(buf, 0, r); got += r; left -= r
                    progress(got, length)
                }
            }
            if (chunked) while (true) {
                val size = readLine(inp)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw IOException("bad chunk")
                if (size == 0L) break
                copy(size); readLine(inp)
            } else copy(length)
        } finally {
            runCatching { s.close() }
        }
    }

    class Peek(val code: Int, val location: String?, val body: ByteArray)

    /**
     * GETs a web page, keeping at most [max] bytes of it (enough to find its title). Used for link
     * previews, always through Tor. Chunk markers may be left in the body; that's fine for reading tags.
     */
    fun peek(route: Route, path: String, max: Int): Peek {
        val s = connect(route.host, route.port, route.tls, route.socksPort, 15_000)
        try {
            s.soTimeout = 30_000
            s.getOutputStream().apply {
                write(("GET $path HTTP/1.1\r\nHost: ${route.host}\r\nUser-Agent: Mozilla/5.0 (compatible; link preview)\r\n" +
                    "Accept: text/html\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val inp = java.io.BufferedInputStream(s.getInputStream(), 64 * 1024)
            val code = readLine(inp)?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status")
            var location: String? = null
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Location:", true)) location = line.substringAfter(':').trim()
            }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < max) { val r = inp.read(buf); if (r == -1) break; out.write(buf, 0, r) }
            return Peek(code, location, out.toByteArray())
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * Opens a long-lived two-way stream (the voice-call room): sends a POST with [headers], checks for
     * 200, and hands back the socket positioned right after the response headers.
     */
    fun openStream(route: Route, path: String, headers: Map<String, String>, readMs: Int): Socket {
        val s = connect(route.host, route.port, route.tls, route.socksPort, 15_000)
        try {
            s.soTimeout = readMs
            val head = StringBuilder("POST $path HTTP/1.1\r\nHost: ${route.host}\r\nContent-Length: 0\r\n")
            for ((k, v) in headers) head.append("$k: $v\r\n")
            s.getOutputStream().apply { write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            val inp = s.getInputStream()
            val code = readLine(inp)?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status")
            while (true) { val line = readLine(inp) ?: break; if (line.isEmpty()) break }
            if (code != 200) throw IOException("HTTP $code")
            return s
        } catch (e: Exception) {
            runCatching { s.close() }; throw e
        }
    }

    /** Reads one CRLF- (or LF-) terminated line as ASCII; null at EOF before any byte. */
    private fun readLine(inp: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().removeSuffix("\r")
            buf.append(b.toChar())
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(out, off, n - off)
            if (r == -1) throw IOException("short body: got $off of $n")
            off += r
        }
        return out
    }
}
