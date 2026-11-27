package com.kidbuddy.smsforwarder

import java.util.UUID

/**
 * 「新建转发规则」2 步向导的纯逻辑状态机与校验器（不依赖 Android）。
 * 负责管理：步骤流转、渠道选择与内联新建草稿、自动规则命名与手动覆盖、最终保存产物原子构造。
 */
class RuleWizardState(
    existingDestinations: List<Destination> = emptyList(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Step { CHANNEL, CONDITION }

    enum class ChannelMode { SELECT_EXISTING, CREATE_NEW }

    enum class ChannelErrorField { SELECTION, NAME, WEBHOOK, SECRET }

    data class ChannelDraft(
        val id: String,
        val type: DestinationType = DestinationType.FEISHU,
        val name: String = "",
        val webhook: String = "",
        val secret: String = "",
    )

    data class ChannelValidationError(
        val field: ChannelErrorField,
        val message: String,
    )

    sealed class NextStepResult {
        data class Success(val destinationName: String) : NextStepResult()
        data class Invalid(val error: ChannelValidationError) : NextStepResult()
    }

    sealed class TestTargetResult {
        data class Ready(val destination: Destination) : TestTargetResult()
        data class Invalid(val error: ChannelValidationError) : TestTargetResult()
    }

    sealed class SavePayloadResult {
        data class Success(
            val config: ForwardConfig,
            val destination: Destination,
            val rule: ForwardRule,
            val createdDestination: Boolean,
        ) : SavePayloadResult()

        data class Error(
            val message: String,
            val isRuleNameError: Boolean = false,
            val backToChannelStep: Boolean = false,
        ) : SavePayloadResult()
    }

    var existingDestinations: List<Destination> = existingDestinations
        private set

    var step: Step = Step.CHANNEL
        private set

    var channelMode: ChannelMode = if (existingDestinations.isNotEmpty()) {
        ChannelMode.SELECT_EXISTING
    } else {
        ChannelMode.CREATE_NEW
    }
        private set

    var selectedDestinationId: String = existingDestinations.firstOrNull()?.id.orEmpty()
        private set

    var channelDraft: ChannelDraft = ChannelDraft(id = idGenerator())
        private set

    var ruleName: String = ""
        private set

    var ruleNameManuallyEdited: Boolean = false
        private set

    var ruleEnabled: Boolean = true
        private set

    fun hasExistingDestinations(): Boolean = existingDestinations.isNotEmpty()

    fun selectedExistingDestination(): Destination? =
        existingDestinations.firstOrNull { it.id == selectedDestinationId }

    fun selectedDestination(): Destination = when (channelMode) {
        ChannelMode.SELECT_EXISTING -> selectedExistingDestination() ?: draftAsDestination()
        ChannelMode.CREATE_NEW -> draftAsDestination()
    }

    fun selectedDestinationName(): String = when (channelMode) {
        ChannelMode.SELECT_EXISTING -> selectedExistingDestination()?.name?.trim().orEmpty()
        ChannelMode.CREATE_NEW -> channelDraft.name.trim()
    }

    /** 刷新已保存的渠道列表（如页面恢复时），尽量保留用户当前选择或草稿。 */
    fun refreshDestinations(destinations: List<Destination>) {
        existingDestinations = destinations
        if (destinations.isEmpty()) {
            selectedDestinationId = ""
            channelMode = ChannelMode.CREATE_NEW
            return
        }
        if (selectedDestinationId.isBlank() || destinations.none { it.id == selectedDestinationId }) {
            selectedDestinationId = destinations.first().id
        }
    }

    /** 切换渠道来源模式：已有渠道列表或内联新建渠道。 */
    fun switchChannelMode(mode: ChannelMode): Boolean {
        if (mode == ChannelMode.SELECT_EXISTING && existingDestinations.isEmpty()) {
            channelMode = ChannelMode.CREATE_NEW
            return false
        }
        channelMode = mode
        if (mode == ChannelMode.SELECT_EXISTING &&
            existingDestinations.none { it.id == selectedDestinationId }
        ) {
            selectedDestinationId = existingDestinations.first().id
        }
        return true
    }

    /** 在已有渠道列表中点选某一项。 */
    fun selectExistingDestination(destinationId: String): Boolean {
        if (existingDestinations.none { it.id == destinationId }) return false
        channelMode = ChannelMode.SELECT_EXISTING
        selectedDestinationId = destinationId
        return true
    }

    fun updateDraftType(type: DestinationType) {
        channelDraft = channelDraft.copy(type = type)
    }

    fun updateDraftName(name: String) {
        channelDraft = channelDraft.copy(name = name)
    }

    fun updateDraftWebhook(webhook: String) {
        channelDraft = channelDraft.copy(webhook = webhook)
    }

    fun updateDraftSecret(secret: String) {
        channelDraft = channelDraft.copy(secret = secret)
    }

    /** 校验第 1 步渠道选择或新建草稿是否完整合法。 */
    fun validateChannelStep(): ChannelValidationError? = when (channelMode) {
        ChannelMode.SELECT_EXISTING -> {
            val selected = selectedExistingDestination()
            if (selected == null) {
                ChannelValidationError(ChannelErrorField.SELECTION, "请选择一个转发渠道")
            } else {
                selected.validationError()?.let { msg ->
                    ChannelValidationError(ChannelErrorField.SELECTION, "所选渠道配置无效：$msg")
                }
            }
        }
        ChannelMode.CREATE_NEW -> validateDraftDestination()
    }

    /** 构造第 1 步「发送测试消息」用的临时 Destination（允许尚未填写渠道名称时先测通地址）。 */
    fun buildTestTarget(): TestTargetResult {
        if (channelMode == ChannelMode.SELECT_EXISTING) {
            val selected = selectedExistingDestination()
                ?: return TestTargetResult.Invalid(
                    ChannelValidationError(ChannelErrorField.SELECTION, "请选择一个转发渠道"),
                )
            val err = selected.validationError()
            return if (err != null) {
                TestTargetResult.Invalid(ChannelValidationError(ChannelErrorField.SELECTION, err))
            } else {
                TestTargetResult.Ready(selected)
            }
        }

        val trimmedName = channelDraft.name.trim()
        if (trimmedName.length > Destination.MAX_NAME_LENGTH) {
            return TestTargetResult.Invalid(
                ChannelValidationError(
                    ChannelErrorField.NAME,
                    "目的地名称最多 ${Destination.MAX_NAME_LENGTH} 字",
                ),
            )
        }
        val effectiveName = trimmedName.ifEmpty { "${channelDraft.type.displayName}（测试）" }
        val candidate = draftAsDestination(overrideName = effectiveName)
        val trimmedWebhook = candidate.webhook
        if (!DestinationProtocol.validWebhook(candidate.type, trimmedWebhook)) {
            return TestTargetResult.Invalid(
                ChannelValidationError(
                    ChannelErrorField.WEBHOOK,
                    candidate.validationError() ?: "请输入有效的 HTTPS Webhook 地址",
                ),
            )
        }
        if (candidate.secret.length > Destination.MAX_SECRET_LENGTH) {
            return TestTargetResult.Invalid(
                ChannelValidationError(
                    ChannelErrorField.SECRET,
                    "签名密钥过长（最多 ${Destination.MAX_SECRET_LENGTH} 字符）",
                ),
            )
        }
        return TestTargetResult.Ready(candidate)
    }

    /** 校验第 1 步并进入第 2 步；若规则名未手动编辑，自动按所选渠道名刷新推荐规则名。 */
    fun proceedToConditionStep(editor: FilterEditor): NextStepResult {
        validateChannelStep()?.let { return NextStepResult.Invalid(it) }
        step = Step.CONDITION
        if (!ruleNameManuallyEdited) {
            ruleName = editor.suggestRuleName(selectedDestinationName())
        }
        return NextStepResult.Success(selectedDestinationName())
    }

    /** 返回上一步：第 2 步回到第 1 步并消费返回事件；第 1 步返回 false 交由页面退出。 */
    fun goBack(): Boolean {
        if (step == Step.CONDITION) {
            step = Step.CHANNEL
            return true
        }
        return false
    }

    /** 第 2 步触发条件变化时，仅在用户未手改名称时更新默认规则名称。 */
    fun onConditionChanged(editor: FilterEditor) {
        if (!ruleNameManuallyEdited) {
            ruleName = editor.suggestRuleName(selectedDestinationName())
        }
    }

    fun updateRuleNameManual(name: String) {
        ruleName = name
        ruleNameManuallyEdited = true
    }

    fun resetRuleNameToSuggested(editor: FilterEditor) {
        ruleNameManuallyEdited = false
        ruleName = editor.suggestRuleName(selectedDestinationName())
    }

    fun updateRuleEnabled(enabled: Boolean) {
        ruleEnabled = enabled
    }

    /**
     * 原子构造最终待保存配置：
     * - 新建渠道模式：同时写入新渠道与新规则；
     * - 已有渠道模式：复用最新配置中的已有渠道并写入新规则。
     */
    fun buildSavePayload(
        currentConfig: ForwardConfig,
        filterRules: FilterRules,
        ruleIdGenerator: () -> String = idGenerator,
        destIdGenerator: () -> String = idGenerator,
    ): SavePayloadResult {
        val createdDestination: Boolean
        val targetDestination: Destination
        val nextDestinations: List<Destination>

        when (channelMode) {
            ChannelMode.CREATE_NEW -> {
                validateDraftDestination()?.let { err ->
                    return SavePayloadResult.Error(err.message, backToChannelStep = true)
                }
                val candidateId = resolveUniqueId(
                    preferred = channelDraft.id,
                    existingIds = currentConfig.destinations.mapTo(HashSet()) { it.id },
                    generator = destIdGenerator,
                )
                channelDraft = channelDraft.copy(id = candidateId)
                targetDestination = draftAsDestination(overrideId = candidateId)
                nextDestinations = currentConfig.destinations + targetDestination
                createdDestination = true
            }
            ChannelMode.SELECT_EXISTING -> {
                val matched = currentConfig.destinationById(selectedDestinationId)
                    ?: return SavePayloadResult.Error(
                        "所选渠道已不存在，请返回上一步重新选择",
                        backToChannelStep = true,
                    )
                matched.validationError()?.let { err ->
                    return SavePayloadResult.Error("所选渠道配置无效：$err", backToChannelStep = true)
                }
                targetDestination = matched
                nextDestinations = currentConfig.destinations
                createdDestination = false
            }
        }

        val trimmedRuleName = ruleName.trim()
        val ruleId = resolveUniqueId(
            preferred = ruleIdGenerator(),
            existingIds = currentConfig.rules.mapTo(HashSet()) { it.id },
            generator = ruleIdGenerator,
        )
        val newRule = ForwardRule(
            id = ruleId,
            name = trimmedRuleName,
            enabled = ruleEnabled,
            destinationId = targetDestination.id,
            filter = filterRules,
        )
        newRule.validationError(nextDestinations)?.let { msg ->
            val nameError = trimmedRuleName.isEmpty() || trimmedRuleName.length > ForwardRule.MAX_NAME_LENGTH
            return SavePayloadResult.Error(msg, isRuleNameError = nameError)
        }

        val nextConfig = currentConfig.copy(
            destinations = nextDestinations,
            rules = currentConfig.rules + newRule,
        )
        nextConfig.validationError()?.let { msg ->
            return SavePayloadResult.Error(msg)
        }

        return SavePayloadResult.Success(
            config = nextConfig,
            destination = targetDestination,
            rule = newRule,
            createdDestination = createdDestination,
        )
    }

    /** 用于配置变更后恢复完整向导状态。 */
    fun restore(
        step: Step,
        channelMode: ChannelMode,
        selectedDestinationId: String,
        channelDraft: ChannelDraft,
        ruleName: String,
        ruleNameManuallyEdited: Boolean,
        ruleEnabled: Boolean,
    ) {
        this.channelDraft = channelDraft
        this.ruleName = ruleName
        this.ruleNameManuallyEdited = ruleNameManuallyEdited
        this.ruleEnabled = ruleEnabled
        this.channelMode = if (channelMode == ChannelMode.SELECT_EXISTING && existingDestinations.isEmpty()) {
            ChannelMode.CREATE_NEW
        } else {
            channelMode
        }
        this.selectedDestinationId = when {
            existingDestinations.isEmpty() -> ""
            existingDestinations.any { it.id == selectedDestinationId } -> selectedDestinationId
            else -> existingDestinations.first().id
        }
        this.step = step
    }

    private fun validateDraftDestination(): ChannelValidationError? {
        val candidate = draftAsDestination()
        val trimmedName = candidate.name
        if (trimmedName.isEmpty() || trimmedName.length > Destination.MAX_NAME_LENGTH) {
            return ChannelValidationError(
                ChannelErrorField.NAME,
                "目的地名称不能为空，且最多 ${Destination.MAX_NAME_LENGTH} 字",
            )
        }
        if (!DestinationProtocol.validWebhook(candidate.type, candidate.webhook)) {
            return ChannelValidationError(
                ChannelErrorField.WEBHOOK,
                candidate.validationError() ?: "请输入有效的 HTTPS Webhook 地址",
            )
        }
        if (candidate.secret.length > Destination.MAX_SECRET_LENGTH) {
            return ChannelValidationError(
                ChannelErrorField.SECRET,
                "签名密钥过长（最多 ${Destination.MAX_SECRET_LENGTH} 字符）",
            )
        }
        return candidate.validationError()?.let {
            ChannelValidationError(ChannelErrorField.WEBHOOK, it)
        }
    }

    private fun draftAsDestination(
        overrideId: String = channelDraft.id,
        overrideName: String = channelDraft.name.trim(),
    ): Destination = Destination(
        id = overrideId,
        name = overrideName,
        type = channelDraft.type,
        webhook = channelDraft.webhook.trim(),
        secret = if (channelDraft.type.supportsSecret) channelDraft.secret.trim() else "",
    )

    private fun resolveUniqueId(
        preferred: String,
        existingIds: Set<String>,
        generator: () -> String,
    ): String {
        if (preferred.isNotBlank() && preferred !in existingIds) return preferred
        repeat(16) {
            val candidate = generator()
            if (candidate.isNotBlank() && candidate !in existingIds) return candidate
        }
        return UUID.randomUUID().toString()
    }
}

/**
 * 第 1 步内联新建渠道「发送测试消息」的显式异步状态机（纯 Kotlin，与 formal/RuleWizard.tla 对应）。
 *
 * 取代 RuleWizardActivity 中散落的 testingChannel / testChannelRequestSeq / destroyed 标志，
 * 保证草稿修改、切换类型或页面销毁后，任何乱序返回的旧网络回调都绝不会覆盖当前 UI 状态。
 */
class ChannelTestMachine {
    sealed class State {
        data object Idle : State()
        data class Sending(val seq: Int) : State()
        data class Succeeded(val seq: Int, val message: String) : State()
        data class Failed(val seq: Int, val message: String) : State()
    }

    var state: State = State.Idle
        private set

    var currentSeq: Int = 0
        private set

    var destroyed: Boolean = false
        private set

    val isSending: Boolean
        get() = state is State.Sending

    /** 尝试发起一次新的测试请求；若页面已销毁或正在发送中则返回 null（防重入）。 */
    fun start(): Int? {
        if (destroyed || state is State.Sending) return null
        val seq = ++currentSeq
        state = State.Sending(seq)
        return seq
    }

    /** 输入变化或重置反馈：递增代际并立即回到 Idle，使任何在途旧回调失效。返回是否中断了在途发送。 */
    fun invalidate(): Boolean {
        val wasSending = state is State.Sending
        currentSeq++
        state = State.Idle
        return wasSending
    }

    /** 异步网络回调到达：仅当页面未销毁、处于 Sending 且 seq 匹配当前代际时才更新状态。 */
    fun complete(seq: Int, ok: Boolean, message: String): Boolean {
        if (destroyed || seq != currentSeq || state !is State.Sending) return false
        state = if (ok) State.Succeeded(seq, message) else State.Failed(seq, message)
        return true
    }

    /** 页面销毁：置 destroyed 标记、递增代际并清空状态，杜绝销毁后回调刷新 UI。 */
    fun destroy() {
        destroyed = true
        currentSeq++
        state = State.Idle
    }
}
