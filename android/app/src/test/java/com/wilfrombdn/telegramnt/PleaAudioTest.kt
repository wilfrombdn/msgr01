package com.wilfrombdn.telegramnt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Mixes the plea and the end's sounds on their timeline into build/shots/plea-audio.wav, so they can be listened to without a phone. */
class PleaAudioTest {
    @Test fun pleaAudio() {
        val rate = DeadLetterSfx.SAMPLE_RATE
        val w = FinaleWorld(); w.debugEnter(FinaleWorld.Stage.BROADCAST)
        val total = (rate * (FinaleWorld.PLEA_S + 23f)).toInt(); val mix = FloatArray(total)
        var t = 0f; var loud = 0f
        while (t < FinaleWorld.PLEA_S + 22f) {
            w.update(0.02f); t += 0.02f
            for (e in w.events) { val s = DeadLetterSfx.pcm(e); assertNotNull(e, s); val at = (t * rate).toInt()
                for (i in s!!.indices) if (at + i < total) mix[at + i] += s[i] / 32767f
                if (e.startsWith("voice")) loud = maxOf(loud, s.maxOf { kotlin.math.abs(it.toInt()) } / 32767f) }
            w.events.clear()
        }
        assertTrue("the voice is audible ($loud)", loud > 0.2f)
        val f = File("build/shots").apply { mkdirs() }.resolve("plea-audio.wav")
        RandomAccessFile(f, "rw").use { o ->
            o.setLength(0)
            fun le32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
            fun le16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
            o.writeBytes("RIFF"); le32(36 + total * 2); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(total * 2)
            val buf = ByteArray(total * 2); for (i in 0 until total) { val v = (mix[i].coerceIn(-1f, 1f) * 32767).toInt(); buf[i * 2] = (v and 255).toByte(); buf[i * 2 + 1] = ((v shr 8) and 255).toByte() }
            o.write(buf)
        }
    }
}
