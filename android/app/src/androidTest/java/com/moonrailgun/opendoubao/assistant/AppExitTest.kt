package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import com.moonrailgun.opendoubao.assistant.remote.Pairing
import com.moonrailgun.opendoubao.assistant.remote.RemoteConfig
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime
import com.moonrailgun.opendoubao.assistant.voice.VoiceService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

class AppExitTest {
    @Test fun bubbleThenTaskRemovalStopsWork() = exerciseExit(bubbleFirst = true)
    @Test fun taskRemovalThenBubbleStopsWork() = exerciseExit(bubbleFirst = false)

    private fun exerciseExit(bubbleFirst: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appExitCheck") == "true")
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
        val preferences = listOf("overlay", "lifecycle", "voice", "task").associateWith {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all
        }
        val vault = Vault(context)
        val original = withContext(Dispatchers.Main) { vault.read() }
        val file = AtomicFile(File(context.noBackupFilesDir, "assistant.enc"))
        val encrypted = if (file.baseFile.exists()) file.readFully() else null
        // Hold TLS handshakes locally so a real model request stays running without contacting a provider.
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val accepting = launch(Dispatchers.IO) {
            try { while (isActive) sockets += server.accept() } catch (_: java.io.IOException) { }
        }
        var activity: Activity? = null
        suspend fun connect(value: Boolean) {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (value) listOf(others, component).filter(String::isNotEmpty).joinToString(":") else others)
            if (value) Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            withTimeout(8000) { AssistantService.connected.first { (it != null) == value } }
        }
        suspend fun await(message: String, condition: () -> Boolean) {
            val done = withTimeoutOrNull(8000) { while (!withContext(Dispatchers.Main) { condition() }) delay(50); true }
            assertEquals(message, true, done)
        }
        fun openApp() {
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        try {
            assertFalse("Finish existing tasks first", original.records.any { it.status in setOf("RUNNING", "PAUSED") })
            connect(false)
            val endpoint = "https://127.0.0.1:${server.localPort}/v1"
            withContext(Dispatchers.Main) {
                context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putBoolean("wake_enabled", true).commit()
                vault.write(original.copy(config = ModelConfig(endpoint, "test", "test", endpoint, "test", "test"),
                    onlineTtsEndpoint = endpoint, onlineTtsToken = "test",
                    remote = RemoteConfig("exit-test", Pairing("127.0.0.1", server.localPort, "0".repeat(64), "test"), true)))
            }
            val device = UiDevice.getInstance(instrument)
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            openApp()
            connect(true)
            val service = AssistantService.connected.value!!
            withContext(Dispatchers.Main) { service.setBubbleActive(true); service.startTask("退出机制回归检查") }
            await("Real task and remote receiver must connect locally") { sockets.size >= 2 }
            withContext(Dispatchers.Main) { assertTrue(VoiceRuntime.speak(context, "退出时应中断这段测试语音")) }
            val manager = context.getSystemService(ActivityManager::class.java)
            @Suppress("DEPRECATION")
            fun voiceRunning() = manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == VoiceService::class.java.name }
            await("Voice foreground service must start") { voiceRunning() }
            assertEquals(TaskStatus.RUNNING, service.state.value.status)
            if (bubbleFirst) {
                withContext(Dispatchers.Main) { service.setBubbleActive(false) }
                device.pressHome()
                delay(500)
                assertEquals("Home must not exit the task", TaskStatus.RUNNING, service.state.value.status)
                assertNotNull(service.remote)
                withContext(Dispatchers.Main) { activity!!.finishAndRemoveTask() }
            } else {
                withContext(Dispatchers.Main) { activity!!.finishAndRemoveTask() }
                delay(500)
                assertEquals("The enabled bubble keeps work alive", TaskStatus.RUNNING, service.state.value.status)
                assertNotNull(service.remote)
                withContext(Dispatchers.Main) { service.setBubbleActive(false) }
            }
            await("Closing both entry points must stop the running task") { service.state.value.status == TaskStatus.STOPPED }
            assertNull("Remote receiving must stop too", service.remote)
            await("Voice foreground service must stop") { !voiceRunning() }
            withContext(Dispatchers.Main) {
                assertFalse("Late speech must not restart the app", VoiceRuntime.speak(context, "迟到的语音"))
                context.startService(Intent(context, VoiceService::class.java))
                VoiceRuntime.assistantReady(context, true)
            }
            delay(300)
            await("A service restart must stay stopped") { !voiceRunning() }
            assertEquals("空闲", VoiceRuntime.state.value)
            await("The final task record must survive exit") { vault.read().records.first { it.id == service.taskId }.status == "STOPPED" }
            withContext(Dispatchers.Main) {
                assertTrue("Closed services must reject late commands", runCatching { service.startTask("迟到的指令") }.isFailure)
            }
            assertTrue("Keep remote preferences", vault.read().remote.enabled)
            assertTrue("Keep wake preferences", context.getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("wake_enabled", false))
            connect(false)
            connect(true)
            val rebound = AssistantService.connected.value!!
            assertNull("System rebinding must not restart remote receiving", rebound.remote)
            assertFalse(rebound.bubbleActive.value)
            openApp()
            await("Explicit reopening restores remote receiving") { rebound.remote != null }
            assertFalse("Reopening must not restore the exited bubble", rebound.bubbleActive.value)
            withContext(Dispatchers.Main) { rebound.startTask("重新打开后的任务"); rebound.stop() }
            await("Reopened tasks still save normally") { vault.read().records.any { it.id == rebound.taskId && it.status == "STOPPED" } }
        } finally {
            try {
                withContext(Dispatchers.Main) {
                    VoiceRuntime.assistantReady(context, false)
                    VoiceRuntime.exit(context)
                    AssistantService.connected.value?.stop()
                }
                connect(false)
                withContext(Dispatchers.Main) { activity?.finishAndRemoveTask() }
                delay(300)
                withContext(Dispatchers.Main) {
                    if (encrypted == null) file.delete() else {
                        val stream = file.startWrite()
                        try { stream.write(encrypted); file.finishWrite(stream) }
                        catch (error: Exception) { file.failWrite(stream); throw error }
                    }
                    preferences.forEach { (name, saved) ->
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply {
                            saved.forEach { (key, value) -> when (value) {
                                is Boolean -> putBoolean(key, value)
                                is String -> putString(key, value)
                                is Int -> putInt(key, value)
                                is Long -> putLong(key, value)
                                is Float -> putFloat(key, value)
                                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                            } }
                        }.commit()
                    }
                }
            } finally {
                server.close(); accepting.cancelAndJoin()
                synchronized(sockets) { sockets.forEach { it.close() } }
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
                automation.dropShellPermissionIdentity()
                Configurator.getInstance().uiAutomationFlags = uiFlags
            }
        }
    }
}
