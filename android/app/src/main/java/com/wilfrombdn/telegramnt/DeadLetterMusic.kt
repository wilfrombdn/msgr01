package com.wilfrombdn.telegramnt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Dead Letter soundtrack, made up on the spot (no audio files): a slow, quiet bassline when you are only a little
 * wanted, getting faster, busier and harsher as the manhunt grows. [heat] is 0..5. Off unless the player turns it on.
 */
internal class DeadLetterMusic {
    @Volatile private var heat = 0
    @Volatile private var running = false
    @Volatile private var calm = false      // the ending: everything fades to a low drone
    private var thread: Thread? = null

    fun setHeat(h: Int) { heat = h.coerceIn(0, 5) }
    fun setCalm(c: Boolean) { calm = c }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ play() }, "dead-letter-music").also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; thread = null }

    private fun play() {
        val rate = 22050
        val track = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(min, 4096) * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { running = false; return }
        try {
            track.play()
            // A minor, one bar of eight eighth-notes (semitones above A1), a darker one for the later levels
            val calmBass = intArrayOf(0, 0, 7, 0, 3, 0, 7, 5)
            val tenseBass = intArrayOf(0, 0, 1, 0, 7, 0, 6, 1)
            var bassPhase = 0.0; var leadPhase = 0.0; var droneP = 0.0
            var seed = 12345
            var pos = 0L
            var level = 0f
            val buf = ShortArray(1024)
            while (running) {
                val h = heat
                val bpm = 62 + h * 16
                val stepLen = (rate * 60.0 / (bpm * 2)).toInt()           // an eighth note
                val sixteenth = stepLen / 2
                val bass = if (h >= 3) tenseBass else calmBass
                val target = if (calm) 0.55f else 1f
                for (i in buf.indices) {
                    level += (target - level) * 0.00004f
                    val step = ((pos / stepLen) % 8).toInt()
                    val inStep = (pos % stepLen).toInt()
                    var s = 0.0
                    // bass: a soft square-ish wave with a quick decay on every eighth
                    val semis = bass[step] + (if (h >= 4 && step % 4 == 3) 12 else 0)
                    val f = 55.0 * Math.pow(2.0, semis / 12.0)
                    bassPhase += f / rate; if (bassPhase > 1) bassPhase -= 1
                    val env = exp(-inStep / (stepLen * (if (h < 2) 0.9 else 0.55)))
                    val sq = if (bassPhase < 0.5) 1.0 else -1.0
                    s += (sq * 0.55 + sin(2 * PI * bassPhase) * 0.45) * env * 0.38
                    // a low drone under everything
                    droneP += 41.2 / rate; if (droneP > 1) droneP -= 1
                    s += sin(2 * PI * droneP) * (0.10 + 0.02 * h)
                    // a kick on the beat from level 3 up
                    if (h >= 2 && step % 2 == 0) {
                        val kt = inStep / rate.toDouble()
                        s += sin(2 * PI * (45.0 * kt + 110.0 / 25.0 * (1 - exp(-25.0 * kt)))) * exp(-kt * 14.0) * 0.45   // a thump that sweeps down from ~155 Hz to 45 Hz
                    }
                    // hi-hat ticks on the off-beats from level 2 up, every sixteenth when it gets frantic
                    if (h >= 1 && ((step % 2 == 1 && inStep < 900) || (h >= 4 && inStep % sixteenth < 700 && inStep > sixteenth))) {
                        seed = seed * 1103515245 + 12345
                        val noise = ((seed shr 16) and 0x7FFF) / 16384.0 - 1.0
                        val tick = inStep % sixteenth
                        s += noise * exp(-tick / 260.0) * (0.10 + 0.025 * h)
                    }
                    // a thin, nervous lead from level 4 up
                    if (h >= 3) {
                        val arp = intArrayOf(24, 27, 31, 36, 31, 27, 34, 31)[((pos / sixteenth) % 8).toInt()]
                        val lf = 110.0 * Math.pow(2.0, arp / 12.0)
                        leadPhase += lf / rate; if (leadPhase > 1) leadPhase -= 1
                        val tt = (pos % sixteenth).toDouble() / sixteenth
                        s += (if (leadPhase < 0.25) 1.0 else -1.0) * exp(-tt * 3.0) * (0.035 + 0.01 * (h - 3))
                    }
                    s *= level * 0.8
                    buf[i] = (max(-1.0, min(1.0, s)) * 12000).toInt().toShort()
                    pos++
                }
                track.write(buf, 0, buf.size)
            }
        } catch (e: Exception) {
        } finally {
            runCatching { track.stop() }; runCatching { track.release() }
        }
    }
}
