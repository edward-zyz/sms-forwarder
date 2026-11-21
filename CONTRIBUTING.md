# 贡献指南（Contributing）

感谢关注「短信自动转发」！本项目旨在以最小维护成本，稳定解决团队与个人将短信（尤其是验证码）自动转发到自己群机器人（飞书、企业微信、钉钉或自定义 HTTPS Webhook）的核心需求。

## 项目定位与克制原则

提交 PR 或功能建议前，请先确认符合以下边界：

- **只做核心闭环**：围绕 Android（多目的地、多规则、商家模板）与 iPhone（纯快捷指令 / Scriptable，仅飞书）的短信筛选、群机器人签名推送、离线重试与可观测排障。
- **不接受的功能方向**：
  - 引入闭源第三方 SDK、广告、数据统计或第三方中转服务器；
  - 将项目改造成大而全的多通道消息网关、云端同步平台或与短信转发无关的功能膨胀；
  - 申请 `READ_SMS` 等超出最小必要范围的敏感权限；
  - 夸大 iOS 能力（iOS 脚本只能处理快捷指令传入的文本，绝不宣称能主动监听或读取所有短信）。
- **低维护优先**：内容筛选、模板匹配与发送协议逻辑尽量保持纯 Kotlin / 纯 JS，便于通过模拟网络单元测试覆盖，减少对真机手工回归的依赖。

## 开发环境与本地门禁

详细环境准备（JDK 17、Android SDK 35、Node.js 22）见 [docs/development.md](docs/development.md)。

提交任何代码修改前，必须在项目根目录通过双端门禁：

```bash
# Android 门禁（单测 + Debug 构建 + Lint）
(cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug)

# iOS Scriptable 门禁（Node 模拟网络测试）
node --test ios/*.test.cjs
```

### Android 约定

- 保持 `applicationId = "com.kidbuddy.smsforwarder"` 不变。
- 真机覆盖升级请使用 `adb install -r`，避免卸载导致本地配置与待发队列丢失。
- Release 签名通过环境变量（`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`）注入，本地 Debug 构建无需配置签名环境变量。

### iOS 约定

- 公开版脚本位于 `ios/短信转飞书.js`，钥匙串配置名保持 `sms-forwarder.feishu.v1`。
- 新增或修改逻辑需同步补充 `ios/*.test.cjs` 模拟测试，禁止在测试中真实请求线上飞书 Webhook。
- 纯快捷指令版由 `ios/build-shortcut.py` 生成，修改后需在 macOS 上用 `shortcuts sign --mode anyone` 重新签名并提交 `ios/sms-forwarder-ios.shortcut`，步骤见 [ios/README.md](ios/README.md#维护者重新生成签名快捷指令)。导入问题的默认值不得包含真实 Webhook。

## 提交规范与脱敏红线

1. **文档与注释**：统一使用简体中文，表达简洁、明确。
2. **严禁泄露敏感信息（红线）**：
   - 严禁在代码、测试、文档、Commit Message 或 PR 描述中写入真实群机器人 Webhook（飞书、企业微信、钉钉或自定义）、签名密钥、签名 keystore、真实手机号或真实用户短信。
   - 日志与错误提示不得输出 Webhook、密钥或完整敏感短信正文。
   - 本机环境脚本、个人调试资料与私有配置请放在被 `.gitignore` 忽略的 `private/` 目录中。
3. **小步提交**：每个 PR 尽量聚焦单一问题或单一特性，附带清晰的修改说明与本地门禁通过记录。
