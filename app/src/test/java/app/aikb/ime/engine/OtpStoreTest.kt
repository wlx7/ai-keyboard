package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OtpStoreTest {

    @Test
    fun extractsStandardChineseAndEnglishVerificationCodes() {
        val sms1 = OtpStore.extractOtp("信息", "【阿里云】您的登录验证码是 2046，请勿泄露给他人。")
        assertNotNull(sms1)
        assertEquals("2046", sms1!!.code)
        assertEquals("阿里云", sms1.source)

        // Must pick actual OTP (394021) instead of card tail digits (尾号8899) or duration (5分钟)
        val bankSms = OtpStore.extractOtp("95555", "【招商银行】您尾号8899的储蓄卡正在登录，动态验证码394021，5分钟内有效。")
        assertNotNull(bankSms)
        assertEquals("394021", bankSms!!.code)
        assertEquals("招商银行", bankSms.source)

        val enSms = OtpStore.extractOtp("GitHub", "Your GitHub verification code is 849201.")
        assertNotNull(enSms)
        assertEquals("849201", enSms!!.code)
        assertEquals("GitHub", enSms.source)

        val colonSms = OtpStore.extractOtp("Google", "G-482910 is your Google verification code: 482910.")
        assertNotNull(colonSms)
        assertEquals("482910", colonSms!!.code)
    }

    @Test
    fun ignoresPlainNumbersWithoutOtpContextAndExpiresAfter90s() {
        assertNull(OtpStore.extractOtp("顺丰速运", "您的快递单号 20261119 已派送，预计今天 18:30 送达"))
        assertNull(OtpStore.extractOtp("备忘录", "今天是 2026 年 11 月 19 日，消费 1280 元"))

        OtpStore.onCandidateText("信息", "验证码：654321", "信息", nowMs = 1_000L)
        assertEquals("654321", OtpStore.activeOtp(nowMs = 50_000L)?.code)
        assertNull(OtpStore.activeOtp(nowMs = 95_000L))
    }
}
