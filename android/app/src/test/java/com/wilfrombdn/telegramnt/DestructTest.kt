package com.wilfrombdn.telegramnt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructTest {
    private val hour = 3_600_000L

    @Test fun wipesOnlyAfterTheChosenTimeUnopened() {
        assertFalse("off", Destruct.due(now = 100 * hour, last = 0, hours = 0, inUse = false))
        assertFalse(Destruct.due(now = 10 * hour + hour - 1, last = 10 * hour, hours = 1, inUse = false))
        assertTrue(Destruct.due(now = 11 * hour, last = 10 * hour, hours = 1, inUse = false))
        assertFalse("a week isn't up after 6 days", Destruct.due(now = 6 * 24 * hour, last = 1, hours = 168, inUse = false))
        assertTrue(Destruct.due(now = 7 * 24 * hour + 1, last = 1, hours = 168, inUse = false))
    }

    @Test fun neverWhileOnScreenOrInACall() {
        assertFalse(Destruct.due(now = 1000 * hour, last = 1, hours = 1, inUse = true))
    }
}
