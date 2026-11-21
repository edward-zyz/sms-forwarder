package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class MessageFormatterTest {
    @Test fun extractsFourToSixDigitCodeIncludingFullwidthAndIgnoresLongNumbers() {
        assertEquals("618294", MessageFormatter.extractVerificationCode("【美团】您的登录验证码为 618294，5分钟内有效"))
        assertEquals("1234", MessageFormatter.extractVerificationCode("动态码：１２３４"))
        assertEquals("56789", MessageFormatter.extractVerificationCode("订单 2026112000012345 的取件校验码为 56789"))
        assertNull(MessageFormatter.extractVerificationCode("您的订单号 123456789 已发货"))
        assertNull(MessageFormatter.extractVerificationCode("短数字 123 不应被识别"))
        assertNull(MessageFormatter.extractVerificationCode("这是一条不含数字的通知短信"))
    }

    @Test fun formatsHeaderWithHighlightedCodeAndRuleName() {
        val formatted = MessageFormatter.format(
            ruleName = "美团外卖运营群",
            sender = "10690001",
            time = "2026-11-20 14:30:00",
            body = "【美团】您的登录验证码为 618294，请勿泄露。",
        )
        assertEquals(
            "短信转发｜618294（美团外卖运营群）\n【美团】您的登录验证码为 618294，请勿泄露。\n发件人：10690001 ｜ 2026-11-20 14:30:00",
            formatted,
        )
        assertTrue(formatted.startsWith("短信转发"))
    }

    @Test fun formatsHeaderWithoutCodeWhenNoVerificationDigitsFound() {
        val formatted = MessageFormatter.format(
            ruleName = "系统通知",
            sender = "10086",
            time = "2026-11-20 14:35:00",
            body = "【通知】您的单号 20261120999888 已签收。",
        )
        assertEquals(
            "短信转发（系统通知）\n【通知】您的单号 20261120999888 已签收。\n发件人：10086 ｜ 2026-11-20 14:35:00",
            formatted,
        )
        assertTrue(formatted.startsWith("短信转发"))
    }

    @Test fun fallsBackGracefullyWhenRuleNameOrSenderIsBlank() {
        val formatted = MessageFormatter.format(
            ruleName = "   ",
            sender = null,
            time = "2026-11-20 14:40:00",
            body = "验证码 4321",
        )
        assertEquals(
            "短信转发｜4321（默认规则）\n验证码 4321\n发件人：未知 ｜ 2026-11-20 14:40:00",
            formatted,
        )
    }

    @Test fun includesDeviceLabelWhenConfiguredAndOmitsWhenBlank() {
        val withLabel = MessageFormatter.format(
            ruleName = "外卖运营群",
            sender = "10690001",
            time = "2026-11-21 12:00:00",
            body = "【美团】验证码 482910",
            deviceLabel = "138****8888",
        )
        assertEquals(
            "短信转发｜482910（外卖运营群）\n接收手机：138****8888\n【美团】验证码 482910\n发件人：10690001 ｜ 2026-11-21 12:00:00",
            withLabel,
        )

        val blankLabel = MessageFormatter.format(
            ruleName = "外卖运营群",
            sender = "10690001",
            time = "2026-11-21 12:00:00",
            body = "【美团】验证码 482910",
            deviceLabel = "   \n  ",
        )
        assertEquals(
            "短信转发｜482910（外卖运营群）\n【美团】验证码 482910\n发件人：10690001 ｜ 2026-11-21 12:00:00",
            blankLabel,
        )
    }

    @Test fun sanitizesDeviceLabelNewlinesAndMaxLength() {
        assertEquals("浦东店 138****8888", MessageFormatter.sanitizeDeviceLabel("  浦东店\n138****8888\r\n "))
        val longText = "A".repeat(80)
        assertEquals(MessageFormatter.MAX_DEVICE_LABEL_LENGTH, MessageFormatter.sanitizeDeviceLabel(longText).length)
    }
}
