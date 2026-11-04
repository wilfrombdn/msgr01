package com.wilfrombdn.telegramnt

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.io.File

// The paperclip menu and sending a picked photo, video or file.

internal fun MainActivity.attachMenu(anchor: View) {   // target already set in attachTo / attachGroupId
    sendOnce = false   // only the "View once photo" row turns it on, for the photo picked right after
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(menuBg, 4)
        elevation = dp(10).toFloat()
        setPadding(0, dp(6), 0, dp(6))
    }
    val pw = android.widget.PopupWindow(panel, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true)
    fun row(res: Int, label: String, go: () -> Unit) = panel.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(20), dp(14))
        background = pressable()
        isClickable = true
        addView(icon(res, white, 22, 0))
        addView(tv(label, 16f, white).apply { setPadding(dp(16), 0, 0, 0) })
        setOnClickListener { pw.dismiss(); go() }
    })
    row(R.drawable.ic_camera, "Camera") {
        @Suppress("DEPRECATION") startActivityForResult(Intent(this, CameraActivity::class.java), TAKE_MEDIA)
    }
    row(R.drawable.ic_attach, "Media") {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_PHOTO)
    }
    attachTo?.let { Messenger.core.contact(it) }?.takeIf { it.status != Contact.SAVED }?.let {
        row(R.drawable.ic_camera, "View once photo") {
            sendOnce = true
            val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_PHOTO)
        }
    }
    row(R.drawable.ic_file, "File") {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(i, PICK_ATTACH_FILE)
    }
    row(R.drawable.ic_location, "Location") { shareLocation() }
    attachGroupId?.let { gid -> row(R.drawable.ic_poll, "Poll") { Messenger.core.group(gid)?.let { createPoll(it) } } }
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    pw.isOutsideTouchable = true
    // expand upward, just above the clip
    panel.measure(View.MeasureSpec.makeMeasureSpec(dp(190), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
    pw.showAsDropDown(anchor, -dp(130), -(anchor.height + panel.measuredHeight + dp(6)))
}

internal fun MainActivity.attachPicked(uri: android.net.Uri, asPhoto: Boolean) {
    val c = attachTo?.let { Messenger.core.contact(it) }
    val g = attachGroupId?.let { Messenger.core.group(it) }
    val once = sendOnce && c != null && asPhoto; sendOnce = false
    val fromCamera = uri.authority == "$packageName.files"   // the in-app camera, not the gallery
    if (c == null && g == null) return
    // send to whichever target the attach menu was opened from
    fun out(name: String, mime: String, data: ByteArray, thumb: ByteArray?) {
        if (g != null) Messenger.sendGroupFile(g, name, mime, data, thumb) else Messenger.sendFile(c!!, name, mime, data, thumb, once)
    }
    toast("Locking and sending…")
    Messenger.run({
        var name = "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cur.isNull(it)) size = cur.getLong(it) }
            }
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        if (mime != "image/gif" && (asPhoto || mime.startsWith("image/"))) {
            // re-saved as JPEG: smaller, and hidden details like GPS location are dropped
            val bmp = loadPhoto(uri, if (fromCamera) CAMERA_SIDE else 2000) ?: throw UserError("Couldn't open that photo")
            out(name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg(bmp, if (fromCamera) CAMERA_SIDE else 1600, if (fromCamera) 90 else 85), thumbOf(bmp))
        } else {
            if (size > Core.MAX_FILE) throw UserError("Files can be up to ${Core.MAX_FILE / 1024 / 1024} MB")
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserError("Couldn't read that file")
            // videos: blank out where/when it was filmed and the phone's tags (photos are re-saved above for the same reason)
            val data = if (mime.startsWith("video/")) Mp4Clean.strip(raw) else raw
            out(name, mime, data, if (mime == "image/gif") decodeSmall(data, 400)?.let { thumbOf(it) } else null)
        }
        // the camera's unencrypted temp file has now been read into memory; don't leave it on disk
        if (uri.authority == "$packageName.files") runCatching { contentResolver.delete(uri, null, null) }
    }, {}) { e -> toast(errorText(e)) }
}

internal fun MainActivity.sizeText(n: Int) = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}
