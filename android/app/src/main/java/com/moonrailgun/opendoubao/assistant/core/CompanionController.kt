package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

data class CompanionState(val active: Boolean = false, val message: String = "", val comment: String = "",
    val retrying: Boolean = false)

data class CompanionContext(val recentComments: List<String> = emptyList(),
    val userMessages: List<String> = emptyList(), val manual: Boolean = false, val summary: String = "")

data class CompanionReply(val summary: String, val comment: String)

internal fun companionScreenAllowed(screen: Screen, ownPackage: String, excluded: Set<String>): Boolean =
    screen.packageName != ownPackage && screen.packageName !in excluded && !screen.protected

internal suspend fun captureCompanion(phone: Phone, ownPackage: String, excluded: Set<String>,
    screenshot: suspend (Screen) -> String = phone::screenshot): CompanionFrame? {
    val screen = phone.observe()
    if (!companionScreenAllowed(screen, ownPackage, excluded)) return null
    // Dynamic text is allowed; the screenshot provider still checks the app, window and geometry.
    val image = withContext(ScreenChangeOverride(true)) { screenshot(screen) }
    return CompanionFrame(screen.packageName, screen.width, screen.height, screen.rotation, image,
        windowId = screen.windowId, windowBounds = screen.windowBounds)
}

data class CompanionFrame(val packageName: String, val width: Int, val height: Int, val rotation: Int,
    val image: String, val capturedAtMillis: Long = 0, val windowId: Int = -1, val windowBounds: Bounds? = null) {
    fun sameScene(other: CompanionFrame): Boolean = packageName == other.packageName && width == other.width &&
        height == other.height && rotation == other.rotation && windowId == other.windowId && windowBounds == other.windowBounds
}

/** Sampling continues during inference; automatic opportunities are dropped while the request loop is busy. */
class CompanionController(private val scope: CoroutineScope, private val capture: suspend () -> CompanionFrame?,
    private val speak: (String) -> Unit = {}, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(CompanionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var inputs: Channel<String>? = null
    private val sessions = Mutex()
    private val sceneVersion = AtomicLong()
    private val observationVersion = AtomicLong()
    @Volatile private var samplingPaused = false

    fun invalidateScene() { sceneVersion.incrementAndGet(); invalidateFrames() }

    fun invalidateFrames() { observationVersion.incrementAndGet() }

    fun setSamplingPaused(paused: Boolean) {
        if (samplingPaused == paused) return
        samplingPaused = paused
        invalidateFrames()
    }

    fun start(intervalMillis: Long, unchanged: (suspend (CompanionFrame, List<CompanionFrame>) -> Boolean)? = null,
        comment: suspend (List<CompanionFrame>, CompanionContext) -> CompanionReply?) {
        require(intervalMillis in 1_000..300_000)
        if (mutable.value.active) return
        val requests = Channel<String>(Channel.UNLIMITED)
        inputs = requests
        mutable.value = CompanionState(active = true, message = "切到游戏或视频，开始陪伴")
        job = scope.launch { sessions.withLock { coroutineScope {
            val started = now()
            fun elapsed() = now() - started
            val frameCount = minOf(5, (intervalMillis / 1000).toInt())
            val captures = Mutex()
            val frames = ArrayDeque<CompanionFrame>()
            val recent = ArrayDeque<String>()
            val userMessages = ArrayDeque<String>()
            var previous: CompanionFrame? = null
            var summary = ""
            var scene = sceneVersion.get()
            var generation = observationVersion.get()
            var captureFailed = false
            var pending: String? = null
            var waitingForResume = false
            val observations = Channel<Unit>(Channel.RENDEZVOUS)

            fun clearVisualContext() {
                frames.clear(); previous = null; summary = ""; recent.clear()
                scene = sceneVersion.get()
                generation = observationVersion.incrementAndGet()
            }
            fun refreshScene() {
                if (scene != sceneVersion.get()) clearVisualContext()
                else if (generation != observationVersion.get()) {
                    frames.clear()
                    generation = observationVersion.get()
                }
            }
            suspend fun sample() = captures.withLock {
                refreshScene()
                if (samplingPaused) return@withLock
                val version = generation
                try {
                    val captured = capture()
                    val at = elapsed()
                    val frame = captured?.copy(capturedAtMillis = at)
                    ensureActive()
                    if (version != observationVersion.get()) {
                        refreshScene()
                        return@withLock
                    }
                    val last = frames.lastOrNull() ?: previous
                    if (frame == null || last != null && !last.sameScene(frame))
                        clearVisualContext()
                    if (frame != null) {
                        // A manual refresh replaces this second's sample instead of padding the batch.
                        if (frames.lastOrNull()?.capturedAtMillis?.div(1000) == at / 1000) frames.removeLast()
                        frames.addLast(frame)
                        while (frames.size > 5) frames.removeFirst()
                        if (waitingForResume) observations.trySend(Unit)
                    }
                    if (captureFailed) mutable.value = mutable.value.copy(retrying = false, message = "陪伴中，等待下一次观察")
                    captureFailed = false
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    ensureActive()
                    if (version != observationVersion.get()) {
                        refreshScene()
                        return@withLock
                    }
                    clearVisualContext()
                    captureFailed = true
                    mutable.value = mutable.value.copy(retrying = true,
                        message = "暂时无法查看画面，稍后重试；请收起键盘或其他浮层，并保持手机解锁")
                }
            }
            fun valid(version: Long): Boolean = !samplingPaused && generation == version && observationVersion.get() == version && frames.lastOrNull()?.let {
                elapsed() - it.capturedAtMillis <= 2000
            } == true

            launch {
                var nextObservation = intervalMillis
                while (isActive) {
                    sample()
                    val at = elapsed()
                    if (at >= nextObservation) {
                        observations.trySend(Unit)
                        nextObservation = (at / intervalMillis + 1) * intervalMillis
                    }
                    delay(1000 - elapsed() % 1000)
                }
            }

            while (isActive) {
                val input = select<String?> {
                    requests.onReceive { it }
                    observations.onReceive { null }
                }
                if (input != null) {
                    pending = input
                    userMessages.addLast(input)
                    while (userMessages.size > 8) userMessages.removeFirst()
                    sample()
                }
                data class Observation(val frames: List<CompanionFrame>, val baseline: CompanionFrame?,
                    val generation: Long, val context: CompanionContext)
                val observation = captures.withLock {
                    refreshScene()
                    val batch = frames.filter { elapsed() - it.capturedAtMillis <= 5000 }.takeLast(frameCount)
                    waitingForResume = batch.isEmpty() && pending != null && samplingPaused
                    Observation(batch,
                        previous, generation, CompanionContext(recent.toList(), userMessages.toList(), pending != null, summary))
                }
                val batch = observation.frames
                if (batch.isEmpty()) {
                    if (!captureFailed) mutable.value = mutable.value.copy(message = if (pending == null)
                        "当前页面暂不评论，安静陪伴中" else "已保留你的输入，等回到可查看的页面再回复")
                    continue
                }
                try {
                    val baseline = observation.baseline
                    // Exact equality is conservative: small subtitles and scores must reach a semantic judge.
                    val similar = pending == null && baseline != null &&
                        (batch.all { baseline.sameScene(it) && baseline.image == it.image } || unchanged?.invoke(baseline, batch) == true)
                    ensureActive()
                    if (!captures.withLock { valid(observation.generation) }) continue
                    if (similar) {
                        mutable.value = mutable.value.copy(retrying = false, message = "画面变化不大，安静陪伴中")
                        continue
                    }
                    mutable.value = mutable.value.copy(retrying = false, message = "正在看看画面…")
                    val reply = comment(batch, observation.context)
                    ensureActive()
                    captures.withLock {
                        if (reply == null || !valid(observation.generation)) {
                            mutable.value = mutable.value.copy(message = "画面已变化，稍后再试；你的输入已保留")
                        } else {
                            previous = batch.last()
                            summary = reply.summary
                            pending = null
                            mutable.value = mutable.value.copy(retrying = false, message = "陪伴中，等待下一次观察")
                            if (reply.comment.isNotBlank() && (observation.context.manual || reply.comment !in recent)) {
                                recent.addLast(reply.comment)
                                while (recent.size > 3) recent.removeFirst()
                                mutable.value = mutable.value.copy(comment = reply.comment)
                                speak(reply.comment)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    ensureActive()
                    // Never echo provider errors containing credentials or private screen content.
                    mutable.value = mutable.value.copy(retrying = true, message = "本轮未成功，稍后重试；可检查模型配置和网络")
                }
            }
        } } }
    }

    fun submit(text: String): Boolean = text.trim().takeIf { it.isNotEmpty() && mutable.value.active }
        ?.let { inputs?.trySend(it)?.isSuccess } == true

    fun stop() {
        job?.cancel()
        inputs?.cancel()
        inputs = null
        mutable.value = CompanionState()
    }
}
