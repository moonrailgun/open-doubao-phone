package com.moonrailgun.opendoubao.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.moonrailgun.opendoubao.assistant.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.coroutines.resumeWithException

class AndroidPhone(private val service: AssistantService, private val displayId: Int = Display.DEFAULT_DISPLAY,
    private val shell: IShellDisplay? = null) : Phone {
    var lastEvent: Long = 0
    private var lastCaptureAt = 0L
    private val captures = Mutex()
    var lastPackage: String? = null
        private set

    override fun canLaunch(packageName: String): Boolean = service.packageManager.getLaunchIntentForPackage(packageName) != null

    private fun unlocked() {
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !service.getSystemService(PowerManager::class.java).isInteractive) throw PhoneUnavailableException("手机已锁定或息屏")
    }

    private suspend fun window(): AccessibilityWindowInfo {
        repeat(10) {
            unlocked()
            pickWindow(windows())?.let { return it }
            // Activity transitions can briefly expose a window with no visible bounds or root.
            delay(100)
        }
        throw PhoneUnavailableException("无法读取当前页面的屏幕内容")
    }

    override suspend fun observe(): Screen {
        currentCoroutineContext().ensureActive()
        val window = window()
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: throw PhoneUnavailableException("目标屏幕已断开")
        val size = android.graphics.Point().also(display::getRealSize)
        // Each of a few hundred nodes is a binder call; walking them on the main thread froze the overlay for up to 0.7 s.
        return withContext(Dispatchers.IO) { readScreen(window, size.x, size.y, display.rotation) }.also { lastPackage = it.packageName }
    }

    override suspend fun screenshot(screen: Screen): String = capture(screen, restoreOverlay = true)

    override fun sameImage(before: String, after: String, screen: Screen, region: Bounds) = screenshotsMatch(before, after, screen, region)

    private fun windows() = service.windowsOnAllDisplays[displayId] ?: emptyList()

    private suspend fun capture(screen: Screen, restoreOverlay: Boolean): String = captures.withLock {
        if (observe().fingerprint != screen.fingerprint) throw ScreenChangedException()
        if (screen.protected) throw PhoneUnavailableException("密码页面不可截图")
        if (shell == null) service.setOverlayVisible(false)
        try {
            delay(120)
            delay((350 - (SystemClock.uptimeMillis() - lastCaptureAt)).coerceAtLeast(0))
            val target = window()
            if (target.id != screen.windowId) throw ScreenChangedException()
            val bounds = Rect().also(target::getBoundsInScreen)
            if (!bounds.intersect(0, 0, screen.width, screen.height)) throw ScreenChangedException()
            if (Build.VERSION.SDK_INT < 34) {
                if (windows().any { other ->
                    other.id != target.id && other.layer > target.layer &&
                        Rect.intersects(bounds, Rect().also(other::getBoundsInScreen))
                }) throw PhoneUnavailableException("键盘或其他浮层遮挡，无法可靠截图")
            }
            val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hardware = result.hardwareBuffer
                        val copy = try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped?.recycle() }
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException("截图无法解码"))
                            return
                        } finally { hardware.close() }
                        if (copy == null) { if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException("截图无法解码")); return }
                        continuation.resume(copy) { _, value, _ -> value.recycle() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException("系统拒绝截图（$errorCode）"))
                    }
                }
                lastCaptureAt = SystemClock.uptimeMillis()
                try {
                    if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(target.id, service.mainExecutor, callback)
                    else service.takeScreenshot(displayId, service.mainExecutor, callback)
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resumeWithException(PhoneUnavailableException("系统未允许截图"))
                }
            }
            try {
                if (observe().fingerprint != screen.fingerprint) throw ScreenChangedException()
                return encodeScreenshot(bitmap, if (Build.VERSION.SDK_INT >= 34) Rect(0, 0, bitmap.width, bitmap.height) else bounds, bounds, screen)
            } finally { bitmap.recycle() }
        } finally { if (restoreOverlay && shell == null) service.setOverlayVisible(true) }
    }

    override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
        currentCoroutineContext().ensureActive()
        unlocked()
        action.validate()
        if (expected != null && observe().fingerprint != expected.fingerprint) return false
        if (action is Action.Tap || action is Action.Swipe) {
            if (expected == null || expectedImage == null || ActionPolicy.assess(action, expected, setOf(expected.packageName)) == Permission.REJECT) return false
        }
        val accepted = when (action) {
            is Action.OpenApp -> launch(service.packageManager.getLaunchIntentForPackage(action.packageName)
                ?: throw PhoneUnavailableException("该应用没有可直接打开的入口，请手动进入需要操作的页面"))
            is Action.OpenUrl -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).setPackage(action.packageName))
            is Action.Click, is Action.Type, is Action.Scroll, is Action.Submit ->
                performOnNode(window().root ?: throw PhoneUnavailableException("当前页面没有可访问节点"), action)
            is Action.Tap -> gesture(Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }, 80, expected!!, expectedImage!!, action.gestureBounds()!!)
            is Action.Swipe -> gesture(Path().apply { moveTo(action.x1.toFloat(), action.y1.toFloat()); lineTo(action.x2.toFloat(), action.y2.toFloat()) }, 400, expected!!, expectedImage!!, action.gestureBounds()!!)
            Action.Back -> if (shell != null) { shell.key(android.view.KeyEvent.KEYCODE_BACK); true } else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Action.Home -> if (shell != null) { shell.key(android.view.KeyEvent.KEYCODE_HOME); true } else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Action.Wait -> true
        }
        val launched = when (action) { is Action.OpenApp -> action.packageName; is Action.OpenUrl -> action.packageName; else -> null }
        if (accepted) { launched?.let { awaitForeground(it) }; settle() }
        return accepted
    }

    private fun launch(intent: Intent): Boolean {
        try {
            if (shell != null) {
                shell.launch(intent.component?.packageName ?: requireNotNull(intent.`package`), intent.dataString)
                return true
            }
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            throw PhoneUnavailableException("没有可打开此页面的应用，请手动进入需要操作的页面")
        } catch (_: SecurityException) {
            throw PhoneUnavailableException("系统未允许打开此页面，请手动进入需要操作的页面")
        }
    }

    // startActivity returns before the app is in front; a cold start can outlast settle() and the next read sees the old app.
    private suspend fun awaitForeground(pkg: String) {
        awaitPackage(pkg, 5_000, ::unlocked, front = {
            pickWindow(windows())?.root?.let { root -> root.packageName?.toString().also { root.recycle() } }
        })
    }

    private suspend fun gesture(path: Path, duration: Long, expected: Screen, expectedImage: String, region: Bounds): Boolean {
        if (shell == null) service.setOverlayVisible(false)
        try {
            // Animated canvases near the gesture still need human control; a cursor elsewhere does not (ImageMatch).
            if (!screenshotsMatch(expectedImage, capture(expected, restoreOverlay = false), expected, region)) return false
            if (observe().fingerprint != expected.fingerprint) return false
            val target = window()
            if (windows().any { other ->
                val bounds = Rect().also(other::getBoundsInScreen)
                other.id != target.id && other.layer > target.layer &&
                    region.intersects(Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
            }) return false
            return suspendCancellableCoroutine { continuation ->
        val dispatched = service.dispatchGesture(GestureDescription.Builder().setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
            }, null)
        if (!dispatched && continuation.isActive) continuation.resume(false)
            }
        } finally { if (shell == null) service.setOverlayVisible(true) }
    }

    private suspend fun settle() {
        delay(350)
        val until = SystemClock.uptimeMillis() + 1200
        while (SystemClock.uptimeMillis() < until && SystemClock.uptimeMillis() - lastEvent < 250) delay(80)
    }
}

/**
 * Polls until [pkg] is the front app or [timeoutMillis] passes, returning whether it arrived. [ready] throws when the
 * phone cannot be used at all (locked); [front] failing only means "not yet", since a cold start's splash screen has
 * nothing readable for a second or more. Whatever is still wrong after the wait surfaces at the task's next read.
 */
internal suspend fun awaitPackage(pkg: String, timeoutMillis: Long, ready: () -> Unit, front: () -> String?,
                                  now: () -> Long = SystemClock::uptimeMillis): Boolean {
    val until = now() + timeoutMillis
    while (now() < until) {
        ready()
        val current = try { front() } catch (_: PhoneUnavailableException) { null }
        if (current == pkg) return true
        delay(100)
    }
    return false
}

/** The readable foreground app window, or null while a transition hides it; active system UI is refused. */
internal fun pickWindow(windows: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
    if (windows.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused) &&
            !Rect().also(it::getBoundsInScreen).isEmpty }) {
        throw PhoneUnavailableException("当前系统界面不支持自动操作，请手动处理后返回应用页面")
    }
    val target = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive || it.isFocused }.thenByDescending { it.layer })
        .firstOrNull() ?: return null
    // Never fall through to a readable background app when the foreground cannot be read.
    val root = target.root
    val available = root != null && !Rect().also(target::getBoundsInScreen).isEmpty
    root?.recycle()
    return target.takeIf { available }
}

/** Reads a window's visible nodes, measured in the coordinates of its [width] by [height] display. */
internal fun readScreen(window: AccessibilityWindowInfo, width: Int, height: Int, rotation: Int): Screen {
    val root = window.root ?: throw PhoneUnavailableException("当前页面没有可访问节点")
    val elements = mutableListOf<Element>()
    var truncated = false
    var textBudget = 48_000
    var visited = 0
    fun visit(node: AccessibilityNodeInfo, id: String, depth: Int) {
        if (++visited > 700 || elements.size >= 350 || depth > 35) { truncated = true; return }
        val rect = Rect().also(node::getBoundsInScreen)
        if (node.isVisibleToUser || node.isPassword) {
            fun text(raw: CharSequence?): String {
                val out = raw?.toString().orEmpty().take(minOf(2000, textBudget))
                textBudget -= out.length
                return out
            }
            elements += Element(id, if (node.isPassword) "" else text(node.text),
                if (node.isPassword) "" else text(node.contentDescription), node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(), Bounds(rect.left, rect.top, rect.right, rect.bottom),
                node.isClickable, node.isEditable, node.isScrollable, node.isEnabled, node.isPassword, node.isChecked)
        }
        for (i in 0 until node.childCount) {
            if (visited >= 700 || elements.size >= 350) { truncated = true; break }
            node.getChild(i)?.let { child -> visit(child, "$id.$i", depth + 1); child.recycle() }
        }
    }
    val pkg = root.packageName?.toString().orEmpty()
    try { visit(root, "0", 0) } finally { root.recycle() }
    val bounds = Rect().also(window::getBoundsInScreen)
    if (!bounds.intersect(0, 0, width, height)) throw ScreenChangedException()
    return Screen(pkg, window.id, width, height, rotation, elements, truncated || textBudget == 0,
        Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
}

/** Runs a click, type, scroll or enter on the node it names under [root]; every node it visits is recycled. */
internal fun performOnNode(root: AccessibilityNodeInfo, action: Action): Boolean {
    val (id, code, bundle) = when (action) {
        is Action.Click -> Triple(action.node, AccessibilityNodeInfo.ACTION_CLICK, null)
        is Action.Type -> Triple(action.node, AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) })
        is Action.Scroll -> Triple(action.node, if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        // Delivered to the field's editor action, so it needs no keyboard on screen (the virtual display shows none).
        is Action.Submit -> Triple(action.node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id, null)
        else -> { root.recycle(); return false }
    }
    var node = root
    try {
        for (index in id.split('.').drop(1).map(String::toInt)) {
            val child = node.getChild(index) ?: return false
            node.recycle(); node = child
        }
        return node.isEnabled && !node.isPassword && node.performAction(code, bundle)
    } finally { node.recycle() }
}

/** [ImageMatch] on two [encodeScreenshot] images of [screen], read at a quarter size; [region] is in display coordinates. */
internal fun screenshotsMatch(before: String, after: String, screen: Screen, region: Bounds): Boolean {
    if (before == after) return true
    fun decode(image: String): Bitmap? = Base64.decode(image, Base64.NO_WRAP).let { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
    }
    val a = decode(before) ?: return false
    val b = decode(after) ?: run { a.recycle(); return false }
    try {
        if (a.width != b.width || a.height != b.height) return false
        fun brightness(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (i in it.indices) it[i] = (Color.red(it[i]) * 299 + Color.green(it[i]) * 587 + Color.blue(it[i]) * 114) / 1000
        }
        val scale = a.width.toFloat() / screen.width
        val target = Bounds((region.left * scale).toInt(), (region.top * scale).toInt(),
            ceil(region.right * scale).toInt(), ceil(region.bottom * scale).toInt())
        return ImageMatch.same(brightness(a), brightness(b), a.width, a.height, target)
    } finally { a.recycle(); b.recycle() }
}

/** JPEG of [source] drawn at [bounds], black elsewhere, keeping the original display coordinate system. */
internal fun encodeScreenshot(bitmap: Bitmap, source: Rect, bounds: Rect, screen: Screen): String {
    val ratio = minOf(1f, 1440f / maxOf(screen.width, screen.height))
    val scaled = Bitmap.createBitmap((screen.width * ratio).toInt(), (screen.height * ratio).toInt(), Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        canvas.scale(ratio, ratio)
        canvas.drawBitmap(bitmap, source, bounds, null)
        return ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)) throw PhoneUnavailableException("截图编码失败")
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
    } finally { scaled.recycle() }
}
