package com.wilfrombdn.telegramnt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little sound effects for Dead Letter, made on the spot like the music (no audio files): the four uplink tones, the error buzz, a jump,
 * a hit, a door kick, typing, the clank of the catwalks, a gunshot and a distant explosion. Each plays on its own short-lived track.
 */
internal object DeadLetterSfx {
    private const val RATE = 22050
    private val cache = HashMap<String, ShortArray>()

    private fun tone(freqs: List<Float>, sec: Float, vol: Float = 0.35f, decay: Float = 3f, square: Boolean = false): ShortArray {
        val n = (RATE * sec).toInt(); val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            var v = 0f
            for (f in freqs) { val s = sin(2 * PI * f * t).toFloat(); v += if (square) (if (s >= 0) 0.6f else -0.6f) else s }
            v /= freqs.size
            val env = exp(-t * decay).toFloat() * (if (t < 0.005f) t / 0.005f else 1f)
            out[i] = (v * env * vol * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }
    private fun noise(sec: Float, vol: Float, decay: Float, low: Float = 0.2f, seed: Int = 1): ShortArray {
        val n = (RATE * sec).toInt(); val out = ShortArray(n); val r = Random(seed); var last = 0f
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            last += (r.nextFloat() * 2f - 1f - last) * low          // a simple low-pass: smaller [low] = deeper rumble
            out[i] = (last * exp(-t * decay).toFloat() * vol * 32767 * 2f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }
    private fun sweep(f0: Float, f1: Float, sec: Float, vol: Float): ShortArray {
        val n = (RATE * sec).toInt(); val out = ShortArray(n); var ph = 0.0
        for (i in 0 until n) { val k = i / n.toFloat(); val f = f0 + (f1 - f0) * k; ph += 2 * PI * f / RATE; out[i] = (sin(ph).toFloat() * (1f - k) * vol * 32767).toInt().toShort() }
        return out
    }

    private fun make(name: String): ShortArray? = when (name) {
        "simon0" -> tone(listOf(329.6f), 0.42f, 0.32f, 2.4f)        // E
        "simon1" -> tone(listOf(440f), 0.42f, 0.32f, 2.4f)          // A
        "simon2" -> tone(listOf(554.4f), 0.42f, 0.32f, 2.4f)        // C#
        "simon3" -> tone(listOf(659.3f), 0.42f, 0.32f, 2.4f)        // E (high)
        "wrong" -> tone(listOf(98f, 103f), 0.6f, 0.35f, 2f, square = true)
        "ok" -> tone(listOf(659.3f, 880f, 1318.5f), 0.9f, 0.25f, 2.2f)
        "jump" -> sweep(300f, 700f, 0.14f, 0.22f)
        "hit" -> sweep(500f, 90f, 0.45f, 0.3f)
        "kick" -> noise(0.3f, 0.6f, 14f, 0.35f, 3)
        "type" -> noise(0.04f, 0.25f, 60f, 0.8f, 5)
        "clank" -> tone(listOf(180f, 263f, 410f), 0.7f, 0.28f, 5f, square = true)
        "shot" -> noise(0.5f, 0.9f, 9f, 0.6f, 7)
        "boom" -> noise(2.2f, 0.8f, 1.6f, 0.04f, 9)
        "throw" -> sweep(200f, 120f, 0.12f, 0.12f)
        "static" -> noise(0.5f, 0.25f, 3f, 0.9f, 11)
        "over" -> tone(listOf(1046.5f, 1568f), 0.22f, 0.18f, 9f)
        "beat" -> noise(0.18f, 0.8f, 22f, 0.12f, 13)
        "keys" -> tone(listOf(2100f, 2900f, 3600f), 0.25f, 0.12f, 14f, square = true)
        else -> null
    }

    fun play(name: String) {
        val pcm = synchronized(cache) { cache.getOrPut(name) { make(name) ?: return } }
        Thread({
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 60)
                track.release()
            } catch (_: Throwable) {}
        }, "dead-letter-sfx").apply { isDaemon = true }.start()
    }
}
