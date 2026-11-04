package com.wilfrombdn.telegramnt

import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Translations of what people read on screen. English is the source: the code keeps its English text, wrapped as T("..."),
 * and this looks it up in assets/i18n/<language>.json. Anything without a translation is shown in English, so a missing line
 * is never blank or broken, and English itself goes straight through untouched.
 *
 * A line with names or numbers in it is stored as a template: "Delete chat with {0}?" matches "Delete chat with @sam?" and
 * the translation's {0} is filled with "@sam". (The code builds the English sentence first, then asks for its translation.)
 */
object I18n {
    /** Language code to the name shown for it in its own language. */
    val SUPPORTED = listOf("en" to "English", "ru" to "Русский", "uk" to "Українська")

    @Volatile var lang: String = fromSystem()

    /** Reads assets/i18n/<code>.json; set by the app at start (tests have none, so they stay English). */
    @Volatile var loader: (String) -> String? = { null }

    private class Template(val re: Regex, val out: String, val weight: Int)

    private val PLACEHOLDER = Regex("\\{(\\d+)}")
    @Volatile private var loadedFor = ""
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var templates: List<Template> = emptyList()
    private val cache = ConcurrentHashMap<String, String>()

    /** The phone's own language if it's one we have, else English. */
    fun fromSystem(): String = Locale.getDefault().language.takeIf { code -> SUPPORTED.any { it.first == code } } ?: "en"

    fun setLanguage(code: String) {
        lang = if (SUPPORTED.any { it.first == code }) code else "en"
        cache.clear(); loadedFor = ""
    }

    fun t(s: String): String {
        val l = lang
        if (l == "en" || s.isEmpty()) return s
        if (loadedFor != l) load(l)
        exact[s]?.let { return it }
        cache[s]?.let { return it }
        var out = s
        for (tpl in templates) {
            val m = tpl.re.matchEntire(s) ?: continue
            out = PLACEHOLDER.replace(tpl.out) { p -> m.groupValues.getOrNull(p.groupValues[1].toInt() + 1) ?: p.value }
            break
        }
        if (cache.size > 3000) cache.clear()   // names and numbers make endless variations; the table is rebuilt cheaply
        cache[s] = out
        return out
    }

    /**
     * "3 chats" in the right form. English picks [one] or [other]; Russian and Ukrainian have three forms (1 чат, 2 чата, 5 чатов), kept
     * in the language file as "{0} чат|{0} чата|{0} чатов" under the key "#plural:chat|chats".
     */
    fun plural(n: Int, one: String, other: String): String {
        val english = if (n == 1) "$n $one" else "$n $other"
        val l = lang
        if (l == "en") return english
        if (loadedFor != l) load(l)
        val forms = exact["#plural:$one|$other"]?.split("|") ?: return english
        val i = if (l == "ru" || l == "uk") {
            val m10 = n % 10; val m100 = n % 100
            if (m10 == 1 && m100 != 11) 0 else if (m10 in 2..4 && m100 !in 12..14) 1 else 2
        } else if (n == 1) 0 else 1
        return (forms.getOrNull(i) ?: forms.last()).replace("{0}", n.toString())
    }

    @Synchronized private fun load(l: String) {
        if (loadedFor == l) return
        val text = runCatching { loader(l) }.getOrNull()
        val table = HashMap<String, String>(); val list = ArrayList<Template>()
        if (text != null) runCatching {
            val o = JSONObject(text)
            for (key in o.keys()) {
                val value = o.optString(key).takeIf { it.isNotEmpty() } ?: continue
                if (!PLACEHOLDER.containsMatchIn(key)) { table[key] = value; continue }
                val parts = key.split(PLACEHOLDER)
                val re = Regex(parts.joinToString("(.+?)") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL)
                list.add(Template(re, value, parts.sumOf { it.length }))
            }
        }
        list.sortByDescending { it.weight }   // the most specific sentence wins
        exact = table; templates = list; cache.clear(); loadedFor = l
    }
}

/** Shorthand used all over the screens: the text in the chosen language (or English if it has none). */
fun T(s: String): String = I18n.t(s)

/** "1 chat" / "3 chats" in the chosen language. */
fun TP(n: Int, one: String, other: String): String = I18n.plural(n, one, other)
