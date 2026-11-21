# 安全策略（Security Policy）

本项目「短信自动转发」聚焦于将收到的短信直接转发到用户自己的群机器人（Android 支持飞书、企业微信、钉钉与自定义 HTTPS Webhook，iPhone 支持飞书），在设计上坚持最小权限、零中转与本地优先。

## 安全与隐私设计边界

1. **零中转服务器**
   - 本项目不包含任何自建后端、云端账号系统或数据统计服务。
   - 短信内容仅由设备直接通过 HTTPS 发送到用户自行配置的群机器人 Webhook 地址；Android 端禁止明文 HTTP。
2. **零第三方闭源依赖**
   - **Android 端**：仅使用 Android 系统 API 与 Google 官方 Material / AndroidX 基础库，无广告 SDK、无埋点与崩溃上报 SDK。
   - **iPhone 端**：纯快捷指令版只使用系统「快捷指令」原生动作，构建脚本 `ios/build-shortcut.py` 可审计；Scriptable 版为单文件纯 JavaScript 脚本，无外部 npm/CDN 依赖。
3. **不读取历史短信**
   - **Android 端**：仅申请接收新短信广播的 `RECEIVE_SMS` 权限，**不申请** `READ_SMS` 权限，不扫描或读取收件箱中的历史短信。应用清单明确关闭明文流量（`usesCleartextTraffic="false"`）与系统云备份（`allowBackup="false"`）。
   - **iPhone 端**：两条路线都只能处理苹果「快捷指令」自动化单次传入的短信文本，不能主动监听系统短信，也无法读取历史短信。
4. **敏感配置存储位置**
   - **Android 端**：各目的地 Webhook、签名密钥、筛选规则、本机号码/备注与待发队列仅保存在本机应用私有目录中，外部应用无法直接读取。
   - **iPhone 纯快捷指令版**：Webhook 明文保存在快捷指令动作中，可能随 iCloud 同步，切勿把填好地址的快捷指令转发给他人。
   - **iPhone Scriptable 版**：飞书 Webhook、签名密钥与本机号码/备注保存在 Scriptable 钥匙串（配置键 `sms-forwarder.feishu.v1`）。
   - 公开源码、测试用例与默认日志中不会包含或打印真实 Webhook、签名密钥或用户短信内容。

## 验证官方构建产物

每次正式发布（GitHub Releases）均通过 GitHub Actions 自动构建并随附：

- `SHA256SUMS.txt`：版本号 APK、`sms-forwarder-latest.apk`、签名快捷指令 `sms-forwarder-ios.shortcut` 与 Scriptable 脚本 `sms-forwarder-ios.js` 的 SHA-256 校验和；
- Release 正文中的 **Android APK 签名证书 SHA-256 指纹**；
- GitHub Build Provenance 溯源证明，可使用以下命令验证：

```bash
gh attestation verify sms-forwarder-<tag>.apk --repo edward-zyz/sms-forwarder
```

## 安全漏洞私下反馈

如果你发现了安全漏洞、权限越界或潜在敏感信息泄露风险，**请勿**在公开 Issue、PR 或讨论区中披露细节，也切勿附上真实 Webhook、签名密钥或完整验证码短信。

请通过以下方式私下反馈：

1. **优先方式**：在 GitHub 仓库页面点击 **Security → Advisories → Report a vulnerability** 提交私密安全报告。
2. 如无法使用 GitHub Security Advisories，请先通过 GitHub 仓库维护者主页联系方式取得私下沟通渠道后再发送详情。

提交报告时，建议包含：

- 受影响的平台（Android App / iPhone 纯快捷指令 / iPhone Scriptable 脚本）与版本号；
- 脱敏后的复现步骤与影响范围说明；
- 如有修复思路，欢迎一并附上。

维护者会在收到报告后尽快确认、评估并安排修复与致谢。
