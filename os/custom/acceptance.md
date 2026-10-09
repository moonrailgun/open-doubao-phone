# 豆馅 OS v1 开发镜像验收记录

验证日期：2026-10-10。目标为当前开发用 Pixel 6 Pro、已解锁 Bootloader、`b` 槽位；基础 GSI 为 `CP3A.260905.010`。此记录区分自动检查与尚需现场、长时间测量的项目，不代表已完成全部声学及耗电验收。

## 当前产物

本地输出目录：`os/custom/out/v1.3/`，未纳入 Git。保留原始镜像以及先前可启动的 `v1/`、`v1.1/`、`v1.2/` 输出。`manifest.json` 记录完整 payload、签名和容量数据。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `f7762bd2a59c5532899b97f02a77e55a8ebe1f3fdf802b1606e77d46eb3885ef` |
| `assistant.apk` | `40e150d96cfde7316dca6e7eedbb9b8951a8b9c4fd48985dc3813532457f11fd` |
| `tts.apk` | `7506d6c265ae3252501d13271734c07c763f4f49c50c6a22d94165c3c0dfe6bd` |
| `bootanimation.zip` | `ab7130e8d416016c77eb9561d589fa6413941b0784a8a479813f865fe284d31d` |
| `voice-corresponding-source.tar.gz` | `f31fbdb703286d8dc677340ddfec7f4c952dbd7e692a67251de593ecf90c3b8c` |

`system.img` 为 2,572,275,712 字节；动态分区合计 8,038,834,176 字节，低于 group 上限 8,527,020,032 字节，余量 488,185,856 字节。两份系统 APK 版本号均为 1001，证书与先前开发 APK 相同：`f73c285679b81382e5bdd814fdfab042620f86824798f7ac949e799d62e63578`。

## 已取得的证据

- Debug/System APK 构建、两种变体的 Android Lint 和单元测试通过；最终日志为 `android/.voice/gradle-toggle-final.log`。普通 APK 保留普通应用行为，只有 system 助手变体声明 `persistent`。
- 小型 ext4 测试覆盖真实共享块展开、文件内容、权限与 SELinux 标签保留、缩容及 AVB 校验；完整镜像构建也完成文件系统检查、读回 SHA-256、容量和 AVB 验证，见 `v1.3/build.log`、`avb-info.txt`。
- 不清数据刷写日志为 `v1.3/flash.log`，未执行 `-w`、卸载、userdata/metadata 擦除或重新锁定 Bootloader。
- 本地语音真机测试在首版系统镜像上通过：真实 SenseVoice 推理、三个关键词的合成语音输入及独立 TTS 合成/播放，见 `v1/system-voice-test.log`。合成音频命中不等同于真人一米声学验收。
- 白底 Logo 动画归档检查通过，且在 `v1.1/boot/000.png` 中确认真机启动画面；当前镜像使用同一动画归档。Bootloader 解锁提示保持原样。
- 首次不清数据升级已通过 UID、语音设置和原有 Keystore 密钥解密检查，见 `v1/upgrade-test.log`。测试前实际配置文件不存在，因此只证明其不存在状态保留，并用同一 Keystore alias 加密的独立测试配置验证密钥可用性；不能据此声称已验证真实账户凭据。
- 当前镜像最终重启后约 23.6 秒完成启动并取得未被静音的后台录音，全程未打开助手 Activity，见 `v1.3/final-reboot.log`、`final-reboot-audio.txt`。默认助手角色、VIS 绑定、麦克风 App-op、前台服务及系统省电豁免均已确认。
- 主动制造助手进程崩溃后，PID 从 2686 变为 3309，2.5 秒内恢复未被静音的录音，见 `v1.3/wake-recovery.log`。修复同时覆盖系统先恢复前台服务、随后才通知 VIS 就绪的顺序；不会重放任务。
- 真机生命周期测试通过播报后恢复监听、真实麦克风静音/恢复、清除最近任务及连续三次开关监听，见 `v1.3/recent-task-test.log`。补充测试使用独立 UID 的测试应用占用隐私敏感麦克风十秒，助手正确进入“麦克风被系统暂停”状态，竞争结束后恢复监听，见 `v1.3/competition-test.log`；测试不读取或保存音频。新增测试 APK 构建及 Debug/System Lint 通过，见 `android/.voice/gradle-competition-test.log`、`gradle-competition-lint.log`。最终镜像再次通过升级数据与 Keystore 检查，见 `v1.3/upgrade-test.log`。
- 设备无活动网络时，通过真实 CloudModels / HttpTransport 发起任务，失败状态返回“规划请求失败：域名解析失败，请检查域名与网络（UnknownHostException）”，且未执行手机操作，见 `v1.3/offline-task-test.log`。测试使用内存临时配置，不覆盖实际凭据；页面渲染该状态消息的路径已检查，但本项未自动验证页面显示。
- 补充测试后，重启脚本在语音模型尚未初始化完成时重启，触发预期的“上次语音初始化中断”保护；通过手机设置中的重试入口恢复，待监听就绪后再次重启，约 26.7 秒取得未被静音的后台录音，全程未打开助手 Activity。见 `v1.3/initialization-retry.log`、`followup-reboot.log`、`followup-reboot-audio.txt`。
- 当前 GSI 默认关闭 deep idle；测试临时开启并强制进入 IDLE，息屏五秒后录音仍活动且未被静音，见 `v1.3/idle-check.log`、`idle-audio.txt`。已恢复原有 deep idle 设置和真实电池状态；这只证明录音未中断，不代表自然待机或真人唤醒已通过。
- 设备上两份 APK 和开机动画的 SHA-256 与当前 manifest 完全一致；文件为 root:root/0644，SELinux 标签为 `system_file`。助手 UID 为 10124，TTS UID 为 10125；两者均为 SYSTEM，仅助手为 PERSISTENT，见 `v1.3/device-checks.txt`。
- 最终启动日志未检出匹配助手包、Douxian 或 system_app 的 AVC 拒绝，也未检出助手进程的 FATAL EXCEPTION；这是所捕获日志的检查结果，不代表所有系统组件均无错误，见 `v1.3/final-boot-logcat.txt`。
- 对应源码包包含 18,156 个条目，已核对最终语音服务、生命周期与断网测试、独立录音测试 Activity、测试 Manifest 及崩溃恢复脚本与当前源码一致，不含签名密钥或 `local.properties`。

## 尚未完成的验收

- 真人在安静环境一米距离对三个唤醒词各说 20 次，分别记录亮屏、锁屏及深度待机结果；目标每词至少 18/20。记录唤醒、识别和首次播报延迟。
- 八小时混合环境音误唤醒计数，以及监听开启/关闭各八小时的息屏耗电对比。
- 真实电话通话、有 PIN 的开机首次解锁流程，以及无网络任务错误的页面显示。代码未启用 Direct Boot；当前开发手机没有 PIN，不能替代该场景验证。
- 当前 GSI 的 `config_supportsMicToggle` 为 false，系统麦克风隐私开关命令不生效。已用真实系统麦克风静音状态验证暂停与恢复，但不能将其记为隐私开关通过。
- 未生成 MiniMax H3 视频：本次环境无可用的视频生成凭据。当前为现有 Logo 的白底淡入与轻微透明度循环。

这是供当前开发设备使用的无签名 AVB 镜像，必须保持 Bootloader 解锁。CPU 持续监听的实际耗电仍需上述长时间测量。
