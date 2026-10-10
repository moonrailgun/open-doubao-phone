package com.moonrailgun.opendoubao.assistant.phone

import android.app.ActivityOptions
import android.app.AppInteractionAttribution
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.computercontrol.ComputerControlSession
import android.companion.virtual.computercontrol.ComputerControlSessionParams
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.accessibility.AccessibilityWindowInfo
import android.annotation.TargetApi
import com.android.internal.os.IResultReceiver
import com.moonrailgun.opendoubao.assistant.R
import com.moonrailgun.opendoubao.assistant.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun taskPrefs(context: Context) = context.getSharedPreferences("task", Context.MODE_PRIVATE)
/** The user's choice in settings; the virtual display is used only when the system also supports it. */
fun foregroundChosen(context: Context) = taskPrefs(context).getBoolean("foreground", false)
fun chooseForeground(context: Context, value: Boolean) = taskPrefs(context).edit().putBoolean("foreground", value).apply()

/** True when this system lets the assistant open a Computer Control session; false on stock Android and older images. */
fun virtualDisplayAvailable(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 && runCatching {
    VirtualDeviceManager::class.java.getMethod("isComputerControlAvailable")
        .invoke(context.getSystemService(VirtualDeviceManager::class.java)) as Boolean
}.getOrDefault(false)

/**
 * Works on an Android 17 Computer Control virtual display, so the phone stays free while a task runs.
 * Needs the Open Doubao image: ACCESS_COMPUTER_CONTROL, hidden API access and the super agent overlay.
 * After [handOver] the apps move to the main display and the remaining steps go through [foreground].
 * Only constructed after [virtualDisplayAvailable], which requires API 34.
 */
@TargetApi(34)
class VirtualPhone(private val service: AssistantService, private val foreground: AndroidPhone,
    private val onLost: (String) -> Unit) : Phone {
    private val executor: Executor = service.mainExecutor
    /** The live session for the peek window: null before the first app opens and after the task ends or is handed over. */
    var session: ComputerControlSession? = null
        private set
    private val targets = linkedSetOf<String>()
    private var blocked: String? = null
    private var closing = false
    private var handedOver = false
    private val stable = MutableStateFlow(0)
    private var lastImage: Pair<String, String>? = null

    override val mode = "虚拟屏"

    override fun canLaunch(packageName: String) = foreground.canLaunch(packageName)

    override suspend fun observe(): Screen {
        if (handedOver) return foreground.observe()
        currentCoroutineContext().ensureActive()
        val size = live().displaySize
        return readScreen(window(), size.width, size.height, Surface.ROTATION_0)
    }

    override suspend fun screenshot(screen: Screen): String {
        if (handedOver) return foreground.screenshot(screen)
        if (observe().fingerprint != screen.fingerprint) throw ScreenChangedException()
        if (screen.protected) throw PhoneUnavailableException("密码页面不可截图")
        val bitmap = try { capture() } catch (e: ComputerControlSession.ScreenshotException) {
            // The display has not redrawn since this page was last captured, so that image still holds.
            lastImage?.takeIf { e.errorCode == ComputerControlSession.ScreenshotException.ERROR_SCREEN_UNCHANGED &&
                it.first == screen.fingerprint }?.let { return it.second }
            throw PhoneUnavailableException("虚拟屏截图失败（${e.errorCode}）")
        }
        try {
            if (observe().fingerprint != screen.fingerprint) throw ScreenChangedException()
            val bounds = screen.windowBounds.let { Rect(it.left, it.top, it.right, it.bottom) }
            return encodeScreenshot(bitmap, bounds, bounds, screen).also { lastImage = screen.fingerprint to it }
        } finally { bitmap.recycle() }
    }

    override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
        if (handedOver) return foreground.execute(action, expected, expectedImage)
        currentCoroutineContext().ensureActive()
        action.validate()
        if (expected != null && observe().fingerprint != expected.fingerprint) return false
        if (action is Action.Tap || action is Action.Swipe) {
            if (expected == null || expectedImage == null ||
                ActionPolicy.assess(action, expected, setOf(expected.packageName)) == Permission.REJECT) return false
            // shortcut: exact image matching requires a stable page, as in the foreground; animated canvases need human control.
            if (screenshot(expected) != expectedImage) return false
        }
        val launched = when (action) { is Action.OpenApp -> action.packageName; is Action.OpenUrl -> action.packageName; else -> null }
        val live = launched?.let { forApp(it) } ?: live()
        val mark = stable.value
        val accepted = when (action) {
            is Action.OpenApp -> launch { live.launchApplication(action.packageName) }
            is Action.OpenUrl -> launch { live.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).setPackage(action.packageName), null) }
            is Action.Click, is Action.Type, is Action.Scroll ->
                performOnNode(window().root ?: throw PhoneUnavailableException("当前页面没有可访问节点"), action)
            is Action.Tap -> interact { cancel, receiver -> live.tap(action.x, action.y, cancel, executor, receiver) }
            is Action.Swipe -> interact { cancel, receiver -> live.swipe(action.x1, action.y1, action.x2, action.y2, cancel, executor, receiver) }
            Action.Back -> interact { cancel, receiver -> live.performAction(ComputerControlSession.ACTION_GO_BACK, cancel, executor, receiver) }
            // The virtual display has no launcher to go home to.
            Action.Home -> false
            Action.Wait -> true
        }
        if (accepted) {
            launched?.let { awaitForeground(it) }
            delay(200)
            // Stable once accessibility events have been quiet for the listener's 300 ms; busy pages stop at the cap.
            withTimeoutOrNull(2_500) { stable.first { it > mark } }
        }
        return accepted
    }

    /** Lets a blocked session continue after the user dealt with the reason; it blocks again if the reason remains. */
    fun resume() {
        if (blocked != null && session?.requestResume() == true) blocked = null
    }

    /** Moves the automated apps to the main display for manual handling; later steps run in the foreground. */
    fun handOver() {
        val live = session ?: return
        // The system closes the session afterwards; its callbacks reach the main thread only after this returns.
        live.handOverApplications()
        closing = true
        handedOver = true
        session = null
    }

    /** Shows the virtual display under [parent], scaled to fit; view only, touches never reach the apps. */
    fun peek(parent: SurfaceControl, width: Int, height: Int): AutoCloseable? {
        val live = session ?: return null
        val mirror = runCatching {
            live.createInteractiveMirror(object : IResultReceiver.Stub() { override fun send(resultCode: Int, resultData: Bundle?) = Unit })
        }.getOrNull() ?: return null
        return runCatching {
            mirror.setHostDisplayId(Display.DEFAULT_DISPLAY)
            mirror.setInteractive(false)
            val size = live.displaySize
            val scale = minOf(width.toFloat() / size.width, height.toFloat() / size.height)
            val layer = mirror.mirrorSurfaceControl
            SurfaceControl.Transaction().use { it.reparent(layer, parent).setScale(layer, scale, scale).setVisibility(layer, true).apply() }
            mirror
        }.getOrElse { runCatching { mirror.close() }; null }
    }

    fun close() {
        // Also set without a session, so a creation still pending at the end of the task never reports a loss.
        closing = true
        val live = session ?: return
        session = null
        runCatching { live.close() }
    }

    private fun live(): ComputerControlSession {
        blocked?.let { throw PhoneUnavailableException(it) }
        return session ?: throw PhoneUnavailableException("虚拟屏上还没有打开目标应用")
    }

    private suspend fun window(): AccessibilityWindowInfo {
        repeat(10) {
            pickWindow(live().accessibilityWindows)?.let { return it }
            // Activity transitions can briefly expose a window with no visible bounds or root.
            delay(100)
        }
        throw PhoneUnavailableException("无法读取虚拟屏上的页面")
    }

    // launchApplication returns before the app draws; the next read must not see the previous app.
    private suspend fun awaitForeground(pkg: String) {
        withTimeoutOrNull(5_000) {
            while (runCatching { window().root?.let { root -> root.packageName?.toString().also { root.recycle() } } }.getOrNull() != pkg) delay(100)
        }
    }

    private fun launch(block: () -> Unit): Boolean = try { block(); true } catch (e: IllegalArgumentException) {
        throw PhoneUnavailableException("无法在虚拟屏打开此页面（${e.message}）")
    } catch (e: SecurityException) {
        throw PhoneUnavailableException("系统未允许在虚拟屏打开此页面")
    }

    private suspend fun interact(
        call: (CancellationSignal, OutcomeReceiver<Void?, ComputerControlSession.InteractionException>) -> Unit,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }
        call(cancel, object : OutcomeReceiver<Void?, ComputerControlSession.InteractionException> {
            override fun onResult(result: Void?) { if (continuation.isActive) continuation.resume(true) }
            override fun onError(error: ComputerControlSession.InteractionException) { if (continuation.isActive) continuation.resume(false) }
        })
    }

    private suspend fun capture(): Bitmap {
        val live = live()
        val image = suspendCancellableCoroutine<Image> { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            live.requestScreenshot(executor, object : OutcomeReceiver<Image, ComputerControlSession.ScreenshotException> {
                override fun onResult(result: Image) {
                    if (continuation.isActive) continuation.resume(result) { _, value, _ -> value.close() } else result.close()
                }
                override fun onError(error: ComputerControlSession.ScreenshotException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, cancel)
        }
        // The session's reader delivers CPU-readable RGBA_8888 frames whose rows may be padded.
        return image.use {
            val plane = it.planes[0]
            val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, it.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width == it.width) padded else Bitmap.createBitmap(padded, 0, 0, it.width, it.height).also { padded.recycle() }
        }
    }

    private suspend fun forApp(pkg: String): ComputerControlSession {
        val live = session ?: return create(pkg)
        if (pkg !in targets) suspendCancellableCoroutine { continuation ->
            live.addPackagesForAutomation(listOf(pkg), executor, object : ComputerControlSession.TargetPackagesAdditionCallback {
                override fun onTargetPackagesAdditionPending(intentSender: IntentSender) = ask(intentSender)
                override fun onTargetPackagesAdditionSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onTargetPackagesAdditionFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException("虚拟屏无法再加入 $pkg（$errorCode）"))
                }
            })
        }
        targets += pkg
        return live()
    }

    private suspend fun create(pkg: String): ComputerControlSession {
        service.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "虚拟屏后台操作", NotificationManager.IMPORTANCE_LOW))
        val preview = PendingIntent.getActivity(service, 0,
            checkNotNull(service.packageManager.getLaunchIntentForPackage(service.packageName)), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, CHANNEL).setSmallIcon(R.drawable.lucide_bot)
            .setContentTitle("Open Doubao 正在虚拟屏上操作").setContentText("点开悬浮球可以查看画面或紧急停止")
            .setContentIntent(preview).setOngoing(true).build()
        // Version 5 is the minimum the system accepts and makes every field below mandatory.
        val params = ComputerControlSessionParams.Builder()
            .setName("Open Doubao ${UUID.randomUUID()}")
            .setTargetPackageNames(listOf(pkg))
            .setTargetComputerControlVersion(5)
            .setPreviewIntent(preview)
            .setAppInteractionAttribution(AppInteractionAttribution.Builder(AppInteractionAttribution.INTERACTION_TYPE_USER_QUERY).build())
            .setNotificationParams(ComputerControlSessionParams.NotificationParams.Builder(notification, NOTIFICATION_ID).build())
            .build()
        val created = suspendCancellableCoroutine { continuation ->
            val callback = object : ComputerControlSession.Callback {
                override fun onSessionPending(intentSender: IntentSender) = ask(intentSender)
                override fun onSessionCreated(session: ComputerControlSession) {
                    if (continuation.isActive) continuation.resume(session) { _, value, _ -> value.close() } else session.close()
                }
                override fun onSessionCreationFailed(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException(when (errorCode) {
                        ComputerControlSession.ERROR_SESSION_LIMIT_REACHED -> "已有其他虚拟屏会话在运行"
                        ComputerControlSession.ERROR_DEVICE_LOCKED -> "手机锁定时无法开启虚拟屏"
                        ComputerControlSession.ERROR_PERMISSION_DENIED -> "系统未允许在虚拟屏操作，可在设置中改为前台执行"
                        else -> "虚拟屏开启失败（$errorCode），可在设置中改为前台执行"
                    }))
                }
                override fun onSessionClosed() = ended(null)
            }
            try {
                VirtualDeviceManager::class.java.getMethod("requestComputerControlSession", ComputerControlSessionParams::class.java,
                    Executor::class.java, ComputerControlSession.Callback::class.java)
                    .invoke(service.getSystemService(VirtualDeviceManager::class.java), params, executor, callback)
            } catch (e: InvocationTargetException) {
                throw PhoneUnavailableException("虚拟屏开启失败：${e.cause?.message}")
            }
        }
        created.setLifecycleCallback(executor, object : ComputerControlSession.LifecycleCallback {
            override fun onActive() { blocked = null }
            override fun onBlocked(reason: Int, message: String?) {
                blocked = when (reason) {
                    ComputerControlSession.BLOCK_REASON_SECURE_CONTENT -> "页面禁止在虚拟屏显示，可点「转到前台处理」"
                    ComputerControlSession.BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH -> "应用打开了本次任务以外的页面，可点「转到前台处理」"
                    ComputerControlSession.BLOCK_REASON_AUTHENTICATION_PROMPT_REQUESTED -> "需要身份验证，可点「转到前台处理」"
                    else -> "虚拟屏暂停操作（$reason）"
                }
            }
            override fun onClosed(reason: Int) = ended(reason)
        })
        created.setStabilityListener(Duration.ofMillis(300), executor, object : ComputerControlSession.StabilityListener {
            override fun onSessionStable() { stable.value++ }
        })
        targets += pkg
        session = created
        return created
    }

    // The system asks the user once per app before it may be automated.
    private fun ask(intentSender: IntentSender) {
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
            if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        runCatching { service.startIntentSender(intentSender, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0, options) }
    }

    private fun ended(reason: Int?) {
        session = null
        if (closing) return
        closing = true
        onLost(when (reason) {
            ComputerControlSession.CLOSE_REASON_USER_INITIATED -> "已从系统通知结束虚拟屏操作"
            ComputerControlSession.CLOSE_REASON_SESSION_TIMED_OUT -> "虚拟屏会话超时"
            else -> "虚拟屏会话被系统结束"
        })
    }

    private companion object {
        const val CHANNEL = "computer_control"
        const val NOTIFICATION_ID = 7301
    }
}
