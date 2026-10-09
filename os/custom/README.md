# 豆馅系统集成

普通 Android 安装包继续使用 `assembleDebug`；`assembleSystem` 为系统镜像生成非 debuggable 包，版本号为 1001。只有助手的 system 变体声明 `persistent`；它必须内置在系统分区才能获得常驻行为。独立 TTS 引擎按需启动。

```sh
cd android
./gradlew :app:assembleSystem :tts:assembleSystem :app:lintSystem :tts:lintSystem :app:testSystemUnitTest
```

两个变体均使用当前开发机的 debug 签名，匹配这台开发手机已有应用。请保留这份本地签名密钥，不能换机重新生成后直接替换；密钥不纳入 Git。系统更新不得卸载旧应用或清除数据。版本号必须高于手机已安装版本；镜像构建前需核对两份系统 APK 与已有开发 APK 的证书一致。

目前已验证系统变体的构建、Lint、单元测试、签名及 Manifest。尚未刷入定制系统，因此常驻标记、无数据丢失升级和 Keystore 保留仍需真机验收。

## 构建镜像副本

需要 Docker、Python 3.12+、Pillow（本次使用 12.1.1）、已下载并通过 `os/upstream.json` 校验的原始 GSI，以及输出文件系统至少 40 GB 可用空间。先构建上述两种 APK；`prepare_payload.py` 会核对包名、签名、版本号和 Manifest，并生成开机动画。以下命令从仓库根目录执行，输出目录必须为空；可将它放在空间充足的外置磁盘。

```sh
export DOUXIAN_OUTPUT=/path/with/40GB/free/douxian-v1
python3 android/tools/prepare_voice.py --assets-only --source-bundle
python3 -B os/custom/test_boot_animation.py
python3 os/custom/prepare_payload.py "$DOUXIAN_OUTPUT"
cp android/.voice/voice-corresponding-source.tar.gz "$DOUXIAN_OUTPUT/"
docker build -t douxian-image-tools os/custom
docker run --rm -v "$PWD":/work:ro -w /work/os/custom \
  douxian-image-tools python3 -B test_image.py
docker run --rm -v "$PWD":/work:ro -v "$DOUXIAN_OUTPUT":/out \
  douxian-image-tools python3 -B /work/os/custom/build_image.py /out
```

配方只修改镜像副本：移除旧 AVB footer、扩容、展开真实共享块，再写入两个 APK、省电豁免和开机动画。原有文件及其权限、xattrs 不经过解包重打包；新增目录为 root:root/0755，文件为 root:root/0644，并写入带 NUL 终止符的 `system_file` SELinux 标签。写入内容会从镜像读回并核对 SHA-256。随后执行文件系统检查、缩容、容量检查和 AVB hashtree 重建及验证。

输出包括 `system.img`、`payload.json`、`manifest.json` 和 `avb-info.txt`。使用无签名 AVB 元数据，只适用于当前已解锁且关闭验证的开发设备；不能重新锁定 Bootloader。容量限制使用当前 Pixel 6 Pro 的 dynamic partition group 与其他分区实际尺寸，不能直接套用到其他手机。

发布镜像或 TTS APK 时，同时提供 `voice-corresponding-source.tar.gz`。该包包含当前 Android 源码、构建脚本、原生依赖源码及许可证；每次代码变更后重新生成，不包含本地签名密钥和构建缓存。模型条款与来源见 [语音依赖说明](../../android/third_party/sherpa-onnx/README.md)。

小型 ext4 测试已覆盖文件写入、权限和 SELinux 标签、拒绝覆盖、真实共享块展开、文件内容保留、缩容、AVB 校验及容量边界。完整 GSI 的尺寸、启动、SELinux AVC 和数据保留尚未验证；空间不足时构建会在复制原始镜像前退出。脚本不会自动刷机。刷写前保留上一份可启动镜像，沿用 `os/flash-plan.md` 的已验证路径，升级不得使用 `-w`、清数据或卸载应用。

## 不清数据升级检查

`SystemUpgradeTest` 使用与目标 APK 同签名的 instrumentation APK，先在旧版应用上运行 `prepare`，刷入 v1 镜像后运行 `verify`。不传参数时跳过，避免普通测试套件创建升级基线。以下命令需明确指定目标手机，不能混用模拟器。

```sh
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class com.openodoubao.assistant.SystemUpgradeTest -e upgradePhase prepare \
  com.openodoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
# 不清数据刷入同签名 v1 镜像，启动后再运行：
adb -s "$DOUXIAN_SERIAL" shell am instrument -w \
  -e class com.openodoubao.assistant.SystemUpgradeTest -e upgradePhase verify \
  com.openodoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

检查保留的 UID、实际配置文件密文摘要、语音设置、系统应用标记及版本号，并用原有 Keystore alias 加密的独立测试配置验证解密能力。不会覆盖实际配置或输出凭据；基线保存在应用的 `no_backup/system-upgrade-check/`，重复 `prepare` 会拒绝覆盖。Instrumentation 会重启目标进程，完成后应重启手机并单独确认默认助手绑定和后台录音。

## 开机动画

`boot_animation.py` 直接使用现有应用 Logo，生成 Android 原生 `ZIP_STORED` 归档：1440×3120 黑底、居中的 512×512 Logo、30 fps，先淡入 0.8 秒，再循环 2 秒的轻微亮度变化，系统就绪时可立即结束。PNG 使用 `trim.txt` 定位，避免为每帧保存整块黑色背景。格式遵循 [AOSP bootanimation 规范](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/bootanimation/FORMAT.md)。

动画随 payload 一起生成、校验 SHA-256，并写入 `/system/media/bootanimation.zip`，不修改 Bootloader 解锁提示。当前目标机未发现 APEX、product 或 OEM 的覆盖动画；更换系统基线后需要重新检查 [AOSP 的加载顺序](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/bootanimation/BootAnimation.cpp)。

已验证归档 CRC、无压缩格式、帧数与顺序、PNG 尺寸、定位及原 Logo 保留，并检查了合成预览。实际开机播放仍需刷入镜像后验收。
