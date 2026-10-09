# Offline speech inputs

Kotlin JNI bindings are copied from [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api), Apache-2.0. Example model catalogs are removed; JNI-reflected configuration fields are retained. `recognition/` is used by the assistant; `tts/` by the separate Android TTS engine.

`../../tools/prepare_voice.py` records download URLs and SHA-256 hashes. It builds two ARM64 JNI libraries with NDK 28.2.13676358, one with TTS disabled and one with TTS enabled. Never use the TTS library in the assistant. The TTS binary links eSpeak NG under GPL-3.0-or-later. Its source and build inputs must accompany redistributed binaries. The separate engine communicates through Android's standard TTS Binder interface; it never plays audio itself.

## Build

Requires Python 3.12+, CMake, Android NDK 28.2.13676358 and JDK 17. From `android/`:

```sh
python3 tools/prepare_voice.py
./gradlew :app:assembleDebug :tts:assembleDebug
python3 tools/prepare_voice.py --assets-only --source-bundle
```

Use `ANDROID_NDK` to override the installed NDK path. Inputs, native build trees, APK assets, and the source bundle live in ignored `.voice/`. The bundle contains the Android source, native sherpa source and all fetched native dependency source trees. Re-export it after changing code and distribute it with the APKs. Build recipes fetch dependencies pinned by upstream CMake files; keep those files and notices intact.

## Licenses and models

- sherpa-onnx, KWS code: Apache-2.0, root `LICENSE`.
- SenseVoice weights: FunASR model license (`licenses/SenseVoice-LICENSE`), distinct from the code license.
- Kokoro v1.1 Chinese/English weights: Apache-2.0 (`licenses/Kokoro-LICENSE`). Voice 3 is `zf_001`.
- ONNX Runtime: MIT and included third-party notices.
- Native dependencies: original license texts under `licenses/*-src/`, including GPL, MPL, Apache, MIT and BSD terms. These texts are copied into both APKs.

The WenetSpeech KWS model card declares Apache-2.0 (`licenses/KWS-model-card.md`). Silero VAD uses the [upstream MIT license](https://github.com/snakers4/silero-vad/blob/master/LICENSE). The SenseVoice archive links to the [FunASR model license](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE); its full text is retained as `licenses/FunASR-MODEL_LICENSE`. Preserve the downloaded archives and notices.

Only ARM64 speech binaries are built. The regular app uses Android 11+ public APIs, runtime microphone permission and foreground services; it does not require Google services, root or a custom OS. Continuous background operation on ordinary vendor firmware is subject to that firmware's power policies.

## Wake words

`app/src/main/assets/voice/kws/keywords.txt` contains the three fixed phrases. They were generated with the pinned v1.13.8 `sherpa_onnx.utils.text2token` function, `tokens_type="ppinyin"`, `pypinyin==0.55.0` and `sentencepiece==0.2.1`. Asset preparation validates every token against the downloaded model vocabulary. `LocalVoiceTest` also feeds synthesized Chinese audio through the real keyword model and checks all three detections.

Install both APKs. In the assistant's voice settings, choose 豆馅 as the default digital assistant, grant microphone permission, and enable wake listening. Enable its accessibility service to execute tasks. Say 豆馅你好, 你好豆馅, or 豆馅同志 followed by the task; 停止/停止任务 stops it, and 继续/继续任务 resumes a paused task. The microphone button still only fills the input field. After reboot, listening starts after the first unlock. Playback pauses listening; locking the screen stops private result playback. A locked wake only asks for an unlock and discards the command. The notification's close action disables listening until enabled again.

### Device checks (Pixel 6 Pro, AOSP 17)

Unit tests, Android Lint and both APK builds passed. Instrumentation passed real Chinese ASR, all three KWS phrases using synthesized audio, file-based TTS, playback completion, microphone cancellation, background listening, mute/resume and listening after playback. A reboot with both the default assistant and default recognition service selected started microphone capture without launching the app; `dumpsys audio` reported active capture with `silenced:false`.

For ADB-only setup on a fresh GSI, setting the assistant role alone leaves the default recognition setting empty. Set `voice_recognition_service` to `com.openodoubao.assistant/.voice.LocalRecognitionService` as well before reboot testing. Normal setup uses the system voice settings page; select the local recognition service if the firmware presents a separate choice.

This GSI reports `config_supportsMicToggle=false`, so the sensor-privacy toggle could not be tested; the actual AudioManager microphone mute path was tested instead. Human voice accuracy at 1 m, locked/deep-idle acoustic wake, calls, eight-hour false positives and battery consumption remain unmeasured. These checks do not establish production readiness.
