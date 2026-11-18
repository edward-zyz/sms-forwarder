# 架构与数据流

## Android

`SmsReceiver` 接收系统新短信广播并合并分段短信，经 `FilterSettings`／`FilterRules` 判断后交给 `Store` 入队。`ForwardJob` 通过系统 JobScheduler 等待网络并发送，`Protocol` 负责构造飞书请求及签名；成功确认后删除队列正文，失败按策略重试。

`MainActivity` 管理转发总开关、飞书配置、队列和状态；`FilterActivity` 管理“哪些短信要转发”，`FilterEditor` 保存表单状态，`FilterDescriptions` 生成可读规则摘要，`Ui` 提供界面共用组件。测试放在 `android/app/src/test/`。

接收依赖 RECEIVE_SMS 与系统广播，不读取历史短信。入队前筛选不影响已有队列。去重指纹、重试上限、正文与请求大小限制等行为详见 [Android 用户指南](android.md)。

## iPhone

短信 → 快捷指令的信息自动化 → 提取短信内容文本 → Scriptable 脚本 → 飞书机器人。

`短信转飞书.js` 发送传入的非空正文，触发条件由快捷指令设置。Webhook 和可选签名密钥保存在 Scriptable 钥匙串。

脚本本身不访问收件箱，不持久化正文，不提供离线队列或自动重试。多个自动化命中同一短信时，可能产生重复推送。

## 飞书协议

只允许 HTTPS 的 `open.feishu.cn/open-apis/bot/v2/hook/` 地址。签名以 `timestamp + 换行 + secret` 为 HMAC-SHA256 密钥，对空消息计算并编码为 Base64。保留“短信转发”文字供机器人关键词校验使用。必须同时取得正确的 HTTP 状态和飞书 `code=0` 才确认成功。
