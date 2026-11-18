package com.kidbuddy.smsforwarder

/** 编辑草稿与明确的用户意图；预设只改正文，绝不暗中覆盖排除词或号码。 */
class FilterEditor(val saved: FilterRules, initial: FilterRules = saved, selected: Intent = identify(initial)) {
    enum class Intent { EVERYTHING, VERIFICATION, VERIFICATION_OR_DIGITS, CUSTOM }
    var rules: FilterRules = initial
        private set
    var intent: Intent = selected
        private set
    val isDirty: Boolean get() = rules != saved

    fun select(value: Intent) {
        intent = value
        rules = when (value) {
            Intent.EVERYTHING -> rules.copy(enabled = false)
            Intent.VERIFICATION -> rules.copy(enabled = true, includeKeywords = listOf("验证码"), requireDigits = true, matchAll = true)
            Intent.VERIFICATION_OR_DIGITS -> rules.copy(enabled = true, includeKeywords = listOf("验证码"), requireDigits = true, matchAll = false)
            Intent.CUSTOM -> rules.copy(enabled = true)
        }
    }
    fun include(values: List<String>) { rules = rules.copy(includeKeywords = values) }
    fun digits(value: Boolean) { rules = rules.copy(requireDigits = value) }
    fun matchAll(value: Boolean) { rules = rules.copy(matchAll = value) }
    fun exclude(values: List<String>) { rules = rules.copy(excludeKeywords = values) }
    fun senders(values: List<String>) { rules = rules.copy(allowedSenders = values) }

    companion object {
        fun identify(rules: FilterRules): Intent = when {
            !rules.enabled -> Intent.EVERYTHING
            rules.includeKeywords == listOf("验证码") && rules.requireDigits ->
                if (rules.matchAll) Intent.VERIFICATION else Intent.VERIFICATION_OR_DIGITS
            else -> Intent.CUSTOM
        }
        fun parseList(text: String): List<String> = text.split(Regex("[\n,，]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
