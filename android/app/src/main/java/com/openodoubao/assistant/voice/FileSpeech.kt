package com.openodoubao.assistant.voice

import android.content.Context
import android.app.KeyguardManager
import android.media.*
import android.os.*
import android.speech.tts.*
import java.io.File
import java.util.Locale
import java.util.UUID

/** The separate TTS UID only writes a seekable file; playback belongs to our foreground service. */
class FileSpeech(private val context: Context, private val unlockPrompt: Boolean = false, private val finished: (String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var active: String? = null

    fun speak(text: String) {
        check(active == null)
        val id = UUID.randomUUID().toString()
        active = id
        handler.postDelayed({ if (active == id) stop("语音合成超时") }, 120_000)
        tts = TextToSpeech(context, { status -> handler.post {
            if (active != id) return@post
            if (status != TextToSpeech.SUCCESS) { stop("无法连接本地播报引擎"); return@post }
            val engine = tts ?: return@post
            if (engine.setLanguage(Locale.SIMPLIFIED_CHINESE) < 0) { stop("播报引擎不支持中文"); return@post }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { handler.post { if (active == utteranceId) play() } }
                @Deprecated("Platform callback")
                override fun onError(utteranceId: String?) { handler.post { if (active == utteranceId) stop("本地合成失败，请在播报引擎设置中重试") } }
            })
            try {
                val output = File.createTempFile("speech-", ".wav", context.cacheDir).also { file = it }
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                    if (engine.synthesizeToFile(speechText(text), Bundle(), it, id) != TextToSpeech.SUCCESS) stop("无法提交播报")
                }
            } catch (e: Exception) { stop(e.message ?: "无法保存语音") }
        } }, "com.openodoubao.tts")
    }

    private fun play() {
        val id = active ?: return
        if (!canPlay()) { stop(); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop("系统暂不允许播报"); return }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file!!.absolutePath)
                setOnCompletionListener { this@FileSpeech.stop() }
                setOnErrorListener { _, _, _ -> this@FileSpeech.stop("播放失败"); true }
                setOnPreparedListener { if (active == id && canPlay()) it.start() else this@FileSpeech.stop() }
                prepareAsync()
            }
        } catch (e: Exception) { stop(e.message ?: "播放失败") }
    }

    private fun canPlay() = unlockPrompt || (context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    fun stop(error: String? = null) {
        if (active == null) return
        active = null
        handler.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts = null
        player?.release(); player = null
        audio.abandonAudioFocusRequest(focus)
        file?.delete(); file = null
        finished(error)
    }
}
