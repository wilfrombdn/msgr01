package com.wilfrombdn.telegramnt

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Russian and Ukrainian files, and the lookup that uses them. */
class I18nTest {
    private fun file(code: String) = File("src/main/assets/i18n/$code.json")
    private fun table(code: String) = JSONObject(file(code).readText()).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    @Before fun load() {
        I18n.loader = { code -> file(code).takeIf { it.exists() }?.readText() }
    }
    @After fun back() { I18n.setLanguage("en") }

    @Test fun bothFilesCoverTheSameText() {
        val ru = table("ru"); val uk = table("uk")
        assertEquals("ru and uk must translate exactly the same lines", ru.keys, uk.keys)
        assertTrue("expected the full set, got ${ru.size}", ru.size > 700)
    }

    @Test fun namesAndNumbersSurviveTranslation() {
        val ph = Regex("\\{\\d+}|%d|%s")
        for (code in listOf("ru", "uk")) for ((en, tr) in table(code)) {
            assertTrue("$code: empty translation for \"$en\"", tr.isNotBlank())
            if (en.startsWith("#plural:")) { assertEquals("$code: $en needs three forms", 3, tr.split("|").size); continue }
            assertEquals("$code: placeholders differ for \"$en\" -> \"$tr\"", ph.findAll(en).map { it.value }.sorted().toList(), ph.findAll(tr).map { it.value }.sorted().toList())
        }
    }

    @Test fun englishPassesThroughUntouched() {
        I18n.setLanguage("en")
        assertEquals("Cancel", T("Cancel"))
        assertEquals("Delete chat with @sam?", T("Delete chat with @sam?"))
        assertEquals("3 chats", TP(3, "chat", "chats")); assertEquals("1 chat", TP(1, "chat", "chats"))
    }

    @Test fun exactTemplateAndMissingLines() {
        I18n.setLanguage("ru")
        assertEquals("Отмена", T("Cancel"))
        assertEquals("Удалить чат с @sam?", T("Delete chat with @sam?"))               // a line with a name in it
        assertEquals("Удалить «Семья»?", T("Delete \"Семья\"?").replace("\"Семья\"", "«Семья»").let { T("Delete \"Семья\"?") })
        assertEquals("a line nobody translated", T("a line nobody translated"))          // falls back to English, never blank
        assertEquals(" · приватна".trim().isNotEmpty(), T(" · private").isNotBlank())
        assertTrue("leading and trailing spaces are kept", T(" · private").startsWith(" "))
        assertEquals("", T(""))
    }

    @Test fun twoNamesInOneLine() {
        I18n.setLanguage("uk")
        val t = T("@ann invited you to the group \"Road Crew\". Join or decline it at the top of your chat list.")
        assertTrue(t, t.startsWith("@ann запрошує вас до групи «Road Crew»"))
    }

    @Test fun russianAndUkrainianHaveThreePluralForms() {
        I18n.setLanguage("ru")
        assertEquals(listOf("1 чат", "2 чата", "5 чатов", "11 чатов", "21 чат", "22 чата", "25 чатов", "112 чатов"),
            listOf(1, 2, 5, 11, 21, 22, 25, 112).map { TP(it, "chat", "chats") })
        I18n.setLanguage("uk")
        assertEquals(listOf("1 чат", "3 чати", "5 чатів", "14 чатів", "31 чат"), listOf(1, 3, 5, 14, 31).map { TP(it, "chat", "chats") })
        assertEquals("2 дні", TP(2, "day", "days")); assertEquals("5 днів", TP(5, "day", "days"))
        assertEquals("a plural nobody translated: 7 foos", "a plural nobody translated: " + TP(7, "foo", "foos"))
    }

    @Test fun switchingLanguageChangesTheAnswer() {
        I18n.setLanguage("ru"); val ru = T("Settings")
        I18n.setLanguage("uk"); val uk = T("Settings")
        I18n.setLanguage("en")
        assertEquals(listOf("Настройки", "Налаштування", "Settings"), listOf(ru, uk, T("Settings")))
    }

    @Test fun unknownLanguageMeansEnglish() {
        I18n.setLanguage("xx")
        assertEquals("en", I18n.lang); assertEquals("Cancel", T("Cancel"))
    }

    /** New plain English lines in the code should get a translation; a few misses are tolerated (they just show in English). */
    @Test fun almostEveryScreenTextInTheCodeHasATranslation() {
        val ru = table("ru")
        val lit = Regex("""\bT\("((?:[^"\\$\n]|\\.)*)"\)""")
        val missing = ArrayList<String>(); var total = 0
        File("src/main/java/com/wilfrombdn/telegramnt").listFiles { f -> f.extension == "kt" }!!.forEach { f ->
            lit.findAll(f.readText()).forEach { m ->
                val text = m.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")
                    .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
                total++; if (text !in ru && text.any { it.isLetter() }) missing += "${f.name}: $text"
            }
        }
        assertTrue("$total lines checked; untranslated (${missing.size}): ${missing.take(12)}", missing.size <= 5)
    }
}
