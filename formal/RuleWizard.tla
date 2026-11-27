--------------------------- MODULE RuleWizard ---------------------------
(***************************************************************************)
(* 新建规则 2 步向导与内联异步通道测试状态机（对应 Android                 *)
(* RuleWizardState + ChannelTestMachine / RuleWizardActivity）。           *)
(*                                                                         *)
(* 显式建模：                                                               *)
(* 1. 向导步骤：CHANNEL <-> CONDITION                                      *)
(* 2. 渠道模式：SELECT_EXISTING <-> CREATE_NEW（随已有渠道列表动态变化）    *)
(* 3. 内联异步测试：IDLE -> SENDING(seq) -> SUCCEEDED / FAILED / CANCELLED *)
(*    以及页面销毁 destroyed 与乱序网络回调                                *)
(* 4. 原子保存：当已有渠道在向导外被删除时安全回退到 CHANNEL 步骤           *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANTS
    Dests,      \* 已有渠道 ID 全集，例如 {"d1", "d2"}
    MaxSeq      \* 测试请求代际上限

VARIABLES
    step,           \* "CHANNEL" | "CONDITION"
    existingDests,  \* 当前配置中实际存在的渠道集合：SUBSET Dests
    channelMode,    \* "SELECT_EXISTING" | "CREATE_NEW"
    selectedDest,   \* 已选渠道 ID \cup {"NONE"}
    draftValid,     \* 新建渠道草稿是否校验通过：BOOLEAN
    testState,      \* "IDLE" | "SENDING" | "SUCCEEDED" | "FAILED"
    currentSeq,     \* 当前最新测试请求代际：0..MaxSeq
    inFlightSeqs,   \* 网络层在途的测试请求代际集合：SUBSET (1..MaxSeq)
    destroyed,      \* Activity 是否已销毁：BOOLEAN
    savedOk         \* 是否已原子保存成功：BOOLEAN

vars == <<step, existingDests, channelMode, selectedDest, draftValid,
          testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

TypeOK ==
    /\ step \in {"CHANNEL", "CONDITION"}
    /\ existingDests \subseteq Dests
    /\ channelMode \in {"SELECT_EXISTING", "CREATE_NEW"}
    /\ selectedDest \in Dests \cup {"NONE"}
    /\ draftValid \in BOOLEAN
    /\ testState \in {"IDLE", "SENDING", "SUCCEEDED", "FAILED"}
    /\ currentSeq \in 0..MaxSeq
    /\ inFlightSeqs \subseteq (1..MaxSeq)
    /\ destroyed \in BOOLEAN
    /\ savedOk \in BOOLEAN

Init ==
    /\ existingDests \in SUBSET Dests
    /\ step = "CHANNEL"
    /\ channelMode = IF existingDests /= {} THEN "SELECT_EXISTING" ELSE "CREATE_NEW"
    /\ selectedDest = IF existingDests /= {} THEN CHOOSE d \in existingDests : TRUE ELSE "NONE"
    /\ draftValid = FALSE
    /\ testState = "IDLE"
    /\ currentSeq = 0
    /\ inFlightSeqs = {}
    /\ destroyed = FALSE
    /\ savedOk = FALSE

\* 1. 切换渠道来源模式
SwitchToCreate ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CHANNEL"
    /\ channelMode = "SELECT_EXISTING"
    /\ channelMode' = "CREATE_NEW"
    /\ UNCHANGED <<step, existingDests, selectedDest, draftValid,
                   testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

SwitchToExisting ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CHANNEL"
    /\ channelMode = "CREATE_NEW"
    /\ existingDests /= {}
    /\ channelMode' = "SELECT_EXISTING"
    /\ selectedDest' = IF selectedDest \in existingDests THEN selectedDest
                       ELSE CHOOSE d \in existingDests : TRUE
    /\ UNCHANGED <<step, existingDests, draftValid,
                   testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

\* 2. 编辑草稿（递增 currentSeq 并重置测试状态，使旧在途回调自动失效）
EditDraft(valid) ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CHANNEL"
    /\ channelMode = "CREATE_NEW"
    /\ valid \in BOOLEAN
    /\ currentSeq < MaxSeq
    /\ draftValid' = valid
    /\ currentSeq' = currentSeq + 1
    /\ testState' = "IDLE"
    /\ UNCHANGED <<step, existingDests, channelMode, selectedDest,
                   inFlightSeqs, destroyed, savedOk>>

\* 3. 发起内联测试消息（仅在 IDLE/SUCCEEDED/FAILED 且草稿合法时）
StartChannelTest ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CHANNEL"
    /\ channelMode = "CREATE_NEW"
    /\ draftValid
    /\ testState /= "SENDING"
    /\ currentSeq < MaxSeq
    /\ currentSeq' = currentSeq + 1
    /\ testState' = "SENDING"
    /\ inFlightSeqs' = inFlightSeqs \cup {currentSeq + 1}
    /\ UNCHANGED <<step, existingDests, channelMode, selectedDest,
                   draftValid, destroyed, savedOk>>

\* 4. 异步网络回调到达（可能乱序、可能在用户修改输入或销毁页面后到达）
CompleteChannelTest(seq, ok) ==
    /\ seq \in inFlightSeqs
    /\ ok \in BOOLEAN
    /\ inFlightSeqs' = inFlightSeqs \ {seq}
    /\ IF ~destroyed /\ seq = currentSeq /\ testState = "SENDING" THEN
           testState' = IF ok THEN "SUCCEEDED" ELSE "FAILED"
       ELSE
           \* 过期回调或已销毁页面：绝不覆盖当前 UI 状态
           UNCHANGED testState
    /\ UNCHANGED <<step, existingDests, channelMode, selectedDest,
                   draftValid, currentSeq, destroyed, savedOk>>

\* 5. 进入第 2 步：要求渠道合法
ProceedToCondition ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CHANNEL"
    /\ (channelMode = "SELECT_EXISTING" /\ selectedDest \in existingDests)
       \/ (channelMode = "CREATE_NEW" /\ draftValid)
    /\ step' = "CONDITION"
    /\ UNCHANGED <<existingDests, channelMode, selectedDest, draftValid,
                   testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

\* 6. 返回第 1 步
GoBack ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CONDITION"
    /\ step' = "CHANNEL"
    /\ UNCHANGED <<existingDests, channelMode, selectedDest, draftValid,
                   testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

\* 7. 外部页面修改/删除了已有渠道，触发 refreshDestinations
ExternalRemoveDest(d) ==
    /\ ~destroyed /\ ~savedOk
    /\ d \in existingDests
    /\ LET rem == existingDests \ {d} IN
       /\ existingDests' = rem
       /\ IF rem = {} THEN
              /\ selectedDest' = "NONE"
              /\ channelMode' = "CREATE_NEW"
          ELSE
              /\ selectedDest' = IF selectedDest \in rem THEN selectedDest
                                 ELSE CHOOSE x \in rem : TRUE
              /\ UNCHANGED channelMode
    /\ UNCHANGED <<step, draftValid, testState, currentSeq, inFlightSeqs, destroyed, savedOk>>

\* 8. 在第 2 步点击「完成并启用」原子保存
AttemptSave ==
    /\ ~destroyed /\ ~savedOk
    /\ step = "CONDITION"
    /\ IF (channelMode = "CREATE_NEW" /\ draftValid)
          \/ (channelMode = "SELECT_EXISTING" /\ selectedDest \in existingDests) THEN
           /\ savedOk' = TRUE
           /\ UNCHANGED step
       ELSE
           \* 渠道已失效或被外部删除：安全退回第 1 步，不产生悬空规则
           /\ step' = "CHANNEL"
           /\ UNCHANGED savedOk
    /\ UNCHANGED <<existingDests, channelMode, selectedDest, draftValid,
                   testState, currentSeq, inFlightSeqs, destroyed>>

\* 9. 页面销毁（Activity onDestroy）
DestroyPage ==
    /\ ~destroyed
    /\ destroyed' = TRUE
    /\ testState' = "IDLE"
    /\ UNCHANGED <<step, existingDests, channelMode, selectedDest, draftValid,
                   currentSeq, inFlightSeqs, savedOk>>

Next ==
    \/ SwitchToCreate
    \/ SwitchToExisting
    \/ \E v \in BOOLEAN : EditDraft(v)
    \/ StartChannelTest
    \/ \E s \in 1..MaxSeq, ok \in BOOLEAN : CompleteChannelTest(s, ok)
    \/ ProceedToCondition
    \/ GoBack
    \/ \E d \in Dests : ExternalRemoveDest(d)
    \/ AttemptSave
    \/ DestroyPage
    \/ (destroyed /\ inFlightSeqs = {} /\ UNCHANGED vars)
    \/ (savedOk /\ inFlightSeqs = {} /\ UNCHANGED vars)

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 无已有渠道时，绝不可能停留在 SELECT_EXISTING 模式
CreateNewWhenNoExisting ==
    existingDests = {} => channelMode = "CREATE_NEW"

\* 处于 SELECT_EXISTING 模式时，selectedDest 必在 existingDests 中
SelectedDestAlwaysValid ==
    channelMode = "SELECT_EXISTING" => selectedDest \in existingDests

\* 已销毁的页面绝不会再显示 SENDING / SUCCEEDED / FAILED
DestroyedPageNeverShowsTestResult ==
    destroyed => testState = "IDLE"

\* 原子保存成功时，目标渠道必然合法（无悬空规则）
SavedRuleNeverDangles ==
    savedOk =>
        \/ (channelMode = "CREATE_NEW" /\ draftValid)
        \/ (channelMode = "SELECT_EXISTING" /\ selectedDest \in existingDests)

=============================================================================
