# Pixel 6 Pro 系统开发

目标设备为 Pixel 6 Pro（`raven`），ARM64。开发镜像保持 Bootloader 解锁。

## 官方基线与自定义改动

- `upstream.json` 固定官方镜像的下载地址、版本与 SHA-256，纳入 Git。
- `aosp-CP3A.260905.010/` 保存校验后的官方原始文件，不直接修改；ZIP、解压镜像和 SDK 工具由本目录的 `.gitignore` 排除。
- 后续自定义源码、补丁、产品配置放在 `os/` 内单独的源码目录，按功能独立提交。升级官方基线时单独更新清单，避免与功能修改混合。
- 当前没有对 AOSP 做任何自定义修改，也没有导入完整 AOSP 源码。镜像校验只能证明二进制基线一致，不能代替源码 diff。

## AOSP 系统镜像

Android 17 QPR1 AOSP GSI，无 Google 应用，构建 `CP3A.260905.010` / `16176746`。

- [官方发布说明与校验值](https://developer.android.com/topic/generic-system-image/releases#android-17-qpr1)
- [官方镜像下载](https://dl.google.com/developers/android/cinnamonbun/images/gsi/aosp_arm64-exp-CP3A.260905.010-16176746-4a869a69.zip)
- ZIP SHA-256：`4a869a6959a50fee15ff871ce378853ced4485f2aa456eb64dbdbf7a2c0408aa`，已核对一致。
- 解压文件：`system.img`、`vbmeta.img`、`build.prop`，各文件校验值见 `upstream.json`。

GSI 替换 Android 系统分区，使用设备现有的内核与 vendor 固件。它用于兼容性和应用开发测试，不是整机出厂包。包内 `build.prop` 表明这是 `user` 构建、`ro.debuggable=0`，不提供 `userdebug` 的 `adb root`；当前也尚未集成 AI 助手。

## 恢复镜像

Google 原厂 Pixel 6 Pro Android 17：`CP3A.261005.002.A1`。

- [官方出厂镜像](https://dl.google.com/dl/android/aosp/raven-cp3a.261005.002.a1-factory-2b76c8d1.zip)
- [恢复步骤与防回滚说明](https://developers.google.com/android/images)

该包用于恢复 Google 原厂 Android，与 AOSP GSI 分开保存。已下载完成，ZIP 全部条目 CRC 检查通过。本地 SHA-256 为 `2b76c8d13964ade5f55600dcae65bfeefd1c8fe27669e38fb7bbbd1c7f37990b`；尚未与官方公布的完整 SHA-256 比对。

## 工具与当前状态

- `tools/platform-tools/`：官方 macOS SDK Platform Tools 37.0.1。
- [官方 GSI 刷写步骤](https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis)。
- Bootloader 已解锁。AOSP 包已下载、校验并刷入槽位 `b`；Bootloader 和基带已升级到 `firmware.json` 对应的 Android 17 版本。
- Bootloader 模式通信正常；fastbootd 模式出现 `usb_write failed with status e00002ed`。更新 Platform Tools、切换 arm64/x86_64 和暂时停止 ADB 服务均未解决，尚不能确定是线缆、Mac USB 通信还是手机 fastbootd 的问题。
- 曾尝试 [官方 Flash Tool 的同一 AOSP 构建](https://flash.android.com/build/16176746?target=aosp_arm64-user&signed=true&wipe=true)。其 GSI 流程要求正常系统中的 ADB 授权，而原系统设置向导被旧 PIN 或 Google 账号验证阻挡；ADB 虽能列出设备，执行 `shell getprop` 仍返回 `error: closed`。
- 已按 [Bootloader 整包刷写方案与记录](flash-plan.md) 完成刷写：原厂固件与 AOSP GSI 组合，使用本地 `fastboot-info.txt` 排除副槽位 system 指令，通过 Bootloader 直接写入 `super`。官方原始文件保持不变。
- 修正后的 29 个 `super` 数据块全部写入成功；重启 Bootloader 后，五个动态分区的大小均与镜像相同。用户数据已清除，已重启系统，尚待确认桌面、版本、ADB 和硬件功能。

刷写会清空用户数据。不要对开发镜像执行 `fastboot flashing lock`，也不要在未核对防回滚限制时刷入旧版 Bootloader。
