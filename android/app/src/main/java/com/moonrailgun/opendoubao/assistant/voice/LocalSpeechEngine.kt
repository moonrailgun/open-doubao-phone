package com.moonrailgun.opendoubao.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.sqrt

/**
 * Native objects and the recorder are confined to VoiceRuntime's one worker, except live captions: they decode on
 * [captioner] while the worker keeps reading, and the worker waits for the last one before it touches the recognizer again.
 */
class LocalSpeechEngine(private val context: Context) {
    private val captioner = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { initialize("asr") {
        OfflineRecognizer(context.assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = "voice/asr/model.int8.onnx"),
            tokens = "voice/asr/tokens.txt", numThreads = 2,
        )))
    } }
    private val vad by lazy { initialize("vad") {
        Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = "voice/silero_vad.onnx", minSilenceDuration = 2f, maxSpeechDuration = 15f,
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

    /** [level] gets the microphone level per read and [partial] the words heard so far, both on this worker. */
    fun recognize(keepGoing: () -> Boolean, stopRequested: () -> Boolean, level: (Float) -> Unit = {},
                  partial: (String) -> Unit = {}, ready: () -> Unit): String {
        // Warm ASR before announcing readiness, so model loading cannot discard the first words.
        recognizer
        vad.reset()
        return recording { record ->
            ready()
            capture(record, keepGoing, stopRequested, level, partial)
        }
    }

    /** Returns the keyword as soon as it is heard, null when it may not take a command, or "" when stopped. */
    fun listenForWake(keepGoing: () -> Boolean, canTakeCommand: () -> Boolean, ready: () -> Unit): String? {
        val stream = spotter.createStream()
        try {
            return recording { record ->
                ready()
                while (keepGoing()) {
                    stream.acceptWaveform(read(record), 16000)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val keyword = spotter.getResult(stream).keyword
                        if (keyword.isNotEmpty()) return@recording keyword.takeIf { canTakeCommand() }
                    }
                }
                ""
            }
        } finally { stream.release() }
    }

    private fun capture(record: AudioRecord, keepGoing: () -> Boolean, stopRequested: () -> Boolean,
                        level: (Float) -> Unit, partial: (String) -> Unit): String {
        val started = SystemClock.elapsedRealtime()
        // Simulated streaming: once speech starts, everything heard so far is decoded again for live captions, each pass
        // taking the newest audio as soon as the previous one ends. Decoding runs on its own thread, so reading, the VAD
        // and the level never pause for the model; an inline decode froze the lines exactly while the user spoke.
        // ponytail: each pass re-decodes the whole utterance, so long ones refresh slower; decode only the tail if that shows.
        val heard = FloatArray(16000 * 21)
        var size = 0; var start = -1; var decoded = 0; var caption = ""
        var inflight: Future<*>? = null
        try {
            while (keepGoing() && !stopRequested() && vad.empty() && SystemClock.elapsedRealtime() - started < 20_000) {
                val samples = read(record)
                level(voiceLevel(samples))
                vad.acceptWaveform(samples)
                if (size + samples.size <= heard.size) { samples.copyInto(heard, size); size += samples.size }
                // Start 0.4 s before detection, so the first syllable is in the captions.
                if (start < 0 && vad.isSpeechDetected()) start = maxOf(0, size - 6400)
                if (start >= 0 && size - decoded >= 4800 && inflight?.isDone != false) {
                    decoded = size
                    val snapshot = heard.copyOfRange(start, size)
                    inflight = captioner.submit {
                        val begun = SystemClock.elapsedRealtime()
                        val text = decode(snapshot)
                        Log.i("LocalSpeech", "caption decode ${snapshot.size / 16000f} s audio in ${SystemClock.elapsedRealtime() - begun} ms")
                        if (text.isNotEmpty() && text != caption) { caption = text; partial(text) }
                    }
                }
            }
        } finally {
            // The last caption lands before the final result or the next session, so stale words never overwrite them.
            runCatching { inflight?.get() }
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
            // Two seconds of headroom: a caption decode blocks this thread for up to a few hundred milliseconds.
            .setBufferSizeInBytes(maxOf(64_000, AudioRecord.getMinBufferSize(16000,
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

// ponytail: RMS × 6 then a square root puts ordinary speech near the phone around 0.4–0.9; re-tune VOICE_GAIN on real voices.
internal const val VOICE_GAIN = 6f

/** Loudness of one read as 0..1 for the voice card's lines. */
internal fun voiceLevel(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
    return sqrt((rms * VOICE_GAIN).coerceIn(0f, 1f))
}

internal fun createKeywordSpotter(context: Context) = KeywordSpotter(context.assets,
    KeywordSpotterConfig(modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(encoder = "voice/kws/encoder.onnx",
            decoder = "voice/kws/decoder.onnx", joiner = "voice/kws/joiner.onnx"),
        tokens = "voice/kws/tokens.txt", numThreads = 1,
    ), keywordsFile = "voice/kws/keywords.txt",
    // ponytail: on 72 synthetic wake clips 4 paths caught 17 and 8 catch 40, still 0 hits in 10 min of ordinary speech;
    // score 2.0 reached 51 but woke on ordinary speech, thresholds changed nothing. Re-tune on real voices.
    maxActivePaths = 8))
