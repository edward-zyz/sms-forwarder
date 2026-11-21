# iPhone 端：短信自动转发到飞书

iPhone 端目前只支持转发到**飞书**群机器人（企业微信、钉钉与自定义 Webhook 请使用 Android App）。本目录提供两条 iPhone 转发路线：面向非技术运营的**纯快捷指令版（免装任何第三方 App）**，以及面向开发者与安全合规团队的 **Scriptable 安全增强版**。两条路线均由系统「快捷指令」的「信息」自动化触发。

## 双轨选型对比

| 对比维度 | 纯快捷指令版（标准版 / 推荐运营与商家） | Scriptable 版（安全增强版 / 推荐开发者与合规团队） |
| --- | --- | --- |
| **配置指南** | [一键导入签名快捷指令](https://github.com/edward-zyz/sms-forwarder/releases/latest/download/sms-forwarder-ios.shortcut) · [纯快捷指令版指南](shortcuts-guide.md) | [使用手册 iPhone 章节](../HANDBOOK.md#3-iphone-安装与验证) + [短信转飞书.js](短信转飞书.js) |
| **需要安装的 App** | **零额外 App**（仅用系统自带「快捷指令」） | 需从 App Store 安装 [Scriptable](https://apps.apple.com/cn/app/scriptable/id1405459188) |
| **上手门槛** | **低**（扫码导入已签名快捷指令，或按指南手动搭建） | **中**（安装 App → 导入 JS 脚本 → 运行写入钥匙串 → 建自动化） |
| **飞书安全校验** | 仅支持**自定义关键词校验**（如 `短信转发`） | 同时支持**自定义关键词**与 **HMAC-SHA256 签名校验** |
| **凭据存储方式** | Webhook 明文存于快捷指令中（勿把填好凭据的指令分享给他人） | Webhook 与签名密钥隔离保存在本机 Scriptable 钥匙串（`sms-forwarder.feishu.v1`） |
| **异常处理与校验** | 依赖系统词典序列化，默认不做字节上限和回执码判断 | 内置 Webhook 域名校验、16,000 / 20,000 字节上限保护、15 秒超时与 `HTTP 200 + code === 0` 严格回执检查 |
| **多机来源标识** | 导入时填写本机号码/备注，消息附带 `接收手机` | 运行脚本时填写本机号码/备注，消息附带 `接收手机` |
| **国内分发方式** | 仓库与 Release 提供已签名的 `sms-forwarder-ios.shortcut`（由 [build-shortcut.py](build-shortcut.py) 生成），可经飞书/微信/AirDrop 直接导入 | 可直接发送 `短信转飞书.js` 或复制代码导入 Scriptable |

> **共同前提**：苹果在系统层面禁止分享「个人自动化」。无论采用哪条路线，都必须在收短信的 iPhone 上手动创建「信息」自动化，并在解锁态点击「始终允许」及开启「允许锁定时运行」。

## iOS 系统边界声明

- **只能处理快捷指令传入的文本**：两条路线都无法主动监听或读取所有短信，也无法访问历史收件箱。能否触发完全取决于 iOS「信息」自动化设置的发件人或关键词条件。
- **无离线队列与自动重试**：遇断网、请求超时、锁屏权限未放行或系统休眠导致自动化未触发时，短信会直接漏发，需要自行核查。
- **可能产生重复推送**：多条「信息」自动化条件重叠并同时命中同一条短信时，会分别触发转发。
- **不建议拿 iPhone 作为 7×24 小时无人值守接码机**：iOS 更适合个人手机轻量转发或临时应急。如果需要长期插电、断网可自动重试、支持多目的地与商家平台模板的稳定转发，请使用闲置旧安卓机（详见 [Android 使用指南](../docs/android.md)）。

## Scriptable 版补充说明

- [短信转飞书.js](短信转飞书.js) 默认不加过滤，直接发送快捷指令传入的非空正文；正文超过 16,000 字节或序列化后的请求超过 20,000 字节时会报错。
- 从使用旧配置存储的版本切换到公开版后，请在 Scriptable 中直接运行一次脚本，重新保存飞书配置至钥匙串 `sms-forwarder.feishu.v1`。
- 直接运行脚本时可填写 Webhook、可选签名密钥与可选本机号码/备注（最多 48 字）；填写本机号码/备注后，消息会附带 `接收手机：…`。
- 请分别测试手动调用、收到短信时的解锁态自动化以及锁屏态自动化。
- 在项目根目录执行以下命令可运行模拟网络单元测试（不会真实发送短信）：

```bash
node --test ios/*.test.cjs
```

## 维护者：重新生成签名快捷指令

修改快捷指令结构时，编辑 [build-shortcut.py](build-shortcut.py)，在 macOS 上执行：

```bash
python3 ios/build-shortcut.py /tmp/sms-forwarder-ios.unsigned.shortcut
shortcuts sign --mode anyone -i /tmp/sms-forwarder-ios.unsigned.shortcut -o /tmp/sms-forwarder-ios.shortcut
cp /tmp/sms-forwarder-ios.shortcut ios/sms-forwarder-ios.shortcut
```

提交后，下一次 Release 会把它作为 `sms-forwarder-ios.shortcut` 发布，README 二维码始终指向最新版。
