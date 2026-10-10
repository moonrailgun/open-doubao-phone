# 豆馅系统集成

普通 Android 安装包继续使用 `assembleDebug`；`assembleSystem` 为系统镜像生成非 debuggable 包，版本号为 1001。只有助手的 system 变体声明 `persistent`；它必须内置在系统分区才能获得常驻行为。独立 TTS 引擎按需启动。

```sh
cd android
./gradlew :app:assembleSystem :tts:assembleSystem :app:lintSystem :tts:lintSystem :app:testSystemUnitTest
```

两个变体均使用当前开发机的 debug 签名，匹配这台开发手机已有应用。请保留这份本地签名密钥，不能换机重新生成后直接替换；密钥不纳入 Git。系统更新不得卸载旧应用或清除数据。版本号必须高于手机已安装版本；镜像构建前需核对两份系统 APK 与已有开发 APK 的证书一致。

定制镜像已在 Pixel 6 Pro 上不清数据刷入并启动，系统常驻标记、应用 UID、语音设置及原 Keystore 密钥解密能力已通过真机检查。具体镜像、证据和未完成的验收项见 [验收记录](acceptance.md)。

## 构建镜像副本

需要 Docker、Python 3.12+、FFmpeg、Pillow（测试使用，本次为 12.1.1）、已下载并通过 `os/upstream.json` 校验的原始 GSI，以及输出文件系统至少 40 GB 可用空间。先构建上述两种 APK；`prepare_payload.py` 会核对包名、签名、版本号和 Manifest，并生成开机动画。以下命令从仓库根目录执行，输出目录必须为空；可将它放在空间充足的外置磁盘。

```sh
export DOUXIAN_OUTPUT=/path/with/40GB/free/douxian-v1
python3 android/tools/prepare_voice.py --assets-only --source-bundle
python3 -B os/custom/test_boot_animation.py
curl -fL --create-dirs -o os/dao-browser-android-v0.1.9/dao-browser-0.1.9-android-arm64-v8a.apk \
  https://github.com/msgbyte/dao-browser/releases/download/android-v0.1.9/dao-browser-0.1.9-android-arm64-v8a.apk
python3 os/custom/prepare_payload.py "$DOUXIAN_OUTPUT"
cp android/.voice/voice-corresponding-source.tar.gz "$DOUXIAN_OUTPUT/"
docker build -t douxian-image-tools os/custom
docker run --rm -v "$PWD":/work:ro -w /work/os/custom \
  douxian-image-tools python3 -B test_image.py
docker run --rm -v "$PWD":/work:ro -v "$DOUXIAN_OUTPUT":/out \
  douxian-image-tools python3 -B /work/os/custom/build_image.py /out
```

配方只修改镜像副本：移除旧 AVB footer、扩容、展开真实共享块，再写入助手、TTS、Dao 浏览器三个 APK、省电豁免、默认运行时权限和开机动画，并把 product 与 system_ext 的 `build.prop` 中的设备型号由 `AOSP on ARM64` 改为 `Open Doubao`。其余原有文件及其权限、xattrs 不经过解包重打包；被改写的 `build.prop` 保留原有的 0600 权限与 SELinux 标签；新增目录为 root:root/0755，文件为 root:root/0644，并写入带 NUL 终止符的 `system_file` SELinux 标签。写入内容会从镜像读回并核对 SHA-256。随后执行文件系统检查、缩容、容量检查和 AVB hashtree 重建及验证。

输出包括 `system.img`、`payload.json`、`manifest.json` 和 `avb-info.txt`。使用无签名 AVB 元数据，只适用于当前已解锁且关闭验证的开发设备；不能重新锁定 Bootloader。容量限制使用当前 Pixel 6 Pro 的 dynamic partition group 与其他分区实际尺寸，不能直接套用到其他手机。

发布镜像或 TTS APK 时，同时提供 `voice-corresponding-source.tar.gz`。该包包含当前 Android 源码、构建脚本、原生依赖源码及许可证；每次代码变更后重新生成，不包含本地签名密钥和构建缓存。模型条款与来源见 [语音依赖说明](../../android/third_party/sherpa-onnx/README.md)。

小型 ext4 测试已覆盖文件写入、权限和 SELinux 标签、拒绝覆盖、真实共享块展开、文件内容保留、缩容、AVB 校验及容量边界。完整 GSI 也已完成文件系统、容量、AVB 和启动检查；空间不足时构建会在复制原始镜像前退出。脚本不会自动刷机。刷写前保留上一份可启动镜像，使用下方的不清数据升级命令，不要复制 `os/flash-plan.md` 中首次安装所用的 `-w` 命令。

## Dao 浏览器

镜像内置 [Dao 浏览器 Android v0.1.9](https://github.com/msgbyte/dao-browser/releases/tag/android-v0.1.9) 的 arm64 APK（`com.msgbyte.dao`，Gecko 内核），作为普通系统应用写入 `/system/app/DaoBrowser/`，不授予特权权限。`prepare_payload.py` 按发布页公布的 SHA-256 `2645fa95…0856e6` 核对文件，并要求原生库不压缩、16 KB 对齐（`extractNativeLibs=false`），因为只读系统分区上的应用不会解压原生库，只能直接从 APK 加载。APK 保留上游签名（证书 SHA-256 `eb66631c…383937`），之后可用同签名、更高版本号的 APK 原地更新。默认浏览器未改动，需在系统设置中手动切换。升级版本时同时修改脚本中的路径和摘要。

## 不清数据刷写

以下命令仅适用于上述已经验证的 Pixel 6 Pro / `b` 槽位组合。先检查当前槽位、解锁状态和无进行中的 snapshot 更新；若与此记录不同，应先核实分区布局。保留原始 GSI 和上一个可启动定制输出目录。

```sh
export DOUXIAN_SERIAL=YOUR_DEVICE_SERIAL
# DOUXIAN_OUTPUT 指向刚构建并验证的输出目录；flash 子目录必须尚不存在。
python3 - <<'PYTHON'
import os
from pathlib import Path
root = Path.cwd()
output = Path(os.environ['DOUXIAN_OUTPUT']).resolve()
flash = output / 'flash'
flash.mkdir()
for source in (root / 'os/prepared-gsi-CP3A.260905.010').iterdir():
    target = output / 'system.img' if source.name == 'system.img' else source.resolve(strict=True)
    (flash / source.name).symlink_to(target)
PYTHON
adb -s "$DOUXIAN_SERIAL" reboot bootloader
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" getvar current-slot
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" getvar unlocked
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" getvar snapshot-update-status
# 确认为 b / yes / none 后执行：
ANDROID_PRODUCT_OUT="$DOUXIAN_OUTPUT/flash" os/tools/platform-tools/fastboot \
  -s "$DOUXIAN_SERIAL" --slot=b --skip-secondary --skip-reboot --disable-verification flashall
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" reboot bootloader
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" getvar partition-size:system_b
# 与 manifest.json 的 system_bytes 一致后启动：
os/tools/platform-tools/fastboot -s "$DOUXIAN_SERIAL" reboot
```

这里不使用 `-w`，不擦除 userdata 或 metadata，不卸载旧应用，不重新锁定 Bootloader。

系统版默认授予麦克风、相机和通知权限，用户仍可在系统设置中关闭；独立安装的 APK 仍在应用内按需申请。Android 只在首次开机或系统 fingerprint 变化时应用 `default-permissions`，而本镜像沿用 GSI 的 fingerprint，所以不清数据刷写并启动后需补一次授权：

```sh
for permission in RECORD_AUDIO CAMERA POST_NOTIFICATIONS; do
  adb -s "$DOUXIAN_SERIAL" shell pm grant com.moonrailgun.opendoubao.assistant android.permission.$permission
done
```

系统设置「关于手机」中的设备名称在首次开机时按当时的型号保存，不清数据刷写后仍显示 `AOSP on ARM64`，需手动改为新型号：

```sh
adb -s "$DOUXIAN_SERIAL" shell settings put global device_name 'Open Doubao'
```

## 不清数据升级检查

`SystemUpgradeTest` 使用与目标 APK 同签名的 instrumentation APK，先在旧版应用上运行 `prepare`，刷入 v1 镜像后运行 `verify`。不传参数时跳过，避免普通测试套件创建升级基线。以下命令需明确指定目标手机，不能混用模拟器。

```sh
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class com.moonrailgun.opendoubao.assistant.SystemUpgradeTest -e upgradePhase prepare \
  com.moonrailgun.opendoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
# 不清数据刷入同签名 v1 镜像，启动后再运行：
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class com.moonrailgun.opendoubao.assistant.SystemUpgradeTest -e upgradePhase verify \
  com.moonrailgun.opendoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

检查保留的 UID、实际配置文件密文摘要、语音设置、系统应用标记及版本号，并用原有 Keystore alias 加密的独立测试配置验证解密能力。不会覆盖实际配置或输出凭据；基线保存在应用的 `no_backup/system-upgrade-check/`，重复 `prepare` 会拒绝覆盖。Instrumentation 会重启目标进程，完成后应重启手机并单独确认默认助手绑定和后台录音。

启用监听并确认麦克风实际录音后，以下检查会主动让开发手机的助手进程崩溃，要求系统在 30 秒内恢复新进程和未被静音的录音。请在没有进行中任务时运行；检查不会重新下达任务。

```sh
python3 android/tools/test_wake_recovery.py --serial "$DOUXIAN_SERIAL"
```

## 离线与麦克风竞争检查

`OfflineTaskTest` 必须显式传入 `offlineCheck=true`，并确认手机已断开所有网络；它只检查当前网络状态，不修改网络设置。测试通过真实网络请求验证失败消息和零手机操作，使用内存临时配置，不覆盖实际账户配置。

追加 `offlineUiCheck=true` 可验证真实任务服务的错误同时显示在手机页面。此项仅用于没有进行中任务、已解锁且无 PIN 的开发手机：临时写入测试模型配置并启用无障碍服务，结束后恢复原有配置密文和无障碍设置。Instrumentation 会重启目标进程，完成后须重新绑定默认助手并确认后台录音。

```sh
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class com.moonrailgun.opendoubao.assistant.OfflineTaskTest -e offlineCheck true -e offlineUiCheck true \
  com.moonrailgun.opendoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class 'com.moonrailgun.opendoubao.assistant.WakeLifecycleTest#wakeListeningSurvivesPlaybackMuteAndBackground' \
  com.moonrailgun.opendoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

生命周期测试需要已设置默认助手，会播放测试语音、切换麦克风静音，并启动独立 UID 的测试 Activity 占用麦克风十秒，检查暂停及恢复；不读取或保存音频。测试结束会关闭监听，重新开启监听并重启手机后再确认后台录音。

## 桌面远程模型配置

桌面连接已配对的手机后，点击侧栏“模型配置”，可修改 Jev 与通用模型的地址、模型名、API Key 和总结语言。保存后写入手机的加密配置，后续任务使用新配置，手机已打开的配置页也会刷新。

已有 Key 不会回传桌面；Key 留空表示保留，修改模型地址时必须重新填写对应 Key。运行或暂停中的任务需要先结束才能修改；断开连接会清空桌面表单。

## 开机动画

`boot_animation.py` 使用仓库内的 `boot-greeting.mp4`，通过 FFmpeg 生成 Android 原生 `ZIP_STORED` 归档：1440×3120 白底、居中的 512×512 头像、30 fps，按原速完整播放 4 秒（120 帧）的挥手招呼。素材为选定的 `artifacts/boot-h3-greeting-natural-4s-20261010/boot-greeting-4s.mp4` 原文件副本，SHA-256 为 `ef994545c0f03994c5eaceaf06bd467d9349d700148a0f3508b46f87b006c7bd`，构建不依赖被忽略的 artifacts 目录。

开场使用 `c 1`，即使系统提前就绪也必须完整播完才显示后续主页；播完后若系统仍在启动，则用 `p 0` 保持最后一帧，直到系统就绪。PNG 使用 `trim.txt` 定位，避免为每帧保存整块白色背景。格式遵循 [AOSP bootanimation 规范](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/bootanimation/FORMAT.md)。

动画随 payload 一起生成、校验 SHA-256，并写入 `/system/media/bootanimation.zip`，不修改 Bootloader 解锁提示。当前目标机未发现 APEX、product 或 OEM 的覆盖动画；更换系统基线后需要重新检查 [AOSP 的加载顺序](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/bootanimation/BootAnimation.cpp)。

测试覆盖归档 CRC、无压缩格式、完整播放指令、120 帧及顺序、PNG 尺寸与定位、视频首中尾帧一致性、末帧等待和拒绝覆盖。此前静态 Logo 动画已通过真机检查；这版视频动画尚未刷入手机验证。
