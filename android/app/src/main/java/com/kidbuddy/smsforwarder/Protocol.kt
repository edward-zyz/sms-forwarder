package com.kidbuddy.smsforwarder

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 飞书协议的纯 JVM 部分，不依赖 Android。 */
object Protocol {
    fun validWebhook(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "open.feishu.cn" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("/open-apis/bot/v2/hook/[A-Za-z0-9_-]{8,128}").matches(uri.rawPath ?: "")
    }.getOrDefault(false)

    fun signature(seconds: Long, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$seconds\n$secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(byteArrayOf()))
    }

    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun payload(text: String, secret: String, seconds: Long): String {
        val auth = if (secret.isBlank()) "" else "\"timestamp\":\"$seconds\",\"sign\":${quote(signature(seconds, secret))},"
        return "{$auth\"msg_type\":\"text\",\"content\":{\"text\":${quote(text)}}}"
    }

    fun fingerprint(subscription: Int, pdus: List<ByteArray>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(subscription.toString().toByteArray())
        pdus.forEach { pdu -> hash.update(pdu.size.toString().toByteArray()); hash.update(0); hash.update(pdu) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
