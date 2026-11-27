package com.kidbuddy.smsforwarder

/** 跨通道消息格式化器（纯 Kotlin，不依赖 Android）。首行高亮验证码与规则名，并始终保留“短信转发”关键词。 */
object MessageFormatter {
    const val MAX_DEVICE_LABEL_LENGTH = 48

    /** 清洗本机号码/备注：去掉换行并压缩首尾空白，限制长度避免破坏消息排版。 */
    fun sanitizeDeviceLabel(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val singleLine = raw.replace(Regex("[\\r\\n]+"), " ").trim()
        return if (singleLine.length <= MAX_DEVICE_LABEL_LENGTH) singleLine else singleLine.take(MAX_DEVICE_LABEL_LENGTH).trimEnd()
    }

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
     * - 提取到验证码：`短信转发｜$code（$displayRule）`
     * - 未提取到验证码：`短信转发（$displayRule）`
     * - 若填写了本机号码/备注，则在首行下附带 `接收手机：$cleanDevice`
     */
    fun format(
        ruleName: String,
        sender: String?,
        time: String,
        body: String,
        deviceLabel: String? = null,
    ): String {
        val displayRule = ruleName.trim().ifEmpty { "默认规则" }
        val displaySender = sender?.trim().takeUnless { it.isNullOrEmpty() } ?: "未知"
        val cleanDevice = sanitizeDeviceLabel(deviceLabel)
        val code = extractVerificationCode(body)
        val header = if (code != null) {
            "短信转发｜$code（$displayRule）"
        } else {
            "短信转发（$displayRule）"
        }
        val deviceLine = if (cleanDevice.isNotEmpty()) "\n接收手机：$cleanDevice" else ""
        return "$header$deviceLine\n$body\n发件人：$displaySender ｜ $time"
    }

    /** 统一格式化各入口（首页、目的地页、新建规则向导）的测试消息正文，确保始终附带接收手机备注。 */
    fun formatTestMessage(destination: Destination, deviceLabel: String? = null): String {
        val cleanDevice = sanitizeDeviceLabel(deviceLabel)
        val deviceLine = if (cleanDevice.isNotEmpty()) "\n接收手机：$cleanDevice" else ""
        return "短信转发$deviceLine\n测试消息：安卓手机已准备好，收到此消息表示${destination.type.displayName}「${destination.name}」连接成功。"
    }
}
