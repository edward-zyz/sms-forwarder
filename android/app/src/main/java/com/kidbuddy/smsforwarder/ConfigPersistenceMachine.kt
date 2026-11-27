package com.kidbuddy.smsforwarder

/**
 * 配置持久化与两阶段提交/回滚显式状态机（纯 Kotlin，与 formal/ConfigPersistence.tla 一一对应）。
 *
 * 统一封装 ConfigStore 与 FilterSettings 共用的失败回滚锁逻辑：
 * Android SharedPreferences.Editor.commit() 失败时内存缓存已被新值污染，
 * 必须尝试二次 commit() 恢复原值；若恢复也失败，则锁定为 RESTORE_FAILED_LOCKED，
 * 拒绝任何 load()，直至一次成功的 save() 覆盖内存与磁盘。
 */
class ConfigPersistenceMachine {
    enum class State {
        /** 尚无快照（待初始化或旧版迁移）。 */
        UNINITIALIZED,

        /** 内存与磁盘快照有效可用。 */
        READY,

        /** 快照损坏，禁止静默降级为默认配置。 */
        CORRUPTED,

        /** 保存失败且回滚也失败，内存已被脏值污染，拒绝读取直到下次成功保存。 */
        RESTORE_FAILED_LOCKED,
    }

    enum class SaveOutcome {
        SAVED,
        FAILED_RESTORED,
        FAILED_LOCKED,
    }

    @Volatile
    private var restoreFailedLocked: Boolean = false

    val isRestoreFailedLocked: Boolean
        get() = restoreFailedLocked

    /** 评估当前存储状态。 */
    fun evaluate(hasSnapshot: Boolean, snapshotValid: Boolean): State = when {
        restoreFailedLocked -> State.RESTORE_FAILED_LOCKED
        !hasSnapshot -> State.UNINITIALIZED
        snapshotValid -> State.READY
        else -> State.CORRUPTED
    }

    /** 是否允许执行 load() 读取。 */
    fun canLoad(): Boolean = !restoreFailedLocked

    /** 记录一次成功落盘，解除任何先前的回滚失败锁定。 */
    fun onSaveSucceeded(): SaveOutcome {
        restoreFailedLocked = false
        return SaveOutcome.SAVED
    }

    /** 记录一次落盘失败及回滚尝试结果。 */
    fun onSaveFailed(restored: Boolean): SaveOutcome {
        restoreFailedLocked = !restored
        return if (restored) SaveOutcome.FAILED_RESTORED else SaveOutcome.FAILED_LOCKED
    }

    /** 仅用于模拟进程重启或测试重置。 */
    fun resetForProcessRestart() {
        restoreFailedLocked = false
    }
}
