package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class FilterDescriptionsTest {
    @Test fun anyAndAllAreDescribedWithDifferentConnectors() {
        val rules = FilterRules(true, listOf("验证码"), true, false)
        val any = FilterDescriptions.describe(rules)
        val all = FilterDescriptions.describe(rules.copy(matchAll = true))
        assertTrue(any.contains("或")); assertFalse(any.contains("且"))
        assertTrue(all.contains("且")); assertFalse(all.contains("或"))
        assertTrue(all.contains("验证码")); assertTrue(all.contains("4–6"))
    }

    @Test fun disabledClarifiesThatExistingRestrictionsAreInactive() {
        assertEquals("全部新短信都会转发。", FilterDescriptions.describe(FilterRules()))
        val description = FilterDescriptions.describe(FilterRules(excludeKeywords = listOf("银行"), allowedSenders = listOf("106*")))
        assertTrue(description.contains("全部新短信"))
        assertTrue(description.contains("不生效"))
        assertTrue(description.contains("其他号码也会转发"))
    }

    @Test fun exclusionAndSenderRemainRestrictionsWithNoBodyCondition() {
        val description = FilterDescriptions.describe(FilterRules(true, excludeKeywords = listOf("银行"), allowedSenders = listOf("106*")))
        assertTrue(description.contains("不要求正文"))
        assertTrue(description.contains("银行")); assertTrue(description.contains("不转发"))
        assertTrue(description.contains("还须来自")); assertTrue(description.contains("106*"))
        assertFalse(description.contains("全部新短信都会转发"))
    }

    @Test fun singleConditionNeverSuggestsAnExtraBodyRequirement() {
        val onlyWord = FilterDescriptions.describe(FilterRules(true, listOf("取餐码"), false, true))
        assertTrue(onlyWord.contains("取餐码")); assertFalse(onlyWord.contains("数字"))
        val onlyDigits = FilterDescriptions.describe(FilterRules(true, requireDigits = true, matchAll = true))
        assertTrue(onlyDigits.contains("数字")); assertFalse(onlyDigits.contains("关键词"))
    }
}
