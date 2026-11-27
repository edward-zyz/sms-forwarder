--------------------------- MODULE IosForwardPipeline ---------------------------
(***************************************************************************)
(* iOS Scriptable 单次执行决策状态机（对应 ios/短信转飞书.js）。            *)
(*                                                                         *)
(* 显式建模单次调用的完整阶段流转：                                        *)
(*   INIT -> CONFIGURE -> CONFIGURED / CONFIG_CANCELLED / FAILED           *)
(*        -> VALIDATE_INPUT -> SKIPPED / FAILED                            *)
(*        -> LOAD_CONFIG -> FAILED                                         *)
(*        -> BUILD_REQUEST -> FAILED                                       *)
(*        -> HTTP_POST -> CHECK_RECEIPT -> SENT / FAILED                   *)
(*                                                                         *)
(* 严格验证安全边界：输入/配置/体积任一不合法绝不发起网络请求；             *)
(* 仅在平台回执严格确认后进入 SENT；单次执行至多 1 次网络请求，绝不重试。   *)
(***************************************************************************)
EXTENDS Integers

VARIABLES
    stage,          \* 当前执行阶段
    runsInApp,      \* 是否在 Scriptable App 内直接运行
    inputKind,      \* "EMPTY" | "INVALID_TYPE" | "BLANK" | "TOO_LARGE" | "VALID"
    configKind,     \* "MISSING" | "CORRUPTED" | "INVALID_URL" | "FEISHU" | "WECOM" | "DINGTALK" | "CUSTOM"
    payloadOk,      \* 序列化后 JSON 是否 <= 20KB
    httpStatus,     \* 0(未请求/网络错) | 200 | 204 | 500
    receiptOk,      \* 平台业务回执 code=0 / errcode=0 是否为真
    networkCalls    \* 已发起的网络请求次数：0..1

vars == <<stage, runsInApp, inputKind, configKind, payloadOk,
          httpStatus, receiptOk, networkCalls>>

Stages == {
    "INIT", "CONFIGURE", "VALIDATE_INPUT", "LOAD_CONFIG",
    "BUILD_REQUEST", "HTTP_POST", "CHECK_RECEIPT",
    "CONFIGURED", "CONFIG_CANCELLED", "SKIPPED", "SENT", "FAILED"
}
TerminalStages == {"CONFIGURED", "CONFIG_CANCELLED", "SKIPPED", "SENT", "FAILED"}

TypeOK ==
    /\ stage \in Stages
    /\ runsInApp \in BOOLEAN
    /\ inputKind \in {"EMPTY", "INVALID_TYPE", "BLANK", "TOO_LARGE", "VALID"}
    /\ configKind \in {"MISSING", "CORRUPTED", "INVALID_URL", "FEISHU", "WECOM", "DINGTALK", "CUSTOM"}
    /\ payloadOk \in BOOLEAN
    /\ httpStatus \in {0, 200, 204, 500}
    /\ receiptOk \in BOOLEAN
    /\ networkCalls \in 0..1

Init ==
    /\ stage = "INIT"
    /\ runsInApp \in BOOLEAN
    /\ inputKind \in {"EMPTY", "INVALID_TYPE", "BLANK", "TOO_LARGE", "VALID"}
    /\ configKind \in {"MISSING", "CORRUPTED", "INVALID_URL", "FEISHU", "WECOM", "DINGTALK", "CUSTOM"}
    /\ payloadOk \in BOOLEAN
    /\ httpStatus \in {0, 200, 204, 500}
    /\ receiptOk \in BOOLEAN
    /\ networkCalls = 0

\* 1. 入口路由：无输入时 App 内进入配置，快捷指令调用报错；有输入进入输入校验
DispatchEntry ==
    /\ stage = "INIT"
    /\ stage' = IF inputKind = "EMPTY" THEN
                    IF runsInApp THEN "CONFIGURE" ELSE "FAILED"
                ELSE "VALIDATE_INPUT"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 2. 交互式配置（仅保存钥匙串，不发网络）
RunConfigure ==
    /\ stage = "CONFIGURE"
    /\ stage' \in {"CONFIGURED", "CONFIG_CANCELLED", "FAILED"}
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 3. 输入校验（类型、非空正文、<= 16KB）
ValidateInput ==
    /\ stage = "VALIDATE_INPUT"
    /\ stage' = IF inputKind = "BLANK" THEN "SKIPPED"
                ELSE IF inputKind = "VALID" THEN "LOAD_CONFIG"
                ELSE "FAILED"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 4. 读取钥匙串配置并识别平台
LoadConfig ==
    /\ stage = "LOAD_CONFIG"
    /\ stage' = IF configKind \in {"FEISHU", "WECOM", "DINGTALK", "CUSTOM"} THEN "BUILD_REQUEST"
                ELSE "FAILED"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 5. 构造签名与载荷，校验 <= 20KB
BuildRequest ==
    /\ stage = "BUILD_REQUEST"
    /\ stage' = IF payloadOk THEN "HTTP_POST" ELSE "FAILED"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 6. 发起单次 HTTPS POST（永不自动重试）
SendHttp ==
    /\ stage = "HTTP_POST"
    /\ networkCalls = 0
    /\ networkCalls' = 1
    /\ stage' = IF httpStatus = 0 THEN "FAILED" ELSE "CHECK_RECEIPT"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk>>

\* 7. 严格回执校验：custom 需 HTTP 2xx；飞书/企微/钉钉需 HTTP 200 且 code/errcode = 0
CheckReceipt ==
    /\ stage = "CHECK_RECEIPT"
    /\ LET accepted ==
           IF configKind = "CUSTOM" THEN httpStatus \in {200, 204}
           ELSE (httpStatus = 200 /\ receiptOk)
       IN stage' = IF accepted THEN "SENT" ELSE "FAILED"
    /\ UNCHANGED <<runsInApp, inputKind, configKind, payloadOk, httpStatus, receiptOk, networkCalls>>

\* 终态自环（表示正常结束，非死锁）
Terminated ==
    /\ stage \in TerminalStages
    /\ UNCHANGED vars

Next ==
    \/ DispatchEntry
    \/ RunConfigure
    \/ ValidateInput
    \/ LoadConfig
    \/ BuildRequest
    \/ SendHttp
    \/ CheckReceipt
    \/ Terminated

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 任何非法输入、非法配置或超限载荷绝不发起网络请求
NoNetworkOnInvalidPrecondition ==
    networkCalls > 0 =>
        /\ inputKind = "VALID"
        /\ configKind \in {"FEISHU", "WECOM", "DINGTALK", "CUSTOM"}
        /\ payloadOk

\* 只有通过平台严格回执校验才能进入 SENT
SentRequiresStrictReceipt ==
    stage = "SENT" =>
        /\ networkCalls = 1
        /\ IF configKind = "CUSTOM" THEN httpStatus \in {200, 204}
           ELSE (httpStatus = 200 /\ receiptOk)

\* 配置模式与跳过模式绝不发网络
ConfigureOrSkipNeverSends ==
    stage \in {"CONFIGURE", "CONFIGURED", "CONFIG_CANCELLED", "SKIPPED"} => networkCalls = 0

\* 单次执行至多发起 1 次网络请求（绝不自动重试）
AtMostOneNetworkCall == networkCalls <= 1

=============================================================================
