package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class DestinationGuidesTest {
    @Test fun coversAllDestinationTypesInOrder() {
        val guides = DestinationGuides.all()
        assertEquals(DestinationType.entries, guides.map { it.type })
        DestinationType.entries.forEach { type ->
            assertEquals(type, DestinationGuides.forType(type).type)
        }
    }

    @Test fun everyGuideHasCompleteNonBlankStructureAndAtLeastThreeSteps() {
        DestinationGuides.all().forEach { guide ->
            val label = guide.type.name
            assertTrue("$label title", guide.title.isNotBlank())
            assertTrue("$label summary", guide.summary.isNotBlank())
            assertTrue("$label scenario", guide.scenario.isNotBlank())
            assertTrue("$label securityNote", guide.securityNote.isNotBlank())
            assertTrue("$label webhookExample", guide.webhookExample.isNotBlank())
            assertTrue("$label steps >= 3", guide.steps.size >= 3)
            guide.steps.forEachIndexed { index, step ->
                assertTrue("$label step $index title", step.title.isNotBlank())
                assertTrue("$label step $index detail", step.detail.isNotBlank())
            }
            assertTrue("$label pitfalls non-empty", guide.pitfalls.isNotEmpty())
            guide.pitfalls.forEachIndexed { index, pitfall ->
                assertTrue("$label pitfall $index", pitfall.isNotBlank())
            }
        }
    }

    @Test fun webhookExamplesAreValidPlaceholdersWithoutRealCredentials() {
        val realUuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        val realDingSecretRegex = Regex("\\bSEC[A-Za-z0-9]{20,}\\b")
        val realHexTokenRegex = Regex("access_token=[0-9a-f]{32,}", RegexOption.IGNORE_CASE)

        DestinationGuides.all().forEach { guide ->
            val example = guide.webhookExample
            assertTrue(
                "${guide.type.name} webhookExample should satisfy validWebhook",
                DestinationProtocol.validWebhook(guide.type, example),
            )
            assertTrue(
                "${guide.type.name} webhookExample must be an obvious placeholder",
                example.contains("example"),
            )

            val allText = buildString {
                appendLine(guide.title)
                appendLine(guide.summary)
                appendLine(guide.scenario)
                guide.steps.forEach {
                    appendLine(it.title)
                    appendLine(it.detail)
                }
                appendLine(guide.securityNote)
                guide.pitfalls.forEach { appendLine(it) }
                appendLine(guide.webhookExample)
            }
            assertFalse("${guide.type.name} must not leak real UUID token", realUuidRegex.containsMatchIn(allText))
            assertFalse("${guide.type.name} must not leak real DingTalk SEC secret", realDingSecretRegex.containsMatchIn(allText))
            assertFalse("${guide.type.name} must not leak real hex access_token", realHexTokenRegex.containsMatchIn(allText))
        }
    }

    @Test fun platformGuidesHighlightKeyProtocolAndSecurityRules() {
        val feishu = DestinationGuides.forType(DestinationType.FEISHU)
        assertTrue(feishu.steps.any { it.detail.contains("签名校验") })
        assertTrue(feishu.securityNote.contains("短信转发"))
        assertTrue(feishu.pitfalls.any { it.contains("IP 白名单") })

        val wecom = DestinationGuides.forType(DestinationType.WECOM)
        assertTrue(wecom.steps.any { it.detail.contains("内部群") })
        assertTrue(wecom.securityNote.contains("key"))

        val dingtalk = DestinationGuides.forType(DestinationType.DINGTALK)
        assertTrue(dingtalk.steps.any { it.detail.contains("SEC") })
        assertTrue(dingtalk.securityNote.contains("加签"))

        val custom = DestinationGuides.forType(DestinationType.CUSTOM)
        assertTrue(custom.steps.any { it.detail.contains("HTTPS") })
        assertTrue(custom.steps.any { it.detail.contains("2xx") })
        assertTrue(custom.securityNote.contains("X-Webhook-Timestamp") && custom.securityNote.contains("X-Webhook-Signature"))
    }
}
