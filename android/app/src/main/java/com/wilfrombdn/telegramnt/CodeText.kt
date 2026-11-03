package com.wilfrombdn.telegramnt

/** Recognises one-time verification / two-factor code texts. */
object CodeText {
    private val WORDS = Regex("""\b(verification|verify|security|login|log-?in|sign-?in|one-?time|authentication|confirmation|2fa|otp|passcode|access) ?(code|pin|number)?\b|\b(code|pin)\b""", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("""(?<![\d-])\d{4,8}(?![\d-])|\b\d{3}[- ]\d{3}\b|\bG-\d{6}\b""")
    fun matches(text: String) = text.length < 300 && DIGITS.containsMatchIn(text) && WORDS.containsMatchIn(text)
}
