package com.wilfrombdn.telegramnt

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import android.view.View
import android.widget.EditText

/**
 * Message formatting, written with simple markers so it travels as plain text (older versions just show
 * the markers): **bold**, __italic__, ~~strike~~, ||spoiler||, `mono`. Also makes links tappable and
 * highlights @mentions.
 */
object Format {
    val MARKS = listOf("**" to "Bold", "__" to "Italic", "~~" to "Strike", "||" to "Spoiler", "`" to "Mono")
    val MENTION = Regex("@([a-z0-9_]{5,32})")

    /**
     * The styled text. [spoilerShown] reveals spoilers; otherwise they're a solid block that calls
     * [reveal] when tapped. [people] are the @usernames that get highlighted.
     */
    fun render(text: String, ink: Int, accent: Int, people: Set<String>, spoilerShown: Boolean, reveal: () -> Unit): CharSequence {
        val sb = SpannableStringBuilder(text)
        for ((mark, kind) in MARKS) {
            val re = Regex("(?s)" + Regex.escape(mark) + "(.+?)" + Regex.escape(mark))
            var from = 0
            while (true) {
                val m = re.find(sb, from) ?: break
                val start = m.range.first; val end = m.range.last + 1
                if (m.groupValues[1].isBlank()) { from = end; continue }
                sb.delete(end - mark.length, end); sb.delete(start, start + mark.length)
                val e = end - 2 * mark.length
                val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                when (kind) {
                    "Bold" -> sb.setSpan(StyleSpan(Typeface.BOLD), start, e, flag)
                    "Italic" -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, e, flag)
                    "Strike" -> sb.setSpan(StrikethroughSpan(), start, e, flag)
                    "Mono" -> sb.setSpan(TypefaceSpan("monospace"), start, e, flag)
                    "Spoiler" -> if (spoilerShown) sb.setSpan(BackgroundColorSpan((ink and 0x00FFFFFF) or (0x22 shl 24)), start, e, flag) else {
                        // hidden: text and background the same colour, tap to show
                        sb.setSpan(BackgroundColorSpan((ink and 0x00FFFFFF) or (0x99 shl 24)), start, e, flag)
                        sb.setSpan(ForegroundColorSpan(0), start, e, flag)
                        sb.setSpan(object : ClickableSpan() {
                            override fun onClick(v: View) = reveal()
                            override fun updateDrawState(ds: TextPaint) {}
                        }, start, e, flag)
                    }
                }
                from = e
            }
        }
        Linkify.addLinks(sb, Linkify.WEB_URLS)
        for (m in MENTION.findAll(sb)) if (m.groupValues[1] in people) {
            sb.setSpan(ForegroundColorSpan(accent), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun hasSpoiler(text: String) = Regex("(?s)\\|\\|(.+?)\\|\\|").containsMatchIn(text)

    /** Text with the markers taken out (chat list, notifications, reply quotes). Spoilers stay hidden. */
    fun plain(text: String): String {
        var t = Regex("(?s)\\|\\|(.+?)\\|\\|").replace(text) { "▒".repeat(minOf(it.groupValues[1].length, 12)) }
        for ((mark, _) in MARKS) t = Regex("(?s)" + Regex.escape(mark) + "(.+?)" + Regex.escape(mark)).replace(t) { it.groupValues[1] }
        return t
    }

    /** Wraps the selected text in [box] with [mark] (from the text-selection menu). */
    fun wrap(box: EditText, mark: String) {
        val a = box.selectionStart.coerceAtLeast(0); val b = box.selectionEnd.coerceAtLeast(0)
        val s = minOf(a, b); val e = maxOf(a, b)
        if (s == e) return
        box.text.insert(e, mark); box.text.insert(s, mark)
        box.setSelection(e + 2 * mark.length)
    }
}
