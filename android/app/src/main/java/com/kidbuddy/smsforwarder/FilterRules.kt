package com.kidbuddy.smsforwarder

data class FilterResult(val allowed: Boolean, val reason: String)

/** 只检查新短信的纯 Kotlin 规则。返回说明不包含号码、正文或配置中的关键词。 */
data class FilterRules(
    val enabled: Boolean = false,
    val includeKeywords: List<String> = emptyList(),
    val requireDigits: Boolean = false,
    val matchAll: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    val allowedSenders: List<String> = emptyList(),
) {
    fun validationError(): String? {
        if (includeKeywords.size > MAX_ITEMS || excludeKeywords.size > MAX_ITEMS || allowedSenders.size > MAX_ITEMS) {
            return "每组规则最多 32 项"
        }
        if ((includeKeywords + excludeKeywords).any { it.isBlank() || it.length > MAX_KEYWORD_LENGTH }) {
            return "关键词不能为空，且每项最多 64 字"
        }
        if (allowedSenders.any { it.length > MAX_SENDER_LENGTH || normalizePattern(it) == null }) {
            return "来源规则须为完整号码，或仅在末尾加一个 * 的号码前缀"
        }
        return null
    }

    fun evaluate(sender: String?, body: String): FilterResult {
        if (validationError() != null) return FilterResult(false, "过滤配置异常，未转发")
        if (!enabled) return FilterResult(true, "过滤未开启")
        if (excludeKeywords.any { body.contains(it, ignoreCase = true) }) {
            return FilterResult(false, "命中排除条件，未转发")
        }
        if (allowedSenders.isNotEmpty()) {
            val source = normalizeNumber(sender.orEmpty())
            val senderMatches = source != null && allowedSenders.any { pattern ->
                val normalized = normalizePattern(pattern)!!
                val raw = compact(pattern)
                // 去掉国家码的手机前缀仅匹配完整手机号，不能退化成对服务短号的宽泛匹配。
                val mobilePrefix = normalized.endsWith('*') && raw != normalized &&
                    (raw.startsWith("+86") || raw.startsWith("0086"))
                val sourceIsMobile = Regex("1[3-9][0-9]{9}").matches(source)
                (!mobilePrefix || sourceIsMobile) &&
                    if (normalized.endsWith('*')) source.startsWith(normalized.dropLast(1)) else source == normalized
            }
            if (!senderMatches) return FilterResult(false, "来源不符合条件，未转发")
        }
        val conditions = mutableListOf<Boolean>()
        if (includeKeywords.isNotEmpty()) conditions += includeKeywords.any { body.contains(it, ignoreCase = true) }
        if (requireDigits) conditions += DIGITS.containsMatchIn(normalizeDigits(body))
        val accepted = conditions.isEmpty() || if (matchAll) conditions.all { it } else conditions.any { it }
        return if (accepted) FilterResult(true, "符合转发条件") else FilterResult(false, "正文不符合条件，未转发")
    }

    companion object {
        const val MAX_ITEMS = 32
        const val MAX_KEYWORD_LENGTH = 64
        const val MAX_SENDER_LENGTH = 64
        // 不能从 7 位以上数字串截取；邻接其他 Unicode 十进制数字也不作为完整数字串。
        private val DIGITS = Regex("(?<!\\p{Nd})[0-9]{4,6}(?!\\p{Nd})")
        private fun normalizeDigits(value: String): String = value.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        private fun compact(value: String): String = normalizeDigits(value).filterNot {
            it.isWhitespace() || it in "-－–—()（）"
        }.replace('＋', '+')

        private fun normalizeNumber(value: String, prefix: Boolean = false): String? {
            val compact = compact(value)
            if (!Regex("\\+?[0-9]{1,24}").matches(compact)) return null
            val national = when {
                compact.startsWith("+86") -> compact.drop(3)
                compact.startsWith("0086") -> compact.drop(4)
                else -> return compact
            }
            // 仅统一中国手机号国家码，保留其他国家和非手机号的精确语义。
            val isMobile = Regex("1[3-9][0-9]{9}").matches(national)
            val isMobilePrefix = prefix && national.isNotEmpty() && national.length <= 11 &&
                national[0] == '1' && (national.length == 1 || national[1] in '3'..'9')
            return if (isMobile || isMobilePrefix) national else compact
        }

        private fun normalizePattern(value: String): String? {
            val raw = compact(value)
            val prefix = raw.endsWith('*')
            val number = if (prefix) raw.dropLast(1) else raw
            val normalized = normalizeNumber(number, prefix) ?: return null
            return normalized + if (prefix) "*" else ""
        }
    }
}
