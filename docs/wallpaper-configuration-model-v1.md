# 壁纸配置数据模型 v1

设计日期：2026-07-20
状态：v1 已于 2026-07-21 完成实现、回归审查、模拟器验证和 Xiaomi/HyperOS 真机验收

本文保留 v1 产品、schema、URI 权限和播放契约。第 1 节与第 14 节说明 legacy 迁移边界；当前实现以源码为准，验证流程见 [AGENTS_DEV.md](../AGENTS_DEV.md)。

## 1. 背景与目标

本设计启动前，应用的运行规则直接来自常量和 APK 内置资源名：

- 横屏固定读取一张硬编码图片；
- 竖屏固定读取一个硬编码图片数组；
- 只有竖屏存在硬编码轮询时间；
- 应用内旧的图片选择器不是 Live Wallpaper 的实际数据源。

当时的配置、轮播规则和渲染逻辑全部混在 `FitWallpaperService` 中，很难继续扩展。

v1 已建立一套标准化配置模型：横屏和竖屏分别持有独立配置，但使用完全相同的数据结构。用户可以在应用内为任一方向选择图片、调整图片顺序、设置轮询时间并选择播放顺序。WallpaperService 消费仓库快照；只有配置文件从未存在的 `LegacyFallback` 状态仍可读取内置资源。

实施过程同时补齐了用户图片输入所必需的有界解码、surface generation 失效、空 profile 清屏、provider snapshot 和 Engine 生命周期保护。跨进程播放位置持久化、API 26–27 实机覆盖以及更长期的 OEM 清理恢复仍留在第 17 节。

## 2. v1 功能范围

v1 固定包含以下功能：

1. 横屏和竖屏分别配置，互不覆盖。
2. 用户可以在应用内为任一方向选择多张本地图片。
3. 用户可以添加、移除并手动调整图片顺序。
4. 横屏和竖屏分别设置轮询间隔。
5. 横屏和竖屏分别选择顺序播放或洗牌播放。
6. 自动换图继续使用固定的平滑切换，不能直接硬切。

应用仍只接管主屏幕壁纸，不接管锁屏。

## 3. v1 明确不做的内容

- 同一方向下继续按 4:3、16:9、16:10 等比例拆分配置；
- 根据图片比例自动判断应该加入横屏还是竖屏；
- 针对手机、平板、折叠屏或外接显示器建立不同配置；
- 让用户选择转场样式或调整转场时长；
- 按时间段、电量、充电状态、网络等条件切换；
- 云端或远程图片源；
- 同时管理主屏幕和锁屏；
- 新增视觉效果；
- 修复现有 surface、黑屏或进程重启问题；
- 完整的 Bitmap 缓存和性能调优，但不包括 v1 必需的有界解码与双帧内存上限。

当前已经接受的平滑切换作为 renderer 的固定行为保留，但不进入 v1 可编辑配置。未来如需开放转场参数，应通过新的 schema 版本增加。

## 4. 核心设计决策

使用一个通用 `WallpaperProfile`，在根配置中保存两个实例：

- `portraitProfile`
- `landscapeProfile`

不创建 `PortraitWallpaperProfile` 和 `LandscapeWallpaperProfile` 两个子类。当前两种方向只有配置数据不同，字段和业务规则完全一致。使用继承会重复序列化、校验、UI 映射和迁移逻辑，也容易让两套模型逐渐分叉。

如果未来横屏和竖屏采用不同渲染算法，应在 renderer 层使用不同策略，不应因此拆分持久化数据模型。

## 5. 模型总览

```text
WallpaperConfiguration
├── schemaVersion
├── revision
├── portraitProfile: WallpaperProfile
└── landscapeProfile: WallpaperProfile

WallpaperProfile
├── images: List<WallpaperImage>
└── rotation: RotationSettings

WallpaperImage
├── id
├── sourceUri
└── displayName

RotationSettings
├── intervalMillis
└── orderMode: SEQUENTIAL | SHUFFLE
```

用户配置和播放运行状态必须分开：

- 配置描述“用户选了什么、希望怎样播放”；
- 运行状态描述“当前播到哪张、下次何时切换、洗牌队列还剩什么”。

`currentIndex`、`nextChangeAt`、洗牌队列等字段不能放进 `WallpaperProfile`。

## 6. 模型定义

### 6.1 `WallpaperConfiguration`

根聚合对象，也是配置仓库唯一保存的完整对象。

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `schemaVersion` | integer | 是 | 持久化结构版本；v1 固定为 `1`。 |
| `revision` | long | 是 | 每次成功保存递增的配置修订号，用于拒绝陈旧编辑覆盖。第一份持久化 v1 为 `1`。 |
| `portraitProfile` | `WallpaperProfile` | 是 | 当前设备方向为竖屏时使用。 |
| `landscapeProfile` | `WallpaperProfile` | 是 | 当前设备方向为横屏时使用。 |

即使图片列表为空，两份 profile 也必须存在。UI、仓库、播放控制器和 renderer 不应到处处理 profile 为 null 的情况。

### 6.2 `WallpaperProfile`

一份完整的方向配置。

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `images` | `List<WallpaperImage>` | 是 | 该方向的用户图片列表；数组位置就是唯一播放/显示顺序。 |
| `rotation` | `RotationSettings` | 是 | 该方向的轮询间隔和播放顺序。 |

方向由根对象中的字段名确定，不在 profile 内重复保存，避免出现 `portraitProfile` 内又声明 `orientation=LANDSCAPE` 的矛盾状态。

### 6.3 `WallpaperImage`

一张用户选择图片的稳定引用。

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `id` | string | 是 | 图片加入配置时生成的稳定 UUID。 |
| `sourceUri` | string | 是 | Storage Access Framework 返回的可持久化 `content://` URI。 |
| `displayName` | string | 是 | 经过清理和限长的显示名称，只用于 UI。 |

约束：

- 应用内部使用 `id` 标识条目，不能用文件名充当主键；
- 同一个 URI 可以分别出现在横屏和竖屏 profile 中；
- 同一个 URI 不能在同一 profile 内重复出现；
- 移除或排序只改变 `images` 数组位置，不再保存可能冲突的 `sortIndex`；
- 每个 profile 最多 64 项，整份配置最多 64 个不同 URI；同一 URI 被两个 profile 引用只计一次；
- `sourceUri` 最长 4096 个字符，序列化 JSON 最大 256 KiB；超限必须在取得多余权限前拒绝；
- `displayName` 去除控制字符、折叠空白并截断到 120 个 Unicode code point；元数据缺失时依次使用 URI 最后路径段和本地化的“未命名图片”；
- URI 暂时不可访问时，运行时跳过该项并在设置页标记不可用，不能静默删除用户配置。

### 6.4 `RotationSettings`

| 字段 | 类型 | 必填 | 含义 |
|---|---|---:|---|
| `intervalMillis` | long | 是 | 两次自动换图之间的时间，统一以毫秒保存。 |
| `orderMode` | enum | 是 | `SEQUENTIAL` 或 `SHUFFLE`。 |

v1 约束：

- 最短 5 秒；
- 最长 24 小时；
- 默认 10 秒；
- 横屏和竖屏分别保存各自的间隔；
- 可用图片为 0 或 1 张时不启动轮播任务；退避图片的 availability-retry 不受此条限制。

`SEQUENTIAL` 按 `images` 数组顺序播放，最后一张之后回到第一张。

`SHUFFLE` 使用洗牌袋算法：一轮内每张可用图片只出现一次；存在至少两张图片时，新一轮第一张不能和上一轮最后一张相同，避免连续重复。

## 7. 持久化示例

```json
{
  "schemaVersion": 1,
  "revision": 3,
  "portraitProfile": {
    "images": [
      {
        "id": "74be54ca-33ba-4a50-9246-8c2eb5acd805",
        "sourceUri": "content://com.android.providers.media.documents/document/image%3A1001",
        "displayName": "portrait-a.jpg"
      },
      {
        "id": "d8f15cb5-2f58-4ce6-bce8-dab2cc38ae40",
        "sourceUri": "content://com.android.providers.media.documents/document/image%3A1002",
        "displayName": "portrait-b.jpg"
      }
    ],
    "rotation": {
      "intervalMillis": 10000,
      "orderMode": "SEQUENTIAL"
    }
  },
  "landscapeProfile": {
    "images": [
      {
        "id": "88bc719e-d4d6-47cb-bc35-f7d2f85ecba7",
        "sourceUri": "content://com.android.providers.media.documents/document/image%3A2001",
        "displayName": "landscape-a.jpg"
      }
    ],
    "rotation": {
      "intervalMillis": 30000,
      "orderMode": "SHUFFLE"
    }
  }
}
```

## 8. 方向选择规则

profile 表示设备当前横竖方向，不表示壁纸 surface 的原始宽高比。Launcher 为滚屏可能创建宽于可见屏幕的 surface，因此不能仅凭 `surfaceWidth > surfaceHeight` 选择 profile。

方向在 `WallpaperService.onConfigurationChanged`、Engine `onVisibilityChanged(true)`、`onSurfaceCreated` 和 `onSurfaceChanged` 时重新采样，按以下优先级处理：

1. 读取 `WallpaperService.getResources().getConfiguration().orientation`。明确为 `ORIENTATION_PORTRAIT` 或 `ORIENTATION_LANDSCAPE` 时立即采用，这是 API 26–35 的主信号。
2. 返回 `ORIENTATION_UNDEFINED` 时沿用该 Engine 最近一次有效方向，不用 surface 比例推翻已有方向。
3. Engine 尚无历史方向时才读取默认显示器 bounds。API 26–29 使用 `WindowManager.getDefaultDisplay().getRealMetrics()`；API 30–35 从服务的 display context 取得 `WindowManager.getCurrentWindowMetrics().getBounds()`。v1 已排除外接显示器，因此只支持 `Display.DEFAULT_DISPLAY`，不能把其他 display 的 metrics 混入当前 Engine。
4. 兜底比例 `width / height >= 1.10` 才判横屏，`height / width >= 1.10` 才判竖屏；中间 10% near-square 区间保持历史方向。初次启动既无有效 configuration 又落在该区间时，延迟 profile 激活并保留已有稳定帧；没有稳定帧则显示中性空状态，直到获得明确信号。
5. bounds 兜底一旦选择方向，只有明确的 configuration 或相反方向跨过 1.10 阈值才切换，形成迟滞，避免瞬态尺寸来回抖动。不得使用 wallpaper desired minimum width 或 surface 原始宽度作方向信号。

每次方向选择都记录触发信号，测试日志只记方向和尺寸，不记录 URI。v1 不读取所选图片的宽高比，也不建立额外比例分组。用户自行决定图片属于哪一个 profile，任意尺寸的图片都允许加入任一方向。

两份 profile 维护各自独立的播放状态。切换到横屏不能推进或重置竖屏序列；再次回到竖屏时，应从竖屏自己的状态继续。方向变化只切换 controller 的活动状态，不能新建或复制一份配置。

## 9. 用户选图与 URI 权限

设置页使用 Android Storage Access Framework：

- 启动 `ACTION_OPEN_DOCUMENT`；
- MIME 类型使用 `image/*`；
- 添加 `CATEGORY_OPENABLE`、`EXTRA_ALLOW_MULTIPLE=true` 和读/持久化权限 flag；
- 同时处理 `ClipData` 与 `data.getData()`，保持用户选择顺序并在加入前按目标 profile 的 URI 去重；
- 对每个接受的 URI 通过仓库的 `UriGrantRegistry` 取得持久读取 lease；Activity 不直接持有或释放 grant；
- 将 URI 字符串保存到 `WallpaperImage.sourceUri`；
- 不依赖外部存储绝对路径。

用户从横屏或竖屏编辑页进入选图，因此目标 profile 是明确的。v1 不根据 EXIF 或图片尺寸自动决定归属。

选择结果按 URI 独立处理。无法取得持久读取权限、不是可解码图片、重复或超过限制的项目不进入草稿；其余项目仍可成功加入。UI 汇总显示“成功 N 项、失败 M 项”，并允许展开每项原因。不能只保留临时访问权限，否则应用或设备重启后配置会失效。

URI grant 由应用级 `UriGrantRegistry` 串行管理，是否释放同时取决于“已提交配置引用”和“尚未提交的草稿 lease”：

- 每个编辑草稿有稳定 `draftId`。选图时调用 `acquireLease(draftId, uri)`；同一 URI 可被多个草稿租用，只取得一份 app-wide grant，取消其中一个草稿不能影响另一个；
- registry 在单独的私有 `AtomicFile` ledger 中记录 v1 自己取得的 URI、pending acquisition 和 draft lease。先写 pending 再调用 `takePersistableUriPermission`，随后标记 leased；进程在任一步终止都可通过系统 `persistedUriPermissions` 幂等归并；
- 保存成功后，配置文件先写入，此时 lease 仍保护 grant；再把该草稿 lease 归并为从最新配置计算的 committed reference。保存冲突时保留 lease，供用户重载/重试；取消草稿时删除对应 lease；
- 移除图片也是先提交配置，再归并 ledger。只有 URI 不在横竖任一 profile 且没有任何 draft lease 时才调用 `releasePersistableUriPermission`；
- 草稿 cache 与 lease 共同保留，24 小时未触达后过期。启动时清理过期 lease，并释放“ledger 明确标记为 v1-owned、配置无引用、无有效 lease”的孤儿 grant；未知历史 grant 永不自动释放；
- 配置引用存在但系统 grant 缺失时，条目保持配置并标记不可用。registry 操作和配置 CAS 共用同一串行边界，所有归并可重复执行；
- 不在普通日志、ledger 诊断摘要和错误文案中输出完整 URI。

## 10. 设置界面

```text
壁纸设置
├── 竖屏设置
│   ├── 图片列表和缩略图
│   ├── 添加图片
│   ├── 移除图片
│   ├── 拖动排序 / 上移 / 下移
│   ├── 轮询间隔
│   └── 播放顺序
└── 横屏设置
    ├── 图片列表和缩略图
    ├── 添加图片
    ├── 移除图片
    ├── 拖动排序 / 上移 / 下移
    ├── 轮询间隔
    └── 播放顺序
```

每个图片条目显示缩略图、显示名称、拖动手柄和移除操作，同时向无障碍服务提供图片名称、当前位置、上移、下移和移除 action，不能把拖动作为唯一排序方式。移除后提供可撤销提示。洗牌模式下仍允许调整手动顺序，因为切回顺序播放时必须保留用户排列。

每个 profile 编辑页使用显式“保存 / 取消”：

- 页面内修改先进入内存草稿；当前无 AndroidX，使用 framework `onSaveInstanceState` 保存 draft ID，并把完整草稿写到应用私有 cache 文件，Activity 重建后按 ID 恢复。保存、取消或过期时清理草稿文件；离开有未保存修改时二次确认；
- 保存携带打开页面时的 `ConfigBaseToken`，调用仓库的方向级事务；仓库在最新根配置上只替换目标 profile，并原子写回完整 `WallpaperConfiguration`；
- token 不匹配时不得静默覆盖，UI 提示配置已被其他页面更新，并让用户重新加载后再应用；
- 校验错误显示在对应间隔字段或图片项上，URI 批量导入错误使用可展开汇总；
- 一次成功保存必须产生一份完整且经过校验的新配置。不能继续分别写入松散的 `SharedPreferences` key。

### 10.1 壁纸启用边界

设置页可以调用系统 Live Wallpaper 预览/选择界面，并明确提示用户在系统提供选项时选择“仅主屏幕”。应用自身不调用锁屏壁纸写入 API，也不以 `FLAG_LOCK` 修改锁屏。

“仅主屏幕”最终由系统/OEM 选择器决定；如果 ROM 只允许“主屏幕与锁屏同时应用”，应用必须明确告知限制，不能宣称能够绕过系统强制保证锁屏不变。目标设备验收时记录启用前后的锁屏壁纸状态；不满足时将该 ROM 标记为不支持仅主屏幕启用，而不是写入私有接口规避。

## 11. 持久化边界

新增 `WallpaperConfigRepository`，作为配置唯一的读写入口。

职责：

- 加载当前配置；
- 保存前统一校验并规范化；
- 原子替换整份持久化配置；
- 向设置页和 WallpaperService 提供不可变配置快照；
- 保存成功后通知 WallpaperService；
- 集中处理未来 schema 迁移。

`load()` 不得把所有异常都伪装成“空配置”，必须返回以下封闭结果之一：

| 结果 | 含义 | v1 行为 |
|---|---|---|
| `LegacyFallback` | 私有配置文件从未存在。 | 只在首次保存前把 bundled assets 作为只读运行时兜底；它不是标准模型，也不能表示用户主动保存的空列表。 |
| `ValidV1(configuration)` | 成功读取 schema 1；两个 profile 可以合法为空。 | 正常编辑和播放，持久化配置是唯一数据源。 |
| `UnsupportedVersion(version)` | 文件 schema 高于当前版本。 | 保留原文件，禁止编辑和隐式覆盖，提示需要升级应用。 |
| `Corrupt(error)` | 文件存在但无法完整解析或校验。 | 保留原文件及 `AtomicFile` 备份，返回显式错误；不得重新启用 bundled assets。用户确认“重置配置”后才写入新的空 v1。 |

旧设置页的 `KEY_IMAGE_URI` 并不是现有 Live Wallpaper 的可靠方向数据源，v1 不自动导入它，也不能将它猜测成某个 profile。迁移期只读保留该 key，验证 v1 后再删除。这样“从未配置”“用户保存空 profile”“旧偏好存在”“损坏/未来版本”不会互相混淆。

v1 将一份 JSON 文档保存在应用私有目录，并使用 Android `AtomicFile` 的 `startWrite`、`finishWrite`、`failWrite` 完成落盘和中断恢复；成功返回前必须完成 flush/sync。图片列表不能拆成许多互不关联的 `SharedPreferences` key。读取时先限制文件大小并检查 `schemaVersion`，再解析业务字段。未知版本和损坏文件不能在普通保存路径上被覆盖。

所有读取—修改—写入由进程内单一仓库实例和串行 executor/mutex 执行。编辑基线使用不透明的 `ConfigBaseToken`：`LegacyAbsentToken` 表示打开页面时配置文件不存在，`RevisionToken(n)` 表示读取到持久化 revision n。方向编辑使用 `updateProfile(orientation, baseToken, transform)`：前者 CAS 要求文件仍不存在，后者要求最新 revision 等于 n；成功后只替换目标 profile，并写入 revision `n + 1`。第一份 v1 使用 revision 1，任何文件一旦存在，所有旧 `LegacyAbsentToken` 立即冲突。v1 不给 Activity 或 Service 配置 `android:process`，因此无需自建跨进程协议。

保存成功后，仓库向当前进程内注册的所有 Engine 发布同一个不可变快照。每个 Engine 在变为可见时还会重新 `load()`，覆盖进程被重建或通知遗漏的情况。通知只表达“新快照可用”，不能携带可变列表或直接控制某个 Engine 的播放位置。

播放运行状态由 `WallpaperPlaybackController` 持有，不属于配置 JSON：

```text
OrientationPlaybackState
├── currentImageId
├── shuffleBagImageIds
├── nextChangeAtElapsedRealtime
├── remainingDelayMillis
├── availabilityRetryAtElapsedRealtime
└── unavailableUntilByImageId
```

每个 `WallpaperService.Engine` 拥有一个 controller，controller 内再持有横、竖两份状态。预览 Engine 与已启用壁纸 Engine 完全隔离，预览不能推进主屏幕的播放状态；Engine 创建时注册配置监听，销毁时取消计时/解码任务并注销。播放位置是否跨进程重启保留，留到后续可靠性阶段决定；无论是否持久化，都必须和用户配置分开。

## 12. 播放控制器与 renderer 边界

`WallpaperPlaybackController` 接收带 revision 的不可变配置快照，负责：

- 当前图片和下一张图片的选择；
- 顺序循环和洗牌袋规则；
- 轮询间隔调度；
- 横屏、竖屏两份独立运行状态。

播放控制器不负责解码或绘制 Bitmap，也不能修改持久化配置。

### 12.1 配置变化时的状态归并

controller 收到新 revision 后，在串行事件队列中按以下规则归并，不能让旧 ID、旧计时或旧解码结果回写：

1. `currentImageId` 仍存在时保留当前图；已被移除时，顺序模式选择原位置在新数组中的后继（越界回到第一项），洗牌模式从重建后的袋首选择；空列表则清空状态。
2. 顺序模式只改排序时保留当前图，下一次从当前图在新数组中的后一个位置继续。
3. 图片集合或 `orderMode` 变化时，删除所有陈旧 ID 并重建洗牌袋；袋中包含除当前图外的全部新可用 ID，因此新加入图片会在当前轮出现。只改排序且仍为洗牌模式时可保留现有袋。
4. `intervalMillis` 变化时不立即换图；活动方向从收到配置时开始按新间隔重新计时，非活动方向把 `remainingDelayMillis` 重置为新间隔。
5. render generation 由配置 revision、方向和 surface epoch 共同构成。每次配置/方向变化，以及 `surfaceCreated`、任意宽高或 format 的 `surfaceChanged`、`surfaceDestroyed` 都递增 epoch，取消旧计时、prepare 和 animator；迟到的旧 generation 结果只能释放，不能显示或推进状态。
6. controller 保留“最后完整显示的稳定帧”。transition 进行中若目标被移除、配置变化或 surface 改变，立即取消动画并丢弃旧目标；surface 仍有效时暂时保留稳定帧，按最新 generation/尺寸重新 prepare。surface 已销毁则释放全部帧。
7. prepare 成功只建立待切换状态；只有同 generation 的 transition 完整结束后，才原子提交新的 `currentImageId` 和成功候选对应的袋状态。解码失败或被取消的动画都不能把从未完整显示的 ID 记录成 current。

### 12.2 计时和可见性

所有截止时间只使用 `SystemClock.elapsedRealtime()`，不能使用墙上时间：

- 只有“Engine 可见且属于当前方向”的一份状态拥有计时任务；
- Engine 变为不可见或方向切走时，保存 `max(0, deadline - now)` 为剩余时间并立即取消 handler/coroutine；非活动方向的倒计时暂停；
- 再次激活时按剩余时间恢复。若取消与回调竞态导致剩余时间为 0，只允许立即推进一次；错过多个间隔也禁止追赶式连续换图；
- 自动换图成功后才从完成时刻安排一个完整新间隔；prepare 失败不会产生高频重试；
- 0 张或 1 张可用图片时不保留“轮播”计时任务；如果仍有退避中的配置项，单独保留一次最早 `availabilityRetryAtElapsedRealtime`。surface 或 Engine 销毁时两类任务都取消。

方向变化时：

1. 按第 8 节的系统方向信号选择另一份 profile；
2. 暂停旧方向并恢复新方向自己的播放状态；
3. prepare 并显示新方向当前图片；
4. 使用新方向保存的剩余时间或完整间隔安排下一次换图。

### 12.3 解码、失败重试和平滑换图

renderer 提供异步 `prepare(image, surfaceSize, generation)` 与 `transition(preparedFrame)` 两阶段接口。所有 renderer、预览 Engine、设置页头部校验和缩略图都必须经过应用级单例 `DecodeCoordinator`，不能各建解码线程池或内存上限。controller 收到同 generation 的成功结果后才进入待切换状态；只有同 generation 的 transition 完成后才提交 `currentImageId`。横屏不能绕过转场路径。首次加载或此前确实没有可显示帧时可以直接显示准备好的首帧；已有帧的自动轮播不得硬切。

`DecodeCoordinator` 使用有界队列和最多 2 个 worker，并以 active Engine、系统预览、设置页缩略图的顺序调度优先级。每个任务在分配前预留预计峰值内存，长期持有的当前/待切帧直到释放都占用 reservation。没有足够预算时先提高采样率；仍无法满足时返回结构化资源不足，不能并发超配，也不能为了预览或缩略图驱逐 active Engine 当前帧。设置页缩略图最长边限制为 256 px，格式校验优先只读 header。

有界解码是 v1 的输入安全要求：

- 先只读图片头，拒绝无法识别、宽高非正、任一边超过 32768 或总像素超过 100 MP 的源；
- 根据当前 surface 和既有 fit 规则计算目标尺寸。API 28+ 使用 `ImageDecoder` 目标尺寸，API 26–27 使用 `BitmapFactory.Options.inJustDecodeBounds` 与 `inSampleSize`，随后只向目标尺寸缩放；禁止直接解码原始流；
- 全进程共享的解码/绘制工作集预算为 `min(64 MiB, Runtime.maxMemory() / 4)`。reservation 必须覆盖所有 Engine 与 UI 的当前帧、待切帧、API 26–27 采样中间帧、模糊/背景派生 Bitmap、像素或算法 scratch array、Canvas 临时 layer 和缩略图；估算超限时继续提高采样或拒绝该图；
- 每个 Engine 最多保留当前帧和一张已准备的下一帧，同时仍受全进程预算约束。generation 失效、transition 结束、surface 销毁和 Engine 销毁时及时释放 Bitmap 及其 reservation；
- 捕获安全异常、文件消失、格式错误与内存拒绝，向 controller 返回结构化失败类型，不能让解码异常结束 Engine 主循环。

一次轮询最多按播放规则尝试每个配置项一次，不能无限寻找“下一张”。洗牌选择使用明确的单事务状态：从 staged bag 弹出候选并标为 in-flight；prepare 失败时只提交“从 bag 移除 + 写入退避”，`currentImageId` 不变，然后在本次上限内尝试下一项；prepare 与 transition 成功时才原子提交新的 current 和 staged bag。generation 取消时丢弃整个未提交事务，并按最新配置重建/归并。

失败项按连续失败使用 1 分钟、5 分钟、30 分钟（封顶）的 `unavailableUntil`。到期项经一次有界 probe 成功后，在洗牌模式下随机插入当前 bag（已经是 current 或 bag 已含该 ID 时不重复插入），顺序模式下恢复其数组资格；新一轮只从当时不在退避的非 current ID 构建。重新授权、重新添加或影响该条目的配置变化会清除退避。

轮播 timer 与 availability-retry timer 是两个逻辑截止时间，可以共用一个 scheduler 实现但不能互相吞掉。即使当前只有 1 张可用图片，也必须为退避项在最早到期时间唤醒一次；probe 后达到 2 张可用项时，从该时刻按完整间隔恢复轮播，禁止立即连续补播。所有候选都失败时保留最后一张成功帧；从未成功显示过图片时才显示空状态。设置页用“权限失效 / 无法解码 / 超过安全限制”等状态标记问题，但不自动删除配置。

### 12.4 非持久化图片诊断

应用级 `WallpaperDiagnosticsStore` 负责把运行时失败传给设置页，但不能写回用户配置。记录以 `(configurationRevision, imageId)` 为键，只包含失败类别、时间戳、来源角色（`ACTIVE_ENGINE`、`PREVIEW_ENGINE`、`SETTINGS_PROBE`）和 surface 尺寸分类，不包含 URI 或 provider 元数据。

- active Engine 的完整 renderer 结果是“主屏实际可显示性”的权威状态；preview 结果单独标注，不能覆盖 active 状态；
- 设置页打开时通过同一 `DecodeCoordinator` 执行权限/header/小尺寸 probe，并把它显示为“本页检查”，不能据此声称某个大 surface 一定可渲染；
- 成功显示、重新授权、条目内容变化或移除时立即清除对应失败；临时资源/解码失败 30 分钟后过期，权限失效保持到重新检查；
- store 只在进程内存在。进程重启后由 active Engine 的下一次 prepare 和设置页打开时的 probe 重新建立，UI 必须能表达“尚未检查”。

## 13. 校验和失败行为

保存前必须保证：

- schema 版本受支持；
- revision 大于等于 1，且 `RevisionToken` 与仓库最新快照一致；首次保存则必须持有仍有效的 `LegacyAbsentToken`；
- 横屏和竖屏 profile 都存在；
- 每个图片条目都有非空 ID 和 URI；
- 整份配置中的图片 ID 唯一；
- 同一 profile 内 URI 不重复；
- 图片项数、不同 URI 数、URI 长度、显示名称长度和 JSON 总大小不超过第 6.3 节限制；
- 轮询间隔在允许范围内；
- 播放顺序是已知枚举值。

运行时：

- 当前 profile 没有图片时显示空状态，不启动轮询；
- 只有一张可用图片时正常显示，不启动轮播；存在退避项时仍按第 12.3 节保留一次 availability-retry；
- 某张图片不可访问或无法安全解码时执行第 12.3 节的有界尝试和退避；
- 全部图片不可用时尽量保留最后一张成功帧，并在设置页暴露逐项问题；
- 配置损坏或版本过新时返回显式不可编辑状态，不能重新进入横屏或竖屏硬编码分支，也不能隐式覆盖原文件；
- 普通 UI 和 release 日志只使用图片 ID 与失败分类，不能输出完整 URI、文档路径或 provider 元数据。

## 14. 从现有硬编码逻辑迁移

迁移完成后，以下常量不能再作为已持久化 v1 配置的运行规则数据源：

- `BUNDLED_LANDSCAPE_IMAGE_NAME`
- `BUNDLED_PORTRAIT_ROTATION_IMAGE_NAMES`
- `PORTRAIT_ROTATION_INTERVAL_MS`

已落地的迁移边界：

1. 模型和 `WallpaperConfigRepository` 已成为标准配置入口。
2. 仓库确认配置文件从未存在时返回 `LegacyFallback`；只有该状态可以继续使用旧 bundled assets 行为。
3. 用户第一次保存设置后，持久化的 v1 配置立即成为唯一数据源。
4. WallpaperService 此后只读取 `WallpaperConfiguration`，不再读取方向专用资源常量。
5. 第一次保存必须是 CAS 幂等事务：持有 `LegacyAbsentToken` 且文件仍不存在 → 构建并校验 revision 1 → 使用 `AtomicFile` 写入 → 成功后才切换内存数据源和整理 grant；写入失败继续保持 `LegacyFallback`，同时打开的其他 legacy 编辑页随后保存必须收到冲突。
6. 已存在的空 v1、损坏文件和未来版本都不得触发兜底。用户显式重置损坏文件后写入空 v1，也不得重新进入兜底。
7. 旧 `KEY_IMAGE_URI` 偏好不再是 Live Wallpaper 的方向配置数据源；仍保留的常量只服务 legacy 兼容边界。

硬编码资源不能被伪装成用户选择的 `content://` 数据。兼容兜底只存在于迁移边界，不进入标准模型。

## 15. 建议代码边界

```text
model/
├── WallpaperConfiguration
├── WallpaperProfile
├── WallpaperImage
├── RotationSettings
└── OrderMode

data/
├── WallpaperConfigRepository
├── ConfigLoadResult
├── ConfigBaseToken
└── UriGrantRegistry

playback/
├── WallpaperPlaybackController
├── OrientationPlaybackState
└── WallpaperDiagnosticsStore

ui/
├── WallpaperSettingsActivity
└── ProfileEditor

wallpaper/
├── FitWallpaperService
├── WallpaperRenderer
├── BoundedImageDecoder
└── DecodeCoordinator
```

实际包名可以继续服从当前最小项目结构。关键要求是：模型、持久化、播放决策、UI 编辑和渲染不能继续全部堆在 `FitWallpaperService` 中。

### 15.1 构建与测试基础设施决策

v1 实现统一使用 `source/tablet-live-wallpaper` 下的 Android Gradle 工程：Android Gradle Plugin 8.11.2、Gradle Wrapper 8.13、Java 17、compile/target SDK 35、min SDK 26。版本集中在 `gradle/libs.versions.toml`，Android Studio、开发机和 CI 都只调用仓库内的 `./gradlew`。

当前 `app` 模块通过 `sourceSets` 接入既有 `src`、`res`、`assets` 与 manifest，避免把业务实现和目录迁移混在一起。v1 生产类继续位于 `src/com/codex/tabletlivewallpaper` 的扁平包中；JVM 与 instrumentation 测试分别使用标准的 `app/src/test`、`app/src/androidTest`。若以后迁移 production source set，应单独做机械变更并同步构建与文档。

实施和 CI 的固定入口：

1. `./gradlew :app:testDebugUnitTest`：运行不依赖 Android runtime 的模型、播放归并、洗牌和伪时钟性质测试；随机测试必须固定 seed。
2. `./gradlew :app:lintDebug`：执行 Android Lint，错误直接阻断。
3. `./gradlew :app:assembleDebug`：生成可安装 debug APK。
4. `./gradlew :app:connectedDebugAndroidTest`：运行 renderer、Engine、SAF、grant、Activity 生命周期和 provider 场景；test-only provider 只注册在 `app/src/androidTest/AndroidManifest.xml`。

Instrumentation 已使用 AndroidX Test runner/ext-junit，并在 `gradle.properties` 中启用 AndroidX；这些依赖仅存在于 `androidTestImplementation`，没有引入 AndroidX 生产运行时，也没有改写 framework 设置页。

静态安全检查必须只传入实际存在的 production source-set 路径。当前 bridge 下检查 `AndroidManifest.xml` 与 `src/com/codex/tabletlivewallpaper`；不能传入尚不存在的 `app/src/main`，否则 `rg` 的路径错误可能被 shell `!` 反转为成功并形成假阳性。

以上 Gradle、JVM、Lint、构建和 connected 测试入口均已投入使用，是当前回归基线。

## 16. v1 验收标准

1. 应用内存在独立的竖屏和横屏设置入口。
2. 用户无需重新构建 APK，即可为任一方向选择多张图片。
3. 应用重启和设备重启后，已选图片仍可读取。
4. 同一张图片可以同时分配给横屏和竖屏。
5. 用户可以移除图片，并通过拖动或无障碍上移/下移调整数组顺序。
6. 横屏和竖屏可以设置不同的轮询间隔。
7. 顺序模式严格按照可见列表顺序循环。
8. 洗牌模式一轮内不重复，并避免两轮交界处连续显示同一张。
9. 切换方向不会修改另一方向的配置或播放位置。
10. 0 张或 1 张可用图片时不会创建无意义的轮播任务；存在退避项时仍能由独立 availability timer 恢复。
11. 自动换图使用固定平滑切换，不主动硬切。
12. v1 配置存在后，修改 bundled asset 常量不会改变用户当前行为。
13. 实现中不出现按具体宽高比分流的第三套 profile。
14. 横屏与竖屏自动轮播都走同一套 prepare/transition 协议；预览 Engine 不推进主屏 Engine。
15. 删除、重排、添加图片以及切换模式/间隔时，播放状态按第 12.1 节确定性归并，不出现陈旧 ID 或追赶式连播。
16. 大图、损坏图片和权限失效不会触发原图无界解码或结束 Engine；最后一张成功帧尽可能保留。
17. `LegacyFallback`、合法空 v1、损坏文件和未来版本在 UI 与运行时可区分，后 3 种都不会重新启用 bundled assets。
18. 两个编辑页基于同一旧 token 保存时，后一次收到冲突而不是覆盖先一次结果；这同时覆盖首次 `LegacyFallback` 保存和普通 revision 更新。
19. URI 被两个 profile 或多个草稿共用时，移除/取消一处不会释放 grant；最后一个配置引用和 lease 都消失后才释放，进程中断产生的 v1 孤儿可安全回收。
20. 应用不调用锁屏写入 API；在系统支持“仅主屏幕”的验收设备上，启用前后锁屏壁纸保持不变。
21. active、preview 与设置页并发解码时仍共同遵守单一进程预算，设置页能区分 active 失败、preview 失败、本页检查和尚未检查。
22. 同方向 surface resize 或 transition 中修改配置会使旧 generation 失效，不提交旧尺寸帧或已移除目标。

### 16.1 验证矩阵

| 层级 | 必测内容 | 通过证据 |
|---|---|---|
| 模型与解析单元测试 | JSON 往返、数组顺序、空 profile、所有边界值、重复 ID/URI、超长元数据、损坏 JSON、未知 schema。 | 自动化测试覆盖每条校验规则；future/corrupt 输入原文件哈希不变。 |
| 仓库与故障注入测试 | 两个 legacy 编辑页首次保存、普通 revision 冲突、并发方向更新、`AtomicFile` 写到一半失败/进程中断、备份恢复；同 URI 多草稿 lease、保存/取消竞态，以及 take/提交/释放每一步终止进程。 | 重启后只能得到完整旧 revision 或完整新 revision，不出现半份配置；旧 token 必冲突；ledger 只回收 v1-owned 孤儿且不误放仍有引用/lease 的 grant。 |
| 播放状态单元/性质测试 | 任意添加、删除、重排、模式切换后的归并；顺序循环；洗牌成功/失败事务与轮界；伪时钟下的暂停、恢复、改间隔、竞态、退避及仅 1 张可用时的到期恢复。 | 对随机操作序列断言 current/bag 只含现存 ID、失败项不成为 current、availability 到期可恢复、无追赶 burst、两方向互不推进。 |
| renderer instrumentation | API 26–27 与当前目标 API 上测试超大、损坏、透明、不同编码图片；active + preview + 设置页同时解码；双帧切换、同方向 resize-during-decode、edit-during-transition 和 surface 销毁。 | 全进程 reservation 峰值不超过第 12.3 节预算，无主线程解码/未捕获异常；旧 surface/目标不提交；横竖屏录屏均无自动硬切。 |
| Engine/lifecycle instrumentation | 预览与主屏 Engine 同时存在、visible 切换、快速横竖旋转、10% 迟滞边界、瞬态 surface 尺寸、Activity 保存、Service/进程重建。 | 预览与主屏状态隔离，销毁后无遗留任务；配置可重新加载；API 26–29 与 30–35 的表驱动方向信号和诊断来源符合第 8、12.4 节。 |
| 真机验收 | 至少覆盖最低 API 模拟器、当前 API 模拟器、目标手机与目标平板/MIUI；执行重启、URI 撤权、文件移动、多选部分失败、64 项上限。 | 保存测试报告、内存峰值和横竖屏轮播录屏；系统支持时记录锁屏启用前后状态。 |

以上矩阵是 v1 验收的一部分，不以“手动看起来能轮播”代替。可靠性 bug 可以另立任务，但本次新增的数据路径、权限路径和播放规则必须先通过对应测试。

## 17. 后续工作与残余风险

v1 已补齐横竖屏 generation 失效、surface resize、空 profile 中性清屏重试、失败退避和生命周期取消。后续工作集中在当前验收尚未覆盖或明确不属于 v1 配置 schema 的范围：

- 在 API 26–27 上执行真实回归，重点覆盖旧 Canvas、`BitmapFactory` 和方向兜底分支；
- 对真实 Wallpaper Engine `SurfaceHolder` 提交失败做更强的设备层故障验证；
- 观察 HyperOS 长时间运行、OneKeyClean 和低内存清理后的服务恢复；
- 决定播放位置是否需要跨进程重启持久化，同时继续与用户配置分离；
- 在不突破进程级预算和 v1 双帧所有权边界的前提下评估进一步缓存与性能调优；
- 单独改进设置页视觉、交互和美术资源，不借此改变 schema、锁屏或横竖 profile 边界。

这些工作必须继续消费标准模型，不能重新添加方向专用硬编码分支。
