package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.moonrailgun.opendoubao.assistant.core.*
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OfflineTaskTest {
    @Test fun realNetworkFailureIsVisibleOnThePhonePage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineUiCheck") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val uiFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = context.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val component = "${context.packageName}/${AssistantService::class.java.name}"
        val others = services.orEmpty().split(':').filter { it.isNotBlank() && it != component }.joinToString(":")
        val vault = Vault(context)
        val original = withContext(Dispatchers.Main) { vault.read() }
        val file = AtomicFile(File(context.noBackupFilesDir, "assistant.enc"))
        val encrypted = if (file.baseFile.exists()) file.readFully() else null
        var activity: android.app.Activity? = null
        try {
            assertTrue("Disconnect all networks before this opt-in test",
                context.getSystemService(ConnectivityManager::class.java).allNetworks.isEmpty())
            assertFalse("Finish existing tasks before this test", original.records.any { it.status in setOf("RUNNING", "PAUSED") })
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
            withTimeout(5000) { AssistantService.connected.first { it == null } }
            withContext(Dispatchers.Main) {
                vault.write(original.copy(config = ModelConfig("https://example.com/v1", "offline-test", "offline-test",
                    "https://example.com/v1", "offline-test", "offline-test"), apps = setOf(context.packageName), consent = true))
            }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, listOf(others, component).filter { it.isNotBlank() }.joinToString(":"))
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val service = withTimeout(8000) { AssistantService.connected.first { it != null }!! }
            val device = UiDevice.getInstance(instrument)
            device.wakeUp()
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withContext(Dispatchers.Main) { context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null) }
            withTimeout(5000) { while (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) delay(100) }
            withContext(Dispatchers.Main) { service.startTask("离线页面错误检查") }
            val result = withTimeoutOrNull(20_000) { service.state.first { it.status == TaskStatus.FAILED } }
                ?: error("Expected network failure, got ${service.state.value}; connected=${AssistantService.connected.value === service}")
            assertTrue(result.message, result.message.contains("规划") && result.message.contains("网络"))
            assertEquals(0, result.steps)
            assertTrue("The actual task error must be visible in MainActivity", device.wait(
                Until.hasObject(By.pkg(context.packageName).hasDescendant(By.text("Open Odoubao"))
                    .hasDescendant(By.text(result.message))), 5000))
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Visible offline task message: ${result.message}\n") })
        } finally {
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
                val disconnected = runCatching { withTimeout(5000) { AssistantService.connected.first { it == null } } }
                withContext(Dispatchers.Main) {
                    activity?.finishAndRemoveTask()
                    // Restore the exact ciphertext so upgrade checks and the user's records remain unchanged.
                    if (encrypted == null) file.delete() else {
                        val stream = file.startWrite()
                        try { stream.write(encrypted); file.finishWrite(stream) }
                        catch (error: Exception) { file.failWrite(stream); throw error }
                    }
                    assertEquals(original, vault.read())
                }
                disconnected.getOrThrow()
            } finally {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
                automation.dropShellPermissionIdentity()
                Configurator.getInstance().uiAutomationFlags = uiFlags
            }
        }
    }

    @Test fun realNetworkFailureReachesTaskMessageWithoutPhoneActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineCheck") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_NETWORK_STATE)
        try {
            assertTrue("Disconnect all networks before this opt-in test",
                instrument.targetContext.getSystemService(ConnectivityManager::class.java).allNetworks.isEmpty())
        } finally { instrument.uiAutomation.dropShellPermissionIdentity() }
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe(): Screen = error("Offline task must not inspect the phone")
            override suspend fun screenshot(screen: Screen): String = error("Offline task must not capture the screen")
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean =
                error("Offline task must not operate the phone")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val models = CloudModels(ModelConfig("https://example.com/v1", "offline-test", "offline-test",
                "https://example.com/v1", "offline-test", "offline-test"))
            val task = TaskController(scope, phone, models)
            withContext(Dispatchers.Main) { task.start("离线错误检查", setOf(instrument.targetContext.packageName)) }
            val result = withTimeout(20_000) { task.state.first { it.status == TaskStatus.FAILED } }
            assertTrue(result.message, result.message.contains("规划") && result.message.contains("网络"))
            assertEquals(0, result.steps)
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Offline task message: ${result.message}\n") })
        } finally { scope.cancel() }
    }
}
