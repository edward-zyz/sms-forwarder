# 短信转飞书

短信转发工具，包含 Android App 和 iPhone 快捷指令配套的 Scriptable 脚本。无需服务器，消息发送到自己的飞书群机器人。

默认不设短信过滤规则。Android 首次开启自动转发后会转发收到的新普通短信，可按正文关键词、排除词、数字条件和发信号码筛选。iPhone 脚本转发收到的非空正文，可在「快捷指令」中按发件人和短信内容限定触发范围。

## 选择入口

首次安装请从[短信转飞书使用手册](HANDBOOK.md)开始，按手机平台完成配置和验证。

- **Android**：[安装、配置与筛选规则](docs/android.md)。支持后台接收、本地试短信、离线队列和重试。
- **iPhone**：[Scriptable 与快捷指令配置](ios/README.md)。使用快捷指令的「信息」自动化运行脚本。
- **开发维护**：[开发指南](docs/development.md)、[架构与数据流](docs/architecture.md)。

## 项目结构

```text
android/                 独立 Android Gradle 工程（含 wrapper）
  app/src/main/          App、资源和清单
  app/src/test/          Kotlin JVM 单元测试
ios/                     Scriptable 脚本、Node 测试和配置指南
docs/                    Android 用户指南、开发和架构文档
```

## 本地验证

先按[开发指南](docs/development.md)配置 JDK 和 Android SDK，再在项目根目录运行。

```bash
(cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug)
node --test ios/*.test.cjs
```

Android APK：`android/app/build/outputs/apk/debug/app-debug.apk`。iPhone 通用脚本：[短信转飞书.js](ios/短信转飞书.js)。

公开源码不包含个人 Webhook、签名密钥或真实短信。Android 配置保存在应用私有目录；iOS 配置保存在 Scriptable 钥匙串。实际转发前请按对应平台指南配置飞书机器人。
