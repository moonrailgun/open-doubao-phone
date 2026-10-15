# 豆馅 OS v1 开发镜像验收记录

验证日期：2026-10-10。目标为当前开发用 Pixel 6 Pro、已解锁 Bootloader、`b` 槽位；基础 GSI 为 `CP3A.260905.010`。此记录区分自动检查与尚需现场、长时间测量的项目，不代表已完成全部声学及耗电验收。

## 当前产物

当前已刷入 v1.54（GMS 底包，界面示例里的杭州改为北京，见 v1.53、v1.54 条目；v1.53 为上一个已验证版本，保留作回退。v1.53 为内置默认记忆改为 Chrome 浏览器；v1.52 为修正代码审查发现的五个问题。v1.51 为应用范围改为排除名单，默认允许所有应用和系统组件，并包含另一会话的内置默认记忆。v1.50 只含本会话改过的文件：明确指令走快速通道、意图判断与规划并行、模型连接预热、执行方式只问 Jev、视觉模型流式输出、规划前先走一步、同一操作重复暂停、总结精简、虚拟屏被拦自动转前台，见 v1.47–v1.50 条目及其真机端到端测试；v1.48、v1.49 为测试中发现的电量文字与授权弹窗问题的修正；v1.46 为执行方式只在纯查询时用虚拟屏、最终语音识别前移 0.4 秒，并首次包含 Codex 的可选 Jev、纯聊天回复与悬浮球任务条。v1.45 为长按电源键调起助手、关掉双击打开相机；v1.44 为提交动作、虚拟屏节点操作改为等页面稳定、Jev 完成门槛 0.8、Jev 决策带记忆、授权弹窗提示、录指令改走热词通道，及后来撤回的双击电源键调起助手；v1.43 为 Jev 的执行方式判断直接取两项中概率高的一项。v1.42 为 Jev 预判应用并在规划期间提前打开、规划不再生成打开应用和首页标签步骤；v1.40、v1.41 为精简发给视觉模型的界面结构（设置可关）、截图与视觉模型和 Jev 同时开始；v1.39 为目标元素未变的动作不因页面其他部分变化而重判；v1.34：2026-10-13 曾撤回到 v1.29 又重建刷回，并在 Bootloader 擦除 `frp` 分区清掉原厂遗留的 Google 恢复出厂保护，见 v1.34 条目末尾；v1.34 的 WebView 换成原厂 Pixel 的双 ABI 版本，Google 登录页可打开；v1.32、v1.33 为纯 64 位尝试，均未开机，见下文；v1.31 为 GMS 底包首个可开机版本：不再刷写原厂 product 分区，并为被改名的 Google 模块补特权白名单；v1.30 为同一改动缺白名单的版本，开机循环重启，见下文；v1.29 为等待唤醒词时以热词音源录音，状态栏不再常亮麦克风指示；v1.28 为设置里显示我们自己的版本号；v1.27 为同一改动的第一版，版本号只写进了 system 分区而被 product 覆盖，见下文；v1.26 为执行方式判断与规划并行、Jev 完成概率入日志、桌面端分块下载模型调用记录；v1.25 为悬浮球处理中动效、首屏加载挪后台、调试页，并首次包含另一会话的内置回复音频与模型调用记录；v1.24 为从已提交源码干净重建，含识别模型预加载与提示音播放完成判定修正；v1.23 的 APK 曾混入另一会话的未提交代码，见其条目；v1.22 为唤醒提示音播放修复，v1.21 为执行方式自动判断与 App 内虚拟屏画面，v1.20 为语音卡片字幕稳定性，v1.19 为语音卡片实时反馈，v1.18 为虚拟屏执行真机验收通过，均见下文；v1.16 为长期记忆与唤醒语音卡片，v1.15 与 v1.17 为虚拟屏的中间版本，均见下文；v1.14 为 v1.13 换上唤醒离线提示音与默认应用范围修复的助手，以及带新图标的 TTS；v1.13 为 v1.12 加豆包输入法和设备型号 Open Doubao；v1.12 起包名改为 `com.moonrailgun.opendoubao.*`），v1.11 及其以前的记录仍使用旧包名 `com.openodoubao.*`；以下“当前产物”表仍为 v1.9 数据，v1.11 摘要见其候选镜像记录。v1.8 的布局修正随 v1.9 一并交付，未单独刷写。

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
- 自动模式下，任务规划前先判断执行位置：先向 Jev 提一个只含 goal 的两选一问题（置信度 ≥ 0.65 采纳），Jev 不可用、超时或拿不准时问 Chat 模型，两者都失败时按虚拟屏执行；每次调用限 8 秒。判断规则：需要用户亲眼看或亲手参与的任务（支付、登录验证、拍照扫码、通话、播放、要求展示的）前台执行，其余虚拟屏执行。判断结果和依据写入任务日志（如“执行方式：虚拟屏（Jev）”），判断期间状态为“正在判断执行方式”，此时可停止。
- 虚拟屏会话打开期间，App 任务页在运行面板下方显示实时画面（与悬浮球 peek 相同的镜像，只看不能点），会话结束、移交前台或任务结束后消失；会话通知文案改为“点开查看画面；悬浮球可停止”。

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

## v1.29：等待唤醒词时不亮麦克风指示

机主反馈不触发唤醒词时状态栏右上角也一直有绿色指示点：Android 的隐私指示器只要有应用在录音就显示，而唤醒监听一直以 `VOICE_RECOGNITION` 音源开着麦克风。SystemUI 统计的麦克风 AppOp（`AppOpsControllerImpl.OPS_MIC`）不含 `RECORD_AUDIO_HOTWORD`，以 `HOTWORD` 音源（值 1999）录音会记到该 op，这是系统给热词检测留的通道；音频策略也允许助手在非前台时用它持续采集，且不算作挤占其他应用录音。

改动（`362e677`、`d6a2040`）：`privapp-permissions-douxian.xml` 与 system 变体 Manifest 加 `CAPTURE_AUDIO_HOTWORD`（`signature|privileged|role`，`test_image.py` 要求两边一致）；`LocalSpeechEngine` 的唤醒监听用 HOTWORD 音源开麦——走 `AudioRecord` 的旧构造函数（`Builder.setAudioSource` 不放行该值，`audioParamCheck` 放行），打不开或起不来就回退到 `VOICE_RECOGNITION` 并记一条调试事件；唤醒后录指令仍用 `VOICE_RECOGNITION`，此时指示点亮起属正常。

| v1.29 产物 | SHA-256 |
|---|---|
| `system.img` | `827159aca0351389b6602b7f7472e0b1871925289adbd7dce6659861f9358298` |
| `assistant.apk` | `c9a7262739fe698ec8f217ac3798975c3a457fa4d4a4e52319949236591d5317` |

镜像为 2,965,680,128 字节，动态分区距上限余 94,781,440 字节；由提交 `d6a2040` 导出构建，7 个镜像测试、语音单元测试与 lint 通过。刷写一次成功（216 秒），`system_b` 与 manifest 一致；开机后版本号为 `Open Doubao v1.29 (CP3A.260905.010)`，`CAPTURE_AUDIO_HOTWORD` 已授予，三项运行时权限已补授，唤醒监听“等待唤醒词”。

真机核实（不出声）：`dumpsys appops` 显示助手的 `RECORD_AUDIO_HOTWORD` 自 11:24:10 持续运行、`RECORD_AUDIO` 没有运行中的记录；SystemUI `PrivacyItemController` 的 Privacy Items 为空；AudioFlinger 的录音线程源显示为 `VOICE_RECOGNITION`（音频策略把 HOTWORD 映射为带 HW_HOTWORD 标志的识别输入，op 归属不受影响）；解锁后状态栏截图无麦克风指示点（`v1.29/statusbar.png`）。待机主说一次唤醒词确认热词音源确有声音进入（Pixel HAL 对该音源的走向无法事先确认），并确认绿点在录指令时出现、结束后消失。

## v1.30、v1.31：GMS 底包

机主要求镜像带上 Google 三件套。同构建号 `CP3A.260905.010` 有官方 `gsi_gms_arm64` 版本（Play 服务 26.22.36），`os/upstream-gms.json` 固定其校验值，`build_image.py` 第三个参数 `gms` 以它为底包，配方其余不变，型号字段按前缀替换（GMS 版为 `GMS on ARM64`）。空间：GMS 版 `system.img` 3,953,213,440 字节，加 payload 超出 AOSP 布局余下的 94,781,440 字节；v1.14 开机日志证明 GSI 从未扫描过原厂 product 分区里的 GmsCore（`skip_mount.cfg` 跳过 `/product`），于是 `fastboot-info-gms.txt` 去掉 `flash product`，fastboot 重建 `super` 时 `product_b` 成为零长度分区。许可只允许兼容性测试、禁止分发与修改，镜像只留在这台开发机，见 `os/README.md`。

v1.30（未提交的工作区改动，导出自 `17fa8a0`）：刷写 162 秒一次成功，`system_b` 为 0x1224c6000 与 manifest 一致，`product_b` 为 0x0；但手机在 adb 出现约 34 秒后回到 Bootloader，三次重启皆如此（槽位重试计数不减，说明是 Android 自己重启到 Bootloader）。抓到的 logcat（`scratchpad/v1.30-bootloop-logcat.txt`）显示 system_server 连续四次在 `PackageManagerService.systemReady` 抛 `IllegalStateException: Signature|privileged permissions not in privileged permission allowlist`，涉及 `com.android.permissioncontroller`（21 项）和 `com.android.cellbroadcastservice`（3 项）。根因：Google 的权限控制器与小区广播服务把 AOSP 模块声明为 `original-package`，不清数据时 `/data` 里还有 AOSP 包，PackageManager 把 Google 包改名为 AOSP 包名（`New package com.google.android.permissioncontroller renamed to replace old package com.android.permissioncontroller`，另有 `packageinstaller` 同样改名但平台白名单已覆盖），而两个 APEX 自带的 `privapp_allowlist_com.google.android.*.xml` 只写了 Google 包名，`ro.control_privapp_permissions=enforce`（system_ext 与 vendor 都如此）下即拒绝启动。Google 用清数据刷写验证该镜像，不会遇到。v1.30 产物已删除，日志保留在会话 scratchpad。

v1.31：新增 `os/custom/privapp-permissions-gms-renamed.xml`，把两份 APEX 白名单按改名后的包名复制一遍（23 + 4 项），只在 GMS 底包写入；`test_image.py` 增加白名单内容与 GMS 刷写清单两项检查（9 项通过）。

| v1.31 产物 | SHA-256 |
|---|---|
| `system.img` | `1e9f090e9a3830a98e62189d9fd8dc1d0757200ced257a12c0ad8f9f954e48df` |
| `assistant.apk` | `4dba22ebdbf089e4cd851c32f1e326c5c3f04a306870bd59078d7724ab07087c` |

镜像为 4,870,402,048 字节，动态分区合计 6,021,648,384 字节，距上限余 2,505,367,552 字节。刷写 158 秒一次成功，`system_b` 为 0x1224c7000 与 manifest 一致，`product_b` 为 0x0；重启后 23 秒出现 adb、45 秒 `boot_completed`。开机检查：版本号 `Open Doubao v1.31 (CP3A.260905.010)`、`ro.opendoubao.version=v1.31`、型号 Open Doubao；助手仍为 SYSTEM、PERSISTENT，三项运行时权限已补授，无障碍服务、默认助手与助手角色均为本应用，虚拟屏 RRO 已启用；`/product` 未挂载、`/dev/block/mapper` 中无 `product_b`；`com.google.android.gms`（26.22.36）、`com.android.vending`（51.8.09）、`com.google.android.gsf` 均已安装，解锁后 Play 商店自动弹出登录页（未登录）。按机主要求全程静默：刷写前开启"仅优先"勿扰，检查后恢复关闭；未测试语音，唤醒监听此前已由机主关闭，未再开启。

尚未验证：Google 账号登录与设备未认证提示（Bootloader 解锁，Play Integrity 必不通过）、改名后的 Google 权限控制器在运行时权限弹窗中的表现、GMS 常驻对耗电的影响、Chrome/WebView 切换。

### v1.31 上的 Google 登录：WebView 缺失

机主反馈 Play 商店的"登录"点不开。手机能通 Google 域名，Play 已连上；点登录会启动 GMS 的 `MinuteMaidActivity`（网页登录），它立刻以 `MissingWebViewPackageException: No WebView installed` 崩溃。`dumpsys webviewupdate`：`Current WebView package is null`，唯一候选 `com.google.android.webview` 是 `/system/product/app/WebViewGoogle-Stub` 的 12 KB 占位包，被系统（`lastDisabledCaller: android`）禁用。GMS 底包的 WebView、Chrome、TrichromeLibrary 都是"占位包 + `.apk.gz`"，首次开机由 PackageManager 解压安装：Chrome 与 Trichrome 已装进 `/data/app`，WebView 失败。用镜像里的 `WebViewGoogle.apk.gz`（149.0.7827.5，versionCode 782700504）手动 `adb install` 复现到原因：`The multiArch app's native libs don't support all the natively supported ABIs of the device`，该 APK 标了 `multiArch` 却只带 arm64 库，而设备 `ro.product.cpu.abilist` 含 32 位 ABI（vendor 与 GSI system 列表皆如此）。`--abi arm64-v8a` 强制也失败。shell 无权 `pm enable` 系统包。官方 Known issues 未提及。机主选择把设备声明为纯 64 位（手机上没有任何 32 位应用）。

### v1.32、v1.33：纯 64 位尝试，均未开机，已回退 v1.31

- v1.32：`/system/build.prop` 追加 `ro.product.cpu.abilist=arm64-v8a`、`abilist32=`（空）、`abilist64=arm64-v8a`。刷写 153 秒成功；`getprop` 证实三项生效，但 `boot_completed` 一直为空：vendor 的 `ro.zygote=zygote64_32` 仍启动 `app_process32`，它读不到 32 位 ABI 列表即 SIGABRT，每 5 秒重启一次，`init.zygote64_32.rc` 里 `zygote_secondary` 的 `onrestart restart zygote` 又连带重启主 zygote，system_server 起不来。
- v1.33：同处再追加 `ro.zygote=zygote64`。刷写 152 秒成功，仍是同一循环：`getprop ro.zygote` 仍为 `zygote64_32`。原因是 init 合并各分区 build.prop 时后加载的文件覆盖先加载的（`ro.` 属性也不例外），`/vendor/build.prop` 在 `/system/build.prop` 之后加载；`ro.product.cpu.abilist*` 之所以生效，只是因为 vendor 没有定义同名属性。
- 回退：用 v1.31 的刷写目录重刷（153 秒），6 秒内 `boot_completed`，设备检查与此前一致（Play 商店已自行更新到 `/data/app`）。v1.32、v1.33 产物已删除，日志与 `v1.33-zygote-diag.txt` 留在会话 scratchpad。
- 纯 64 位这条路到此放弃：原厂 Pixel 6 Pro Android 17 的 vendor 与 system 同样宣称 32 位 ABI、`ro.zygote=zygote64_32`，硬改等于跟 vendor 对抗。

### v1.34：换用原厂 Pixel 的 WebView，Google 登录页可打开

原厂镜像 `os/stock-CP3A.261005.002.A1/product.img` 里的 WebView 与 GSI 的同为 Chromium 149.0.7827.5、同一把签名密钥，但带 arm64 + armeabi-v7a 两套原生库（占位包 + 108 MB `.apk.gz`，versionCode 782700501；GSI 的为 77 MB、仅 arm64、782700504），两者都不依赖 Trichrome 静态库。`build_image.py` 新增 `replace_file`，从原厂 product 镜像取出这两个文件、核对固定 SHA-256 后替换 GSI 里的同名文件；v1.32、v1.33 的 ABI 与 zygote 改动全部撤销。`test_image.py` 增加 `test_replace_file`（10 项通过）。

| v1.34 产物 | SHA-256 |
|---|---|
| `system.img` | `48ae77b86f24b83ff14cbd51b0216a1f8d422ed7608b1c1a1e5fdd1fede7b966` |
| `assistant.apk` | `c52c59e341d9d27836a6fc0fe2d6ff16113dd11962eeeed0658fff500e7f7b89` |

镜像 4,900,581,376 字节，距上限余 2,475,188,224 字节。刷写 153 秒一次成功，重启后 21 秒出现 adb、26 秒 `boot_completed`。开机后 PackageManager 自动解压了新的占位包：`dumpsys webviewupdate` 显示 `Current WebView package (com.google.android.webview, 149.0.7827.5)`、`Valid package … versionCode: 782700501 … installed/enabled for all users`，relro 2/2 完成，APK 位于 `/data/app`；此前"已被系统禁用"的记录没有阻止解压。其余设备检查与 v1.31 一致。用 uiautomator 取"登录"按钮坐标精确点击一次：`MinuteMaidActivity` 正常成为前台，无崩溃，WebView 沙箱进程 3 个，截图 `scratchpad/v1.34-login.png` 为 Google"验证账号"页。全程静默：刷前开"仅优先"勿扰，检查后关闭；未开唤醒监听，未输入任何账号。

登录页显示"此设备已重置。请登录之前在此设备上同步的 Google 账号"：`settings get global secure_frp_mode` 为 1，`dumpsys persistent_data_block` 指向 `/dev/block/by-name/frp`（sda4，512 KiB），即原厂系统遗留的恢复出厂保护仍在，只接受此前同步过的 Google 账号。这与镜像无关，属机主决定：用原账号登录，或在确认设备归属后另行处理 FRP。

尚未验证：Google 账号实际登录与"设备未认证"提示、Play 商店安装应用、改名后的 Google 权限控制器在运行时权限弹窗中的表现、GMS 常驻耗电。

**撤回（2026-10-13）**：机主看到 FRP 门槛后决定不再使用带 GMS 的镜像。用 v1.29 的刷写目录不清数据刷回（215 秒，含 4.3 GB 的原厂 product 分区），10 秒内 `boot_completed`，助手、权限、无障碍、默认助手、虚拟屏 RRO 与此前一致，`product_b` 恢复为 0x101366000。GMS 期间解压到 `/data/app` 的 Google 应用以普通用户应用身份留下：Play 商店（`com.android.vending`，无 GMS 不能用）、Chrome（可独立使用）、Google WebView（AOSP 自带 `com.android.webview`，此份闲置）、`com.google.android.apps.restore`；未删除，待机主决定。随后按机主要求用 adb 安装第三方应用商店 Aurora Store 4.8.4（`com.aurora.store`，取自官方 GitLab 发布页，签名为作者证书 `4c626157…aa280f`）。v1.34 产物已删除，manifest 与日志留在会话 scratchpad；GMS 底包的配方、白名单与文档改动仍在工作区，未提交。

**再次切回 GMS 并清除 FRP（2026-10-13）**：Aurora Store 在手机上拉不到列表（手机没有代理，`play.google.com` 被解析到 Twitter 网段的 `104.244.42.197`，Google 真实 IP 的 443 端口直连不通；Mac 正常只是因为 FlClash 监听 127.0.0.1:7890 作系统代理），机主改为继续用 GMS 底包，并要求清掉记不住账号的 FRP 锁。先把 v1.29 上 GMS 残留的四个用户应用（Play 商店、Chrome、Google WebView、`apps.restore`）`pm uninstall`，用同一配方重建 v1.34（system.img `6f8bbdd08df084b15c53dc8cf850a5d2a62c0cefc8d9a86d8c22cb1477d8b506`，与上一份不同只因 APK 重编译与写入时间，源码与配方一致）。进 Bootloader 后 `fastboot erase frp` 返回 OKAY（`persist` 分区存 vendor 校准数据，与账号无关，未动），随后不清数据刷入 v1.34（154 秒），重启后 30 秒 adb、41 秒 `boot_completed`。开机核对：`secure_frp_mode=0`，`dumpsys persistent_data_block` 显示 `FRP state: false`、挑战块大小 0；GmsCore、Play 商店回到系统分区，原厂 WebView 占位包再次自动解压为有效 WebView。再次用 uiautomator 定位 Play 的"登录"按钮精确点击：`MinuteMaidActivity` 显示普通的 Google"登录"页，带"创建账号"入口，不再出现"此设备已重置"（截图 `scratchpad/v1.34-login2.png`），未输入账号即退出。开发者选项里的"OEM 解锁"开关随 `frp` 擦除被重置，Bootloader 本身仍处解锁状态。Aurora Store 仍以用户应用身份留在手机上。

### v1.35：点继续即授权，连续失败 3 次才暂停，「紧急停止」改为「停止」

视觉模型交还（handover）而暂停后，用户点继续即视为同意这次请求：交还理由记入本任务的授权列表，之后的视觉模型请求附带 `user_approvals`，可以直接执行被授权的那一个操作（画面显示已完成则不重复），其他发送、付款、删除仍会再次询问；输入凭据始终交还。找不到下一步、本地检查拒绝、应用无法直接打开、页面暂时读不到或截不了图，改为自动重试，连续 3 次失败才暂停；有一步执行成功或点继续即清零。悬浮窗、应用、通知、无障碍说明和桌面远程说明里的「紧急停止」改为「停止」，停止提示改为“已停止，不会再有新的操作”；旧的内置语音 `emergency-stop.wav` 已删除，该提示改走 TTS。

工作树里另有其他会话未提交的 Android 改动（Jev 可选、聊天判断等），所以本版从 `git archive HEAD`（4fb7b08）导出，只按 HEAD 行号套用本会话的 39 个改动块，导出目录内单元测试全部通过；APK 的 dex 中没有 `isChat`。GMS 底包配方与 v1.34 相同。

| v1.35 产物 | SHA-256 |
|---|---|
| `system.img` | `0ff53e87e2fd833661b8cf3d85f92adb7f07df46ef02a09c2a7aceb43fac9d51` |
| `assistant.apk` | `85a16e118fb48e9feff389d1affa1d3a2b1380245682a9f934d05e13a8e4af23` |

镜像 4,900,442,112 字节，距上限余 2,475,327,488 字节。刷写 153 秒一次成功，`system_b` 为 0x12416d000，与 manifest 一致，`product_b` 为 0x0。开机检查：版本号 `Open Doubao v1.35 (CP3A.260905.010)`，设备上 APK 摘要一致；三项权限、无障碍、默认助手、虚拟屏 RRO 以及 GMS/Play 商店均与 v1.34 相同。开机后处于锁屏息屏，语音通知为空、录音 0，与 v1.34 的开机检查一致。

尚未验证：真机上发送类任务“暂停—点继续—自动执行”的完整流程，以及重试对真实任务耗时的影响。

### v1.36：完成后少等一次模型调用，任务记录改到后台保存

起因是 2026-10-13 15:47 的“打开和孙淼的聊天窗口”：聊天窗口打开后约 28 秒才显示完成，其中视觉模型确认完成 15.5 秒、单独的总结调用 9.7 秒；同时助手主线程每步卡顿 0.7 到 1.6 秒（Choreographer 跳帧），当时推断来自每步在主线程上解密再加密整个记录库；v1.37 实测表明卡顿主要来自读屏，见 v1.37 条目。本版三处改动：视觉模型判定完成时同时给出给用户看的结果（`result`），有结果就不再调用总结模型；视觉模型请求不再附带最近 8 屏的完整界面树，那些屏的文字仍以 `retained_sources` 随请求发送；记录库在进程内缓存一份，读取不再解密，任务记录改到后台单线程按序保存，任务中的记忆写入改为原子更新，记录页在每次后台保存后刷新。

本版从 `git archive HEAD`（4fb7b08）导出，先按 HEAD 行号套用 v1.35 的 39 个改动块（与 v1.35 相同），再套用本会话的改动（逐处精确替换，每处须且仅匹配一次），Codex 会话未提交的聊天判断、可选 Jev 与视觉兜底重构不在其中。导出目录内 152 个单元测试全部通过，含新增的 `GuardedTest`、`VisionAnswerTest`。GMS 底包配方与 v1.35 相同。

| v1.36 产物 | SHA-256 |
|---|---|
| `system.img` | `db9cae53aa6717d7b849d507577e59f1e4b65a7a29a0c107b6010bf6ee36b965` |
| `assistant.apk` | `f258758d410317c4c8bf56cb0342b8e9a3b5a8bbdd6f0c58449ac83c3a4d1f2a` |

镜像 4,900,454,400 字节，距上限余 2,475,315,200 字节。刷写 154 秒一次成功，`system_b` 为 0x124170000，与 manifest 一致，`product_b` 为 0x0。开机检查：版本号 `Open Doubao v1.36 (CP3A.260905.010)`，设备上 APK 摘要一致；三项权限、无障碍（已连接）、默认助手、虚拟屏 RRO 以及 GMS/Play 商店均与 v1.35 相同；崩溃缓冲区没有助手的记录。构建前按清理规则删除 v1.34 产物，保留 v1.35 作回退；v1.14 是 adb 服务进程的当前目录，暂未清理。

尚未验证：真机任务的实际耗时（完成后的等待、视觉模型单次耗时、主线程跳帧是否消失），以及视觉模型给出的结果与原总结的质量对比。

### v1.37：冷启动应用时不再直接暂停

v1.36 上用户发“打开飞书中和孙淼的聊天窗口”（17:10）：刷机重启后飞书不在后台，助手 17:10:39.96 冷启动它，启动画面没有可读节点，读窗口 10 次（约 1 秒）后抛出“无法读取当前页面的屏幕内容”；这一异常从“等待应用到前台”（本应最多等 5 秒）一路抛出打开应用这一步，而这一步不在连续失败 3 次的重试范围内，任务当即暂停；飞书主界面 17:10:41.2 才画出（冷启动 1.3 秒）。本版把这段等待抽成 `awaitPackage`：5 秒内读不到窗口只算“还没到”，锁屏仍立即报错，超时后的问题留给下一次读屏处理。新增 `AwaitPackageTest`（3 个）。构建方式同 v1.36（HEAD 导出 + v1.35 的 39 个改动块 + 本会话改动），导出目录内 155 个单元测试全部通过。

| v1.37 产物 | SHA-256 |
|---|---|
| `system.img` | `dfcff4bf8968751aec11e464426539ea465acf9fec1647c885a090b91921c803` |
| `assistant.apk` | `4dafb6fdc71abc869d68b2b67652ba42cf42369d3980c5cf14c3b192f0654608` |

镜像 4,900,458,496 字节，距上限余 2,475,311,104 字节。刷写 154 秒一次成功，`system_b` 为 0x124171000，与 manifest 一致，`product_b` 为 0x0；开机检查与 v1.36 相同。构建前按清理规则删除 v1.35 产物。

真机复测（17:26，机主授权代测）：媒体、系统、铃声、通知音量本就为 0，全程无外放；亮屏解锁后飞书未运行，在助手首页用豆包输入法点按拼音输入“打开和孙淼的聊天窗口”并开始，文字任务不触发播报。结果：

- 冷启动：17:26:53.7 启动飞书，主界面 +1.38 秒才显示，日志“打开 com.ss.android.lark：成功”，未暂停，随后经搜索在 17:27:23 进入聊天窗口，任务完成，结果为“已在飞书中打开与孙淼的单聊窗口，未发送任何消息”。
- 总结：日志“沿用视觉模型给出的结果，不再单独总结”，省掉一次总结调用。
- 视觉模型单次 4.3 至 8.0 秒（6 次，多数 6.0 至 7.0 秒），15:47 那次为 10.6 至 15.5 秒；未排除模型服务本身的波动。
- 聊天窗口打开后到完成仍约 30 秒（17:27:23 → 17:27:53.5）：视觉模型在 17:27:33.7 已判定完成，但复核时飞书聊天页的节点指纹变了（“决策期间页面变化”），整轮重判；同样情况连续发生两次，多花约 20 秒。任务全程 64.8 秒（15:47 那次飞书已在后台，75 秒）。
- 主线程跳帧仍有 5 次，34 至 87 帧（120 Hz 下约 0.3 至 0.7 秒），都紧跟在模型返回后的读屏，即在主线程上遍历约 300 个无障碍节点；记录库后台保存不是卡顿的主因。

尚未处理：完成判定在页面内容小幅变化时被整轮作废；读屏放在主线程导致悬浮窗卡顿。

### v1.38：同一窗口内的完成判定不再作废，读屏移出主线程

两处改动。视觉模型凭截图判定完成后，复核时若仍是同一应用的同一窗口，只是内容有变化（聊天页状态、列表还在加载），直接接受判定；换了应用或窗口才重判。Jev 的完成判定重做只需 1 到 2 秒，仍保持严格，原有测试 `pageChangesAreReobservedWithoutUserInteraction` 不变。前台模式读屏（遍历无障碍节点）改到 IO 线程；虚拟屏模式未改。新增 `VisionAnswerTest` 两个用例（同窗口接受、换窗口重判）。工作区 166 个、导出目录内 157 个单元测试全部通过。

| v1.38 产物 | SHA-256 |
|---|---|
| `system.img` | `79bb6c5d1658d1bf882ecc38d7a24e98e15d4eddf80e8e58fc600a3272219748` |
| `assistant.apk` | `b80cce7e98f2d81681a889d125c4471f3c0f9fcbab039f4a7bc5f6c6050464c5` |

镜像 4,900,458,496 字节，距上限余 2,475,311,104 字节。刷写 154 秒一次成功，开机检查与 v1.37 相同。构建前按清理规则删除 v1.36 产物。

真机复测（18:40，同一场景、同样静音，飞书冷启动）：

- 进入聊天窗口（第 3 步 18:41:15.9）到完成 15.7 秒：Jev 3.2 秒、截图 0.5 秒、视觉模型 11.2 秒判定完成后直接采用，未再出现“决策期间页面变化”，并沿用视觉模型结果、未调用总结。v1.37 为 30 秒，15:47 那次为 28 秒。
- 助手进程全程没有 Choreographer 跳帧（v1.37 同场景 5 次）。
- 任务全程 87.9 秒，比 v1.37 慢：第 1 步“点击搜索”的视觉判定三次因页面变化被丢弃，第 1 步耗时 36.5 秒。飞书本身不慢（主界面 1.2 秒显示）；页面变化的原因见 v1.39 条目开头。
- 视觉模型单次 6.9 至 11.7 秒（6 次，均值约 9.2 秒），v1.37 为 4.3 至 8.0 秒；同一请求结构下差异较大，此前“请求变小后变快”的判断不能成立，波动主要来自模型服务。

尚未处理：页面其他部分变化时，动作判定会被反复作废（例如点击目标元素在新页面中仍在原处时可以直接执行）。

### v1.39：目标元素未变的动作不再因页面其他部分变化而重判

v1.38 那次第 1 步的“页面变化”经导出模型调试记录核实：消息列表顶部一个群聊在 18:40 前后连续收到新消息，最后一条预览变了三次，“未读”标签计数在 4、无、1 之间变化，未读红点让该行高度在 225 和 228 像素之间切换，下面各行随之移动 3 像素；要点的搜索按钮（`function_btn_1`，纵向 208 至 292 像素）没有任何变化。检查没有误报，只是过严。

本版对点按、输入、滚动这类按无障碍节点执行的动作：复核时若应用与窗口未变，且最新页面同一路径上的元素与决策时完全相同（文字、描述、类型、资源 ID、坐标与状态），就直接执行；执行按节点路径定位，所以同一路径上元素相同即是同一目标。按坐标点按、滑动仍做截图比对。判断放在新文件 `core/TargetCheck.kt`，新增 `TargetCheckTest`（2 个）。工作区 168 个、导出目录内 159 个单元测试全部通过。

| v1.39 产物 | SHA-256 |
|---|---|
| `system.img` | `c3c591c08df650942eb0f597e0339a8f16577a4123c91aca75bc4408dc352ae2` |
| `assistant.apk` | `cdc4425701bd64f06b6ab71572f9fe762707b29ff054cf7d20c55371703c6d23` |

镜像 4,900,458,496 字节，距上限余 2,475,311,104 字节。刷写 154 秒一次成功，开机检查与 v1.38 相同。构建前按清理规则删除 v1.37 产物。

真机复测（19:29，同一场景、同样静音，飞书冷启动）：任务全程 47.2 秒（v1.38 为 87.9 秒，v1.37 为 64.8 秒，15:47 那次为 75 秒）；从第 3 步进入聊天窗口到完成 11.0 秒；助手进程没有跳帧。飞书冷启动后前 3 秒页面确实在加载（节点数 7、261、242），截图前的复核因此作废两次，属正常。此后决策到执行之间没有再出现“页面变化”；这一轮没有日志能说明目标检查是否实际放行过（群聊当时是否有新消息不明）。视觉模型这次直接点了搜索历史里的“孙淼”，省去了输入步骤。

### v1.40：精简视觉模型请求（默认开启，可关），截图与视觉模型和 Jev 同时开始

起因：v1.39 的调试导出显示每次视觉请求 3.6 万至 6.4 万输入 token、0.15 至 0.26 美元，文本里当前界面 129 个节点有 107 个是无文字、不可操作的布局容器，另带上一屏的完整界面树；Jev 在该场景每步都先花 1 至 2 秒给出“无动作”，视觉模型才开始。

- 精简：当前屏与上一屏只发有文字、描述或可点按、输入、滚动、勾选状态的节点，省略空字段与 false，类名和资源 ID 去掉包名；提示词说明缺省标志为 false。执行仍按节点路径，被省略的节点本来无法操作。Jev 请求格式不变。
- 开关：设置 → 执行 →“看截图时的界面信息”中的“精简界面信息（推荐）”，默认开启，新任务生效；关闭后发送完整界面结构。设置页带 FLAG_SECURE，截图验证不了该开关的外观。
- 并行：每步读完页面即同时开始截图和视觉模型调用；Jev 有把握就取消（日志记“× 视觉模型 取消 Nms”，桌面时间线显示“（已取消）”），否则等视觉结果。代价是 Jev 本可独自完成的步骤也会发出视觉请求和截图（悬浮球短暂隐藏）。
- 新增 `CompactScreenTest`（2 个）、`LookAheadTest`（2 个），更新 `TaskControllerTest` 中两个按“只在需要时调用视觉”编写的用例。

| v1.40 产物 | SHA-256 |
|---|---|
| `system.img` | `a63ee4872da37722615b27a52c7cdd5a0071872d842c977b45562a77e68004d4` |
| `assistant.apk` | `370b9e806515701f767c6dfab0eae714cd7c6a14db90647dd852f82be0207df7` |

构建前删除 v1.38 产物。刷写一次成功，开机检查同 v1.39。

真机复测（21:55，同一场景、静音、飞书冷启动）：任务全程 40.5 秒（v1.39 为 47.2 秒），助手进程没有跳帧。视觉请求降到 1.55 万至 1.83 万输入 token，每次 0.064 至 0.077 美元；单次耗时 4.5 至 7.6 秒，与请求大小关系不明显（一次只有 3248 token 的请求也用了 5.0 秒）。退步一处：截图在飞书 7 个节点的启动页上立刻完成，视觉模型用 5.0 秒答“等待”后结果作废，冷启动到首个稳定页面 6.1 秒（v1.39 为 4.1 秒）。

### v1.41：空白页面变化后不再等视觉模型

Jev 答“无动作”后，若它看的页面没有任何有文字或可操作的节点且此后已变化（应用仍在加载），立即取消视觉调用并重读页面；有内容的页面仍等视觉结果，因其答案可能仍可用（同一窗口的完成判定、目标节点未变）。只对空白页生效，是因为对所有变化都重读会让持续刷新的群聊页面一直拿不到视觉结果（`VisionAnswerTest` 的两个用例即此情形）。`LookAheadTest` 增至 3 个。工作区 173 个、导出目录内 164 个单元测试全部通过。

| v1.41 产物 | SHA-256 |
|---|---|
| `system.img` | `7698fab3de7b72b652114a82a09e0af8aead77fccb13803f218e414cd6232653` |
| `assistant.apk` | `ebd05f1f6df91a76920ac8270cc79aa8f6057bae6acd523c43d884d649a8f821` |

镜像 4,900,470,784 字节，距上限余 2,475,298,816 字节。构建前删除 v1.39 产物。刷写 153 秒一次成功，开机检查同上。

真机复测（22:09，同上）：任务全程 62.8 秒，比 v1.40 慢，原因不在本版改动：规划 7.0 秒、执行方式 5.9 秒（Jev 不确定，改问 Chat 模型），主界面那次视觉调用 10.9 秒；搜索页结果在 104、114、104 个节点间变化，所选联系人行两次移位，两次视觉结果被作废（约 16 秒）。新检查这一轮没有触发：Jev 0.4 秒答完时启动页还没变，视觉模型仍用 6.2 秒答“等待”。助手进程没有跳帧。

第二轮（22:15，先强制停止飞书保证冷启动）：任务全程 30.2 秒，但路径不同：视觉模型在消息列表里直接点了与孙大炮的会话（`content_view`），只用 1 步，没走搜索，不能与前几轮直接比较。规划 8.7 秒；启动页上视觉模型又用 4.7 秒答“等待”，冷启动到首个稳定页面 5.8 秒；单步 7.4 秒、确认完成 7.1 秒，三次视觉调用 4.7、5.2、5.5 秒，Jev（1.1 秒左右）都被并行掩盖。新检查两轮都没触发：Jev 0.4 秒就答完，飞书那时还没画出内容。

两轮合看：确定的收益是 Jev 不再挡在视觉模型前面（每个视觉步骤约 1 至 2 秒），以及视觉请求费用降低一半以上；单次视觉耗时在 4.5 至 10.9 秒之间波动，单轮全程耗时受服务延迟和页面加载影响，各只有一轮，不足以下结论。

### v1.42：Jev 预判应用并提前打开，规划不再写首页步骤

- 预判：任务开始时与规划、执行方式同时向 Jev 问一句“从哪个应用开始”，候选为可启动应用（带名称），状态里附长期记忆与最近任务；Jev 的选择概率与置信度都不低于 0.8 才算数（比别处的 0.65 高，因为猜错会多打开一个应用）。有把握时等执行方式定下用哪块屏，就在那块屏上打开该应用；规划返回同一应用则不再重复打开，返回别的应用就照常打开规划的那个，规划直接回复则取消预判。日志记“Jev 预判应用：…”和“打开 …：成功（Jev 预判，规划同时进行）”，桌面时间线照样标出“打开应用”。
- 规划提示词：步骤从应用打开之后算起，不写打开应用和进入它默认打开的页面或标签（如消息列表）。
- 新增 `AppGuessTest`（4 个）。工作区 177 个、导出目录内 168 个单元测试全部通过。导出目录的 `ModelConfig` 没有工作区里 Codex 未提交的 `hasJev`，预判在那里改用 `jevKey.isBlank()` 判断。

| v1.42 产物 | SHA-256 |
|---|---|
| `system.img` | `9d363cd8a9e2a6406c8968b3909a4878ac8d690f32f86927ce0d670957817e09` |
| `assistant.apk` | `2f6151585ae13a8eb0cd626377754e01f66d01b4bf473f536aaf97a03ebff753` |

镜像 4,900,478,976 字节，距上限余 2,475,290,624 字节。构建前删除 v1.40 产物。刷写 156 秒一次成功，开机检查同上。

真机复测（23:12，同一场景、静音、重启后飞书冷启动）：任务全程 33.5 秒，走的是完整搜索路径（同路径此前为 v1.39 47.2 秒、v1.40 40.5 秒、v1.41 62.8 秒）。规划步骤从“点击顶部搜索框”开始，没有再点“消息”标签。Jev 1.1 秒预判飞书（置信 0.95），但提前打开没有生效：Jev 对执行方式偏向前台（概率 0.82），置信度 0.64，差 0.01 未过 0.65，改问 Chat 模型又用 4.3 秒，执行方式共 6.0 秒，比规划（5.5 秒）还晚，飞书因此到规划之后才打开。启动页这次截图本身因页面变化失败，很快重读，冷启动到首个稳定页面 2.6 秒。三次视觉调用 4.9、4.5、6.8 秒。助手进程在打开飞书后的截图期间跳了一次帧（31 帧），前几轮没有，原因未查。

### v1.43：执行方式由 Jev 取两项中概率高的一项

用户要求：前台还是虚拟屏二选一，直接取 Jev 概率高的一项，不再看置信度门槛；两项相等时按规则取虚拟屏。只有 Jev 出错或超时才问 Chat 模型。日志里不再出现“Jev 不确定”。更新 `ProtocolTest`、`CloudModelsTest` 中按旧门槛写的两个用例。工作区 177 个、导出目录内 168 个单元测试全部通过。

| v1.43 产物 | SHA-256 |
|---|---|
| `system.img` | `2114f7b9de7f60c4977a949bd966ef9daed149956f3cdbf69962bfc2f6479e7d` |
| `assistant.apk` | `f304850e8bb1c17ecfc089d7d20caab728e34e57779e90e432ce31d6da6d2c29` |

镜像 4,900,478,976 字节。构建前删除 v1.41 产物。刷写 154 秒一次成功，开机检查同上。

真机复测（23:49，同一场景、静音、重启后飞书冷启动）：任务全程 32.1 秒。应用预判与执行方式各约 1.2 秒，飞书在开始后 3.2 秒打开；规划这次用了 6.7 秒，返回时飞书已加载完（首次读到 241 个节点），没有再等启动页（v1.42 从规划结束到首个稳定页面 4.3 秒，此前各版 6 秒左右）。三次视觉调用 5.0、7.2、6.1 秒，比 v1.42 那轮慢约 2 秒，所以全程只比 33.5 秒少 1.4 秒。助手进程没有跳帧。

Jev 门槛排查（三份调试导出共 16 次 Jev 决策）：动作门槛 0.65 以下的首选，只有 2 次与随后视觉模型选的节点一致（搜索框 0.39、联系人头像 0.52），6 次是点“消息”标签（0.20 至 0.50），其余是“无动作”或绕远的搜索框；降低动作门槛会多出无用步骤，不划算。完成判定在真正完成的聊天窗口上只有 0.13 至 0.16（门槛 0.95），降门槛也无用，原因是 Jev 不知道“孙大炮”就是“孙淼”。“上一步效果”只在判为“没生效”时转视觉，没有拖慢；意图判断不在镜像里；应用预判两次都是 0.95。除执行方式外，没有单纯因为门槛导致的变慢。

### v1.44：提交动作、虚拟屏等待、完成门槛与录指令静音

2026-10-14 实测发现的问题与改动：

- 提交动作：谷歌地图打完搜索词后没有“按回车”可用（虚拟屏也不显示键盘），连试 14 步没搜出来。新增 `submit` 动作，对输入框执行 `ACTION_IME_ENTER`；只在资源名或描述含 search、query、url_bar、omnibox、搜索等的输入框里放行，聊天框里回车等于发送，本地拒绝。
- 虚拟屏等待：反编译手机上的 `ComputerControlAccessibilityProxy` 得知，会话的稳定回调只在会话自己的输入（打开应用、模拟点按、滑动、返回）后触发，无障碍节点操作永远等满 2.5 秒上限（Chrome 实测每次点击 2.72–2.75 秒）。节点操作改为反复读页面，连续 0.3 秒不变即继续，最长 1.5 秒。
- Jev 完成门槛由 0.95 降到 0.8：六份记录 40 多次判断里，未完成页面最高 0.2，查电量的完成页为 0.85、0.87。
- Jev 决策请求带上记忆与最近任务；系统授权弹窗出现时，任务卡片与悬浮条显示“等你在系统弹窗里允许操作「应用」”。
- 录指令被静音：长按电源键后录音每次 0.1 秒内就被系统静音（`setSilenced`），助手报“麦克风被系统暂停”收起卡片。按 AOSP `AudioPolicyService::updateUidStates_l`，无障碍服务所在 UID 不参与“前台或最近录音”的计算，助手用 `VOICE_RECOGNITION` 录音只在无障碍应用处于前台状态（进程状态 2–5）时放行，而常驻进程的状态是 0，所以自 v1.29 登记为助手后录指令总被静音。助手自己的指令改走 HOTWORD 音源（与唤醒词相同，状态栏不亮麦克风指示）；其他应用调用本机语音识别仍用普通音源。
- 双击电源键调起助手（框架覆盖 `config_doublePressOnPowerBehavior` 3 加目标 Activity），真机上亮屏双击没反应：紧急求救手势开启时，`GestureLauncherService` 会拦下每个快速的第二下用来数 5 连按。按用户选择在 v1.45 撤回。

单元测试工作区 184 个、导出目录 175 个通过。镜像刷写成功，开机检查正常（产物已在构建 v1.46 前清理）。

### v1.45：长按电源键调起助手

框架覆盖只保留 `config_doubleTapPowerGestureMode` 0（关掉双击打开相机或钱包）；长按电源键沿用 GSI 默认的调起助手，双击不做任何事，关机菜单为电源键加音量上键或快捷设置里的电源按钮。真机核对：`mLongPressOnPowerBehavior=LONG_PRESS_POWER_ASSISTANT`、`mDoublePressOnPowerBehavior=MULTI_PRESS_POWER_NOTHING`、`MaxMultiPressCount=1`，紧急求救未改。00:56 用户长按电源键后，录音记录为 `src:HOTWORD not silenced`，持续约 10 秒到说完，录指令静音问题已解决。

| v1.45 产物 | SHA-256 |
|---|---|
| `system.img` | `b91139101445c17a1d66b795aa2a4febbf831466146f116ec47b01f6d9980749` |
| `assistant.apk` | `a9ef6b0c44255239d0e3fe477c2c533033e5ced2d32b6145f05c9defb9db3012` |

### v1.46：执行方式只在纯查询时用虚拟屏，语音最终识别前移 0.4 秒

改动（提交 `19affe2`、`6b31f88`）：

- 用户说“打开飞书”，悬浮窗字幕是“打开飞书”，交给任务的却是“海飞书”。字幕从语音检测点前 0.4 秒截取，最终识别却直接用 VAD 切出的片段（从过阈值处开始），切掉了“打”和“开”的声母。最终识别改用同样前移 0.4 秒的音频（`utterance()`，3 个单元测试）。
- 执行方式规则按用户要求改为：只有单纯获取信息再汇报、不需要给用户展示任何东西的任务（查询、读取或总结、查看数值或状态）用虚拟屏，其余（打开应用或页面、展示或播放、发送、付款、登录、改设置、编辑或起草）一律前台；拿不准、两项概率相同、两个模型都判断失败时也按前台。

由 HEAD `3548e34` 直接导出构建（此前各版是在旧 HEAD 上套补丁构建的），这是第一个包含 Codex 改动（可选 Jev、纯聊天回复、悬浮球任务条）的镜像。导出目录 188 个单元测试通过，安装包内已含新规则字符串、不含旧的“拿不准选虚拟屏”。刷写 152 秒一次成功，开机检查正常。

| v1.46 产物 | SHA-256 |
|---|---|
| `system.img` | `91f9000a2fe565a155a9dba58e3f8110d6d591da6d63cace772c3ecaf5440151` |
| `assistant.apk` | `bf3e989453a5ff14fd2fc3eb26f636d74972a4f6f3dca4795cd37f7645dda797` |

真机端到端测试（2026-10-15 14:54–15:09，静音：媒体与助手音量为 0，全部打字下达；授权弹窗一律选“仅限这一次”）：

| # | 指令 | 结果 | 全程 | 执行方式 | 明细 |
|---|---|---|---|---|---|
| 1 | 打开飞书 | 通过 | 31.8 秒 | 前台（Jev，0.46 秒） | 意图判断 3.0 秒（超时，按任务处理）；应用预判 0.47 秒，开始后 5.2 秒打开飞书；规划 7.0 秒；Jev 判完成 0.87（4.3 秒），过 0.8 门槛未调视觉模型；消息列表刷新导致页面变化，重读后 Jev 再判 0.86（4.0 秒）；总结 12.0 秒 |
| 2 | 打开和孙淼的聊天窗口（飞书冷启动） | 通过 | 55.9 秒 | 前台（Chat 模型，Jev 超时） | 意图判断 3.0 秒超时；应用预判 8.0 秒超时，未提前打开；执行方式 13.6 秒（Jev 8 秒超时后 Chat 模型 5.6 秒），压在关键路径上；规划 9.1 秒；冷启动两次页面变化后读到首页；点搜索、填“孙大炮”、点联系人三步均由视觉模型决定（8.6、4.9、6.3 秒）；完成由视觉模型确认（5.7 秒）。v1.43 同场景 32.1 秒 |
| 3 | 查一下电池电量 | 未通过 | 停止 | 虚拟屏（Jev，0.35 秒） | 授权弹窗出现时悬浮条显示“等你在系统弹窗里允许操作「设置」”；虚拟屏上一次点击从决定到完成 0.6 秒（v1.43 为 2.7 秒）；首页看不到“电池”，Jev 点了设置顶部搜索栏，搜索页属于另一个包，被虚拟屏拦下（“应用打开了本次任务以外的页面”），连续 3 次后暂停，手动停止 |
| 4 | 在谷歌地图里搜索附近的咖啡店 | 提交通过，任务未完成 | 停止 | 前台（Jev，0.48 秒） | 应用预判开始后 3.1 秒打开地图；Jev 每步约 1 秒完成点搜索框、填“附近的咖啡店”、提交（开始后 11 秒）；视觉模型对不可编辑节点的一次提交被本地拒绝；结果页与底图一直加载不出（网络），任务在“点搜索框、提交”间原样重复到第 18 步，手动停止 |
| 5 | 屏幕上显示的是什么 | 有结果，聊天路径未覆盖 | 约 30 秒 | 前台（Jev，1.2 秒） | 意图判断 3.0 秒超时按任务处理，没走纯聊天回复；规划打开飞书聊天窗口并描述其内容 |

未测：语音（最终识别前移需要真人说话，用户要求不出声）。

发现的问题：

- Jev 决策请求的记忆被丢：记忆里已有“孙淼在飞书中的显示名为孙大炮”，但飞书页面一大请求就超过 48 KB，实现时先丢记忆再裁上一屏，结果除启动页外的请求都不带记忆，完成页的完成概率仍只有 0.04。应先裁上一屏、保留记忆。
- 意图判断串行排在最前，5 次中 3 次撞上 3 秒超时。
- Jev 超时时执行方式改问 Chat 模型，合计 13.6 秒；按新规则拿不准即前台，可不再等 Chat 模型。
- 虚拟屏遇到跨包页面（设置搜索）直接暂停；可改为转到前台继续。
- 环境卡住时会原样重复同一组动作（地图 18 步），需要重复检测。
- “打开飞书”这类目标的总结用了 12 秒；Jev 判完成后页面局部刷新会丢掉完成判断重来一轮。

### v1.47：快速通道与提速

针对 v1.46 端到端测试发现的问题（2026-10-15），由 HEAD `3548e34` 加未提交的工作区改动构建（构建脚本用临时索引把工作区文件叠到 HEAD 上导出，不动真实索引）。改动：

- 快速通道：打开某个应用（整句只有动词和一个准确的应用名）、回桌面、返回、打开蓝牙或 WLAN 等设置页、查电量或存储空间，在手机上直接完成，不调用模型、不写总结，结果是一句固定的话。识别不了或执行失败时走完整流程。
- 意图判断超时：原因是每个任务新建 HTTP 客户端，第一次 Jev 调用要重新和美国东部的路由做 TLS 握手（0.5–1 秒）。改为共享客户端，并在开始输入或开始听指令时，向各模型服务的源地址发一次不带密钥的 HEAD 预热连接。规划与意图判断同时开始，判为聊天就取消规划。
- 执行方式：配置了 Jev 时只问 Jev，3 秒内没答复或出错按前台，不再接着问 Chat 模型；只有未配置 Jev 时问 Chat 模型。
- Jev 决策请求过大时先裁上一屏、再裁当前页尾部节点，保留记忆和最近任务。
- 虚拟屏上应用要打开任务以外的页面（被系统拦下）时，自动转到前台继续；转不过去仍按原来的方式暂停。
- 同一页面上的同一操作已执行 2 次、第 3 次时暂停交给用户（只计真正执行的操作，等待除外）。
- 总结只带最后一页的精简节点和已保留的文字（v1.46 的总结请求 160 KB，其中 149 KB 是 8 页完整节点）。
- 同一窗口内局部刷新时，Jev 的“完成”和视觉模型的一样算数，不再重判。
- 视觉模型改为流式请求，动作字段一齐就执行，观察内容随后补进已保留的文字；总结和下一次视觉调用前先等它补完。
- 预判的应用打开后、规划返回前，先做 Jev 有把握的点击（只点击，输入等规划给出文字）；规划选了别的应用就不再点。

工作区单元测试 209 个、导出目录 209 个通过。刷写 153 秒一次成功，开机检查正常。

| v1.47 产物 | SHA-256 |
|---|---|
| `system.img` | `0ffef7519d447df12c11b5b683e87d516c70ec375114d85722ed9b1a735424cf` |
| `assistant.apk` | `696b943cc008d53120553d6e56b41b456850e6702fad31ace21326acc0524eb7` |

真机端到端测试（2026-10-15 16:07–16:24，静音：媒体、系统、铃声、通知音量为 0，全部打字下达，测试期间临时打开充电时常亮、测完恢复为 0；授权弹窗选“仅限这一次”）：

| # | 指令 | 结果 | 全程 | 执行方式 | 明细 |
|---|---|---|---|---|---|
| 1 | 打开飞书 | 通过 | 1.85 秒 | 快速通道 | 不调用模型，1.78 秒是打开应用本身；悬浮条显示“已打开飞书”（v1.46 为 31.8 秒） |
| 2 | 查一下电池电量 | 通过，文字有误 | 即时 | 快速通道 | “电量 100%，正在充电”，实际为接着电源但未充电（状态 4），v1.48 修正 |
| 3 | 打开和孙淼的聊天窗口（飞书冷启动） | 通过 | 24.8 秒 | 前台（Jev，392 毫秒） | 意图判断 376 毫秒未超时；应用预判 721 毫秒，开始后 2.2 秒打开飞书；规划 3.6 秒与意图判断并行；两步由视觉模型决定，流式回复的动作分别比完整回复早 0.65、0.79 秒；完成由视觉模型确认（6.2 秒），沿用其结果不再总结。所有 Jev 决策请求都带记忆（v1.46 为 55.9 秒） |
| 4 | 查一下手机的安卓版本号 | 回答正确，留下弹窗 | 8.5 秒 | 虚拟屏（Jev） | 规划凭记忆直接回答；但规划期间已在虚拟屏提前打开设置并弹出系统授权框，任务结束后授权框留在屏幕上，v1.50 修正 |
| 5 | 查一下手机的蓝牙名称是什么 | 通过 | 14.9 秒（含约 2.4 秒手动授权） | 虚拟屏（Jev，356 毫秒） | 规划返回前先做了第一步点击；Jev 三步，视觉模型确认完成：“手机的蓝牙名称是 Open Doubao” |
| 6 | 在设置里搜一下屏幕超时是多久 | 自动转前台，随后暂停 | 停止 | 虚拟屏（Jev） | 点设置搜索栏后被虚拟屏拦下，16:18:03.996 自动转到前台；搜索页属于 `com.android.settings.intelligence`，不在 28 个可用应用内，按范围规则暂停 |
| 7 | 在谷歌地图里搜索附近的咖啡店 | 提交通过，任务未完成 | 停止 | 前台（Jev，376 毫秒） | 规划返回前先点了搜索框；Jev 每步约 1 秒，开始后 8.9 秒提交搜索（v1.46 为 11 秒）；结果加载不出（地图提示检查网络）；视觉模型 3 次想对不可编辑的搜索栏提交，均被本地拒绝，约 33 秒后暂停（v1.46 在同样的网络下空转到第 18 步） |
| 8 | 屏幕上显示的是什么（在飞书聊天窗口用悬浮条输入） | 通过 | 12.9 秒 | 聊天 | 意图判断 378 毫秒判为聊天，规划随即取消；截图 1.2 秒，聊天回复 11.1 秒，准确描述当前聊天窗口，未操作手机 |

已确认网关对视觉请求返回流式响应（SSE）。未测：语音；同一操作重复暂停在真机上未触发（地图先因本地拒绝 3 次暂停），由单元测试覆盖。

### v1.48、v1.49：电量文字与授权弹窗

- v1.48：电量满且接着电源时系统的 `isCharging` 仍为真，改为只在电池状态为“充电中”时说正在充电。真机核对：状态 4、电量 100% 时显示“电量 100%”。
- v1.48 还尝试在放弃虚拟屏请求时用 `rootInActiveWindow` 判断授权框并按返回键关掉；v1.49 改为在获得焦点的窗口里找授权框。两次真机复现授权框都没关掉：任务结束那一刻（16:31:58.889 有助手窗口的输入法事件）或点悬浮条停止时，焦点在悬浮条上而不在授权框上，按返回键也落不到授权框。此方案放弃，v1.50 改为不提前打开。

| 产物 | SHA-256 |
|---|---|
| v1.48 `system.img` | `3e3b4f250c83fdecbf1b162fc810280cd5b4d8df3b964082dd415bb7dd3374ba` |
| v1.48 `assistant.apk` | `9331d5e29684edad7385cf2e1911b4cd39140f47e1a5ec051a32a30043a976f5` |
| v1.49 `system.img` | `502379007bfdd1191538196b3a9f5b2e1ed5659c4bcc5d59230b776af878a2fe` |
| v1.49 `assistant.apk` | `f37f6c34e0baeced1dd49f4544168b44e11e4e25bee918b5bef7197c0623b795` |

### v1.50：虚拟屏上等规划返回再打开应用

在虚拟屏打开应用要先经用户授权；规划返回前就提前打开，规划随后凭记忆直接回答时，授权框会留在屏幕上。执行位置是虚拟屏时改为等规划确认是任务再打开（前台仍提前打开，并在规划期间先走一步）。代价是虚拟屏任务少了提前打开的约 1–2 秒。

只导出本会话改过的文件（`recipe/my-paths.txt`），另一会话同时在工作区里的默认记忆改动（`Memory.kt`、`Vault.kt` 等四个文件）不在镜像内。工作区单元测试 211 个（含另一会话的 1 个）通过，导出目录单元测试通过。刷写 151 秒一次成功，开机检查正常。

| v1.50 产物 | SHA-256 |
|---|---|
| `system.img` | `574ceb825c58d7e62037311137771a51245bdb2ccc7ddbd8a1861fd742759b73` |
| `assistant.apk` | `0ccc659cddabc790b83521140899b2184e23180d85323309bcede8ba6ffee6b8` |

真机核对（16:52）：“查一下设置里的安卓版本号”的日志为“虚拟屏上等规划返回再打开 com.android.settings”，规划（6.2 秒）确认是任务后才弹授权框；授权后 Jev 两步（滚动、点击）、完成概率 0.85，总结 4.4 秒（v1.46 为 12 秒），结果“你的手机安卓版本是 Android 17，这是在‘设置 > 关于本机’页面看到的”。

### v1.51：应用范围改为排除名单

按用户要求，默认允许助手操作所有已安装的应用和系统组件（助手自身除外），用户在选择器里勾选的是“不让助手操作的应用”。起因是 v1.47 测试 6：设置的搜索页属于 `com.android.settings.intelligence`，不在当时的 28 个可启动应用里，任务转到前台后又暂停了。

- 存储：`Saved.apps`/`appsConfigured`（允许名单）改为 `excludedApps`（排除名单）。旧的允许名单不迁移，升级后回到全部允许。为了能回退到旧镜像，仍写入空的 `apps` 和 `appsConfigured: false`，旧版本读到后按“全部可启动应用”处理。
- 选择器：标题“不让助手操作的应用”，默认一个不勾；首页网格图标旁只在有排除时显示“排除 N”，下方列出“不操作：…”。至少要留一个应用。
- 桌面端协议不变：`apps.get` 返回允许的应用（默认全部），`apps.set` 收到允许的应用后，手机换算成排除名单保存。
- 规划只从能直接打开的应用里选起点（`allowed_apps` 只发可启动的应用，27 个）：全部 183 个包名有 6.3 KB，会让规划请求翻倍；没有入口的组件本来也只能从别的应用进入，出现在屏幕上时照常操作。
- 暂停提示改为“当前应用 X 不在助手可操作的范围内”。

另一会话在工作区里有未提交的内置默认记忆（`Memory.kt`、`Vault.kt`、`MemoryTest.kt`、`SystemUpgradeTest.kt`）。本次改动也要改 `Vault.kt`，镜像不得不连同这四个文件一起导出（`recipe/v151-paths.txt`），所以 v1.51 包含该默认记忆。工作区单元测试 212 个通过，设备测试可编译（未运行）。刷写 152 秒一次成功，开机检查正常。

| v1.51 产物 | SHA-256 |
|---|---|
| `system.img` | `42fd0ba92a79f1618dd0c67efb2022707e5fae2a8cfdc5b8c77395366644c6ab` |
| `assistant.apk` | `6731a5b903add8ac9e477ce4ea937240c15932f9d17e1b017aceff21b358886a` |

真机核对（17:21–17:24，静音，打字下达）：

- 首页网格图标旁没有数字，选择器“已排除 0 个”，列表一个不勾。
- “在设置里搜一下屏幕超时是多久”：日志开头为“可用应用 182 个”；虚拟屏上点搜索栏被拦，17:22:23.999 自动转到前台，随后在 `com.android.settings.intelligence` 里点搜索框、输入“屏幕超时”，不再因范围暂停。这台手机的设置搜索对“屏幕超时”和“超时”都返回“没有结果”，任务在“返回设置、再搜索”之间来回三轮，第三次在同一页面点搜索栏前按“同一页面上的同一操作已做过 2 次仍没有进展”暂停（首次在真机上触发这条规则），手动停止。

### v1.52：代码审查修正

对 v1.47–v1.51 的未提交改动做了审查（两个独立审查加人工核实），按投入产出只修会实际碰到的问题：

- 视觉模型“动作先到”后补来的观察会丢：下一步的视觉调用在等它时先清掉了待补标记，若这一步被 Jev 有把握的决定取消（常见路径），观察就没人保存。改为拿到观察后才清标记。原测试因 Jev 不耗时没覆盖到，新增 Jev 耗时 1 秒的测试。
- 权限授权弹窗（`permissioncontroller`）、安装与卸载确认（`packageinstaller`）、虚拟屏授权框（`virtualdevicemanager`）默认被放行，模型可能直接点“允许”或“安装”。三者固定不列入应用目录，遇到时按范围规则暂停交给用户。
- 被排除的应用暂时停用或卸载时，界面和桌面保存会把它的排除抹掉，重装后变成可操作。改为保留目录外的旧排除项，界面只按已安装的计数。
- 规划前先走一步的点击用的是 Jev 决定前读到的页面，冷启动的应用一刷新点击就发不出去，并让规划后的第一步转给视觉模型（v1.47 测试 6 的日志即如此）。改为点击前重读页面、确认目标节点未变，没发出去时不记为上一步。
- Jev 判完成后同一窗口刷新，总结仍用刷新前的页面。改为完成时把当前页面加入证据。
- README 两处旧说法（“授权后的系统组件”“默认只有可启动应用”）。

未处理（不太可能碰到或影响很小）：打字下达“返回”时键盘会先吃掉返回键；回退到旧镜像期间排除名单不生效，旧镜像写库后排除名单丢失；预判比规划还慢时虚拟屏可能提前打开；暂停时仍在输出的视觉调用让后台流程多挂最多 60 秒；已关闭会话迟到的拦截回调；设置页 intent 未限定包名；任务开始时多约 40 毫秒的启动入口查询。

单元测试 214 个通过，设备测试可编译（未运行）。刷写 152 秒一次成功，开机检查正常。

| v1.52 产物 | SHA-256 |
|---|---|
| `system.img` | `45cf435d8297c6ce5d83592f01b11dfdae1006e6910b2a0886357aaf6505b925` |
| `assistant.apk` | `324bfadff91b4b653bb2f760b65b91992e8dbffc8dd4a18d5ee4bf4ab8268062` |

真机核对（18:22，静音，打字下达）：“打开和孙淼的聊天窗口”（飞书冷启动）通过，40.6 秒（规划 8.8 秒、最后一次视觉 12.1 秒，模型耗时波动）。日志为“可用应用 179 个”（182 减去固定排除的 3 个）；第 2 步由 Jev 有把握地点击，取消了排队中的视觉调用，后两次视觉请求的已保留来源里依次带有 1 条、2 条视觉观察，没有丢失；全程没有“未生效”或“上一步未能派发”。

### v1.53、v1.54：内置记忆改为 Chrome，示例城市改为北京

- v1.53：按用户要求，内置默认记忆改为“用户说「浏览器」时，一般指 Chrome 浏览器”（另一会话加的 `DefaultMemory`）。内置记忆按编号只写入一次，手机已写入过 `dao-browser`，所以改用新编号 `chrome-browser`，并在记忆页手动删掉了已写入的 Dao 那条。真机核对：刷入后记忆页第一条为 Chrome 这条（18:50 写入）。
- v1.54：首页输入框提示改为“在浏览器里搜索北京这周末的天气……”，记忆页提示改为“我住在北京朝阳区”，调试页预填指令改为“帮我查一下明天北京的天气”。单元测试里的“住在杭州”是测试数据，未改。真机核对两处提示已显示北京。

两版单元测试均为 214 个通过，刷写各约 152 秒一次成功，开机检查正常。

| 产物 | SHA-256 |
|---|---|
| v1.53 `system.img` | `507a0155d8d443e9bb1b41dd42a8e5e39c068f243577a4055c499d432415298c` |
| v1.53 `assistant.apk` | `d32721ed22ee2b34a4e9977573ab406953251d78b83308c098dd2026fa0e577f` |
| v1.54 `system.img` | `2b8576c17bb63b81f5165805175734151696c47b432f055777afb866077c1819` |
| v1.54 `assistant.apk` | `3188b793924c7a691e0af72df93d66e994ce133a655e504195f942f72365eb89` |

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
