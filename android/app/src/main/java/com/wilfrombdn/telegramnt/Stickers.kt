package com.wilfrombdn.telegramnt

import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.ByteArrayOutputStream

// ---------- stickers ----------

/** Stickers: a picture named sticker.* — shown big, with no bubble behind it. */
internal fun MainActivity.isSticker(m: Msg) = m.file?.optString("n")?.startsWith("sticker.") == true && m.file?.optString("m") in listOf("image/webp", "image/gif", "image/png")

/** Saves a picture/GIF/sticker from a chat into my stickers (photos become 512 px see-through-capable WebP). */
internal fun MainActivity.addToStickers(m: Msg) {
    Messenger.run({
        val bytes = Messenger.core.openFile(m) ?: throw UserError("Couldn't open that picture")
        val mime = m.file?.optString("m") ?: ""
        if (mime == "image/gif" || (mime == "image/webp" && isAnimatedWebp(bytes))) Messenger.addSticker(bytes, mime)
        else {
            val bmp = decodeSmall(bytes, 1024) ?: throw UserError("Couldn't open that picture")
            val scale = 512f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION") small.compress(if (Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP, 90, out)
            Messenger.addSticker(out.toByteArray(), "image/webp")
        }
    }, { toast("Added to your stickers") }) { e -> toast(errorText(e)) }
}

internal fun MainActivity.isAnimatedWebp(b: ByteArray) = b.size > 40 && String(b, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (b[20].toInt() and 0x02) != 0

/** The Stickers tab: your saved stickers; tap sends, hold removes. */
internal fun MainActivity.showStickerTab(grid: LinearLayout, scroll: ScrollView, target: Any) {
    grid.removeAllViews()
    val ids = Messenger.stickers.toList()
    if (ids.isEmpty()) {
        grid.addView(tv("No stickers yet.\n\nHold any picture, GIF or sticker in a chat and tap \"Add to my stickers\".", 14.5f, dim).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), dp(40))
        }, LinearLayout.LayoutParams(-1, -2))
        return
    }
    var row: LinearLayout? = null
    ids.forEachIndexed { i, id ->
        if (i % 4 == 0) row = LinearLayout(this).also { grid.addView(it, LinearLayout.LayoutParams(-1, dp(86))) }
        val mime = id.substringAfter('|')
        row!!.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)); background = pressable()
            Messenger.stickerBytes(id)?.let { b -> decodeSmall(b, 200)?.let { setImageBitmap(it) } }
            setOnClickListener {
                val bytes = Messenger.stickerBytes(id) ?: return@setOnClickListener
                val thumb = decodeSmall(bytes, 300)?.let { thumbOf(it) }
                val name = "sticker." + if (mime == "image/gif") "gif" else "webp"
                emojiPopup?.dismiss(); emojiPopup = null; stick = true
                Messenger.io.execute {
                    when (target) {
                        is Contact -> Messenger.sendFile(target, name, mime, bytes, thumb)
                        is Group -> Messenger.sendGroupFile(target, name, mime, bytes, thumb)
                    }
                }
            }
            setOnLongClickListener {
                popup("Remove this sticker?", null, null, "Cancel" to dim, "Remove" to red) { k -> if (k == 1) { Messenger.removeSticker(id); showStickerTab(grid, scroll, target) } }
                true
            }
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }
    repeat((4 - ids.size % 4) % 4) { row!!.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f)) }
    scroll.scrollTo(0, 0)
}
