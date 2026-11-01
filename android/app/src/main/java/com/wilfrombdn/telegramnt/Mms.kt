package com.wilfrombdn.telegramnt

import java.io.ByteArrayOutputStream

/**
 * The carrier's picture/group text format (MMS "PDUs", OMA MMS 1.2 binary encoding), only the parts
 * a texting app needs: read the "you have a picture message" notice and the downloaded message,
 * and write an outgoing message and the "got it" reply. Android's own MMS service does the network
 * part (it talks to the carrier, not Tor); this file only turns bytes into fields and back.
 * Reading is lenient: an odd header is skipped, never fatal.
 */
object Mms {
    const val SEND_REQ = 0x80
    const val NOTIFICATION_IND = 0x82
    const val NOTIFY_RESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84

    class Part(val mime: String, val data: ByteArray, val name: String? = null, val cid: String? = null, val charset: Int = UTF8) {
        val text get() = if (mime.startsWith("text/")) decode(data, charset) else null
    }

    class Pdu(
        val type: Int,
        val tid: String? = null,
        val messageId: String? = null,
        val from: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String? = null,
        val date: Long = 0,            // seconds
        val expiry: Long = 0,          // seconds, absolute
        val size: Long = 0,
        val location: String? = null,  // where to download a notified message
        val contentType: String? = null,
        val parts: List<Part> = emptyList(),
    )

    // ---------- reading ----------

    fun parse(b: ByteArray): Pdu? = runCatching { Reader(b).pdu() }.getOrNull()

    private class Reader(val b: ByteArray) {
        var p = 0
        fun has(end: Int = b.size) = p < end
        fun u8() = b[p++].toInt() and 0xFF
        fun peek() = b[p].toInt() and 0xFF

        fun uintvar(): Long {
            var v = 0L
            repeat(5) { val x = u8(); v = (v shl 7) or (x and 0x7F).toLong(); if (x and 0x80 == 0) return v }
            return v
        }

        fun valueLength(): Int { val x = u8(); return if (x == 31) uintvar().toInt() else x }

        fun textBytes(): ByteArray {
            if (p < b.size && (peek() == 0x7F || peek() == '"'.code)) p++   // quote markers
            val s = p
            while (p < b.size && b[p].toInt() != 0) p++
            return b.copyOfRange(s, p).also { if (p < b.size) p++ }
        }

        fun text() = decode(textBytes(), UTF8)

        fun longInt(): Long { var v = 0L; repeat(u8()) { v = (v shl 8) or u8().toLong() }; return v }

        fun intValue(): Long = if (peek() >= 0x80) (u8() and 0x7F).toLong() else longInt()

        /** Text, optionally with its character set in front. */
        fun encodedString(): String {
            if (peek() >= 32) return text()
            val len = valueLength()
            val end = p + len
            val cs = intValue().toInt()
            return decode(textBytes(), cs).also { p = end }
        }

        /** Skip any value we don't care about (the generic WSP rule fits every field). */
        fun skip() {
            val x = peek()
            when {
                x < 31 -> { p++; p += x }
                x == 31 -> { p++; p += uintvar().toInt() }
                x < 128 -> textBytes()
                else -> p++
            }
        }

        /** A content type with its parameters; [p] ends right after it. */
        fun contentType(): Pair<String, Map<Int, String>> {
            val x = peek()
            if (x >= 0x80) return wellKnown(u8() and 0x7F) to emptyMap()
            if (x >= 32) return text() to emptyMap()
            val len = valueLength()
            val stop = p + len
            val mime = when {
                peek() >= 0x80 -> wellKnown(u8() and 0x7F)
                peek() < 32 -> wellKnown(longInt().toInt())
                else -> text()
            }
            val params = HashMap<Int, String>()
            while (p < stop) {
                val k = peek()
                if (k >= 0x80) {
                    p++
                    when (k and 0x7F) {
                        0x01 -> params[CHARSET] = if (peek() == 0x80) { p++; "$UTF8" } else intValue().toString()
                        0x05, 0x17 -> params[NAME] = text()
                        0x06, 0x18 -> params[FILENAME] = text()
                        0x09 -> params[TYPE] = if (peek() >= 0x80) wellKnown(u8() and 0x7F) else text()
                        0x0A, 0x19 -> params[START] = text()
                        else -> skip()
                    }
                } else { textBytes(); if (p < stop) skip() }   // untyped parameter: name, value
            }
            p = stop
            return mime to params
        }

        fun pdu(): Pdu {
            var type = 0; var tid: String? = null; var mid: String? = null; var from: String? = null
            val to = ArrayList<String>(); val cc = ArrayList<String>()
            var subject: String? = null; var date = 0L; var expiry = 0L; var size = 0L; var location: String? = null
            var ct: Pair<String, Map<Int, String>>? = null
            while (has() && ct == null) {
                val f = u8()
                if (f < 0x80) { p--; textBytes(); skip(); continue }   // a header sent by name
                when (f and 0x7F) {
                    0x0C -> type = u8()
                    0x18 -> tid = text()
                    0x0B -> mid = text()
                    0x03 -> location = text()
                    0x09 -> { val len = valueLength(); val stop = p + len; if (u8() == 0x80) from = address(encodedString()); p = stop }
                    0x17 -> to += address(encodedString())
                    0x02 -> cc += address(encodedString())
                    0x16 -> subject = encodedString()
                    0x05 -> date = longInt()
                    0x08 -> { val len = valueLength(); val stop = p + len; val relative = u8() == 0x81; val v = longInt(); expiry = if (relative) System.currentTimeMillis() / 1000 + v else v; p = stop }
                    0x0E -> size = longInt()
                    0x04 -> ct = contentType()
                    else -> skip()
                }
            }
            val parts = when {
                ct == null || !has() -> emptyList()
                ct.first.contains("multipart") -> multipart()
                else -> listOf(Part(ct.first, b.copyOfRange(p, b.size), ct.second[NAME], null, ct.second[CHARSET]?.toIntOrNull() ?: UTF8))
            }
            return Pdu(type, tid, mid, from, to, cc, subject, date, expiry, size, location, ct?.first, parts)
        }

        fun multipart(): List<Part> {
            val out = ArrayList<Part>()
            val n = uintvar().toInt()
            repeat(n) {
                if (!has()) return out
                val headLen = uintvar().toInt(); val dataLen = uintvar().toInt()
                val headEnd = p + headLen
                val (mime, params) = contentType()
                var cid: String? = null; var loc: String? = null
                while (p < headEnd) {
                    val f = peek()
                    if (f >= 0x80) {
                        p++
                        when (f and 0x7F) {
                            0x0E -> loc = text()
                            0x40 -> cid = text().removePrefix("<").removeSuffix(">")
                            else -> skip()
                        }
                    } else { textBytes(); if (p < headEnd) textBytes() }
                }
                p = headEnd
                val end = minOf(b.size, p + dataLen)
                out += Part(mime, b.copyOfRange(p, end), params[NAME] ?: params[FILENAME] ?: loc, cid, params[CHARSET]?.toIntOrNull() ?: UTF8)
                p = end
            }
            return out
        }
    }

    /** "+15551234567/TYPE=PLMN" → "+15551234567". */
    private fun address(s: String) = s.substringBefore("/TYPE=").trim()

    // ---------- writing ----------

    private class Writer {
        val o = ByteArrayOutputStream()
        fun u8(x: Int) = apply { o.write(x) }
        fun bytes(x: ByteArray) = apply { o.write(x) }
        fun short(x: Int) = u8(x or 0x80)
        fun uintvar(v: Long) = apply {
            var n = v; val tmp = ArrayList<Int>()
            do { tmp += (n and 0x7F).toInt(); n = n shr 7 } while (n > 0)
            for (i in tmp.indices.reversed()) u8(tmp[i] or if (i > 0) 0x80 else 0)
        }
        fun length(n: Int) = if (n < 31) u8(n) else u8(31).uintvar(n.toLong())
        fun text(s: String) = apply { val t = s.toByteArray(); if (t.isNotEmpty() && (t[0].toInt() and 0xFF) >= 0x80) u8(0x7F); bytes(t); u8(0) }
        fun quoted(s: String) = apply { u8('"'.code); bytes(s.toByteArray()); u8(0) }
        /** Plain text if ASCII, else UTF-8 marked as such. */
        fun encoded(s: String) = apply {
            if (s.all { it.code in 32..126 }) text(s)
            else { val t = Writer().short(UTF8).text(s).o.toByteArray(); length(t.size); bytes(t) }
        }
        fun header(field: Int) = short(field)
        fun toBytes(): ByteArray = o.toByteArray()
    }

    /** One outgoing message to [to] (one number = picture text, several = group text). */
    fun sendReq(to: List<String>, parts: List<Part>, subject: String? = null, tid: String = "T" + java.lang.Long.toHexString(System.nanoTime())): ByteArray {
        val w = Writer()
        w.header(0x0C).short(SEND_REQ and 0x7F)
        w.header(0x18).text(tid)
        w.header(0x0D).short(0x12)                       // MMS 1.2
        w.header(0x09).u8(1).short(0x01)                 // From: filled in by the carrier
        for (a in to) w.header(0x17).encoded(if (a.contains('@')) a else "${a.filter { it.isDigit() || it == '+' }}/TYPE=PLMN")
        subject?.takeIf { it.isNotBlank() }?.let { w.header(0x16).encoded(it) }
        w.header(0x0A).short(0x00)                       // personal
        w.header(0x06).short(0x01)                       // no delivery report
        w.header(0x10).short(0x01)                       // no read report
        // body: a SMIL layout first (some phones only show parts it lists), then the parts
        val all = listOf(Part("application/smil", smil(parts).toByteArray(), "smil.xml", "smil")) + parts
        val ct = Writer().short(0x33).header(0x09).text("application/smil").header(0x0A).text("<smil>").toBytes()
        w.header(0x04).length(ct.size).bytes(ct)
        w.uintvar(all.size.toLong())
        all.forEachIndexed { i, part ->
            val name = part.name ?: "part$i"
            val h = Writer()
            val type = Writer()
            WELL_KNOWN.indexOf(part.mime).let { if (it >= 0) type.short(it) else type.text(part.mime) }
            if (part.mime.startsWith("text/") || part.mime == "application/smil") type.header(0x01).short(UTF8)
            type.header(0x05).text(name)
            val tb = type.toBytes()
            h.length(tb.size).bytes(tb)
            h.header(0x40).quoted("<${part.cid ?: name}>")
            h.header(0x0E).text(name)
            val hb = h.toBytes()
            w.uintvar(hb.size.toLong()).uintvar(part.data.size.toLong()).bytes(hb).bytes(part.data)
        }
        return w.toBytes()
    }

    /** "Got it" for a downloaded message, so the carrier stops re-sending the notice. */
    fun notifyResp(tid: String): ByteArray = Writer()
        .header(0x0C).short(NOTIFY_RESP_IND and 0x7F)
        .header(0x18).text(tid)
        .header(0x0D).short(0x12)
        .header(0x15).short(0x01)                        // status: retrieved
        .header(0x11).short(0x01)                        // no delivery reports for the sender
        .toBytes()

    private fun smil(parts: List<Part>): String {
        val items = parts.joinToString("") { p ->
            val n = p.name ?: ""
            when {
                p.mime.startsWith("image/") -> "<img src=\"$n\" region=\"Image\"/>"
                p.mime.startsWith("video/") -> "<video src=\"$n\" region=\"Image\"/>"
                p.mime.startsWith("audio/") -> "<audio src=\"$n\"/>"
                p.mime.startsWith("text/") -> "<text src=\"$n\" region=\"Text\"/>"
                else -> "<ref src=\"$n\"/>"
            }
        }
        return "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body><par dur=\"5000ms\">$items</par></body></smil>"
    }

    // ---------- shared ----------

    const val UTF8 = 106
    private const val CHARSET = 1; private const val NAME = 2; private const val FILENAME = 3; private const val TYPE = 4; private const val START = 5

    fun decode(b: ByteArray, charset: Int): String = String(b, when (charset) {
        3 -> Charsets.US_ASCII
        4 -> Charsets.ISO_8859_1
        1000, 1015 -> Charsets.UTF_16
        1013 -> Charsets.UTF_16BE
        1014 -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    })

    private fun wellKnown(i: Int) = WELL_KNOWN.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

    /** WAP's numbered content types (index = number). */
    private val WELL_KNOWN = listOf(
        "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar", "text/x-vCard",
        "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event", "multipart/*", "multipart/mixed",
        "multipart/form-data", "multipart/byterantes", "multipart/alternative", "application/*", "application/java-vm",
        "application/x-www-form-urlencoded", "application/x-hdmlc", "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc", "application/vnd.wap.uaprof", "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert", "application/x-x509-user-cert",
        "image/*", "image/gif", "image/jpeg", "image/tiff", "image/png", "image/vnd.wap.wbmp",
        "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed", "application/vnd.wap.multipart.form-data",
        "application/vnd.wap.multipart.byteranges", "application/vnd.wap.multipart.alternative", "application/xml", "text/xml",
        "application/vnd.wap.wbxml", "application/x-x968-cross-cert", "application/x-x968-ca-cert", "application/x-x968-user-cert",
        "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl", "application/vnd.wap.slc", "text/vnd.wap.co",
        "application/vnd.wap.coc", "application/vnd.wap.multipart.related", "application/vnd.wap.sia",
        "text/vnd.wap.connectivity-xml", "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate", "application/vnd.wap.cert-response",
        "application/xhtml+xml", "application/wml+xml", "text/css", "application/vnd.wap.mms-message",
    )
}
