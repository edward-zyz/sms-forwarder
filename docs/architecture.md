# 架构与数据流

## Android

`SmsReceiver` 接收系统新短信广播并合并分段短信，经 `ConfigStore` 加载多目的地与多规则配置，由 `RoutingEngine`（复用 `FilterRules`）依次评估所有已启用规则并按目的地去重，再交由 `Store` 分别入队。`ForwardJob` 通过系统 JobScheduler 等待网络并按条目的目的地 ID 调用 `DestinationProtocol` 构造对应平台请求（飞书、企业微信、钉钉、自定义 HTTPS Webhook）及签名；确认接收后删除队列正文，失败按策略重试且单目的地异常不阻塞其他目的地。

`MainActivity` 管理转发总开关、规则/目的地概览、测试消息、队列和状态；`DestinationsActivity`／`DestinationEditActivity` 管理转发目的地；`RulesActivity` 管理多条转发规则与全局试算；`FilterActivity` 编辑单条规则（名称、目的地、开关及过滤条件），`FilterEditor` 保存表单草稿，`FilterDescriptions` 生成可读摘要，`Ui` 提供界面共用组件。旧版单飞书配置与待发队列在升级后由 `ConfigStore` 和 `Store` 自动兼容迁移。测试放在 `android/app/src/test/`。

接收依赖 RECEIVE_SMS 与系统广播，不读取历史短信。入队前筛选不影响已有队列。去重指纹（按短信指纹 + 目的地 ID 维度）、重试上限、正文与请求大小限制等行为详见 [Android 用户指南](android.md)。

## iPhone

短信 → 快捷指令的信息自动化 → 提取短信内容文本 → Scriptable 脚本 → 飞书机器人。

`短信转飞书.js` 发送传入的非空正文，触发条件由快捷指令设置。Webhook 和可选签名密钥保存在 Scriptable 钥匙串。

脚本本身不访问收件箱，不持久化正文，不提供离线队列或自动重试。多个自动化命中同一短信时，可能产生重复推送。

## 转发目的地协议

- **飞书**：只允许 HTTPS 的 `open.feishu.cn/open-apis/bot/v2/hook/` 地址。签名以 `timestamp + 换行 + secret` 为 HMAC-SHA256 密钥，对空消息计算并编码为 Base64。保留“短信转发”文字供机器人关键词校验使用。需 HTTP 200 且 `code=0` 才确认成功。
- **企业微信**：只允许 HTTPS 的 `qyapi.weixin.qq.com/cgi-bin/webhook/send?key=` 地址。发送 `{"msgtype":"text","text":{"content":"..."}}`，需 HTTP 200 且 `errcode=0` 才确认成功。
- **钉钉**：只允许 HTTPS 的 `oapi.dingtalk.com/robot/send?access_token=` 地址。启用加签时，以 `secret` 为 HMAC-SHA256 密钥对 `毫秒时间戳 + 换行 + secret` 签名、Base64 后 URL 编码并追加 `timestamp` 与 `sign` 查询参数。需 HTTP 200 且 `errcode=0` 才确认成功。
- **自定义 Webhook**：允许任意合法 HTTPS 地址（禁止 userInfo 与 fragment），发送 `{"title":"短信转发","text":"...","timestamp":秒}`；若填写密钥，则在请求头附带 `X-Webhook-Timestamp` 与 `X-Webhook-Signature`（HMAC-SHA256 Base64），HTTP 2xx 即确认成功。
