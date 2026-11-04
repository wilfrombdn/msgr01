package com.wilfrombdn.telegramnt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Photos and files in messages: viewing, saving, view-once, downloading, and shrinking/decoding images.

/** Puts the photo on the clipboard (a temporary unlocked copy, wiped next time the app opens). */
/** Longest side kept for in-app camera photos: at least a phone screen tall, so nothing is shrunk. */
internal const val CAMERA_SIDE = 2560

internal fun MainActivity.copyImage(m: Msg) {
    var out: File? = null
    Messenger.run({ out = unlockedCopy(m) }, {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.wilfrombdn.telegramnt.files", out!!)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "photo", uri))
        toast(T("Photo copied"))
    }) { e -> toast(errorText(e)) }
}

internal fun MainActivity.unlockedCopy(m: Msg): File {
    val bytes = Messenger.core.openFile(m) ?: throw UserError(T("Couldn't open that photo"))
    val name = m.file?.optString("n")?.takeIf { it.isNotBlank() }?.replace('/', '_') ?: "photo.jpg"
    return File(File(cacheDir, "open").apply { mkdirs() }, name).apply { writeBytes(bytes) }
}

/** Saves the photo to the phone's Pictures/Tellem folder (only when you ask; outside the app it isn't locked). */
internal fun MainActivity.downloadImage(m: Msg) {
    val f = m.file ?: return
    val name = "${APP_NAME}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(m.ts)) + if (isGif(m)) ".gif" else ".jpg"
    if (Build.VERSION.SDK_INT < 29) {
        pendingSave = m
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.optString("m")).putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION") startActivityForResult(i, SAVE_IMAGE)
        return
    }
    Messenger.run({
        val bytes = Messenger.core.openFile(m) ?: throw UserError(T("Couldn't open that photo"))
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, f.optString("m", "image/jpeg"))
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$APP_NAME")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = contentResolver
        val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw UserError(T("Couldn't save the photo"))
        r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw UserError(T("Couldn't save the photo"))
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
    }, { toast(T("Saved to Pictures/$APP_NAME")) }) { e -> toast(errorText(e)) }
}

internal fun MainActivity.saveImageTo(uri: android.net.Uri) {
    val m = pendingSave ?: return; pendingSave = null
    Messenger.run({
        val bytes = Messenger.core.openFile(m) ?: throw UserError(T("Couldn't open that photo"))
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }, { toast(T("Photo saved")) }) { e -> toast(errorText(e)) }
}

internal fun MainActivity.jpeg(b: android.graphics.Bitmap, maxSide: Int, quality: Int): ByteArray {
    val k = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
    val sized = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
    return ByteArrayOutputStream().also { sized.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

/** Tiny blurry preview that travels inside the message, shown until the photo arrives. */
internal fun MainActivity.thumbOf(b: android.graphics.Bitmap): ByteArray {
    var q = 60
    while (true) {
        val t = jpeg(b, 96, q)
        if (t.size <= 5000 || q <= 20) return t
        q -= 10
    }
}

internal fun MainActivity.attachmentView(m: Msg, ink: Int, onLong: () -> Unit = {}): View {
    val f = m.file!!
    val here = f.has("local")
    if (Voice.isVoice(f)) return voiceView(m, ink, onLong)
    if (f.optBoolean("vo")) return onceView(m, ink, onLong)
    if (isRound(m)) return roundView(m, onLong)
    if (f.optString("m").startsWith("image/")) {
        val ask = askToDownload(m)
        val bmp = if (ask) null else pictures[m.id] ?: run {
            val full = if (here) Messenger.core.openFile(m)?.let { decodeSmall(it, 720) } else null
            val shown = full ?: f.optString("th").takeIf { it.isNotEmpty() }?.let {
                safeBitmap(java.util.Base64.getDecoder().decode(it), 720)
            }
            if (full != null) pictures[m.id] = full
            shown
        }
        val sticker = isSticker(m)
        val w = if (sticker) dp(170) else dp(240)
        val h = if (bmp != null) (w * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(dp(if (sticker) 40 else 80), dp(360)) else dp(160)
        return FrameLayout(this).apply {
            addView(ImageView(context).apply {
                scaleType = if (sticker) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                bmp?.let { setImageBitmap(it) } ?: setBackgroundColor(Color.parseColor("#33000000"))
                if (here && isAnimated(m)) playGif(this, m, dp(360))
            }, FrameLayout.LayoutParams(w, h))
            if (isGif(m)) addView(tv("GIF", 11f, Color.WHITE, true).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2)); background = round(Color.parseColor("#88000000"), 4)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
            val cleared = f.optBoolean("cleared")
            if (!here) addView(tv(if (cleared) T("Removed to save space") else if (m.mine) "" else if (ask) T("⬇  ${if (isGif(m)) "GIF" else "Photo"} · ${sizeText(f.optInt("s"))}\nTap to download") else T("Downloading…"), 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = round(Color.parseColor("#99000000"), 10)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { if (here) showImage(m) else if (cleared) toast(T("This was removed from your phone to save space")) else retryFetch(m) }
            setOnLongClickListener { onLong(); true }
            layoutParams = LinearLayout.LayoutParams(w, h).apply { bottomMargin = dp(4) }
        }
    }
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), dp(6), dp(4))
        addView(icon(R.drawable.ic_file, ink, 26, 8).apply { background = circle(Color.parseColor("#22FFFFFF")) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(tv(f.optString("n"), 15f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE })
            addView(tv(sizeText(f.optInt("s")) + if (here) "" else if (f.optBoolean("cleared")) T(" · removed to save space") else if (m.mine) "" else if (askToDownload(m)) T(" · tap to download") else T(" · downloading…"), 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
        })
        setOnClickListener { if (here) openOutside(m) else if (f.optBoolean("cleared")) toast(T("This was removed from your phone to save space")) else retryFetch(m) }
        setOnLongClickListener { onLong(); true }
    }
}

internal fun MainActivity.decodeSmall(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? = safeBitmap(bytes, maxSide)

/**
 * Decodes a picture someone else sent (a thumbnail, a profile photo, an attachment). A tiny file can claim to be tens of thousands of
 * pixels wide and use up all memory when opened; because the message is saved, that would crash the app every time the chat opens.
 * So absurd sizes are refused, big ones are shrunk while decoding, and nothing here ever throws.
 */
internal fun safeBitmap(bytes: ByteArray, maxSide: Int = 1024): android.graphics.Bitmap? = try {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    val big = maxOf(o.outWidth, o.outHeight)
    if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth.toLong() * o.outHeight > 100_000_000L) null   // 100 megapixels: nothing real is this big
    else android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, big / maxSide) })
} catch (e: Throwable) {   // including OutOfMemoryError: a bad picture is just not shown
    null
}

/** A view-once photo: no preview, ever. Theirs: tap to view (once). Mine, or already opened: just says so. */
internal fun MainActivity.onceView(m: Msg, ink: Int, onLong: () -> Unit): View {
    val f = m.file!!
    val here = f.has("local")
    val (title, sub) = when {
        m.mine -> T("View once photo") to if (m.state == Msg.SENDING) T("Sending…") else T("Sent. Only they can open it, once")
        f.optBoolean("opened") -> T("View once photo") to T("Opened")
        !here -> T("View once photo") to if (askToDownload(m)) T("Tap to download") else T("Downloading…")
        else -> T("View once photo") to T("Tap to view. It disappears after")
    }
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), dp(6), dp(4))
        addView(tv("①", 22f, ink, true).apply { gravity = Gravity.CENTER; background = circle(Color.parseColor("#22FFFFFF")) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
            addView(tv(title, 15f, ink, true))
            addView(tv(sub, 13f, (ink and 0x00FFFFFF) or (0x99 shl 24)))
        })
        setOnClickListener {
            when {
                m.mine || f.optBoolean("opened") -> {}
                here -> showOnce(m)
                else -> retryFetch(m)
            }
        }
        setOnLongClickListener { onLong(); true }
    }
}

/** Shows a view-once photo: deleted from the phone (and saved as opened) before it appears. No copy, save or forward. */
internal fun MainActivity.showOnce(m: Msg) {
    var bmp: android.graphics.Bitmap? = null
    Messenger.run({
        val bytes = Messenger.core.openViewOnce(m) ?: throw UserError(T("This photo can't be opened again"))
        Messenger.save()
        bmp = decodeSmall(bytes, 2400)
    }, {
        val b = bmp ?: return@run toast(T("Couldn't open that photo"))
        screen = Screen.VIEWER
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ImageView(context).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_CENTER })
            addView(tv(T("View once: it's gone when you close it"), 13f, Color.WHITE).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(Color.parseColor("#99000000"), 12)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
            addView(icon(R.drawable.ic_back, Color.WHITE) { leaveViewer() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
            fitsSystemWindows = true
        })
    }) { e -> toast(errorText(e)) }
}

/** A photo/GIF/video that waits for a tap before downloading (Settings > Storage > auto-download off). */
internal fun MainActivity.askToDownload(m: Msg): Boolean {
    val f = m.file ?: return false
    return !m.mine && !f.has("local") && !f.optBoolean("cleared") && !Messenger.autoMedia && Messenger.isMedia(f)
}

internal fun MainActivity.retryFetch(m: Msg) {
    if (m.mine) return
    Messenger.run({ Messenger.core.fetchFile(m) }, {}) { e -> toast(errorText(e)) }
}

internal fun MainActivity.leaveViewer() = chatUser?.let { showChat(it) } ?: groupId?.let { showGroup(it) } ?: showChats()

/** Full-screen photo, unlocked only in memory. */
internal fun MainActivity.showImage(m: Msg) {
    var bmp: android.graphics.Bitmap? = null
    var anim: android.graphics.drawable.Drawable? = null
    Messenger.run({
        val bytes = Messenger.core.openFile(m)
        if (bytes != null && isAnimated(m)) anim = decodeGif(bytes, 1600)
        bmp = bytes?.let { decodeSmall(it, 2400) }
    }, {
        val b = bmp ?: return@run toast(T("Couldn't open that photo"))
        screen = Screen.VIEWER
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(ImageView(context).apply {
                setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_CENTER
                anim?.let { a -> setImageDrawable(a); if (Build.VERSION.SDK_INT >= 28 && a is android.graphics.drawable.AnimatedImageDrawable) a.start() }
                setOnLongClickListener { longPressMenu(null, null, imageActions(m)); true }
            })
            addView(icon(R.drawable.ic_back, Color.WHITE) { leaveViewer() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
            fitsSystemWindows = true
        })
    })
}

/** Hands a temporary unlocked copy to another app (PDF viewer, etc.). It's deleted next time the app opens. */
internal fun MainActivity.openOutside(m: Msg) {
    val f = m.file ?: return
    var out: File? = null
    Messenger.run({
        val bytes = Messenger.core.openFile(m) ?: throw UserError(T("Couldn't open that file"))
        out = File(File(cacheDir, "open").apply { mkdirs() }, f.optString("n")).apply { writeBytes(bytes) }
    }, {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.wilfrombdn.telegramnt.files", out!!)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.optString("m")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(view, f.optString("n"))) } catch (e: Exception) { toast(T("No app on this phone can open that file")) }
    }) { e -> toast(errorText(e)) }
}

// ---------- recovery backup (survives uninstall; encrypted; nothing server-side) ----------

internal fun MainActivity.takePersist(uri: android.net.Uri) = runCatching {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
}

/** Opens the chosen photo at a sensible size, the right way up. */
internal fun MainActivity.loadPhoto(uri: android.net.Uri, maxSide: Int = 2000): android.graphics.Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > maxSide) d.setTargetSampleSize(big / maxSide + 1)
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxSide + 1)
    }
    return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
}
