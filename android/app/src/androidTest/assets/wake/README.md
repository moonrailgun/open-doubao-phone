Fixed keyword-spotting regression audio, generated with sherpa-onnx 1.13.8,
`kokoro-int8-multi-lang-v1_1`, speaker 3 (`zf_001`), speed 1, two CPU threads.
The model archive SHA-256 is
`a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6`.
Model license: `android/third_party/sherpa-onnx/licenses/Kokoro-LICENSE`.

Files 0–3 contain `豆馅你好。`, `你好豆馅。`, `豆馅同志。`, `同志你好。`
respectively. They stay fixed when the app's playback model changes; local TTS
synthesis is checked separately. These synthetic samples do not establish
real-speaker wake-word accuracy.
