package com.wilfrombdn.telegramnt

/**
 * Rules for NEW passwords (sign-up). The password guards the backup that holds every private key, and anyone who ever gets a
 * copy of the server's database can try guesses offline, so it has to be hard to guess, not just long. Logging in accepts
 * whatever an old account already has.
 */
object PasswordRules {
    const val MIN_LENGTH = 12
    /** Long enough to count as a passphrase without mixing letter kinds ("correct horse battery staple"). */
    const val PASSPHRASE_LENGTH = 16

    /** A plain-words reason the password isn't good enough, or null when it is. */
    fun check(user: String, password: String): String? {
        val chars = password.codePointCount(0, password.length)
        if (chars < MIN_LENGTH) return T("Password needs at least $MIN_LENGTH characters")
        val lower = password.lowercase()
        if (user.length >= 4 && lower.contains(user.lowercase())) return T("Your password can't contain your username")
        if (password.toSet().size < 6) return T("That password repeats too much. Mix it up.")
        if (password.all { it.isDigit() }) return T("A password can't be only numbers")
        // Four views of the same password, so tricks don't get it past the list: as typed, with letter swaps undone
        // (p@ssw0rd -> password), and each of those again without trailing digits/symbols ("Password123456" -> password).
        val typed = lower.filter { it.isLetterOrDigit() }
        val swapped = normalize(lower)
        val headOfTyped = lower.trimEnd { !it.isLetter() }.filter { it.isLetterOrDigit() }
        val head = normalize(lower.trimEnd { !it.isLetter() })
        if (listOf(typed, swapped, headOfTyped, head).any { it.isNotEmpty() && it in COMMON } ||
            BASES.any { b -> head == b || head == b + b || head == b + b + b })
            return T("That password is too common. Pick something only you would think of.")
        val plain = swapped
        if (isRun(plain) || isRun(typed) || isRepeatedBlock(plain)) return T("That password is a simple pattern. Pick something less predictable.")
        if (chars < PASSPHRASE_LENGTH && classes(password) < 3)
            return T("Mix upper and lower case, numbers or symbols, or make it $PASSPHRASE_LENGTH+ characters (a few random words works well)")
        return null
    }

    private fun classes(p: String): Int =
        listOf(p.any { it.isLowerCase() }, p.any { it.isUpperCase() }, p.any { it.isDigit() }, p.any { !it.isLetterOrDigit() }).count { it }

    /** Lower case, common letter swaps undone (p@ssw0rd -> password), anything not a letter or digit dropped. */
    internal fun normalize(p: String): String = buildString {
        for (c in p.lowercase()) append(when (c) { '@' -> 'a'; '0' -> 'o'; '$' -> 's'; '!' -> 'i'; '3' -> 'e'; '7' -> 't'; else -> c })
    }.filter { it.isLetterOrDigit() }

    /** abcdefghijkl, 123456789012, qwertyuiopas: nearly every step is +1 (or -1) along the alphabet, digits or a keyboard row. */
    private fun isRun(s: String): Boolean {
        if (s.length < 8) return false
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "abcdefghijklmnopqrstuvwxyz", "0123456789")
        val steps = (1 until s.length).count { i -> val d = s[i] - s[i - 1]; d == 1 || d == -1 }
        if (steps >= s.length - 2) return true
        return rows.any { row -> s.length >= 8 && (row.contains(s.take(8)) || row.reversed().contains(s.take(8))) }
    }

    /** "abcabcabcabc", "passpasspass": one short block repeated. */
    private fun isRepeatedBlock(s: String): Boolean =
        (1..s.length / 2).any { n -> s.length % n == 0 && s.length / n >= 3 && s == s.take(n).repeat(s.length / n) }

    // Words people build passwords from; "<word>" + digits/symbols and "<word><word>" are refused too.
    private val BASES = listOf(
        "password", "passw0rd", "letmein", "welcome", "iloveyou", "qwerty", "qwertyuiop", "asdfgh", "zxcvbn", "admin", "administrator",
        "login", "master", "monkey", "dragon", "football", "baseball", "basketball", "soccer", "hockey", "sunshine", "princess",
        "superman", "batman", "spiderman", "starwars", "trustno", "shadow", "michael", "jennifer", "jordan", "hunter", "freedom",
        "whatever", "computer", "internet", "samsung", "iphone", "android", "google", "facebook", "tellem", "messenger", "secret",
        "changeme", "default", "summer", "winter", "spring", "autumn", "pokemon", "minecraft", "fortnite", "loveyou", "mustang",
        "ranger", "buster", "killer", "ginger", "pepper", "cookie", "banana", "chocolate", "mypassword", "thepassword", "abc",
    )

    // Whole passwords that show up in every breach list at 12+ characters (after [normalize]).
    private val COMMON = setOf(
        "correcthorsebatterystaple", "passwordpassword", "qwertyuiopasdf", "1234567890123", "123456789012", "1234567890123456",
        "iloveyouforever", "letmeinplease", "welcometomyworld", "thequickbrownfox", "thisismypassword", "mynameisjohn",
        "abcdefghijkl", "abcdefghijklmnop", "qwertyuiopas", "qwertyuiopasdfghjkl", "asdfghjklzxcv", "zaq12wsxcde3",
        "1q2w3e4r5t6y", "1qaz2wsx3edc", "qazwsxedcrfv", "q1w2e3r4t5y6", "passwordpassword1", "administrator1", "trustno1trustno1",
        "monkeymonkeymonkey", "sunshinesunshine", "iloveyouiloveyou", "football1234", "baseball1234",
    ).flatMap { listOf(it, it.trimEnd { c -> !c.isLetter() }) }.filter { it.isNotEmpty() }.toSet()
}
