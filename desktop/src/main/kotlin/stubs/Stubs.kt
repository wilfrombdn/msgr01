package com.wilfrombdn.telegramnt

import org.json.JSONObject

/** Desktop stand-ins for the two Android-side helpers the shared engine calls. */
object Format {
    val MENTION = Regex("@([a-z0-9_]{5,32})")
}

object LinkPreview {
    fun clean(o: JSONObject?): JSONObject? {
        o ?: return null
        val u = o.optString("u").take(500)
        if (!u.startsWith("https://") && !u.startsWith("http://")) return null
        val t = o.optString("t").take(200).ifBlank { return null }
        return JSONObject().put("u", u).put("s", o.optString("s").take(60)).put("t", t).put("d", o.optString("d").take(300))
    }
}
