# Android 平板动态壁纸

可以竖屏轮播不同壁纸, 在平板上可以横竖屏分别设置不同壁纸.

一个使用原生 Android framework UI 和 Java 编写的本地动态壁纸应用，主要面向 Android 平板，并保留 Xiaomi/HyperOS 系统壁纸选择器的兼容处理。

## 功能

- 横屏、竖屏分别保存图片列表、播放顺序和切换间隔，切换方向时互不覆盖。
- 通过系统文件选择器导入多张本地图片，支持移除、上移和下移。
- 支持顺序循环和洗牌随机播放，可选择 5、10、30、60 秒切换间隔。
- 使用固定的平滑转场，并在应用重启后保留配置和图片读取权限。
- 图片损坏或读取权限失效时进行失败处理，配置损坏时提示重置。

应用只提供主屏幕动态壁纸，不主动写入锁屏。最终可选的应用目标由系统或 OEM 选择器决定；系统提供选项时，请选择“仅主屏幕”。

## 下载与安装

前往 [GitHub Releases](https://github.com/Lumine0708/my_wallpaper/releases/latest) 下载签名后的 APK。每个发布包附带同名 `.sha256` 校验文件。

- 系统要求：Android 8.0 / API 26 及以上，并支持动态壁纸。
- 当前发布版本：[v1.1.0](https://github.com/Lumine0708/my_wallpaper/releases/tag/v1.1.0)。
- 新发布包使用独立的发布签名，无法直接覆盖此前使用其他 key 签名的内部测试包。

### 使用方式

1. 安装并打开“平板动态壁纸”。
2. 进入“竖屏”或“横屏”，添加图片，设置顺序与切换间隔。
3. 点击“保存”，分别提交两个方向的设置。
4. 点击“选择动态壁纸”，在系统预览中应用到主屏幕。

首次保存前，应用使用内置示例壁纸：横屏显示《地出》，竖屏在《蓝色弹珠》和《地出》之间轮播。保存用户配置后，以用户图片列表为准；空列表不会恢复成内置示例。

## 本地图片与隐私

图片通过 Android Storage Access Framework 选择，无需账号，应用不申请联网权限，也不上传图片。配置保存图片的访问引用，读取权限通过系统机制持久保留；删除原文件或撤销权限后，需要重新选择图片。

## 从源码构建

唯一 Android 工程位于 [`source/tablet-live-wallpaper/`](source/tablet-live-wallpaper/)，可直接用 Android Studio 打开。

| 项目 | 配置 |
|---|---|
| 语言与 UI | Java 17、Android framework UI |
| 构建工具 | Gradle Wrapper 8.13、Android Gradle Plugin 8.11.2 |
| Android SDK | compile/target 35，min 26 |
| 本地验证环境 | JDK 21、Android 15 / API 35 `medium_tablet` 模拟器 |

准备好 JDK 和 Android SDK 后，在仓库根目录执行：

```sh
cd source/tablet-live-wallpaper
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，使用本机默认 debug key。发布用 release variant 需要另外使用仓库外的私钥签名，具体见[发布说明](docs/releasing.md)。

## 兼容性与验证范围

v1.1.0 已通过本地单元测试和 Lint，并在 API 35 模拟器完成设备测试，以及签名 release 包的安装、启动、横竖切换和默认图片轮播检查。API 26–27 尚未实际回归，Xiaomi/HyperOS 真机及锁屏独立行为未在本次发布中重新验收。

系统选择器、后台清理和低内存后的壁纸恢复可能因 ROM 而异。反馈问题时请附上机型、Android/ROM 版本与复现步骤，并移除日志和截图中的私人内容。

## 文档与参与

- [文档索引](docs/README.md)
- [项目概览与使用方式](docs/overview.md)
- [开发命令与代码结构](docs/development.md)
- [配置数据模型 v1](docs/wallpaper-configuration-model-v1.md)
- [Release 构建与签名](docs/releasing.md)

欢迎通过 [Issues](https://github.com/Lumine0708/my_wallpaper/issues) 反馈问题，或提交 Pull Request。修改前请阅读 [AGENTS.md](AGENTS.md) 及其中列出的开发和验证规则，保持改动与当前需求相关。

## 许可证与素材

项目代码采用 [MIT License](LICENSE)。

两张内置壁纸为标注美国公有领域的 NASA 历史照片，保留其原有素材地位；来源、作者及文件校验值见[内置图片说明](docs/bundled-images.md)。NASA 未参与或背书本应用。
