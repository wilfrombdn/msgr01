package com.wilfrombdn.telegramnt

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * The message box. Tells the keyboard (Gboard, Samsung, SwiftKey…) that it accepts GIFs, stickers and
 * pictures, so picking one from the keyboard's own GIF/sticker panel sends it instead of
 * "images aren't supported here". [onContent] gets the item and a `done` to call once it's been read.
 */
class ChatBox(ctx: Context, private val onContent: (Uri, String, () -> Unit) -> Unit) : EditText(ctx) {
    init {
        // select some text: Bold / Italic / Strike / Spoiler / Mono join Copy, Paste… in the menu
        customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                Format.MARKS.forEachIndexed { i, (_, name) -> menu.add(0, FORMAT_ID + i, 100 + i, name) }
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val i = item.itemId - FORMAT_ID
                if (i !in Format.MARKS.indices) return false
                Format.wrap(this@ChatBox, Format.MARKS[i].first); mode.finish(); return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
    }

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(out) ?: return null
        EditorInfoCompat.setContentMimeTypes(out, MIMES)
        return InputConnectionCompat.createWrapper(ic, out) { info, flags, _ ->
            val grant = Build.VERSION.SDK_INT >= 25 && (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            if (grant) try { info.requestPermission() } catch (e: Exception) { return@createWrapper false }
            val mime = MIMES.firstOrNull { info.description.hasMimeType(it) }
                ?: run { if (grant) info.releasePermission(); return@createWrapper false }
            onContent(info.contentUri, mime) { if (grant) runCatching { info.releasePermission() } }
            true
        }
    }

    companion object {
        private const val FORMAT_ID = 0x4d53
        val MIMES = arrayOf("image/gif", "image/webp", "image/png", "image/jpeg")
    }
}
