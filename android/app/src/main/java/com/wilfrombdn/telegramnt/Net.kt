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
