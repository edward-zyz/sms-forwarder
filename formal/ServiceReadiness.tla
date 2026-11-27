--------------------------- MODULE ServiceReadiness ---------------------------
(***************************************************************************)
(* 主控转发开关与首页就绪状态机（对应 Android ServiceReadinessMachine /     *)
(* MainActivity）。                                                        *)
(*                                                                         *)
(* 显性化原先散落在 MainActivity 中的隐含组合态：                           *)
(*   enabled x hasPermission x configValid x hasActiveRoute                *)
(* 特别显性化 DEGRADED_ENABLED 态（开关已开，但运行期权限被撤销或规则       *)
(* 全部停用/配置损坏），修复原代码中点击主按钮盲目取反的缺陷。              *)
(***************************************************************************)
EXTENDS TLC

VARIABLES
    enabled,        \* 持久化的主控开关：BOOLEAN
    hasPermission,  \* RECEIVE_SMS 权限状态：BOOLEAN
    configValid,    \* ConfigStore 配置是否健康：BOOLEAN
    hasActiveRoute, \* 是否存在至少一条有效且启用的规则：BOOLEAN
    confirmPending, \* 是否正在展示“开启自动转发”二次确认弹窗：BOOLEAN
    lastAction      \* 最近一次主按钮点击导出的确定性动作

vars == <<enabled, hasPermission, configValid, hasActiveRoute, confirmPending, lastAction>>

Actions == {
    "NONE",
    "SHOW_CONFIG_ERROR",
    "OPEN_PERMISSIONS",
    "OPEN_RULES",
    "SHOW_CONFIRM_ENABLE",
    "ENABLE_COMMITTED",
    "PAUSED"
}

Ready == hasPermission /\ configValid /\ hasActiveRoute

\* 显式投影为 5 个互斥的业务状态
HeroState ==
    IF ~configValid THEN "CONFIG_ERROR"
    ELSE IF ~Ready /\ enabled THEN "DEGRADED_ENABLED"
    ELSE IF ~Ready /\ ~enabled THEN "NOT_READY"
    ELSE IF enabled THEN "RUNNING"
    ELSE "PAUSED"

TypeOK ==
    /\ enabled \in BOOLEAN
    /\ hasPermission \in BOOLEAN
    /\ configValid \in BOOLEAN
    /\ hasActiveRoute \in BOOLEAN
    /\ confirmPending \in BOOLEAN
    /\ lastAction \in Actions

Init ==
    /\ enabled = FALSE
    /\ hasPermission = FALSE
    /\ configValid = TRUE
    /\ hasActiveRoute = FALSE
    /\ confirmPending = FALSE
    /\ lastAction = "NONE"

\* 用户点击大圆形主控按钮
TapPowerButton ==
    /\ ~confirmPending
    /\ IF enabled THEN
           \* 无论 RUNNING 还是 DEGRADED_ENABLED，点击均可安全暂停；
           \* DEGRADED_ENABLED 时先暂停清理调度，用户再按准备清单修复。
           /\ enabled' = FALSE
           /\ lastAction' = "PAUSED"
           /\ UNCHANGED <<hasPermission, configValid, hasActiveRoute, confirmPending>>
       ELSE IF ~configValid THEN
           /\ lastAction' = "SHOW_CONFIG_ERROR"
           /\ UNCHANGED <<enabled, hasPermission, configValid, hasActiveRoute, confirmPending>>
       ELSE IF ~hasPermission THEN
           /\ lastAction' = "OPEN_PERMISSIONS"
           /\ UNCHANGED <<enabled, hasPermission, configValid, hasActiveRoute, confirmPending>>
       ELSE IF ~hasActiveRoute THEN
           /\ lastAction' = "OPEN_RULES"
           /\ UNCHANGED <<enabled, hasPermission, configValid, hasActiveRoute, confirmPending>>
       ELSE
           /\ confirmPending' = TRUE
           /\ lastAction' = "SHOW_CONFIRM_ENABLE"
           /\ UNCHANGED <<enabled, hasPermission, configValid, hasActiveRoute>>

\* 用户在二次确认弹窗点击“开启”（会再次校验全部前置条件）
ConfirmEnable ==
    /\ confirmPending
    /\ confirmPending' = FALSE
    /\ IF Ready THEN
           /\ enabled' = TRUE
           /\ lastAction' = "ENABLE_COMMITTED"
       ELSE
           /\ enabled' = FALSE
           /\ lastAction' = IF ~configValid THEN "SHOW_CONFIG_ERROR"
                            ELSE IF ~hasPermission THEN "OPEN_PERMISSIONS"
                            ELSE "OPEN_RULES"
    /\ UNCHANGED <<hasPermission, configValid, hasActiveRoute>>

\* 用户取消二次确认弹窗
CancelEnable ==
    /\ confirmPending
    /\ confirmPending' = FALSE
    /\ UNCHANGED <<enabled, hasPermission, configValid, hasActiveRoute, lastAction>>

\* 外部环境变化：系统授权/撤权、配置损坏/修复、规则启用/停用
TogglePermission ==
    /\ hasPermission' = ~hasPermission
    /\ UNCHANGED <<enabled, configValid, hasActiveRoute, confirmPending, lastAction>>

ToggleConfigValid ==
    /\ configValid' = ~configValid
    /\ UNCHANGED <<enabled, hasPermission, hasActiveRoute, confirmPending, lastAction>>

ToggleActiveRoute ==
    /\ hasActiveRoute' = ~hasActiveRoute
    /\ UNCHANGED <<enabled, hasPermission, configValid, confirmPending, lastAction>>

Next ==
    \/ TapPowerButton
    \/ ConfirmEnable
    \/ CancelEnable
    \/ TogglePermission
    \/ ToggleConfigValid
    \/ ToggleActiveRoute

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 只有全部前置条件就绪且开关为开，才能进入 RUNNING
RunningRequiresAllReady ==
    HeroState = "RUNNING" => (enabled /\ hasPermission /\ configValid /\ hasActiveRoute)

\* 绝不会出现未就绪却宣称 RUNNING 的状态
ConfigErrorOverridesRunning ==
    ~configValid => HeroState = "CONFIG_ERROR"

\* 五个显式状态完备且互斥
HeroStateWellDefined ==
    HeroState \in {"CONFIG_ERROR", "DEGRADED_ENABLED", "NOT_READY", "PAUSED", "RUNNING"}

\* 处于开启确认弹窗时，服务本身尚未开启
ConfirmDialogOnlyWhenDisabled ==
    confirmPending => ~enabled

=============================================================================
