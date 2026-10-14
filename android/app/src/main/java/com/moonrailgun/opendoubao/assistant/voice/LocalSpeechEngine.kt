package com.moonrailgun.opendoubao.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.moonrailgun.opendoubao.assistant.core.Diagnostics
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

    /** Loads the recognizer and the VAD now (about 3 s for the 239 MB model), so the first command after boot is heard right after the prompt. */
    fun warm() { recognizer; vad }

    /** [level] gets the microphone level per read on this worker; [partial], when given, the words heard so far on [captioner]. */
    fun recognize(keepGoing: () -> Boolean, stopRequested: () -> Boolean, level: (Float) -> Unit = {},
                  partial: ((String) -> Unit)? = null, source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                  ready: () -> Unit): String {
        // Warm ASR before announcing readiness, so model loading cannot discard the first words.
        recognizer
        vad.reset()
        return recording(source) { record ->
            ready()
            capture(record, keepGoing, stopRequested, level, partial)
        }
    }

    /** Returns the keyword as soon as it is heard, null when it may not take a command, or "" when stopped. */
    fun listenForWake(keepGoing: () -> Boolean, canTakeCommand: () -> Boolean, ready: () -> Unit): String? {
        val stream = spotter.createStream()
        try {
            return recording(HOTWORD_SOURCE) { record ->
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
                        level: (Float) -> Unit, partial: ((String) -> Unit)?): String {
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
                if (partial != null && start >= 0 && size - decoded >= 4800 && inflight?.isDone != false) {
                    decoded = size
                    val snapshot = heard.copyOfRange(start, size)
                    inflight = captioner.submit {
                        val begun = SystemClock.elapsedRealtime()
                        val text = mergeCaption(caption, decode(snapshot))
                        val took = SystemClock.elapsedRealtime() - begun
                        Log.i("LocalSpeech", "caption decode ${snapshot.size / 16000f} s audio in $took ms")
                        Diagnostics.log("字幕解码 ${snapshot.size / 16000f} s 音频，$took ms")
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
    private fun open(source: Int): AudioRecord? {
        // Two seconds of headroom: a caption decode blocks this thread for up to a few hundred milliseconds. The old
        // constructor is used on purpose: unlike the Builder it accepts the hidden HOTWORD source.
        val buffer = maxOf(64_000, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val record = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) { record.release(); return null }
        return record
    }

    /**
     * Wake listening and the assistant's own commands record as a hotword client ([HOTWORD_SOURCE]): the system counts
     * that under RECORD_AUDIO_HOTWORD, which the status bar's microphone dot ignores. Commands must: the audio policy
     * leaves accessibility services out of its "top or latest" capture, and as the assistant this app may then record
     * VOICE_RECOGNITION only while an accessibility app is on top, which a persistent process never counts as, so every
     * command was silenced. Builds without CAPTURE_AUDIO_HOTWORD cannot open that source and fall back to ordinary capture.
     */
    private fun <T> recording(source: Int, block: (AudioRecord) -> T): T {
        val record = open(source)
            ?: (if (source == HOTWORD_SOURCE) open(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                ?.also { Diagnostics.log("热词音源不可用，改用普通录音") } else null)
            ?: throw IllegalStateException("麦克风不可用")
        try {
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

/**
 * Each caption pass decodes the whole utterance again, and the edges drift: trailing punctuation comes and goes and the
 * first syllable can drop out of one pass, which made the typed line collapse and retype. Drop the punctuation and keep
 * the previous head when the new text only lost a character or two at the front.
 */
internal fun mergeCaption(previous: String, decoded: String): String {
    val text = decoded.trimEnd { it in "，。！？、,.!?" }
    if (text.startsWith(previous)) return text
    // ponytail: a dropped first syllable and a corrected one look the same here, so a wrong head can stick until the final
    // whole-segment decode replaces the line; vote across passes if that shows.
    for (drop in 1..2) if (previous.length > drop && text.startsWith(previous.drop(drop))) return previous.take(drop) + text
    return text
}

/** MediaRecorder.AudioSource.HOTWORD, hidden from the SDK; the image grants the CAPTURE_AUDIO_HOTWORD it needs. */
internal const val HOTWORD_SOURCE = 1999

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
