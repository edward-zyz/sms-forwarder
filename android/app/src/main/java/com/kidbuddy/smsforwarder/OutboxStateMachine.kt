package com.kidbuddy.smsforwarder

/**
 * 离线队列与并发投递显式状态机（纯 Kotlin，与 formal/OutboxForwarding.tla 一一对应）。
 *
 * 将原先隐含在 Store.attempts、ForwardJob.Run.stopped、State.enabled 与 generation 中的
 * 状态转换、代际守卫与队列操作权限全部显性化，杜绝旧代际任务污染重试计数。
 */
object OutboxStateMachine {
    const val MAX_ATTEMPTS = 8
    const val MAX_QUEUE_SIZE = 200
    const val MAX_BATCH_SIZE = 20

    /** 队列条目的显式生命周期状态。 */
    enum class QueueItemStatus {
        /** 刚入队，尚未尝试发送（attempts == 0）。 */
        QUEUED,

        /** 至少失败 1 次但未达上限，等待自动重试（1 <= attempts < MAX_ATTEMPTS）。 */
        RETRYING,

        /** 已达最大重试次数（attempts >= MAX_ATTEMPTS），仅可手动重试或暂停后清空。 */
        EXHAUSTED,
    }

    /** 单次 ForwardJob 投递运行的显式状态。 */
    enum class ForwardRunState {
        IDLE,
        SENDING,
        STOPPED,
        FINISHED,
    }

    /** 单次投递尝试的显式判定结果。 */
    enum class FailureDisposition {
        /** 当前 Run 仍有效（未停止、开关开启、代际匹配）：记录 attempts + 1 并隔离本轮目的地。 */
        RECORD_FAILURE_AND_ISOLATE,

        /** 当前 Run 已过期（被停止、已暂停或代际变更）：忽略本次失败，绝不污染队列计数。 */
        IGNORE_STALE_RUN,
    }

    /** 将底层 attempts 整数显式映射为队列条目状态。 */
    fun itemStatus(attempts: Int): QueueItemStatus {
        require(attempts >= 0) { "attempts 不能为负数：$attempts" }
        return when {
            attempts == 0 -> QueueItemStatus.QUEUED
            attempts < MAX_ATTEMPTS -> QueueItemStatus.RETRYING
            else -> QueueItemStatus.EXHAUSTED
        }
    }

    /** 判断某条队列条目是否可被当前 Run 调度发送（未耗尽且所属目的地未被本轮隔离）。 */
    fun isEligible(attempts: Int, destinationId: String, failedDestinations: Set<String>): Boolean =
        itemStatus(attempts) != QueueItemStatus.EXHAUSTED && destinationId !in failedDestinations

    /** 计算下一次失败后的 attempts（严格受 MAX_ATTEMPTS 上界约束）。 */
    fun nextAttemptsOnFailure(currentAttempts: Int): Int {
        require(currentAttempts >= 0) { "attempts 不能为负数：$currentAttempts" }
        return (currentAttempts + 1).coerceAtMost(MAX_ATTEMPTS)
    }

    /** 代际守卫：只有未停止、转发开启且代际一致的 Run 才允许继续取件或发起网络请求。 */
    fun isRunPermitted(
        stopped: Boolean,
        enabled: Boolean,
        runGeneration: Long,
        currentGeneration: Long,
    ): Boolean = !stopped && enabled && runGeneration == currentGeneration

    /**
     * 投递失败时的显式决策：
     * 必须同时满足未停止、开关开启且 runGeneration == currentGeneration，
     * 防止旧 Run 在“暂停->开启”或“配置变更”间隙超时后错误递增新代际条目的 attempts。
     */
    fun onSendFailure(
        stopped: Boolean,
        enabled: Boolean,
        runGeneration: Long,
        currentGeneration: Long,
    ): FailureDisposition = if (isRunPermitted(stopped, enabled, runGeneration, currentGeneration)) {
        FailureDisposition.RECORD_FAILURE_AND_ISOLATE
    } else {
        FailureDisposition.IGNORE_STALE_RUN
    }

    /** 收尾时是否应请求 JobScheduler 重新排程。 */
    fun shouldRescheduleOnFinish(
        stopped: Boolean,
        retryRequested: Boolean,
        hasEligibleRemaining: Boolean,
        enabled: Boolean,
    ): Boolean = !stopped && enabled && (retryRequested || hasEligibleRemaining)

    /** 手动重试队列仅在转发开启时允许。 */
    fun canManualRetry(enabled: Boolean): Boolean = enabled

    /** 手动清空队列仅在转发暂停时允许。 */
    fun canManualClear(enabled: Boolean): Boolean = !enabled

    /** 开关切换或配置变更统一递增代际号。 */
    fun nextGeneration(current: Long): Long = current + 1L
}
