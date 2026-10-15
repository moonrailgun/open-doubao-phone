package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Pipe
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 2026-10-15: vision steps took 4–11 s, the last 50–120 tokens of each being observations written after the action. */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyActionTest {
    @Test fun anActionIsReadyOnceTheFieldsBeforeTheObservationsAreComplete() {
        assertNull(ModelProtocol.earlyStep("""{"action":"click","node":"0."""))
        assertEquals(Action.Click("0.1"), ModelProtocol.earlyStep("""{"action":"click","node":"0.1",""")?.action)
        assertNull(ModelProtocol.earlyStep("""{"action":"type","node":"0.2","""))
        // The comma sits inside the text being typed, so the text is not complete yet.
        assertNull(ModelProtocol.earlyStep("""{"action":"type","node":"0.2","text":"咖啡,"""))
        assertEquals(Action.Click("0.1"), ModelProtocol.earlyStep("""{"action":"click","node":"0.1","observations":"飞""")?.action)
        assertEquals(Action.Type("0.2", "咖啡"), ModelProtocol.earlyStep("""```json
            {"action":"type","node":"0.2","text":"咖啡", "observations" :""")?.action)
        // A done carries its answer and a handover its reason after the observations, so both wait for the whole reply.
        assertNull(ModelProtocol.earlyStep("""{"action":"done","observations":"x""""))
        assertNull(ModelProtocol.earlyStep("""{"action":"handover","observations":"x""""))
    }

    @Test fun theVisionModelStreamsAndHandsOverTheActionBeforeItsObservations() = runBlocking {
        val chunks = listOf("""{"action":"click",""", """"node":"0.1",""", """"observations":"飞书""", """消息页"}""")
        val actionSeen = CountDownLatch(1)
        var waitedForTheAction = false
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = JSONObject(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            val pipe = Pipe(1 shl 16)
            Thread {
                pipe.sink.buffer().use { sink ->
                    chunks.forEachIndexed { index, chunk ->
                        // The observations are only written after the action has been handed over.
                        if (index == 2) waitedForTheAction = actionSeen.await(5, TimeUnit.SECONDS)
                        val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", chunk))))
                        sink.writeUtf8("data: $delta\n\n").flush()
                    }
                    sink.writeUtf8("data: [DONE]\n\n")
                }
            }.start()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(pipe.source.buffer().asResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"),
            HttpTransport(client))
        val early = mutableListOf<ModelStep>()
        val step = withContext(EarlyAction { early += it; actionSeen.countDown() }) {
            models.fallback("goal", screen(), Plan("browser.app", listOf("read"), emptyList()), null, Evidence(emptyList()), "pixels")
        }
        assertTrue(sent!!.getBoolean("stream"))
        assertTrue(waitedForTheAction)
        assertEquals(listOf(Action.Click("0.1")), early.map { it.action })
        assertEquals(ModelStep(Action.Click("0.1"), observations = "飞书消息页"), step)
    }

    @Test fun observationsStillArriveWhenASureJevCancelsTheNextVisionCall() = runTest {
        // The next step's vision call waits for these observations; a sure Jev cancels it during that wait.
        var pages = 0
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen(text = "Page $pages")
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action is Action.Click) pages++
                return true
            }
        }
        var summarized: Evidence? = null
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan("browser.app", listOf("read"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(1_000)
                return when (pages) {
                    0 -> Decision(null, false, Effect.UNKNOWN)
                    1 -> Decision(Action.Click("0.1"), false, Effect.YES)
                    else -> Decision(null, true, Effect.YES)
                }
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                currentCoroutineContext()[EarlyAction]!!.take(ModelStep(Action.Click("0.1")))
                delay(3_000)
                return ModelStep(Action.Click("0.1"), observations = "消息页上有 3 条未读")
            }
            override suspend fun summarize(goal: String, evidence: Evidence): String { summarized = evidence; return "done" }
        }
        val controller = TaskController(this, phone, models)
        controller.start("看看未读", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(summarized!!.sources.any { it.visual && it.text == "消息页上有 3 条未读" })
    }

    @Test fun theControllerActsOnTheEarlyActionAndKeepsTheObservationsThatFollow() = runTest {
        var pages = 0
        val acted = mutableListOf<Pair<Action, Long>>()
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen(text = "Page $pages")
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action is Action.Click) { pages++; acted += action to currentTime }
                return true
            }
        }
        var summarized: Evidence? = null
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan("browser.app", listOf("read"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                if (pages == 0) Decision(null, false, Effect.UNKNOWN) else Decision(null, true, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                currentCoroutineContext()[EarlyAction]!!.take(ModelStep(Action.Click("0.1")))
                delay(3_000)
                return ModelStep(Action.Click("0.1"), observations = "消息页上有 3 条未读")
            }
            override suspend fun summarize(goal: String, evidence: Evidence): String { summarized = evidence; return "done" }
        }
        val controller = TaskController(this, phone, models)
        controller.start("看看未读", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, acted.size)
        assertTrue("clicked at ${acted[0].second}", acted[0].second < 3_000)
        assertTrue(summarized!!.sources.any { it.visual && it.text == "消息页上有 3 条未读" })
    }
}
