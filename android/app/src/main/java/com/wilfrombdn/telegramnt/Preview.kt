package com.wilfrombdn.telegramnt

import org.json.JSONObject

// One-line descriptions of messages, shared by the chat list, replies and notifications.

/** A round "video message" (recorded in the app), as opposed to an ordinary video file. */
fun isRound(m: Msg) = m.file?.optString("m") == "video/mp4" && m.file?.optString("n")?.startsWith("Video message") == true

/** What a call summary says, from this phone's side ("Missed call", "No answer", …). */
fun callTitle(k: JSONObject): String {
    val out = k.optString("d") == "out"
    return when (k.optString("s")) {
        "ok" -> if (out) T("Outgoing call") else T("Incoming call")
        "missed" -> T("Missed call")
        "declined" -> if (out) T("Call declined") else T("Declined call")
        "cancelled" -> T("Cancelled call")
        "busy" -> T("Line busy")
        "noanswer" -> T("No answer")
        else -> T("Call failed")
    }
}

fun callDuration(sec: Int) = when {
    sec >= 3600 -> T("%d h %d min").format(sec / 3600, sec % 3600 / 60)
    sec >= 60 -> T("%d min %d s").format(sec / 60, sec % 60)
    else -> "$sec s"
}

/** The message as one line of plain text (formatting markers removed). */
fun preview(m: Msg): String = Format.plain(previewRaw(m))

fun previewRaw(m: Msg): String {
    if (m.loc != null) return T("📍 Location")
    if (isRound(m)) return T("📹 Video message")
    m.poll?.let { return "📊 " + it.optString("q") }
    m.call?.let { return callTitle(it) + if (it.optString("s") == "ok") " (${callDuration(it.optInt("dur"))})" else "" }
    val f = m.file ?: return m.text
    if (Voice.isVoice(f)) return T("🎤 Voice message") + (Voice.unpack(f.optString("th"))?.let { " (${Voice.clock(it.first)})" } ?: "")
    if (f.optBoolean("vo")) return T("📷 View once photo")
    val mime = f.optString("m")
    return when {
        mime == "image/gif" -> "GIF"
        mime == "image/webp" -> T("Sticker")
        mime.startsWith("image/") -> T("Photo")
        mime.startsWith("video/") -> T("Video")
        else -> T("File: ") + f.optString("n")
    }
}
