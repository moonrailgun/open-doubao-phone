# 豆馅 OS v1 开发镜像验收记录

验证日期：2026-10-10。目标为当前开发用 Pixel 6 Pro、已解锁 Bootloader、`b` 槽位；基础 GSI 为 `CP3A.260905.010`。此记录区分自动检查与尚需现场、长时间测量的项目，不代表已完成全部声学及耗电验收。

## 当前产物

当前已刷入 v1.28（设置里显示我们自己的版本号；v1.27 为同一改动的第一版，版本号只写进了 system 分区而被 product 覆盖，见下文；v1.26 为执行方式判断与规划并行、Jev 完成概率入日志、桌面端分块下载模型调用记录；v1.25 为悬浮球处理中动效、首屏加载挪后台、调试页，并首次包含另一会话的内置回复音频与模型调用记录；v1.24 为从已提交源码干净重建，含识别模型预加载与提示音播放完成判定修正；v1.23 的 APK 曾混入另一会话的未提交代码，见其条目；v1.22 为唤醒提示音播放修复，v1.21 为执行方式自动判断与 App 内虚拟屏画面，v1.20 为语音卡片字幕稳定性，v1.19 为语音卡片实时反馈，v1.18 为虚拟屏执行真机验收通过，均见下文；v1.16 为长期记忆与唤醒语音卡片，v1.15 与 v1.17 为虚拟屏的中间版本，均见下文；v1.14 为 v1.13 换上唤醒离线提示音与默认应用范围修复的助手，以及带新图标的 TTS；v1.13 为 v1.12 加豆包输入法和设备型号 Open Doubao；v1.12 起包名改为 `com.moonrailgun.opendoubao.*`），v1.11 及其以前的记录仍使用旧包名 `com.openodoubao.*`；以下“当前产物”表仍为 v1.9 数据，v1.11 摘要见其候选镜像记录。v1.8 的布局修正随 v1.9 一并交付，未单独刷写。

本地输出目录：`os/custom/out/v1.9/`，未纳入 Git。保留原始镜像和先前版本输出。`manifest.json` 记录完整 payload、签名和容量数据。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `516c35754d6853dd1e2f47db5445898456a18027abd4ecfdedd5d0461ceaeb07` |
| `assistant.apk` | `2d45ba3e5f1adb444673de1f05963d3a67de884f5f6d5412d3410a64ac607322` |
| `tts.apk` | `7506d6c265ae3252501d13271734c07c763f4f49c50c6a22d94165c3c0dfe6bd` |
| `bootanimation.zip` | `ab7130e8d416016c77eb9561d589fa6413941b0784a8a479813f865fe284d31d` |
| `voice-corresponding-source.tar.gz` | `8cd6c299983df3084c07a33a4e9575da9b4ca7b330311f9cc198d2c3d870874c` |

`system.img` 为 2,572,324,864 字节；动态分区合计 8,038,883,328 字节，低于 group 上限 8,527,020,032 字节，余量 488,136,704 字节。两份系统 APK 版本号均为 1001，证书与先前开发 APK 相同：`f73c285679b81382e5bdd814fdfab042620f86824798f7ac949e799d62e63578`。手机实际运行的 TTS 是数据分区中的更新包，不能将预装 TTS 摘要当作运行版本。

## 已取得的证据

- Debug/System APK 构建、两种变体的 Android Lint 和单元测试通过；本次日志为 `v1.5/gradle-wake-reply-final.log`。普通 APK 保留普通应用行为，只有 system 助手变体声明 `persistent`。
- 小型 ext4 测试覆盖真实共享块展开、文件内容、权限与 SELinux 标签保留、缩容及 AVB 校验；完整镜像构建也完成文件系统检查、读回 SHA-256、容量和 AVB 验证，见 `v1.5/build.log`、`avb-info.txt`。
- 本地语音真机测试在首版系统镜像上通过：真实 SenseVoice 推理、三个关键词的合成语音输入及独立 TTS 合成/播放，见 `v1/system-voice-test.log`。合成音频命中不等同于真人一米声学验收。
- 白底 Logo 动画归档检查通过，且在 `v1.1/boot/000.png` 中确认真机启动画面；当前镜像使用同一动画归档。Bootloader 解锁提示保持原样。
- 首次不清数据升级已通过 UID、语音设置和原有 Keystore 密钥解密检查，见 `v1/upgrade-test.log`。测试前实际配置文件不存在，因此只证明其不存在状态保留，并用同一 Keystore alias 加密的独立测试配置验证密钥可用性；不能据此声称已验证真实账户凭据。
- v1.3 镜像重启后约 23.6 秒完成启动并取得未被静音的后台录音，全程未打开助手 Activity，见 `v1.3/final-reboot.log`、`final-reboot-audio.txt`。默认助手角色、VIS 绑定、麦克风 App-op、前台服务及系统省电豁免均已确认。
- 主动制造助手进程崩溃后，PID 从 2686 变为 3309，2.5 秒内恢复未被静音的录音，见 `v1.3/wake-recovery.log`。修复同时覆盖系统先恢复前台服务、随后才通知 VIS 就绪的顺序；不会重放任务。
- 真机生命周期测试通过播报后恢复监听、真实麦克风静音/恢复、清除最近任务及连续三次开关监听，见 `v1.3/recent-task-test.log`。补充测试使用独立 UID 的测试应用占用隐私敏感麦克风十秒，助手正确进入“麦克风被系统暂停”状态，竞争结束后恢复监听，见 `v1.3/competition-test.log`；测试不读取或保存音频。新增测试 APK 构建及 Debug/System Lint 通过，见 `android/.voice/gradle-competition-test.log`、`gradle-competition-lint.log`。v1.3 镜像再次通过升级数据与 Keystore 检查，见 `v1.3/upgrade-test.log`。
- 设备无活动网络时，通过真实 CloudModels / HttpTransport 发起任务，失败状态返回“规划请求失败：域名解析失败，请检查域名与网络（UnknownHostException）”，且未执行手机操作，见 `v1.3/offline-task-test.log`。v1.4 补测实际 AssistantService 与 MainActivity，在页面视图树中确认同一错误可见，两项测试均通过（3.107 秒），见 `v1.4/offline-ui-test.log`。页面测试临时使用测试配置，结束后恢复并验证原有配置密文与设置；测试 APK 构建、Debug/System Lint 通过，见 `android/.voice/gradle-offline-ui.log`。
- 补充测试后，重启脚本在语音模型尚未初始化完成时重启，触发预期的“上次语音初始化中断”保护；通过手机设置中的重试入口恢复，待监听就绪后再次重启，约 26.7 秒取得未被静音的后台录音，全程未打开助手 Activity。见 `v1.3/initialization-retry.log`、`followup-reboot.log`、`followup-reboot-audio.txt`。
- 当前 GSI 默认关闭 deep idle；测试临时开启并强制进入 IDLE，息屏五秒后录音仍活动且未被静音，见 `v1.3/idle-check.log`、`idle-audio.txt`。已恢复原有 deep idle 设置和真实电池状态；这只证明录音未中断，不代表自然待机或真人唤醒已通过。
- v1.3 设备上两份 APK 和开机动画的 SHA-256 与对应 manifest 完全一致；文件为 root:root/0644，SELinux 标签为 `system_file`。助手 UID 为 10124，TTS UID 为 10125；两者均为 SYSTEM，仅助手为 PERSISTENT，见 `v1.3/device-checks.txt`。
- 最终启动日志未检出匹配助手包、Douxian 或 system_app 的 AVC 拒绝，也未检出助手进程的 FATAL EXCEPTION；这是所捕获日志的检查结果，不代表所有系统组件均无错误，见 `v1.3/final-boot-logcat.txt`。
- v1.4 启动日志共 13,235 行，未检出助手、Douxian 或 system_app 相关的 AVC 拒绝。另有 74 条 `rild` 域的 AVC 拒绝及一条 `init` 子进程 SIGABRT；同类现象在 v1 至 v1.3 的捕获日志中已存在，早于本次远程配置改动。`rild` 拒绝附近有 `telephony.ril.silent_reset` 属性读取失败，但缺少原始 GSI 对照和可读崩溃堆栈，根因仍未确定，不能判定系统整体无错误；见 `v1.4/final-boot-logcat.txt`、`boot-log-audit.txt`、`boot-log-followup.txt`。
- 对应源码包包含 18,156 个条目；已核对本次唤醒词与播报接续涉及的九个源码文件和当前源码一致，见 `v1.5/source-check.txt`，不含签名密钥或 `local.properties`。
- v1.4 不清数据刷写完成，见 `v1.4/flash.log`。升级测试再次通过，见 `v1.4/upgrade-test.log`：原 UID、实际配置状态、监听设置和 Keystore 测试密钥保留。旧基线包含此前“重试识别引擎”已清除的两个 false 初始化标记；测试现在按运行时代码的默认值，将缺省与 false 视为相同，不忽略 true 或用户设置。测试 APK 构建及两种变体 Lint 通过，见 `android/.voice/gradle-upgrade-final.log`。
- v1.4 最终重启检查约 24.8 秒完成启动并取得未被静音的后台录音，全程未打开助手 Activity。设备上的两份 APK 和开机动画 SHA-256 与本版 manifest 一致；助手 UID、系统持久标记、默认助手角色、前台录音和省电豁免均通过，见 `v1.4/final-reboot.log`、`final-reboot-audio.txt`。TTS UID 为 10125，仅助手有 PERSISTENT 标记；三份预装文件权限与 SELinux 标签保持正确，见 `v1.4/assistant-package.txt`、`tts-package.txt`、`device-files.txt`。
- 离线页面补测后重新绑定默认助手，待监听就绪再重启，23.9 秒取得未被静音的后台录音，全程未打开 Activity；payload 摘要与系统持久标记等检查再次通过，见 `v1.4/offline-followup-reboot.log`、`offline-followup-final-reboot-audio.txt`。

## 唤醒回应

v1.5 新增“同志你好”，保留原有三个唤醒词。解锁状态下，唤醒话语结束后回复“我在”；只说唤醒词时，播报结束再收听任务，最长等待 20 秒。同一句中连着说出的任务保留到回应后执行。播报期间关闭录音，取消回应也取消后续收音。锁屏仍提示先解锁。

模拟器三个本地语音测试通过（22.078 秒），覆盖四词分别合成后的 KWS 命中、真实 ASR/TTS、回应后收音与取消衔接，见 `v1.5/wake-reply-emulator-final.log`。

最终测试适配 Debug/System 变体的方法名，并隔离测试前残留的语音缓存与服务退出过程。真机两项测试通过（30.682 秒）：四词分别使用合成音频命中 KWS，实际播完“我在”后进入任务录音并持续等待至少两秒，取消回应后不再开启任务录音；见 `v1.5/voice-test-final.log`。同一回应测试在模拟器再次通过（11.946 秒），见 `v1.5/wake-reply-variant-emulator.log`；最终测试 APK 构建与 Debug Lint 通过，见 `v1.5/gradle-wake-reply-variant-test.log`。这些结果不替代真人声学测试。

v1.5 已完成不清数据刷写，见 `v1.5/flash.log`。测试后恢复默认助手绑定并开启监听，最终重启在 23.7 秒取得未被静音的后台录音，全程未打开 Activity；设备上的两份 APK、开机动画摘要均与本版 manifest 一致，助手 UID、系统持久标记、默认助手角色、前台录音及省电豁免检查通过，见 `v1.5/final-reboot.log`、`final-reboot-audio.txt`。三份预装文件仍为 root:root/0644，SELinux 标签为 `system_file`，见 `v1.5/device-files.txt`。

## 分段语音播放

v1.6 在独立 TTS 引擎返回 PCM 音频时立即交给助手 UID 的 `AudioTrack` 播放，不再等待完整 WAV 合成。保留原有音色、INT8 模型和两线程配置；全部音频实际播放结束后才恢复任务收音，取消时释放播放器并清理临时文件。

Pixel 6 Pro 上用同一模型、音色和测试长句直接测量合成回调：

| CPU 线程 | 首段音频 | 完整合成 |
|---|---|---|
| 1 | 2.297 秒 | 12.169 秒 |
| 2 | 1.807 秒 | 9.772 秒 |
| 4 | 1.881 秒 | 10.325 秒 |

测试长句为“我在！你好呀，我是豆馅。今天也一起加油吧！有什么想做的，告诉我就好。”，基准程序为 `android/.voice/tts-bench/TtsBench.java`。上述时间不含模型加载、服务连接和播放缓冲；两线程模型加载另需约 1.921 秒。这是一次设备测量，优化首次开口等待，不代表总合成耗时下降；生成速度跟不上播放时仍可能出现句间停顿。

Debug/System 构建、Lint 和各 104 项单元测试通过。模拟器四项本地语音测试通过（62.461 秒），覆盖真实 ASR/TTS、提前播放、取消和回应后收音。最终测试兼容性调整后，提前播放测试再次通过（12.297 秒），见 `v1.6/streaming-emulator-retry.log`；此前一次运行被测试环境遗留的 TTS 初始化中断标记阻止，重置该测试状态后重跑，失败日志保留在 `streaming-emulator-test.log`。

完整镜像通过文件系统、容量、读回文件摘要及 AVB 检查，见 `v1.6/build.log`、`avb-info.txt`。对应源码包包含 18,156 个条目，本次播放实现和测试与工作区源码逐字节一致，见 `v1.6/source-check.txt`。镜像沿用 v1.5 的开机动画归档。

真机首次运行没有收到 TTS 音频，两项测试超时；通过现有引擎设置的“重试初始化”入口恢复后，同两项测试通过（14.964 秒），见 `v1.6/streaming-device-test.log`、`streaming-device-retry.log`。检查点包括完整 WAV 的 RIFF 头尚未写入时已有音频数据与活动的助手播放器、取消后停止播放并删除缓存，以及回应播完后继续收音。本次未修改 TTS 初始化中断保护。

v1.6 已完成不清数据刷写，见 `v1.6/flash.log`、`partition-check.txt`。测试后恢复默认助手绑定，确认监听就绪再重启，25.6 秒取得未被静音的后台录音，全程未打开 Activity；两份预装 APK 与开机动画摘要均与 manifest 一致，助手 UID、系统持久标记、默认助手角色、前台录音及省电豁免检查通过，见 `v1.6/final-reboot.log`、`final-reboot-audio.txt`。

## 系统桌面与启动默认项

v1.7 将助手放入系统特权应用目录，仅增加 `WRITE_SECURE_SETTINGS` 权限白名单。系统版进程启动时自动启用自己的无障碍服务，保留其他已启用服务，并避免短名称与完整名称重复；现有无障碍服务负责创建悬浮窗，锁屏隐藏规则保持不变。普通 APK 不启用这些系统默认行为。

新桌面采用 Launcher3 的 partner 默认布局，在 5×5 网格的首页第 3 行、第 3 列放置助手，保留原有 Dock 与图库图标。真机临时用户的全新桌面已验证 `screen=0 cell(2,2)` 及其余五个快捷方式，见 `v1.7/fresh-layout-check.log`、`fresh-user-launcher.txt`、`fresh-user-home.png`；验证后已删除该临时用户。已有用户布局不被默认模板覆盖；当前手机通过拖放补入居中图标，确认原有五个图标未移动，见 `owner-layout-check.log`、`owner-launcher-after.txt`。

Debug/System 构建、Lint 和单元测试通过，镜像构建通过文件系统、容量、读回摘要和 AVB 检查，见 `v1.7/gradle-accessibility.log`、`gradle-launcher.log`、`build.log`。真机启动设置测试通过，覆盖保留另一已安装的无障碍服务、重复启动去重及实际权限授予，见 `defaults-device-retry.log`；原测试使用的虚构服务会被系统清理，已改为真实已安装服务。测试 APK 最终重新构建通过，见 `gradle-test-final.log`。

v1.7 已完成不清数据刷写，见 `flash.log`、`partition-check.txt`。测试前主动删除助手的无障碍启用项并将总开关设为 0，重启后自动恢复为已启用且已绑定，悬浮窗显示，首页居中图标保留，全程未打开助手 Activity。后台监听在 23.8 秒就绪，设备上两份 APK 与开机动画摘要和 manifest 一致，UID、系统持久标记、默认助手角色、前台录音与省电豁免检查通过；见 `accessibility-before-reboot.txt`、`final-reboot.log`、`final-accessibility.txt`、`final-windows.txt`、`final-defaults-check.log`、`final-home.png`。

本次升级数据比较未通过：此前基线记录实际配置不存在，而手机随后已经保存模型配置、配对信息和任务记录，该基线已过时。保留严格比较逻辑，当前配置仍可解密，但本次缺少刷写前的最新配置摘要，不能声称完成了配置逐字节保留验证。对应源码包已更新，共 18,166 个条目，包含的 151 个已跟踪 Android 文件与镜像对应的源码提交 `8d27c94` 逐字节一致，见 `source-check.txt`；生产 APK 与镜像摘要不变。

## v1.8 布局修正：底部 Dock 居中

按用户澄清，将助手从首页网格中央移到五格底部 Dock 的第三格：`container=-101 screen=2 cell(2,0)`，保留其他默认快捷方式。修改已提交为 `4e2c3cc`。默认模板只用于新建桌面；当前机主的图标已单独移入 Dock，原有五个快捷方式保持不变，见 `v1.8/owner-layout-check.log`、`owner-home-docked.png`。

构建使用隔离的已提交源码，仅加入该布局修正，未混入工作区同时进行的语音改动。Debug/System APK 构建、System Lint 和单元测试通过，见 `v1.8/gradle.log`；完整镜像通过文件系统、容量、读回摘要与 AVB 检查，见 `build.log`、`avb-info.txt`。再次核对产物 SHA-256，并从 APK 中解码布局，确认助手在 Dock 第三格，见 `artifact-check.txt`、`packaged-launcher-layout.txt`。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `c5a8d41f1ffe3bdbc7c8f1e38000447b63b45121e15f000238b5dd38a81285b1` |
| `assistant.apk` | `dc8ef282a161b5a34a6cd420974eaade1352b35e5a8b022adb8e0647a52be563` |
| `voice-corresponding-source.tar.gz` | `56a44c64d044c10cb35b8e6c9d62ec0e046cb96f4c32d73de5fa2b7df745760f` |

TTS 与开机动画沿用 v1.7，摘要见“当前产物”。镜像为 2,572,296,192 字节，动态分区合计 8,038,854,656 字节，低于 8,527,020,032 字节上限。源码包共 18,166 个条目，其中 151 个已跟踪 Android 文件与 `4e2c3cc` 一致，见 `source-check.txt`。

v1.8 未单独刷写，其布局修正随 v1.9 完成真机验证。机主原有六个图标的位置在升级前后完全一致，见 `v1.9/owner-layout-check.log`。临时新用户 11 的 Launcher 记录确认六个快捷方式均属于该用户，助手位于 Dock 第三格，见 `fresh-user-launcher.txt`、`fresh-layout-check.log`；检查后已切回机主并删除该临时用户，见 `fresh-user-cleanup.txt`。

## v1.9 TTS 语音设置

设置页增加独立 TTS 区域，可配置完整 HTTPS 接口、Token、模型和音色 ID，并保存或保存后试听。默认模型为 `elevenlabs/eleven_v3`；清空 Token 使用本地播报，在线请求出声前失败时回退到本地。设置使用现有 Keystore 加密存储，旧配置缺少模型、音色字段时使用默认值。当前在线客户端按 24 kHz 单声道 PCM 流接收音频，未验证任意服务商兼容性。

Debug/System 各 108 项单元测试通过，System Lint 无错误，Debug/System APK 和测试 APK 构建通过，见 `v1.9/gradle.log`、`gradle-system-tests.log`。请求测试覆盖自定义模型和音色、拒绝空值；真机加密配置测试覆盖新增字段。镜像与对应源码包保留原有预装 TTS 和开机动画，仅更新设置及所依赖的助手在线播报实现，见 `source-check.txt`、`artifact-check.txt`。

不清数据刷写前重新记录了当前实际配置基线；刷写后 UID、实际配置密文、语音设置和 Keystore 解密验证通过，见 `upgrade-prepare.log`、`upgrade-verify.log`。该结果补齐了 v1.7 使用过时基线留下的验证缺口。

真机验证通过加密保存、在线部分播放中断不重复回退，以及在线请求失败后的本地合成播放。首次回退测试受实际 TTS 更新包的初始化中断状态阻止并超时；通过该引擎现有“重试初始化”入口恢复后，同一测试在 4.626 秒通过，见 `tts-device-tests.log`、`tts-device-retry.log`。本次没有修改或替换该更新包，也没有修改初始化保护逻辑。

通过手机设置页“保存并试听”实际保存配置并播放了本地语音，之后状态回到空闲，见 `tts-settings-saved-ui.xml`、`tts-settings-finished-ui.xml`、`tts-preview-logcat.txt`。接口和 Token 当前为空，没有调用付费在线接口。设置页启用防截屏保护，因此界面检查使用视图树；未将黑屏截图作为视觉验收证据。

最终重启约 26.7 秒恢复未被静音的助手录音，全程未打开助手 Activity。默认助手实际连接、前台服务、省电豁免和无障碍绑定均通过检查，机主 Dock 居中图标保留，见 `final-reboot.log`、`final-reboot-audio.txt`、`final-voiceinteraction.txt`、`final-accessibility.txt`、`final-launcher.txt`。预装两份 APK 与开机动画摘要和 manifest 一致；实际运行的 TTS 更新包摘要为 `ea9e6d2d95407b5d0090316a7f9142b155516e345b0215f48374ce7142d471d4`。本次捕获的 15,890 行启动日志未检出 FATAL EXCEPTION 或助手相关 AVC 拒绝，见 `boot-log-audit.txt`。这不替代真人唤醒和长时间待机验收。

## v1.10 候选镜像：内置 Dao 浏览器

在 v1.9 payload 上只加入 Dao 浏览器 0.1.9 arm64（`com.msgbyte.dao`），写入 `/system/app/DaoBrowser/DaoBrowser.apk`；助手、TTS、开机动画和源码包按原文件复制，未混入工作区其他未提交改动，见 `v1.10/source-check.txt`。APK 摘要与发布页一致，原生库不压缩且 16 KB 对齐。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `1a98630aa2894438e0f4016c6cb8c8fd91bb07203b49d92fbd9c0f28557349d6` |
| `dao.apk` | `2645fa9570d8de423ccfa9642e4b604e6ed98b3d46d4eec2344c79f0240856e6` |

镜像为 2,818,867,200 字节，动态分区合计 8,285,425,664 字节，距上限余 241,594,368 字节。小型 ext4 测试、完整构建的读回摘要与 AVB 校验通过；另从镜像导出 APK 复核摘要、root:root/0644 与 `system_file` 标签，见 `build.log`、`artifact-check.txt`。v1.10 尚未刷写，Dao 作为系统应用的启动和默认浏览器设置尚未真机验证。

## v1.11 候选镜像：已提交源码与 Dao 浏览器

由提交 `0e54fe5` 构建，工作区与提交一致。相对 v1.9 新增：Dao 浏览器；唤醒词谐音不再作为任务（`2d76eae`）；视觉回复格式错误重试一次（`25144f2`）；Jev 不可用时改用视觉模型（`de7c65d`）；KWS `maxActivePaths=8`（`e03827c`、`3b6bcad`）；语音输入接续、自动发送及悬浮窗麦克风；本地 TTS 改为 Piper xiao_ya；挥手招呼开机动画；系统版默认运行时权限。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `c1b0bd80f6a1eec882dceea90c9b41e9912a0379053bc5c735e5e056eee17817` |
| `assistant.apk` | `5754f92e965746ace2258869beb59bb95f0e3204c55cf19b00e1443b06512ad6` |
| `tts.apk` | `5e7be9269b669124a87167f2a384975697eabed208213619915638827d2bda0c` |
| `dao.apk` | `2645fa9570d8de423ccfa9642e4b604e6ed98b3d46d4eec2344c79f0240856e6` |
| `bootanimation.zip` | `9cbcf00435e88c34525ce14ac3067076bbfdcf173cd561dc03eab730346a93ec` |
| `voice-corresponding-source.tar.gz` | `b8a6f37e12623858d6fcd6fc812f1d7ebaf279281e763c83a2dd6ae6e910f139` |

镜像为 2,708,021,248 字节，动态分区合计 8,174,579,712 字节，距上限余 352,440,320 字节。Debug/System 各 112 项单元测试通过，三项 Lint 均无错误，见 `gradle.log`；开机动画测试、小型 ext4 测试、完整构建的读回摘要与 AVB 校验通过，见 `image-test.log`、`build.log`。另从镜像导出五个写入文件复核摘要、0644 与 `system_file` 标签，见 `artifact-check.txt`；源码包中 162 个已跟踪 Android 文件与提交一致，见 `source-check.txt`。

v1.11 已在 b 槽位不清数据刷写，见 `flash.log`；`system_b` 为 `0xa1692000`，与 manifest 一致，见 `partition-check.txt`。启动 25 秒完成。设备上五个写入文件的 SHA-256 与 manifest 一致，Dao 为 root:root/0644、`system_file`，以 SYSTEM 标记安装，见 `device-checks.txt`。

刷写前 v1.9 的升级基线已过时（实际配置在其后被保存），`prepare` 拒绝覆盖，`verify` 因配置摘要不同失败。改为比较刷写前后同一测试报告的实际配置摘要，均为 `8c5ee934805bc35f1bcad637f0250bb8fb58251f6264f7c2837f4a8707a28175`，且 UID 断言通过；助手 appId 仍为 10124，保留 SYSTEM 与 PERSISTENT 标记，见 `upgrade-preflash-verify.log`、`upgrade-verify.log`。由于测试在配置摘要处中止，语音设置和 Keystore 哨兵未在本次逐项比较。三项运行时权限在刷写前后均已授予，`pm grant` 未改变状态，见 `permissions.txt`。

Dao 以系统应用冷启动打开 `https://example.com` 并正常渲染，Gecko 从 `DaoBrowser.apk!/lib/arm64-v8a` 直接加载原生库，未出现崩溃，见 `dao-launch.txt`、`dao-logcat.txt`、`dao-launch.png`。启动日志 15,922 行，未检出 FATAL EXCEPTION 或相关 AVC 拒绝，见 `final-boot-logcat.txt`。最终重启 25 秒取得未被静音的助手录音，前台为桌面、未打开助手 Activity；默认助手绑定、前台服务、省电豁免和无障碍绑定通过，见 `final-reboot.log`、`final-reboot-audio.txt`、`final-reboot-state.txt`。

手机数据分区的 TTS 更新包（`ea9e6d2d…d471d4`，版本 1001）仍覆盖预装的 xiao_ya TTS，实际运行的不是本镜像的 TTS。尚待：挥手开机动画目测；xiao_ya 真机 TTS 测试此前超时未解决；Dao 搜索写入笔记任务重跑三次；真人唤醒率及误唤醒计数。

## v1.12：改名为 com.moonrailgun.opendoubao

由提交 `9845061` 加未提交的工作区构建（13 个 Android 路径有改动：去掉数据使用确认、设置页分 tab、桌面语音播报接口、显示名改为 Open Doubao）。源码包中 162 个已跟踪 Android 文件与构建时工作区一致，见 `source-check.txt`。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `92ea459e4bfa9773c1060aa64abcd1582e3407719d3d88c9f427e1e3f9572cdd` |
| `assistant.apk` | `6c6fdb836e7ed64e19011c3e99bcc16396b71c08ad1c444b65935cf20cbf62d0` |
| `tts.apk` | `da1b17dc66800e9d28e78162d4289dcc8c3be4ddcf4c562e53ad7937eb4800ff` |
| `dao.apk` | `2645fa9570d8de423ccfa9642e4b604e6ed98b3d46d4eec2344c79f0240856e6` |
| `bootanimation.zip` | `9cbcf00435e88c34525ce14ac3067076bbfdcf173cd561dc03eab730346a93ec` |
| `voice-corresponding-source.tar.gz` | `2aa212cd8d372d503a030859899d0dff5b37ae7eaf1b56985646da850fd082d6` |

镜像为 2,708,041,728 字节，动态分区合计 8,174,600,192 字节，距上限余 352,419,840 字节。Debug/System 各 117 项单元测试、三项 Lint、开机动画测试、小型 ext4 测试、读回摘要与 AVB 校验通过，见 `gradle.log`、`image-test.log`、`build.log`。证书仍为 `f73c2856…e63578`。

b 槽位不清数据刷写，见 `flash.log`；`system_b` 为 `0xa1697000`，与 manifest 一致。启动 24 秒完成。设备上五个写入文件摘要与 payload 一致，助手 appId 10129，为 SYSTEM、PERSISTENT；TTS appId 10130，为 SYSTEM；无障碍服务开机自动启用到新组件，见 `device-checks.txt`。补授三项运行时权限，并用 adb 将默认助手、语音识别服务指向新包，见 `permissions.txt`、`voice-settings.txt`。启动日志未检出新包或 Douxian 相关的 AVC 拒绝；崩溃缓冲区只有已知的 `init` SIGABRT。

包名变更不是原地升级：旧系统应用 `com.openodoubao.assistant` 随镜像移除，其模型配置、桌面绑定、记录和监听设置不会迁移，需要重新设置；数据分区里的旧 TTS 更新包和旧测试 APK 降为普通应用残留。未做升级测试（`SystemUpgradeTest` 只适用于同包名），也未做真人唤醒和长时间录音检查。

## v1.13：内置豆包输入法

由提交 `9d9a4af` 的配方构建。助手与 TTS 沿用 v1.12 的同一 APK（当时 `android/` 构建输出含未提交的语音提示改动，代码引用的 `voice/prompts/*.wav` 未打包，故不使用），新增豆包输入法 v1.5.1 及其解压的 76 个 arm64 原生库，并包含 `14e5414` 的设备型号改名；TTS 新图标未随本版交付。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `7b1409ef6647289fa001e94c6ab7618bfd237dceaa28ee1db3b81b69758d5c63` |
| `doubao-ime.apk` | `abb24d0a33cee91a9d96783243b75950151bc478ce47da629e5fdf8844607f26` |

镜像为 2,953,695,232 字节，比 v1.12 多 245,653,504 字节；动态分区合计 8,420,253,696 字节，距上限只余 106,766,336 字节，后续加内容需先腾空间。小型 ext4 测试（含原生库解压）、读回摘要与 AVB 校验通过，见 `image-test.log`、`build.log`。

b 槽位不清数据刷写，见 `flash.log`；`system_b` 为 `0xb00dd000`，与 manifest 一致。启动 25 秒完成，`ro.product.model` 为 Open Doubao。设备上五个写入文件摘要与 payload 一致；豆包输入法为 SYSTEM 应用，`primaryCpuAbi=arm64-v8a`，库目录 `/system/app/DoubaoIme/lib`，文件为 root:root/0644、`system_file`；助手仍为 SYSTEM、PERSISTENT 并持有默认助手角色，见 `device-checks.txt`。补授三项运行时权限；按旧 README 命令设置设备名只写入 `Open`（引号未传到手机），已改正命令并重设为 `Open Doubao`。

用 `ime enable` / `ime set` 启用并设为默认后，在设置搜索框请求键盘，输入法进程启动并显示其用户协议页（`PrivacyStatementActivity`），日志无 FATAL、`UnsatisfiedLinkError` 或 dlopen 失败；AVC 拒绝只来自 `rild`（已知）和 `hal_graphics_composer_default`，见 `ime-first-run-logcat.txt`。随后用户在手机上完成首次引导，麦克风与通知权限为用户授予（USER_SET）。重启后在设置搜索框直接弹出拼音 26 键键盘，没有再出现协议页，默认输入法与麦克风授权保留，助手后台录音恢复且未被静音；未做真人唤醒检查。之后配方新增的 `default-permissions-doubaoime.xml`（默认授予输入法麦克风）未随 v1.13 刷入。

## v1.14：唤醒离线提示音

由提交 `63f8b23` 构建，工作区无未提交改动。助手含 `355128d`：检测到唤醒词立即从内置的「我在」「诶」「有什么指示」中随机播放一段（ElevenLabs `eleven_v3`、音色 `bhJUNIXWQQ94l8eI2VUf`，`android/tools/make_voice_prompts.py` 生成的 24 kHz 单声道 WAV），播完再录音；不再支持唤醒词后连着说任务。VAD 静音判定由 0.8 秒改为 2 秒；识别为空时播放「我没有听清楚，能再说一遍么」并重听一次，仍为空才提示重新唤醒。另含 `63f8b23`：后台任务（语音、悬浮窗、桌面）在未保存应用范围时按全部可启动应用执行，而不是报「请先选择本次任务允许访问的应用」。TTS 首次带上 `14e5414` 的新图标；Dao、豆包输入法与开机动画与 v1.13 相同。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `4d856b43c2ca20e294ca93d0b52043a8a7497581b53c44e517227eed2cc62277` |
| `assistant.apk` | `d8fafb806e701685540d6632ab7980100bc436ef69e156993043b5d08579f7d9` |
| `tts.apk` | `34740b8ba203ebeb8d8504d97439f4530528c5707ca142bd2921eb34a39dab88` |

镜像为 2,954,178,560 字节，比 v1.13 多 483,328 字节；动态分区距上限余 106,283,008 字节。系统 APK lint 与单元测试、开机动画测试、小型 ext4 测试、读回摘要与 AVB 校验通过，见 `gradle.log`、`image-test.log`、`build.log`。

b 槽位不清数据刷写，见 `flash.log`；`system_b` 为 `0xb0153000`，与 manifest 一致。启动 24 秒完成，型号 Open Doubao。设备上五个写入文件摘要与 payload 一致，助手 APK 含四段提示音；助手为 SYSTEM、PERSISTENT，持有默认助手与识别服务，无障碍服务已启用，默认输入法仍为豆包输入法，设备名保留为 Open Doubao；补授三项运行时权限；开机后后台录音未被静音，见 `device-checks.txt`。开机日志无助手崩溃；崩溃缓冲区只有 v1 起已有的 `init` 子进程 SIGABRT。未做真人唤醒、提示音实际听感和识别失败重试的现场检查。

## v1.16：长期记忆与唤醒语音卡片

由提交 `b295376` 构建，工作区无未提交改动。相对 v1.15 只换了助手：
- 长期记忆（`b156830`–`e080fd5`）：记忆和最近 3 条已完成任务随规划、视觉、总结请求发给 Chat 模型；规划可编辑记忆，也可不操作手机直接回复；任务完成后再判断一次值得记住的内容；底部新增「记忆」页。
- 唤醒语音卡片（`cadb323`–`b295376`）：唤醒后悬浮球原地展开为卡片，彩色线条随麦克风音量起伏；录音中每 0.3 秒重新识别已录音频，文字逐字显示；最终结果仍按 VAD 整段解码；录音缓冲加大到 2 秒。

TTS、虚拟屏 RRO、Dao、豆包输入法与开机动画与 v1.15 相同。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `df6d9e2d0d8c3494676bc09e147a2c36fd327716d2da4254ac542d95bc074f7d` |
| `assistant.apk` | `2fc7554617c680f4c5207a2618c17a9cb4942a5fa14a8e8622aa56e1522cea18` |
| `tts.apk` | `54e66f3366a6d4cb3c2776a6dae56fde00f03ff57311edfaa405cba2156dcfe4` |

镜像为 2,954,330,112 字节，比 v1.15 多 77,824 字节；动态分区距上限余 106,131,456 字节。系统 APK 编译、lint 与单元测试、开机动画测试、小型 ext4 测试、读回摘要与 AVB 校验均通过，见 `gradle.log`、`image-test.log`、`build.log`。

b 槽位不清数据刷写，见 `flash.log`；`system_b` 为 `0xb0178000`，与 manifest 一致。启动 25 秒完成，型号 Open Doubao。三个写入文件在设备上的摘要与 payload 一致；助手为 SYSTEM、PERSISTENT，持有默认助手、识别服务和无障碍服务；默认输入法仍为豆包输入法，设备名保留为 Open Doubao；三项运行时权限已补授。以上见 `device-checks.txt`。崩溃缓冲区只有 v1 起已有的 `init` 子进程 SIGABRT。刷写后手机停在锁屏，尚未检查语音卡片的线条、逐字显示和 CPU 占用，也未检查记忆页及真实模型下的记忆读写。

## v1.15、v1.17、v1.18：虚拟屏执行

三版都在 Android 17 的 Computer Control 虚拟屏上执行任务，差别在系统如何允许助手开启会话。

- v1.15（`182652c`，RRO 修正 `0717860`）：加入 `ACCESS_COMPUTER_CONTROL`、隐藏 API 豁免和把助手登记为超级智能体的 RRO。第一次把 RRO 写到 `/system/overlay`，PackageManager 不扫描该目录，输出保留为 `v1.15-rc1/`；改到 `/system/product/overlay` 后 RRO 生效。真机任务仍走前台：`isSuperAgent()` 只在可调试构建承认超级智能体，本机 GSI 为 `user` 构建。`user` 构建下 DeviceConfig 白名单拒绝 adb 写入，`cmd allowlist` 只能临时加入会话发起方，且未被采纳。
- v1.17（`64ae691`）：RRO 改为把助手设为 `config_allowlistProviderServicePackage`，助手的 `AllowlistProvider` 回答会话发起方（ID 4）、可自动化应用（ID 3）和拒绝名单（ID 5），其他名单照旧返回失败。系统绑定该服务并得到 1 个会话发起方；会话创建随后在客户端校验失败：会话通知必须可提升为实况通知（`hasPromotableCharacteristics()`）。
- v1.18（`cceabeb`）：会话通知加上 `android.requestPromotedOngoing`。

| v1.18 产物 | SHA-256 |
|---|---|
| `system.img` | `dcad521aea1dd60d68bce28524c48e9b58bff4f540a6d15314adc3bb06b27a8f` |
| `assistant.apk` | `b3d0fb57fe563d705777d9482287885daa1ca20838723f8a605e2c2513c7be5b` |
| `computer-control-overlay.apk` | `47527c34ef0fec2ec50493cb7ce1cebc0d3a2bc71a5cfb60105d3af4b83e60fe` |

镜像为 2,954,334,208 字节，动态分区距上限余 106,127,360 字节；TTS、Dao、豆包输入法与开机动画与 v1.16 相同。构建、lint、单元测试、镜像测试和 AVB 校验通过，见 `v1.18/gradle.log`、`image-test.log`、`build.log`。b 槽位不清数据刷写，`system_b` 为 `0xb0179000`，与 manifest 一致；启动 25 秒，助手摘要与 payload 一致，`dumpsys app_binding` 显示 `AllowlistProvider` 已绑定，三项运行时权限已补授，见 `flash.log`、`device-checks.txt`。

真机任务 “Open Settings and tell me the Android version”：系统弹出“允许 Open Doubao 使用屏幕自动化操作与设置互动？”，选择“仅限这一次”；随后建立会话（目标应用 `com.android.settings`，虚拟屏为 Display 2），主屏幕始终停在 Open Doubao。展开悬浮球能看到虚拟屏的实时画面；任务经视觉模型截图调整后完成，结果为 “Android 版本为 17”。结束后会话关闭、Display 2 销毁，设置没有移到主屏幕。见 `virtual-consent.png`、`virtual-main-display.png`、`virtual-peek.png`、`virtual-result.png`。同一任务中，任务运行时持有 `SCREEN_BRIGHT_WAKE_LOCK`，结束后释放，见 `keep-screen-on.txt`。刷机后 Wi-Fi 未就绪时第一次规划报域名解析失败，联网后重试成功。

尚未验证：暂停时的「转到前台处理」、从悬浮窗或语音下达的任务（会话要求助手在主屏有可见窗口）、一个任务内追加第二个应用、会话超时，以及“始终允许”后不再弹授权页。

## v1.19：语音卡片实时反馈

由提交 `f3df1e5` 构建，工作区无未提交改动。相对 v1.18 只换了助手：字幕解码从录音线程移到引擎自己的单线程 `captioner`。此前 VAD 一检测到说话，每 0.3 秒对整段音频重跑一次 SenseVoice，解码期间不读麦克风、不上报电平，解码完才一次读出积压的音频块，几十次电平更新在微秒内写入 StateFlow 被合并成一次，所以恰好在用户开口时线条不动、字幕也要等整段解码后才出现。现在录音线程只读麦克风、算电平、喂 VAD；每次取最新音频快照解码，上一次未完成就不排队；结束前等最后一次解码落地再做最终识别。另加一行 `LocalSpeech: caption decode` 日志记录每次字幕解码的音频长度与耗时。

| v1.19 产物 | SHA-256 |
|---|---|
| `system.img` | `563566a4d75bf47807cf0780ba1407e4d92a69a3ac3e2f5e0e4e5ad7bd5f9e54` |
| `assistant.apk` | `67a017169620e7fcc78ee362ce4ee97f0ee6e46d281237122a371d018d5114e8` |

镜像为 2,954,334,208 字节，动态分区距上限余 106,127,360 字节；TTS、虚拟屏 RRO、Dao、豆包输入法与开机动画与 v1.18 相同。语音单元测试、镜像测试和 AVB 校验通过，见 `v1.19/build.log`、`image-test.log`。b 槽位不清数据刷写 212 秒，`system_b` 为 `0xb0179000`，与 manifest 一致；开机后助手摘要与 payload 一致，三项运行时权限已补授，无障碍服务与默认助手保留，后台录音未被静音，见 `flash.log`、`device-checks.txt`。

真机检查：用 `KEYCODE_ASSIST` 打开语音卡片，Mac 内置扬声器以 macOS 婷婷音色播放“帮我看一下明天上午的天气怎么样，再提醒我出门记得带伞”（6.3 秒），说完 0.5 秒点卡片取消，未下发任务。录屏帧（`voice-card-frames.png`，每 0.25 秒一帧）显示说话时线条明显起伏，字幕从“帮”起逐字出现到“帮我看一下明天上午的天气怎么样”。字幕解码每 0.33 秒一次，耗时随音频长度从 67 ms（0.4 秒）线性增至 398 ms（5.9 秒），见 `caption-decode.txt`、`voice-card-timeline.txt`。此前在 v1.18 上的同样播放不能作为修复前基线：当时 Mac 扬声器音量为 0.07，手机没有听到。机主随后要求不再用 Mac 扬声器或让手机发声做测试，后续语音验收由机主本人进行。

发现的问题：整段重识别的首字在“帮我…”与“我…”之间来回变化、句末标点时有时无，打字机退回公共前缀重打，整行闪烁。已提交 `920c01d`（保留上一次字幕的句首、忽略末尾标点）、`ac55cbe`（codex 审查后：延伸不再被当作掉头、最终结果整句显示、面板听写不再做字幕解码）和 `3c3c0d0`（上一条结果仍在卡片上时再次触发助手手势会被关卡定时器收起），随 v1.20 进入镜像（见下文）。

## v1.20：语音卡片字幕稳定性

由提交 `2d59ea6` 构建，工作区无未提交改动。相对 v1.19 只换了助手，含 `920c01d`、`ac55cbe`、`3c3c0d0`（见 v1.19 一节）。`2d59ea6` 本身只把 `build_image.py` 的可用空间要求从 40 GB 改为 10 GB：构建峰值约 7 GB（GSI 副本扩到 6 GiB 写入 payload 后缩回），40 GB 是写死的余量。

| v1.20 产物 | SHA-256 |
|---|---|
| `system.img` | `98bda35ccab601f7786206c93477bb9c033d35ca16f865d15e856f2368fb95fa` |
| `assistant.apk` | `dddba50fe59eae5e508b1e75c0cb1cef8bbf67dd1a2865f482b954f0e5cfb056` |

镜像为 2,954,334,208 字节，动态分区距上限余 106,127,360 字节；TTS、虚拟屏 RRO、Dao、豆包输入法与开机动画与 v1.19 相同。单元测试、镜像测试和 AVB 校验通过，见 `v1.20/build.log`、`image-test.log`。b 槽位不清数据刷写 212 秒，`system_b` 为 `0xb0179000`，与 manifest 一致；开机后助手摘要与 payload 一致，三项运行时权限已补授，无障碍服务与默认助手保留，后台录音未被静音，见 `flash.log`、`device-checks.txt`。按机主要求，本版不做播放语音的自动化检查，语音卡片由机主真人验收：待确认曲线随声音起伏、字幕逐字出现且不再整行回退重打。

## v1.21：执行方式自动判断与 App 内虚拟屏画面

由提交 `247b193` 构建，工作区无未提交改动。相对 v1.20 只换了助手（`c292f6b`、`247b193`）：
- 执行方式设置改为三态，默认「自动判断」，保留固定虚拟屏和固定前台；旧的两态设置按原意迁移（明确选过前台或虚拟屏的保持不变，从未设置的进入自动）。
- 自动模式下，任务规划前先判断执行位置：先向 Jev 提一个只含 goal 的两选一问题（置信度 ≥ 0.65 采纳），Jev 不可用、超时或拿不准时问 Chat 模型，两者都失败时按虚拟屏执行；每次调用限 8 秒。判断规则：需要用户亲眼看或亲手参与的任务（支付、登录验证、拍照扫码、通话、播放、要求展示的）前台执行，其余虚拟屏执行。判断结果和依据写入任务日志（如“执行方式：虚拟屏（Jev）”），判断期间状态为“正在判断执行方式”，此时可紧急停止。
- 虚拟屏会话打开期间，App 任务页在运行面板下方显示实时画面（与悬浮球 peek 相同的镜像，只看不能点），会话结束、移交前台或任务结束后消失；会话通知文案改为“点开查看画面；悬浮球可紧急停止”。

| v1.21 产物 | SHA-256 |
|---|---|
| `system.img` | `35f895e4151a52ecae8c43cf574b9b8dfa3a415ee052f274e0be875d6f6bac88` |
| `assistant.apk` | `052221a5c55539ef486bcb54d1843f95b6821322a4006db8df1fe3e9b336bc7a` |

镜像为 2,954,354,688 字节，动态分区距上限余 106,106,880 字节；TTS、虚拟屏 RRO、Dao、豆包输入法与开机动画与 v1.20 相同。132 个单元测试（新增执行方式协议、控制器判断步骤与停止、Jev→Chat 兜底、设置迁移）、system lint、镜像测试和 AVB 校验通过，见 `v1.21/build.log`、`image-test.log`。b 槽位不清数据刷写 212 秒，`system_b` 与 manifest 一致；开机后助手摘要与 payload 一致，三项运行时权限已补授，无障碍服务与默认助手保留，后台录音未被静音，见 `flash.log`、`device-checks.txt`。

尚未真机验证（由机主进行）：自动判断在真实任务上的前台/虚拟屏选择与日志；App 内虚拟屏画面的显示、随页面滚动的表现（置顶 SurfaceView 在滚动容器内可能有一帧延迟）、与悬浮球 peek 同时打开时系统是否允许两个镜像；语音卡片的曲线与字幕（v1.20 起）。

## v1.22：唤醒提示音播放修复

由提交 `50b4d4c` 构建，工作区无未提交改动。相对 v1.21 只换了助手，修复机主反馈的“唤醒词命中后没有播内置提示音”。

根因（v1.21 真机日志 23:20:05 与 AOSP `Tracks.cpp`/`Threads.cpp`/`AudioTrack.cpp` 对照）：`FileSpeech.play()` 用 `MODE_STREAM` 的 `AudioTrack` 播放提示音，缓冲区固定 0.5 秒（24 kHz 下 12000 帧），先 `play()` 再一次写入整段音频。AudioFlinger 的 `Track::isReady()` 要求新轨道缓冲数据达到启动阈值（默认等于缓冲区大小）才开始播放，`wake-2.wav` 只有 8662 帧，永远达不到：日志里轨道建立 0.5 秒后即被 `prepareTracks_l BUFFER TIMEOUT` 移出活动列表，播放头停在 0，`awaitPlayback` 轮询不止，音频焦点和“正在播报”状态一直保持到 56 秒后屏幕超时触发 `cancel()`，后续收听也没有开始。较长的两段提示音能填满缓冲区，但若同时有其他应用的音频被压低播放，混音器会留下不足一个周期的尾段不播，播放头到不了终点，同样卡住。

修复：提示音轨道按音频长度开缓冲区，一次写入即满；API 31 及以上把启动阈值降到 HAL 最小缓冲，很短的播报也能起播；写完后记录 underrun 计数，之后出现新的 underrun 即视为播完；提示音播放加“时长 + 5 秒”保底超时。`AudioTrack::getPosition()` 在已 `stop()` 的轨道上恒为 0，所以没有采用“写完即 stop 让其排空”的做法。

| v1.22 产物 | SHA-256 |
|---|---|
| `system.img` | `641773544121dfc1e970f053b34b1c03945e406fa33f11d416f03f9091237cdb` |
| `assistant.apk` | `76ea6b799958d186655ebda91cf46ebaa0eaf828037fab1f078e9d1e36225fa6` |

镜像为 2,954,354,688 字节，动态分区距上限余 106,106,880 字节；其余产物与 v1.21 相同。语音单元测试、system lint、镜像测试和 AVB 校验通过，见 `v1.22/build.log`、`image-test.log`。b 槽位不清数据刷写，`system_b` 与 manifest 一致；开机后助手摘要与 payload 一致，三项运行时权限已补授，无障碍服务、默认助手与唤醒监听正常，见 `flash.log`、`device-checks.txt`。提示音是否可闻由机主真人验收；本次未做播放声音的自动化检查。

## v1.23：识别模型随唤醒监听预加载

由提交 `b358d96`（重写前为 `7f4f864`）构建。**更正**：构建时工作区含另一个会话 23:38–23:40 写入的未提交改动（`CloudModels.kt`、`MainActivity.kt`、`AssistantService.kt`、`Vault.kt`、`ModelCallLog.kt` 等），APK 把它们一并打了进去；`7f4f864` 还因整文件提交把对方在 `VoiceRuntime.kt` 的改动带进了提交，已重写为只含本会话改动的 `b358d96`（`f98665c` 同理重写为 `30137b6`），对方的改动原样留在工作区。该镜像已被 v1.24 覆盖。相对 v1.22 本会话只换了助手。机主反馈唤醒后不能马上下达指令：v1.22 真机日志（23:46）显示提示音 36.0–36.6 秒播完后，识别器到 39.6 秒才开始录音，中间 3 秒在首次加载 239 MB 的 SenseVoice 模型，而卡片提示“我在，请说出任务”已经在邀请开口。现在 `VoiceRuntime.startWake()` 在唤醒协程开始时于 worker 线程调用 `LocalSpeechEngine.warm()`，把识别器和 VAD 的惰性加载提前到“正在加载唤醒词”阶段；两者本就加载后常驻，常驻内存不变。

| v1.23 产物 | SHA-256 |
|---|---|
| `system.img` | `108225945680e177e38a306f0f3d2fd7afba5b1044fd6e4e6eab74009d553672` |
| `assistant.apk` | `a29704e80fdb6374f23a68eecf225e899a72944df02abf7b2ad97612d656140c` |

镜像为 2,954,379,264 字节，动态分区距上限余 106,082,304 字节；其余产物与 v1.22 相同。语音单元测试、镜像测试和 AVB 校验通过，见 `v1.23/build.log`、`image-test.log`。b 槽位不清数据刷写，`system_b` 与 manifest 一致；开机后助手摘要与 payload 一致，三项运行时权限已补授，唤醒监听正常，见 `flash.log`、`device-checks.txt`。开机约 4 秒后 VAD 与唤醒词模型相继加载，助手进程 Native Heap 为 316 MB（识别模型已常驻），说明预加载生效；首次唤醒后是否立即进入“正在听”由机主确认。

同期机主反馈 Jev 经常“无动作”导致转视觉模型、响应变慢；每步决策只在任务记录的执行日志中，待机主提供日志后分析。代码层面的嫌疑：`Candidates.build` 为应用范围内每个可启动应用各加一个“打开 X”候选，未保存范围时为全部应用，会摊薄 Jev 的概率而达不到 0.65 的采纳线。

## v1.24：干净重建与提示音播放完成判定

由提交 `30137b6` 用 `git archive HEAD` 导出到独立目录构建：工作区当时含另一个会话的未提交改动，不能直接用；Docker 构建额外挂载仓库路径，以解析导出目录中指向 `android/.voice`、`os/aosp-*` 等的符号链接。相对 v1.22 的助手改动：识别模型与 VAD 随唤醒监听预加载（`b358d96`，见 v1.23）；提示音播放改为“所有数据写完后播放头连续 1 秒不前进即视为结束，仍为 0 则报播放未开始”，写完后把启动阈值降到已写入帧数（API 31 及以上，AOSP 确认这也会让被 AudioFlinger 禁用的轨道重新启动），提示音失败时收起唤醒卡片（`30137b6`；按 codex 复核意见替换了有竞态的 underrun 计数判定，并取消预先降低阈值以免在线播报出现起播空档）。

| v1.24 产物 | SHA-256 |
|---|---|
| `system.img` | `1b9986286b2cee58b55a5d3bed46ee90f18408746d00d0c3e1c8a69d6dd4cb65` |
| `assistant.apk` | `40d12fe5ceb747fdd071bb5eb206f11225a0d128fa3cca2b19bb6353427ecd06` |

镜像为 2,954,358,784 字节，动态分区距上限余 106,102,784 字节；其余产物与 v1.22 相同。导出目录中 133 个单元测试、镜像测试和 AVB 校验通过，见 `v1.24/build.log`、`image-test.log`。

刷写经过：第一次 v1.24 构建链因发现 APK 混入外来改动而被中止，但中止时 `flashall` 已在写 `super`，bootloader 的传输状态随之卡死（USB 仍枚举为 fastboot，命令无应答）；机主在手机上选择 Restart bootloader 后恢复，随即完整刷写 213 秒，`system_b` 为 `0xb017f000`，与 manifest 一致，见 `flash.log`、`partition-check.txt`。开机后助手摘要与 payload 一致，三项运行时权限已补授，唤醒监听恢复为“等待唤醒词”、录音未被静音，助手进程 Native Heap 337 MB（识别模型已常驻），见 `device-checks.txt`。教训：共享工作区里构建前先看 `git status`，不要中断正在进行的 `flashall`。提示音可闻与否仍由机主真人验收。

## v1.25：悬浮球动效、首屏加载与调试页

由提交 `d25af96` 用 `git archive HEAD` 导出到独立目录构建（工作区此时已无未提交改动，导出只是为了与共享工作区隔离）。本会话的改动：
- 悬浮球描边改为自绘 `BubbleRing`（`a68a170`）：执行中一段 250° 弧线以 1.1 秒一圈旋转，语音对话时绿色环以 1.6 秒周期呼吸，暂停保持静态黄环，空闲为细线；球被卡片或面板遮住时动画随 Drawable 可见性停止，系统关闭动画时退回静态。
- App 首屏把保险库解密和应用枚举挪到 IO 线程（`2e34cad`），此前首屏出现后主线程卡约 0.65 秒（真机日志“Skipped 39 frames”）。
- 调试页 `DebugActivity`（`d25af96`，设置页底部「调试」进入）：四段内置提示音试播与音频参数、播报测试、模拟唤醒/直接收听/识别自检（只显示不执行）/取消及实时状态与电平、执行方式试算、运行状态、进程内最近 200 条语音事件（`core/Diagnostics`，记录唤醒命中、播报结果与耗时、收听与识别结果、字幕解码耗时、执行方式判断）并可复制。

同时这是第一个包含另一会话提交的镜像：内置回复音频（`4fd63ef`，约 60 段 WAV）与模型调用记录及导出（`bddc5d3`），镜像因此增至 2,965,676,032 字节，动态分区距上限余 94,785,536 字节。

| v1.25 产物 | SHA-256 |
|---|---|
| `system.img` | `2a52c80897c9e448ab54285fbae29caccedd070a14da1c397c707577e57d8678` |
| `assistant.apk` | `10a0832f5de32de73e7f081d228fe911a35893eed0959ee3c17d71aebef04ea2` |

144 个单元测试、system lint、镜像测试和 AVB 校验通过，见 `v1.25/build.log`、`image-test.log`。第一次 `flashall` 在 `super` 第 3/33 块遇到 macOS USB 写入错误（`usb_write failed with status e00002ed`，见 `flash-attempt1-usb-error.log`），bootloader 仍可应答，随即重试完整刷写 213 秒成功，`system_b` 为 `0xb0c4a000`，与 manifest 一致，见 `flash.log`、`partition-check.txt`。开机后助手摘要与 payload 一致，三项运行时权限已补授，唤醒监听“等待唤醒词”、录音未被静音，见 `device-checks.txt`。动效、首屏与调试页由机主真人验收。

## v1.26：判断与规划并行、桌面端下载模型调用记录

由提交 `af65246` 用 `git archive HEAD` 导出到独立目录构建。改动：
- 执行方式判断与规划并行（`9b6127a`）：两者都只依赖 goal，`TaskController` 用 `async` 同时发出，省下串行时的约 1.9 秒；判断期间状态为“正在判断执行方式、规划步骤”。Jev 日志写出完成概率（如“完成=false（0.82）”），`Decision` 新增 `doneProbability`，0.95 的采纳线不变。起因是机主提供的任务日志：两步任务共 26.2 秒，其中 Chat 模型三次调用（规划 6.4 s、视觉确认 6.1 s、总结 5.9 s）占 18.4 秒；图库打开后 Jev 正确地“无动作”，但对“完成”的把握没过 0.95，控制器又花 6.5 秒让视觉模型确认——降门槛前需要概率数据。
- 桌面端下载模型调用记录（`af65246`）：手机新增协议方法 `records.export`，把另一会话持久化的模型调用（`ModelCallLog`）按任务打成 ZIP 放在应用缓存，按 150 KiB 分块 base64 返回（单帧上限 256 KiB），`offset=0` 重新打包；桌面端 Rust 新增 `download_calls` 命令循环拉取（自带 20 行 base64 解码，无新依赖），写入系统「下载」文件夹 `open-doubao-model-calls-<任务ID前 8 位>.zip`；任务详情里新增「下载模型调用记录（zip）」按钮。Android 145 个单元测试（新增分块导出测试）、lint 通过，桌面端 `cargo test` 10 个通过。

| v1.26 产物 | SHA-256 |
|---|---|
| `system.img` | `afa455e68966e770383c0842ed8735fb65c9d70237c385fdca03b5c111ab966a` |
| `assistant.apk` | `e7fd552f7c5cf4fffa17be90a53a2161408f5fadac492617c291334120a5deca` |

镜像为 2,965,680,128 字节，动态分区距上限余 94,781,440 字节；其余产物与 v1.25 相同。镜像测试和 AVB 校验通过，见 `v1.26/build.log`、`image-test.log`。刷写：第一次 `flashall` 在 `super` 第 12/33 块遇到 USB“设备无响应”（`usb_write failed with status e00002ed`，见 `flash-attempt1-failed.log`），bootloader 随后停止应答，机主手动 Restart bootloader 后由等待脚本自动完整刷写 214 秒成功，`system_b` 为 `0xb0c4b000`，与 manifest 一致，见 `flash.log`、`partition-check.txt`。开机后助手摘要与 payload 一致，三项运行时权限已补授，唤醒监听“等待唤醒词”、录音未被静音，见 `device-checks.txt`。连续两版（v1.25、v1.26）在 `super` 传输中途出现同类 USB 错误，之前五次刷写没有，怀疑数据线或接口不稳，建议换线直连。

待机主验证：桌面端下载某条任务的模型调用记录（任务执行时需开启模型调试记录），用其中 Jev 的“完成”原始概率决定是否在“无动作 + 上一步生效”时放宽完成判定、跳过视觉确认。

## v1.27、v1.28：镜像自带版本号

此前镜像只改了设备型号，设置 → 关于手机 → 版本号一直显示上游 GSI 的 `CP3A.260905.010`，我们的版本只存在于输出目录名和本记录里。现在 `build_image.py` 接受第二个参数（如 `v1.28`，缺省取输出目录名，必须形如 `v1.27`），把 `ro.build.display.id` 改为 `Open Doubao v1.28 (CP3A.260905.010)`，并在 `/system/build.prop` 追加 `ro.opendoubao.version=v1.28`；`manifest.json` 记录 `version` 与 `display_id`；指纹不变。调试页「运行状态」第一行显示 `Build.DISPLAY`。

- v1.27（提交 `4ed6048`，amend 前为 `156e7c1`）：只改了 `/system/build.prop`。刷入后 `getprop ro.opendoubao.version` 为 `v1.27`，但版本号仍是 `CP3A.260905.010`：`/system/product/etc/build.prop` 第 28 行也定义了 `ro.build.display.id`，init 合并各分区属性时后加载的 product 覆盖了 system（与改型号必须改 product 分区同理）。该镜像已被 v1.28 覆盖，未单独验收。
- v1.28（提交 `876eb42`，amend 后为 `6e56424`，内容相同）：`stamp_version` 在 system、system_ext、product 三处凡存在的 `ro.build.display.id` 都替换，`ro.opendoubao.version` 只写 system；`test_image.py` 补了 product 覆盖用例（7 个镜像测试通过）。

| v1.28 产物 | SHA-256 |
|---|---|
| `system.img` | `09cdc7495bf3ae4e915df5d41966b1ee31925c67cb0613de97ad9e8e85138364` |
| `assistant.apk` | `c5fc5354b9bdd219fb0722431bec118d74374285d8a8d0bd53f9dc1996daf9a2` |

镜像为 2,965,680,128 字节，动态分区距上限余 94,781,440 字节；其余产物与 v1.26 相同。两版都由 `git archive HEAD` 导出构建。v1.28 刷写一次成功（215 秒），`system_b` 为 `0xb0c4b000`，与 manifest 一致；开机后 `ro.build.display.id` 为 `Open Doubao v1.28 (CP3A.260905.010)`、`ro.opendoubao.version` 为 `v1.28`、指纹仍为上游值，助手摘要与 payload 一致，三项运行时权限已补授，唤醒监听与录音正常，见 `v1.28/flash.log`、`partition-check.txt`、`device-checks.txt`。

过程记录：v1.28 的第一次构建链因工作区有另一会话未提交的 `AGENTS.md` 改动而跳过构建，但链中 heredoc 之后的设备步骤仍执行，把手机误重启进了 bootloader（未刷写，`fastboot reboot` 恢复）；此后构建与刷写改用带 `set -e` 的脚本，任一步失败即停。

## 唤醒失效反馈跟进

用户反馈在亮屏解锁时说“同志你好”没有响应。该词已包含在 v1.7 关键词配置中。现场诊断发现同期 instrumentation 测试结束时杀掉了助手进程，随后默认助手绑定没有实际服务连接，活动录音为空。重新绑定默认助手后，录音恢复为未被系统静音，通知显示“等待唤醒词”；这只证明监听恢复，尚未取得真人再次唤醒成功的确认，不能将反馈记为已解决。

同期另一个真机任务安装了数据分区中的 TTS 更新包。v1.9 已核对实际运行 APK，并通过现有重试入口恢复其初始化后验证本地播放；重启后录音也已恢复，但仍待真人再次唤醒确认。不能仅凭镜像内预装文件摘要判断当前运行版本。避免在真人唤醒验收期间运行会终止助手进程的 instrumentation 测试。

## 桌面远程模型配置

v1.4 增加已配对桌面的 `config.get` / `config.set`。读取只返回模型配置与 Key 是否存在；留空保留同一地址的原 Key，修改地址必须重新填写 Key，任务运行或暂停时拒绝修改。手机继续使用 Keystore 加密保存，已打开的手机配置页会同步刷新。

桌面 19 项 Playwright 测试、Rust 9 项单元测试及 `cargo check` 通过，macOS 应用与 DMG 构建通过，日志为 `v1.4/desktop-ui-tests.log`、`desktop-rust-tests.log`、`desktop-cargo-check.log`、`desktop-bundle.log`。安装包为 `desktop/src-tauri/target/release/bundle/dmg/Open Odoubao_0.1.0_aarch64.dmg`，仅作本地签名，未做 Apple 公证。Android 模拟器集成测试验证真实服务回调、加密写入、设置保留及已打开手机页面的刷新，见 `android/.voice/remote-config-native-test.log`。尚未验证桌面原生程序通过真实 WSS 连接向物理手机修改配置。

桌面目录原有内容尚未纳入 Git，本次保留其状态；安装包包含本次功能，增量补丁为 `v1.4/desktop-model-config.patch`。

## 尚未完成的验收

- 真人在安静环境一米距离对四个唤醒词各说 20 次，分别记录亮屏、锁屏及深度待机结果；目标每词至少 18/20。记录唤醒、识别和首次播报延迟。
- 八小时混合环境音误唤醒计数，以及监听开启/关闭各八小时的息屏耗电对比。
- 真实电话通话、有 PIN 的开机首次解锁流程。当前手机 `gsm.sim.state=ABSENT`，通话验收需要可用 SIM 和安排测试通话。代码未启用 Direct Boot；当前开发手机没有 PIN，不能替代该场景验证。
- 当前 GSI 的 `config_supportsMicToggle` 为 false，系统麦克风隐私开关命令不生效。已用真实系统麦克风静音状态验证暂停与恢复，但不能将其记为隐私开关通过。
- 未生成 MiniMax H3 视频：本次环境无可用的视频生成凭据。当前为现有 Logo 的白底淡入与轻微透明度循环。

这是供当前开发设备使用的无签名 AVB 镜像，必须保持 Bootloader 解锁。CPU 持续监听的实际耗电仍需上述长时间测量。
