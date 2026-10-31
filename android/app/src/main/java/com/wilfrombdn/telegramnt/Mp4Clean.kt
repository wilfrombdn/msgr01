package com.wilfrombdn.telegramnt

/**
 * Removes hidden details from an MP4/MOV video before it's sent: where it was filmed (GPS), the phone
 * model and other tags (the "udta", "meta" and "uuid" boxes), and when it was recorded (the times in the
 * movie, track and media headers).
 *
 * Nothing is cut out: each tag box is overwritten in place as an empty "free" box of the same size, so
 * every offset in the file stays right and the video plays exactly as before. If the file can't be read
 * as MP4, it's returned unchanged.
 */
object Mp4Clean {
    private val CONTAINERS = setOf("moov", "trak", "mdia")
    private val TAGS = setOf("udta", "meta", "uuid")
    private val HEADERS = setOf("mvhd", "tkhd", "mdhd")

    fun strip(src: ByteArray): ByteArray {
        val b = src.copyOf()
        return try { walk(b, 0, b.size); b } catch (e: Exception) { src }
    }

    private fun walk(b: ByteArray, from: Int, to: Int) {
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L) { size = u64(b, at + 8); head = 16 } else if (size == 0L) size = (to - at).toLong()
            if (size < head || at + size > to) throw IllegalArgumentException("bad box")
            val end = (at + size).toInt()
            when {
                type in TAGS -> blank(b, at, end)
                type in HEADERS -> clearTimes(b, at + head, end)
                type in CONTAINERS -> walk(b, at + head, end)
            }
            at = end
        }
    }

    /** Turns a box into an empty "free" box of the same size (the size field and any 64-bit size stay). */
    private fun blank(b: ByteArray, at: Int, end: Int) {
        "free".toByteArray(Charsets.ISO_8859_1).copyInto(b, at + 4)
        val head = if (u32(b, at) == 1L) 16 else 8
        java.util.Arrays.fill(b, at + head, end, 0)
    }

    /** Full box: version (1 byte) + flags (3), then creation and modification time (4 bytes each, or 8 in version 1). */
    private fun clearTimes(b: ByteArray, body: Int, end: Int) {
        val n = if (b[body].toInt() == 1) 16 else 8
        if (body + 4 + n <= end) java.util.Arrays.fill(b, body + 4, body + 4 + n, 0)
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)
}
