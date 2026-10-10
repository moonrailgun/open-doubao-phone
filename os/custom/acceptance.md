# 豆馅 OS v1 开发镜像验收记录

验证日期：2026-10-10。目标为当前开发用 Pixel 6 Pro、已解锁 Bootloader、`b` 槽位；基础 GSI 为 `CP3A.260905.010`。此记录区分自动检查与尚需现场、长时间测量的项目，不代表已完成全部声学及耗电验收。

## 当前产物

最近完成刷写验收的是 v1.7。v1.8 已修正为底部 Dock 居中并完成构建，尚未刷入，详见下方候选镜像记录。

本地输出目录：`os/custom/out/v1.7/`，未纳入 Git。保留原始镜像以及先前可启动的 `v1/` 至 `v1.6/` 输出。`manifest.json` 记录完整 payload、签名和容量数据。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `e8c81d7779beec8f894b9b1426dfd0fdb8c8cdc89795ea7fe873352c07377ebc` |
| `assistant.apk` | `e4e07acc1dc7cd9ac5188a9a9bf35845c4b54387b43d988f4fef17cb23fd4ba7` |
| `tts.apk` | `7506d6c265ae3252501d13271734c07c763f4f49c50c6a22d94165c3c0dfe6bd` |
| `bootanimation.zip` | `ab7130e8d416016c77eb9561d589fa6413941b0784a8a479813f865fe284d31d` |
| `voice-corresponding-source.tar.gz` | `4c580391c199b278619e2d4849c4b95e0d219c5bb7ff03fed4673e8b36dec68c` |

`system.img` 为 2,572,296,192 字节；动态分区合计 8,038,854,656 字节，低于 group 上限 8,527,020,032 字节，余量 488,165,376 字节。两份系统 APK 版本号均为 1001，证书与先前开发 APK 相同：`f73c285679b81382e5bdd814fdfab042620f86824798f7ac949e799d62e63578`。

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

## v1.8 候选镜像：底部 Dock 居中

按用户澄清，将助手从首页网格中央移到五格底部 Dock 的第三格：`container=-101 screen=2 cell(2,0)`，保留其他默认快捷方式。修改已提交为 `4e2c3cc`。默认模板只用于新建桌面，已有用户的图标还需单独移动；当前手机尚未完成这一步。

构建使用隔离的已提交源码，仅加入该布局修正，未混入工作区同时进行的语音改动。Debug/System APK 构建、System Lint 和单元测试通过，见 `v1.8/gradle.log`；完整镜像通过文件系统、容量、读回摘要与 AVB 检查，见 `build.log`、`avb-info.txt`。再次核对产物 SHA-256，并从 APK 中解码布局，确认助手在 Dock 第三格，见 `artifact-check.txt`、`packaged-launcher-layout.txt`。

| 产物 | SHA-256 |
|---|---|
| `system.img` | `c5a8d41f1ffe3bdbc7c8f1e38000447b63b45121e15f000238b5dd38a81285b1` |
| `assistant.apk` | `dc8ef282a161b5a34a6cd420974eaade1352b35e5a8b022adb8e0647a52be563` |
| `voice-corresponding-source.tar.gz` | `56a44c64d044c10cb35b8e6c9d62ec0e046cb96f4c32d73de5fa2b7df745760f` |

TTS 与开机动画沿用 v1.7，摘要见“当前产物”。镜像为 2,572,296,192 字节，动态分区合计 8,038,854,656 字节，低于 8,527,020,032 字节上限。源码包共 18,166 个条目，其中 151 个已跟踪 Android 文件与 `4e2c3cc` 一致，见 `source-check.txt`。

v1.8 尚未刷写，也未完成新用户 Dock 布局及重启后的真机验收。目前 ADB 和 fastboot 均未检测到设备；恢复连接后，需先确认其他真机测试已停止，记录最新升级数据基线，再进行不清数据刷写。

## 唤醒失效反馈跟进

用户反馈在亮屏解锁时说“同志你好”没有响应。该词已包含在 v1.7 关键词配置中。现场诊断发现同期 instrumentation 测试结束时杀掉了助手进程，随后默认助手绑定没有实际服务连接，活动录音为空。重新绑定默认助手后，录音恢复为未被系统静音，通知显示“等待唤醒词”；这只证明监听恢复，尚未取得真人再次唤醒成功的确认，不能将反馈记为已解决。

同期另一个真机任务安装了数据分区中的 TTS 更新包。后续诊断需重新核对实际运行的 TTS APK、初始化状态与播放结果，不能仅凭镜像内预装文件摘要判断当前运行版本。避免在真人唤醒验收期间运行会终止助手进程的 instrumentation 测试。

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
