package com.kidbuddy.smsforwarder

/** 商家平台模板分组。 */
enum class PlatformCategory(val displayName: String) {
    LOCAL_LIFE("本地生活 / 即时零售"),
    ECOMMERCE("电商 / 投放商家"),
    GENERAL("通用保底"),
}

/** 单个预置平台模板。 */
data class PlatformTemplate(
    val id: String,
    val name: String,
    val category: PlatformCategory,
    val keywords: List<String>,
    val isAllVerification: Boolean = false,
)

/**
 * 9 个常用商家平台模板与默认安全排除词（纯 Kotlin，可直接在 JVM 单测）。
 * 配合三段防误转语义（平台关键词 + 验证码字样 + 4~6 位数字），避免营销/满减短信误触发。
 */
object PlatformTemplates {
    const val ALL_VERIFICATION_ID = "all_verification"

    val DEFAULT_EXCLUDE_KEYWORDS: List<String> = listOf(
        "退订",
        "回T",
        "回复T",
        "优惠",
        "满减",
        "银行",
        "扣款",
        "转账",
    )

    val ALL: List<PlatformTemplate> = listOf(
        // 本地生活 / 即时零售
        PlatformTemplate(
            id = "meituan",
            name = "美团",
            category = PlatformCategory.LOCAL_LIFE,
            keywords = listOf("美团", "开店宝", "美团外卖"),
        ),
        PlatformTemplate(
            id = "eleme",
            name = "淘宝闪购 / 饿了么",
            category = PlatformCategory.LOCAL_LIFE,
            keywords = listOf("淘宝闪购", "饿了么", "蜂鸟"),
        ),
        PlatformTemplate(
            id = "jd_daojia",
            name = "京东到家 / 秒送",
            category = PlatformCategory.LOCAL_LIFE,
            keywords = listOf("京东到家", "京东秒送", "达达"),
        ),
        PlatformTemplate(
            id = "douyin_laike",
            name = "抖音来客",
            category = PlatformCategory.LOCAL_LIFE,
            keywords = listOf("抖音来客", "抖音生活服务", "抖店"),
        ),
        // 电商 / 投放商家
        PlatformTemplate(
            id = "taobao_qianniu",
            name = "淘宝 / 天猫 / 千牛",
            category = PlatformCategory.ECOMMERCE,
            keywords = listOf("淘宝", "天猫", "千牛"),
        ),
        PlatformTemplate(
            id = "pdd",
            name = "拼多多商家",
            category = PlatformCategory.ECOMMERCE,
            keywords = listOf("拼多多"),
        ),
        PlatformTemplate(
            id = "xiaohongshu",
            name = "小红书",
            category = PlatformCategory.ECOMMERCE,
            keywords = listOf("小红书", "千帆", "聚光"),
        ),
        PlatformTemplate(
            id = "wechat_shop",
            name = "微信小店 / 视频号",
            category = PlatformCategory.ECOMMERCE,
            keywords = listOf("微信小店", "视频号", "微信支付商户"),
        ),
        // 通用保底
        PlatformTemplate(
            id = ALL_VERIFICATION_ID,
            name = "全部验证码",
            category = PlatformCategory.GENERAL,
            keywords = emptyList(),
            isAllVerification = true,
        ),
    )

    fun byId(id: String): PlatformTemplate? = ALL.firstOrNull { it.id == id }

    fun inCategory(category: PlatformCategory): List<PlatformTemplate> =
        ALL.filter { it.category == category }

    /**
     * 处理单个模板的勾选切换：
     * - 勾选「全部验证码」时，与具体平台互斥，仅保留全部验证码；
     * - 勾选具体平台时，自动取消「全部验证码」。
     */
    fun toggleSelection(currentIds: Set<String>, toggledId: String, checked: Boolean): Set<String> {
        if (byId(toggledId) == null) return currentIds
        if (!checked) return currentIds - toggledId
        if (toggledId == ALL_VERIFICATION_ID) return setOf(ALL_VERIFICATION_ID)
        return (currentIds - ALL_VERIFICATION_ID) + toggledId
    }

    /** 合并默认安全排除词，保留用户原有排除词并去重。 */
    fun mergeDefaultExcludes(existing: List<String>): List<String> =
        (existing + DEFAULT_EXCLUDE_KEYWORDS).distinct().take(FilterRules.MAX_ITEMS)

    /** 根据已选平台模板生成三段防误转规则，保留用户已配置的号码白名单和原有排除词。 */
    fun buildRules(selectedIds: Set<String>, base: FilterRules = FilterRules()): FilterRules {
        val selected = ALL.filter { it.id in selectedIds }
        if (selected.isEmpty()) return base
        val include = if (selected.any { it.isAllVerification }) {
            emptyList()
        } else {
            selected.flatMap { it.keywords }.distinct().take(FilterRules.MAX_ITEMS)
        }
        return base.copy(
            enabled = true,
            includeKeywords = include,
            requireCodeWord = true,
            requireDigits = true,
            matchAll = true,
            excludeKeywords = mergeDefaultExcludes(base.excludeKeywords),
        )
    }

    /** 从已有规则反推处于勾选状态的平台模板 ID，用于编辑页回显。 */
    fun detectSelectedIds(rules: FilterRules): Set<String> {
        if (!rules.enabled || !rules.requireCodeWord || !rules.requireDigits || !rules.matchAll) {
            return emptySet()
        }
        if (rules.includeKeywords.isEmpty()) {
            return setOf(ALL_VERIFICATION_ID)
        }
        val lowerKeywords = rules.includeKeywords.map { it.lowercase() }.toSet()
        return ALL
            .filter { !it.isAllVerification && it.keywords.isNotEmpty() }
            .filter { template -> template.keywords.all { it.lowercase() in lowerKeywords } }
            .map { it.id }
            .toSet()
    }
}
