package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialPageTest {
    private class CurrentPhone : Phone {
        var page = screen(text = "文章：城市公交新开了三条线路")
        var launchable = true
        var screenshots = 0
        val actions = mutableListOf<Action>()
        override fun canLaunch(packageName: String) = launchable
        override suspend fun observe() = page
        override suspend fun screenshot(screen: Screen): String { screenshots++; return "article-pixels" }
        override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
            actions += action
            if (action is Action.OpenApp) page = screen(action.packageName)
            return true
        }
    }

    private open class TaskModels : Models {
        var intentScreen: Screen? = null
        var plannedScreen: Screen? = null
        var plannedImage: String? = null
        override suspend fun isChat(goal: String, screen: Screen?): Boolean { intentScreen = screen; return false }
        override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
        override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
            plannedScreen = screen
            plannedImage = image
            delay(500)
            return Plan("browser.app", listOf("读当前文章"), emptyList())
        }
        override suspend fun guessApp(goal: String, apps: Set<String>): String? = "other.app"
        override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
            Decision(if (previous == null) Action.Scroll("0.1", true) else null, previous != null, Effect.YES)
        override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
            ModelStep(done = true)
        override suspend fun summarize(goal: String, evidence: Evidence) = "文章摘要"
    }

    @Test fun readingTheCurrentPageNeverReopensItOrChoosesAnotherDisplay() = runTest {
        val phone = CurrentPhone().apply {
            launchable = false
            page = page.copy(elements = page.elements.map { it.copy(scrollable = true) })
        }
        var placements = 0
        val models = TaskModels()
        val controller = TaskController(this, phone, models, place = { placements++; phone to "虚拟屏" })
        controller.start("把当前文章往下翻，再总结", setOf("browser.app", "other.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(0, placements)
        assertEquals(phone.page, models.intentScreen)
        assertEquals(phone.page, models.plannedScreen)
        assertEquals("article-pixels", models.plannedImage)
        assertEquals(listOf(Action.Scroll("0.1", true)), phone.actions)
    }

    @Test fun aDifferentAppIsOpenedOnlyAfterPlanningWithTheInitialPage() = runTest {
        val phone = CurrentPhone()
        val initial = phone.page
        var planned = false
        var placements = 0
        val models = object : TaskModels() {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                assertEquals(initial, screen)
                assertEquals("article-pixels", image)
                delay(500)
                assertTrue(phone.actions.isEmpty())
                planned = true
                return Plan("other.app", listOf("查相关信息"), emptyList())
            }
            override suspend fun guessApp(goal: String, apps: Set<String>): String = error("Must wait for the page-aware plan")
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, true, Effect.YES)
        }
        val controller = TaskController(this, phone, models, place = {
            assertTrue(planned)
            placements++
            phone to "前台"
        })
        controller.start("去另一个应用查这篇文章的相关信息", setOf("browser.app", "other.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, placements)
        assertEquals(listOf(Action.OpenApp("other.app")), phone.actions)
    }

    @Test fun protectedAndExcludedInitialPagesAreNotCapturedOrSent() = runTest {
        for (page in listOf(screen("excluded.app"), screen().let { it.copy(elements = it.elements.map { node -> node.copy(password = true) }) })) {
            val phone = CurrentPhone().apply { this.page = page }
            val models = object : TaskModels() {
                override suspend fun guessApp(goal: String, apps: Set<String>): String? = null
                override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                    assertNull(screen)
                    assertNull(image)
                    return Plan("", emptyList(), emptyList(), reply = "已记住")
                }
            }
            val controller = TaskController(this, phone, models)
            controller.start("记住我的偏好", setOf("browser.app"))
            advanceUntilIdle()
            assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
            assertNull(models.intentScreen)
            assertEquals(0, phone.screenshots)
            assertTrue(phone.actions.isEmpty())
        }
    }

    @Test fun anArticleCanBeAnsweredFromTheInitialPageWithoutJev() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"reply\":\"当前可见内容介绍了三条新公交线路\"}"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val phone = CurrentPhone().apply { launchable = false }
        val models = CloudModels(ModelConfig(chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        val controller = TaskController(this, phone, models)
        controller.start("当前文章说了什么", setOf("browser.app"))
        withTimeout(5_000) { while (controller.state.value.status == TaskStatus.RUNNING) delay(10) }
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(phone.actions.isEmpty())
        val messages = requests.single().getJSONArray("messages")
        val content = messages.getJSONObject(1).getJSONArray("content")
        val state = JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals("browser.app", state.getJSONArray("allowed_apps").getString(0))
        assertEquals("browser.app", state.getJSONObject("screen").getString("app"))
        assertTrue(state.getJSONObject("screen").toString().contains("三条线路"))
        assertEquals("data:image/jpeg;base64,article-pixels", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("untrusted"))
    }
}
