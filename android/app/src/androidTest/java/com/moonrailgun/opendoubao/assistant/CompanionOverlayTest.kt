package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
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
            while (SystemClock.uptimeMillis() < end) {
                if (condition()) return
                SystemClock.sleep(50)
            }
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
            fun bounds(name: String): Rect {
                // The view's screen offset can lag behind window resizing or rotation.
                var result = Rect()
                await("The system and overlay must finish the same layout") {
                    val frame = Rect()
                    service.windows.firstOrNull { it.id == service.overlayWindowId }?.getBoundsInScreen(frame)
                    var settled = false
                    instrument.runOnMainSync {
                        val root = field(service, "overlay") as View
                        val view = field(service, name) as View
                        assertTrue("$name must stay visible", view.isShown)
                        val params = field(service, "layout") as WindowManager.LayoutParams
                        val expectedFrame = Rect()
                        Gravity.apply(params.gravity, root.width, root.height,
                            service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds,
                            params.x, params.y, expectedFrame)
                        settled = !root.isLayoutRequested && frame == expectedFrame
                        val location = IntArray(2).also(view::getLocationInWindow)
                        location[0] += frame.left
                        location[1] += frame.top
                        result = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                    }
                    settled
                }
                return result
            }
            fun assertAnchor(expected: Rect) {
                device.waitForIdle(1000)
                assertEquals("Messages must not move or resize the avatar", expected, bounds("bubbleAvatar"))
            }
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
            val dockedAvatar = bounds("bubbleAvatar")
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
                            if (Build.VERSION.SDK_INT < 34) {
                                assertTrue("Status bar and capsule must be excluded from the image", (0 until bitmap.width).all {
                                    val color = bitmap.getPixel(it, y)
                                    Color.red(color) < 10 && Color.green(color) < 10 && Color.blue(color) < 10
                                })
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
            val execute = device.wait(Until.findObject(By.text("执行")), 5000)
            assertNotNull("The main page must finish opening", execute)
            val executeTab = execute.visibleBounds
            captureWithoutBlink(uncoveredTap = Action.Tap(executeTab.centerX(), executeTab.centerY()))
            instrument.runOnMainSync {
                val controller = field(service, "companion")
                (field(controller, "mutable") as MutableStateFlow<CompanionState>).value =
                    CompanionState(active = true, retrying = true, message = "暂时无法查看画面，稍后重试")
            }
            assertNotNull(device.wait(Until.findObject(By.text("稍后重试")), 5000))
            assertAnchor(dockedAvatar)
            val comment = "这波配合很漂亮，先照顾后排再找机会推进。".repeat(15) + "完整结尾。"
            fun showComment(text: String) {
                instrument.runOnMainSync {
                    val controller = field(service, "companion")
                    (field(controller, "mutable") as MutableStateFlow<CompanionState>).value =
                        CompanionState(active = true, message = "陪伴中，等待下一次观察", comment = text)
                    AssistantService::class.java.getDeclaredMethod("showCompanionComment", String::class.java).apply {
                        isAccessible = true; invoke(service, text)
                    }
                }
                assertNotNull(device.wait(Until.findObject(By.text(text)), 5000))
            }
            showComment(comment)
            assertAnchor(dockedAvatar)
            assertTrue("Docked comments open below the capsule", bounds("taskPill").top > bounds("bubble").bottom)
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
            assertAnchor(dockedAvatar)
            instrument.runOnMainSync {
                assertFalse("A docked conversation must not repeat the avatar", (field(service, "statusIcon") as View).isShown)
            }
            captureWithoutBlink(checkGesture = true)
            val input = send.parent.findObject(By.clazz("android.widget.EditText"))
            assertNotNull(input)
            input.click()
            input.text = "下一次请关注辅助的装备"
            assertAnchor(dockedAvatar)
            screenshot("companion-input")
            device.findObject(By.desc("发送")).click()
            await("Input reaches companion even on an excluded page") { service.companionState.value.message.contains("已保留你的输入") }
            assertTrue(service.companionState.value.active)
            assertEquals("Input must not create an automation task", TaskStatus.IDLE, service.state.value.status)
            assertEquals(saved.records, vault.read().records)
            assertAnchor(dockedAvatar)
            showComment("短消息也保持原位")
            assertAnchor(dockedAvatar)
            assertTrue("Comments still dismiss automatically", device.wait(Until.gone(By.text("短消息也保持原位")), 7000))
            assertAnchor(dockedAvatar)

            for ((name, x, y) in listOf(
                Triple("lower-left", 40, device.displayHeight * 3 / 4),
                Triple("upper-left", 40, device.displayHeight / 4),
                Triple("docked-left", 40, 20),
                // Rotate last: UiAutomator can retain gesture coordinates from the previous orientation.
                Triple("lower-right", device.displayWidth - 40, device.displayHeight * 3 / 4),
            )) {
                val beforeDrag = bounds("bubbleAvatar")
                device.drag(beforeDrag.centerX(), beforeDrag.centerY(), x, y, 30)
                device.waitForIdle(1000)
                val anchor = bounds("bubbleAvatar")
                assertEquals("$name must reach the requested screen edge", x > device.displayWidth / 2,
                    anchor.centerX() > device.displayWidth / 2)
                assertEquals("$name must reach the requested screen half", y > device.displayHeight / 2,
                    anchor.centerY() > device.displayHeight / 2)
                showComment(comment)
                assertAnchor(anchor)
                val dialogue = bounds("taskPill")
                assertTrue("Comment must not cover the ball", !Rect.intersects(anchor, dialogue))
                assertTrue("Comment stays on screen", dialogue.top >= 0 && dialogue.bottom <= device.displayHeight)
                val trigger = device.findObject(By.desc("Open Doubao 陪伴中，点按查看评论、输入或停止"))
                val triggerBounds = trigger.visibleBounds
                trigger.click()
                val sendAtAnchor = device.wait(Until.findObject(By.desc("发送")), 5000)
                assertNotNull("$name click=$triggerBounds, ball=${bounds("bubbleAvatar")}, chip=${bounds("bubble")}, expanded=${field(service, "expanded")}", sendAtAnchor)
                if (name == "lower-right") {
                    for (targetX in listOf(40, device.displayWidth - 40)) {
                        val before = bounds("bubbleAvatar")
                        device.drag(before.centerX(), before.centerY(), targetX, before.centerY(), 30)
                        device.waitForIdle(1000)
                        val moved = bounds("bubbleAvatar")
                        assertEquals("An expanded conversation must allow dragging to either edge",
                            targetX > device.displayWidth / 2, moved.centerX() > device.displayWidth / 2)
                        assertEquals("Horizontal dragging must keep the avatar's height", anchor.top, moved.top)
                        val movedPanel = bounds("panel")
                        assertTrue("The expanded conversation stays on screen after dragging",
                            movedPanel.left >= 0 && movedPanel.right <= device.displayWidth)
                    }
                }
                val editor = sendAtAnchor.parent
                    .findObject(By.clazz("android.widget.EditText"))
                assertAnchor(anchor)
                editor.click()
                editor.text = "位置检查"
                assertAnchor(anchor)
                val keyboardPackage = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD).substringBefore('/')
                val keyboard = device.wait(Until.findObject(By.pkg(keyboardPackage)), 5000)
                assertNotNull("Typing must show the keyboard", keyboard)
                val panelBounds = bounds("panel")
                val screenMetrics = service.getSystemService(WindowManager::class.java).currentWindowMetrics
                val keyboardTop = screenMetrics.bounds.height() - screenMetrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
                assertTrue("Input controls must stay above the keyboard", bounds("inputRow").bottom <= keyboardTop)
                assertTrue("The whole panel must stay above the keyboard: $panelBounds, keyboardTop=$keyboardTop",
                    panelBounds.bottom <= keyboardTop)
                val shortInputHeight = bounds("inputRow").height()
                val shortCommentHeight = bounds("statusText").height()
                editor.text = "请详细分析双方现在的阵容和装备，并告诉我接下来应该优先保护谁以及怎样安排推进顺序。".repeat(5)
                assertAnchor(anchor)
                instrument.runOnMainSync {
                    val text = field(service, "input") as TextView
                    assertTrue("Long input must reach the four-line limit", text.lineCount >= 4)
                }
                assertTrue("Multiline input must grow", bounds("inputRow").height() > shortInputHeight)
                val multilinePanel = bounds("panel")
                assertTrue("Multiline input must keep the panel above the keyboard: $multilinePanel, keyboardTop=$keyboardTop",
                    multilinePanel.top >= 0 && multilinePanel.bottom <= keyboardTop)
                assertTrue("Stop must stay fully visible", bounds("stopButton").bottom <= keyboardTop)
                screenshot("companion-$name")
                editor.text = "位置检查"
                assertAnchor(anchor)
                assertEquals("Shortening the input restores its height", shortInputHeight, bounds("inputRow").height())
                assertEquals("Shortening the input restores room for comments", shortCommentHeight, bounds("statusText").height())
                instrument.runOnMainSync { (field(service, "input") as android.widget.EditText).text.clear() }
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.pkg(keyboardPackage)), 5000))
                device.findObject(By.desc("Open Doubao 陪伴中，点按查看评论、输入或停止")).click()
                assertAnchor(anchor)
                assertTrue(device.wait(Until.gone(By.text(comment)), 7000))
                assertAnchor(anchor)
                if (name == "lower-right") {
                    try {
                        device.setOrientationLeft()
                        await("Rotation keeps the anchor on screen") {
                            val rotated = bounds("bubbleAvatar")
                            device.displayWidth > device.displayHeight && rotated.top >= 0 && rotated.bottom <= device.displayHeight &&
                                rotated.top == field(service, "companionAnchorY") as Int
                        }
                        val landscapeAnchor = bounds("bubbleAvatar")
                        screenshot("companion-rotation")
                        showComment(comment)
                        assertAnchor(landscapeAnchor)
                        val landscapeComment = bounds("taskPill")
                        assertTrue("Landscape comments stay on screen", landscapeComment.top >= 0 && landscapeComment.bottom <= device.displayHeight)
                        assertTrue("Landscape comments stay beside the ball",
                            landscapeAnchor.top - landscapeComment.bottom in 0..(8 * context.resources.displayMetrics.density).toInt())
                        screenshot("companion-landscape")
                        assertTrue(device.wait(Until.gone(By.text(comment)), 7000))
                        assertAnchor(landscapeAnchor)
                    } finally {
                        device.setOrientationNatural()
                        device.unfreezeRotation()
                        device.waitForIdle(1000)
                    }
                }
            }
        } catch (failure: Throwable) {
            screenshot("companion-failure")
            throw failure
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
