package com.openodoubao.assistant

import android.Manifest
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.google.zxing.client.android.Intents
import com.openodoubao.assistant.data.Vault
import com.openodoubao.assistant.phone.AssistantService
import com.openodoubao.assistant.remote.PairingCaptureActivity
import com.openodoubao.assistant.remote.RemoteConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class PairingScanTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val device = run {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 8000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertTrue("Timed out waiting for scanner state", condition())
    }

    private fun scanner(): PairingCaptureActivity? {
        var activity: PairingCaptureActivity? = null
        instrumentation.runOnMainSync {
            activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }
                .filterIsInstance<PairingCaptureActivity>().singleOrNull()
        }
        return activity
    }

    private fun openScanner() {
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("扫码绑定")))
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == "扫码绑定") return node
            return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::find) }
        }
        var node = checkNotNull(automation.windows.firstNotNullOfOrNull { it.root?.let(::find) })
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue("Scan button should be enabled", node.isEnabled)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { scanner() != null }
    }

    private fun showsError(text: String) {
        waitFor { scanner() == null }
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textContains(text)))
    }

    private fun scanResult(contents: String, recreateMain: Boolean = false) {
        var main: MainActivity? = null
        instrumentation.runOnMainSync {
            main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        openScanner()
        if (recreateMain) {
            instrumentation.runOnMainSync { main!!.recreate() }
            waitFor { main!!.isDestroyed }
        }
        val activity = scanner()!!
        instrumentation.runOnMainSync {
            activity.apply {
                assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                setResult(Activity.RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, contents)
                    .putExtra(Intents.Scan.RESULT_FORMAT, "QR_CODE"))
                finish()
            }
        }
        waitFor { scanner() == null }
    }

    @Test fun permissionDenialCancellationAndScanResultsUseTheExistingPairingBoundary() {
        val vault = Vault(context)
        val saved = vault.read()
        val priorAccessibility = Settings.Secure.getInt(context.contentResolver, "accessibility_enabled", 0)
        val priorServices = Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
        val component = "${context.packageName}/${AssistantService::class.java.name}"
        val others = priorServices.split(':').filter { it.isNotBlank() && it != component }.joinToString(":")
        fun services(value: String) = shell(if (value.isEmpty()) "settings delete secure enabled_accessibility_services"
            else "settings put secure enabled_accessibility_services $value")
        try {
            services(others)
            waitFor { AssistantService.connected.value == null }
            instrumentation.runOnMainSync { vault.write(saved.copy(remote = RemoteConfig())) }
            services(listOf(others, component).filter(String::isNotEmpty).joinToString(":"))
            shell("settings put secure accessibility_enabled 1")
            waitFor { AssistantService.connected.value?.remote != null }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("设置")), 5000))
            device.findObject(UiSelector().description("设置")).click()
            val receiver = AssistantService.connected.value!!.remote!!

            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                openScanner()
                val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), 5000)
                assertNotNull("Camera permission should be requested only after tapping scan", deny)
                deny.click()
                showsError("未获得相机权限")
                assertFalse(receiver.state.value.enabled)
                assertNull(vault.read().remote.binding)
            }
            automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            openScanner()
            device.pressBack()
            waitFor { scanner() == null }
            assertFalse(receiver.state.value.enabled)
            assertNull(vault.read().remote.binding)

            scanResult("https://example.com/")
            showsError("完整设备码")
            assertFalse(receiver.state.value.enabled)
            assertNull(vault.read().remote.binding)

            val code = "odoubao://pair?v=1&host=192.168.255.254&port=1&fingerprint=${"a".repeat(64)}&token=${"b".repeat(64)}"
            scanResult(code + "&host=10.0.0.1")
            showsError("字段无效或重复")
            assertFalse(receiver.state.value.enabled)
            scanResult(code, recreateMain = true)
            waitFor { receiver.state.value.enabled }
            assertEquals("192.168.255.254:1", receiver.state.value.address)
            assertNull("Credentials are not saved before the TLS handshake succeeds", vault.read().remote.binding)
        } finally {
            services(others)
            waitFor { AssistantService.connected.value == null }
            instrumentation.runOnMainSync { vault.write(saved) }
            services(priorServices)
            shell("settings put secure accessibility_enabled $priorAccessibility")
        }
    }
}
