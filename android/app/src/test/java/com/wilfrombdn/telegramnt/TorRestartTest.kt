package com.wilfrombdn.telegramnt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRestartTest {
    private val min = 60_000L

    @Test fun waitsThreeMinutesBeforeFirstRestart() {
        assertFalse(Tor.restartDue(now = 2 * min, lastOk = 0, lastRestart = 0, restarts = 0))
        assertTrue(Tor.restartDue(now = 3 * min, lastOk = 0, lastRestart = 0, restarts = 0))
    }

    @Test fun spacesRestartsOutWhileServerStaysDown() {
        // second restart 10 minutes after the first
        assertFalse(Tor.restartDue(now = 12 * min, lastOk = 0, lastRestart = 3 * min, restarts = 1))
        assertTrue(Tor.restartDue(now = 13 * min, lastOk = 0, lastRestart = 3 * min, restarts = 1))
        // then every 30 minutes, however long the server is off
        assertFalse(Tor.restartDue(now = 40 * min, lastOk = 0, lastRestart = 13 * min, restarts = 2))
        assertTrue(Tor.restartDue(now = 43 * min, lastOk = 0, lastRestart = 13 * min, restarts = 2))
        assertTrue(Tor.restartDue(now = 600 * min, lastOk = 0, lastRestart = 570 * min, restarts = 20))
    }

    @Test fun neverRestartsSoonAfterServerAnswered() {
        assertFalse(Tor.restartDue(now = 100 * min, lastOk = 99 * min, lastRestart = 0, restarts = 0))
    }
}
