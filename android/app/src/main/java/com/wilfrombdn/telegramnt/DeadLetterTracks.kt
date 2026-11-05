package com.wilfrombdn.telegramnt

import android.content.Context
import android.media.MediaPlayer

/** The real songs of Dead Letter. One plays at a time; [play] with "" is silence. "l2" runs once and is followed by "l2b". */
internal class DeadLetterTracks(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    private var name = ""
    private val res = mapOf("l1" to R.raw.dl_level1, "l2" to R.raw.dl_level2, "l2b" to R.raw.dl_level2b, "l3" to R.raw.dl_level3, "l3b" to R.raw.dl_level3b, "l4" to R.raw.dl_level4, "l6" to R.raw.dl_level6)

    fun play(n: String) {
        if (n == name && mp != null) return
        stop()
        if (n.isNotEmpty()) start(n)
    }

    private fun start(n: String) {
        val id = res[n] ?: return
        try {
            val m = MediaPlayer.create(ctx, id) ?: return
            m.isLooping = n != "l2"
            m.setVolume(0.85f, 0.85f)
            if (n == "l2") m.setOnCompletionListener { try { it.release() } catch (_: Exception) {}; mp = null; start("l2b") }
            m.start(); mp = m; name = n
        } catch (_: Exception) { mp = null; name = "" }
    }

    fun stop() {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null; name = ""
    }
}
