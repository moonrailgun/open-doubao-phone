# Bootloader 整包刷写方案与记录

设备为 Pixel 6 Pro（`raven`），Bootloader 已解锁，当前槽位为 `b`。刷写前，原系统设置向导要求旧 PIN 或 Google 账号，无法完成网页 GSI 流程所需的 USB 调试授权。

## 镜像组合

| 内容 | 来源 |
| --- | --- |
| `system.img`、`vbmeta.img` | `upstream.json` 中的 AOSP GSI `CP3A.260905.010` |
| Bootloader、基带、内核、vendor 及其余整机分区 | `firmware.json` 中的 Google Pixel 原厂包 `CP3A.261005.002.A1` |
| 刷写目录 | `prepared-gsi-CP3A.260905.010/`，通过符号链接引用以上原始文件 |
| 刷写清单 | 本目录的 `fastboot-info.txt`；刷写目录中的同名链接指向 `../fastboot-info.txt` |

这是本地组合的刷写目录，不是 Google 发布的完整 AOSP 整机包。原始镜像未修改；实际启动和 ADB 已验证，硬件功能尚未测试。

## 副槽位处理

本地清单仅从原厂清单删除 `flash --slot-other system system_other.img`，其余指令保持一致。SDK Platform Tools 37.0.1 首次执行时，虽然设置了 `--skip-secondary`，该指令仍导致优化器将 `system_b` 从 `2,072,014,848` 缩小到 `143,958,016` 字节。该次写入后没有启动系统。

不能仅依赖 `--skip-secondary` 跳过该清单指令；删除后重新刷写，并在重启 Bootloader 刷新分区信息后核对 `system_b` 大小。

## 执行范围

1. 将 Bootloader 从 `slider-16.4-14548185` 更新到 `slider-17.0-15681480`。
2. 将基带从 `g5123b-145971-260109-B-14688209` 更新到 `g5123b-145971-260721-B-15910359`。
3. 用上述目录执行官方 fastboot `flashall`，目标槽位 `b`，清除用户数据、关闭验证、跳过副槽位并暂不自动重启。保持 Bootloader 解锁。
4. 检查写入结果，再启动系统并验证版本、ADB 和基本硬件功能。

本方案增加了底层固件升级，不能保证此后可以降回旧版。用户已明确同意升级底层固件并刷入 AOSP；失败时可能需要使用已保存的原厂包恢复。

## 已完成的检查

- 实机读取：`unlocked=yes`、`is-userspace=no`、`slot-count=2`、`snapshot-update-status=none`，Bootloader 通信正常。
- 官方 fastboot 支持将 `reboot fastboot`、`update-super` 和动态分区写入合并为直接写入 `super`，见 [官方实现说明](https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/README.md#flashing-logic)。
- 原厂 `fastboot-info.txt` 符合该优化模式，动态分区镜像均为非 sparse 格式。
- `super_empty.img` 的头部和表格校验和通过；其单一 `super` 设备、只读分区、空 extents 满足官方优化实现的条件。
- 实机 `super` 容量与镜像元数据一致，均为 `8,531,214,336` 字节。组合后的动态分区为 `7,538,573,312` 字节，低于分区组上限 `8,527,020,032` 字节。

以上检查在刷写前完成，fastboot 没有提供本次刷写的 dry-run。

## 实刷结果（2026-10-09）

- Bootloader 和基带均写入成功，重启 Bootloader 后读取到上述目标版本。
- 使用修正后的本地清单执行以下命令（在项目根目录运行）：

  ```sh
  ANDROID_PRODUCT_OUT="$PWD/os/prepared-gsi-CP3A.260905.010" \
    os/tools/platform-tools/fastboot -s YOUR_DEVICE_SERIAL \
    --slot=b --skip-secondary --skip-reboot --disable-verification -w flashall
  ```

- fastboot 将动态分区合并为 29 个 sparse 数据块直接写入 `super`，全部返回 `OKAY`，命令以状态 `0` 结束，用时约 198 秒。
- 用户数据与 metadata 已清除。设备报告文件系统类型为 `raw`，fastboot 未在主机端格式化；后续 Recovery 初始化与实机验证见下文。
- 再次重启 Bootloader 后，实机读取的分区大小与本地镜像逐一相同：

  | 分区 | 字节数 |
  | --- | ---: |
  | `system_b` | 2,072,014,848 |
  | `system_ext_b` | 443,674,624 |
  | `product_b` | 4,315,308,032 |
  | `vendor_b` | 651,722,752 |
  | `vendor_dlkm_b` | 55,853,056 |

- 保持 `unlocked=yes`、目标槽位 `b`。未进行整分区二进制回读校验。

## 首次启动与 Recovery 初始化

首次启动出现黑屏，但 Fastboot 和 Recovery 均能进入。Recovery 中 ADB 可连接，普通 shell 无权读取恢复日志，未取得能直接解释黑屏的启动日志。

在此前已同意清空数据的范围内，用户从 Recovery 执行 `Wipe data/factory reset` → `Factory data reset`，完成后选择 `Reboot system now`，随后确认已进入桌面或初始设置。该操作后的结果表明 Recovery 初始化解决了本次启动阻塞；不能仅凭此确定具体失败原因。

若进入 Recovery 后显示机器人或 `No command`，按住电源键，短按音量上键，然后同时松开即可打开菜单，见 [Google 恢复出厂设置说明](https://support.google.com/pixelphone/answer/16493042?hl=zh-Hans)。初始化会清空用户数据。

## 实机启动验证

用户开启 USB 调试并授权电脑后，已从这台 Pixel 6 Pro 读取并确认：

- ADB 状态为 `device`；`sys.boot_completed=1`。
- 系统指纹为 `Android/generic_system/generic:17/CP3A.260905.010/16176746:user/release-keys`，Android 17 / SDK 37。
- vendor 指纹为 `google/raven/raven:17/CP3A.261005.002.A1/16269273:user/release-keys`，设备为 `raven`。
- 槽位为 `_b`，`ro.boot.flash.locked=0`，`ro.treble.enabled=true`。
- `/data` 可用，默认桌面为 `com.android.launcher3/.uioverrides.QuickstepLauncher`。
- 再执行一次 `adb reboot` 后，ADB 自动重连，读取到 `sys.boot_completed=1`，当时系统 uptime 为 `8.78` 秒，USB 调试授权已保留。

该 GSI 为 `user` 构建，`ro.debuggable=0`，不提供 `adb root`。尚未测试相机、通话、Wi-Fi、蓝牙等硬件功能，也尚未集成 AI 助手。
