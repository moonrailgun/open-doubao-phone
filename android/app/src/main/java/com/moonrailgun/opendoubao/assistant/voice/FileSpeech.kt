package com.moonrailgun.opendoubao.assistant.voice

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.media.*
import android.os.*
import android.speech.tts.*
import com.moonrailgun.opendoubao.assistant.data.Vault
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** How speech plays; its volume stream is what the debug page reports. Lazy, so JVM tests of [wavPcm] load this file. */
internal val speechAttributes: AudioAttributes by lazy {
    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
}

/** Online or separate-UID local synthesis; PCM playback belongs to our foreground service. */
class FileSpeech(private val context: Context, private val unlockPrompt: Boolean = false,
    private val online: OnlineSpeech = OnlineSpeech(), private val finished: (String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val playback = Executors.newSingleThreadExecutor()
    private var player: AudioTrack? = null
    private var writtenFrames = 0L // Only accessed on the playback worker.
    private var file: File? = null
    @Volatile private var active: String? = null

    fun speak(text: String) {
        check(active == null)
        val id = UUID.randomUUID().toString()
        active = id
        handler.postDelayed({ if (active == id) stop("语音合成超时") }, 120_000)
        val spoken = speechText(text)
        val settings = try { if (unlockPrompt) null else Vault(context).read() }
            catch (e: Exception) { stop(e.message ?: "无法读取播报设置"); return }
        if (settings == null || settings.onlineTtsToken.isBlank() || settings.onlineTtsEndpoint.isBlank()) {
            speakLocal(id, spoken); return
        }
        scope.launch {
            var receivedAudio = false
            try {
                online.stream(settings.onlineTtsEndpoint, settings.onlineTtsToken, spoken,
                    model = settings.onlineTtsModel, voice = settings.onlineTtsVoice) { buffer -> handler.post {
                    if (active != id) return@post
                    if (!receivedAudio) {
                        receivedAudio = true
                        startPlayback(24_000, AudioFormat.ENCODING_PCM_16BIT, 1)
                    }
                    if (active == id) queueAudio(id, buffer)
                } }
                if (active == id) completeAudio(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (active == id) {
                    // Once speech has started, falling back would repeat private results from the beginning.
                    if (receivedAudio) stop(e.message ?: "在线播报中断") else speakLocal(id, spoken)
                }
            }
        }
    }

    /** Plays a bundled mono PCM16 WAV prompt; no synthesis, so it starts immediately. */
    fun play(asset: String) {
        check(active == null)
        val id = UUID.randomUUID().toString()
        active = id
        val (rate, pcm) = runCatching { wavPcm(context.assets.open(asset).use { it.readBytes() }) }
            .getOrElse { stop("语音提示 $asset 不可用：${it.message}"); return }
        // A streaming track starts only once its buffer holds the start threshold, by default the whole buffer, so a
        // prompt shorter than the 0.5 s buffer never played. A buffer the size of the clip is full after the one write;
        // completeAudio() also lowers the threshold to what was written, for systems that support it.
        startPlayback(rate, AudioFormat.ENCODING_PCM_16BIT, 1, pcm.size)
        handler.postDelayed({ if (active == id) stop("语音提示播放超时") }, pcm.size * 1000L / (2 * rate) + 5_000)
        if (active == id) { queueAudio(id, pcm); completeAudio(id) }
    }

    private fun speakLocal(id: String, text: String) {
        if (context.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE")
                .setPackage("com.moonrailgun.opendoubao.tts"), 0).isEmpty()) {
            stop("请先安装豆馅本地语音引擎"); return
        }
        tts = TextToSpeech(context, { status -> handler.post {
            if (active != id) return@post
            if (status != TextToSpeech.SUCCESS) { stop("无法连接本地播报引擎"); return@post }
            val engine = tts ?: return@post
            if (engine.setLanguage(Locale.SIMPLIFIED_CHINESE) < 0) { stop("播报引擎不支持中文"); return@post }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    handler.post { if (active == id && utteranceId == id) startPlayback(sampleRateInHz, audioFormat, channelCount) }
                }
                override fun onAudioAvailable(utteranceId: String?, buffer: ByteArray) {
                    handler.post {
                        if (active != id || utteranceId != id) return@post
                        queueAudio(id, buffer)
                    }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (active != id || utteranceId != id) return@post
                        completeAudio(id)
                    }
                }
                @Deprecated("Platform callback")
                override fun onError(utteranceId: String?) { handler.post { if (active == utteranceId) stop("本地合成失败，请在播报引擎设置中重试") } }
            })
            try {
                val output = File.createTempFile("speech-", ".wav", context.cacheDir).also { file = it }
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                    if (engine.synthesizeToFile(text, Bundle(), it, id) != TextToSpeech.SUCCESS) stop("无法提交播报")
                }
            } catch (e: Exception) { stop(e.message ?: "无法保存语音") }
        } }, "com.moonrailgun.opendoubao.tts")
    }

    private fun queueAudio(id: String, buffer: ByteArray) {
        val track = player ?: return
        playback.execute {
            try {
                var offset = 0
                while (active == id && offset < buffer.size) {
                    val count = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "播放失败" }
                    offset += count
                    writtenFrames += count / 2
                }
            } catch (e: Exception) { handler.post { if (active == id) stop(e.message ?: "播放失败") } }
        }
    }

    private fun completeAudio(id: String) {
        playback.execute {
            val frames = writtenFrames
            // Everything is written: let a short clip or reply start even though it never filled the buffer. Lowering the
            // threshold also restarts a track AudioFlinger disabled after waiting for data.
            if (Build.VERSION.SDK_INT >= 31) player?.let { track ->
                runCatching { track.setStartThresholdInFrames(frames.coerceIn(1, track.bufferSizeInFrames.toLong()).toInt()) }
            }
            handler.post { if (active == id) awaitPlayback(id, frames, -1, SystemClock.uptimeMillis()) }
        }
    }

    private fun startPlayback(sampleRate: Int, format: Int, channels: Int, bufferBytes: Int = sampleRate) {
        if (!canPlay()) { stop(); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop("系统暂不允许播报"); return }
        try {
            check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && sampleRate > 0) { "不支持的语音格式" }
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, format)
            check(minimum > 0) { "无法创建播放器" }
            player = AudioTrack.Builder().setAudioAttributes(speechAttributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(format)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum, bufferBytes)).setTransferMode(AudioTrack.MODE_STREAM).build()
            player!!.play()
        } catch (e: Exception) { stop(e.message ?: "播放失败") }
    }

    /** Polls until every written frame played; [last] and [since] track how long the head position has stood still. */
    private fun awaitPlayback(id: String, frames: Long, last: Long, since: Long) {
        val track = player
        if (track == null || frames == 0L) { stop("合成未返回音频"); return }
        // Synthesis completion can precede the last audible sample; only then may listening resume. With everything
        // written, a head position that stops short of the total means the mixer left a final fragment shorter than one
        // period unplayed (it does so while another app's audio is ducked under ours) or the track never started.
        val position = track.playbackHeadPosition.toLong() and 0xffffffffL
        val now = SystemClock.uptimeMillis()
        when {
            position >= frames -> stop()
            position != last -> handler.postDelayed({ if (active == id) awaitPlayback(id, frames, position, now) }, 25)
            now - since < 1_000 -> handler.postDelayed({ if (active == id) awaitPlayback(id, frames, last, since) }, 25)
            position == 0L -> stop("播放未开始")
            else -> stop()
        }
    }

    private fun canPlay() = unlockPrompt || (context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    fun stop(error: String? = null) {
        if (active == null) return
        active = null
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts = null
        player?.let { track ->
            // Pause unblocks an in-flight write before the worker releases the track.
            track.pause(); track.flush()
            playback.execute { track.release() }
        }
        player = null
        playback.shutdown()
        audio.abandonAudioFocusRequest(focus)
        file?.delete(); file = null
        finished(error)
    }
}

/** The sample rate and samples of a mono PCM16 WAV file. */
internal fun wavPcm(wav: ByteArray): Pair<Int, ByteArray> {
    fun tag(at: Int) = String(wav, at, 4, Charsets.US_ASCII)
    val bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    require(wav.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "不是 WAV 文件" }
    var offset = 12
    var rate = 0
    while (offset + 8 <= wav.size) {
        val size = bytes.getInt(offset + 4)
        val body = offset + 8
        require(size >= 0 && size <= wav.size - body) { "WAV 数据不完整" }
        when (tag(offset)) {
            "fmt " -> {
                require(size >= 16 && bytes.getShort(body).toInt() == 1 && bytes.getShort(body + 2).toInt() == 1 &&
                    bytes.getShort(body + 14).toInt() == 16) { "只支持单声道 16 位 PCM" }
                rate = bytes.getInt(body + 4)
            }
            "data" -> {
                require(rate > 0) { "缺少 WAV 格式信息" }
                return rate to wav.copyOfRange(body, body + size)
            }
        }
        offset = body + size + (size and 1) // Chunks are padded to even sizes.
    }
    throw IllegalArgumentException("缺少 WAV 音频数据")
}
