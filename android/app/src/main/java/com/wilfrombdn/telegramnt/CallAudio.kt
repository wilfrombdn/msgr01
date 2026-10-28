package com.wilfrombdn.telegramnt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sound of a call. Both phones join the call room on the server (over Tor, like everything else)
 * and stream 20 ms frames of 16 kHz voice (G.711 µ-law, ~130 kbit/s with framing), each sealed with
 * AES-GCM under a key only the two phones know (sent inside the Signal-encrypted call setup). The
 * server just passes noise along. Tor adds about a second of delay; frames that pile up during a
 * network hiccup are dropped so the delay doesn't keep growing.
 */
object CallAudio {
    private const val RATE = 16_000
    private const val FRAME = 320           // samples per 20 ms frame
    @Volatile private var sock: Socket? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false   // hung up on purpose (not a dropped connection)
    @Volatile var speaker = false
        private set

    fun start(ctx: Context, k: Core.Call) {
        stop()
        stopRequested = false
        running = true
        Thread({ run(ctx.applicationContext, k) }, "call-audio").start()
    }

    fun stop() {
        stopRequested = true
        running = false
        runCatching { sock?.close() }
        sock = null
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        speaker = on
        @Suppress("DEPRECATION")
        ctx.getSystemService(AudioManager::class.java).isSpeakerphoneOn = on
    }

    private fun run(ctx: Context, k: Core.Call) {
        val core = Messenger.core
        val am = ctx.getSystemService(AudioManager::class.java)
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // Join the call room, and keep trying until the other phone is there too (0x01) or the call ends:
            // Tor may still be warming up, or a connection may drop while it rings. A failed try never
            // hangs up by itself; only the ring timeout, the other side, or you end the call.
            var s: java.net.Socket? = null
            var inp: DataInputStream? = null
            while (running && k.phase != Core.Call.ENDED) {
                try {
                    val x = core.relay.pipe(k.room, if (k.outgoing) "caller" else "callee")
                    sock = x
                    val i = DataInputStream(x.getInputStream())
                    if (i.read() == 1) { s = x; inp = i; break }   // both phones are here
                    runCatching { x.close() }
                } catch (e: Exception) { }
                if (!running || k.phase == Core.Call.ENDED) return
                Thread.sleep(2_000)
            }
            if (s == null || inp == null || !running || k.phase == Core.Call.ENDED) return
            s.soTimeout = 45_000
            s.tcpNoDelay = true
            core.callConnected()

            val key = k.key.unhex()
            val sendKey = Crypto.sha256(key + (if (k.outgoing) "caller" else "callee").toByteArray())
            val recvKey = Crypto.sha256(key + (if (k.outgoing) "callee" else "caller").toByteArray())

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeaker(ctx, speaker)
            val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // VOICE_COMMUNICATION turns on the phone's echo cancelling and noise suppression
            @Suppress("MissingPermission")
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, FRAME * 2 * 4))
            rec = r
            val minPlay = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minPlay, FRAME * 2 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
            r.startRecording(); t.play()

            // send: microphone -> µ-law -> sealed frame
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 4096))
            Thread({
                val pcm = ShortArray(FRAME); val law = ByteArray(FRAME)
                val c = Cipher.getInstance("AES/GCM/NoPadding"); var n = 0L
                try {
                    while (running) {
                        var got = 0
                        while (got < FRAME && running) { val x = r.read(pcm, got, FRAME - got); if (x <= 0) break; got += x }
                        for (i in 0 until FRAME) law[i] = if (k.muted) ULAW_SILENCE else lin2ulaw(pcm[i].toInt())
                        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(n++)))
                        val ct = c.doFinal(law)
                        out.writeShort(ct.size); out.write(ct); out.flush()
                    }
                } catch (e: Exception) { }
                runCatching { s.close() }
            }, "call-send").start()

            // play: a small queue between the network and the speaker; if it backs up, skip ahead
            val q = ArrayBlockingQueue<ShortArray>(64)
            Thread({
                try {
                    while (running) {
                        val f = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        while (q.size > 12) q.poll()        // > ~240 ms waiting: drop to catch up
                        t.write(f, 0, f.size)
                    }
                } catch (e: Exception) { }
            }, "call-play").start()

            // receive: sealed frame -> µ-law -> queue
            val c = Cipher.getInstance("AES/GCM/NoPadding"); var n = 0L
            while (running) {
                val len = inp.readUnsignedShort()
                if (len !in 17..4096) throw IOException("bad frame")
                val ct = ByteArray(len); inp.readFully(ct)
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(128, nonce(n++)))
                val law = c.doFinal(ct)                     // throws if tampered or out of order
                q.offer(ShortArray(law.size) { ULAW[law[it].toInt() and 0xFF] })
            }
        } catch (e: Exception) {
            // hung up, network dropped, or couldn't connect: handled below
        } finally {
            running = false
            runCatching { rec?.stop() }; runCatching { rec?.release() }
            runCatching { track?.stop() }; runCatching { track?.release() }
            runCatching { sock?.close() }; sock = null
            runCatching { am.mode = AudioManager.MODE_NORMAL; @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
            speaker = false
            if (k.phase != Core.Call.ENDED && !stopRequested) Messenger.io.execute { core.hangUp(if (k.connectedAt > 0) "ok" else "failed") }
        }
    }

    private fun nonce(n: Long) = ByteArray(12).also { for (i in 0 until 8) it[11 - i] = (n ushr (8 * i)).toByte() }

    // ---- G.711 µ-law ----
    private const val ULAW_SILENCE = 0xFF.toByte()
    private val ULAW = ShortArray(256) { i ->
        val x = i.inv() and 0xFF
        val exp = (x shr 4) and 7; val mant = x and 0x0F
        val s = (((mant shl 3) + 0x84) shl exp) - 0x84
        (if (x and 0x80 != 0) -s else s).toShort()
    }

    private fun lin2ulaw(pcm: Int): Byte {
        var s = pcm
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32635) s = 32635
        s += 0x84
        var exp = 7; var mask = 0x4000
        while (s and mask == 0 && exp > 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    /** For tests: µ-law round trip of one sample. */
    internal fun roundTrip(pcm: Int): Int = ULAW[lin2ulaw(pcm).toInt() and 0xFF].toInt()
}
