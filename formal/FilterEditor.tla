--------------------------- MODULE FilterEditor ---------------------------
(***************************************************************************)
(* 过滤条件编辑器 3 模式切换与草稿记忆状态机（对应 Android FilterEditor）。  *)
(*                                                                         *)
(* 显式建模：                                                               *)
(* - mode \in {"VERIFICATION", "KEYWORDS", "ALL"}                          *)
(* - selectedPlatforms（永不为空的验证码平台集合）                          *)
(* - 用户显式编辑过的排除词 userExcludes 与发件人白名单 senders             *)
(* - KEYWORDS 模式的草稿记忆 keywordsDraft                                  *)
(*                                                                         *)
(* 验证核心不变量：任意模式切换序列与平台勾选，绝不丢弃用户配置的高级项     *)
(* 或关键词草稿，且 selectedPlatforms 永不为空。                            *)
(***************************************************************************)
EXTENDS FiniteSets

CONSTANTS
    Platforms,      \* 平台集合，例如 {"all_verif", "meituan", "taobao"}
    AllVerifId      \* 通用保底验证码平台 ID："all_verif"

VARIABLES
    mode,               \* 当前编辑模式："VERIFICATION" | "KEYWORDS" | "ALL"
    filterEnabled,      \* FilterRules.enabled：BOOLEAN
    selectedPlatforms,  \* 当前选中的验证码平台子集（非空）
    lastActiveMode,     \* 上一次非 ALL 的模式："VERIFICATION" | "KEYWORDS"
    excludeEdited,      \* 用户是否手动改过排除词：BOOLEAN
    hasUserExcludes,    \* 用户是否配置了自定义排除词：BOOLEAN
    hasDefaultExcludes, \* 当前规则是否携带默认验证码排除词：BOOLEAN
    hasSenders,         \* 用户是否配置了允许发件号码：BOOLEAN
    hasKeywordsDraft,   \* KEYWORDS 模式下用户是否输入了关键词草稿：BOOLEAN
    activeKeywords      \* 当前 rules.includeKeywords 是否有关键词：BOOLEAN

vars == <<mode, filterEnabled, selectedPlatforms, lastActiveMode,
          excludeEdited, hasUserExcludes, hasDefaultExcludes,
          hasSenders, hasKeywordsDraft, activeKeywords>>

Modes == {"VERIFICATION", "KEYWORDS", "ALL"}

TypeOK ==
    /\ mode \in Modes
    /\ filterEnabled \in BOOLEAN
    /\ selectedPlatforms \subseteq Platforms
    /\ selectedPlatforms /= {}
    /\ lastActiveMode \in {"VERIFICATION", "KEYWORDS"}
    /\ excludeEdited \in BOOLEAN
    /\ hasUserExcludes \in BOOLEAN
    /\ hasDefaultExcludes \in BOOLEAN
    /\ hasSenders \in BOOLEAN
    /\ hasKeywordsDraft \in BOOLEAN
    /\ activeKeywords \in BOOLEAN

Init ==
    /\ mode = "VERIFICATION"
    /\ filterEnabled = TRUE
    /\ selectedPlatforms = {AllVerifId}
    /\ lastActiveMode = "VERIFICATION"
    /\ excludeEdited = FALSE
    /\ hasUserExcludes = FALSE
    /\ hasDefaultExcludes = TRUE
    /\ hasSenders = FALSE
    /\ hasKeywordsDraft = FALSE
    /\ activeKeywords = FALSE

\* 1. 切换到「转发全部」模式
SelectAll ==
    /\ mode /= "ALL"
    /\ mode' = "ALL"
    /\ filterEnabled' = FALSE
    /\ UNCHANGED <<selectedPlatforms, lastActiveMode, excludeEdited,
                   hasUserExcludes, hasDefaultExcludes, hasSenders,
                   hasKeywordsDraft, activeKeywords>>

\* 2. 切换到「只转验证码」模式
SelectVerification ==
    /\ mode /= "VERIFICATION"
    /\ mode' = "VERIFICATION"
    /\ filterEnabled' = TRUE
    /\ lastActiveMode' = "VERIFICATION"
    /\ selectedPlatforms' = IF selectedPlatforms = {} THEN {AllVerifId} ELSE selectedPlatforms
    /\ hasDefaultExcludes' = IF excludeEdited THEN hasDefaultExcludes ELSE TRUE
    /\ activeKeywords' = (selectedPlatforms' /= {AllVerifId})
    /\ UNCHANGED <<excludeEdited, hasUserExcludes, hasSenders, hasKeywordsDraft>>

\* 3. 切换到「按关键词转发」模式
SelectKeywords ==
    /\ mode /= "KEYWORDS"
    /\ mode' = "KEYWORDS"
    /\ filterEnabled' = TRUE
    /\ lastActiveMode' = "KEYWORDS"
    /\ IF lastActiveMode = "VERIFICATION" THEN
           /\ activeKeywords' = hasKeywordsDraft
           /\ hasDefaultExcludes' = IF excludeEdited THEN hasDefaultExcludes ELSE FALSE
           /\ UNCHANGED hasKeywordsDraft
       ELSE
           /\ hasKeywordsDraft' = activeKeywords
           /\ UNCHANGED <<activeKeywords, hasDefaultExcludes>>
    /\ UNCHANGED <<selectedPlatforms, excludeEdited, hasUserExcludes, hasSenders>>

\* 4. 勾选/取消单个验证码平台（保持在 VERIFICATION 模式，且至少保留 AllVerifId）
TogglePlatform(p) ==
    /\ p \in Platforms
    /\ LET toggled == IF p = AllVerifId THEN {AllVerifId}
                      ELSE IF p \in selectedPlatforms THEN
                          IF (selectedPlatforms \ {p}) = {} THEN {AllVerifId}
                          ELSE selectedPlatforms \ {p}
                      ELSE (selectedPlatforms \ {AllVerifId}) \cup {p}
       IN
       /\ selectedPlatforms' = toggled
       /\ mode' = "VERIFICATION"
       /\ filterEnabled' = TRUE
       /\ lastActiveMode' = "VERIFICATION"
       /\ hasDefaultExcludes' = IF excludeEdited THEN hasDefaultExcludes ELSE TRUE
       /\ activeKeywords' = (toggled /= {AllVerifId})
       /\ UNCHANGED <<excludeEdited, hasUserExcludes, hasSenders, hasKeywordsDraft>>

\* 5. 在 KEYWORDS 模式下编辑包含关键词
EditKeywords(val) ==
    /\ mode = "KEYWORDS"
    /\ val \in BOOLEAN
    /\ activeKeywords' = val
    /\ hasKeywordsDraft' = val
    /\ UNCHANGED <<mode, filterEnabled, selectedPlatforms, lastActiveMode,
                   excludeEdited, hasUserExcludes, hasDefaultExcludes, hasSenders>>

\* 6. 用户主动编辑排除词（标记 excludeEdited = TRUE，跨模式保留）
EditExcludes(val) ==
    /\ val \in BOOLEAN
    /\ excludeEdited' = TRUE
    /\ hasUserExcludes' = val
    /\ hasDefaultExcludes' = FALSE
    /\ UNCHANGED <<mode, filterEnabled, selectedPlatforms, lastActiveMode,
                   hasSenders, hasKeywordsDraft, activeKeywords>>

\* 7. 用户配置发件人白名单（跨模式永不丢失）
ToggleSenders ==
    /\ hasSenders' = ~hasSenders
    /\ UNCHANGED <<mode, filterEnabled, selectedPlatforms, lastActiveMode,
                   excludeEdited, hasUserExcludes, hasDefaultExcludes,
                   hasKeywordsDraft, activeKeywords>>

Next ==
    \/ SelectAll
    \/ SelectVerification
    \/ SelectKeywords
    \/ \E p \in Platforms : TogglePlatform(p)
    \/ \E v \in BOOLEAN : EditKeywords(v)
    \/ \E v \in BOOLEAN : EditExcludes(v)
    \/ ToggleSenders

Spec == Init /\ [][Next]_vars

\* ==================== 安全性不变量 ====================

\* 验证码平台集合永不为空
PlatformsNeverEmpty == selectedPlatforms /= {}

\* ALL 模式必然关闭过滤
AllModeDisablesFilter == (mode = "ALL") => ~filterEnabled

\* VERIFICATION / KEYWORDS 模式必然开启过滤
ActiveModesEnableFilter == (mode \in {"VERIFICATION", "KEYWORDS"}) => filterEnabled

\* KEYWORDS 模式下当前关键词与草稿记忆保持同步
KeywordsModeMatchesDraft == (mode = "KEYWORDS") => (activeKeywords = hasKeywordsDraft)

\* 非自定义排除词时，切回 KEYWORDS 不会残留验证码默认排除词
NoStaleDefaultExcludesInKeywords ==
    (mode = "KEYWORDS" /\ ~excludeEdited) => ~hasDefaultExcludes

=============================================================================
