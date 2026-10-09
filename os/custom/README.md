# 豆馅系统集成

普通 Android 安装包继续使用 `assembleDebug`；`assembleSystem` 为系统镜像生成非 debuggable 包，版本号为 1001。只有助手的 system 变体声明 `persistent`；它必须内置在系统分区才能获得常驻行为。独立 TTS 引擎按需启动。

```sh
cd android
./gradlew :app:assembleSystem :tts:assembleSystem :app:lintSystem :tts:lintSystem :app:testSystemUnitTest
```

两个变体均使用当前开发机的 debug 签名，匹配这台开发手机已有应用。请保留这份本地签名密钥，不能换机重新生成后直接替换；密钥不纳入 Git。系统更新不得卸载旧应用或清除数据。版本号必须高于手机已安装版本；镜像构建前需核对两份系统 APK 与已有开发 APK 的证书一致。

目前已验证系统变体的构建、Lint、单元测试、签名及 Manifest。尚未刷入定制系统，因此常驻标记、无数据丢失升级和 Keystore 保留仍需真机验收。
