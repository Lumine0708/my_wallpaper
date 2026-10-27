# 平板动态壁纸 Android 工程

本目录是仓库中唯一可直接由 Android Studio 打开的 Gradle 工程。应用面向 Android 平板，重点适配 Xiaomi/HyperOS，只管理主屏幕动态壁纸。

当前壁纸配置模型 v1 已完成：横屏和竖屏分别保存多张用户图片、切换间隔以及顺序/随机模式，并共享同一套配置、播放、解码和渲染抽象。设置页使用原生 framework UI，AndroidX 只用于 `androidTest`。

## 工程配置

| 项目 | 版本或取值 |
|---|---|
| 模块 | `:app` |
| application ID / namespace | `com.codex.tabletlivewallpaper` |
| Android Gradle Plugin | 8.11.2 |
| Gradle Wrapper | 8.13 |
| Java | 17 |
| compile / target SDK | 35 |
| min SDK | 26 |
| 当前版本 | `version.properties` |

`app` 是唯一权威模块。生产 Java、资源、assets 和 manifest 目前仍位于工程根的 `src`、`res`、`assets` 和 `AndroidManifest.xml`，由 `app/build.gradle.kts` 的 `sourceSets.main` 接入。JVM 和 instrumentation 测试分别位于标准的 `app/src/test`、`app/src/androidTest`。

版本统一在工程根 `version.properties` 中维护。`versionName` 使用三段 SemVer，`versionCode` 每次发布加一；Gradle、PR CI 和 Release tag 共用这份版本事实。

## 常用命令

在本目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

需要同时检查 release 编译时：

```sh
./gradlew :app:assembleRelease
```

debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

debug APK 使用本机默认 debug keystore。release variant 默认未签名；交付时使用仓库外私钥签名，见[发布说明](../../docs/releasing.md)。

## 模拟器与设备测试

默认开发目标是 `medium_tablet` AVD：Pixel Tablet、Android 15 / API 35、ARM64、`2560x1600`、`320 dpi`。

```sh
android emulator start medium_tablet
android run \
  --device=emulator-5554 \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

运行 instrumentation 测试：

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

测试结束后应检查实际结果文件，而不只看终端末行：

- `app/build/test-results/testDebugUnitTest/`
- `app/build/outputs/androidTest-results/connected/debug/`
- `app/build/reports/lint-results-debug.html`

日常开发不依赖真机。Xiaomi 系统壁纸选择器、主屏/锁屏分离、OEM 清理恢复以及最终动画手感到明确验收阶段再使用 HyperOS 真机。

## 当前功能边界

- 竖屏和横屏各自维护图片列表、播放顺序和切换间隔。
- 用户图片通过 `ACTION_OPEN_DOCUMENT` 导入并保存可持久读取的 `content://` URI。
- renderer 使用固定平滑转场；转场参数不是 v1 设置项。
- 首次保存前使用内置图片作为 legacy fallback，保存后只使用 v1 配置。
- 合法空配置、损坏配置和未来 schema 具有不同语义，不会静默回退。
- 应用不写入锁屏；启用时应在系统支持的情况下选择“仅主屏幕”。

## 验证边界与文档

API 26–27 仍未实际覆盖，真实 Wallpaper surface 失败路径仍需设备层关注。验证命令及真机边界见 [AGENTS_DEV.md](../../AGENTS_DEV.md)。

- [项目文档索引](../../docs/README.md)
- [壁纸配置数据模型 v1](../../docs/wallpaper-configuration-model-v1.md)
- [内置图片来源](../../docs/bundled-images.md)
- [Release 发布说明](../../docs/releasing.md)
