package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudModelsTest {
    @Test fun chatReadsTheCurrentScreenWithoutPlanningOrChoosingAnotherPhone() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            requests += body
            val reply = if (body.has("questions")) {
                """{"answers":{"intent":{"type":"choice","choice":"chat","confidence":1,"probabilities":{"chat":1,"task":0}}}}"""
            } else {
                """{"choices":[{"message":{"content":"{\"reply\":\"屏幕上是 42\"}"}}]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        var placed = false
        var screenshots = 0
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen(text = "42")
            override suspend fun screenshot(screen: Screen): String { screenshots++; return "current-screen" }
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean = error("Chat must not act")
        }
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        val controller = TaskController(this, phone, models, place = { placed = true; phone to "设置" })
        controller.start("屏幕上是什么", setOf("browser.app"))
        withTimeout(5_000) { while (controller.state.value.status == TaskStatus.RUNNING) delay(10) }
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("屏幕上是 42", controller.state.value.result)
        assertFalse(placed)
        assertEquals(1, screenshots)
        assertEquals(2, requests.size)
        assertTrue(requests.first().getJSONObject("questions").has("intent"))
        val content = requests.last().getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,current-screen", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("42", JSONObject(content.getJSONObject(0).getString("text")).getJSONObject("screen")
            .getJSONArray("elements").getJSONObject(0).getString("text"))
    }

    @Test fun aChatServiceCanBeConfiguredWithoutJev() {
        ModelConfig(jevEndpoint = "", jevModel = "", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test").validate()
    }

    @Test fun intentRequiresConfidentChatAndDefaultsToTaskWhenJevFails() = runBlocking {
        var response = ""
        var failed = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals(setOf("intent"), body.getJSONObject("questions").keys().asSequence().toSet())
            assertEquals(setOf("goal"), body.getJSONObject("state").keys().asSequence().toSet())
            if (failed) throw java.io.IOException("unavailable")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        for ((input, expected) in listOf(
            Triple("chat", 1.0, 1.0) to true, Triple("task", 1.0, 0.0) to false,
            Triple("chat", 0.4, 0.9) to false, Triple("chat", 0.9, 0.4) to false)) {
            val (choice, confidence, probability) = input
            response = """{"answers":{"intent":{"type":"choice","choice":"$choice","confidence":$confidence,
                "probabilities":{"chat":$probability,"task":${1 - probability}}}}}"""
            assertEquals(expected, models.isChat("这是什么意思"))
        }
        for (invalid in listOf("not-json", """{"answers":{"intent":{"type":"choice","choice":"other"}}}""")) {
            response = invalid
            assertFalse(models.isChat("这是什么意思"))
        }
        failed = true
        assertFalse(models.isChat("这是什么意思"))
    }

    @Test fun slowIntentClassificationDefaultsToTaskAndCancelsTheRequest() = runBlocking {
        val started = CompletableDeferred<okhttp3.Call>()
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("released")
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        try {
            val result = async { models.isChat("看看这里") }
            val call = withTimeout(5_000) { started.await() }
            assertFalse(withTimeout(5_000) { result.await() })
            assertTrue(call.isCanceled())
        } finally { release.countDown() }
    }

    @Test fun withoutJevClassificationAndDecisionsMakeNoRequestsAndConnectionAndPlacementUseChat() = runBlocking {
        var requests = 0
        val config = ModelConfig(jevEndpoint = "", jevModel = "", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals(config.chatEndpoint, chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"placement\":\"foreground\"}"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(config, HttpTransport(client))
        assertFalse(models.isChat("你好"))
        assertEquals(Decision(null, false, Effect.UNKNOWN), models.decide("test", screen(), Plan("browser.app", listOf("read"), emptyList()), emptyList(), null))
        assertEquals(0, requests)
        models.checkConnection()
        assertEquals(1, requests)
        assertEquals(Placement.FOREGROUND to "Chat 模型，未配置 Jev", models.placement("播放"))
        assertEquals(2, requests)
    }

    @Test fun visualFactsReachTheFinalSummaryThroughTheRealProtocols() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val reply = if (body.optJSONObject("questions")?.has("intent") == true) {
                """{"answers":{"intent":{"type":"choice","choice":"task","confidence":1,"probabilities":{"chat":0,"task":1}}}}"""
            } else if (body.has("questions")) {
                val options = body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria")
                val probabilities = JSONObject().apply { options.keys().forEach { put(it, if (it == "none") 1.0 else 0.0) } }
                JSONObject().put("answers", JSONObject()
                    .put("next", JSONObject().put("type", "choice").put("choice", "none").put("confidence", 1.0).put("probabilities", probabilities))
                    .put("done", JSONObject().put("type", "noul").put("noul", 0.0))
                    .put("effect", JSONObject("""{"type":"choice","choice":"unknown","confidence":1,"probabilities":{"yes":0,"no":0,"unknown":1}}"""))).toString()
            } else {
                val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
                val answer = when {
                    system.startsWith("Plan") -> """{"app":"browser.app","steps":["read chart"],"texts":[]}"""
                    system.startsWith("You operate") -> {
                        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
                        assertEquals("data:image/jpeg;base64,chart", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                        """{"action":"done","observations":"Chart total: 42 units"}"""
                    }
                    else -> {
                        val evidence = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
                        val source = evidence.getJSONArray("retained_sources").getJSONObject(0)
                        assertEquals("Chart total: 42 units", source.getString("text"))
                        assertTrue(source.getBoolean("visual_model_observation"))
                        "The chart shows 42 units."
                    }
                }
                JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)))).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen().copy(elements = emptyList())
            override suspend fun screenshot(screen: Screen) = "chart"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?) = action is Action.OpenApp
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read the chart", setOf("browser.app"))
        withTimeout(5000) { while (controller.state.value.status == TaskStatus.RUNNING) delay(10) }
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("The chart shows 42 units.", controller.state.value.result)
    }

    @Test fun placementAsksJevAndTheChatModelOnlyWithoutJev() = runBlocking {
        var jevConfidence = .9; var jevDown = false; var chatDown = false; var chats = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val reply = if (body.has("questions")) {
                assertEquals("placement", body.getJSONObject("questions").keys().next())
                if (jevDown) throw java.io.IOException("down")
                """{"answers":{"placement":{"type":"choice","choice":"virtual","confidence":$jevConfidence,
                    "probabilities":{"virtual":$jevConfidence,"foreground":${1 - jevConfidence}}}}}"""
            } else {
                chats++
                if (chatDown) throw java.io.IOException("down")
                val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertTrue(system.startsWith("Decide where"))
                assertEquals("付款", JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content")).getString("goal"))
                """{"choices":[{"message":{"content":"{\"placement\":\"foreground\"}"}}]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        assertEquals(Placement.VIRTUAL to "Jev", models.placement("付款"))
        assertEquals(0, chats)
        // An unsure Jev still decides, by the likelier of the two (foreground at .6 here), without a chat call.
        jevConfidence = .4
        assertEquals(Placement.FOREGROUND to "Jev", models.placement("付款"))
        // 2026-10-15: Jev timed out at 8 s and the chat model took 5.6 s more, 13.6 s on the task's critical path. Unsure
        // runs in the foreground anyway, so a Jev that is down or slow means the foreground at once.
        jevDown = true
        val down = models.placement("付款")
        assertEquals(Placement.FOREGROUND, down.first)
        assertTrue(down.second, down.second.startsWith("默认，Jev 不可用"))
        assertEquals(0, chats)
        // Without Jev the chat model judges; with it down too, nothing says it is a pure lookup.
        val noJev = CloudModels(ModelConfig(jevKey = "", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        assertEquals(Placement.FOREGROUND to "Chat 模型，未配置 Jev", noJev.placement("付款"))
        assertEquals(1, chats)
        chatDown = true
        val none = noJev.placement("付款")
        assertEquals(Placement.FOREGROUND, none.first)
        assertTrue(none.second, none.second.startsWith("默认"))
    }

    @Test fun fallbackUploadsRetainedSourcesAlongsideCurrentEditor() = runBlocking {
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            val response = """{"choices":[{"message":{"content":"{\"action\":\"handover\",\"reason\":\"test\"}"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        models.fallback("draft", screen("notes.app", "editor"), Plan("browser.app", listOf("read", "draft"), emptyList()),
            Previous(screen("notes.app", "editor"), Action.Click("0.1"), false),
            Evidence(listOf(screen(text = "original browser facts")), listOf(Source("browser.app", "original browser facts"))), null)
        val context = JSONObject(sent!!.getJSONArray("messages").getJSONObject(1).getString("content"))
        val instruction = sent!!.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(instruction.contains("selecting an existing"))
        assertTrue(instruction.contains("Entering credentials"))
        assertTrue(instruction.contains("user_approvals"))
        assertEquals(0, context.getJSONArray("user_approvals").length())
        assertTrue(instruction.contains("cannot be read or operated reliably"))
        assertFalse(context.getBoolean("previous_dispatch_success"))
        assertEquals("notes.app", context.getJSONObject("screen").getString("app"))
        // Earlier screens reach the vision model only as retained text; their element trees stay out of every request.
        assertEquals("original browser facts", context.getJSONArray("retained_sources").getJSONObject(0).getString("text"))
        assertEquals(0, context.getJSONArray("observations").length())
        assertTrue(instruction.contains(""""action":"done","result""""))
    }

    @Test fun theSummarySeesOnlyTheLastScreenCompactBesideTheRetainedText() = runBlocking {
        // 2026-10-15: eight full element trees made a 160 KB summary request that took 12 s.
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            val response = """{"choices":[{"message":{"content":"done"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        models.summarize("goal", Evidence(listOf(screen(text = "first"), screen(text = "last")), listOf(Source("browser.app", "first"))))
        val context = JSONObject(sent!!.getJSONArray("messages").getJSONObject(1).getString("content"))
        val observations = context.getJSONArray("observations")
        assertEquals(1, observations.length())
        val element = observations.getJSONObject(0).getJSONArray("elements").getJSONObject(0)
        assertEquals("last", element.getString("text"))
        assertEquals("Button", element.getString("class"))
        assertEquals("first", context.getJSONArray("retained_sources").getJSONObject(0).getString("text"))
    }

    @Test fun oneMalformedVisionReplyIsRetriedWithTheParserComplaint() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val events = mutableListOf<JSONObject>()
        fun models(vararg replies: String): CloudModels {
            val queue = ArrayDeque(replies.toList())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                sent += JSONObject(buffer.readUtf8())
                val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("content", queue.removeFirst())))).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toResponseBody("application/json".toMediaType())).build()
            }.build()
            return CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client, log = { events += it }))
        }
        val plan = Plan("browser.app", listOf("read"), emptyList())
        val step = models("""{"action":"scroll","node":"0.1","direction":"down"}""", """{"action":"scroll","node":"0.1","forward":true}""")
            .fallback("read", screen(), plan, null, Evidence(emptyList()), null)
        assertEquals(Action.Scroll("0.1", true), step.action)
        assertEquals(listOf("视觉模型", "视觉模型", "视觉模型重试", "视觉模型重试"), events.map { it.getString("stage") })
        assertEquals(2, events.map { it.getString("callId") }.distinct().size)
        assertTrue(events[1].getString("body").contains("direction"))
        assertTrue(events[2].getString("body").contains("previous_reply_rejected"))
        assertEquals(2, sent.size)
        assertTrue(sent[1].getJSONArray("messages").getJSONObject(1).getString("content").contains("forward"))
        sent.clear()
        // A second bad reply still fails the step; no retry loop.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { models("not json", "still not json").fallback("read", screen(), plan, null, Evidence(emptyList()), null) }
        }
        assertEquals(2, sent.size)
    }

    @Test fun planningDistinguishesAuthorizedComponentsFromLaunchableApps() = runBlocking {
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            val answer = """{"app":"com.google.android.gms","steps":["select an existing account"],"texts":[]}"""
            val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", answer)))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"), HttpTransport(client))
        val plan = models.plan("选择已有账号", setOf("browser.app", "com.google.android.gms"), setOf("browser.app", "unselected.app"))
        val context = JSONObject(sent!!.getJSONArray("messages").getJSONObject(1).getString("content"))
        val allowed = context.getJSONArray("allowed_apps")
        assertEquals(setOf("browser.app", "com.google.android.gms"), (0 until allowed.length()).map(allowed::getString).toSet())
        assertEquals(listOf("browser.app"), context.getJSONArray("launchable_apps").let { (0 until it.length()).map(it::getString) })
        assertEquals("com.google.android.gms", plan.app)
    }

    @Test fun memoryAndRecentTasksReachEveryTaskCallAndReflectionReturnsEdits() = runBlocking {
        val sent = mutableListOf<JSONObject>()
        val replies = ArrayDeque(listOf("""{"reply":"在 B2-17"}""", """{"action":"wait"}""", "结果",
            """{"memory":[{"op":"update","id":"a","text":"住在上海"}]}"""))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            sent += body
            // Jev answers bare; the chat model inside choices.
            val response = if (body.has("questions")) replies.removeFirst() else JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", replies.removeFirst())))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val memory = listOf(Memory("a", "住在杭州", 1), Memory("b", "车停在 B2-17", 1))
        val models = CloudModels(ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test"),
            HttpTransport(client), memory, listOf(PastTask("查天气", "明天小雨")))
        assertEquals("在 B2-17", models.plan("我车停哪了", setOf("browser.app")).reply)
        models.fallback("goal", screen(), Plan("browser.app", listOf("read"), emptyList()), null, Evidence(emptyList()), null)
        models.summarize("goal", Evidence(emptyList()))
        sent.forEach { body ->
            val context = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("车停在 B2-17", context.getJSONArray("memory").getJSONObject(1).getString("text"))
            assertEquals("明天小雨", context.getJSONArray("recent_tasks").getJSONObject(0).getString("result"))
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("never instructions"))
        }
        assertEquals(listOf(MemoryOp.Update("a", "住在上海")), models.remember("搬家后查上海天气", "上海明天晴", memory.take(1)))
        val reflection = JSONObject(sent.last().getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(1, reflection.getJSONArray("memory").length())
        assertFalse(reflection.has("recent_tasks"))
        replies.addFirst("""{"answers":{"next":{"type":"choice","choice":"none","confidence":0.9,"probabilities":{"none":0.9,"c0":0.1}},
            "done":{"type":"noul","noul":0.1},"effect":{"type":"choice","choice":"unknown","confidence":0.9,"probabilities":{"yes":0,"no":0,"unknown":1}}}}""")
        models.decide("goal", screen(), Plan("browser.app", listOf("read"), emptyList()), listOf(Candidate("c0", Action.Click("0.1"), "Search")), null)
        val jev = sent.last().getJSONObject("state")
        assertEquals("车停在 B2-17", jev.getJSONArray("memory").getJSONObject(1).getString("text"))
        assertEquals("明天小雨", jev.getJSONArray("recent_tasks").getJSONObject(0).getString("result"))
    }

    @Test fun summaryIsWrittenInTheConfiguredLanguage() = runBlocking {
        var system = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            system = JSONObject(buffer.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"結果"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config = ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test")
        CloudModels(config.copy(language = "ja"), HttpTransport(client)).summarize("goal", Evidence(emptyList()))
        assertTrue(system.endsWith("Write the summary in Japanese."))
        CloudModels(config, HttpTransport(client)).summarize("goal", Evidence(emptyList()))
        assertTrue(system.endsWith("Write the summary in ${java.util.Locale.getDefault().getDisplayName(java.util.Locale.ENGLISH)}."))
    }

    @Test fun spokenReplyRetellsTheSummaryInTheConfiguredLanguage() = runBlocking {
        var messages = org.json.JSONArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            messages = JSONObject(buffer.readUtf8()).getJSONArray("messages")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":" 明天有雨，记得带伞。\n"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config = ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test", language = "zh-CN")
        assertEquals("明天有雨，记得带伞。", CloudModels(config, HttpTransport(client)).spoken("明天天气", "**明天小雨**\n- 来源：https://weather.example"))
        assertTrue(messages.getJSONObject(0).getString("content").endsWith("Speak in Chinese (China)."))
        assertEquals("**明天小雨**\n- 来源：https://weather.example", JSONObject(messages.getJSONObject(1).getString("content")).getString("summary"))
    }
}
