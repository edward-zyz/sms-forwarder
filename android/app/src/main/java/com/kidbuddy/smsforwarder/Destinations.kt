package com.kidbuddy.smsforwarder

import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 支持的转发目的地类型。 */
enum class DestinationType(
    val id: String,
    val displayName: String,
    val supportsSecret: Boolean,
    val webhookPlaceholder: String,
    val webhookHint: String,
    val secretHint: String,
) {
    FEISHU(
        id = "feishu",
        displayName = "飞书",
        supportsSecret = true,
        webhookPlaceholder = "https://open.feishu.cn/open-apis/bot/v2/hook/...",
        webhookHint = "仅支持飞书官方 HTTPS 机器人地址；群机器人关键词可设为“短信转发”。",
        secretHint = "仅在飞书机器人启用「签名校验」时填写。",
    ),
    WECOM(
        id = "wecom",
        displayName = "企业微信",
        supportsSecret = false,
        webhookPlaceholder = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...",
        webhookHint = "仅支持企业微信官方 HTTPS 群机器人地址（含 key 参数）。",
        secretHint = "",
    ),
    DINGTALK(
        id = "dingtalk",
        displayName = "钉钉",
        supportsSecret = true,
        webhookPlaceholder = "https://oapi.dingtalk.com/robot/send?access_token=...",
        webhookHint = "仅支持钉钉官方 HTTPS 群机器人地址（含 access_token）；关键词可设为“短信转发”。",
        secretHint = "仅在钉钉机器人启用「加签」时填写（通常以 SEC 开头）。",
    ),
    CUSTOM(
        id = "custom",
        displayName = "自定义 Webhook",
        supportsSecret = true,
        webhookPlaceholder = "https://example.com/webhook/sms",
        webhookHint = "仅支持 HTTPS 地址；发送 JSON：{\"title\":\"短信转发\",\"text\":\"...\",\"timestamp\":...}，HTTP 2xx 视为成功。",
        secretHint = "选填；填写后会在请求头附带 X-Webhook-Timestamp 与 X-Webhook-Signature（HMAC-SHA256）。",
    );

    companion object {
        fun fromId(id: String?): DestinationType? = entries.firstOrNull { it.id == id }
    }
}

data class Destination(
    val id: String,
    val name: String,
    val type: DestinationType,
    val webhook: String,
    val secret: String = "",
) {
    fun validationError(): String? {
        if (id.isBlank() || id.length > MAX_ID_LENGTH) return "目的地标识无效"
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            return "目的地名称不能为空，且最多 $MAX_NAME_LENGTH 字"
        }
        if (!DestinationProtocol.validWebhook(type, webhook.trim())) {
            return when (type) {
                DestinationType.FEISHU -> "请输入有效的飞书机器人 HTTPS 地址"
                DestinationType.WECOM -> "请输入有效的企业微信机器人 HTTPS 地址（含 key 参数）"
                DestinationType.DINGTALK -> "请输入有效的钉钉机器人 HTTPS 地址（含 access_token 参数）"
                DestinationType.CUSTOM -> "请输入有效的 HTTPS Webhook 地址"
            }
        }
        if (secret.length > MAX_SECRET_LENGTH) return "签名密钥过长（最多 $MAX_SECRET_LENGTH 字符）"
        return null
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_NAME_LENGTH = 32
        const val MAX_SECRET_LENGTH = 256
    }
}

data class ForwardRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val destinationId: String,
    val filter: FilterRules = FilterRules(),
) {
    fun validationError(destinations: List<Destination>): String? {
        if (id.isBlank() || id.length > Destination.MAX_ID_LENGTH) return "规则标识无效"
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            return "规则名称不能为空，且最多 $MAX_NAME_LENGTH 字"
        }
        val dest = destinations.firstOrNull { it.id == destinationId }
            ?: return "请选择有效的转发目的地"
        dest.validationError()?.let { return "所选目的地配置无效：$it" }
        return filter.validationError()
    }

    companion object {
        const val MAX_NAME_LENGTH = 32
    }
}

data class ForwardConfig(
    val destinations: List<Destination> = emptyList(),
    val rules: List<ForwardRule> = emptyList(),
) {
    fun destinationById(id: String): Destination? = destinations.firstOrNull { it.id == id }

    fun enabledRules(): List<ForwardRule> = rules.filter { it.enabled }

    fun hasActiveRoute(): Boolean = enabledRules().any { rule ->
        val dest = destinationById(rule.destinationId)
        dest != null && dest.validationError() == null && rule.filter.validationError() == null
    }

    fun validationError(): String? {
        if (destinations.size > MAX_DESTINATIONS) return "最多支持 $MAX_DESTINATIONS 个转发目的地"
        if (rules.size > MAX_RULES) return "最多支持 $MAX_RULES 条转发规则"
        if (destinations.map { it.id }.distinct().size != destinations.size) return "存在重复的目的地标识"
        if (rules.map { it.id }.distinct().size != rules.size) return "存在重复的规则标识"
        destinations.forEach { dest -> dest.validationError()?.let { return it } }
        rules.forEach { rule -> rule.validationError(destinations)?.let { return it } }
        return null
    }

    companion object {
        const val MAX_DESTINATIONS = 32
        const val MAX_RULES = 32
    }
}

/** 各平台发送协议的纯 JVM 实现，不依赖 Android。 */
object DestinationProtocol {
    const val MAX_PAYLOAD_BYTES = 20000

    data class HttpRequest(
        val url: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    data class ResponseCheck(
        val ok: Boolean,
        val detail: String,
    )

    private val WECOM_KEY_QUERY = Regex("key=[A-Za-z0-9_-]{8,128}")
    private val DINGTALK_TOKEN_QUERY = Regex("access_token=[A-Za-z0-9_-]{8,128}")

    fun validWebhook(type: DestinationType, value: String): Boolean = when (type) {
        DestinationType.FEISHU -> Protocol.validWebhook(value)
        DestinationType.WECOM -> runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "qyapi.weixin.qq.com" && uri.port == -1 &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.rawPath == "/cgi-bin/webhook/send" &&
                WECOM_KEY_QUERY.matches(uri.rawQuery ?: "")
        }.getOrDefault(false)
        DestinationType.DINGTALK -> runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "oapi.dingtalk.com" && uri.port == -1 &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.rawPath == "/robot/send" &&
                DINGTALK_TOKEN_QUERY.matches(uri.rawQuery ?: "")
        }.getOrDefault(false)
        DestinationType.CUSTOM -> runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                value.length <= 2048
        }.getOrDefault(false)
    }

    fun dingTalkSignature(millis: Long, secret: String): String {
        val stringToSign = "$millis\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val base64 = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
        return URLEncoder.encode(base64, "UTF-8")
    }

    fun customSignature(seconds: Long, payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val data = "$seconds\n$payload".toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(mac.doFinal(data))
    }

    fun buildRequest(destination: Destination, text: String, nowMillis: Long): HttpRequest {
        val seconds = nowMillis / 1000L
        val webhook = destination.webhook.trim()
        val secret = destination.secret.trim()
        return when (destination.type) {
            DestinationType.FEISHU -> {
                val json = Protocol.payload(text, secret, seconds)
                HttpRequest(url = webhook, body = json.toByteArray(Charsets.UTF_8))
            }
            DestinationType.WECOM -> {
                val json = "{\"msgtype\":\"text\",\"text\":{\"content\":${Protocol.quote(text)}}}"
                HttpRequest(url = webhook, body = json.toByteArray(Charsets.UTF_8))
            }
            DestinationType.DINGTALK -> {
                val json = "{\"msgtype\":\"text\",\"text\":{\"content\":${Protocol.quote(text)}}}"
                val url = if (secret.isBlank()) {
                    webhook
                } else {
                    val sign = dingTalkSignature(nowMillis, secret)
                    "$webhook&timestamp=$nowMillis&sign=$sign"
                }
                HttpRequest(url = url, body = json.toByteArray(Charsets.UTF_8))
            }
            DestinationType.CUSTOM -> {
                val json = "{\"title\":\"短信转发\",\"text\":${Protocol.quote(text)},\"timestamp\":$seconds}"
                val headers = if (secret.isBlank()) {
                    emptyMap()
                } else {
                    mapOf(
                        "X-Webhook-Timestamp" to seconds.toString(),
                        "X-Webhook-Signature" to customSignature(seconds, json, secret),
                    )
                }
                HttpRequest(url = webhook, body = json.toByteArray(Charsets.UTF_8), headers = headers)
            }
        }
    }

    /** 检查服务端响应是否确认接收；纯 Kotlin 解析，便于 JVM 单测。 */
    fun checkResponse(type: DestinationType, httpCode: Int, responseBody: String): ResponseCheck {
        if (type == DestinationType.CUSTOM) {
            return if (httpCode in 200..299) {
                ResponseCheck(true, "已接收")
            } else {
                ResponseCheck(false, "HTTP $httpCode")
            }
        }
        if (httpCode != 200) return ResponseCheck(false, "HTTP $httpCode")
        return when (type) {
            DestinationType.FEISHU -> {
                val code = extractIntField(responseBody, "code")
                if (code == 0) ResponseCheck(true, "已接收")
                else ResponseCheck(false, "飞书错误码 ${code ?: -1}")
            }
            DestinationType.WECOM -> {
                val errcode = extractIntField(responseBody, "errcode")
                if (errcode == 0) ResponseCheck(true, "已接收")
                else ResponseCheck(false, "企微错误码 ${errcode ?: -1}")
            }
            DestinationType.DINGTALK -> {
                val errcode = extractIntField(responseBody, "errcode")
                if (errcode == 0) ResponseCheck(true, "已接收")
                else ResponseCheck(false, "钉钉错误码 ${errcode ?: -1}")
            }
            DestinationType.CUSTOM -> ResponseCheck(true, "已接收")
        }
    }

    /** 从 JSON 对象顶层或标准结构中提取指定整数键值（不依赖 Android org.json）。 */
    internal fun extractIntField(json: String, field: String): Int? {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+)(?=[,\\s}])")
        val match = pattern.find(trimmed) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}

data class RuleMatch(
    val rule: ForwardRule,
    val destination: Destination,
)

data class RoutingDecision(
    val matches: List<RuleMatch>,
    val dispatches: List<RuleMatch>,
    val summaryReason: String,
) {
    val allowed: Boolean get() = dispatches.isNotEmpty()
}

/** 多规则匹配引擎：评估所有已启用规则，并按目的地去重投递。 */
object RoutingEngine {
    fun evaluate(config: ForwardConfig, sender: String?, body: String): RoutingDecision {
        if (config.validationError() != null) {
            return RoutingDecision(emptyList(), emptyList(), "转发配置异常，未转发")
        }
        val activeRules = config.enabledRules()
        if (activeRules.isEmpty()) {
            return RoutingDecision(emptyList(), emptyList(), "没有已启用的转发规则，未转发")
        }
        val matches = mutableListOf<RuleMatch>()
        var lastRejectionReason = "未命中转发规则，未转发"
        for (rule in activeRules) {
            val destination = config.destinationById(rule.destinationId) ?: continue
            val result = rule.filter.evaluate(sender, body)
            if (result.allowed) {
                matches += RuleMatch(rule, destination)
            } else {
                lastRejectionReason = result.reason
            }
        }
        if (matches.isEmpty()) {
            val reason = if (activeRules.size == 1) lastRejectionReason else "未命中任何已启用规则，未转发"
            return RoutingDecision(emptyList(), emptyList(), reason)
        }
        val seenDest = HashSet<String>()
        val dispatches = matches.filter { seenDest.add(it.destination.id) }
        val summary = if (dispatches.size == 1) {
            "命中转发规则，准备发往 1 个目的地"
        } else {
            "命中 ${matches.size} 条规则，准备发往 ${dispatches.size} 个目的地"
        }
        return RoutingDecision(matches, dispatches, summary)
    }
}
