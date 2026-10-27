# 开发与工程结构

以下命令除特别注明外从仓库根目录开始执行。构建、安装与设备验证规则见 [AGENTS_DEV.md](../AGENTS_DEV.md)。

## 技术栈

| 项目 | 当前配置 |
|---|---|
| Android 工程 | 单模块原生应用 `:app` |
| UI | Android framework UI |
| 语言 | Java 17 |
| Android Gradle Plugin | 8.11.2 |
| Gradle Wrapper | 8.13 |
| SDK | compile/target 35，min 26 |
| 单元测试 | JUnit 4 |
| 设备测试 | AndroidX Test（仅 `androidTest`） |
| 包名 | `com.codex.tabletlivewallpaper` |
| 壁纸服务 | `com.codex.tabletlivewallpaper.FitWallpaperService` |

## 快速开始

唯一可直接打开的 Android 工程位于 `source/tablet-live-wallpaper`：

```sh
cd source/tablet-live-wallpaper
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

debug APK 输出到：

```text
source/tablet-live-wallpaper/app/build/outputs/apk/debug/app-debug.apk
```

debug APK 使用本机默认 debug keystore；release variant 需在构建后使用仓库外私钥签名，见[发布说明](releasing.md)。

## 模拟器开发

默认开发目标是持久化的 `medium_tablet` AVD：Pixel Tablet、Android 15 / API 35、ARM64、`2560x1600`、`320 dpi`。

```sh
android emulator start medium_tablet
cd source/tablet-live-wallpaper
android run \
  --device=emulator-5554 \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

需要运行设备测试时：

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

完整的构建、交互、日志和截图流程见 [AGENTS_DEV.md](../AGENTS_DEV.md)。真机只用于 Xiaomi 选择器、主屏/锁屏分离、OEM 清理恢复和最终视觉手感等明确验收。

## 代码结构

| 路径 | 作用 |
|---|---|
| `source/tablet-live-wallpaper/` | 唯一 Android Studio / Gradle 工程 |
| `source/tablet-live-wallpaper/src/com/codex/tabletlivewallpaper/` | 当前生产 Java 源码 |
| `source/tablet-live-wallpaper/res/` | 生产资源 |
| `source/tablet-live-wallpaper/assets/` | 首次保存前使用的内置 fallback 图片 |
| `source/tablet-live-wallpaper/app/src/test/` | JVM 单元测试 |
| `source/tablet-live-wallpaper/app/src/androidTest/` | 模拟器/设备 instrumentation 测试 |
| `docs/` | 项目概览、开发说明和开源检查记录 |


生产源码和资源目前仍位于工程根的 legacy 路径，由 `app/build.gradle.kts` 的 `sourceSets.main` 接入；测试已经使用标准的 `app/src/test` 和 `app/src/androidTest`。功能改动不应顺带迁移这套 source-set 布局。

## 主要组件

| 组件 | 责任 |
|---|---|
| `WallpaperModels` | schema v1 不可变模型和统一校验 |
| `WallpaperConfigRepository` | AtomicFile 配置、revision/token CAS 和快照发布 |
| `UriGrantRegistry` | URI grant、草稿 lease、ownership provenance 和安全释放 |
| `ProfileDraftStore` | Activity 重建所需的私有草稿缓存 |
| `WallpaperPlaybackController` | 横竖独立播放状态、顺序/洗牌和失败退避 |
| `DecodeCoordinator` | 进程级并发与内存预算、有界解码和 provider snapshot |
| `WallpaperDiagnosticsStore` | active、preview、settings 分来源图片健康状态 |
| `FitWallpaperService` | Engine 生命周期、方向采样、异步 prepare、绘制和转场 |
| `ImageSourceActivity` | 中文设置 UI、SAF 导入、保存冲突和系统壁纸选择器 |

版本以 [`version.properties`](../source/tablet-live-wallpaper/version.properties) 为准；历史验收见[项目概览](overview.md)。
