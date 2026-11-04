package com.wilfrombdn.telegramnt

import org.json.JSONObject

// One-line descriptions of messages, shared by the chat list, replies and notifications.

/** A round "video message" (recorded in the app), as opposed to an ordinary video file. */
fun isRound(m: Msg) = m.file?.optString("m") == "video/mp4" && m.file?.optString("n")?.startsWith("Video message") == true

/** What a call summary says, from this phone's side ("Missed call", "No answer", …). */
fun callTitle(k: JSONObject): String {
    val out = k.optString("d") == "out"
    return when (k.optString("s")) {
        "ok" -> if (out) "Outgoing call" else "Incoming call"
        "missed" -> "Missed call"
        "declined" -> if (out) "Call declined" else "Declined call"
        "cancelled" -> "Cancelled call"
        "busy" -> "Line busy"
        "noanswer" -> "No answer"
        else -> "Call failed"
    }
}

fun callDuration(sec: Int) = when {
    sec >= 3600 -> "%d h %d min".format(sec / 3600, sec % 3600 / 60)
    sec >= 60 -> "%d min %d s".format(sec / 60, sec % 60)
    else -> "$sec s"
}

/** The message as one line of plain text (formatting markers removed). */
fun preview(m: Msg): String = Format.plain(previewRaw(m))

fun previewRaw(m: Msg): String {
    if (m.loc != null) return "📍 Location"
    if (isRound(m)) return "📹 Video message"
    m.poll?.let { return "📊 " + it.optString("q") }
    m.call?.let { return callTitle(it) + if (it.optString("s") == "ok") " (${callDuration(it.optInt("dur"))})" else "" }
    val f = m.file ?: return m.text
    if (Voice.isVoice(f)) return "🎤 Voice message" + (Voice.unpack(f.optString("th"))?.let { " (${Voice.clock(it.first)})" } ?: "")
    if (f.optBoolean("vo")) return "📷 View once photo"
    val mime = f.optString("m")
    return when {
        mime == "image/gif" -> "GIF"
        mime == "image/webp" -> "Sticker"
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        else -> "File: " + f.optString("n")
    }
}
