package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class FilterEditorTest {
    private fun existing(all: Boolean) = FilterRules(true, listOf("取餐码", "OTP", "OTP"), true,
        all, listOf("银行", "private"), listOf("106*", "+8613800138000"))

    @Test fun openingExistingAnyOrAllDoesNotChangeAnyField() {
        for (all in listOf(false, true)) {
            val saved = existing(all)
            val editor = FilterEditor(saved)
            assertEquals(FilterEditor.Intent.CUSTOM, editor.intent)
            assertEquals(saved, editor.rules)
            assertFalse(editor.isDirty)
            editor.select(FilterEditor.Intent.CUSTOM)
            assertEquals(saved, editor.rules)
            assertFalse(editor.isDirty)
        }
    }

    @Test fun presetsOnlyReplaceBodyConditions() {
        val saved = existing(false)
        val editor = FilterEditor(saved)
        editor.select(FilterEditor.Intent.VERIFICATION)
        assertEquals(saved.copy(includeKeywords = listOf("验证码"), requireDigits = true, matchAll = true), editor.rules)
        editor.select(FilterEditor.Intent.VERIFICATION_OR_DIGITS)
        assertEquals(saved.copy(includeKeywords = listOf("验证码"), requireDigits = true, matchAll = false), editor.rules)
        assertEquals(saved.excludeKeywords, editor.rules.excludeKeywords)
        assertEquals(saved.allowedSenders, editor.rules.allowedSenders)
        assertTrue(editor.isDirty)
    }

    @Test fun everythingDisablesWithoutDiscardingExistingFields() {
        val saved = existing(true)
        val editor = FilterEditor(saved)
        editor.select(FilterEditor.Intent.EVERYTHING)
        assertEquals(saved.copy(enabled = false), editor.rules)
        assertTrue(editor.rules.evaluate("10010", "银行").allowed)
        editor.select(FilterEditor.Intent.CUSTOM)
        assertEquals(saved, editor.rules)
        assertFalse(editor.isDirty)
    }

    @Test fun selectingCustomAfterPresetDoesNotMutatePresetBodyOrRestrictions() {
        val editor = FilterEditor(existing(true))
        editor.select(FilterEditor.Intent.VERIFICATION_OR_DIGITS)
        val before = editor.rules
        editor.select(FilterEditor.Intent.CUSTOM)
        assertEquals(before, editor.rules)
        assertEquals(FilterEditor.Intent.CUSTOM, editor.intent)
    }

    @Test fun existingPresetsAreRecognizedWithoutDiscardingExtraRestrictions() {
        for (all in listOf(false, true)) {
            val saved = existing(all).copy(includeKeywords = listOf("验证码"))
            val editor = FilterEditor(saved)
            assertEquals(if (all) FilterEditor.Intent.VERIFICATION else FilterEditor.Intent.VERIFICATION_OR_DIGITS, editor.intent)
            assertEquals(saved, editor.rules)
        }
        val disabled = existing(true).copy(enabled = false)
        assertEquals(FilterEditor.Intent.EVERYTHING, FilterEditor(disabled).intent)
        assertEquals(disabled, FilterEditor(disabled).rules)
    }

    @Test fun restoringEditorKeepsBaselineDraftAndExplicitCustomIntent() {
        val saved = existing(true)
        val editor = FilterEditor(saved)
        editor.select(FilterEditor.Intent.VERIFICATION_OR_DIGITS)
        editor.select(FilterEditor.Intent.CUSTOM)
        val restored = FilterEditor(editor.saved, editor.rules, editor.intent)
        assertEquals(saved, restored.saved)
        assertEquals(editor.rules, restored.rules)
        assertEquals(FilterEditor.Intent.CUSTOM, restored.intent)
        assertTrue(restored.isDirty)
    }

    @Test fun editingOneFieldLeavesAllOtherBusinessFieldsAlone() {
        val saved = existing(true).copy(requireDigits = false)
        val editor = FilterEditor(saved)
        editor.exclude(listOf("新排除词"))
        assertEquals(saved.copy(excludeKeywords = listOf("新排除词")), editor.rules)
        editor.senders(listOf("10086"))
        assertEquals(saved.copy(excludeKeywords = listOf("新排除词"), allowedSenders = listOf("10086")), editor.rules)
        assertTrue(editor.rules.matchAll) // 隐藏的 AND/OR 状态也不擅自改变。
    }

    @Test fun listEntrySupportsNewlinesAndBothCommaStyles() {
        assertEquals(listOf("验证码", "OTP", "取餐码"), FilterEditor.parseList(" 验证码\nOTP,取餐码，验证码\n\n"))
    }
}
