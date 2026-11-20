package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class PlatformTemplatesTest {
    @Test fun definesNineGroupedTemplatesAndDefaultSafetyExcludes() {
        assertEquals(9, PlatformTemplates.ALL.size)
        assertEquals(4, PlatformTemplates.inCategory(PlatformCategory.LOCAL_LIFE).size)
        assertEquals(4, PlatformTemplates.inCategory(PlatformCategory.ECOMMERCE).size)
        assertEquals(1, PlatformTemplates.inCategory(PlatformCategory.GENERAL).size)
        assertEquals(
            listOf("退订", "回T", "回复T", "优惠", "满减", "银行", "扣款", "转账"),
            PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS,
        )
    }

    @Test fun meituanTemplateBlocksPromoWithFourDigitsAndAllowsVerificationCode() {
        val rules = PlatformTemplates.buildRules(setOf("meituan"))
        assertTrue(rules.enabled)
        assertTrue(rules.requireCodeWord)
        assertTrue(rules.requireDigits)
        assertTrue(rules.matchAll)
        assertEquals(listOf("美团", "开店宝", "美团外卖"), rules.includeKeywords)
        assertEquals(PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS, rules.excludeKeywords)

        assertTrue(rules.evaluate("10690001", "【美团】您的登录验证码为 618294，5 分钟内有效。").allowed)
        assertTrue(rules.evaluate("10690001", "【开店宝】动态码 8392，请勿泄露。").allowed)
        assertFalse(rules.evaluate("10690001", "【美团】领 8888 元满减券").allowed)
        // 即便不含任何默认排除词，带 4 位数字的营销短信也会因缺少验证码字样被三段语义拦截。
        assertFalse(rules.evaluate("10690001", "【美团】新店开业送 8888 积分礼盒").allowed)
        // 其他平台验证码不应命中美团单选模板。
        assertFalse(rules.evaluate("10690001", "【饿了么】您的验证码为 123456").allowed)
    }

    @Test fun multiSelectMergesKeywordsAndPreservesUserRestrictions() {
        val base = FilterRules(
            excludeKeywords = listOf("广告", "银行"),
            allowedSenders = listOf("106*"),
        )
        val rules = PlatformTemplates.buildRules(setOf("meituan", "eleme", "taobao_qianniu"), base)
        assertEquals(
            listOf("美团", "开店宝", "美团外卖", "淘宝闪购", "饿了么", "蜂鸟", "淘宝", "天猫", "千牛"),
            rules.includeKeywords,
        )
        assertTrue(rules.excludeKeywords.contains("广告"))
        assertTrue(rules.excludeKeywords.containsAll(PlatformTemplates.DEFAULT_EXCLUDE_KEYWORDS))
        assertEquals(rules.excludeKeywords.distinct(), rules.excludeKeywords)
        assertEquals(listOf("106*"), rules.allowedSenders)

        assertTrue(rules.evaluate("10690001", "【蜂鸟】您的登录码为 4567").allowed)
        assertTrue(rules.evaluate("10690001", "【千牛】Security code 778899").allowed)
    }

    @Test fun allVerificationTemplateIsMutuallyExclusiveAndMatchesAnyPlatformCode() {
        val afterAll = PlatformTemplates.toggleSelection(
            setOf("meituan", "pdd"),
            PlatformTemplates.ALL_VERIFICATION_ID,
            checked = true,
        )
        assertEquals(setOf(PlatformTemplates.ALL_VERIFICATION_ID), afterAll)

        val afterPlatform = PlatformTemplates.toggleSelection(afterAll, "douyin_laike", checked = true)
        assertEquals(setOf("douyin_laike"), afterPlatform)

        val rules = PlatformTemplates.buildRules(setOf(PlatformTemplates.ALL_VERIFICATION_ID))
        assertTrue(rules.includeKeywords.isEmpty())
        assertTrue(rules.requireCodeWord)
        assertTrue(rules.requireDigits)
        assertTrue(rules.matchAll)
        assertTrue(rules.evaluate(null, "【任意平台】您的校验码为 246810").allowed)
        assertFalse(rules.evaluate(null, "【任意平台】恭喜获得 8888 元奖励").allowed)
    }

    @Test fun detectSelectedIdsRestoresCheckboxStateFromRules() {
        val multi = PlatformTemplates.buildRules(setOf("jd_daojia", "xiaohongshu", "wechat_shop"))
        assertEquals(setOf("jd_daojia", "xiaohongshu", "wechat_shop"), PlatformTemplates.detectSelectedIds(multi))

        val all = PlatformTemplates.buildRules(setOf(PlatformTemplates.ALL_VERIFICATION_ID))
        assertEquals(setOf(PlatformTemplates.ALL_VERIFICATION_ID), PlatformTemplates.detectSelectedIds(all))

        // 手动关闭 requireCodeWord 后不再视为标准防误转模板勾选状态。
        assertTrue(PlatformTemplates.detectSelectedIds(multi.copy(requireCodeWord = false)).isEmpty())
    }
}
