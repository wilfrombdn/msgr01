package com.wilfrombdn.telegramnt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import java.io.File

/**
 * Voice messages. A voice message is an ordinary attachment (audio/mp4, AAC) whose "th" field holds the
 * length and a small waveform instead of a picture, so older versions still show it as a file.
 */
object Voice {
    const val MIME = "audio/mp4"
    const val NAME = "Voice message.m4a"
    const val BARS = 48
    const val MAX_MS = 5 * 60_000L
    const val MIN_MS = 700L

    fun isVoice(f: org.json.JSONObject?) = f?.optString("m") == MIME && f.optString("n").startsWith("Voice message")

    /** "th" for a voice message: 2 bytes of length in seconds, then [BARS] loudness bars (0-255). */
    fun pack(seconds: Int, levels: List<Int>): ByteArray {
        val out = ByteArray(2 + BARS)
        val s = seconds.coerceIn(0, 65535)
        out[0] = (s shr 8).toByte(); out[1] = s.toByte()
        if (levels.isNotEmpty()) {
            val peak = levels.max().coerceAtLeast(1).toDouble()
            for (i in 0 until BARS) {
                // average the samples that fall into this bar; square root so quiet speech still shows
                val a = i * levels.size / BARS; val b = maxOf(a + 1, (i + 1) * levels.size / BARS)
                val avg = levels.subList(a, minOf(b, levels.size)).average()
                out[2 + i] = (Math.sqrt(avg / peak) * 255).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Length in seconds and the bars (0..1), or null if [th] isn't a voice waveform. */
    fun unpack(th: String?): Pair<Int, FloatArray>? {
        val b = runCatching { java.util.Base64.getDecoder().decode(th ?: return null) }.getOrNull() ?: return null
        if (b.size != 2 + BARS) return null
        val s = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        return s to FloatArray(BARS) { (b[2 + it].toInt() and 0xFF) / 255f }
    }

    fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    // ---------- recording ----------

    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    private val levels = ArrayList<Int>()
    private val ui = Handler(Looper.getMainLooper())
    private val sample = object : Runnable {
        override fun run() {
            val r = rec ?: return
            levels.add(runCatching { r.maxAmplitude }.getOrDefault(0))
            if (elapsed() >= MAX_MS) onLimit?.invoke() else ui.postDelayed(this, 100)
        }
    }
    /** Called when a recording reaches [MAX_MS]. */
    var onLimit: (() -> Unit)? = null

    val recording get() = rec != null
    fun elapsed() = if (rec != null) System.currentTimeMillis() - started else 0L

    /** Old leftovers from a recording that was cut off (app killed): never keep them. */
    fun cleanup(ctx: Context) { File(ctx.cacheDir, "voice").deleteRecursively() }

    fun start(ctx: Context): Boolean {
        stopPlaying()
        cancel()
        val f = File(File(ctx.cacheDir, "voice").apply { mkdirs() }, "rec_${System.currentTimeMillis()}.m4a")
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder())
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            rec = r; file = f; started = System.currentTimeMillis(); levels.clear()
            ui.postDelayed(sample, 100)
            true
        } catch (e: Exception) {
            runCatching { r.release() }; f.delete(); false
        }
    }

    /** Stops and returns (audio, th), or null if it was too short or failed. The temp file is deleted. */
    fun finish(): Pair<ByteArray, ByteArray>? {
        val r = rec ?: return null
        val f = file
        val ms = elapsed()
        ui.removeCallbacks(sample)
        rec = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val data = if (ok && ms >= MIN_MS) runCatching { f?.readBytes() }.getOrNull() else null
        f?.delete()
        data ?: return null
        return data to pack(((ms + 500) / 1000).toInt().coerceAtLeast(1), levels.toList())
    }

    fun cancel() {
        val r = rec ?: return
        ui.removeCallbacks(sample)
        rec = null
        runCatching { r.stop() }; runCatching { r.release() }
        file?.delete(); file = null
    }

    // ---------- playback (straight from memory: the unlocked audio never touches the disk) ----------

    private var player: MediaPlayer? = null
    /** Which message is loaded in the player. */
    var playingId: String? = null
        private set

    fun isPlaying(id: String) = playingId == id && player?.isPlaying == true
    /** 0..1 through the message that is loaded, or 0 for any other. */
    fun progress(id: String): Float {
        val p = player ?: return 0f
        if (playingId != id) return 0f
        return runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f)
    }
    fun position(id: String) = if (playingId == id) runCatching { player!!.currentPosition / 1000 }.getOrDefault(0) else 0

    /** Play / pause [id]. [load] unlocks the audio (call from a background thread is not needed: it's small). */
    fun toggle(id: String, load: () -> ByteArray?, at: Float? = null) {
        val p = player
        if (p != null && playingId == id) {
            if (at != null) { p.seekTo((at * p.duration).toInt()); if (!p.isPlaying) p.start(); return }
            if (p.isPlaying) p.pause() else p.start()
            return
        }
        if (recording) return
        stopPlaying()
        val bytes = load() ?: return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(Bytes(bytes))
            mp.prepare()
            if (at != null) mp.seekTo((at * mp.duration).toInt())
            mp.setOnCompletionListener { stopPlaying() }
            mp.start()
            player = mp; playingId = id
        } catch (e: Exception) { runCatching { mp.release() } }
    }

    fun stopPlaying() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null; playingId = null
    }

    private class Bytes(val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= b.size) return -1
            val n = minOf(size.toLong(), b.size - position).toInt()
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize() = b.size.toLong()
        override fun close() {}
    }
}

/** The voice-message waveform: played part in [on], the rest in [off]. Tap or drag to jump. */
class WaveView(ctx: Context, private val bars: FloatArray, private val on: Int, private val off: Int) : View(ctx) {
    var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    var onSeek: ((Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val n = bars.size.coerceAtLeast(1)
        val step = width.toFloat() / n
        val w = step * 0.6f
        val minH = w
        for (i in 0 until n) {
            val h = maxOf(minH, bars.getOrElse(i) { 0f } * height)
            val x = i * step
            r.set(x, (height - h) / 2, x + w, (height + h) / 2)
            paint.color = if ((i + 0.5f) / n <= progress) on else off
            c.drawRoundRect(r, w / 2, w / 2, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = onSeek ?: return false
        if (e.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.action == MotionEvent.ACTION_UP) f((e.x / width).coerceIn(0f, 1f))
        return true
    }
}
