package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class DestinationsProtocolTest {
    @Test fun wecomWebhookValidationIsStrict() {
        assertTrue(DestinationProtocol.validWebhook(
            DestinationType.WECOM,
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=12345678-abcd-ef01",
        ))
        listOf(
            "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=12345678-abcd",
            "https://qyapi.weixin.qq.com.evil.test/cgi-bin/webhook/send?key=12345678-abcd",
            "https://user@qyapi.weixin.qq.com/cgi-bin/webhook/send?key=12345678-abcd",
            "https://qyapi.weixin.qq.com:8443/cgi-bin/webhook/send?key=12345678-abcd",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=short",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=12345678&extra=1",
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send#frag",
        ).forEach {
            assertFalse(it, DestinationProtocol.validWebhook(DestinationType.WECOM, it))
        }
    }

    @Test fun dingtalkWebhookValidationIsStrict() {
        assertTrue(DestinationProtocol.validWebhook(
            DestinationType.DINGTALK,
            "https://oapi.dingtalk.com/robot/send?access_token=abcdef1234567890",
        ))
        listOf(
            "http://oapi.dingtalk.com/robot/send?access_token=abcdef1234567890",
            "https://oapi.dingtalk.com.evil.test/robot/send?access_token=abcdef1234567890",
            "https://oapi.dingtalk.com/robot/send?access_token=short",
            "https://oapi.dingtalk.com/robot/send?access_token=abcdef1234567890&timestamp=1",
            "https://oapi.dingtalk.com/other?access_token=abcdef1234567890",
        ).forEach {
            assertFalse(it, DestinationProtocol.validWebhook(DestinationType.DINGTALK, it))
        }
    }

    @Test fun customWebhookRequiresHttpsAndNoUserInfo() {
        assertTrue(DestinationProtocol.validWebhook(DestinationType.CUSTOM, "https://hooks.example.com/sms?channel=1"))
        assertTrue(DestinationProtocol.validWebhook(DestinationType.CUSTOM, "https://hooks.example.com:8443/sms"))
        listOf(
            "http://hooks.example.com/sms",
            "https://user:pass@hooks.example.com/sms",
            "https://hooks.example.com/sms#fragment",
            "not-a-url",
            "",
        ).forEach {
            assertFalse(it, DestinationProtocol.validWebhook(DestinationType.CUSTOM, it))
        }
    }

    @Test fun wecomRequestUsesTextPayloadWithoutSignature() {
        val dest = Destination(
            id = "d-wecom",
            name = "运维企微",
            type = DestinationType.WECOM,
            webhook = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-wecom-key",
            secret = "ignored",
        )
        val req = DestinationProtocol.buildRequest(dest, "短信\n\"测试\"", 1700000000123L)
        assertEquals(dest.webhook, req.url)
        assertTrue(req.headers.isEmpty())
        assertEquals(
            "{\"msgtype\":\"text\",\"text\":{\"content\":\"短信\\n\\\"测试\\\"\"}}",
            req.body.toString(Charsets.UTF_8),
        )
    }

    @Test fun dingtalkRequestAppendsTimestampAndEncodedSignWhenSecretSet() {
        val dest = Destination(
            id = "d-ding",
            name = "告警钉钉",
            type = DestinationType.DINGTALK,
            webhook = "https://oapi.dingtalk.com/robot/send?access_token=test-ding-token",
            secret = "SECtestsecret",
        )
        val millis = 1700000000123L
        val req = DestinationProtocol.buildRequest(dest, "短信内容", millis)
        val expectedSign = DestinationProtocol.dingTalkSignature(millis, "SECtestsecret")
        assertEquals("${dest.webhook}&timestamp=$millis&sign=$expectedSign", req.url)
        assertFalse(expectedSign.contains("+"))
        assertFalse(expectedSign.contains("/"))
        assertTrue(URLDecoder.decode(expectedSign, "UTF-8").length >= 40)
        assertEquals(
            "{\"msgtype\":\"text\",\"text\":{\"content\":\"短信内容\"}}",
            req.body.toString(Charsets.UTF_8),
        )
    }

    @Test fun dingtalkRequestWithoutSecretDoesNotAppendQueryParams() {
        val dest = Destination(
            id = "d-ding-plain",
            name = "普通钉钉",
            type = DestinationType.DINGTALK,
            webhook = "https://oapi.dingtalk.com/robot/send?access_token=test-ding-token",
            secret = "",
        )
        val req = DestinationProtocol.buildRequest(dest, "你好", 1700000000000L)
        assertEquals(dest.webhook, req.url)
    }

    @Test fun customRequestIncludesSignatureHeadersWhenSecretSet() {
        val dest = Destination(
            id = "d-custom",
            name = "自建服务",
            type = DestinationType.CUSTOM,
            webhook = "https://hooks.example.com/sms",
            secret = "my-secret",
        )
        val req = DestinationProtocol.buildRequest(dest, "验证码 123456", 1700000000999L)
        val body = req.body.toString(Charsets.UTF_8)
        assertEquals("{\"title\":\"短信转发\",\"text\":\"验证码 123456\",\"timestamp\":1700000000}", body)
        assertEquals("1700000000", req.headers["X-Webhook-Timestamp"])
        assertEquals(
            DestinationProtocol.customSignature(1700000000L, body, "my-secret"),
            req.headers["X-Webhook-Signature"],
        )
    }

    @Test fun responseCheckingMatchesEachPlatformContract() {
        assertTrue(DestinationProtocol.checkResponse(DestinationType.FEISHU, 200, """{"code":0,"msg":"success"}""").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.FEISHU, 200, """{"code":19021,"msg":"sign fail"}""").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.FEISHU, 500, """{"code":0}""").ok)

        assertTrue(DestinationProtocol.checkResponse(DestinationType.WECOM, 200, """{"errcode":0,"errmsg":"ok"}""").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.WECOM, 200, """{"errcode":93000,"errmsg":"invalid"}""").ok)

        assertTrue(DestinationProtocol.checkResponse(DestinationType.DINGTALK, 200, """{"errcode":0,"errmsg":"ok"}""").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.DINGTALK, 200, """{"errcode":310000,"errmsg":"sign not match"}""").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.DINGTALK, 200, "not-json").ok)

        assertTrue(DestinationProtocol.checkResponse(DestinationType.CUSTOM, 200, "").ok)
        assertTrue(DestinationProtocol.checkResponse(DestinationType.CUSTOM, 204, "").ok)
        assertFalse(DestinationProtocol.checkResponse(DestinationType.CUSTOM, 403, "forbidden").ok)
    }
}
