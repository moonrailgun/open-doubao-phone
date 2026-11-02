package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class CompanionState(val active: Boolean = false, val message: String = "", val comment: String = "")

internal fun companionScreenAllowed(screen: Screen, ownPackage: String, excluded: Set<String>): Boolean =
    screen.packageName != ownPackage && screen.packageName !in excluded && !screen.protected

internal suspend fun captureCompanion(phone: Phone, ownPackage: String, excluded: Set<String>,
    pixels: suspend (String) -> IntArray): CompanionFrame? {
    val screen = phone.observe()
    if (!companionScreenAllowed(screen, ownPackage, excluded)) return null
    // Games and videos may update text mid-capture; still require the same app, window and safe geometry.
    val image = withContext(ScreenChangeOverride(true)) { phone.screenshot(screen) }
    return CompanionFrame(screen.packageName, screen.width, screen.height, screen.rotation, image, pixels(image))
}

data class CompanionFrame(val packageName: String, val width: Int, val height: Int, val rotation: Int,
    val image: String, val pixels: IntArray) {
    fun similarTo(other: CompanionFrame): Boolean {
        if (packageName != other.packageName || width != other.width || height != other.height || rotation != other.rotation ||
            pixels.isEmpty() || pixels.size != other.pixels.size) return false
        // A coarse color grid ignores compression noise and tiny animations; compare to the last sent frame so changes accumulate.
        val changed = pixels.indices.count { i ->
            val a = pixels[i]; val b = other.pixels[i]
            (abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) +
                abs((a and 255) - (b and 255))) / 3 > 24
        }
        return changed <= pixels.size * 0.08
    }
}

/** One cancellable request at a time, with the full interval after each completed check or request. */
class CompanionController(private val scope: CoroutineScope, private val capture: suspend () -> CompanionFrame?,
    private val speak: (String) -> Unit = {}) {
    private val mutable = MutableStateFlow(CompanionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val sessions = Mutex()

    fun start(intervalMillis: Long, comment: suspend (CompanionFrame, List<String>) -> String?) {
        require(intervalMillis > 0)
        if (mutable.value.active) return
        mutable.value = CompanionState(active = true, message = "切到游戏或视频，开始陪伴")
        job = scope.launch { sessions.withLock {
            var previous: CompanionFrame? = null
            val recent = ArrayDeque<String>()
            while (isActive) {
                try {
                    val frame = capture()
                    ensureActive()
                    val baseline = previous
                    val similar = frame != null && baseline != null && baseline.similarTo(frame)
                    ensureActive()
                    when {
                        frame == null -> mutable.value = mutable.value.copy(message = "当前页面暂不评论，安静陪伴中")
                        similar -> mutable.value = mutable.value.copy(message = "画面变化不大，安静陪伴中")
                        else -> {
                            mutable.value = mutable.value.copy(message = "正在看看画面…")
                            val reply = comment(frame, recent.toList())
                            ensureActive()
                            // Null discards a stale observation; an empty reply is the model choosing silence.
                            if (reply != null) previous = frame.copy(image = "")
                            mutable.value = mutable.value.copy(message = "陪伴中，等待下一次观察")
                            if (!reply.isNullOrBlank() && reply !in recent) {
                                recent.addLast(reply)
                                while (recent.size > 3) recent.removeFirst()
                                mutable.value = mutable.value.copy(comment = reply)
                                speak(reply)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    ensureActive()
                    // Never echo server text: it may contain credentials or private screen content.
                    mutable.value = mutable.value.copy(message = "本轮未成功，稍后重试；可检查模型配置和网络")
                }
                delay(intervalMillis)
            }
        } }
    }

    fun stop() {
        job?.cancel()
        mutable.value = CompanionState()
    }
}
