package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class FilterRulesTest {
    @Test fun defaultsAndDisabledPreserveOriginalBehavior() {
        assertTrue(FilterRules().evaluate(null, "普通短信").allowed)
        assertTrue(FilterRules(includeKeywords = listOf("验证码"), requireDigits = true,
            excludeKeywords = listOf("银行"), allowedSenders = listOf("10086"))
            .evaluate("10010", "银行公告").allowed)
    }

    @Test fun anyCombinesKeywordGroupAndDigitsAsAlternatives() {
        val rules = FilterRules(enabled = true, includeKeywords = listOf("验证码", "OTP"), requireDigits = true)
        assertTrue(rules.evaluate(null, "这是验证码").allowed)
        assertTrue(rules.evaluate(null, "Your otp is ready").allowed)
        assertTrue(rules.evaluate(null, "订单 12345 已受理").allowed)
        assertFalse(rules.evaluate(null, "普通公告").allowed)
    }

    @Test fun allRequiresBothGroupsButAnyKeywordWithinGroup() {
        val rules = FilterRules(enabled = true, includeKeywords = listOf("验证码", "otp"), requireDigits = true, matchAll = true)
        assertFalse(rules.evaluate(null, "验证码已发送").allowed)
        assertFalse(rules.evaluate(null, "1234").allowed)
        assertTrue(rules.evaluate(null, "OTP：你好。请使用 123456 完成登录").allowed)
        assertTrue(rules.evaluate(null, "12345。后面才有验证码字样").allowed)
    }

    @Test fun exclusionWinsOverBothPositiveGroupsAndSender() {
        val rules = FilterRules(enabled = true, includeKeywords = listOf("验证码"), requireDigits = true,
            excludeKeywords = listOf("银行", "BANK"), allowedSenders = listOf("10086"))
        assertFalse(rules.evaluate("10086", "银行验证码 1234").allowed)
        assertFalse(rules.evaluate("10086", "bank 验证码 1234").allowed)
        assertTrue(rules.evaluate("10086", "验证码 1234").allowed)
    }

    @Test fun emptyBodyConditionsPermitExclusionOnlyAndSenderOnlyRules() {
        assertTrue(FilterRules(enabled = true, matchAll = true).evaluate(null, "").allowed)
        val excludes = FilterRules(enabled = true, excludeKeywords = listOf("银行"))
        assertTrue(excludes.evaluate(null, "普通公告").allowed)
        assertFalse(excludes.evaluate(null, "银行公告").allowed)
        val senders = FilterRules(enabled = true, allowedSenders = listOf("10086"))
        assertTrue(senders.evaluate("10086", "普通公告").allowed)
        assertFalse(senders.evaluate("10010", "普通公告").allowed)
    }

    @Test fun onlyEnabledBodyConditionIsUsedRegardlessOfAllMode() {
        for (all in listOf(false, true)) {
            val words = FilterRules(enabled = true, includeKeywords = listOf("验证码"), matchAll = all)
            assertTrue(words.evaluate(null, "验证码").allowed)
            assertFalse(words.evaluate(null, "12345").allowed)
            val numbers = FilterRules(enabled = true, requireDigits = true, matchAll = all)
            assertTrue(numbers.evaluate(null, "12345").allowed)
            assertFalse(numbers.evaluate(null, "验证码").allowed)
        }
    }

    @Test fun digitsMustBeCompleteRunsOfFourToSixIncludingFullwidth() {
        val rules = FilterRules(enabled = true, requireDigits = true)
        listOf("1234", "a12345b", "编号(123456)", "１２３４", "验证码１２３４５６。", "12３４５").forEach {
            assertTrue(it, rules.evaluate(null, it).allowed)
        }
        listOf("", "123", "1234567", "12345678", "１２３４５６７", "abc1234567def", "123 456", "١1234", "1234٢").forEach {
            assertFalse(it, rules.evaluate(null, it).allowed)
        }
        assertTrue(rules.evaluate(null, "长编号123456789，另外有1234").allowed)
    }

    @Test fun senderFullMatchDoesNotUseSuffixOrSubstringMatching() {
        val rules = FilterRules(enabled = true, allowedSenders = listOf("10086", "13800138000"))
        assertTrue(rules.evaluate("10086", "").allowed)
        assertTrue(rules.evaluate("13800138000", "").allowed)
        listOf("010086", "100861", "8613800138000", "913800138000", "138001380001", "", " ", "other", null).forEach {
            assertFalse("source=$it", rules.evaluate(it, "验证码1234").allowed)
        }
    }

    @Test fun senderFormattingAndChineseMobileCountryCodesAreNormalized() {
        val rules = FilterRules(enabled = true, allowedSenders = listOf("＋８６ (１３８)－００１３－８０００"))
        listOf("13800138000", "+86 138-0013-8000", "0086（138）00138000", "１３８００１３８０００").forEach {
            assertTrue(it, rules.evaluate(it, "").allowed)
        }
        assertTrue(FilterRules(enabled = true, allowedSenders = listOf("(100)-86")).evaluate("１００８６", "").allowed)
    }

    @Test fun singleTrailingStarMatchesPrefixOnlyAndHandlesCountryCode() {
        val rules = FilterRules(enabled = true, allowedSenders = listOf("106*", "+86 138*"))
        listOf("106", "10690001", "13800138000", "008613800138000").forEach { assertTrue(it, rules.evaluate(it, "").allowed) }
        listOf("110690001", "13900138000", "0106", "106A").forEach { assertFalse(it, rules.evaluate(it, "").allowed) }
        assertTrue(FilterRules(enabled = true, allowedSenders = listOf("0086138*")).evaluate("13800138000", "").allowed)
    }

    @Test fun invalidPatternsAndLimitsAreRejectedEvenIfDisabled() {
        listOf("*", "", " ", "10*86", "106**", "*106", "+", "++86138", "abc", "10086?", "10086/", "10086,10010", "1".repeat(25)).forEach {
            val rules = FilterRules(allowedSenders = listOf(it))
            assertNotNull("pattern=$it", rules.validationError())
            assertFalse(rules.evaluate("10086", "验证码1234").allowed)
        }
        assertNotNull(FilterRules(includeKeywords = listOf(" ")).validationError())
        assertNotNull(FilterRules(excludeKeywords = listOf("x".repeat(65))).validationError())
        assertNotNull(FilterRules(includeKeywords = List(33) { "x" }).validationError())
        assertNotNull(FilterRules(allowedSenders = List(33) { "10086" }).validationError())
        assertNull(FilterRules(includeKeywords = List(32) { "x".repeat(64) }).validationError())
    }

    @Test fun countryCodeMobilePrefixesCannotMatchShortServiceNumbers() {
        for (pattern in listOf("+861*", "00861*", "+86138*", "0086138*")) {
            val rules = FilterRules(enabled = true, allowedSenders = listOf(pattern))
            listOf("10086", "10690001", "138", "+86138", "0086138").forEach {
                assertFalse("$pattern must reject $it", rules.evaluate(it, "").allowed)
            }
            listOf("13800138000", "+8613800138000", "008613800138000").forEach {
                assertTrue("$pattern must allow $it", rules.evaluate(it, "").allowed)
            }
        }
    }

    @Test fun reasonsNeverExposePrivateMessageSenderOrConfiguredWords() {
        val rules = FilterRules(enabled = true, excludeKeywords = listOf("私密词"), allowedSenders = listOf("13800138000"))
        for (result in listOf(rules.evaluate("13800138000", "私密词123456"), rules.evaluate("13900139000", "秘密正文"))) {
            assertFalse(result.allowed)
            listOf("私密词", "123456", "13800138000", "13900139000", "秘密正文").forEach { assertFalse(result.reason.contains(it)) }
        }
    }

    @Test fun requireCodeWordBlocksPromoNumbersAndMatchesCodeWordCaseInsensitively() {
        val rules = FilterRules(
            enabled = true,
            includeKeywords = listOf("美团", "开店宝"),
            requireDigits = true,
            matchAll = true,
            requireCodeWord = true,
        )
        assertFalse(rules.evaluate("10690001", "【美团】领 8888 元满减券").allowed)
        assertFalse(rules.evaluate("10690001", "【美团】验证码已发送，请注意查收").allowed)
        assertFalse(rules.evaluate("10690001", "【其他平台】验证码 123456").allowed)
        for (word in listOf("验证码", "动态码", "校验码", "安全码", "确认码", "登录码", "CODE", "Code")) {
            assertTrue(word, rules.evaluate("10690001", "【美团】您的 $word 是 618294").allowed)
        }
    }
}
