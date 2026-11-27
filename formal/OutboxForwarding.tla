--------------------------- MODULE OutboxForwarding ---------------------------
(***************************************************************************)
(* 离线队列与并发投递状态机（对应 Android OutboxStateMachine / Store /      *)
(* ForwardJob / RecoveryJob / SmsReceiver / MainActivity）。                *)
(*                                                                         *)
(* 显式建模：                                                               *)
(* 1. 队列条目状态：QUEUED(0) -> RETRYING(1..MaxAttempts-1) -> EXHAUSTED   *)
(*    -> DELIVERED / CLEARED                                               *)
(* 2. 投递任务生命周期：IDLE -> PICKED -> SENDING -> IDLE                  *)
(* 3. 并发代际控制：enabled + generation + runStopped + failedDests        *)
(* 4. 去重指纹表 seen（按 sms:dest 维度）与队列容量上限 MaxQueue            *)
(*                                                                         *)
(* 关键修复：                                                               *)
(* - 暂停转发（DisableForwarding）同步递增 generation；                     *)
(* - 失败记录（FinishSendFail）严格检查 runGen = generation，防止旧 Run     *)
(*   在“关->开”切换间隙污染新代际下的重试计数。                             *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS
    SmsIds,         \* 短信集合，例如 {"s1", "s2"}
    Dests,          \* 目的地集合，例如 {"d1", "d2"}
    MaxQueue,       \* 队列容量上限（生产为 200，模型缩为 2）
    MaxAttempts,    \* 最大重试次数（生产为 8，模型缩为 2）
    MaxGen          \* 代际上限（有界模型检验）

VARIABLES
    enabled,        \* 主控转发开关：BOOLEAN
    generation,     \* 全局配置/开关代际：0..MaxGen
    queue,          \* 队列映射：[Entry -> 0..MaxAttempts]，空表示未入队/已删除
    seen,           \* 去重集合：SUBSET (SmsIds \X Dests)
    jobState,       \* 当前 ForwardJob 状态："IDLE" | "SENDING"
    runGen,         \* 当前 Run 启动时捕获的 generation
    runStopped,     \* 系统 onStopJob 标记：BOOLEAN
    inFlight,       \* 当前正在投递的条目：Entry \cup {"NONE"}
    failedDests,    \* 当前 Run 内已失败隔离的目的地：SUBSET Dests
    destHealthy,    \* 目的地当前是否有效且网络可达：[Dests -> BOOLEAN]
    staleFailBlocked \* 审计计数：拦截到旧代际 Run 尝试写回失败的次数（验证可达性）

vars == <<enabled, generation, queue, seen, jobState, runGen, runStopped,
          inFlight, failedDests, destHealthy, staleFailBlocked>>

Entries == SmsIds \X Dests
NoEntry == <<"NONE", "NONE">>
ItemStatus(a) ==
    IF a = 0 THEN "QUEUED"
    ELSE IF a < MaxAttempts THEN "RETRYING"
    ELSE "EXHAUSTED"

EligibleEntries(excl) ==
    { e \in DOMAIN queue : queue[e] < MaxAttempts /\ e[2] \notin excl }

RunPermitted ==
    /\ jobState = "SENDING"
    /\ ~runStopped
    /\ enabled
    /\ runGen = generation

TypeOK ==
    /\ enabled \in BOOLEAN
    /\ generation \in 0..MaxGen
    /\ DOMAIN queue \subseteq Entries
    /\ \A e \in DOMAIN queue : queue[e] \in 0..MaxAttempts
    /\ seen \subseteq Entries
    /\ jobState \in {"IDLE", "SENDING"}
    /\ runGen \in 0..MaxGen
    /\ runStopped \in BOOLEAN
    /\ inFlight \in Entries \cup {NoEntry}
    /\ failedDests \subseteq Dests
    /\ destHealthy \in [Dests -> BOOLEAN]
    /\ staleFailBlocked \in 0..4

Init ==
    /\ enabled = FALSE
    /\ generation = 0
    /\ queue = [e \in {} |-> 0]
    /\ seen = {}
    /\ jobState = "IDLE"
    /\ runGen = 0
    /\ runStopped = FALSE
    /\ inFlight = NoEntry
    /\ failedDests = {}
    /\ destHealthy = [d \in Dests |-> TRUE]
    /\ staleFailBlocked = 0

\* 1. 开启转发：要求有界 generation 递增
EnableForwarding ==
    /\ ~enabled
    /\ generation < MaxGen
    /\ enabled' = TRUE
    /\ generation' = generation + 1
    /\ UNCHANGED <<queue, seen, jobState, runGen, runStopped,
                   inFlight, failedDests, destHealthy, staleFailBlocked>>

\* 2. 暂停转发：同步递增 generation 并触发 onStopJob 语义（若任务运行中）
DisableForwarding ==
    /\ enabled
    /\ generation < MaxGen
    /\ enabled' = FALSE
    /\ generation' = generation + 1
    /\ runStopped' = IF jobState = "SENDING" THEN TRUE ELSE runStopped
    /\ UNCHANGED <<queue, seen, jobState, runGen, inFlight,
                   failedDests, destHealthy, staleFailBlocked>>

\* 3. 配置保存变更：递增 generation，强制正在运行的 Run 重新校验
ConfigChanged ==
    /\ generation < MaxGen
    /\ generation' = generation + 1
    /\ UNCHANGED <<enabled, queue, seen, jobState, runGen, runStopped,
                   inFlight, failedDests, destHealthy, staleFailBlocked>>

\* 4. 收到新短信并经路由入队（受 enabled、去重表 seen 与队列上限 MaxQueue 约束）
ReceiveSms(s, d) ==
    LET e == <<s, d>> IN
    /\ enabled
    /\ e \notin seen
    /\ e \notin DOMAIN queue
    /\ Cardinality(DOMAIN queue) < MaxQueue
    /\ queue' = [x \in DOMAIN queue \cup {e} |-> IF x = e THEN 0 ELSE queue[x]]
    /\ seen' = seen \cup {e}
    /\ UNCHANGED <<enabled, generation, jobState, runGen, runStopped,
                   inFlight, failedDests, destHealthy, staleFailBlocked>>

\* 5. ForwardJob 启动并选取一条可投递条目（排除本轮已隔离的目的地）
StartSend(e) ==
    /\ jobState = "IDLE"
    /\ enabled
    /\ e \in EligibleEntries(failedDests)
    /\ jobState' = "SENDING"
    /\ runGen' = generation
    /\ runStopped' = FALSE
    /\ inFlight' = e
    /\ UNCHANGED <<enabled, generation, queue, seen, failedDests,
                   destHealthy, staleFailBlocked>>

\* 6. 系统抢占停止当前 Job（如切网/省电）
SystemStopJob ==
    /\ jobState = "SENDING"
    /\ ~runStopped
    /\ runStopped' = TRUE
    /\ UNCHANGED <<enabled, generation, queue, seen, jobState, runGen,
                   inFlight, failedDests, destHealthy, staleFailBlocked>>

\* 7. 投递成功：服务端确认 200/code=0，立即删除队列正文，不留存明文
FinishSendSuccess ==
    /\ jobState = "SENDING"
    /\ inFlight \in DOMAIN queue
    /\ destHealthy[inFlight[2]]
    /\ queue' = [x \in DOMAIN queue \ {inFlight} |-> queue[x]]
    /\ jobState' = "IDLE"
    /\ inFlight' = NoEntry
    /\ runStopped' = FALSE
    /\ UNCHANGED <<enabled, generation, seen, runGen, failedDests,
                   destHealthy, staleFailBlocked>>

\* 8. 投递失败（有效代际）：attempts + 1，并将该目的地加入本轮隔离集
FinishSendFailValid ==
    /\ RunPermitted
    /\ inFlight \in DOMAIN queue
    /\ ~destHealthy[inFlight[2]]
    /\ queue' = [queue EXCEPT ![inFlight] = @ + 1]
    /\ failedDests' = failedDests \cup {inFlight[2]}
    /\ jobState' = "IDLE"
    /\ inFlight' = NoEntry
    /\ runStopped' = FALSE
    /\ UNCHANGED <<enabled, generation, seen, runGen, destHealthy, staleFailBlocked>>

\* 9. 投递失败但 Run 已过期（被停止、已暂停或 generation 已变更）：安全丢弃，不污染 attempts
FinishSendFailStale ==
    /\ jobState = "SENDING"
    /\ ~RunPermitted
    /\ jobState' = "IDLE"
    /\ inFlight' = NoEntry
    /\ runStopped' = FALSE
    /\ staleFailBlocked' = IF staleFailBlocked < 4 THEN staleFailBlocked + 1 ELSE staleFailBlocked
    /\ UNCHANGED <<enabled, generation, queue, seen, runGen, failedDests, destHealthy>>

\* 10. 一轮 Job 结束重置隔离表（模拟下一次 JobScheduler 唤醒）
ResetRunIsolation ==
    /\ jobState = "IDLE"
    /\ failedDests /= {}
    /\ failedDests' = {}
    /\ UNCHANGED <<enabled, generation, queue, seen, jobState, runGen,
                   runStopped, inFlight, destHealthy, staleFailBlocked>>

\* 11. 用户手动点击「重试发送」（仅在 enabled 时允许）：重置所有条目 attempts 为 0
ManualRetry ==
    /\ enabled
    /\ jobState = "IDLE"
    /\ \E e \in DOMAIN queue : queue[e] > 0
    /\ queue' = [e \in DOMAIN queue |-> 0]
    /\ failedDests' = {}
    /\ UNCHANGED <<enabled, generation, seen, jobState, runGen,
                   runStopped, inFlight, destHealthy, staleFailBlocked>>

\* 12. 用户手动点击「清空队列」（仅在 !enabled 时允许）
ManualClear ==
    /\ ~enabled
    /\ jobState = "IDLE"
    /\ DOMAIN queue /= {}
    /\ queue' = [e \in {} |-> 0]
    /\ failedDests' = {}
    /\ UNCHANGED <<enabled, generation, seen, jobState, runGen,
                   runStopped, inFlight, destHealthy, staleFailBlocked>>

\* 13. 外部环境：目的地网络/配置健康状态切换
ToggleDestHealth(d) ==
    /\ destHealthy' = [destHealthy EXCEPT ![d] = ~@]
    /\ UNCHANGED <<enabled, generation, queue, seen, jobState, runGen,
                   runStopped, inFlight, failedDests, staleFailBlocked>>

Next ==
    \/ EnableForwarding
    \/ DisableForwarding
    \/ ConfigChanged
    \/ \E s \in SmsIds, d \in Dests : ReceiveSms(s, d)
    \/ \E e \in Entries : StartSend(e)
    \/ SystemStopJob
    \/ FinishSendSuccess
    \/ FinishSendFailValid
    \/ FinishSendFailStale
    \/ ResetRunIsolation
    \/ ManualRetry
    \/ ManualClear
    \/ \E d \in Dests : ToggleDestHealth(d)

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 队列容量永不超限
QueueCapacityBound == Cardinality(DOMAIN queue) <= MaxQueue

\* 重试次数永不越界（不会超过 MaxAttempts）
AttemptsBound == \A e \in DOMAIN queue : queue[e] \in 0..MaxAttempts

\* 已入队条目必然已登记在去重表 seen 中（不会出现未登记重复入队）
QueuedItemsAlwaysSeen == DOMAIN queue \subseteq seen

\* 空闲态没有在途消息，发送态必有在途消息
JobInFlightConsistency ==
    /\ (jobState = "IDLE" => inFlight = NoEntry)
    /\ (jobState = "SENDING" => inFlight \in Entries)

\* 发送态的在途条目不属于本轮已隔离的目的地（单目的地隔离不变量）
IsolationPerRun ==
    jobState = "SENDING" => inFlight[2] \notin failedDests

\* 队列中若某条目被清空或不存在，不会出现负计数
AllStatusesWellDefined ==
    \A e \in DOMAIN queue : ItemStatus(queue[e]) \in {"QUEUED", "RETRYING", "EXHAUSTED"}

=============================================================================
