package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.core.CompanionState
import com.moonrailgun.opendoubao.assistant.core.Action
import com.moonrailgun.opendoubao.assistant.core.Phone
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CompanionOverlayTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun autoDocksWrapsCommentsAndAcceptsInputWithoutStartingATask() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bubbleUiCheck") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val uiFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = context.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val component = "${context.packageName}/${AssistantService::class.java.name}"
        val others = services.orEmpty().split(':').filter { it.isNotBlank() && it != component }.joinToString(":")
        val preferences = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
        val overlayEnabled = if (preferences.contains("enabled")) preferences.getBoolean("enabled", true) else null
        val vault = Vault(context)
        val saved = vault.read()
        val device = UiDevice.getInstance(instrument)
        var activity: android.app.Activity? = null
        var configured = false
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 8000
            while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).run {
            isAccessible = true; get(owner)!!
        }
        fun screenshot(name: String) {
            device.waitForIdle(1000)
            val bitmap = automation.takeScreenshot()!!
            try { File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        try {
            assertFalse("Finish existing tasks first", saved.records.any { it.status in setOf("RUNNING", "PAUSED") })
            assertFalse("Stop existing companion sessions first", AssistantService.connected.value?.companionState?.value?.active == true)
            configured = true
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
            await("Disconnect accessibility") { AssistantService.connected.value == null }
            // The app's own page is excluded from capture; this UI test never calls a provider.
            vault.write(saved.copy(config = ModelConfig(chatEndpoint = "https://example.invalid/chat", chatModel = "test", chatKey = "test"),
                companion = saved.companion.copy(intervalSeconds = 300, useDedicatedModel = false, speechEnabled = false)))
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                listOf(others, component).filter(String::isNotEmpty).joinToString(":"))
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            await("Connect accessibility") { AssistantService.connected.value != null }
            val service = AssistantService.connected.value!!
            instrument.runOnMainSync { service.startCompanion() }
            assertNotNull(device.wait(Until.findObject(By.text("陪伴中")), 5000))
            instrument.runOnMainSync {
                val chip = field(service, "bubble") as View
                val location = IntArray(2).also(chip::getLocationOnScreen)
                val bar = service.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                assertTrue("Capsule must be inside the status bar", location[1] >= 0 && location[1] + chip.height <= bar)
                assertTrue("Capsule includes a label beside the avatar", chip.width > chip.height * 2)
                val ring = (field(service, "bubbleAvatar") as View).foreground
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val bounds = android.graphics.Rect(ring.bounds)
                try {
                    ring.setBounds(0, 0, 64, 64); ring.draw(Canvas(bitmap))
                    assertTrue("Companion must not paint a green ring", (0 until 64).all { x ->
                        (0 until 64).all { y -> android.graphics.Color.green(bitmap.getPixel(x, y)) == 0 }
                    })
                } finally { ring.bounds = bounds; bitmap.recycle() }
            }
            screenshot("companion-capsule")
            fun captureWithoutBlink(checkGesture: Boolean = false, uncoveredTap: Action.Tap? = null) = runBlocking {
                withContext(Dispatchers.Main) {
                    val root = field(service, "overlay") as View
                    val phone = field(service, "phone") as Phone
                    var hidden = false
                    val watcher = launch {
                        while (isActive) { hidden = hidden || root.visibility != View.VISIBLE; delay(10) }
                    }
                    try {
                        val location = IntArray(2).also(root::getLocationOnScreen)
                        val overlayBounds = android.graphics.Rect(location[0], location[1],
                            location[0] + root.width, location[1] + root.height)
                        val screen = phone.observe()
                        val encoded = phone.screenshot(screen)
                        assertFalse("Capturing must keep the capsule and expanded panel visible", hidden)
                        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        try {
                            val bar = service.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                                .getInsets(WindowInsets.Type.statusBars()).top
                            val y = bar * bitmap.height / screen.height / 2
                            assertTrue("Status bar and capsule must be excluded from the image", (0 until bitmap.width).all {
                                val color = bitmap.getPixel(it, y)
                                Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) < 10
                            })
                            if (Build.VERSION.SDK_INT < 34) {
                                val scale = bitmap.width.toFloat() / screen.width
                                val left = (overlayBounds.left * scale).toInt().coerceAtLeast(0) + 4
                                val top = (overlayBounds.top * scale).toInt().coerceAtLeast(0) + 4
                                val right = (overlayBounds.right * scale).toInt().coerceAtMost(bitmap.width) - 4
                                val bottom = (overlayBounds.bottom * scale).toInt().coerceAtMost(bitmap.height) - 4
                                assertTrue("The visible overlay must be masked in the model image", (left until right).all { x ->
                                    (top until bottom).all { y ->
                                        val color = bitmap.getPixel(x, y)
                                        Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) < 10
                                    }
                                })
                            }
                            assertTrue("The app content must remain visible in the model image", (0 until bitmap.height).any { y ->
                                val color = bitmap.getPixel(bitmap.width / 2, y)
                                Color.red(color) > 20 || Color.green(color) > 20 || Color.blue(color) > 20
                            })
                        } finally { bitmap.recycle() }
                        if (uncoveredTap != null) {
                            assertTrue("Automation can tap outside the capsule", phone.execute(uncoveredTap, screen, encoded))
                            assertFalse("An accepted tap must not blink the overlay", hidden)
                        }
                        if (checkGesture) {
                            assertFalse("Automation must not tap the visible panel", phone.execute(
                                Action.Tap(overlayBounds.centerX(), overlayBounds.centerY()), screen, encoded))
                            assertFalse("Rejecting a covered tap must not blink the overlay", hidden)
                        }
                    } finally { watcher.cancelAndJoin() }
                }
            }
            val executeTab = device.findObject(By.text("执行")).visibleBounds
            captureWithoutBlink(uncoveredTap = Action.Tap(executeTab.centerX(), executeTab.centerY()))
            instrument.runOnMainSync {
                val controller = field(service, "companion")
                (field(controller, "mutable") as MutableStateFlow<CompanionState>).value =
                    CompanionState(active = true, retrying = true, message = "暂时无法查看画面，稍后重试")
            }
            assertNotNull(device.wait(Until.findObject(By.text("稍后重试")), 5000))
            val comment = "这波配合很漂亮，先照顾后排再找机会推进。".repeat(5) + "完整结尾。"
            instrument.runOnMainSync {
                val controller = field(service, "companion")
                (field(controller, "mutable") as MutableStateFlow<CompanionState>).value =
                    CompanionState(active = true, message = "陪伴中，等待下一次观察", comment = comment)
                AssistantService::class.java.getDeclaredMethod("showCompanionComment", String::class.java).apply {
                    isAccessible = true; invoke(service, comment)
                }
            }
            assertNotNull(device.wait(Until.findObject(By.text(comment)), 5000))
            instrument.runOnMainSync {
                val text = field(service, "taskStep") as TextView
                assertTrue("Comment must wrap", text.lineCount > 1)
                assertEquals(comment.length, text.layout.getLineEnd(text.lineCount - 1))
                assertTrue((0 until text.lineCount).all { text.layout.getEllipsisCount(it) == 0 })
                assertTrue("Comment fits or can be scrolled", text.height >= text.layout.height || text.movementMethod != null)
            }
            screenshot("companion-comment")
            captureWithoutBlink()
            device.findObject(By.desc("Open Doubao 陪伴中，点按查看评论、输入或停止")).click()
            val send = device.wait(Until.findObject(By.desc("发送")), 5000)
            assertNotNull(send)
            captureWithoutBlink(checkGesture = true)
            val input = send.parent.findObject(By.clazz("android.widget.EditText"))
            assertNotNull(input)
            input.click()
            input.text = "下一次请关注辅助的装备"
            screenshot("companion-input")
            device.findObject(By.desc("发送")).click()
            await("Input reaches companion even on an excluded page") { service.companionState.value.message.contains("已保留你的输入") }
            assertTrue(service.companionState.value.active)
            assertEquals("Input must not create an automation task", TaskStatus.IDLE, service.state.value.status)
            assertEquals(saved.records, vault.read().records)
        } finally {
            try {
                if (configured) {
                    instrument.runOnMainSync { AssistantService.connected.value?.stopCompanion(); activity?.finishAndRemoveTask() }
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
                    await("Disconnect test service") { AssistantService.connected.value == null }
                    vault.write(saved)
                    preferences.edit().apply {
                        if (overlayEnabled == null) remove("enabled") else putBoolean("enabled", overlayEnabled)
                    }.commit()
                }
            } finally {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
                automation.dropShellPermissionIdentity()
                Configurator.getInstance().uiAutomationFlags = uiFlags
            }
        }
    }
}
