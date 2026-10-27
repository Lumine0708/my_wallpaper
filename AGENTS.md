# AGENTS.md

本文件是仓库级 agent 协作入口。先读这里，再按任务类型读取下方两个分片；不要把参考仓库中的前端、后端、数据库或通用部署规则机械套入本项目。

## 项目简介

`my_wallpaper` 是面向 Android 平板、重点适配 Xiaomi/HyperOS 的主屏幕动态壁纸项目。当前应用已支持横屏和竖屏独立配置多张本地图片、轮询间隔、顺序/随机播放，并保留固定的平滑换图效果。

核心目标：

- 应用只接管主屏幕动态壁纸，锁屏继续由系统或用户管理。
- 横屏和竖屏共享同一套配置、播放和渲染抽象，但保存彼此独立的 profile 与运行状态。
- 用户图片在重启后仍可读取；损坏图片、权限失效、配置损坏和生命周期竞态不能拖垮 Wallpaper Engine。
- 日常开发和回归优先在 `medium_tablet` 模拟器完成，HyperOS 真机只用于明确的 OEM/最终验收。

主要技术栈：

- 单模块原生 Android 应用，Java 17，framework UI。
- Android Gradle Plugin 8.11.2、Gradle Wrapper 8.13。
- compile/target SDK 35，min SDK 26。
- JVM 单元测试使用 JUnit 4；设备测试使用 AndroidX Test，但生产代码当前不依赖 AndroidX。

## 目录与权威入口

| 路径 | 作用 |
|---|---|
| `source/tablet-live-wallpaper/` | 可直接由 Android Studio 打开的唯一 Android 工程 |
| `source/tablet-live-wallpaper/app/build.gradle.kts` | app 模块、SDK、source set、测试和签名的权威构建配置 |
| `source/tablet-live-wallpaper/src/com/codex/tabletlivewallpaper/` | 当前生产 Java 源码；仍由 Gradle source-set bridge 接入 |
| `source/tablet-live-wallpaper/app/src/test/` | JVM 单元测试 |
| `source/tablet-live-wallpaper/app/src/androidTest/` | 模拟器/设备 instrumentation 测试及 test-only provider |
| `source/tablet-live-wallpaper/{res,assets,AndroidManifest.xml}` | 当前生产资源、内置兜底图片和 manifest |
| `docs/` | 项目说明、配置契约、素材来源和发布流程 |

## 开始任务前

- 先确认用户要的是分析、修改、验证还是交付。只要求阅读或诊断时，不修改文件。
- 修改前先读 `AGENTS_BRANCHING.md`，再执行 `git branch --show-current`、`git status --short`，并用 `git log --oneline -n 20` 了解提交风格。保留用户已有改动和未跟踪文件，不替其清理、覆盖或纳入提交。
- 需求、当前实现或受保护边界不清时，先查源码、测试、最近提交和项目文档；不能用旧 README 或记忆覆盖更晚的已验证事实。
- 业务改动必须能追溯到用户要求、已批准设计、缺陷复现、测试失败或运行证据。范围外问题只记录和报告，不顺手改。
- 除非用户要求，不主动创建分支、commit、push、PR 或刷新交付 APK。代码进入 `main` 必须遵守 `AGENTS_BRANCHING.md` 的 PR 与 force push 边界。

## 硬规则

### 产品边界

- 应用只管理主屏幕。不得新增 `android.permission.SET_WALLPAPER`、锁屏 Activity、`WallpaperManager.FLAG_LOCK` 或其它锁屏写入路径。
- 系统/OEM 是否提供“仅主屏幕”由选择器决定；不使用私有 API 绕过 ROM 限制。涉及 Xiaomi 选择器行为时保留现有 manufacturer-scoped fallback。
- 横竖屏是仅有的两份 profile。不要按宽高比、设备类型或图片比例偷偷增加第三套配置分支。
- 固定平滑转场属于 renderer 行为，当前不是用户可编辑 schema 字段；改变 schema 或产品范围必须先得到明确需求。

### 数据与生命周期边界

- 用户配置、播放运行状态和渲染帧状态必须分离。配置统一经过 `WallpaperConfigRepository`；不要新增零散 `SharedPreferences` 业务 key。
- 必须保留 `LegacyFallback`、合法空 v1、`Corrupt` 和 `UnsupportedVersion` 的不同语义。损坏或未来版本不能静默回退到内置图片，也不能在普通保存中被覆盖。
- 用户图片只保存可持久化 `content://` URI。URI lease、已提交引用和释放统一经过 `UriGrantRegistry`，并与配置提交保持既有串行/原子边界。
- 不在日志、诊断摘要、错误文案、review 证据中记录完整用户 URI 或其它私人内容。
- 图片解码必须有界、离开主线程并遵守进程级预算。解码失败、surface 变化、Engine 销毁或配置 revision 变化时，不得提交陈旧帧或播放事务。
- 预览 Engine 与已启用壁纸 Engine 的播放状态互相隔离；横竖方向切换也不能推进或重置另一方向的状态。

更完整的模块职责和改动约束见 `AGENTS_ANDROID.md`。

### 构建、依赖与文件边界

- 构建只使用仓库内 `source/tablet-live-wallpaper/gradlew`。发布签名私钥和密码保存在仓库之外，APK 通过 GitHub Release 交付，不提交构建产物。
- 不把功能改动和 legacy source-set bridge 的目录迁移混在一起。若要迁移到 `app/src/main`，单独做机械变更并同步构建、测试和文档。
- AndroidX 当前只获准用于 `androidTest`。新增生产依赖、升级 AGP/Gradle/SDK、修改 wrapper、签名或 keystore 都需要明确任务来源。
- `.gradle/`、`app/build/`、IDE 配置、AVD、截图缓存、logcat、临时脚本和本机路径覆盖不提交。
- 不自动改动 `source/tablet-live-wallpaper/assets/`。只有用户明确要求美术替换时才更新，并同步记录 `docs/bundled-images.md` 中的来源、许可和校验值。验证证据默认放在仓库外。

## 验证与完成标准

- 测试强度与风险匹配，不能为了通过而删测试、降低断言、加 lint baseline 或隐藏失败。
- 纯文档改动至少运行 `git diff --check`，并核对命令、路径和文档链接。
- Java/资源/manifest/Gradle 改动按 `AGENTS_DEV.md` 执行对应的单元测试、lint、构建和必要的设备测试。
- 涉及 UI、方向、renderer、SAF、URI grant、Engine 生命周期或 OEM 行为时，仅有 JVM 测试不算完整验证。
- 汇报时区分：已自动验证、已在模拟器验证、需要真机验收、未验证及原因。不要把已有历史证据描述成本次新验证。

## 按需读取

- **`AGENTS_BRANCHING.md`** — 开始任何会修改文件、创建/切换分支、commit、push、发 PR 或合并的工作前必读。
- **`AGENTS_ANDROID.md`** — 修改 Java、资源、manifest、Gradle 配置，或分析配置、播放、解码、SAF、Engine/UI 行为前必读。
- **`AGENTS_DEV.md`** — 安装依赖、运行 Gradle、启动模拟器、安装 APK、采集日志/截图或做真机验收前必读。

## 项目文档索引

| 文档 | 用途 |
|---|---|
| `docs/README.md` | 详细项目文档索引；项目入口、下载和开源信息见根 README |
| `source/tablet-live-wallpaper/README.md` | Android 工程入口和基础命令 |
| `docs/wallpaper-configuration-model-v1.md` | v1 产品、schema、URI 权限、播放和验收契约 |
| `docs/development.md` | 构建和模拟器开发入口 |
| `docs/releasing.md` | Release 构建、外置签名和 tag 发布 |
| `docs/bundled-images.md` | 内置图片来源、公有领域标识和校验值 |

冲突时优先使用用户当前要求、最新已批准设计、当前源码与可重复测试结果；历史 handoff 和截图只作为背景证据。
