package com.wilfrombdn.telegramnt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTextTest {
    @Test fun verificationCodesAreRecognised() {
        assertTrue(CodeText.matches("Your verification code is 482913. Don't share it."))
        assertTrue(CodeText.matches("G-123456 is your Google verification code."))
        assertTrue(CodeText.matches("Use 5521 as your login PIN"))
        assertTrue(CodeText.matches("123-456 is your security code"))
    }
    @Test fun normalTextsAreNot() {
        assertFalse(CodeText.matches("See you at 7, bring the code for the garage"))
        assertFalse(CodeText.matches("Your order 12345678 shipped"))
        assertFalse(CodeText.matches("lol ok"))
    }
}
