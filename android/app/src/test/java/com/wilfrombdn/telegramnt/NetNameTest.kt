package com.wilfrombdn.telegramnt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The certificate-name rules behind the TLS hostname check. */
class NetNameTest {
    @Test fun exactNamesMatchIgnoringCase() {
        assertTrue(Net.nameMatches("wilfrombdn.github.io", "wilfrombdn.github.io"))
        assertTrue(Net.nameMatches("WILFROMBDN.github.io", "wilfrombdn.GITHUB.io."))
        assertFalse(Net.nameMatches("wilfrombdn.github.io", "github.io"))
        assertFalse(Net.nameMatches("evil.example", "wilfrombdn.github.io"))
        assertFalse(Net.nameMatches("", ""))
    }

    @Test fun wildcardsStandForExactlyOneLabel() {
        assertTrue(Net.nameMatches("a-b-c.trycloudflare.com", "*.trycloudflare.com"))
        assertFalse("not the bare domain", Net.nameMatches("trycloudflare.com", "*.trycloudflare.com"))
        assertFalse("not two labels", Net.nameMatches("x.y.trycloudflare.com", "*.trycloudflare.com"))
        assertFalse("never a whole TLD", Net.nameMatches("evil.com", "*.com"))
        assertFalse("not look-alike suffixes", Net.nameMatches("eviltrycloudflare.com", "*.trycloudflare.com"))
        assertFalse("not wildcard in the middle", Net.nameMatches("a.b.example.com", "a.*.example.com"))
        assertFalse("host on someone else's domain", Net.nameMatches("a.trycloudflare.com.evil.example", "*.trycloudflare.com"))
    }
}
