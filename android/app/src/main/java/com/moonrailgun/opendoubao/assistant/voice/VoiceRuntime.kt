package com.moonrailgun.opendoubao.assistant.voice

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
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** What the voice card shows: the words heard so far, the microphone level, and a hint until words arrive. */
data class Listening(val text: String = "", val level: Float = 0f, val hearing: Boolean = false, val hint: String = "我在，请说出任务")

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
    private val mutableActive = MutableStateFlow<Long?>(null)
    private var active: Long?
        get() = mutableActive.value
        set(value) { mutableActive.value = value }
    private val mutableListening = MutableStateFlow<Listening?>(null)
    /** Non-null from the wake word until the spoken command is dispatched, given up or cancelled. */
    val listening = mutableListening.asStateFlow()
    /** True while a request listens, speaks or loads for one; waiting for the wake word does not count. */
    val conversing = mutableActive.map { it != null }
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
        active = id
        interruptWake()
        this.context = context.applicationContext
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
    private fun finish(id: Long, after: (() -> Unit)? = null): Boolean {
        val deliver = requests.finish(id)
        if (active == id) {
            active = null
            if (deliver) after?.invoke()
            if (active == null) mutableState.value = "空闲"
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
                    if (text == null) speakUnlock(context) else respondToWake(context)
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
    private suspend fun record(context: Context, id: Long, level: (Float) -> Unit = {}, partial: ((String) -> Unit)? = null,
                               onReady: () -> Unit = {}) = runCatching {
        withTimeout(5_000) { ready.await() }
        withContext(worker) {
            engine(context).recognize({ requests.accepts(id) && microphoneAvailable(context) }, { stopRequested }, level, partial) {
                scope.launch { if (requests.accepts(id)) { mutableState.value = "正在听，请说话"; onReady() } }
            }
        }
    }
    fun listenForTask(context: Context, retry: Boolean = true) {
        if (!screenAvailable(context)) { mutableListening.value = null; speakUnlock(context); return }
        if (!hasMic(context)) { mutableListening.value = null; mutableError.value = "请先在应用中授权麦克风"; return }
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "正在加载本地语音")
            ?: run { mutableListening.value = null; return }
        // The assist gesture listens without a wake word, so the card may open here.
        if (mutableListening.value == null) mutableListening.value = Listening()
        scope.launch {
            // The engine reports from its worker; update {} keeps a card that was just closed from reopening.
            val result = record(context, id, level = { level -> mutableListening.update { it?.copy(level = level) } },
                partial = { text -> mutableListening.update { it?.copy(text = text) } }) {
                mutableListening.update { it?.copy(hearing = true, hint = "正在听…") }
            }
            if (finish(id)) result.fold({ text ->
                mutableListening.update { it?.copy(text = text, level = 0f, hearing = false) }
                dispatch(context, text, retry)
            }, { mutableListening.value = null; mutableError.value = it.message ?: "识别失败" })
            else mutableListening.value = null
        }
    }
    /** Answers the wake word at once with a bundled prompt, then listens for the task. */
    internal fun respondToWake(context: Context) {
        mutableListening.value = Listening()
        if (!speak(context, "我在", false, "voice/prompts/wake-${(1..3).random()}.wav") { listenForTask(context) }) mutableListening.value = null
    }
    /** Leaves the final words on the card briefly, so the sentence can be read before it folds away. */
    private fun closeCard() {
        val shown = mutableListening.value ?: return
        scope.launch { delay(1_200); mutableListening.compareAndSet(shown, null) }
    }
    private fun dispatch(context: Context, text: String, retry: Boolean) {
        if (!screenAvailable(context)) { mutableListening.value = null; speakUnlock(context); return }
        val command = parseVoiceCommand(text)
        if (command == VoiceCommand.Empty) {
            // Ask once more, then give up so an empty room cannot keep the microphone open.
            if (retry) {
                mutableListening.value = Listening(hint = "没有听清，请再说一遍")
                if (!speak(context, "我没有听清楚，能再说一遍么", false, "voice/prompts/retry.wav") { listenForTask(context, false) }) mutableListening.value = null
            } else { mutableListening.value = null; speak(context, "没有听清，请重新唤醒后说出任务。") }
            return
        }
        closeCard()
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
            if (!screenAvailable(context)) return@launch
            // Speak a short retelling of the summary; a slow or failed rewrite falls back to the summary itself.
            val spoken = result.result.takeIf { it.isNotBlank() }?.let { summary ->
                try { withTimeoutOrNull(15_000) { CloudModels(Vault(context).read().config).spoken(result.goal, summary) } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            if (screenAvailable(context)) speak(context, spoken ?: result.result.ifBlank { result.message })
        }
    }

    fun speak(context: Context, text: String) = speak(context, text, false)
    private fun speakUnlock(context: Context) = speak(context, "请先点亮并解锁手机，然后重新下达指令。", true)
    private fun speak(context: Context, text: String, unlockPrompt: Boolean, clip: String? = null, after: (() -> Unit)? = null): Boolean {
        if (!unlockPrompt && !screenAvailable(context)) return false
        val id = begin(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, "正在播报") ?: return false
        scope.launch {
            try {
                withTimeout(5_000) { ready.await() }
                if (!requests.accepts(id)) { finish(id); return@launch }
                speech = FileSpeech(context.applicationContext, unlockPrompt) { error ->
                    speech = null
                    if (error != null) mutableError.value = error
                    finish(id, if (error == null) after else null)
                }
                if (clip != null) speech?.play(clip) else speech?.speak(text)
            } catch (e: Exception) { mutableError.value = e.message ?: "播报失败"; finish(id) }
        }
        return true
    }
    fun stopListening(id: Long?) { if (active == id) stopRequested = true }
    fun cancel(id: Long? = active) {
        if (id != active) return
        mutableListening.value = null
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
