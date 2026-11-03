package com.wilfrombdn.telegramnt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/** A dropped update download carries on from where it stopped instead of starting over. */
class UpdateResumeTest {
    private val body = Random(1).nextBytes(300_000)

    /** Tiny HTTP server: [cutAt] = drop the first reply after that many bytes; [ranges] = honour Range. */
    private fun server(cutAt: Int, ranges: Boolean, seenFrom: MutableList<Long>): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            var first = true
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                s.use {
                    val head = generateSequence { readLine(s.getInputStream()) }.takeWhile { it.isNotEmpty() }.toList()
                    val from = head.firstOrNull { it.startsWith("Range:", true) }?.substringAfter("bytes=")?.substringBefore('-')?.toLong() ?: 0L
                    seenFrom += from
                    val out = s.getOutputStream()
                    val start = if (ranges) from.toInt() else 0
                    val status = if (ranges && from > 0) "206 Partial Content\r\nContent-Range: bytes $from-${body.size - 1}/${body.size}" else "200 OK"
                    out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size - start}\r\n\r\n".toByteArray())
                    if (first) { first = false; out.write(body, start, cutAt); out.flush() }   // then hang up mid-file
                    else out.write(body, start, body.size - start)
                }
            }
        }
        return ss.localPort
    }

    private fun readLine(i: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) { val c = i.read(); if (c == -1 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
        return sb.toString()
    }

    private fun fetch(port: Int, f: File, last: LongArray) =
        Net.download(Net.Route("127.0.0.1", port, false, null), "/x.apk", f.length(), { at -> FileOutputStream(f, at > 0) }) { got, total ->
            last[0] = got; last[1] = total
        }

    @Test fun continuesWhereItStopped() {
        val seen = mutableListOf<Long>()
        val port = server(120_000, ranges = true, seenFrom = seen)
        val f = File.createTempFile("upd", ".apk").apply { delete() }
        val last = LongArray(2)
        try { fetch(port, f, last); error("should have dropped") } catch (_: IOException) {}
        assertEquals(120_000L, f.length())
        fetch(port, f, last)
        assertEquals(listOf(0L, 120_000L), seen)
        assertArrayEquals(body, f.readBytes())
        assertEquals(body.size.toLong(), last[0]); assertEquals(body.size.toLong(), last[1])   // progress counts the whole file
    }

    @Test fun startsOverWhenServerIgnoresRange() {
        val seen = mutableListOf<Long>()
        val port = server(50_000, ranges = false, seenFrom = seen)
        val f = File.createTempFile("upd", ".apk").apply { delete() }
        val last = LongArray(2)
        try { fetch(port, f, last); error("should have dropped") } catch (_: IOException) {}
        fetch(port, f, last)
        assertTrue(seen[1] > 0)
        assertArrayEquals(body, f.readBytes())   // no doubled-up start
    }
}
