package com.wilfrombdn.telegramnt

import android.content.Context
import android.media.MediaPlayer

/** What the soundtrack should be doing: song [name] ("" = silence) at volume [vol]. A song that is left is cut over [cut] seconds (0 = dead stop). */
internal class Cue(val name: String, val vol: Float = 0.85f, val cut: Float = 0f)

/** The real songs of Dead Letter. One plays at a time; [play] with "" is silence. "l2" runs once and is followed by "l2b". */
internal class DeadLetterTracks(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    private var name = ""
    private var vol = 0.85f
    private var fadeLeft = 0f; private var fadeRate = 0f
    private val res = mapOf(
        "l1" to R.raw.dl_level1, "l2" to R.raw.dl_level2, "l2b" to R.raw.dl_level2b, "l3" to R.raw.dl_level3, "l3b" to R.raw.dl_level3b, "l4" to R.raw.dl_level4, "l6" to R.raw.dl_level6,
        "hotline" to R.raw.dl_hotline, "mayor" to R.raw.dl_mayor, "cell" to R.raw.dl_cell, "credits" to R.raw.dl_credits,
    )

    /** Start [n] at volume [v] (or, if it is already playing, just set its volume). */
    fun play(n: String, v: Float = 0.85f) {
        if (n == name && mp != null && fadeLeft <= 0f) { volume(v); return }
        stop()
        if (n.isNotEmpty()) start(n, v)
    }

    fun volume(v: Float) { vol = v.coerceIn(0f, 1f); try { mp?.setVolume(vol, vol) } catch (_: Exception) {} }

    /** Silence: a dead stop for 0, otherwise the song drops to nothing over [sec] seconds. */
    fun cut(sec: Float) {
        if (mp == null) return
        if (sec <= 0.03f) { stop(); return }
        name = ""; fadeLeft = sec; fadeRate = vol / sec
    }

    /** Called every frame: carries out a cut that is in progress. */
    fun tick(dt: Float) {
        if (fadeLeft <= 0f) return
        fadeLeft -= dt
        if (fadeLeft <= 0f) stop() else volume(vol - fadeRate * dt)
    }

    private fun start(n: String, v: Float) {
        // "tremolo" is dropped in as res/raw/dl_tremolo.mp3; until it's there, it's silence
        val id = res[n] ?: ctx.resources.getIdentifier("dl_$n", "raw", ctx.packageName).takeIf { it != 0 } ?: return
        try {
            val m = MediaPlayer.create(ctx, id) ?: return
            m.isLooping = n != "l2" && n != "tremolo" && n != "mayor" && n != "credits"
            vol = v.coerceIn(0f, 1f); m.setVolume(vol, vol)
            if (n == "l2") m.setOnCompletionListener { try { it.release() } catch (_: Exception) {}; mp = null; start("l2b", vol) }
            m.start(); mp = m; name = n
        } catch (_: Exception) { mp = null; name = "" }
    }

    fun stop() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null; name = ""; fadeLeft = 0f
    }
}
