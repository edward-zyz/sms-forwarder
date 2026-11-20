package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class RoutingEngineTest {
    private val feishu = Destination(
        id = "d-feishu",
        name = "验证码飞书",
        type = DestinationType.FEISHU,
        webhook = "https://open.feishu.cn/open-apis/bot/v2/hook/test-feishu-token",
    )
    private val wecom = Destination(
        id = "d-wecom",
        name = "运维企微",
        type = DestinationType.WECOM,
        webhook = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-wecom-key",
    )
    private val dingtalk = Destination(
        id = "d-ding",
        name = "财务钉钉",
        type = DestinationType.DINGTALK,
        webhook = "https://oapi.dingtalk.com/robot/send?access_token=test-ding-token",
    )

    @Test fun routesSameSmsToMultipleMatchingDestinations() {
        val config = ForwardConfig(
            destinations = listOf(feishu, wecom, dingtalk),
            rules = listOf(
                ForwardRule(
                    id = "r1",
                    name = "验证码",
                    enabled = true,
                    destinationId = feishu.id,
                    filter = FilterRules(enabled = true, includeKeywords = listOf("验证码"), requireDigits = true, matchAll = true),
                ),
                ForwardRule(
                    id = "r2",
                    name = "告警",
                    enabled = true,
                    destinationId = wecom.id,
                    filter = FilterRules(enabled = true, includeKeywords = listOf("告警", "异常")),
                ),
                ForwardRule(
                    id = "r3",
                    name = "全部备份",
                    enabled = true,
                    destinationId = dingtalk.id,
                    filter = FilterRules(enabled = false),
                ),
            ),
        )

        val otpDecision = RoutingEngine.evaluate(config, "10690001", "【应用】您的登录验证码为 123456")
        assertTrue(otpDecision.allowed)
        assertEquals(listOf(feishu.id, dingtalk.id), otpDecision.dispatches.map { it.destination.id })

        val alertDecision = RoutingEngine.evaluate(config, "10690002", "【监控】数据库磁盘异常告警")
        assertTrue(alertDecision.allowed)
        assertEquals(listOf(wecom.id, dingtalk.id), alertDecision.dispatches.map { it.destination.id })
    }

    @Test fun deduplicatesDispatchesTargetingSameDestination() {
        val config = ForwardConfig(
            destinations = listOf(feishu),
            rules = listOf(
                ForwardRule(
                    id = "r1",
                    name = "规则一",
                    enabled = true,
                    destinationId = feishu.id,
                    filter = FilterRules(enabled = true, includeKeywords = listOf("验证码")),
                ),
                ForwardRule(
                    id = "r2",
                    name = "规则二",
                    enabled = true,
                    destinationId = feishu.id,
                    filter = FilterRules(enabled = true, requireDigits = true),
                ),
            ),
        )
        val decision = RoutingEngine.evaluate(config, "10690001", "验证码 654321")
        assertEquals(2, decision.matches.size)
        assertEquals(1, decision.dispatches.size)
        assertEquals("r1", decision.dispatches.first().rule.id)
    }

    @Test fun disabledRulesAreSkipped() {
        val config = ForwardConfig(
            destinations = listOf(feishu, wecom),
            rules = listOf(
                ForwardRule("r1", "停用规则", enabled = false, destinationId = feishu.id, filter = FilterRules(enabled = false)),
                ForwardRule("r2", "仅银行", enabled = true, destinationId = wecom.id, filter = FilterRules(enabled = true, includeKeywords = listOf("银行"))),
            ),
        )
        val decision = RoutingEngine.evaluate(config, "10086", "普通通知")
        assertFalse(decision.allowed)
        assertTrue(decision.dispatches.isEmpty())
    }

    @Test fun invalidConfigNeverAllowsForwarding() {
        val broken = ForwardConfig(
            destinations = listOf(feishu.copy(webhook = "http://insecure.example.com/hook")),
            rules = listOf(ForwardRule("r1", "默认", enabled = true, destinationId = feishu.id)),
        )
        assertNotNull(broken.validationError())
        assertFalse(RoutingEngine.evaluate(broken, "10086", "验证码 123456").allowed)
    }

    @Test fun summaryReasonNeverLeaksSensitiveSmsContent() {
        val config = ForwardConfig(
            destinations = listOf(feishu),
            rules = listOf(
                ForwardRule(
                    id = "r1",
                    name = "私密规则",
                    enabled = true,
                    destinationId = feishu.id,
                    filter = FilterRules(enabled = true, includeKeywords = listOf("私密词"), allowedSenders = listOf("13800138000")),
                ),
            ),
        )
        val rejected = RoutingEngine.evaluate(config, "13900139000", "银行卡验证码 998877")
        assertFalse(rejected.allowed)
        listOf("13900139000", "13800138000", "998877", "私密词", "银行卡").forEach { sensitive ->
            assertFalse(rejected.summaryReason.contains(sensitive))
        }
    }
}
