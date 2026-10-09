package com.openodoubao.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*

/** Native objects and the recorder are confined to VoiceRuntime's one worker. */
class LocalSpeechEngine(context: Context) {
    private val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = "voice/asr/model.int8.onnx"),
            tokens = "voice/asr/tokens.txt", numThreads = 2,
        ),
    ))
    private val vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
        model = "voice/silero_vad.onnx", minSilenceDuration = 0.8f, maxSpeechDuration = 15f,
    )))

    @SuppressLint("MissingPermission") // The entry point checks RECORD_AUDIO before starting this worker.
    fun recognize(keepGoing: () -> Boolean, stopRequested: () -> Boolean, ready: () -> Unit): String {
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(4096, AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }
            vad.reset()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
            ready()
            val buffer = ShortArray(512)
            val started = SystemClock.elapsedRealtime()
            while (keepGoing() && !stopRequested() && SystemClock.elapsedRealtime() - started < 20_000) {
                check(record.activeRecordingConfiguration?.isClientSilenced != true) { "麦克风被系统暂停" }
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                check(count > 0) { "录音中断（$count）" }
                vad.acceptWaveform(FloatArray(count) { buffer[it] / 32768f })
                if (!vad.empty()) break
            }
            if (!keepGoing()) return ""
            vad.flush()
            if (vad.empty()) return ""
            return decode(vad.front().samples)
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
