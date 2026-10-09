package com.openodoubao.assistant.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

object VoiceRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val requests = VoiceRequests()
    private val mutableState = MutableStateFlow("空闲")
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    private var engine: LocalSpeechEngine? = null
    private var speech: FileSpeech? = null
    private var active: Long? = null
    private var ready = CompletableDeferred<Unit>()
    var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        private set
    @Volatile private var stopRequested = false

    fun serviceReady() { ready.complete(Unit) }
    fun serviceStopped() { cancel() }

    private fun begin(context: Context, type: Int, status: String): Long? {
        val id = requests.begin() ?: return null
        active = id
        stopRequested = false
        mutableError.value = ""
        mutableState.value = status
        foregroundType = type
        ready = CompletableDeferred()
        try { context.startForegroundService(Intent(context, VoiceService::class.java)) }
        catch (e: RuntimeException) {
            finish(id)
            mutableError.value = e.message ?: "系统不允许启动语音，请打开应用后重试"
            return null
        }
        return id
    }

    private fun finish(id: Long): Boolean {
        val deliver = requests.finish(id)
        if (active == id) { active = null; mutableState.value = "空闲" }
        return deliver
    }

    fun recognize(context: Context, listener: RecognitionService.Callback): Long? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return null
        }
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "正在加载本地语音")
            ?: run { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return null }
        scope.launch {
            val result = runCatching {
                withTimeout(5_000) { ready.await() }
                withContext(worker) {
                    if (engine == null) {
                        val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
                        check(!prefs.getBoolean("initializing", false)) { "上次语音初始化中断，请在设置中重试" }
                        check(prefs.edit().putBoolean("initializing", true).commit())
                        try { engine = LocalSpeechEngine(context.applicationContext) }
                        finally { check(prefs.edit().putBoolean("initializing", false).commit()) }
                    }
                    engine!!.recognize({ requests.accepts(id) }, { stopRequested }) {
                        scope.launch {
                            if (requests.accepts(id)) {
                                mutableState.value = "正在听，请说话"
                                runCatching { listener.readyForSpeech(Bundle()) }
                            }
                        }
                    }
                }
            }
            if (finish(id)) runCatching {
                listener.endOfSpeech()
                result.fold(onSuccess = { text ->
                    if (text.isBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    else listener.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    })
                }, onFailure = {
                    mutableError.value = it.message ?: "本地语音不可用"
                    listener.error(SpeechRecognizer.ERROR_AUDIO)
                })
            }
        }
        return id
    }

    fun speak(context: Context, text: String): Boolean {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (context.packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE")
                .setPackage("com.openodoubao.tts"), 0).isEmpty()) {
            mutableError.value = "请先安装豆馅本地语音引擎"
            return false
        }
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, "正在播报") ?: return false
        scope.launch {
            try {
                withTimeout(5_000) { ready.await() }
                if (!requests.accepts(id)) { finish(id); return@launch }
                speech = FileSpeech(context.applicationContext) { error ->
                    speech = null
                    if (error != null) mutableError.value = error
                    finish(id)
                }.also { it.speak(text) }
            } catch (e: Exception) {
                mutableError.value = e.message ?: "播报失败"
                finish(id)
            }
        }
        return true
    }

    fun stopListening(id: Long?) { if (active == id) stopRequested = true }
    fun cancel(id: Long? = active) {
        if (id != active) return
        requests.cancel(id)
        speech?.stop()
    }
    fun retryInitialization(context: Context) {
        if (active != null) return
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().remove("initializing").apply()
        mutableError.value = ""
    }
}
