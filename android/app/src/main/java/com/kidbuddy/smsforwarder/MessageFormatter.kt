package com.kidbuddy.smsforwarder

/** 跨通道消息格式化器（纯 Kotlin，不依赖 Android）。首行高亮验证码与规则名，并始终保留“短信转发”关键词。 */
object MessageFormatter {
    /**
     * 提取正文中的第一个独立 4~6 位数字作为验证码（兼容全角数字）。
     * 复用 FilterRules 的独立数字正则，不从 7 位及以上长数字（如订单号）截取。
     */
    fun extractVerificationCode(body: String): String? {
        val normalized = FilterRules.normalizeDigits(body)
        return FilterRules.DIGITS.find(normalized)?.value
    }

    /**
     * 格式化跨通道转发消息正文：
     * - 提取到验证码：`短信转发｜$code（$displayRule）\n$body\n发件人：$displaySender ｜ $time`
     * - 未提取到验证码：`短信转发（$displayRule）\n$body\n发件人：$displaySender ｜ $time`
     */
    fun format(ruleName: String, sender: String?, time: String, body: String): String {
        val displayRule = ruleName.trim().ifEmpty { "默认规则" }
        val displaySender = sender?.trim().takeUnless { it.isNullOrEmpty() } ?: "未知"
        val code = extractVerificationCode(body)
        val header = if (code != null) {
            "短信转发｜$code（$displayRule）"
        } else {
            "短信转发（$displayRule）"
        }
        return "$header\n$body\n发件人：$displaySender ｜ $time"
    }
}
