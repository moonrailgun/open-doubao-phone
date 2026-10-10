package com.openodoubao.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*

/** Native objects and the recorder are confined to VoiceRuntime's one worker. */
class LocalSpeechEngine(private val context: Context) {
    private val recognizer by lazy { initialize("asr") {
        OfflineRecognizer(context.assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = "voice/asr/model.int8.onnx"),
            tokens = "voice/asr/tokens.txt", numThreads = 2,
        )))
    } }
    private val vad by lazy { initialize("vad") {
        Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = "voice/silero_vad.onnx", minSilenceDuration = 0.8f, maxSpeechDuration = 15f,
        )))
    } }
    private val spotter by lazy { initialize("kws") { createKeywordSpotter(context) } }

    private fun <T> initialize(name: String, create: () -> T): T {
        val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val key = "initializing_$name"
        check(!prefs.getBoolean(key, false)) { "上次语音初始化中断，请在设置中重试" }
        check(prefs.edit().putBoolean(key, true).commit())
        try { return create() }
        finally { check(prefs.edit().putBoolean(key, false).commit()) }
    }

    fun recognize(keepGoing: () -> Boolean, stopRequested: () -> Boolean, ready: () -> Unit): String {
        // Warm ASR before announcing readiness, so model loading cannot discard the first words.
        recognizer
        vad.reset()
        return recording { record ->
            ready()
            capture(record, keepGoing, stopRequested)
        }
    }

    /** Keep the same recorder and VAD segment across the keyword/command boundary. */
    fun listenForWake(keepGoing: () -> Boolean, canTakeCommand: () -> Boolean, detected: () -> Unit, ready: () -> Unit): String? {
        val stream = spotter.createStream()
        try {
            vad.reset()
            return recording { record ->
                ready()
                while (keepGoing()) {
                    val samples = read(record)
                    vad.acceptWaveform(samples)
                    stream.acceptWaveform(samples, 16000)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val keyword = spotter.getResult(stream).keyword
                        if (keyword.isNotEmpty()) {
                            detected()
                            if (!canTakeCommand()) return@recording null
                            // Preserve a continuous command, then close the recorder before the wake reply.
                            return@recording wakeCommand(capture(record, keepGoing, { false })).ifBlank { keyword }
                        }
                    }
                    // Discard speech without a wake word; never submit it to the task model.
                    while (!vad.empty()) vad.pop()
                }
                ""
            }
        } finally { stream.release() }
    }

    private fun capture(record: AudioRecord, keepGoing: () -> Boolean, stopRequested: () -> Boolean): String {
        val started = SystemClock.elapsedRealtime()
        while (keepGoing() && !stopRequested() && vad.empty() && SystemClock.elapsedRealtime() - started < 20_000) {
            vad.acceptWaveform(read(record))
        }
        if (!keepGoing()) return ""
        vad.flush()
        if (vad.empty()) return ""
        return decode(vad.front().samples)
    }

    private fun read(record: AudioRecord): FloatArray {
        check(record.activeRecordingConfiguration?.isClientSilenced != true) { "麦克风被系统暂停" }
        val buffer = ShortArray(512)
        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        check(count > 0) { "录音中断（$count）" }
        return FloatArray(count) { buffer[it] / 32768f }
    }

    @SuppressLint("MissingPermission") // Runtime gates all entry points before opening the recorder.
    private fun <T> recording(block: (AudioRecord) -> T): T {
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(4096, AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
            return block(record)
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }

    fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
}

internal fun createKeywordSpotter(context: Context) = KeywordSpotter(context.assets,
    KeywordSpotterConfig(modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoder = "voice/kws/encoder.onnx",
            decoder = "voice/kws/decoder.onnx", joiner = "voice/kws/joiner.onnx"),
        tokens = "voice/kws/tokens.txt", numThreads = 1,
    ), keywordsFile = "voice/kws/keywords.txt"))
