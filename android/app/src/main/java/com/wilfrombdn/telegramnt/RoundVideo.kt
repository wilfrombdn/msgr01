package com.wilfrombdn.telegramnt

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

/** A recorded round video: read it, make a round-ish preview frame, delete the file, send it to the open chat. */
internal fun MainActivity.sendRound(path: String) {
    val c = chatUser?.let { Messenger.core.contact(it) }.takeIf { screen == Screen.CHAT }
    val g = groupId?.let { Messenger.core.group(it) }.takeIf { screen == Screen.GROUP }
    val f = File(path)
    if (c == null && g == null) { f.delete(); return }
    stick = true
    Messenger.run({
        val bytes = f.readBytes()
        val thumb = runCatching {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(path)
            val frame = r.getFrameAtTime(0); r.release()
            frame?.let { b -> val s = minOf(b.width, b.height); thumbOf(android.graphics.Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)) }
        }.getOrNull()
        f.delete()
        if (g != null) Messenger.sendGroupFile(g, "Video message.mp4", "video/mp4", bytes, thumb) else Messenger.sendFile(c!!, "Video message.mp4", "video/mp4", bytes, thumb)
    }, {}) { e -> f.delete(); toast(errorText(e)) }
}

/** A round video in the chat (its first frame); tap plays it with sound in a pop-up circle. */
internal fun MainActivity.roundView(m: Msg, onLong: () -> Unit): View {
    val f = m.file!!
    val here = f.has("local")
    val ask = askToDownload(m)
    val thumb = if (ask) null else f.optString("th").takeIf { it.isNotEmpty() }?.let { val t = java.util.Base64.getDecoder().decode(it); android.graphics.BitmapFactory.decodeByteArray(t, 0, t.size) }
    return FrameLayout(this).apply {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
        setBackgroundColor(Color.parseColor("#33000000"))
        layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply { bottomMargin = dp(4) }
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; thumb?.let { setImageBitmap(it) } }, FrameLayout.LayoutParams(-1, -1))
        addView(tv(if (!here) (if (f.optBoolean("cleared")) "Removed" else if (m.mine) "" else if (ask) "⬇ Tap" else "Downloading…") else "▶", if (here) 26f else 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER; background = circle(Color.parseColor("#88000000")); setPadding(dp(12), dp(6), dp(12), dp(6))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { if (here) playRound(m) else if (!f.optBoolean("cleared")) retryFetch(m) }
        setOnLongClickListener { onLong(); true }
    }
}

/** Plays a round video from memory in a big circle over the chat (so chat refreshes don't cut it off). */
internal fun MainActivity.playRound(m: Msg) {
    val bytes = Messenger.core.openFile(m) ?: return toast("Couldn't open that video")
    Voice.stopPlaying()
    val size = minOf(resources.displayMetrics.widthPixels - dp(40), dp(340))
    val d = android.app.Dialog(this)
    val mp = android.media.MediaPlayer()
    val texture = android.view.TextureView(this)
    texture.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            try {
                mp.setDataSource(Voice.Bytes(bytes)); mp.setSurface(android.view.Surface(st))
                mp.setOnVideoSizeChangedListener { _, vw, vh ->   // fill the circle without stretching
                    if (vw > 0 && vh > 0) {
                        val s = maxOf(size.toFloat() / vw, size.toFloat() / vh)
                        texture.setTransform(android.graphics.Matrix().apply { setScale(vw * s / size, vh * s / size, size / 2f, size / 2f) })
                    }
                }
                mp.setOnCompletionListener { d.dismiss() }
                mp.prepare(); mp.start()
            } catch (e: Exception) { d.dismiss(); toast("Couldn't play that video") }
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
    }
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    d.setContentView(FrameLayout(this).apply {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
        addView(texture, FrameLayout.LayoutParams(size, size))
        setOnClickListener { d.dismiss() }
    })
    d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout(size, size)
    d.setOnDismissListener { runCatching { mp.stop() }; runCatching { mp.release() } }
    d.show()
}
