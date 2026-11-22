package com.kidbuddy.smsforwarder

/**
 * 过滤规则草稿编辑器与模式映射（纯 Kotlin）。
 * 顶层收拢为 3 种直观模式（只转验证码 / 按关键词转发 / 转发全部），
 * 切换模式与勾选平台时只调整必要字段，绝不暗中丢弃用户已配置的高级选项或老规则字段。
 */
class FilterEditor(
    val saved: FilterRules,
    initial: FilterRules = saved,
    selected: Mode = identify(initial),
    initialPlatforms: Set<String> = initialPlatformSelection(initial, selected),
) {
    enum class Mode { VERIFICATION, KEYWORDS, ALL }

    data class AdvancedSummary(
        val count: Int,
        val configuredLabels: List<String>,
        val hasCustomExcludes: Boolean,
        val hasSenders: Boolean,
        val hasNonDefaultMatchAll: Boolean,
        val hasNonDefaultCodeWord: Boolean,
        val hasNonDefaultDigits: Boolean,
    )

    var rules: FilterRules = initial
        private set
    var mode: Mode = selected
        private set
    var selectedPlatforms: Set<String> = normalizePlatforms(initialPlatforms)
        private set
    val isDirty: Boolean get() = rules != saved

    private var cameFromVerification: Boolean = (selected == Mode.VERIFICATION)
    private var excludeEditedByUser: Boolean = initialHasCustomExcludes(initial, selected)
    private var userBaseExcludes: List<String> = initialUserExcludes(initial, selected)
    private var keywordsDraft: List<String> = if (selected == Mode.KEYWORDS) initial.includeKeywords else emptyList()
    private var keywordsMatchAll: Boolean = if (selected == Mode.KEYWORDS) initial.matchAll else false
    private var keywordsCodeWord: Boolean = if (selected == Mode.KEYWORDS) initial.requireCodeWord else false
    private var keywordsDigits: Boolean = if (selected == Mode.KEYWORDS) initial.requireDigits else false

    fun select(value: Mode) {
        if (value == mode) return
        when (value) {
            Mode.ALL -> {
                mode = Mode.ALL
                rules = rules.copy(enabled = false)
            }
            Mode.VERIFICATION -> {
                val targets = selectedPlatforms.ifEmpty { setOf(PlatformTemplates.ALL_VERIFICATION_ID) }
                selectedPlatforms = targets
                mode = Mode.VERIFICATION
                cameFromVerification = true
                rules = buildVerificationRules(targets)
            }
            Mode.KEYWORDS -> {
                val fromVerification = cameFromVerification
                mode = Mode.KEYWORDS
                cameFromVerification = false
                rules = if (fromVerification) {
                    val nextExcludes = if (excludeEditedByUser) rules.excludeKeywords else userBaseExcludes
                    rules.copy(
                        enabled = true,
                        includeKeywords = keywordsDraft,
                        requireCodeWord = keywordsCodeWord,
                        requireDigits = keywordsDigits,
                        matchAll = keywordsMatchAll,
                        excludeKeywords = nextExcludes,
                    )
                } else {
                    val enabledRules = rules.copy(enabled = true)
                    keywordsDraft = enabledRules.includeKeywords
                    keywordsMatchAll = enabledRules.matchAll
                    keywordsCodeWord = enabledRules.requireCodeWord
                    keywordsDigits = enabledRules.requireDigits
                    enabledRules
                }
            }
        }
    }

    /** 切换单个验证码平台标签，始终保持在 VERIFICATION 模式，不会跳转到自定义模式。 */
    fun togglePlatform(platformId: String, checked: Boolean) {
        val next = PlatformTemplates.toggleSelection(selectedPlatforms, platformId, checked)
            .ifEmpty { setOf(PlatformTemplates.ALL_VERIFICATION_ID) }
        applyPlatformTemplates(next)
    }

    /** 直接设置验证码平台多选集合，始终保持在 VERIFICATION 模式。 */
    fun applyPlatformTemplates(selectedIds: Set<String>) {
        val normalized = normalizePlatforms(selectedIds)
        selectedPlatforms = normalized
        mode = Mode.VERIFICATION
        cameFromVerification = true
        rules = buildVerificationRules(normalized)
    }

    fun include(values: List<String>) {
        rules = rules.copy(includeKeywords = values)
        if (mode == Mode.KEYWORDS) keywordsDraft = values
    }

    fun requireCodeWord(value: Boolean) {
        rules = rules.copy(requireCodeWord = value)
        if (mode == Mode.KEYWORDS) keywordsCodeWord = value
    }

    fun digits(value: Boolean) {
        rules = rules.copy(requireDigits = value)
        if (mode == Mode.KEYWORDS) keywordsDigits = value
    }

    fun matchAll(value: Boolean) {
        rules = rules.copy(matchAll = value)
        if (mode == Mode.KEYWORDS) keywordsMatchAll = value
    }

    fun exclude(values: List<String>) {
        excludeEditedByUser = true
        userBaseExcludes = values
        rules = rules.copy(excludeKeywords = values)
    }

    fun senders(values: List<String>) {
        rules = rules.copy(allowedSenders = values)
    }

    fun advancedSummary(): AdvancedSummary {
        val hasCustomExcludes = when (mode) {
            Mode.VERIFICATION -> {
                val defaultSet = PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS.toSet()
                rules.excludeKeywords.isNotEmpty() && rules.excludeKeywords.toSet() != defaultSet
            }
            Mode.KEYWORDS, Mode.ALL -> rules.excludeKeywords.isNotEmpty()
        }
        val hasSenders = rules.allowedSenders.isNotEmpty()
        val hasNonDefaultMatchAll = when (mode) {
            Mode.VERIFICATION -> !rules.matchAll
            Mode.KEYWORDS -> rules.matchAll
            Mode.ALL -> rules.matchAll && (rules.includeKeywords.isNotEmpty() || rules.requireCodeWord || rules.requireDigits)
        }
        val hasNonDefaultCodeWord = when (mode) {
            Mode.VERIFICATION -> !rules.requireCodeWord
            Mode.KEYWORDS, Mode.ALL -> rules.requireCodeWord
        }
        val hasNonDefaultDigits = when (mode) {
            Mode.VERIFICATION -> !rules.requireDigits
            Mode.KEYWORDS, Mode.ALL -> rules.requireDigits
        }
        val labels = buildList {
            if (hasCustomExcludes) add("排除关键词")
            if (hasSenders) add("允许发件号码")
            if (hasNonDefaultMatchAll) add(if (rules.matchAll) "必须全部满足" else "任一条件即可")
            if (hasNonDefaultCodeWord) add(if (rules.requireCodeWord) "验证码字样" else "不限验证码字样")
            if (hasNonDefaultDigits) add(if (rules.requireDigits) "4–6位数字" else "不限数字长度")
        }
        return AdvancedSummary(
            count = labels.size,
            configuredLabels = labels,
            hasCustomExcludes = hasCustomExcludes,
            hasSenders = hasSenders,
            hasNonDefaultMatchAll = hasNonDefaultMatchAll,
            hasNonDefaultCodeWord = hasNonDefaultCodeWord,
            hasNonDefaultDigits = hasNonDefaultDigits,
        )
    }

    fun suggestRuleName(destinationName: String? = null): String =
        suggestRuleName(rules, mode, selectedPlatforms, destinationName)

    private fun buildVerificationRules(targets: Set<String>): FilterRules {
        val built = PlatformTemplates.buildRules(targets, rules.copy(excludeKeywords = userBaseExcludes))
        return if (excludeEditedByUser) {
            built.copy(excludeKeywords = rules.excludeKeywords)
        } else {
            built
        }
    }

    companion object {
        fun defaultNewRules(): FilterRules = PlatformTemplates.defaultVerificationRules()

        fun forNewRule(): FilterEditor = FilterEditor(
            saved = FilterRules(),
            initial = defaultNewRules(),
            selected = Mode.VERIFICATION,
            initialPlatforms = setOf(PlatformTemplates.ALL_VERIFICATION_ID),
        )

        fun identify(rules: FilterRules): Mode = when {
            !rules.enabled -> Mode.ALL
            PlatformTemplates.detectSelectedIds(rules).isNotEmpty() -> Mode.VERIFICATION
            !rules.requireCodeWord && rules.includeKeywords == listOf("验证码") && rules.requireDigits && rules.matchAll ->
                Mode.VERIFICATION
            else -> Mode.KEYWORDS
        }

        fun initialPlatformSelection(rules: FilterRules, mode: Mode = identify(rules)): Set<String> =
            if (mode == Mode.VERIFICATION) {
                PlatformTemplates.detectSelectedIds(rules).ifEmpty { setOf(PlatformTemplates.ALL_VERIFICATION_ID) }
            } else {
                setOf(PlatformTemplates.ALL_VERIFICATION_ID)
            }

        fun parseList(text: String): List<String> = text.split(Regex("[\n,，]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** 根据当前条件与可选目的地名称自动生成不超过 32 字的默认规则名。 */
        fun suggestRuleName(
            rules: FilterRules,
            mode: Mode = identify(rules),
            selectedPlatforms: Set<String> = initialPlatformSelection(rules, mode),
            destinationName: String? = null,
        ): String {
            val conditionLabel = when (mode) {
                Mode.ALL -> "全部短信"
                Mode.VERIFICATION -> {
                    val platformNames = PlatformTemplates.ALL
                        .filter { it.id in selectedPlatforms && !it.isAllVerification }
                        .map { it.shortName }
                    when {
                        platformNames.isEmpty() -> "验证码短信"
                        platformNames.size == 1 -> "${platformNames[0]}验证码"
                        platformNames.size == 2 -> "${platformNames[0]}、${platformNames[1]}验证码"
                        else -> "${platformNames[0]}等${platformNames.size}平台验证码"
                    }
                }
                Mode.KEYWORDS -> when {
                    rules.includeKeywords.size == 1 -> "含「${rules.includeKeywords[0].take(10)}」短信"
                    rules.includeKeywords.size == 2 ->
                        "「${rules.includeKeywords[0].take(6)}、${rules.includeKeywords[1].take(6)}」短信"
                    rules.includeKeywords.size > 2 ->
                        "「${rules.includeKeywords[0].take(6)}」等${rules.includeKeywords.size}词短信"
                    rules.requireCodeWord && rules.requireDigits -> "验证码短信"
                    rules.requireDigits -> "含数字短信"
                    rules.requireCodeWord -> "验证码字样短信"
                    rules.allowedSenders.isNotEmpty() -> "指定号码短信"
                    else -> "自定义筛选短信"
                }
            }.take(ForwardRule.MAX_NAME_LENGTH)

            val dest = destinationName?.trim()?.takeIf { it.isNotEmpty() } ?: return conditionLabel
            val full = "$conditionLabel → $dest"
            if (full.length <= ForwardRule.MAX_NAME_LENGTH) return full
            val availableDest = ForwardRule.MAX_NAME_LENGTH - conditionLabel.length - 4 // " → " + "…"
            return if (availableDest >= 2) {
                "$conditionLabel → ${dest.take(availableDest)}…"
            } else {
                conditionLabel
            }
        }

        private fun normalizePlatforms(ids: Set<String>): Set<String> =
            ids.filter { PlatformTemplates.byId(it) != null }
                .toSet()
                .ifEmpty { setOf(PlatformTemplates.ALL_VERIFICATION_ID) }

        private fun initialHasCustomExcludes(rules: FilterRules, mode: Mode): Boolean {
            if (mode != Mode.VERIFICATION) return rules.excludeKeywords.isNotEmpty()
            val defaultSet = PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS.toSet()
            return rules.excludeKeywords.isNotEmpty() && rules.excludeKeywords.toSet() != defaultSet
        }

        private fun initialUserExcludes(rules: FilterRules, mode: Mode): List<String> {
            if (mode != Mode.VERIFICATION) return rules.excludeKeywords
            val defaultSet = PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS.toSet()
            return if (rules.excludeKeywords.toSet() == defaultSet) {
                emptyList()
            } else {
                rules.excludeKeywords.filter { it !in defaultSet }
            }
        }
    }
}
