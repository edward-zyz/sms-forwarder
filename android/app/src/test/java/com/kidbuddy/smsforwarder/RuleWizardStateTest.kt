package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

class RuleWizardStateTest {
    private val feishuDest = Destination(
        id = "dest-1",
        name = "验证码飞书群",
        type = DestinationType.FEISHU,
        webhook = "https://open.feishu.cn/open-apis/bot/v2/hook/12345678-abcd-ef01",
    )
    private val wecomDest = Destination(
        id = "dest-2",
        name = "运维企微群",
        type = DestinationType.WECOM,
        webhook = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=12345678-abcd-ef01",
    )

    private fun seqIds(prefix: String = "gen"): () -> String {
        var idx = 0
        return { "${prefix}-${++idx}" }
    }

    @Test
    fun initialModeDependsOnExistingDestinations() {
        val emptyState = RuleWizardState(emptyList(), seqIds())
        assertEquals(RuleWizardState.Step.CHANNEL, emptyState.step)
        assertEquals(RuleWizardState.ChannelMode.CREATE_NEW, emptyState.channelMode)
        assertFalse(emptyState.hasExistingDestinations())
        assertNull(emptyState.selectedExistingDestination())

        val existingState = RuleWizardState(listOf(feishuDest, wecomDest), seqIds())
        assertEquals(RuleWizardState.Step.CHANNEL, existingState.step)
        assertEquals(RuleWizardState.ChannelMode.SELECT_EXISTING, existingState.channelMode)
        assertTrue(existingState.hasExistingDestinations())
        assertEquals("dest-1", existingState.selectedDestinationId)
        assertEquals(feishuDest, existingState.selectedExistingDestination())
    }

    @Test
    fun channelModeSwitchingPreservesDraftAndRejectsSelectWhenEmpty() {
        val emptyState = RuleWizardState(emptyList(), seqIds())
        assertFalse(emptyState.switchChannelMode(RuleWizardState.ChannelMode.SELECT_EXISTING))
        assertEquals(RuleWizardState.ChannelMode.CREATE_NEW, emptyState.channelMode)

        val state = RuleWizardState(listOf(feishuDest, wecomDest), seqIds())
        assertTrue(state.switchChannelMode(RuleWizardState.ChannelMode.CREATE_NEW))
        state.updateDraftType(DestinationType.DINGTALK)
        state.updateDraftName("告警钉钉群")
        state.updateDraftWebhook("https://oapi.dingtalk.com/robot/send?access_token=abcdef1234567890")
        state.updateDraftSecret("SECtestsecret")

        assertTrue(state.selectExistingDestination("dest-2"))
        assertEquals(RuleWizardState.ChannelMode.SELECT_EXISTING, state.channelMode)
        assertEquals("运维企微群", state.selectedDestinationName())

        assertTrue(state.switchChannelMode(RuleWizardState.ChannelMode.CREATE_NEW))
        assertEquals(DestinationType.DINGTALK, state.channelDraft.type)
        assertEquals("告警钉钉群", state.channelDraft.name)
        assertEquals("SECtestsecret", state.channelDraft.secret)
    }

    @Test
    fun stepValidationAndBackNavigationWorkAsExpected() {
        val editor = FilterEditor.forNewRule()
        val state = RuleWizardState(emptyList(), seqIds())

        // 空名称拦截
        state.updateDraftWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/12345678-abcd-ef01")
        val missingName = state.proceedToConditionStep(editor)
        assertTrue(missingName is RuleWizardState.NextStepResult.Invalid)
        assertEquals(
            RuleWizardState.ChannelErrorField.NAME,
            (missingName as RuleWizardState.NextStepResult.Invalid).error.field,
        )
        assertEquals(RuleWizardState.Step.CHANNEL, state.step)

        // 无效 Webhook 拦截
        state.updateDraftName("测试飞书群")
        state.updateDraftWebhook("http://insecure.example.com/hook")
        val badWebhook = state.proceedToConditionStep(editor)
        assertTrue(badWebhook is RuleWizardState.NextStepResult.Invalid)
        assertEquals(
            RuleWizardState.ChannelErrorField.WEBHOOK,
            (badWebhook as RuleWizardState.NextStepResult.Invalid).error.field,
        )

        // 合法渠道进入第 2 步
        state.updateDraftWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/12345678-abcd-ef01")
        val ok = state.proceedToConditionStep(editor)
        assertTrue(ok is RuleWizardState.NextStepResult.Success)
        assertEquals(RuleWizardState.Step.CONDITION, state.step)
        assertEquals("测试飞书群", (ok as RuleWizardState.NextStepResult.Success).destinationName)

        // 第 2 步回退到第 1 步；第 1 步回退交由 Activity 退出
        assertTrue(state.goBack())
        assertEquals(RuleWizardState.Step.CHANNEL, state.step)
        assertFalse(state.goBack())
    }

    @Test
    fun autoNamingAndManualOverrideBehavior() {
        val editor = FilterEditor.forNewRule()
        val state = RuleWizardState(listOf(feishuDest, wecomDest), seqIds())

        assertTrue(state.proceedToConditionStep(editor) is RuleWizardState.NextStepResult.Success)
        assertEquals("验证码短信 → 验证码飞书群", state.ruleName)
        assertFalse(state.ruleNameManuallyEdited)

        // 条件变更时未手动编辑则自动更新
        editor.togglePlatform("meituan", true)
        state.onConditionChanged(editor)
        assertEquals("美团验证码 → 验证码飞书群", state.ruleName)

        // 回第 1 步换渠道，再进入第 2 步也会自动更新
        assertTrue(state.goBack())
        state.selectExistingDestination("dest-2")
        assertTrue(state.proceedToConditionStep(editor) is RuleWizardState.NextStepResult.Success)
        assertEquals("美团验证码 → 运维企微群", state.ruleName)

        // 用户手动修改后，后续条件变更和换渠道都不应覆盖
        state.updateRuleNameManual("我的专属规则")
        assertTrue(state.ruleNameManuallyEdited)
        editor.select(FilterEditor.Mode.ALL)
        state.onConditionChanged(editor)
        assertEquals("我的专属规则", state.ruleName)

        assertTrue(state.goBack())
        state.selectExistingDestination("dest-1")
        assertTrue(state.proceedToConditionStep(editor) is RuleWizardState.NextStepResult.Success)
        assertEquals("我的专属规则", state.ruleName)

        // 主动重置恢复推荐名称
        state.resetRuleNameToSuggested(editor)
        assertFalse(state.ruleNameManuallyEdited)
        assertEquals("全部短信 → 验证码飞书群", state.ruleName)
    }

    @Test
    fun buildSavePayloadCreatesChannelAndRuleAtomicallyForNewChannel() {
        val editor = FilterEditor.forNewRule()
        val state = RuleWizardState(listOf(feishuDest), seqIds("wiz"))
        state.switchChannelMode(RuleWizardState.ChannelMode.CREATE_NEW)
        state.updateDraftType(DestinationType.WECOM)
        state.updateDraftName("  新企微告警群  ")
        state.updateDraftWebhook("  https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abcdef12-3456-7890  ")
        // 企微不支持 secret，构造产物时应自动忽略
        state.updateDraftSecret("should-be-stripped")
        assertTrue(state.proceedToConditionStep(editor) is RuleWizardState.NextStepResult.Success)

        val currentConfig = ForwardConfig(destinations = listOf(feishuDest), rules = emptyList())
        val result = state.buildSavePayload(
            currentConfig = currentConfig,
            filterRules = editor.rules,
            ruleIdGenerator = { "rule-new-1" },
            destIdGenerator = { "dest-new-1" },
        )
        assertTrue(result is RuleWizardState.SavePayloadResult.Success)
        val success = result as RuleWizardState.SavePayloadResult.Success
        assertTrue(success.createdDestination)
        assertEquals(2, success.config.destinations.size)
        assertEquals(1, success.config.rules.size)
        assertEquals("新企微告警群", success.destination.name)
        assertEquals("", success.destination.secret)
        assertEquals(success.destination.id, success.rule.destinationId)
        assertTrue(success.rule.enabled)
        assertEquals("验证码短信 → 新企微告警群", success.rule.name)
        assertNull(success.config.validationError())
    }

    @Test
    fun buildSavePayloadReusesExistingChannelAndRejectsStaleOrInvalidInputs() {
        val editor = FilterEditor.forNewRule()
        val state = RuleWizardState(listOf(feishuDest, wecomDest), seqIds())
        state.selectExistingDestination("dest-2")
        assertTrue(state.proceedToConditionStep(editor) is RuleWizardState.NextStepResult.Success)

        val currentConfig = ForwardConfig(destinations = listOf(feishuDest, wecomDest), rules = emptyList())
        val reused = state.buildSavePayload(
            currentConfig = currentConfig,
            filterRules = editor.rules,
            ruleIdGenerator = { "rule-1" },
        )
        assertTrue(reused is RuleWizardState.SavePayloadResult.Success)
        val ok = reused as RuleWizardState.SavePayloadResult.Success
        assertFalse(ok.createdDestination)
        assertEquals(2, ok.config.destinations.size)
        assertEquals("dest-2", ok.rule.destinationId)

        // 手动清空规则名时应精准标记规则名错误
        state.updateRuleNameManual("   ")
        val blankRuleName = state.buildSavePayload(currentConfig, editor.rules, { "rule-2" })
        assertTrue(blankRuleName is RuleWizardState.SavePayloadResult.Error)
        assertTrue((blankRuleName as RuleWizardState.SavePayloadResult.Error).isRuleNameError)

        // 若所选已有渠道已在外部被删除，应提示回第 1 步重选
        state.updateRuleNameManual("有效名称")
        val missingDestResult = state.buildSavePayload(
            currentConfig = ForwardConfig(destinations = listOf(feishuDest)),
            filterRules = editor.rules,
            ruleIdGenerator = { "rule-3" },
        )
        assertTrue(missingDestResult is RuleWizardState.SavePayloadResult.Error)
        assertTrue((missingDestResult as RuleWizardState.SavePayloadResult.Error).backToChannelStep)
    }

    @Test
    fun buildTestTargetAllowsBlankNameAndValidatesWebhook() {
        val state = RuleWizardState(emptyList(), seqIds())
        state.updateDraftType(DestinationType.FEISHU)
        state.updateDraftWebhook("invalid")
        val invalid = state.buildTestTarget()
        assertTrue(invalid is RuleWizardState.TestTargetResult.Invalid)
        assertEquals(
            RuleWizardState.ChannelErrorField.WEBHOOK,
            (invalid as RuleWizardState.TestTargetResult.Invalid).error.field,
        )

        state.updateDraftWebhook("https://open.feishu.cn/open-apis/bot/v2/hook/12345678-abcd-ef01")
        val ready = state.buildTestTarget()
        assertTrue(ready is RuleWizardState.TestTargetResult.Ready)
        assertEquals("飞书（测试）", (ready as RuleWizardState.TestTargetResult.Ready).destination.name)
    }

    @Test
    fun restoreAndRefreshDestinationsKeepConsistentState() {
        val state = RuleWizardState(listOf(feishuDest), seqIds())
        state.restore(
            step = RuleWizardState.Step.CONDITION,
            channelMode = RuleWizardState.ChannelMode.CREATE_NEW,
            selectedDestinationId = "dest-1",
            channelDraft = RuleWizardState.ChannelDraft(
                id = "draft-99",
                type = DestinationType.CUSTOM,
                name = "自建服务",
                webhook = "https://hooks.example.com/sms",
                secret = "my-secret",
            ),
            ruleName = "自定义规则名",
            ruleNameManuallyEdited = true,
            ruleEnabled = true,
        )
        assertEquals(RuleWizardState.Step.CONDITION, state.step)
        assertEquals(RuleWizardState.ChannelMode.CREATE_NEW, state.channelMode)
        assertEquals("自建服务", state.selectedDestinationName())
        assertEquals("自定义规则名", state.ruleName)

        // 当已有列表在后台被清空且处于 SELECT_EXISTING 时自动降级到 CREATE_NEW
        state.switchChannelMode(RuleWizardState.ChannelMode.SELECT_EXISTING)
        state.refreshDestinations(emptyList())
        assertEquals(RuleWizardState.ChannelMode.CREATE_NEW, state.channelMode)
        assertEquals("", state.selectedDestinationId)
    }
}
