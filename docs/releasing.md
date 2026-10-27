# Release 构建与发布

版本来自 `source/tablet-live-wallpaper/version.properties`，标签使用 `v<versionName>`。APK、签名私钥、密码、本机 SDK 配置和构建缓存均不提交 Git。仓库可见性由维护者另行决定，发布命令不自动修改可见性。

## 构建

从仓库根目录进入 Android 工程，使用已安装的 SDK 和 JDK：

```sh
cd source/tablet-live-wallpaper
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

默认产物：

- debug：`app/build/outputs/apk/debug/app-debug.apk`，使用本机默认 debug key。
- release：`app/build/outputs/apk/release/app-release-unsigned.apk`，尚未签名。

设备测试使用 `medium_tablet`，执行 `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`。完整验证范围见 [AGENTS_DEV.md](../AGENTS_DEV.md)。

## 外置签名

维护者需在仓库外妥善保存发布 keystore 和密码，后续更新使用同一个发布 key。首次发布使用新 key，无法覆盖安装此前使用其他 key 签名的内部包。

以下示例在 Android 工程目录执行。`ANDROID_HOME` 指向本机 SDK，`RELEASE_OUTPUT` 指向仓库外的交付目录；`SIGNING_DIR` 下保存 `release.keystore` 和仅维护者可读的 `password` 文件。不要把这些文件放进源码仓库或 Release 附件。

```sh
SIGNING_DIR="$HOME/.local/share/my_wallpaper/signing"
RELEASE_OUTPUT="$HOME/.local/share/my_wallpaper/releases"
mkdir -p "$RELEASE_OUTPUT"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks "$SIGNING_DIR/release.keystore" \
  --ks-key-alias release \
  --ks-pass "file:$SIGNING_DIR/password" \
  --out "$RELEASE_OUTPUT/TabletLiveWallpaper-release.apk" \
  app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify \
  --verbose --print-certs "$RELEASE_OUTPUT/TabletLiveWallpaper-release.apk"
```

## Tag 与 GitHub Release

验证源码、APK 包名/版本、ZIP 完整性、非 debuggable 标志和签名后，为 APK 生成 SHA-256 校验文件。tag 必须指向产生该 APK 的源码提交；推送 tag 后用 `gh release create` 上传 APK 与校验文件。Release 说明应列出版本、签名变更、已完成验证和未验证的平台范围。

交付后删除仓库内的 `app/build/`、旧 `build/` 和项目 `.gradle/`，保留仓库外的签名资料。已有 tag 不覆盖，版本调整按 [分支规则](../AGENTS_BRANCHING.md) 执行。
