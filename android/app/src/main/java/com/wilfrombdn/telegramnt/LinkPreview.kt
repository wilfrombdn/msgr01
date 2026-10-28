package com.wilfrombdn.telegramnt

import org.json.JSONObject
import java.io.IOException
import java.net.URI

/**
 * Link previews. The SENDER's phone fetches the page (through Tor, so the site never sees the real
 * address) and sends a small card with the message: {u: link, s: site name, t: title, d: description}.
 * The person receiving it doesn't fetch anything.
 */
object LinkPreview {
    private val URL = android.util.Patterns.WEB_URL

    /** The first web link in [text], with https:// added if it was left off. */
    fun firstUrl(text: String): String? {
        val m = URL.matcher(text)
        while (m.find()) {
            val u = m.group()
            if (!u.contains('.')) continue
            return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "https://$u"
        }
        return null
    }

    /** Fetches [url] through Tor and reads its title/description. Network: off the main thread. */
    fun fetch(url: String): JSONObject? {
        var u = url
        repeat(4) {   // follow a few redirects (http -> https, www, short links)
            val uri = URI(u)
            val tls = uri.scheme.equals("https", true)
            val route = Net.Route(uri.host ?: return null, if (uri.port != -1) uri.port else if (tls) 443 else 80, tls,
                Tor.socksPort() ?: throw IOException("Tor not ready"))
            val path = (uri.rawPath ?: "").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val r = Net.peek(route, path, 400_000)
            if (r.code in 300..399 && r.location != null) { u = uri.resolve(r.location).toString(); return@repeat }
            if (r.code != 200) return null
            return parse(u, String(r.body, Charsets.UTF_8))
        }
        return null
    }

    fun parse(url: String, html: String): JSONObject? {
        fun meta(vararg names: String): String? {
            for (n in names) {
                val re = Regex("<meta[^>]+(?:property|name)\\s*=\\s*[\"']" + Regex.escape(n) + "[\"'][^>]*>", RegexOption.IGNORE_CASE)
                val tag = re.find(html)?.value ?: continue
                Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.let { return unescape(it.groupValues[1]) }
            }
            return null
        }
        val title = meta("og:title", "twitter:title")
            ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { unescape(it) }
        val t = title?.trim()?.replace(Regex("\\s+"), " ")?.take(200)
        if (t.isNullOrEmpty()) return null
        val site = (meta("og:site_name") ?: runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: "").take(60)
        val d = (meta("og:description", "description", "twitter:description") ?: "").trim().replace(Regex("\\s+"), " ").take(300)
        return JSONObject().put("u", url.take(500)).put("s", site).put("t", t).put("d", d)
    }

    /** A card off the wire: only short, plain fields and a web link are kept. */
    fun clean(o: JSONObject?): JSONObject? {
        o ?: return null
        val u = o.optString("u").take(500)
        if (!u.startsWith("https://") && !u.startsWith("http://")) return null
        val t = o.optString("t").take(200).ifBlank { return null }
        return JSONObject().put("u", u).put("s", o.optString("s").take(60)).put("t", t).put("d", o.optString("d").take(300))
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
}
