# 架构与数据流

## Android

`SmsReceiver` 接收系统新短信广播并合并分段短信，经 `ConfigStore` 加载多目的地（`Destinations`）与多规则配置，由 `RoutingEngine`（位于 `Destinations.kt`，复用 `FilterRules` 与 `PlatformTemplates` 预置商家模板）依次评估所有已启用规则并按目的地去重；`MessageFormatter` 负责将提取出的验证码与规则名排版为首行高亮纯文本格式，并附带 `State` 中保存的本机号码 / 备注（`接收手机`），再交由 `Store` 分别入队。`ForwardJob` 通过系统 JobScheduler 等待网络，并按条目的目的地 ID 调用 `Destinations` 中对应的协议构造器（飞书、企业微信、钉钉、自定义 HTTPS Webhook）及签名；确认接收后删除队列正文，失败按策略重试且单目的地异常不阻塞其他目的地。

- **核心纯 Kotlin 逻辑**：`ConfigStore`（多目的地/多规则持久化与旧版迁移）、`Destinations`（目的地模型与各平台 Webhook/签名协议）、`PlatformTemplates`（9 个高频商家平台预置模板、三段防误转语义与默认安全排除词）、`FilterRules`（过滤匹配引擎）、`RoutingEngine`（多规则评估与同目的地去重）、`MessageFormatter`（验证码提取、本机号码 / 备注清洗与首行高亮跨通道纯文本排版）。
- **界面层**：`MainActivity` 管理转发总开关、规则/目的地概览、本机号码 / 备注、测试消息、队列和状态；`DestinationsActivity`／`DestinationEditActivity` 管理转发目的地；`RulesActivity` 管理多条转发规则与全局试算；`FilterActivity` 编辑单条规则（名称、目的地、平台模板勾选、开关及过滤条件），`FilterEditor` 保存表单草稿，`FilterDescriptions` 生成可读摘要，`PermissionActivity` 管理权限与放行引导，`Ui` 提供界面共用组件。
- 旧版单飞书配置与待发队列在升级后由 `ConfigStore` 和 `Store` 自动兼容迁移。单元测试放在 `android/app/src/test/`。

接收依赖 `RECEIVE_SMS` 与系统广播，不读取历史短信。入队前筛选不影响已有队列。去重指纹（按短信指纹 + 目的地 ID 维度）、重试上限、正文与请求大小限制等行为详见 [Android 用户指南](android.md)。

## iPhone（双轨架构）

iPhone 端采用双轨方案，均由苹果「快捷指令」的「信息」个人自动化触发（详见 [iOS 双轨说明](../ios/README.md)）：

- **纯快捷指令版（标准版，免装第三方 App）**：
  - 数据流：`短信 → 快捷指令「信息」自动化 → Webhook/备注/正文模板文本 → 按域名条件分支构造原生词典 → 获取 URL 内容（POST，请求体选文件）→ 飞书 / 企业微信 / 钉钉 / 自定义 Webhook`。
  - 飞书与钉钉使用自定义关键词校验（消息正文固定含 `短信转发`），企业微信无需签名，自定义 Webhook 发送 `{"title":"短信转发","text":"..."}`（不带 HMAC 签名）；Webhook 明文存于快捷指令中。仓库提供 [build-shortcut.py](../ios/build-shortcut.py) 生成单个万能快捷指令，经 macOS `shortcuts sign --mode anyone` 签名后提交为 `ios/sms-forwarder-ios.shortcut`，并随 Release 发布；导入时通过「导入问题」填写 Webhook 与本机号码/备注。详见 [纯快捷指令版指南](../ios/shortcuts-guide.md)。
- **Scriptable 版（安全增强版）**：
  - 数据流：`短信 → 快捷指令「信息」自动化 → 提取短信内容文本 → 短信转飞书.js → 飞书 / 企业微信 / 钉钉 / 自定义 Webhook`。
  - `短信转飞书.js` 发送传入的非空正文，按 Webhook 域名自动识别平台（协议与签名算法与 Android 端一致），Webhook、可选 HMAC-SHA256 签名密钥与可选本机号码/备注隔离保存在本机 Scriptable 钥匙串（`sms-forwarder.feishu.v1`），内置字节上限与回执校验，附带模拟网络单测（`ios/*.test.cjs`）。

两条 iOS 路线均不访问历史收件箱，不持久化短信正文，不提供离线队列或自动重试；多个自动化条件重叠命中同一短信时可能产生重复推送。

## 转发目的地协议

- **飞书**：只允许 HTTPS 的 `open.feishu.cn/open-apis/bot/v2/hook/` 地址。签名以 `timestamp + 换行 + secret` 为 HMAC-SHA256 密钥，对空消息计算并编码为 Base64。保留“短信转发”文字供机器人关键词校验使用。需 HTTP 200 且 `code=0` 才确认成功。
- **企业微信**：只允许 HTTPS 的 `qyapi.weixin.qq.com/cgi-bin/webhook/send?key=` 地址。发送 `{"msgtype":"text","text":{"content":"..."}}`，需 HTTP 200 且 `errcode=0` 才确认成功。
- **钉钉**：只允许 HTTPS 的 `oapi.dingtalk.com/robot/send?access_token=` 地址。启用加签时，以 `secret` 为 HMAC-SHA256 密钥对 `毫秒时间戳 + 换行 + secret` 签名、Base64 后 URL 编码并追加 `timestamp` 与 `sign` 查询参数。需 HTTP 200 且 `errcode=0` 才确认成功。
- **自定义 Webhook**：允许任意合法 HTTPS 地址（禁止 userInfo 与 fragment），发送 `{"title":"短信转发","text":"...","timestamp":秒}`；若填写密钥，则在请求头附带 `X-Webhook-Timestamp` 与 `X-Webhook-Signature`（HMAC-SHA256 Base64），HTTP 2xx 即确认成功。
