package com.wilfrombdn.telegramnt

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFormatTest {
    @Test fun northAmericanNumbersFormatLikePlusOne() {
        assertEquals("+1 (555) 123-4567", PhoneFormat.pretty("5551234567", "US"))
        assertEquals("+1 (555) 123-4567", PhoneFormat.pretty("15551234567", "US"))
        assertEquals("+1 (555) 123-4567", PhoneFormat.pretty("+15551234567", "CA"))
    }
    @Test fun partialNumbersFormatAsFarAsTheyGo() {
        assertEquals("911", PhoneFormat.pretty("911", "US"))
        assertEquals("+1 (555) 1", PhoneFormat.pretty("5551", "US"))
        assertEquals("+1 (555) 123", PhoneFormat.pretty("555123", "US"))
        assertEquals("+1 (555) 123-45", PhoneFormat.pretty("55512345", "US"))
        assertEquals("+1", PhoneFormat.pretty("+1", "US"))
    }
    @Test fun dialableKeepsOnlyDigitsAndPlus() {
        assertEquals("+15551234567", PhoneFormat.dialable("+1 (555) 123-4567"))
        assertEquals("5551234567", PhoneFormat.dialable("(555) 123-4567"))
    }
}
