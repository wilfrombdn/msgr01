package com.wilfrombdn.telegramnt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRulesTest {
    private fun bad(pw: String, user: String = "someone_x") = assertNotNull("should refuse: $pw", PasswordRules.check(user, pw))
    private fun good(pw: String, user: String = "someone_x") = assertNull("should accept: $pw -> ${PasswordRules.check(user, pw)}", PasswordRules.check(user, pw))

    @Test fun tooShort() { bad("Ab1!Ab1!Ab1"); assertTrue(PasswordRules.check("u_user", "short")!!.contains("12")) }

    @Test fun commonPasswordsAndEasyVariations() {
        for (p in listOf("password1234", "Password123456", "P@ssw0rd!1234", "qwertyuiop123", "iloveyou12345", "letmein123456", "football1234567",
            "welcomewelcome", "passwordpassword", "correcthorsebatterystaple", "123456789012", "1234567890123456", "tellem123456789", "adminadminadmin"))
            bad(p)
    }

    @Test fun patterns() {
        for (p in listOf("aaaaaaaaaaaa", "abababababab", "abcabcabcabc", "abcdefghijkl", "qwertyuiopas", "zxcvbnmasdfg", "987654321098"))
            bad(p)
    }

    @Test fun cantContainTheUsername() {
        bad("my-alice_smith-secret", user = "alice_smith")
        good("my-purple-giraffe-secret", user = "alice_smith")
    }

    @Test fun needsVarietyUnlessItIsALongPassphrase() {
        bad("onlylowercasex")                  // 14 characters, one kind
        good("Tr0ub4dor&3xyz")                 // 14, mixed kinds
        good("correct horse battery")          // long passphrase
        good("purple-giraffe-eats-pizza")
        good("My dog Rex is 7 years old!")
    }

    @Test fun ordinaryGoodOnesPass() {
        good("als long password"); good("another long password"); good("daves long password")
    }
}
