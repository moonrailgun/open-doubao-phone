package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.moonrailgun.opendoubao.assistant.core.*
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.phone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Collections

/** Opt-in real network/device comparison; configuration, task history and memory are never written. */
class JevVisionLiveTest {
    @Test fun compare() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("jevVisionLive") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val saved = Vault(context).read()
        fun emit(value: JSONObject) = instrument.sendStatus(0, Bundle().apply {
            putString("stream", "JEV_LIVE " + value.toString() + "\n")
        })
        emit(JSONObject().put("event", "config").put("jevHost", URI(saved.config.jevEndpoint).host)
            .put("jevModel", saved.config.jevModel).put("hasJevKey", saved.config.hasJev)
            .put("chatHost", URI(saved.config.chatEndpoint).host).put("chatModel", saved.config.chatModel)
            .put("hasChatKey", saved.config.chatKey.isNotBlank()).put("savedVision", saved.config.jevVision))
        if (args.getString("metadataOnly") == "true") return@runBlocking
        assertFalse("Finish existing tasks first", saved.records.any { it.status in setOf("RUNNING", "PAUSED") })
        val vaultFile = File(context.noBackupFilesDir, "assistant.enc")
        val encrypted = vaultFile.readBytes()
        val uiConfig = Configurator.getInstance()
        val oldFlags = uiConfig.uiAutomationFlags
        uiConfig.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        val automation = instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        val resolver = context.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val component = ComponentName(context, AssistantService::class.java)
        val device = UiDevice.getInstance(instrument)
        val results = JSONArray()
        try {
            // Instrumentation force-stops the target process; reconnect its accessibility service.
            if (AssistantService.connected.value == null) {
                val others = services.orEmpty().split(':').filter {
                    it.isNotBlank() && ComponentName.unflattenFromString(it) != component
                }.joinToString(":")
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
                delay(300)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    listOf(others, component.flattenToString()).filter { it.isNotBlank() }.joinToString(":"))
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            }
            val service = withTimeout(10_000) { AssistantService.connected.first { it != null }!! }
            assertTrue("Finish the current task first", service.state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED))
            val catalog = loadAppCatalog(context)
            val apps = allowedApps(catalog, saved.excludedApps)
            val case = args.getString("case") ?: "bluetooth"
            require(case in setOf("bluetooth", "android_version", "visual_tap", "visual_read"))
            val visual = case.startsWith("visual_")
            val goal = args.getString("goal") ?: when (case) {
                "android_version" -> "在系统设置中查看 Android 版本号并告诉我。只读取，不修改设置。"
                "visual_tap" -> "点击当前页面里的绿色圆形一次，并根据页面反馈告诉我结果。不要点击红色方形。"
                "visual_read" -> "读取当前页面图片中的六位编号，告诉我具体数字。只读取，不点击或输入。"
                else -> "在系统设置中查看蓝牙设备名称并告诉我。只读取，不修改设置。"
            }
            val codes = List(2) { (100000 + SecureRandom().nextInt(900000)).toString() }
            val modes = (args.getString("modes") ?: "off,on,on,off,off,on").split(',')
            require(modes.all { it == "off" || it == "on" })
            for ((index, mode) in modes.withIndex()) {
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                val expected = args.getString("expected") ?: when (case) {
                    "android_version" -> android.os.Build.VERSION.RELEASE
                    "visual_tap" -> "绿色圆形"
                    "visual_read" -> codes[(index / 2) % codes.size]
                    else -> Settings.Global.getString(context.contentResolver, "device_name")
                }
                if (visual) instrument.context.startActivity(Intent(instrument.context, JevVisionFixtureActivity::class.java)
                    .putExtra("code", codes[(index / 2) % codes.size]).putExtra("left", index / 2 % 2 == 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                else {
                    device.executeShellCommand("am force-stop com.android.settings")
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                delay(1500)
                val initial = withContext(Dispatchers.Main) { service.phone.observe() }
                assertEquals(if (visual) instrument.context.packageName else "com.android.settings", initial.packageName)
                if (visual) {
                    assertFalse("Image-only code must not leak into text input", initial.json().toString().contains(codes[(index / 2) % codes.size]))
                    assertFalse("Image-only control must have no textual node", initial.json().toString().contains("绿色"))
                }
                val config = saved.config.copy(jevVision = mode == "on",
                    jevModel = args.getString("jevModel") ?: saved.config.jevModel)
                config.validate()
                val calls = Collections.synchronizedList(mutableListOf<JSONObject>())
                val transport = HttpTransport(secrets = listOf(config.jevKey, config.chatKey), log = { event ->
                    val copy = JSONObject(event.toString())
                    if (copy.optString("event") == "request") {
                        val body = copy.getString("body")
                        val json = JSONObject(body)
                        copy.remove("body")
                        copy.put("model", json.optString("model")).put("bytes", body.toByteArray().size)
                            .put("hasImage", body.replace("\\/", "/").contains("data:image/") || body.contains("data_base64"))
                            .put("questions", json.optJSONObject("questions")?.names())
                    }
                    calls += copy
                })
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val models = CloudModels(config, transport, compactScreens = compactScreens(context),
                    appLabels = catalog.associate { it.packageName to it.label })
                val controller = TaskController(scope, service.phone, models)
                emit(JSONObject().put("event", "start").put("case", case).put("round", index + 1).put("mode", mode).put("goal", goal))
                val start = SystemClock.elapsedRealtime()
                try {
                    withContext(Dispatchers.Main) { controller.start(goal, apps) }
                    val result = withTimeout(310_000) { controller.state.first {
                        it.status !in setOf(TaskStatus.IDLE, TaskStatus.RUNNING)
                    } }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    withContext(Dispatchers.Main) { controller.stop() }
                    scope.coroutineContext.job.cancelAndJoin()
                    val fixtureVerified = !visual || device.hasObject(By.text(if (case == "visual_tap")
                        "绿色圆形已点击 1 次" else "视觉测试：等待操作"))
                    val report = JSONObject().put("event", "result").put("case", case).put("round", index + 1).put("mode", mode)
                        .put("durationMs", elapsed).put("status", result.status.name).put("steps", result.steps)
                        .put("result", result.result).put("message", result.message)
                        .put("correct", result.status == TaskStatus.COMPLETED && result.result.contains(expected) && fixtureVerified)
                        .put("expected", expected).put("fixtureVerified", fixtureVerified)
                        .put("trace", JSONArray(result.trace)).put("calls", JSONArray(calls.toList()))
                    results.put(report)
                    emit(report)
                    if (config.jevVision) {
                        val requests = calls.filter { it.optString("event") == "request" }
                        assertTrue("Only Jev and vision recovery may receive screenshots", requests.filter { it.getBoolean("hasImage") }
                            .all { it.getString("stage") in setOf("Jev 多模态", "视觉模型", "视觉模型重试") })
                        assertTrue("Jev multimodal requests must include the screenshot", requests
                            .filter { it.getString("stage") == "Jev 多模态" }.all { it.getBoolean("hasImage") })
                    }
                } finally {
                    withContext(Dispatchers.Main) { controller.stop() }
                    scope.coroutineContext.job.cancelAndJoin()
                }
            }
            assertEquals("All runs must finish with the verified result", modes.size,
                (0 until results.length()).count { results.getJSONObject(it).getBoolean("correct") })
        } finally {
            device.pressHome()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
            automation.dropShellPermissionIdentity()
            uiConfig.uiAutomationFlags = oldFlags
            assertEquals(saved, Vault(context).read())
            assertArrayEquals("User configuration/history must stay byte-for-byte unchanged", encrypted, vaultFile.readBytes())
        }
    }
}
