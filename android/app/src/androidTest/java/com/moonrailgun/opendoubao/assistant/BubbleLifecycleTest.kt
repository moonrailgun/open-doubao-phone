package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BubbleLifecycleTest {
    @Test fun manualExitSurvivesCaptureAndActivityRestarts() {
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
        val device = UiDevice.getInstance(instrument)
        val bubble = By.desc("Open Doubao，点按查看结果或下达命令")
        var activity: Activity? = null
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 8000
            while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        fun connect(value: Boolean) {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (value) listOf(others, component).filter(String::isNotEmpty).joinToString(":") else others)
            if (value) Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            await("Accessibility connection: $value") { (AssistantService.connected.value != null) == value }
        }
        fun openApp() {
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        fun toggle(label: String) {
            val button = device.wait(Until.findObject(By.clickable(true).hasChild(By.text(label))), 5000)
            assertNotNull("Missing bubble action: $label", button)
            assertTrue(button.isEnabled)
            button.click()
        }
        fun hidden() {
            assertTrue("Bubble must remain hidden", device.wait(Until.gone(bubble), 5000))
            assertTrue("Expanded panel must also close", device.wait(Until.gone(By.desc("收起")), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("启动悬浮球")), 5000))
        }
        try {
            connect(false)
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            openApp()
            val disabled = device.wait(Until.findObject(By.clickable(true).hasChild(By.text("启动悬浮球"))), 5000)
            assertNotNull("Show the start action before accessibility is connected", disabled)
            assertFalse(disabled.isEnabled)

            connect(true)
            assertTrue("Default bubble should appear", device.wait(Until.hasObject(bubble), 5000))
            val service = AssistantService.connected.value!!
            instrument.runOnMainSync { service.setOverlayVisible(false) }
            assertTrue(device.wait(Until.gone(bubble), 5000))
            assertTrue("Temporary hiding must not change the toggle", device.hasObject(By.text("退出悬浮球")))
            instrument.runOnMainSync { service.setOverlayVisible(true) }
            assertTrue(device.wait(Until.hasObject(bubble), 5000))

            toggle("退出悬浮球")
            hidden()
            assertSame("Exiting the bubble keeps accessibility connected", service, AssistantService.connected.value)
            instrument.runOnMainSync { service.setOverlayVisible(false); service.setOverlayVisible(true) }
            hidden()
            openApp()
            hidden()

            toggle("启动悬浮球")
            assertTrue(device.wait(Until.hasObject(bubble), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("退出悬浮球")), 5000))
            device.findObject(bubble).click()
            val collapse = device.wait(Until.findObject(By.desc("收起")), 5000)
            assertNotNull("Bubble should expand", collapse)
            assertFalse("Keep the exit action in the main app", device.hasObject(By.desc("退出悬浮球")))
            val field = collapse.parent.parent.findObject(By.clazz("android.widget.EditText"))
            assertNotNull("Use the bubble input, not the app input underneath", field)
            field.click()
            await("Bubble input should open the keyboard") {
                automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            toggle("退出悬浮球")
            hidden()
            await("Exiting must dismiss the keyboard") {
                automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }

            toggle("启动悬浮球")
            assertTrue(device.wait(Until.hasObject(bubble), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("退出悬浮球")), 5000))
        } finally {
            try {
                connect(false)
                instrument.runOnMainSync { activity?.finishAndRemoveTask() }
            } finally {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
                automation.dropShellPermissionIdentity()
                Configurator.getInstance().uiAutomationFlags = uiFlags
            }
        }
    }
}
