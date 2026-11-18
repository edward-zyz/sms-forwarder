package com.kidbuddy.smsforwarder

object FilterDescriptions {
    fun describe(rules: FilterRules): String {
        if (rules.validationError() != null) return "设置尚不完整，请检查输入后保存。"
        if (!rules.enabled) return "全部新短信都会转发。" +
            if (rules.excludeKeywords.isNotEmpty() || rules.allowedSenders.isNotEmpty()) "原有排除词和来源号码限制不生效，含排除内容或来自其他号码也会转发。" else ""
        val bodyConditions = buildList {
            if (rules.includeKeywords.isNotEmpty()) add("含${words(rules.includeKeywords)}")
            if (rules.requireDigits) add("有完整连续的 4–6 位数字")
        }
        val body = when (bodyConditions.size) {
            0 -> ""
            1 -> bodyConditions.single()
            else -> bodyConditions.joinToString(if (rules.matchAll) "，且" else "，或")
        }
        return buildList {
            if (body.isNotEmpty()) add("转发${body}的短信。")
            else if (rules.excludeKeywords.isEmpty() && rules.allowedSenders.isEmpty()) add("全部新短信都会转发。")
            else add("不要求正文含特定内容。")
            if (rules.excludeKeywords.isNotEmpty()) add("含${words(rules.excludeKeywords)}的不转发。")
            if (rules.allowedSenders.isNotEmpty()) add("还须来自指定号码（${examples(rules.allowedSenders)}，* 表示以该号码开头）。")
        }.joinToString("")
    }
    private fun words(values: List<String>): String = values.take(3).joinToString("或") { "“$it”" } +
        if (values.size > 3) "等 ${values.size} 项文字中的任一种" else ""
    private fun examples(values: List<String>): String = values.take(3).joinToString("、") { "“$it”" } +
        if (values.size > 3) "等 ${values.size} 项" else ""
}
