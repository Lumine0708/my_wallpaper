# AGENTS_ANDROID.md

修改 Android 应用代码、资源、manifest 或构建配置前读这里。本文件只记录本项目特有的架构和安全边界；本机命令与验收流程见 `AGENTS_DEV.md`。

## 当前工程形态

- 唯一 Gradle 工程：`source/tablet-live-wallpaper`，唯一模块：`:app`。
- 生产 Java、资源、assets 和 manifest 暂时位于工程根的 legacy 路径，由 `app/build.gradle.kts` 的 `sourceSets.main` 接入。
- 标准 `app/src/test` 与 `app/src/androidTest` 已投入使用；不要因为生产路径较旧就把新测试放进生产源码目录。
- 包名和 application ID 均为 `com.codex.tabletlivewallpaper`。
- launcher 是 `ImageSourceActivity`，壁纸服务是 `FitWallpaperService`。`LockWallpaperActivity` 只是未注册的历史占位，不是可恢复的功能入口。

## 模块职责

| 文件/组件 | 责任与修改注意点 |
|---|---|
| `WallpaperModels.java` | schema v1 不可变模型和统一校验；不保存播放位置或 renderer 状态 |
| `WallpaperConfigRepository.java` | 配置唯一读写入口、AtomicFile、revision/token CAS、加载状态和进程内发布 |
| `UriGrantRegistry.java` | app-wide URI grant ledger、draft lease、ownership provenance、恢复与安全释放 |
| `ProfileDraftStore.java` | Activity 重建所需的私有 cache 草稿；保存、取消、重置和过期时清理 |
| `WallpaperPlaybackController.java` | 横竖独立播放状态、顺序/洗牌、暂停恢复、availability retry 和事务提交 |
| `DecodeCoordinator.java` | 进程级并发/内存预算、有界采样、content provider snapshot 和 bitmap 生命周期 |
| `WallpaperDiagnosticsStore.java` | active、preview、settings 分来源的非持久化图片健康状态 |
| `FitWallpaperService.java` | Engine 生命周期、方向采样、异步 prepare、surface 绘制和平滑换图 |
| `ImageSourceActivity.java` | framework 设置 UI、横竖草稿、SAF 多选导入、保存冲突和系统壁纸选择器 |
| `WallpaperConfig.java` | legacy bundled fallback 名称和固定 renderer 参数；不是用户 v1 配置仓库 |

可以在当前扁平包结构内继续开发。只有当拆分本身是任务目标时，才创建新包或迁移 production source set。

## 配置与编辑事务

- 根配置始终包含 portrait、landscape 两份非 null profile，schema v1 固定为 `1`；首份持久化配置 revision 从 `1` 开始。
- 同一 profile 内 URI 不重复；图片 ID 是稳定 UUID。数组位置是唯一顺序，不增加额外 `sortIndex`。
- 修改配置必须基于 `ConfigBaseToken` 走方向级 CAS；冲突时要求 UI 重新加载，不能 last-write-wins。
- 保存只替换目标 profile，同时保留另一方向的最新值。任何 API 都不能让两个编辑页面用旧快照互相覆盖。
- 合法空 profile 是用户选择，不等同于“从未保存”。空 profile 要显示明确中性空状态，不能继续保留另一方向或旧配置的帧。
- `Corrupt` 只能经用户确认 reset；`UnsupportedVersion` 始终只读。两者均不得借普通保存路径覆盖原文件。

## SAF 与 URI grant

- 选图使用 `ACTION_OPEN_DOCUMENT`、`image/*`、`CATEGORY_OPENABLE`、多选和持久读取 flag；同时处理 `ClipData` 与单个 `data` URI，并保持选择顺序。
- 导入按项失败，已通过项可继续；在取得 lease 前先检查重复、图片可解码性和完整候选配置的大小/数量限制。
- Activity 不直接调用 take/release 形成自己的权限状态；统一使用 `UriGrantRegistry` 的 lease/token 协议。
- 同一 URI 可被两份 profile 或多个草稿共享。只有没有 committed reference、有效 lease，且 ownership 确认可由 v1 释放时，才释放系统 grant。
- Activity/editor generation 之外到达的异步导入或 probe 回调必须失效；保存、取消、Back、重建、reset 后不能重新污染草稿或 ledger。
- content provider 可能返回 pipe 或不可 seek descriptor。解码路径不能假设 `lseek` 可用，也不能无界复制输入。

## 播放、解码与渲染

- `WallpaperPlaybackController` 决定候选、计时和提交；renderer 只有在完整帧成功 post 后才把候选标记为已显示。
- 配置更新时按稳定 ID 归并状态。已删除或失败目标不能残留在 current、shuffle bag、pending transaction 或 incoming frame 中。
- 每个 Engine 拥有自己的 controller；controller 内的 portrait/landscape state 独立。preview Engine 不得推进 active Engine。
- visible/surface/config/orientation generation 变化时，取消旧 handler、Choreographer、decode 回调和播放事务。旧 generation 不得提交到新 surface。
- 解码不得在主线程进行；先 bounds/sample，再受进程级 reservation 约束。active、preview 和 settings probe 共用预算。
- 切换过程中只持有预算允许的稳定帧和 incoming 帧；替换、失败、销毁时按现有所有权约定释放 bitmap，避免重复 recycle 或泄漏。
- 首个候选失败时，在有界次数内尝试其它可用图；失败项进入 availability backoff。0/1 张可用图不建立无意义轮询，但 backoff 到期仍要有独立唤醒路径。
- 方向优先读取 configuration；bounds 兜底使用现有迟滞阈值。不要用 wallpaper desired minimum width、原图比例或瞬时 surface 宽度创建新的方向语义。

## UI、资源与 manifest

- 设置页继续使用 framework UI，除非任务明确批准生产 AndroidX/Compose 迁移。test-only AndroidX 依赖不能泄漏进 production implementation。
- 横屏与竖屏都必须支持添加、移除、上移/下移、间隔和顺序/随机模式；拖动不能成为唯一可访问排序方式。
- 未保存修改使用显式保存/取消和 Back 确认；Activity 重建应恢复每个方向各自的 dirty 状态与草稿。
- 用户可见字符串优先放在 `res/values/strings.xml`；不要新增只写死一种语言的关键提示。
- production manifest 只注册 launcher 和 live wallpaper service。test provider、测试 Activity 或测试权限只能存在于 `app/src/androidTest/AndroidManifest.xml`。
- 更换 bundled assets 时同步核对 `source/tablet-live-wallpaper/assets/`、fallback 常量和 `docs/bundled-images.md`，但只有明确的美术/交付任务才允许这样做。

## 测试落点

- 纯 Java 模型、校验、播放状态、伪时钟和确定性随机行为放在 `app/src/test`；随机测试固定 seed。
- 依赖 Android runtime、AtomicFile、ContentResolver、SAF grant、Activity 重建、provider pipe、renderer/Engine 生命周期的行为放在 `app/src/androidTest`。
- 回归测试必须复现缺陷的失败条件，而不只是调用新代码的 happy path。
- API 26–27 仍是已知未覆盖区间。涉及 API 分支、旧 Canvas 或方向 fallback 的改动，不能只凭 API 35 模拟器结果宣称全版本通过。

## 静态安全检查

涉及 manifest、壁纸启用或锁屏边界时，至少确认生产路径没有下列内容：

```sh
cd source/tablet-live-wallpaper
! rg -n "android\.permission\.SET_WALLPAPER|WallpaperManager\.FLAG_LOCK" \
  AndroidManifest.xml src
```

只向 `rg` 传入实际存在的 production source-set 路径；将来若迁移到 `app/src/main`，应同步替换此命令，不能利用路径错误形成假阳性。
