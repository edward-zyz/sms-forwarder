# 开发指南

## 构建环境

- Android 使用 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和 Platform Tools。可用 Android Studio 的 SDK Manager 安装 SDK 组件。项目自带 Gradle 8.11.1 wrapper。
- 将 `JAVA_HOME` 设为本机 JDK 17 的安装目录。将 `ANDROID_HOME` 设为 SDK 目录，也可在本机的 `android/local.properties` 中用 `sdk.dir` 指定 SDK 目录。Gradle 默认使用用户目录下的缓存，`GRADLE_USER_HOME` 可按需设置。
- iPhone 脚本的模拟测试使用 Node.js 22 或兼容版本，无需安装 npm 依赖。

在项目根目录运行以下命令。

```bash
(cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug)
node --test ios/*.test.cjs
```

Windows 用户可在 `android/` 内运行 `gradlew.bat testDebugUnitTest assembleDebug lintDebug`。依赖已经缓存时，可为 Gradle 命令加 `--offline`。

| 输出 | 路径 |
| --- | --- |
| 调试 APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| JVM 测试报告 | `android/app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 报告 | `android/app/build/reports/lint-results-debug.html` |

## 真机安装与更新

在 Android 设备上启用 USB 调试并连接电脑，按设备提示允许调试。将 SDK 的 `platform-tools` 加入 `PATH`，然后在项目根目录运行。

```bash
adb devices
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

连接多台设备时，用 `adb -s <设备序列号> install -r …` 指定目标。升级包必须使用与已安装版本相同的签名。遇到签名不一致时，请取得兼容包，避免卸载导致配置和待发队列丢失。

## 本机配置与测试

将本机环境脚本、个人资料及专用脚本放入根目录下的 `private/`。该目录、`local.properties` 和本机构建产物均由 Git 忽略。手机上的 Webhook 和密钥通过各端设置界面保存。

Android 单元测试覆盖签名、筛选规则、多规则路由、消息排版和表单。Node 测试覆盖 iPhone 的输入、签名、配置、请求大小和错误回执。测试使用模拟请求。真机验证按[使用手册](../HANDBOOK.md)进行。

## iPhone 签名快捷指令

纯快捷指令版的万能安装包（`ios/sms-forwarder-ios.shortcut`，自动识别四平台）由 `ios/build-shortcut.py` 生成，需在 macOS 上签名：

```bash
python3 ios/build-shortcut.py --sign ios/sms-forwarder-ios.shortcut
```

## 发布

推送 `v*` 标签后，`.github/workflows/release.yml` 会运行双端门禁、用仓库 Secrets 中的 keystore 签名构建 Release APK，并发布版本号 APK、`sms-forwarder-latest.apk`、`sms-forwarder-ios.shortcut`、`sms-forwarder-ios.js` 与 `SHA256SUMS.txt`，同时生成 APK 构建溯源证明。README 中的二维码指向 `releases/latest/download/` 下的固定文件名，无需随版本更新。
