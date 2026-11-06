package com.wilfrombdn.telegramnt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
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

    private fun make(name: String): ShortArray? = makeMore(name) ?: makeMain(name)
    private fun makeMain(name: String): ShortArray? = when (name) {
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
        "dome" -> { val a = noise(0.9f, 1f, 5f, 0.05f, 17); val b = tone(listOf(880f, 1320f), 0.9f, 0.18f, 3f); ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32767, 32767).toShort() } }   // a kick to the head: a heavy thump and a ringing
        "mic" -> { val a = noise(0.25f, 0.5f, 18f, 0.3f, 19); val b = tone(listOf(2350f, 2370f), 0.9f, 0.08f, 2.5f); ShortArray(b.size) { ((if (it < a.size) a[it] else 0) + b[it]).coerceIn(-32767, 32767).toShort() } }   // a thump on the mic, a thin whine of feedback
        "thud" -> { val a = noise(1.1f, 1f, 4f, 0.03f, 23); val b = tone(listOf(62f, 93f, 147f, 211f), 1.1f, 0.3f, 4.5f, square = true); ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32767, 32767).toShort() } }   // something heavy slams the steel door
        "radiofire" -> radioFire()
        else -> if (name.startsWith("voice:")) { val q = name.split(':'); voice(q[1].toInt(), q[2].toInt() / 1000f, q[3].toInt()).let { v -> ShortArray(v.size) { (v[it] * VOICE_GAIN).toInt().toShort() } } } else null
    }
    private fun makeMore(name: String): ShortArray? = when (name) {
        "breath" -> breath()
        "cry" -> voice(41, 2.6f, 3).let { v -> ShortArray(v.size) { (v[it] * 0.55f).toInt().toShort() } }     // "PLEASE HELP ME!": a low, shouting man, cut off by the shot
        "cough" -> { val a = noise(0.18f, 0.5f, 10f, 0.25f, 29); val b = noise(0.2f, 0.4f, 12f, 0.25f, 31); ShortArray(a.size + 2400 + b.size) { when { it < a.size -> a[it]; it < a.size + 2400 -> 0; else -> b[it - a.size - 2400] } } }
        "doorfall" -> { val k = noise(0.3f, 1.6f, 10f, 0.35f, 37); val f = noise(1.8f, 1.6f, 2.2f, 0.06f, 41); val ring = tone(listOf(118f, 177f, 266f), 1.8f, 0.5f, 2.6f, square = true); val off = (RATE * 0.32f).toInt()
            ShortArray(off + f.size) { val i2 = it - off; ((if (it < k.size) k[it] else 0) + (if (i2 >= 0) f[i2] + ring[i2] else 0)).coerceIn(-32767, 32767).toShort() } }   // the kick, then the door crashing down
        "doorbang" -> { val a = noise(0.7f, 1.6f, 5f, 0.05f, 71); val b = tone(listOf(70f, 104f, 158f, 233f), 0.7f, 0.5f, 5f, square = true); val c2 = noise(0.08f, 1.4f, 40f, 0.8f, 73); ShortArray(a.size) { (a[it] + b[it] + (if (it < c2.size) c2[it] else 0)).coerceIn(-32767, 32767).toShort() } }   // a boot on the steel door
        "breathsoft" -> { val b = breath(); ShortArray(b.size) { (b[it] * 0.45f).toInt().toShort() } }
        "creak" -> { val n = (RATE * 2.6f).toInt(); val r = Random(79); var ph = 0.0; ShortArray(n) { val tt = it / RATE.toFloat(); val f = 55f + 30f * sin(tt * 1.7f) + (r.nextFloat() - 0.5f) * 8f; ph += 2 * PI * f / RATE; val g = if (sin(ph * 0.5) > 0.6) 1f else 0.3f; (sin(ph).toFloat() * g * 0.22f * (1f - tt / 2.6f) * 32767).toInt().toShort() } }   // a heavy door on dry hinges
        "stomp1", "stomp2", "stomp3", "stomp4" -> { val g = name.last().digitToInt() / 4f; val a = noise(0.16f, 0.55f + 0.9f * g, 22f, 0.08f, 83 + name.last().code); val b = tone(listOf(58f, 74f), 0.16f, 0.25f * g, 20f); ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32767, 32767).toShort() } }   // boots on the floor, closer and closer
        "thrown" -> { val a = noise(0.7f, 1.7f, 6f, 0.04f, 89); val b = noise(0.35f, 0.9f, 12f, 0.5f, 97); val lo = tone(listOf(48f, 66f), 0.7f, 0.8f, 6f); ShortArray(a.size) { (a[it] + (if (it < b.size) b[it] else 0) + lo[it]).coerceIn(-32767, 32767).toShort() } }   // a body slammed into the floor
        "chuckle" -> chuckle()
        "pump" -> { val a = noise(0.08f, 0.7f, 40f, 0.7f, 43); val gap = (RATE * 0.16f).toInt(); val b = noise(0.08f, 0.8f, 40f, 0.7f, 47); ShortArray(a.size + gap + b.size) { when { it < a.size -> a[it]; it < a.size + gap -> 0; else -> b[it - a.size - gap] } } }   // shk-shk
        "shotgun" -> { val a = noise(3.0f, 1.6f, 1.7f, 0.1f, 53); val b = noise(0.2f, 1.6f, 18f, 0.9f, 59); val lo = tone(listOf(42f, 55f), 3.0f, 0.9f, 1.4f); ShortArray(a.size) { (a[it] + (if (it < b.size) b[it] else 0) + lo[it]).coerceIn(-32767, 32767).toShort() } }   // a crack, a deep boom, the room ringing
        "keys" -> tone(listOf(2100f, 2900f, 3600f), 0.25f, 0.12f, 14f, square = true)
        else -> null
    }

    /** Gunfire tearing through the door, heard over the open radio: a long ragged burst, thin and crackling, then it drops into static. */
    private fun radioFire(): ShortArray {
        val n = (RATE * 1.7f).toInt(); val out = FloatArray(n); val r = Random(61)
        var at = 0
        while (at < n - RATE / 5) {
            val shot = noise(0.12f, 1.5f, 26f, 0.55f, r.nextInt(1000))
            for (i in shot.indices) if (at + i < n) out[at + i] += shot[i] / 32767f
            at += (RATE * (0.055f + r.nextFloat() * 0.07f)).toInt()
        }
        var hp = 0f; var lastIn = 0f
        return ShortArray(n) {
            val hiss = (r.nextFloat() * 2f - 1f) * 0.12f
            hp = 0.6f * (hp + out[it] - lastIn); lastIn = out[it]                                // thin, like a radio
            val v = (hp * 3.4f).coerceIn(-0.95f, 0.95f) + hiss                                      // clipped hard
            (v * 32767 * 1.0f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }
    /**
     * A panicked man talking into a cheap radio mic, with no real words: fast syllables on a high, wavering, cracking voice through
     * moving vowel sounds (two resonances), little gasps between bursts, then squeezed thin (radio band), clipped hard and hissing.
     * [mood]: 0 broken, sobbing; 1 talking fast; 2 shouting. Exactly [sec] long; it stops dead at the end (the cut).
     */
    private fun voice(seed: Int, sec: Float, mood: Int): ShortArray {
        val n = (RATE * sec).toInt(); val r = Random(1000 + seed); val out = FloatArray(n)
        val vowels = arrayOf(floatArrayOf(800f, 1250f), floatArrayOf(520f, 1850f), floatArrayOf(320f, 2300f), floatArrayOf(560f, 950f), floatArrayOf(380f, 820f), floatArrayOf(680f, 1650f))
        // a resonant band-pass (one formant)
        class Res(var f: Float, val q: Float) { var y1 = 0f; var y2 = 0f
            fun step(x: Float): Float { val w = (2 * PI * f / RATE).toFloat(); val rr = 1f - w / (2f * q); val c = 2f * rr * kotlin.math.cos(w); val y = (1f - rr) * x + c * y1 - rr * rr * y2; y2 = y1; y1 = y; return y } }
        val f1 = Res(700f, 5f); val f2 = Res(1500f, 7f)
        var i = 0; var phase = 0f; var noiseL = 0f
        val base = when (mood) { 0 -> 230f; 1 -> 285f; 3 -> 150f; else -> 340f }
        while (i < n) {
            // a burst of syllables, then a gasp or a sob
            val burst = if (mood == 0) 1 + r.nextInt(3) else 3 + r.nextInt(6)
            for (b in 0 until burst) {
                if (i >= n) break
                val len = (RATE * (if (mood == 0) 0.14f + r.nextFloat() * 0.18f else 0.07f + r.nextFloat() * 0.11f)).toInt()
                val va = vowels[r.nextInt(vowels.size)]; val vb = vowels[r.nextInt(vowels.size)]
                val crack = r.nextFloat() < (if (mood >= 2) 0.35f else 0.15f)                 // the voice breaks up high
                val p0 = base * (0.9f + r.nextFloat() * 0.25f) * (if (crack) 1.6f else 1f); val p1 = p0 * (if (mood == 0) 0.8f else 0.92f + r.nextFloat() * 0.2f)
                val cons = r.nextInt(3)                                                       // a consonant at the start: none, hiss, pop
                for (k in 0 until len) { if (i >= n) break
                    val u = k / len.toFloat()
                    val pitch = (p0 + (p1 - p0) * u) * (1f + 0.04f * sin(i * 2f * PI.toFloat() * 9f / RATE)) * (1f + (r.nextFloat() - 0.5f) * 0.03f)
                    phase += pitch / RATE; if (phase >= 1f) phase -= 1f
                    val src = (1f - 2f * phase) * 0.7f + (if (phase < 0.12f) 0.6f else 0f)       // a buzzy, raspy pulse
                    f1.f = va[0] + (vb[0] - va[0]) * u; f2.f = va[1] + (vb[1] - va[1]) * u
                    val breathy = (r.nextFloat() * 2f - 1f) * (if (mood == 0) 0.35f else 0.15f)
                    var v = f1.step(src + breathy) * 1.0f + f2.step(src + breathy) * 0.7f
                    val cl = (RATE * 0.03f).toInt()
                    if (cons == 1 && k < cl) v = v * (k / cl.toFloat()) + (r.nextFloat() * 2f - 1f) * 0.5f * (1f - k / cl.toFloat())
                    if (cons == 2 && k < cl / 3) v += (r.nextFloat() * 2f - 1f) * 0.9f
                    val env = min(1f, k / (RATE * 0.012f)) * min(1f, (len - k) / (RATE * 0.025f))
                    out[i] = v * env * (if (b == burst - 1 && mood >= 2) 1.25f else 1f); i++ }
                i += (RATE * (0.01f + r.nextFloat() * 0.04f)).toInt()
            }
            // between bursts: a sharp gasp, or (broken) a shaky sob
            val gap = (RATE * (if (mood == 0) 0.25f + r.nextFloat() * 0.3f else 0.08f + r.nextFloat() * 0.2f)).toInt()
            for (k in 0 until gap) { if (i >= n) break
                val u = k / gap.toFloat(); noiseL += (r.nextFloat() * 2f - 1f - noiseL) * 0.35f
                val shake = if (mood == 0) (0.5f + 0.5f * sin(u * 2f * PI.toFloat() * 5f)) else 1f
                out[i] = noiseL * sin(PI.toFloat() * u) * 0.45f * shake; i++ }
        }
        // the radio: no lows, no highs, driven into clipping, with hiss
        var hp = 0f; var lastIn = 0f; var lp = 0f
        val res = ShortArray(n)
        for (k in 0 until n) {
            hp = 0.93f * (hp + out[k] - lastIn); lastIn = out[k]
            lp += (hp - lp) * 0.55f
            val driven = kotlin.math.tanh(lp * (if (mood >= 2) 4.5f else 3.2f))
            val hiss = (r.nextFloat() * 2f - 1f) * 0.05f
            res[k] = ((driven * 0.62f + hiss) * 0.75f * 32767).toInt().coerceIn(-32767, 32767).toShort()
        }
        return res
    }

    /** A low, satisfied "heh... heh heh": three breathy bursts of a deep voice. */
    private fun chuckle(): ShortArray {
        val n = (RATE * 1.5f).toInt(); val out = ShortArray(n); val r = Random(101); var ph = 0.0; var lp = 0f
        val bursts = floatArrayOf(0.0f, 0.55f, 0.82f); val lens = floatArrayOf(0.22f, 0.16f, 0.2f)
        for (i in 0 until n) { val t = i / RATE.toFloat(); var env = 0f
            for (k in bursts.indices) { val u = (t - bursts[k]) / lens[k]; if (u in 0f..1f) env = max(env, sin(PI * u).toFloat()) }
            val f = 108f - 14f * t; ph += 2 * PI * f / RATE
            val buzz = ((ph / (2 * PI)) % 1.0).toFloat() * 2f - 1f
            lp += ((buzz * 0.6f + (r.nextFloat() * 2f - 1f) * 0.5f) - lp) * 0.18f
            out[i] = (lp * env * 0.9f * 32767).toInt().coerceIn(-32767, 32767).toShort() }
        return out
    }

    /** One heavy, ragged breath: in, then out. */
    private fun breath(): ShortArray {
        val n = (RATE * 1.3f).toInt(); val r = Random(67); var last = 0f
        return ShortArray(n) { val t = it / RATE.toFloat(); last += (r.nextFloat() * 2f - 1f - last) * 0.18f
            val env = if (t < 0.5f) sin(PI * t / 0.5f).toFloat() * 0.6f else if (t < 0.6f) 0f else sin(PI * (t - 0.6f) / 0.7f).toFloat()
            (last * env * 0.55f * 32767 * 2f).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** The samples of a sound (22050 Hz mono), for tests. */
    internal fun pcm(name: String): ShortArray? = synchronized(cache) { cache.getOrPut(name) { make(name) ?: return null } }
    internal const val SAMPLE_RATE = RATE
    /** The voice on the call: 0.8, then 30% quieter, then quieter again (it was far too loud). */
    internal const val VOICE_GAIN = 0.3f

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
