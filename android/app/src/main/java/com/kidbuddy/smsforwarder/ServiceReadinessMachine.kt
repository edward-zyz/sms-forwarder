package com.kidbuddy.smsforwarder

/**
 * 主控转发开关与首页就绪状态机（纯 Kotlin，与 formal/ServiceReadiness.tla 一一对应）。
 *
 * 显性化原先散落在 MainActivity 中的裸字符串 heroStateCache 与多重 if/when 组合态，
 * 特别显性化 DEGRADED_ENABLED 态（开关已开，但运行期权限被撤或规则全停用）。
 */
object ServiceReadinessMachine {
    /** 首页主控区的 5 个互斥显式状态。 */
    enum class State {
        /** 配置读取或反序列化损坏。 */
        CONFIG_ERROR,

        /** 开关关闭，且尚未满足全部开启前置条件（缺权限或无启用规则）。 */
        NOT_READY,

        /** 前置条件全部就绪，当前处于暂停态。 */
        PAUSED,

        /** 前置条件全部就绪且开关开启，正在自动转发。 */
        RUNNING,

        /** 开关为开，但运行期间权限被撤或已无有效启用规则（降级待修复态）。 */
        DEGRADED_ENABLED,
    }

    data class Snapshot(
        val enabled: Boolean,
        val hasPermission: Boolean,
        val configValid: Boolean,
        val hasActiveRoute: Boolean,
        val hasAnyRules: Boolean = hasActiveRoute,
    ) {
        val isReady: Boolean get() = configValid && hasPermission && hasActiveRoute
    }

    /** 点击主控大圆按钮后导出的确定性动作。 */
    enum class PowerTapAction {
        /** 暂停自动转发并取消后台调度。 */
        PAUSE,

        /** 提示配置异常。 */
        SHOW_CONFIG_ERROR,

        /** 引导前往权限页授权 RECEIVE_SMS。 */
        OPEN_PERMISSIONS,

        /** 尚无任何规则，引导进入新建规则向导。 */
        OPEN_RULE_WIZARD,

        /** 已有规则但均已停用，引导进入规则列表启用。 */
        OPEN_RULES,

        /** 全部前置条件就绪，弹出二次确认开启弹窗。 */
        SHOW_CONFIRM_ENABLE,
    }

    /** 将系统当前维度快照投影为唯一的显式状态。 */
    fun evaluate(snapshot: Snapshot): State = when {
        !snapshot.configValid -> State.CONFIG_ERROR
        !snapshot.isReady && snapshot.enabled -> State.DEGRADED_ENABLED
        !snapshot.isReady -> State.NOT_READY
        snapshot.enabled -> State.RUNNING
        else -> State.PAUSED
    }

    /** 主控按钮点击事件的状态转换决策。 */
    fun onPowerTap(snapshot: Snapshot): PowerTapAction = when {
        snapshot.enabled -> PowerTapAction.PAUSE
        !snapshot.configValid -> PowerTapAction.SHOW_CONFIG_ERROR
        !snapshot.hasPermission -> PowerTapAction.OPEN_PERMISSIONS
        !snapshot.hasActiveRoute -> if (snapshot.hasAnyRules) PowerTapAction.OPEN_RULES else PowerTapAction.OPEN_RULE_WIZARD
        else -> PowerTapAction.SHOW_CONFIRM_ENABLE
    }

    /** 二次确认弹窗点击“开启”时的最终前置校验。 */
    fun canCommitEnable(snapshot: Snapshot): Boolean = snapshot.isReady
}
