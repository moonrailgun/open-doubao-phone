package com.moonrailgun.opendoubao.assistant

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.core.Action
import com.moonrailgun.opendoubao.assistant.core.ActionPolicy
import com.moonrailgun.opendoubao.assistant.core.Permission
import com.moonrailgun.opendoubao.assistant.core.Models
import com.moonrailgun.opendoubao.assistant.core.Plan
import com.moonrailgun.opendoubao.assistant.core.Previous
import com.moonrailgun.opendoubao.assistant.core.Candidate
import com.moonrailgun.opendoubao.assistant.core.Decision
import com.moonrailgun.opendoubao.assistant.core.Effect
import com.moonrailgun.opendoubao.assistant.core.Evidence
import com.moonrailgun.opendoubao.assistant.core.ModelStep
import com.moonrailgun.opendoubao.assistant.core.TaskState
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.core.Phone
import com.moonrailgun.opendoubao.assistant.core.PhoneUnavailableException
import com.moonrailgun.opendoubao.assistant.core.Screen
import com.moonrailgun.opendoubao.assistant.core.TaskController
import com.moonrailgun.opendoubao.assistant.data.Record
import com.moonrailgun.opendoubao.assistant.data.Saved
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.remote.RemoteCommands
import com.moonrailgun.opendoubao.assistant.remote.RemoteReceiver
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import com.moonrailgun.opendoubao.assistant.phone.allowedApps
import com.moonrailgun.opendoubao.assistant.phone.loadAppCatalog
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun remoteModelConfigPersistsAndRefreshesTheOpenPhonePage() = runBlocking {
        val service = fixture()
        val vault = Vault(context)
        val before = withContext(Dispatchers.Main) { vault.read() }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        try {
            withContext(Dispatchers.Main) {
                vault.write(before.copy(config = ModelConfig()))
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.text("还没有设置模型")), 5000))
            withContext(Dispatchers.Main) {
                // Exercise the service's real persistence callback without opening a network listener.
                val commands = RemoteReceiver::class.java.getDeclaredField("commands")
                    .apply { isAccessible = true }.get(service.remote) as RemoteCommands
                val config = ModelConfig(jevKey = "integration-jev-secret", chatEndpoint = "https://example.com/v1",
                    chatModel = "integration-model", chatKey = "integration-chat-secret", language = "zh-CN")
                val params = JSONObject().put("jevEndpoint", config.jevEndpoint).put("jevModel", config.jevModel)
                    .put("jevKey", config.jevKey).put("chatEndpoint", config.chatEndpoint)
                    .put("chatModel", config.chatModel).put("chatKey", config.chatKey).put("language", config.language)
                val response = commands.handle(JSONObject().put("type", "request").put("id", java.util.UUID.randomUUID().toString())
                    .put("method", "config.set").put("params", params).toString())
                assertTrue(response.toString(), response.getBoolean("ok"))
                assertFalse(response.toString().contains("secret"))
                assertEquals(before.copy(config = config), vault.read())
                val encrypted = File(context.noBackupFilesDir, "assistant.enc").readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(encrypted.contains(config.jevKey))
                assertFalse(encrypted.contains(config.chatKey))
            }
            assertTrue("Open page must observe the remotely saved configuration", device.wait(
                androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("还没有设置模型")), 5000))
        } finally {
            withContext(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() }
                vault.write(before)
            }
        }
    }

    private suspend fun fixture(secure: Boolean = false, canvas: Boolean = false): AssistantService {
        check(context.getSharedPreferences("fixture", Context.MODE_PRIVATE).edit().clear().commit())
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Instrumentation force-stops the target process, so reconnect its accessibility service.
        if (AssistantService.connected.value == null) {
            fun shell(command: String) {
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
            }
            val component = "${context.packageName}/${AssistantService::class.java.name}"
            val prior = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")
                .orEmpty().split(':').filter { it.isNotBlank() && it != component }
            if (prior.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${prior.joinToString(":")}")
            delay(300)
            shell("settings put secure enabled_accessibility_services ${(prior + component).joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(context, FixtureActivity::class.java).putExtra("secure", secure).putExtra("canvas", canvas)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        delay(1000)
        return withTimeout(8000) {
            while (AssistantService.connected.value == null) delay(100)
            AssistantService.connected.value!!
        }
    }

    @Test fun pixelOnlyChangesInvalidateCoordinatesAtTheNativeExecutor() = runBlocking {
        val service = fixture(canvas = true)
        withContext(Dispatchers.Main) {
            val phone = service.phone
            val page = phone.observe()
            val before = phone.screenshot(page)
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() as FixtureActivity
            val canvas = activity.findViewById<android.view.View>(R.id.fixture_canvas)
            val location = IntArray(2).also(canvas::getLocationOnScreen)
            val tap = Action.Tap(location[0] + canvas.width / 2, location[1] + canvas.height / 2)
            canvas.setBackgroundColor(android.graphics.Color.BLUE)
            delay(100)
            assertEquals(page.fingerprint, phone.observe().fingerprint)
            assertFalse(phone.execute(tap, page))
            assertFalse(phone.execute(tap, page, before))
            assertEquals(0, context.getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("canvas_taps", 0))
            assertEquals("Canvas tap must be eligible", Permission.ALLOW, ActionPolicy.assess(tap, page, setOf(context.packageName)))
            val fresh = phone.screenshot(page)
            val repeated = phone.screenshot(page)
            assertTrue("Stable canvas must produce identical screenshots", fresh == repeated)
            assertTrue("Fresh canvas tap must succeed", phone.execute(tap, page, fresh))
            assertEquals(1, context.getSharedPreferences("fixture", Context.MODE_PRIVATE).getInt("canvas_taps", 0))
        }
    }

    @Test fun controllerAndCloudProtocolsDriveNativeDraftAndVerifySavedResult() = runBlocking {
        val service = fixture()
        val draft = "Controller integration draft"
        var summaries = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val response = if (body.optJSONObject("questions")?.has("intent") == true) {
                """{"answers":{"intent":{"type":"choice","choice":"task","confidence":1,"probabilities":{"chat":0,"task":1}}}}"""
            } else if (body.has("questions")) {
                val elements = body.getJSONObject("state").getJSONObject("screen").getJSONArray("elements")
                val nodes = (0 until elements.length()).map { elements.getJSONObject(it) }
                val saved = nodes.any { it.getString("text") == "Draft saved: $draft" }
                val filled = nodes.any { it.getString("resource").endsWith("/fixture_note") && it.getString("text") == draft }
                val criteria = body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria")
                val selected = if (saved) "none" else criteria.keys().asSequence().first { id ->
                    if (id == "none") false else {
                        val action = criteria.getJSONObject(id).getJSONObject("action")
                        if (!filled) action.getString("action") == "type" && action.getString("text") == draft
                        else action.getString("action") == "click" && nodes.any {
                            it.getString("id") == action.getString("node") && it.getString("resource").endsWith("/fixture_apply")
                        }
                    }
                }
                val probabilities = JSONObject().apply { criteria.keys().forEach { put(it, if (it == selected) 1 else 0) } }
                JSONObject().put("answers", JSONObject()
                    .put("next", JSONObject().put("type", "choice").put("choice", selected).put("confidence", 1).put("probabilities", probabilities))
                    .put("done", JSONObject().put("type", "noul").put("noul", if (saved) 1 else 0))
                    .put("effect", JSONObject("""{"type":"choice","choice":"unknown","confidence":1,"probabilities":{"yes":0,"no":0,"unknown":1}}"""))).toString()
            } else {
                val messages = body.getJSONArray("messages")
                val answer = if (messages.getJSONObject(0).getString("content").startsWith("Plan")) {
                    JSONObject().put("app", context.packageName).put("steps", JSONArray(listOf("fill draft", "apply draft")))
                        .put("texts", JSONArray(listOf(draft))).toString()
                } else {
                    check(messages.getJSONObject(1).getString("content").contains("Draft saved: $draft"))
                    summaries++
                    "Verified saved draft: $draft"
                }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)))).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatModel = "test", chatKey = "test"), HttpTransport(client))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Only app launch is redirected to the isolated debug page; observation and all task actions use AndroidPhone.
        val phone = object : Phone by service.phone {
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action is Action.OpenApp) {
                    check(action.packageName == context.packageName)
                    return service.phone.observe().packageName == context.packageName
                }
                return service.phone.execute(action, expected, expectedImage)
            }
        }
        val controller = TaskController(scope, phone, models)
        try {
            withContext(Dispatchers.Main) { controller.start("Write and apply the test draft", setOf(context.packageName)) }
            withTimeout(15_000) {
                while (controller.state.value.status == TaskStatus.RUNNING) delay(50)
            }
            assertEquals("${controller.state.value.message}: ${controller.state.value.log}", TaskStatus.COMPLETED, controller.state.value.status)
            assertEquals(2, controller.state.value.steps)
            assertEquals(1, summaries)
            assertEquals("Verified saved draft: $draft", controller.state.value.result)
            assertEquals(draft, context.getSharedPreferences("fixture", Context.MODE_PRIVATE).getString("draft", null))
            withContext(Dispatchers.Main) { assertTrue(service.phone.observe().elements.any { it.text == "Draft saved: $draft" }) }
        } finally { scope.cancel() }
    }

    @Test fun realNodesInputClickToggleScrollAndScreenshot() = runBlocking {
        val service = fixture()
        withContext(Dispatchers.Main) {
            val phone = service.phone
            var screen = phone.observe()
            assertEquals(context.packageName, screen.packageName)
            val note = screen.elements.single { it.resourceId.endsWith("/fixture_note") }
            assertTrue(note.editable)
            assertTrue(phone.execute(Action.Type(note.id, "Native Android draft"), screen))
            val changed = phone.observe()
            assertNotEquals(screen.fingerprint, changed.fingerprint)
            assertFalse(phone.execute(Action.Click(screen.elements.single { it.resourceId.endsWith("/fixture_apply") }.id), screen))
            screen = changed
            assertTrue(phone.execute(Action.Click(screen.elements.single { it.resourceId.endsWith("/fixture_apply") }.id), screen))
            screen = phone.observe()
            assertTrue(screen.elements.any { it.text == "Draft saved: Native Android draft" })
            val setting = screen.elements.single { it.resourceId.endsWith("/fixture_setting") }
            assertTrue(phone.execute(Action.Tap((setting.bounds.left + setting.bounds.right) / 2,
                (setting.bounds.top + setting.bounds.bottom) / 2), screen, phone.screenshot(screen)))
            screen = phone.observe()
            assertTrue(screen.elements.single { it.resourceId.endsWith("/fixture_setting") }.checked)
            val scroll = screen.elements.first { it.scrollable }
            assertTrue(phone.execute(Action.Scroll(scroll.id, true), screen))
            screen = phone.observe()
            val screenshot = Base64.decode(phone.screenshot(screen), Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size)
            assertTrue(bitmap.width > 200 && bitmap.height > 200)
            bitmap.recycle()
            assertTrue(phone.execute(Action.Back, screen))
        }
    }

    @Test fun secureWindowScreenshotFailsWithoutFallback() = runBlocking {
        val service = fixture(secure = true)
        withContext(Dispatchers.Main) {
            val result = runCatching { service.phone.screenshot(service.phone.observe()) }
            assertTrue("Secure window must report an unavailable phone instead of a fatal failure",
                result.exceptionOrNull() is PhoneUnavailableException)
        }
    }

    @Test fun secureWindowPausesControllerAndContinuesAfterCaptureBecomesAvailable() = runBlocking {
        val service = fixture(secure = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var plans = 0
        var fallbacks = 0
        val actions = mutableListOf<Action>()
        val phone = object : Phone by service.phone {
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                actions += action
                return if (action is Action.OpenApp) true else service.phone.execute(action, expected, expectedImage)
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                plans++
                return Plan(context.packageName, listOf("read"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                assertFalse(image.isNullOrEmpty())
                fallbacks++
                return ModelStep(done = true)
            }
            override suspend fun summarize(goal: String, evidence: Evidence) = "重新读取后的结果"
        }
        val controller = TaskController(scope, phone, models)
        try {
            withContext(Dispatchers.Main) { controller.start("读取受保护页面", setOf(context.packageName)) }
            withTimeout(8000) { while (controller.state.value.status == TaskStatus.RUNNING) delay(20) }
            assertEquals(controller.state.value.message, TaskStatus.PAUSED, controller.state.value.status)
            assertTrue(controller.state.value.message.contains("截图"))
            assertEquals("A rejected capture must not reach the visual model", 0, fallbacks)
            withContext(Dispatchers.Main) {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single() as FixtureActivity
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                delay(200)
                controller.resume()
            }
            withTimeout(8000) { while (controller.state.value.status == TaskStatus.RUNNING) delay(20) }
            assertEquals(controller.state.value.message, TaskStatus.COMPLETED, controller.state.value.status)
            assertEquals("重新读取后的结果", controller.state.value.result)
            assertEquals(1, plans)
            assertEquals(1, fallbacks)
            assertEquals(listOf(Action.OpenApp(context.packageName)), actions)
        } finally { scope.cancel() }
    }

    @Test fun runningOverlayHasOnlyEmergencyStopAndCancelsPendingWork() = runBlocking {
        val service = fixture()
        val render = AssistantService::class.java.getDeclaredMethod("renderOverlay", TaskState::class.java).apply { isAccessible = true }
        val field = AssistantService::class.java.getDeclaredField("controller").apply { isAccessible = true }
        val prior = field.get(service)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = mutableListOf<Action>()
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan(context.packageName, listOf("read"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                withContext(NonCancellable) { delay(1500) }
                return Decision(Action.Back, false, Effect.UNKNOWN)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) = ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "result"
        }
        val phone = object : Phone by service.phone {
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                actions += action
                return true
            }
        }
        val controller = TaskController(scope, phone, models)
        try {
            withContext(Dispatchers.Main) {
                field.set(service, controller)
                controller.start("自动读取页面", setOf(context.packageName))
                scope.launch { controller.state.collect { render.invoke(service, it) } }
            }
            delay(300)
            val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            File(context.getExternalFilesDir(null), "qa-autonomy.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                fun view(name: String) = AssistantService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as android.view.View
                val panel = view("panel") as android.widget.LinearLayout
                assertEquals(android.view.View.GONE, panel.visibility)
                assertTrue(view("bubble").performClick())
                assertEquals(android.view.View.VISIBLE, panel.visibility)
                val buttons = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<android.widget.Button>()
                    .filter { it.visibility == View.VISIBLE }
                assertEquals(listOf("停止"), buttons.map { it.contentDescription.toString() })
                assertTrue(buttons.single().performClick())
                assertEquals(TaskStatus.STOPPED, controller.state.value.status)
            }
            delay(1500)
            assertEquals(listOf(Action.OpenApp(context.packageName)), actions)
            withContext(Dispatchers.Main) {
                fun view(name: String) = AssistantService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as android.view.View
                render.invoke(service, TaskState(status = TaskStatus.RUNNING))
                assertEquals("A new task folds the panel away", android.view.View.GONE, view("panel").visibility)
                render.invoke(service, TaskState(status = TaskStatus.COMPLETED, result = "摘要"))
                assertEquals("A finished task opens with its summary", android.view.View.VISIBLE, view("panel").visibility)
                assertEquals("摘要", (view("statusText") as android.widget.TextView).text.toString())
                assertEquals(android.view.View.GONE, view("stopButton").visibility)
                assertEquals(android.view.View.VISIBLE, view("inputRow").visibility)
            }
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main) { field.set(service, prior); render.invoke(service, TaskState()) }
        }
    }

    @Test fun scopePauseOffersContinueAndStopWithoutStartingAnotherTask() = runBlocking {
        val service = fixture()
        val render = AssistantService::class.java.getDeclaredMethod("renderOverlay", TaskState::class.java).apply { isAccessible = true }
        val field = AssistantService::class.java.getDeclaredField("controller").apply { isAccessible = true }
        fun view(name: String) = AssistantService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as View
        val prior = field.get(service)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val page = withContext(Dispatchers.Main) { service.phone.observe() }
        var outside = true
        var plans = 0
        var decisions = 0
        val actions = mutableListOf<Action>()
        val deciding = CompletableDeferred<Unit>()
        val finishDecision = CompletableDeferred<Unit>()
        val phone = object : Phone by service.phone {
            override suspend fun observe() = page.copy(packageName = if (outside) "com.google.android.gms" else context.packageName)
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                actions += action
                return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                plans++
                return Plan(context.packageName, listOf("read"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                assertEquals(context.packageName, screen.packageName)
                decisions++
                deciding.complete(Unit)
                finishDecision.await()
                return Decision(null, true, Effect.UNKNOWN)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) = error("Unexpected fallback")
            override suspend fun summarize(goal: String, evidence: Evidence) = "继续后的结果"
        }
        val controller = TaskController(scope, phone, models)
        suspend fun awaitPause() = withTimeout(8000) {
            while (withContext(Dispatchers.Main) {
                controller.state.value.status != TaskStatus.PAUSED || view("resumeButton").visibility != View.VISIBLE
            }) delay(20)
        }
        try {
            withContext(Dispatchers.Main) {
                field.set(service, controller)
                scope.launch { controller.state.collect { render.invoke(service, it) } }
                controller.start("等待手动登录", setOf(context.packageName))
            }
            awaitPause()
            withContext(Dispatchers.Main) {
                assertEquals(View.VISIBLE, view("panel").visibility)
                assertEquals(View.VISIBLE, view("stopButton").visibility)
                assertEquals(View.GONE, view("inputRow").visibility)
                assertEquals(0, decisions)
                assertTrue(view("resumeButton").performClick())
            }
            awaitPause()
            withContext(Dispatchers.Main) {
                assertEquals("Continue outside the selected app must remain paused", 0, decisions)
                outside = false
                assertTrue(view("resumeButton").performClick())
            }
            withTimeout(8000) { deciding.await() }
            withContext(Dispatchers.Main) {
                assertSame(controller, field.get(service))
                assertEquals("等待手动登录", controller.state.value.goal)
                assertEquals(View.GONE, view("panel").visibility)
                assertEquals(1, plans)
                assertEquals(listOf(Action.OpenApp(context.packageName)), actions)
                finishDecision.complete(Unit)
            }
            withTimeout(8000) { while (controller.state.value.status != TaskStatus.COMPLETED) delay(20) }
            assertEquals("继续后的结果", controller.state.value.result)
            withContext(Dispatchers.Main) {
                outside = true
                controller.start("暂停后停止", setOf(context.packageName))
            }
            awaitPause()
            withContext(Dispatchers.Main) {
                assertTrue(view("stopButton").performClick())
                assertEquals(TaskStatus.STOPPED, controller.state.value.status)
            }
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main) { field.set(service, prior); render.invoke(service, TaskState()) }
        }
    }

    @Test fun expandedOverlayDragsFromBlankSpaceAndKeepsItsControlsUsable() = runBlocking {
        val service = fixture()
        withContext(Dispatchers.Main) {
            val render = AssistantService::class.java.getDeclaredMethod("renderOverlay", TaskState::class.java).apply { isAccessible = true }
            fun view(name: String) = AssistantService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service) as View
            val root = view("overlay")
            val panel = view("panel") as android.widget.LinearLayout
            val params = AssistantService::class.java.getDeclaredField("layout").apply { isAccessible = true }.get(service) as WindowManager.LayoutParams
            val windows = service.getSystemService(WindowManager::class.java)
            val originalX = params.x
            val originalY = params.y
            val originalGravity = params.gravity
            fun dp(value: Int) = value * context.resources.displayMetrics.density
            fun point(target: View, x: Float = target.width / 2f, y: Float = target.height / 2f): Pair<Float, Float> {
                val location = IntArray(2).also(target::getLocationOnScreen)
                val origin = IntArray(2).also(root::getLocationOnScreen)
                return location[0] - origin[0] + x to location[1] - origin[1] + y
            }
            fun gesture(point: Pair<Float, Float>, dx: Float = 0f, dy: Float = 0f) {
                val start = SystemClock.uptimeMillis()
                fun dispatch(action: Int, x: Float, y: Float, elapsed: Long) {
                    val event = MotionEvent.obtain(start, start + elapsed, action, x, y, 0)
                    try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                dispatch(MotionEvent.ACTION_DOWN, point.first, point.second, 0)
                if (dx != 0f || dy != 0f) dispatch(MotionEvent.ACTION_MOVE, point.first + dx, point.second + dy, 40)
                dispatch(MotionEvent.ACTION_UP, point.first + dx, point.second + dy, 80)
            }
            suspend fun resetPosition() {
                params.x = 0; params.y = dp(70).toInt(); params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                windows.updateViewLayout(root, params)
                delay(100)
            }
            try {
                render.invoke(service, TaskState())
                view("bubble").performClick()
                delay(100)
                val row = view("inputRow") as android.widget.LinearLayout
                val field = view("input") as android.widget.EditText
                val targets = listOf(
                    "card padding" to { point(panel, panel.width / 2f, panel.paddingTop / 2f) },
                    "empty header" to { point(view("statusText")) },
                    "input row gap" to { point(row, (field.right + row.getChildAt(1).left) / 2f, row.height / 2f) },
                    "outer padding" to { point(root, root.paddingLeft / 2f, root.height / 2f) },
                )
                for ((name, target) in targets) {
                    resetPosition()
                    val y = params.y
                    gesture(target(), -dp(20), dp(20))
                    assertTrue("$name must move horizontally without snapping to an edge", params.x > 0)
                    assertTrue("$name must move vertically", params.y > y)
                    assertEquals(View.VISIBLE, panel.visibility)
                }
                resetPosition()
                val x = params.x
                val y = params.y
                gesture(point(field), -dp(20), 0f)
                delay(100)
                assertTrue("Input keeps its touch handling", AssistantService::class.java.getDeclaredField("typing").apply { isAccessible = true }.getBoolean(service))
                assertEquals(x, params.x)
                assertEquals(y, params.y)
                val header = panel.getChildAt(0) as android.widget.LinearLayout
                gesture(point(header.getChildAt(header.childCount - 1)))
                // Android posts performClick after ACTION_UP; let the main queue deliver it.
                withTimeout(1000) { while (panel.visibility != View.GONE) delay(10) }
                assertEquals("Collapse button still receives taps", View.GONE, panel.visibility)
                render.invoke(service, TaskState(status = TaskStatus.RUNNING))
                render.invoke(service, TaskState(status = TaskStatus.COMPLETED, result = (1..80).joinToString("\n") { "第 $it 行结果" }))
                delay(100)
                val summary = view("statusText") as android.widget.TextView
                gesture(point(summary), 0f, -dp(60))
                assertTrue("Summary keeps its scrolling gesture", summary.scrollY > 0)
                assertEquals(x, params.x)
                assertEquals(y, params.y)
            } finally {
                params.x = originalX; params.y = originalY; params.gravity = originalGravity
                windows.updateViewLayout(root, params)
                render.invoke(service, TaskState())
                val collapse = AssistantService::class.java.getDeclaredMethod("collapse").apply { isAccessible = true }
                collapse.invoke(service)
            }
        }
    }

    @Test fun vaultKeepsExcludedApps() {
        val folder = File(context.cacheDir, "app-selection-test").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val vault = Vault(isolated)
        try {
            assertTrue(vault.read().excludedApps.isEmpty())
            vault.write(Saved(excludedApps = setOf("browser.app")))
            vault.record(Record("one", 10, "goal", "COMPLETED", "result"))
            assertEquals(setOf("browser.app"), Vault(isolated).read().excludedApps)
        } finally { folder.deleteRecursively() }
    }

    @Test fun installedComponentsWithoutLauncherAreAllowedUntilExcluded() = runBlocking {
        val service = fixture()
        val testPackage = instrumentation.context.packageName
        val catalog = loadAppCatalog(context)
        val component = catalog.single { it.packageName == testPackage }
        assertFalse("The test APK has no launcher entry", component.launchable)
        assertFalse(service.phone.canLaunch(testPackage))
        assertTrue(service.phone.canLaunch(context.packageName))
        assertTrue(allowedApps(catalog, emptySet()).contains(testPackage))
        assertFalse(allowedApps(catalog, setOf(testPackage)).contains(testPackage))
        assertFalse(catalog.any { it.packageName == context.packageName })
        val playServicesInstalled = context.packageManager.getInstalledApplications(0).any { it.enabled && it.packageName == "com.google.android.gms" }
        assertEquals("Google Play services must be listed when installed", playServicesInstalled,
            catalog.any { it.packageName == "com.google.android.gms" })
        withContext(Dispatchers.Main) {
            val failure = runCatching { service.phone.execute(Action.OpenApp(testPackage), null) }.exceptionOrNull()
            assertTrue("A component without an entry asks for handover", failure is PhoneUnavailableException)
        }
    }

    @Test fun vaultEncryptsAndRetainsUnreadableFile() {
        val folder = File(context.cacheDir, "vault-test").apply { deleteRecursively(); mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val vault = Vault(isolated)
        val config = ModelConfig(jevKey = "never-write-this-plain", chatKey = "private-secret")
        val record = Record("one", 10, "private goal", "COMPLETED", "actual result", listOf("12:00:00.000 → Jev 决策"))
        vault.write(Saved(config, setOf("browser.app"), listOf(record), onlineTtsToken = "private-tts-token",
            onlineTtsEndpoint = "https://speech.example/v1/audio/speech",
            onlineTtsModel = "elevenlabs/custom-model", onlineTtsVoice = "custom-voice"))
        assertEquals(record, vault.read().records.single())
        assertEquals(config, vault.read().config)
        assertEquals("private-tts-token", vault.read().onlineTtsToken)
        assertEquals("https://speech.example/v1/audio/speech", vault.read().onlineTtsEndpoint)
        assertEquals("elevenlabs/custom-model", vault.read().onlineTtsModel)
        assertEquals("custom-voice", vault.read().onlineTtsVoice)
        val file = File(folder, "assistant.enc")
        assertFalse(file.readText().contains("never-write-this-plain"))
        assertFalse(file.readText().contains("private goal"))
        assertFalse(file.readText().contains("private-tts-token"))
        val corrupted = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        assertTrue(runCatching { vault.read() }.isFailure)
        assertArrayEquals(corrupted, file.readBytes())
        folder.deleteRecursively()
    }
}
