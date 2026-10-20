package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.core.Action
import com.moonrailgun.opendoubao.assistant.core.PhoneUnavailableException
import com.moonrailgun.opendoubao.assistant.phone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ShellDisplayTest {
    @Test fun independentDisplayCapturesAndActsWithoutChangingMainDisplay() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shellDisplayCheck") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val previousUiFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = context.contentResolver
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val previousBackend = shellBackend(context)
        val fixtureComponent = ComponentName(instrument.context.packageName, ShellDisplayFixtureActivity::class.java.name)
        val previousFixtureState = context.packageManager.getComponentEnabledSetting(fixtureComponent)
        val backend = InstrumentationRegistry.getArguments().getString("shellBackend") ?: BACKEND_ADB
        var helper: ParcelFileDescriptor? = null
        var phone: ShellPhone? = null
        var main: android.app.Activity? = null
        val active = MutableStateFlow(false)
        try {
            context.packageManager.setComponentEnabledSetting(fixtureComponent, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            val component = "${context.packageName}/${AssistantService::class.java.name}"
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (previousServices.orEmpty().split(':').filter { it.isNotBlank() } + component).distinct().joinToString(":"))
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            automation.dropShellPermissionIdentity()
            val service = withTimeout(8000) { AssistantService.connected.first { it != null }!! }
            main = instrument.startActivitySync(Intent(context, FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertEquals(Display.DEFAULT_DISPLAY, main.display!!.displayId)
            instrument.runOnMainSync {
                main.findViewById<android.widget.EditText>(R.id.fixture_note).apply {
                    setText("")
                    assertTrue(requestFocus())
                }
            }
            fun typeOnMain(text: String) {
                automation.executeShellCommand("input text $text").use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
                }
                instrument.waitForIdleSync()
            }
            typeOnMain("123")
            instrument.runOnMainSync {
                assertEquals("Main input works before creating a virtual display", "123",
                    main.findViewById<android.widget.EditText>(R.id.fixture_note).text.toString())
                main.findViewById<android.widget.EditText>(R.id.fixture_note).setText("")
            }
            chooseShellBackend(context, backend)
            if (backend == BACKEND_SHIZUKU) {
                withTimeout(5000) { while (!rikka.shizuku.Shizuku.pingBinder()) delay(100) }
                withContext(Dispatchers.Main) { ShellBridge.refresh(requestPermission = true) }
                if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val device = UiDevice.getInstance(instrument)
                    val allow = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Allow all the time|Allow|始终允许|允许"))), 5000)
                    checkNotNull(allow) { "Shizuku permission dialog missing" }.click()
                }
            } else {
                // Use the exact installed APK and shell entry point shown by the setup page.
                helper = automation.executeShellCommand("env CLASSPATH=${context.applicationInfo.sourceDir} app_process / ${ShellDisplayService::class.java.name}")
            }
            withTimeout(10_000) { while (!ShellBridge.ready(context)) delay(100) }
            if (backend == BACKEND_SHIZUKU) {
                val previousBinder = ShellBridge.service(context).asBinder()
                withContext(Dispatchers.Main) {
                    ShellBridge.disconnect()
                    ShellBridge.refresh()
                }
                withTimeout(10_000) { while (!ShellBridge.ready(context)) delay(100) }
                assertNotEquals("Immediate reconnect starts a new service", previousBinder, ShellBridge.service(context).asBinder())
            }
            val bridge = ShellBridge.service(context)
            val fixture = instrument.context.packageName
            assertNotEquals(context.applicationInfo.uid, context.packageManager.getApplicationInfo(fixture, 0).uid)
            withContext(Dispatchers.Main) {
                phone = ShellPhone(service, AndroidPhone(service), active) { fail(it) }
            }
            assertTrue(phone!!.execute(Action.OpenApp(fixture), null))
            val id = checkNotNull(ShellBridge.displayId.value)
            assertTrue(id > Display.DEFAULT_DISPLAY)
            var screen = phone!!.observe()
            assertEquals(fixture, screen.packageName)
            val image = phone!!.screenshot(screen)
            val bytes = Base64.decode(image, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue("A real non-empty screenshot", bitmap.width > 100 && bitmap.height > 100)
            bitmap.recycle()
            assertEquals(context.packageName, AndroidPhone(service).observe().packageName)
            val input = checkNotNull(screen.elements.firstOrNull { it.editable }) { "No input: $screen" }
            assertTrue(phone!!.execute(Action.Type(input.id, "independent input"), screen))
            screen = phone!!.observe()
            assertTrue(screen.elements.any { it.text == "independent input" })
            val button = checkNotNull(screen.elements.firstOrNull { it.text.equals("Open details", ignoreCase = true) }) { "No button: $screen" }
            val b = button.bounds
            assertTrue(phone!!.execute(Action.Tap((b.left + b.right) / 2, (b.top + b.bottom) / 2), screen, phone!!.screenshot(screen)))
            assertTrue(phone!!.observe().elements.any { it.text == "Virtual details" })
            assertEquals(context.packageName, AndroidPhone(service).observe().packageName)
            typeOnMain("456789")
            instrument.runOnMainSync {
                assertEquals("Main keyboard keeps its focus", "456789",
                    main.findViewById<android.widget.EditText>(R.id.fixture_note).text.toString())
            }
            if (InstrumentationRegistry.getArguments().getString("previewCheck") == "true") {
                instrument.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "scrcpy preview display: $id\n")
                })
                delay(15_000)
            }
            assertTrue(phone!!.execute(Action.Back, null))
            assertEquals(context.packageName, AndroidPhone(service).observe().packageName)
            assertTrue(phone!!.execute(Action.OpenApp(fixture), null))
            screen = phone!!.observe()
            val secure = screen.elements.single { it.text.equals("Protected page", ignoreCase = true) }
            assertTrue(phone!!.execute(Action.Click(secure.id), screen))
            val protectedCapture = runCatching { phone!!.screenshot(phone!!.observe()) }
            assertTrue("FLAG_SECURE must also block virtual display screenshots",
                protectedCapture.exceptionOrNull() is PhoneUnavailableException)
            withContext(Dispatchers.Main) { phone!!.handOver() }
            assertNull(ShellBridge.displayId.value)
            withTimeout(5000) {
                while (true) {
                    val current = try { AndroidPhone(service).observe().packageName }
                        catch (_: PhoneUnavailableException) { null }
                    if (current == fixture) break
                    delay(100)
                }
            }
            assertFalse(active.value)
            phone!!.close()
            phone = ShellPhone(service, AndroidPhone(service), active) { /* Checked via state below. */ }
            assertTrue(phone!!.execute(Action.OpenApp(fixture), null))
            val secondId = checkNotNull(ShellBridge.displayId.value)
            runCatching { bridge.destroy() }
            withTimeout(5000) { while (ShellBridge.ready(context) || active.value) delay(100) }
            assertNull(service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(secondId))
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream",
                "$backend virtual display $id: third-party UID, screenshot, text, coordinate tap, Back, main-screen isolation, protected page, handover and bridge death passed.\n") })
        } finally {
            phone?.close()
            ShellBridge.displayId.value = null
            chooseShellBackend(context, previousBackend)
            helper?.close()
            withContext(Dispatchers.Main) { main?.finishAndRemoveTask() }
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
            context.packageManager.setComponentEnabledSetting(fixtureComponent, previousFixtureState, PackageManager.DONT_KILL_APP)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
            automation.dropShellPermissionIdentity()
            Configurator.getInstance().uiAutomationFlags = previousUiFlags
        }
    }
}
