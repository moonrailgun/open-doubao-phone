package com.openodoubao.assistant.voice

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.media.*
import android.os.*
import android.speech.tts.*
import com.openodoubao.assistant.data.Vault
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Online or separate-UID local synthesis; PCM playback belongs to our foreground service. */
class FileSpeech(private val context: Context, private val unlockPrompt: Boolean = false,
    private val online: OnlineSpeech = OnlineSpeech(), private val finished: (String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
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

    private fun speakLocal(id: String, text: String) {
        if (context.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE")
                .setPackage("com.openodoubao.tts"), 0).isEmpty()) {
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
        } }, "com.openodoubao.tts")
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
            handler.post { if (active == id) awaitPlayback(id, frames) }
        }
    }

    private fun startPlayback(sampleRate: Int, format: Int, channels: Int) {
        if (!canPlay()) { stop(); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop("系统暂不允许播报"); return }
        try {
            check(format == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && sampleRate > 0) { "不支持的语音格式" }
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, format)
            check(minimum > 0) { "无法创建播放器" }
            player = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(format)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum, sampleRate)).setTransferMode(AudioTrack.MODE_STREAM).build()
            player!!.play()
        } catch (e: Exception) { stop(e.message ?: "播放失败") }
    }

    private fun awaitPlayback(id: String, frames: Long) {
        val track = player
        if (track == null || frames == 0L) { stop("合成未返回音频"); return }
        // Synthesis completion can precede the last audible sample; only then may listening resume.
        if ((track.playbackHeadPosition.toLong() and 0xffffffffL) >= frames) stop()
        else handler.postDelayed({ if (active == id) awaitPlayback(id, frames) }, 25)
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
