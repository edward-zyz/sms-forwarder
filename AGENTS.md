# 开发约定

这是独立的短信转飞书项目。文档和注释使用简体中文。根目录 README 提供平台入口，`docs/development.md` 提供构建命令。

- Android 工程位于 `android/`，iPhone Scriptable 脚本和 Node 测试位于 `ios/`。
- 构建 Android 前按开发指南设置本机 JDK 和 SDK 路径。个人开发环境脚本与资料放在 Git 忽略的 `private/`。
- Android 门禁：在 `android/` 运行 `./gradlew testDebugUnitTest assembleDebug lintDebug`；iOS 门禁：根目录运行 `node --test ios/*.test.cjs`。
- Android 应用 ID 保持 `com.kidbuddy.smsforwarder`，升级安装使用 `adb install -r`，不要卸载清除用户配置与队列。
- 公开版 Scriptable 使用钥匙串配置名 `sms-forwarder.feishu.v1`。不要写入真实 Webhook、密钥或短信，不在日志中输出这些内容。
- 内容筛选与发送协议逻辑尽量保持纯 Kotlin／纯 JS，并用模拟网络测试。不要在测试中真实推送用户短信。
- iOS 脚本只能处理快捷指令传入的文本，不能宣称它能主动监听或读取所有短信。
