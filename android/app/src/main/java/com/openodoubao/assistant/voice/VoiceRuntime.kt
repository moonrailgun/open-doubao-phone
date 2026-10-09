package com.openodoubao.assistant.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.openodoubao.assistant.core.TaskStatus
import com.openodoubao.assistant.phone.AssistantService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors

object VoiceRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val requests = VoiceRequests()
    private val mutableState = MutableStateFlow("空闲")
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private var context: Context? = null
    private var assistantReady = false
    private var engine: LocalSpeechEngine? = null
    private var speech: FileSpeech? = null
    private var active: Long? = null
    private var wakeJob: Job? = null
    private var taskWatcher: Job? = null
    private var ready = CompletableDeferred<Unit>()
    @Volatile private var wakeEpoch = 0L
    @Volatile private var stopRequested = false
    var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        private set

    fun serviceReady() { ready.complete(Unit) }
    // A previous service can finish stopping after the user has enabled wake listening again.
    fun serviceStopped() { interruptWake(); cancel(); startWake() }
    fun assistantReady(context: Context, ready: Boolean) {
        this.context = context.applicationContext
        assistantReady = ready
        if (!ready) interruptWake()
        refresh(context)
    }
    fun refresh(context: Context) {
        this.context = context.applicationContext
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        mutableEnabled.value = context.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("wake_enabled", false)
        startWake()
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        this.context = context.applicationContext
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putBoolean("wake_enabled", enabled).apply()
        mutableEnabled.value = enabled
        if (!enabled) interruptWake()
        else { mutableError.value = ""; startWake() }
    }

    private fun interruptWake() {
        wakeEpoch++
        wakeJob?.cancel(); wakeJob = null
        if (active == null) mutableState.value = "空闲"
    }
    private fun foreground(context: Context, type: Int, status: String) {
        foregroundType = type
        mutableState.value = status
        ready = CompletableDeferred()
        context.startForegroundService(Intent(context, VoiceService::class.java))
    }
    private fun begin(context: Context, type: Int, status: String): Long? {
        val id = requests.begin() ?: return null
        interruptWake()
        this.context = context.applicationContext
        active = id
        stopRequested = false
        mutableError.value = ""
        try { foreground(context, type, status) }
        catch (e: RuntimeException) {
            requests.finish(id); active = null; mutableState.value = "空闲"
            mutableError.value = e.message ?: "系统不允许启动语音，请打开应用后重试"
            return null
        }
        return id
    }
    private fun finish(id: Long): Boolean {
        val deliver = requests.finish(id)
        if (active == id) {
            active = null
            mutableState.value = "空闲"
            startWake()
        }
        return deliver
    }
    private fun engine(context: Context) = engine ?: LocalSpeechEngine(context.applicationContext).also { engine = it }
    private fun hasMic(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun screenAvailable(context: Context) = taskScreenAvailable(
        context.getSystemService(PowerManager::class.java).isInteractive,
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    private fun microphoneAvailable(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.mode == AudioManager.MODE_NORMAL && !audio.isMicrophoneMute
    }

    private fun startWake() {
        val context = context ?: return
        if (!assistantReady || !mutableEnabled.value || active != null || wakeJob != null || !hasMic(context) ||
            !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        if (listOf("initializing", "initializing_asr", "initializing_vad", "initializing_kws").any { prefs.getBoolean(it, false) }) {
            mutableError.value = "上次语音初始化中断，请在设置中重试"
            return
        }
        val epoch = ++wakeEpoch
        try { foreground(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "正在加载唤醒词") }
        catch (e: RuntimeException) { mutableState.value = "空闲"; mutableError.value = e.message ?: "无法启动唤醒"; return }
        wakeJob = scope.launch {
            try {
                withTimeout(5_000) { ready.await() }
                while (epoch == wakeEpoch) {
                    if (!microphoneAvailable(context)) {
                        mutableState.value = "麦克风暂停，等待恢复"
                        delay(2_000)
                        continue
                    }
                    val result = runCatching {
                        withContext(worker) {
                            engine(context).listenForWake(
                                { epoch == wakeEpoch && microphoneAvailable(context) },
                                { screenAvailable(context) },
                                { scope.launch { if (epoch == wakeEpoch) mutableState.value = "正在听，请说出任务" } },
                                { scope.launch { if (epoch == wakeEpoch) mutableState.value = "等待唤醒词" } },
                            )
                        }
                    }
                    if (epoch != wakeEpoch) return@launch
                    result.exceptionOrNull()?.let {
                        mutableError.value = it.message ?: "麦克风暂不可用"
                        mutableState.value = "麦克风暂停，等待恢复"
                        delay(2_000)
                    }
                    if (result.isFailure) continue
                    val text = result.getOrNull()
                    if (text == "") continue
                    wakeJob = null
                    if (text == null) speakUnlock(context) else dispatch(context, text)
                    startWake()
                    return@launch
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableError.value = e.message ?: "唤醒不可用" }
            finally {
                if (epoch == wakeEpoch && active == null) { wakeJob = null; mutableState.value = "空闲" }
            }
        }
    }

    fun recognize(context: Context, listener: RecognitionService.Callback): Long? {
        if (!hasMic(context)) { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return null }
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "正在加载本地语音")
            ?: run { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return null }
        scope.launch {
            val result = record(context, id) { runCatching { listener.readyForSpeech(Bundle()) } }
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
    private suspend fun record(context: Context, id: Long, onReady: () -> Unit = {}) = runCatching {
        withTimeout(5_000) { ready.await() }
        withContext(worker) {
            engine(context).recognize({ requests.accepts(id) && microphoneAvailable(context) }, { stopRequested }) {
                scope.launch { if (requests.accepts(id)) { mutableState.value = "正在听，请说话"; onReady() } }
            }
        }
    }
    fun listenForTask(context: Context) {
        if (!screenAvailable(context)) { speakUnlock(context); return }
        if (!hasMic(context)) { mutableError.value = "请先在应用中授权麦克风"; return }
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "正在加载本地语音") ?: return
        scope.launch {
            val result = record(context, id)
            if (finish(id)) result.fold({ dispatch(context, it) }, { mutableError.value = it.message ?: "识别失败" })
        }
    }
    private fun dispatch(context: Context, text: String) {
        if (!screenAvailable(context)) { speakUnlock(context); return }
        val command = parseVoiceCommand(text)
        if (command == VoiceCommand.Empty) { speak(context, "没有听清，请重新唤醒后说出任务。"); return }
        runCatching {
            val service = checkNotNull(AssistantService.connected.value) { "请先在设置中启用豆馅无障碍服务" }
            when (command) {
                VoiceCommand.Stop -> { taskWatcher?.cancel(); service.stop(); speak(context, "已停止任务。") }
                VoiceCommand.Resume -> {
                    check(service.state.value.status == TaskStatus.PAUSED) { "当前没有可继续的暂停任务" }
                    service.resume(); watchTask(context, service)
                }
                is VoiceCommand.Task -> { service.startTask(command.goal); watchTask(context, service) }
                VoiceCommand.Empty -> Unit
            }
        }.onFailure { mutableError.value = it.message ?: "无法开始任务"; speak(context, mutableError.value) }
    }
    private fun watchTask(context: Context, service: AssistantService) {
        taskWatcher?.cancel()
        taskWatcher = scope.launch {
            // Let the executor publish the new state before observing its eventual result.
            yield()
            val result = service.state.first { it.status !in setOf(TaskStatus.IDLE, TaskStatus.RUNNING) }
            if (screenAvailable(context)) speak(context, result.result.ifBlank { result.message })
        }
    }

    fun speak(context: Context, text: String) = speak(context, text, false)
    private fun speakUnlock(context: Context) = speak(context, "请先点亮并解锁手机，然后重新下达指令。", true)
    private fun speak(context: Context, text: String, unlockPrompt: Boolean): Boolean {
        if (!unlockPrompt && !screenAvailable(context)) return false
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
                speech = FileSpeech(context.applicationContext, unlockPrompt) { error ->
                    speech = null
                    if (error != null) mutableError.value = error
                    finish(id)
                }.also { it.speak(text) }
            } catch (e: Exception) { mutableError.value = e.message ?: "播报失败"; finish(id) }
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
        if (active != null || wakeJob != null) return
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().apply {
            listOf("initializing", "initializing_asr", "initializing_vad", "initializing_kws").forEach { remove(it) }
        }.apply()
        mutableError.value = ""
        refresh(context)
    }
}
