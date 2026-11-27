# TLA+ 形式化验证（Formal Verification）

本目录存放短信自动转发项目全部核心状态机的 TLA+ 形式化规约与 TLC 模型检验脚本，用于对系统的并发边界、持久化回滚、就绪状态与执行链路进行穷举状态空间验证。

## 状态机与代码映射表

| # | TLA+ 模型 | 对应代码模块 | 验证的核心安全性不变量 |
| :--- | :--- | :--- | :--- |
| 1 | [`OutboxForwarding.tla`](OutboxForwarding.tla) | `OutboxStateMachine.kt`、`Store.kt`、`ForwardJob.kt`、`SmsReceiver.kt`、`MainActivity.kt` | 队列容量上限、重试次数上界、已入队必已去重、在途状态一致、单轮目的地隔离、旧代际 Run 不污染失败计数、仅暂停可清空队列 |
| 2 | [`ServiceReadiness.tla`](ServiceReadiness.tla) | `ServiceReadinessMachine.kt`、`MainActivity.kt` | `RUNNING` 必然同时满足权限/配置/有效规则就绪、配置损坏优先覆盖运行态、`DEGRADED_ENABLED` 显式可安全暂停、开启确认弹窗仅在关闭态出现 |
| 3 | [`ConfigPersistence.tla`](ConfigPersistence.tla) | `ConfigPersistenceMachine.kt`、`ConfigStore.kt`、`FilterSettings.kt` | `commit()` 失败且回滚失败时脏内存强制锁定拒绝 `load()`、损坏配置与旧版损坏过滤配置绝不静默降级、成功保存或重启后安全恢复 |
| 4 | [`FilterEditor.tla`](FilterEditor.tla) | `FilterEditor.kt` | 验证码平台集合永不为空、`ALL` 必关过滤、`VERIFICATION`/`KEYWORDS` 必开过滤、跨模式往返不丢关键词草稿/自定义排除词/发件人白名单 |
| 5 | [`RuleWizard.tla`](RuleWizard.tla) | `RuleWizardState.kt`（含 `ChannelTestMachine`）、`RuleWizardActivity.kt` | 无已有渠道必为新建模式、选中渠道必存在、输入变更或页面销毁后过期测试回调绝不覆盖 UI、原子保存绝不产生悬空规则 |
| 6 | [`IosForwardPipeline.tla`](IosForwardPipeline.tla) | `ios/短信转飞书.js`（`PIPELINE_STAGES` / `PIPELINE_TRANSITIONS`） | 非法输入/配置/超限载荷绝不发网、仅严格通过平台回执校验才达 `SENT`、配置与跳过分支零网络请求、单次执行至多 1 次请求不重试 |

## 本地运行与 CI 门禁

在仓库根目录执行（需 JDK 17+，首次运行自动下载并校验 `tla2tools.jar` SHA-256）：

```bash
bash formal/verify.sh
```

验证门禁包含三层保障：
1. **TLC 穷举模型检验**：对全部 6 个模型逐一探索完整可达状态空间，要求 **0 安全性不变量违规**、**0 意外死锁**。
2. **动作零覆盖拦截**：自动解析 TLC `-coverage 1` 报告，若出现任何 `0:0` 不可达动作则判定失败，确保每个声明状态与迁移路径均被实际验证。
3. **代码-模型一致性测试**：
   - Android：`StateMachinesConformanceTest.kt` 穷举校验纯 Kotlin 状态机的全部状态与组合边界；
   - iOS：`ios/pipeline-state-machine.test.cjs` 校验阶段集合、转换表与非法跃迁拦截；
   - CI：`.github/workflows/ci.yml` 的 `formal` Job 与 `android`、`ios` 门禁并行强制执行。

## 日常演进规范

修改涉及队列调度、主控开关、配置持久化、规则编辑器、向导流程或 iOS 执行管道的逻辑时：
1. **先改规约**：同步更新对应 `formal/*.tla` 与不变量，运行 `bash formal/verify.sh` 确认新状态空间无违规、无死锁、无零覆盖动作；
2. **再改实现**：只通过对应显式状态机模块做状态迁移，不引入新的散落布尔标志或裸字符串状态；
3. **补齐一致测**：在 `StateMachinesConformanceTest.kt` 或 `ios/pipeline-state-machine.test.cjs` 同步覆盖新增状态/事件。
