# 普通 Android 系统的虚拟屏

Android 14 及以上可以通过 ADB 或 Shizuku 启动受限的 shell 桥接进程，无需刷机或 Root。Open Doubao 创建独立显示器，沿用无障碍服务读取页面、截图和操作；已有自定义系统继续使用原生 ComputerControl。

在应用的「执行方式」中选择连接方式。无障碍服务仍需开启。连接成功后再选「虚拟屏」或「自动判断」。自动判断未连接时使用前台；明确选择虚拟屏后断连会报错，不会偷偷改为前台执行。

## Shizuku

1. 从 [Shizuku 官网](https://shizuku.rikka.app/download/) 安装应用。
2. 在 Shizuku 内按提示开启无线调试、配对和启动服务。
3. 回到 Open Doubao，选择「Shizuku（手机端）」并点击「授权并连接」，允许授权。
4. 状态显示已连接后开始任务。手机重启后需重新启动 Shizuku。

## ADB + scrcpy

电脑安装 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) 和 [scrcpy](https://github.com/Genymobile/scrcpy)。手机开启 USB 调试并允许电脑连接。多台设备连接时为 `adb` 指定 `-s SERIAL`，为 `scrcpy` 指定 `--serial=SERIAL`。

1. 在 Open Doubao 选择「ADB + scrcpy」。
2. 复制设置页命令，在电脑终端运行，保持终端打开：

   ```sh
   adb shell 'CLASSPATH=$(pm path com.moonrailgun.opendoubao.assistant | cut -d: -f2) app_process / com.moonrailgun.opendoubao.assistant.phone.ShellDisplayService'
   ```

3. 状态显示已连接后，选择虚拟屏并开始任务。屏幕按需创建，不会在连接时打开应用。
4. 设置页会显示实际显示器编号，例如：

   ```sh
   scrcpy --display-id=2 --no-audio --no-control
   ```

这里查看的是助手创建的屏幕，不使用 `--new-display` 另建一块屏幕。任务页和悬浮球也提供定时刷新的预览。应用更新、进程退出或手机重启后需重新执行连接命令。停止任务会释放显示器，scrcpy 随之结束。

## 使用边界

- 虚拟屏共用原手机的应用和账号。应用的单实例窗口可能从主屏转移过来，不提供账号或数据隔离。
- 锁屏、息屏、桥接进程退出会停止任务。密码和受保护页面可能无法截图，可使用「转到前台处理」。
- 厂商的额外调试限制及应用的多屏适配会影响兼容性。此实现使用 Android 14 的独立焦点能力，旧系统可继续使用前台模式。
- 桥接接口只允许助手 UID 调用，只提供创建/释放显示器、启动应用及目标屏幕的返回/主页按键，不接受任意 shell 命令。ADB 接入点仅接受 shell/root 调用。

## 模拟器验证

测试不调用在线模型。安装 debug 与 androidTest APK 后，在专用、已解锁的 Android 14 模拟器运行：

```sh
adb -s emulator-5580 shell am instrument -w \
  -e shellDisplayCheck true \
  -e class com.moonrailgun.opendoubao.assistant.ShellDisplayTest \
  com.moonrailgun.opendoubao.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用独立 UID 的测试应用，检查真实截图、文字输入、坐标点击、返回、主屏键盘焦点、`FLAG_SECURE` 截图保护、转到前台以及桥接死亡后的显示器释放。默认测试集跳过该测试，避免在日常手机上启动桥接或修改无障碍设置。

验证 Shizuku 时，先在模拟器安装并启动 Shizuku，再给上述命令增加 `-e shellBackend shizuku`。首次运行会出现授权弹窗，测试会点击允许，并检查断开后立即重连。需要配合 scrcpy 检查时增加 `-e previewCheck true`：测试输出实际显示器编号并等待 15 秒，可用上面的查看命令连接。测试仅在运行期间启用测试应用的启动入口，结束会恢复原来的入口状态、连接方式和无障碍设置。
