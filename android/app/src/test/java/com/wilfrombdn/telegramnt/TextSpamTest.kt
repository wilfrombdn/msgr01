package com.wilfrombdn.telegramnt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSpamTest {
    @Test fun typicalScamsAreCaught() {
        listOf(
            "USPS: Your package could not be delivered. Update your address at usps-redeliver.top/abc",
            "Congratulations! You've won a \$1000 Walmart gift card. Claim now: bit.ly/3xYz",
            "Your account has been locked. Verify your identity at https://secure-login.example",
            "FINAL NOTICE: unpaid toll balance of \$6.99. Pay today to avoid fees",
            "Get pre-approved for a loan offer today! Reply STOP to opt out",
            "Canada Post: delivery attempt failed, reschedule here www.cp-track.info",
        ).forEach { assertTrue(it, Texts.looksLikeSpam(it)) }
    }

    @Test fun normalTextsAreLeftAlone() {
        listOf(
            "Are you coming tonight?",
            "Your verification code is 482913",
            "running 10 min late, sorry",
            "Can you pick up milk on the way home",
            "Dinner at 7? I booked the table",
            "ok sounds good",
        ).forEach { assertFalse(it, Texts.looksLikeSpam(it)) }
    }

    @Test fun yourChoiceWins() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scam = "You won a prize! claim now"
        assertTrue(Texts.spamVerdict(app, 5, listOf("+15550001111"), scam, replied = false))
        assertFalse("replied threads are never spam", Texts.spamVerdict(app, 5, listOf("+15550001111"), scam, replied = true))
        Texts.markSpam(app, 5, false)
        assertFalse(Texts.spamVerdict(app, 5, listOf("+15550001111"), scam, replied = false))
        Texts.markSpam(app, 6, true)
        assertTrue(Texts.spamVerdict(app, 6, listOf("+15550002222"), "hi", replied = true))
    }
}
