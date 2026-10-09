# Bootloader 整包刷写方案（待确认，尚未执行）

设备为 Pixel 6 Pro（`raven`），Bootloader 已解锁，当前槽位为 `b`。原系统设置向导要求旧 PIN 或 Google 账号，无法完成网页 GSI 流程所需的 USB 调试授权。

## 镜像组合

| 内容 | 来源 |
| --- | --- |
| `system.img`、`vbmeta.img` | `upstream.json` 中的 AOSP GSI `CP3A.260905.010` |
| Bootloader、基带、内核、vendor 及其余整机分区 | `firmware.json` 中的 Google Pixel 原厂包 `CP3A.261005.002.A1` |
| 刷写目录 | `prepared-gsi-CP3A.260905.010/`，通过符号链接引用以上原始文件 |

这是本地组合的刷写目录，不是 Google 发布的完整 AOSP 整机包。原始镜像未修改；尚未验证组合后的实际启动和硬件功能。

## 执行范围

1. 将 Bootloader 从 `slider-16.4-14548185` 更新到 `slider-17.0-15681480`。
2. 将基带从 `g5123b-145971-260109-B-14688209` 更新到 `g5123b-145971-260721-B-15910359`。
3. 用上述目录执行官方 fastboot `flashall`，目标槽位 `b`，清除用户数据、关闭验证、跳过副槽位并暂不自动重启。保持 Bootloader 解锁。
4. 检查写入结果，再启动系统并验证版本、ADB 和基本硬件功能。

本方案增加了底层固件升级，不能保证此后可以降回旧版。执行前需要确认该范围；失败时可能需要使用已保存的原厂包恢复。

## 已完成的检查

- 实机读取：`unlocked=yes`、`is-userspace=no`、`slot-count=2`、`snapshot-update-status=none`，Bootloader 通信正常。
- 官方 fastboot 支持将 `reboot fastboot`、`update-super` 和动态分区写入合并为直接写入 `super`，见 [官方实现说明](https://android.googlesource.com/platform/system/core/+/refs/heads/main/fastboot/README.md#flashing-logic)。
- 原厂 `fastboot-info.txt` 符合该优化模式，动态分区镜像均为非 sparse 格式。
- `super_empty.img` 的头部和表格校验和通过；其单一 `super` 设备、只读分区、空 extents 满足官方优化实现的条件。
- 实机 `super` 容量与镜像元数据一致，均为 `8,531,214,336` 字节。组合后的动态分区为 `7,538,573,312` 字节，低于分区组上限 `8,527,020,032` 字节。

以上为离线检查和只读设备查询。fastboot 没有提供本次刷写的 dry-run；尚不能把这些检查当作实刷成功的证明。
