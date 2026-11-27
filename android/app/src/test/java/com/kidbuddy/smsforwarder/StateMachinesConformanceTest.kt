package com.kidbuddy.smsforwarder

import org.junit.Assert.*
import org.junit.Test

/**
 * 显式状态机与 TLA+ 形式化规约的双向一致性穷举测试。
 * 覆盖：OutboxStateMachine / ServiceReadinessMachine / ConfigPersistenceMachine /
 *       ChannelTestMachine / FilterEditor（对应 formal 目录下的 .tla 规约）。
 */
class StateMachinesConformanceTest {

    // ==================== 1. OutboxStateMachine (OutboxForwarding.tla) ====================

    @Test
    fun outboxItemStatusCoversAllAttemptBoundaries() {
        assertEquals(OutboxStateMachine.QueueItemStatus.QUEUED, OutboxStateMachine.itemStatus(0))
        for (a in 1 until OutboxStateMachine.MAX_ATTEMPTS) {
            assertEquals(OutboxStateMachine.QueueItemStatus.RETRYING, OutboxStateMachine.itemStatus(a))
            assertTrue(OutboxStateMachine.isEligible(a, "d1", emptySet()))
            assertFalse(OutboxStateMachine.isEligible(a, "d1", setOf("d1")))
            assertTrue(OutboxStateMachine.isEligible(a, "d2", setOf("d1")))
        }
        assertEquals(
            OutboxStateMachine.QueueItemStatus.EXHAUSTED,
            OutboxStateMachine.itemStatus(OutboxStateMachine.MAX_ATTEMPTS),
        )
        assertFalse(OutboxStateMachine.isEligible(OutboxStateMachine.MAX_ATTEMPTS, "d1", emptySet()))
        assertEquals(
            OutboxStateMachine.MAX_ATTEMPTS,
            OutboxStateMachine.nextAttemptsOnFailure(OutboxStateMachine.MAX_ATTEMPTS),
        )
    }

    @Test
    fun outboxRunGateExhaustiveCombinationsMatchTlaRunPermitted() {
        val bools = listOf(false, true)
        for (stopped in bools) {
            for (enabled in bools) {
                for (genMatches in bools) {
                    val runGen = 10L
                    val curGen = if (genMatches) 10L else 11L
                    val expectedPermitted = !stopped && enabled && genMatches
                    assertEquals(
                        expectedPermitted,
                        OutboxStateMachine.isRunPermitted(stopped, enabled, runGen, curGen),
                    )
                    val expectedDisposition = if (expectedPermitted) {
                        OutboxStateMachine.FailureDisposition.RECORD_FAILURE_AND_ISOLATE
                    } else {
                        OutboxStateMachine.FailureDisposition.IGNORE_STALE_RUN
                    }
                    assertEquals(
                        expectedDisposition,
                        OutboxStateMachine.onSendFailure(stopped, enabled, runGen, curGen),
                    )
                }
            }
        }
    }

    @Test
    fun outboxRescheduleAndManualOpsMatchTlaGuards() {
        val bools = listOf(false, true)
        for (stopped in bools) {
            for (retry in bools) {
                for (remaining in bools) {
                    for (enabled in bools) {
                        val expected = !stopped && enabled && (retry || remaining)
                        assertEquals(
                            expected,
                            OutboxStateMachine.shouldRescheduleOnFinish(stopped, retry, remaining, enabled),
                        )
                    }
                }
            }
        }
        assertTrue(OutboxStateMachine.canManualRetry(enabled = true))
        assertFalse(OutboxStateMachine.canManualRetry(enabled = false))
        assertTrue(OutboxStateMachine.canManualClear(enabled = false))
        assertFalse(OutboxStateMachine.canManualClear(enabled = true))
        assertEquals(42L, OutboxStateMachine.nextGeneration(41L))
    }

    // ==================== 2. ServiceReadinessMachine (ServiceReadiness.tla) ====================

    @Test
    fun serviceReadinessExhaustive32CombinationsSatisfyAllInvariants() {
        val bools = listOf(false, true)
        val observedStates = mutableSetOf<ServiceReadinessMachine.State>()
        val observedActions = mutableSetOf<ServiceReadinessMachine.PowerTapAction>()

        for (enabled in bools) {
            for (hasPermission in bools) {
                for (configValid in bools) {
                    for (hasActiveRoute in bools) {
                        for (hasAnyRules in bools) {
                            val snap = ServiceReadinessMachine.Snapshot(
                                enabled = enabled,
                                hasPermission = hasPermission,
                                configValid = configValid,
                                hasActiveRoute = hasActiveRoute,
                                hasAnyRules = hasAnyRules,
                            )
                            val state = ServiceReadinessMachine.evaluate(snap)
                            val action = ServiceReadinessMachine.onPowerTap(snap)
                            observedStates += state
                            observedActions += action

                            // TLA+ Invariant: RunningRequiresAllReady
                            if (state == ServiceReadinessMachine.State.RUNNING) {
                                assertTrue(enabled && hasPermission && configValid && hasActiveRoute)
                            }
                            // TLA+ Invariant: ConfigErrorOverridesRunning
                            if (!configValid) {
                                assertEquals(ServiceReadinessMachine.State.CONFIG_ERROR, state)
                            }
                            // TLA+ Invariant: DEGRADED_ENABLED 明确可安全暂停
                            if (state == ServiceReadinessMachine.State.DEGRADED_ENABLED) {
                                assertTrue(enabled && configValid && !snap.isReady)
                                assertEquals(ServiceReadinessMachine.PowerTapAction.PAUSE, action)
                            }
                            // TLA+ Invariant: NeverEnableWithoutPrerequisites
                            if (action == ServiceReadinessMachine.PowerTapAction.SHOW_CONFIRM_ENABLE) {
                                assertFalse(enabled)
                                assertTrue(snap.isReady)
                                assertTrue(ServiceReadinessMachine.canCommitEnable(snap))
                            }
                            if (!snap.isReady) {
                                assertFalse(ServiceReadinessMachine.canCommitEnable(snap))
                            }
                        }
                    }
                }
            }
        }
        // 确保 5 种显式状态与 6 种动作全部被穷举命中
        assertEquals(ServiceReadinessMachine.State.entries.toSet(), observedStates)
        assertEquals(ServiceReadinessMachine.PowerTapAction.entries.toSet(), observedActions)
    }

    // ==================== 3. ConfigPersistenceMachine (ConfigPersistence.tla) ====================

    @Test
    fun configPersistenceCoversAllStatesAndRestoreLockLifecycle() {
        val machine = ConfigPersistenceMachine()

        assertEquals(ConfigPersistenceMachine.State.UNINITIALIZED, machine.evaluate(hasSnapshot = false, snapshotValid = false))
        assertEquals(ConfigPersistenceMachine.State.READY, machine.evaluate(hasSnapshot = true, snapshotValid = true))
        assertEquals(ConfigPersistenceMachine.State.CORRUPTED, machine.evaluate(hasSnapshot = true, snapshotValid = false))
        assertTrue(machine.canLoad())

        // 保存失败但回滚成功：不锁定
        assertEquals(ConfigPersistenceMachine.SaveOutcome.FAILED_RESTORED, machine.onSaveFailed(restored = true))
        assertFalse(machine.isRestoreFailedLocked)
        assertTrue(machine.canLoad())

        // 保存失败且回滚失败：强制锁定，拒绝 load
        assertEquals(ConfigPersistenceMachine.SaveOutcome.FAILED_LOCKED, machine.onSaveFailed(restored = false))
        assertTrue(machine.isRestoreFailedLocked)
        assertFalse(machine.canLoad())
        assertEquals(
            ConfigPersistenceMachine.State.RESTORE_FAILED_LOCKED,
            machine.evaluate(hasSnapshot = true, snapshotValid = true),
        )

        // 一次成功保存解除锁定
        assertEquals(ConfigPersistenceMachine.SaveOutcome.SAVED, machine.onSaveSucceeded())
        assertFalse(machine.isRestoreFailedLocked)
        assertTrue(machine.canLoad())

        // 进程重启解除锁定
        machine.onSaveFailed(restored = false)
        assertFalse(machine.canLoad())
        machine.resetForProcessRestart()
        assertTrue(machine.canLoad())
    }

    // ==================== 4. ChannelTestMachine (RuleWizard.tla) ====================

    @Test
    fun channelTestMachineBlocksReentryStaleCallbacksAndPostDestroyUpdates() {
        val machine = ChannelTestMachine()
        assertEquals(ChannelTestMachine.State.Idle, machine.state)

        // 正常发起并防重入
        val seq1 = machine.start()
        assertNotNull(seq1)
        assertTrue(machine.isSending)
        assertNull(machine.start())

        // 输入修改使 seq1 失效
        assertTrue(machine.invalidate())
        assertEquals(ChannelTestMachine.State.Idle, machine.state)
        assertFalse(machine.complete(seq1!!, ok = true, message = "stale"))
        assertEquals(ChannelTestMachine.State.Idle, machine.state)

        // 重新发起 seq2，期间 seq1 迟到也不干扰，seq2 正常完成
        val seq2 = machine.start()!!
        assertTrue(seq2 > seq1)
        assertFalse(machine.complete(seq1, ok = false, message = "old error"))
        assertTrue(machine.isSending)
        assertTrue(machine.complete(seq2, ok = true, message = "ok"))
        assertEquals(ChannelTestMachine.State.Succeeded(seq2, "ok"), machine.state)

        // 失败分支
        val seq3 = machine.start()!!
        assertTrue(machine.complete(seq3, ok = false, message = "boom"))
        assertEquals(ChannelTestMachine.State.Failed(seq3, "boom"), machine.state)

        // 页面销毁后：无法发起新请求，在途回调也被忽略
        val seq4 = machine.start()!!
        machine.destroy()
        assertTrue(machine.destroyed)
        assertEquals(ChannelTestMachine.State.Idle, machine.state)
        assertNull(machine.start())
        assertFalse(machine.complete(seq4, ok = true, message = "after destroy"))
        assertEquals(ChannelTestMachine.State.Idle, machine.state)
    }

    // ==================== 5. FilterEditor (FilterEditor.tla) ====================

    @Test
    fun filterEditorModeCyclesPreserveUserDraftsAndInvariants() {
        val editor = FilterEditor.forNewRule()
        editor.senders(listOf("106900*"))

        // 切换到 KEYWORDS，录入关键词与自定义排除词
        editor.select(FilterEditor.Mode.KEYWORDS)
        assertTrue(editor.rules.enabled)
        editor.include(listOf("告警", "异常"))
        editor.exclude(listOf("测试"))

        // 切到 ALL 再切回 KEYWORDS：不丢关键词、不丢排除词、不丢发件人
        editor.select(FilterEditor.Mode.ALL)
        assertFalse(editor.rules.enabled)
        editor.select(FilterEditor.Mode.KEYWORDS)
        assertTrue(editor.rules.enabled)
        assertEquals(listOf("告警", "异常"), editor.rules.includeKeywords)
        assertEquals(listOf("测试"), editor.rules.excludeKeywords)
        assertEquals(listOf("106900*"), editor.rules.allowedSenders)

        // 切到 VERIFICATION 勾选/取消全部平台再切回 KEYWORDS
        editor.select(FilterEditor.Mode.VERIFICATION)
        assertTrue(editor.selectedPlatforms.isNotEmpty())
        editor.togglePlatform(PlatformTemplates.ALL_VERIFICATION_ID, false)
        assertTrue(editor.selectedPlatforms.isNotEmpty())
        editor.select(FilterEditor.Mode.KEYWORDS)
        assertEquals(listOf("告警", "异常"), editor.rules.includeKeywords)
        assertEquals(listOf("测试"), editor.rules.excludeKeywords)
        assertEquals(listOf("106900*"), editor.rules.allowedSenders)
    }
}
