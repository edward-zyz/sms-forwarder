--------------------------- MODULE ConfigPersistence ---------------------------
(***************************************************************************)
(* 配置持久化、旧版迁移与两阶段提交/回滚状态机（对应 Android               *)
(* ConfigPersistenceMachine / ConfigStore / FilterSettings）。             *)
(*                                                                         *)
(* 精确建模 Android SharedPreferences 的真实特性：                         *)
(* - commit() 失败时内存缓存仍已被写入新值；                               *)
(* - 必须尝试第二次 commit() 恢复原值；                                    *)
(* - 若恢复也失败，进入 RESTORE_FAILED_LOCKED 态，拒绝所有 load()，        *)
(*   直到一次成功的 save() 覆写磁盘与内存，或进程重启从磁盘重读。          *)
(***************************************************************************)
EXTENDS Integers

CONSTANTS MaxGen

VARIABLES
    diskState,      \* 磁盘上的快照状态："EMPTY" | "VALID" | "CORRUPTED"
    memState,       \* SharedPreferences 内存缓存："EMPTY" | "VALID" | "CORRUPTED" | "DIRTY_NEW"
    legacyState,    \* 旧版配置状态："NONE" | "VALID" | "CORRUPTED"
    failedRestore,  \* 内存回滚失败锁：BOOLEAN
    generation,     \* 配置版本号：0..MaxGen
    lastLoadResult  \* 最近一次 load() 的结果："NONE" | "LOADED_VALID" | "LOADED_EMPTY" | "REJECTED"

vars == <<diskState, memState, legacyState, failedRestore, generation, lastLoadResult>>

DiskValues == {"EMPTY", "VALID", "CORRUPTED"}
MemValues == {"EMPTY", "VALID", "CORRUPTED", "DIRTY_NEW"}
LegacyValues == {"NONE", "VALID", "CORRUPTED"}
LoadResults == {"NONE", "LOADED_VALID", "LOADED_EMPTY", "REJECTED"}

\* 显式状态投影
StoreState ==
    IF failedRestore THEN "RESTORE_FAILED_LOCKED"
    ELSE IF memState = "CORRUPTED" THEN "CORRUPTED"
    ELSE IF memState = "VALID" THEN "READY"
    ELSE "UNINITIALIZED"

TypeOK ==
    /\ diskState \in DiskValues
    /\ memState \in MemValues
    /\ legacyState \in LegacyValues
    /\ failedRestore \in BOOLEAN
    /\ generation \in 0..MaxGen
    /\ lastLoadResult \in LoadResults

Init ==
    /\ diskState \in DiskValues
    /\ memState = diskState
    /\ legacyState \in LegacyValues
    /\ failedRestore = FALSE
    /\ generation = 0
    /\ lastLoadResult = "NONE"

\* 1. 读取配置 load()
LoadConfig ==
    IF failedRestore THEN
        /\ lastLoadResult' = "REJECTED"
        /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>
    ELSE IF memState = "CORRUPTED" THEN
        /\ lastLoadResult' = "REJECTED"
        /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>
    ELSE IF memState = "VALID" THEN
        /\ lastLoadResult' = "LOADED_VALID"
        /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>
    ELSE \* memState = "EMPTY" -> 尝试旧版迁移
        IF legacyState = "CORRUPTED" THEN
            \* 关键安全不变量：旧过滤配置损坏绝不静默降级为全部转发
            /\ lastLoadResult' = "REJECTED"
            /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>
        ELSE IF legacyState = "NONE" THEN
            /\ lastLoadResult' = "LOADED_EMPTY"
            /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>
        ELSE \* legacyState = "VALID" -> 迁移并落盘（成功）
            /\ generation < MaxGen
            /\ diskState' = "VALID"
            /\ memState' = "VALID"
            /\ generation' = generation + 1
            /\ lastLoadResult' = "LOADED_VALID"
            /\ UNCHANGED <<legacyState, failedRestore>>

\* 2. 保存合法配置成功：同时更新磁盘、内存，解锁 failedRestore，递增 generation
SaveSuccess ==
    /\ generation < MaxGen
    /\ diskState' = "VALID"
    /\ memState' = "VALID"
    /\ failedRestore' = FALSE
    /\ generation' = generation + 1
    /\ UNCHANGED <<legacyState, lastLoadResult>>

\* 3. 保存失败但回滚成功：磁盘不变，内存恢复为磁盘原值，不锁定
SaveFailRestoreSuccess ==
    /\ memState' = diskState
    /\ failedRestore' = FALSE
    /\ UNCHANGED <<diskState, legacyState, generation, lastLoadResult>>

\* 4. 保存失败且回滚也失败：内存残留脏值，强制锁定 failedRestore = TRUE
SaveFailRestoreFail ==
    /\ memState' = "DIRTY_NEW"
    /\ failedRestore' = TRUE
    /\ UNCHANGED <<diskState, legacyState, generation, lastLoadResult>>

\* 5. 增量更新（如删除/编辑单个目的地或规则）：若当前损坏或回滚锁定，绝不能用空配置静默覆盖
SafeUpdateRejected ==
    /\ (failedRestore \/ memState = "CORRUPTED")
    /\ lastLoadResult' = "REJECTED"
    /\ UNCHANGED <<diskState, memState, legacyState, failedRestore, generation>>

\* 6. 进程重启：内存锁与脏内存清空，重新从磁盘加载
ProcessRestart ==
    /\ memState' = diskState
    /\ failedRestore' = FALSE
    /\ lastLoadResult' = "NONE"
    /\ UNCHANGED <<diskState, legacyState, generation>>

Next ==
    \/ LoadConfig
    \/ SaveSuccess
    \/ SaveFailRestoreSuccess
    \/ SaveFailRestoreFail
    \/ SafeUpdateRejected
    \/ ProcessRestart

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 脏内存绝不会在未锁定的情况下暴露给调用方
DirtyMemoryAlwaysLocked ==
    memState = "DIRTY_NEW" => failedRestore

\* 处于回滚失败锁定时，如果刚执行过 load，只能是 REJECTED（或保持上一次状态）
LockedNeverReadsDirty ==
    failedRestore => (memState = "DIRTY_NEW")

\* 当磁盘和旧版配置均损坏时，不可能在重启后加载出有效配置
NoSilentFallbackOnCorrupted ==
    (diskState = "CORRUPTED" /\ memState = "CORRUPTED" /\ ~failedRestore) =>
        (lastLoadResult \in {"NONE", "REJECTED"})

StoreStateWellDefined ==
    StoreState \in {"UNINITIALIZED", "READY", "CORRUPTED", "RESTORE_FAILED_LOCKED"}

=============================================================================
