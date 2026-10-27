# AGENTS_DEV.md

运行 Gradle、启动模拟器、安装 APK、操作壁纸或采集验证证据前读这里。默认开发目标是本机持久化 AVD，真机不是日常依赖。

## 工具链

- 从仓库根进入 Android 工程：`cd source/tablet-live-wallpaper`。
- 只调用仓库内 `./gradlew`，不要依赖全局 Gradle。
- Java source/target 为 17；本机最近验证的 Gradle launcher runtime 为 JDK 21。
- Android SDK compile/target 35，min SDK 26。
- Android CLI 最近记录版本为 `1.0.15857036`；模拟器也可从 Android Studio Device Manager 操作。
- 本机 SDK、JDK、Gradle cache、AVD 和 `local.properties` 都属于本地状态，默认不提交。

如本机事实与本文不同，先用 `./gradlew --version`、`android emulator list`、`adb devices -l` 查证，再决定是否需要更新文档；不要为适配本机去改项目 SDK、wrapper 或生产代码。

## 快速验证

在 `source/tablet-live-wallpaper` 中运行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

debug APK 使用 Android Gradle Plugin 的本机默认 debug keystore；仓库不保存签名私钥。release variant 默认未签名，正式交付需使用仓库外私钥签署，不能把 debug APK 描述为 release 包。具体命令见 [发布说明](docs/releasing.md)。

## PR 验证与发布

`.github/workflows/main-pr-ci.yml` 是进入 `main` 前的 CI 门禁，只在目标为 `main` 的非草稿 PR 上运行版本增量校验、JVM 单元测试、Lint 和 `assembleDebug`。不得在本地或 workflow 中直接向 `main` commit/push；通过 CI 的任务分支仍须经 PR 合并。

Android 版本统一在 `source/tablet-live-wallpaper/version.properties` 中维护。`feature/*`、`feat/*` 使用 minor 增量，`fix/*` 使用 patch 增量，`versionCode` 每次恰好加一。验证完成后，使用对应源码构建 release variant，外置签名并发布 `v<versionName>` 标签和 GitHub Release；APK 和校验文件不纳入 Git。当前没有自动签名发布 workflow。

## 默认模拟器

| 项目 | 值 |
|---|---|
| AVD | `medium_tablet` |
| 设备形态 | Pixel Tablet |
| Android | 15 / API 35 |
| 架构 | ARM64 |
| 分辨率 / density | `2560x1600` / `320 dpi` |
| 常用 serial | `emulator-5554` |

启动并确认设备：

```sh
android emulator start medium_tablet
adb devices -l
```

安装/启动当前 debug APK：

```sh
android run \
  --device=emulator-5554 \
  --apks=app/build/outputs/apk/debug/app-debug.apk
```

需要设备测试时：

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

设备测试结束后读取测试 XML/HTML，而不只相信终端末行：

- `app/build/test-results/testDebugUnitTest/`
- `app/build/outputs/androidTest-results/connected/debug/`
- `app/build/reports/lint-results-debug.html`

## 交互与诊断

先看语义布局，再按需截图：

```sh
android layout --device=emulator-5554 --pretty
android layout --device=emulator-5554 --diff --pretty
android screen capture --device=emulator-5554 --output=/tmp/tablet-wallpaper.png
```

常用诊断：

```sh
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat -d
adb -s emulator-5554 shell dumpsys wallpaper
adb -s emulator-5554 shell dumpsys activity exit-info com.codex.tabletlivewallpaper
adb -s emulator-5554 shell run-as com.codex.tabletlivewallpaper id
```

日志检查至少关注应用 `FATAL EXCEPTION`、ANR、`SecurityException` 和新的 exit-info。系统镜像自己的 “System UI isn't responding” 不能误报成应用 ANR，但也不能在未恢复 AVD 状态时继续做视觉验收。

方向验证可临时锁定 rotation：

```sh
adb -s emulator-5554 shell wm user-rotation lock 0
```

测试结束必须恢复自动旋转：

```sh
adb -s emulator-5554 shell wm user-rotation free
```

只停止本次任务启动的模拟器或进程，不清除 AVD、app data、系统壁纸状态或其它项目进程，除非任务明确要求。

## 按改动类型选择验证

| 改动 | 最低验证 |
|---|---|
| 纯文档 | `git diff --check`，核对引用路径和命令 |
| 纯 Java 模型/播放逻辑 | `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` |
| Activity、资源、manifest、仓库、SAF/URI grant | 上述三项 + `:app:connectedDebugAndroidTest` + 模拟器手动 smoke |
| renderer、解码、Engine、surface、方向/转场 | 上述全部 + 横竖切换、wallpaper binding、logcat/exit-info 和必要截图/录屏 |
| Gradle/依赖/SDK | debug/release assemble、单元/设备测试、lint，并说明工具链变化 |
| Xiaomi picker、主屏/锁屏分离、OEM 清理、最终动画手感 | 先完成模拟器验证，再请求明确的 Xiaomi/HyperOS 真机验收 |

历史验证文件只能证明当时的版本。修改相关代码后，要重新产生与风险对应的证据。

## 真机验收边界

最近记录的目标设备是 Xiaomi Pad 6 Pro（`23046RP50C` / `liuqin`），Android 15 / API 35、HyperOS 3。设备 serial 和 ROM 版本可能变化，连接后必须重新用 `adb devices -l` 和系统属性确认，不把文档中的旧 serial 当成永远有效。

只有以下情况需要真机：

- MIUI/HyperOS 的主屏与锁屏分离；
- Xiaomi 系统壁纸选择器行为；
- OEM 清理或内存压力后的 WallpaperService 恢复；
- 代表性硬件 Canvas 时序与最终人工视觉接受。

真机操作前先说明本次验收范围。不得清数据、替换锁屏、删除用户图片或改变无关系统设置。验收后确认自动旋转已恢复、临时录屏已移除，并明确说明是否把 debug APK 和主屏壁纸留在设备上。

## 证据与交付物

- 临时截图、录屏、frame dump 和 logcat 默认写到 `/tmp` 或被忽略的构建目录，不直接塞进仓库。
- 只有用户要求保留回归证据时，才在指定位置建立命名清晰、可追溯的小集合；避免提交大批重复原始帧。
- 只有用户明确要求交付 APK 时，才在仓库外准备交付目录，并记录构建命令、variant、签名性质和已完成的验证。交付后清理仓库内的构建产物。
- 设备上的私人内容、完整用户 URI、无关通知或账户信息不得进入截图、日志或仓库。
