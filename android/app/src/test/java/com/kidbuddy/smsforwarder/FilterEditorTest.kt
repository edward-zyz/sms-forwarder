package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class FilterEditorTest {
    private fun legacyCustom(all: Boolean) = FilterRules(
        enabled = true,
        includeKeywords = listOf("取餐码", "OTP", "OTP"),
        requireDigits = true,
        matchAll = all,
        excludeKeywords = listOf("银行", "private"),
        allowedSenders = listOf("106*", "+8613800138000"),
    )

    @Test fun defaultNewRuleStartsInVerificationModeWithAllVerification() {
        val editor = FilterEditor.forNewRule()
        assertEquals(FilterEditor.Mode.VERIFICATION, editor.mode)
        assertEquals(setOf(PlatformTemplates.ALL_VERIFICATION_ID), editor.selectedPlatforms)
        assertTrue(editor.rules.enabled)
        assertTrue(editor.rules.requireCodeWord)
        assertTrue(editor.rules.requireDigits)
        assertTrue(editor.rules.matchAll)
        assertEquals(0, editor.advancedSummary().count)
    }

    @Test fun selectingPlatformsStaysInVerificationModeAndNeverJumpsMode() {
        val editor = FilterEditor.forNewRule()
        editor.senders(listOf("106*"))

        editor.togglePlatform("meituan", checked = true)
        assertEquals(FilterEditor.Mode.VERIFICATION, editor.mode)
        assertEquals(setOf("meituan"), editor.selectedPlatforms)
        assertEquals(listOf("美团", "开店宝", "美团外卖"), editor.rules.includeKeywords)
        assertEquals(listOf("106*"), editor.rules.allowedSenders)
        assertEquals(1, editor.advancedSummary().count)

        editor.togglePlatform("pdd", checked = true)
        assertEquals(FilterEditor.Mode.VERIFICATION, editor.mode)
        assertEquals(setOf("meituan", "pdd"), editor.selectedPlatforms)
        assertEquals(listOf("美团", "开店宝", "美团外卖", "拼多多"), editor.rules.includeKeywords)

        // 取消全部具体平台后，自动回退到「全部验证码」并保持在 VERIFICATION 模式。
        editor.togglePlatform("meituan", checked = false)
        editor.togglePlatform("pdd", checked = false)
        assertEquals(FilterEditor.Mode.VERIFICATION, editor.mode)
        assertEquals(setOf(PlatformTemplates.ALL_VERIFICATION_ID), editor.selectedPlatforms)
        assertTrue(editor.rules.includeKeywords.isEmpty())
    }

    @Test fun switchingBetweenThreeModesUsesCleanDefaultsAndPreservesUserDrafts() {
        val editor = FilterEditor.forNewRule()
        editor.togglePlatform("meituan", checked = true)

        // 从验证码切到关键词转发：默认任一匹配，不携带模板关键词或模板默认排除词。
        editor.select(FilterEditor.Mode.KEYWORDS)
        assertEquals(FilterEditor.Mode.KEYWORDS, editor.mode)
        assertTrue(editor.rules.enabled)
        assertTrue(editor.rules.includeKeywords.isEmpty())
        assertTrue(editor.rules.excludeKeywords.isEmpty())
        assertFalse(editor.rules.matchAll)
        assertFalse(editor.rules.requireCodeWord)
        assertFalse(editor.rules.requireDigits)

        // 在关键词模式录入内容并切到全部短信再切回，原输入与高级设置保留。
        editor.include(listOf("快递", "取餐码"))
        editor.exclude(listOf("广告"))
        editor.senders(listOf("106*"))
        editor.select(FilterEditor.Mode.ALL)
        assertEquals(FilterEditor.Mode.ALL, editor.mode)
        assertFalse(editor.rules.enabled)
        assertTrue(editor.rules.evaluate("10010", "广告").allowed)

        editor.select(FilterEditor.Mode.KEYWORDS)
        assertEquals(FilterEditor.Mode.KEYWORDS, editor.mode)
        assertTrue(editor.rules.enabled)
        assertEquals(listOf("快递", "取餐码"), editor.rules.includeKeywords)
        assertEquals(listOf("广告"), editor.rules.excludeKeywords)
        assertEquals(listOf("106*"), editor.rules.allowedSenders)

        // 切回验证码模式再切回关键词模式，平台多选与关键词草稿双向各自保留。
        editor.select(FilterEditor.Mode.VERIFICATION)
        assertEquals(setOf("meituan"), editor.selectedPlatforms)
        assertEquals(listOf("广告"), editor.rules.excludeKeywords)
        assertEquals(listOf("106*"), editor.rules.allowedSenders)

        editor.select(FilterEditor.Mode.KEYWORDS)
        assertEquals(listOf("快递", "取餐码"), editor.rules.includeKeywords)
        assertEquals(listOf("广告"), editor.rules.excludeKeywords)
    }

    @Test fun legacyRulesMapBidirectionallyWithZeroDataLoss() {
        // 1) 老版「仅验证码」预设
        val legacyVerif = FilterRules(
            enabled = true,
            includeKeywords = listOf("验证码"),
            requireDigits = true,
            matchAll = true,
            excludeKeywords = listOf("银行"),
            allowedSenders = listOf("106*"),
            requireCodeWord = false,
        )
        val verifEditor = FilterEditor(legacyVerif)
        assertEquals(FilterEditor.Mode.VERIFICATION, verifEditor.mode)
        assertEquals(legacyVerif, verifEditor.rules)
        assertFalse(verifEditor.isDirty)
        assertEquals(3, verifEditor.advancedSummary().count)

        // 2) 老版「验证码或数字」预设
        val legacyEither = legacyVerif.copy(matchAll = false)
        val eitherEditor = FilterEditor(legacyEither)
        assertEquals(FilterEditor.Mode.KEYWORDS, eitherEditor.mode)
        assertEquals(legacyEither, eitherEditor.rules)
        assertFalse(eitherEditor.isDirty)
        assertEquals(3, eitherEditor.advancedSummary().count)

        // 3) 老版自定义规则（AND / OR + 排除词 + 号码 + 数字）
        for (all in listOf(false, true)) {
            val saved = legacyCustom(all)
            val editor = FilterEditor(saved)
            assertEquals(FilterEditor.Mode.KEYWORDS, editor.mode)
            assertEquals(saved, editor.rules)
            assertFalse(editor.isDirty)
            assertEquals(if (all) 4 else 3, editor.advancedSummary().count)
        }

        // 4) 老版已停用但带限制的规则
        val disabled = legacyCustom(true).copy(enabled = false)
        val allEditor = FilterEditor(disabled)
        assertEquals(FilterEditor.Mode.ALL, allEditor.mode)
        assertEquals(disabled, allEditor.rules)
        assertFalse(allEditor.isDirty)
        assertEquals(4, allEditor.advancedSummary().count)
        allEditor.select(FilterEditor.Mode.KEYWORDS)
        assertEquals(disabled.copy(enabled = true), allEditor.rules)
        allEditor.select(FilterEditor.Mode.ALL)
        assertEquals(disabled, allEditor.rules)
        assertFalse(allEditor.isDirty)

        // 5) 平台关键词混入自定义词的老规则识别为关键词模式，绝不丢失自定义词
        val mixed = PlatformTemplates.buildRules(setOf("meituan")).let {
            it.copy(includeKeywords = it.includeKeywords + "取餐码")
        }
        val mixedEditor = FilterEditor(mixed)
        assertEquals(FilterEditor.Mode.KEYWORDS, mixedEditor.mode)
        assertTrue(mixedEditor.rules.includeKeywords.contains("取餐码"))
        assertEquals(mixed, mixedEditor.rules)
    }

    @Test fun editingAdvancedFieldsUpdatesSummaryAndLeavesOtherFieldsIntact() {
        val saved = legacyCustom(true).copy(requireDigits = false)
        val editor = FilterEditor(saved)
        assertEquals(3, editor.advancedSummary().count)

        editor.exclude(listOf("新排除词"))
        assertEquals(saved.copy(excludeKeywords = listOf("新排除词")), editor.rules)
        editor.senders(listOf("10086"))
        assertEquals(saved.copy(excludeKeywords = listOf("新排除词"), allowedSenders = listOf("10086")), editor.rules)
        assertTrue(editor.rules.matchAll)

        editor.matchAll(false)
        assertEquals(2, editor.advancedSummary().count)
        assertEquals(listOf("排除关键词", "允许发件号码"), editor.advancedSummary().configuredLabels)
    }

    @Test fun suggestRuleNameCoversModesPlatformsKeywordsAndDestinationTruncation() {
        val verifEditor = FilterEditor.forNewRule()
        assertEquals("验证码短信", verifEditor.suggestRuleName())
        assertEquals("验证码短信 → 运营飞书", verifEditor.suggestRuleName("运营飞书"))

        verifEditor.togglePlatform("meituan", checked = true)
        assertEquals("美团验证码", verifEditor.suggestRuleName())
        assertEquals("美团验证码 → 店铺群", verifEditor.suggestRuleName("店铺群"))

        verifEditor.togglePlatform("eleme", checked = true)
        assertEquals("美团、饿了么验证码", verifEditor.suggestRuleName())

        verifEditor.togglePlatform("pdd", checked = true)
        assertEquals("美团等3平台验证码", verifEditor.suggestRuleName())

        verifEditor.select(FilterEditor.Mode.KEYWORDS)
        verifEditor.include(listOf("取餐码"))
        assertEquals("含「取餐码」短信", verifEditor.suggestRuleName())

        verifEditor.include(listOf("取餐码", "快递"))
        assertEquals("「取餐码、快递」短信", verifEditor.suggestRuleName())

        verifEditor.select(FilterEditor.Mode.ALL)
        assertEquals("全部短信", verifEditor.suggestRuleName())

        val longDestName = verifEditor.suggestRuleName("这是一个超级无敌非常非常长长长长长长长长长长的目的地群组名称")
        assertTrue(longDestName.length <= ForwardRule.MAX_NAME_LENGTH)
        assertTrue(longDestName.startsWith("全部短信"))
    }

    @Test fun listEntrySupportsNewlinesAndBothCommaStyles() {
        assertEquals(listOf("验证码", "OTP", "取餐码"), FilterEditor.parseList(" 验证码\nOTP,取餐码，验证码\n\n"))
    }
}
