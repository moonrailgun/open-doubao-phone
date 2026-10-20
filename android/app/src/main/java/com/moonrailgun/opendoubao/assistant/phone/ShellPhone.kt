package com.moonrailgun.opendoubao.assistant.phone

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import android.view.Surface
import android.view.SurfaceControl
import com.moonrailgun.opendoubao.assistant.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class ShellPhone(private val service: AssistantService, private val foreground: AndroidPhone,
    private val active: MutableStateFlow<Boolean>, private val onLost: (String) -> Unit) : OffscreenPhone {
    private val bridge = ShellBridge.service(service)
    @Volatile private var phone: AndroidPhone? = null
    @Volatile private var closed = false
    @Volatile private var handedOver = false
    private val previews = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val death = android.os.IBinder.DeathRecipient {
        service.mainExecutor.execute {
            if (!closed && !handedOver) { close(); onLost("虚拟屏连接已断开，请重新连接后开始任务") }
        }
    }
    init { bridge.asBinder().linkToDeath(death, 0) }
    override val mode get() = if (handedOver) foreground.mode else "虚拟屏"
    override val hasSession get() = phone != null && !closed && !handedOver
    override fun canLaunch(packageName: String) = foreground.canLaunch(packageName)
    private fun current(): AndroidPhone {
        if (handedOver) return foreground
        check(!closed) { "虚拟屏任务已结束" }
        return phone ?: throw PhoneUnavailableException("虚拟屏上还没有打开目标应用")
    }
    override suspend fun observe() = current().observe()
    override suspend fun screenshot(screen: Screen) = current().screenshot(screen)
    override fun sameImage(before: String, after: String, screen: Screen, region: Bounds) = foreground.sameImage(before, after, screen, region)
    override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
        if (!handedOver && !closed && phone == null) {
            check(action is Action.OpenApp || action is Action.OpenUrl) { "请先在虚拟屏打开应用" }
            // Creation must publish its result before cancellation or close can release the display.
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(this@ShellPhone) {
                    check(!closed) { "虚拟屏任务已结束" }
                    if (phone == null) {
                        val id = bridge.createDisplay()
                        phone = AndroidPhone(service, id, bridge)
                        ShellBridge.displayId.value = id
                        active.value = true
                    }
                }
            }
        }
        return current().execute(action, expected, expectedImage)
    }
    override fun resume() = Unit
    @Synchronized override fun handOver() {
        if (!hasSession) return
        bridge.releaseDisplay(true)
        handedOver = true
        active.value = false
        ShellBridge.displayId.value = null
        previews.coroutineContext.cancelChildren()
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        previews.cancel()
        bridge.asBinder().unlinkToDeath(death, 0)
        if (!handedOver) runCatching { bridge.releaseDisplay(false) }
        active.value = false
        ShellBridge.displayId.value = null
    }
    override fun peek(parent: SurfaceControl, width: Int, height: Int, surface: Surface?): AutoCloseable? {
        if (!hasSession || surface == null) return null
        // Use the same protected-page checks as task screenshots; previews never bypass FLAG_SECURE.
        val job = previews.launch {
            while (isActive && hasSession && surface.isValid) {
                val bitmap = runCatching {
                    val bytes = Base64.decode(screenshot(observe()), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                try {
                    val canvas = surface.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
                        if (bitmap != null) {
                            val scale = minOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
                            val w = (bitmap.width * scale).toInt()
                            val h = (bitmap.height * scale).toInt()
                            val x = (canvas.width - w) / 2
                            val y = (canvas.height - h) / 2
                            canvas.drawBitmap(bitmap, null, Rect(x, y, x + w, y + h), null)
                        }
                    } finally { surface.unlockCanvasAndPost(canvas) }
                } catch (_: Exception) { break } finally { bitmap?.recycle() }
                delay(700)
            }
        }
        return AutoCloseable { job.cancel() }
    }
}
