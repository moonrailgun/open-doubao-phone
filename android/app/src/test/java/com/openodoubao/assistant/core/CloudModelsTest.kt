package com.openodoubao.assistant.core

import com.openodoubao.assistant.model.CloudModels
import com.openodoubao.assistant.model.HttpTransport
import com.openodoubao.assistant.model.ModelConfig
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
    @Test fun visualFactsReachTheFinalSummaryThroughTheRealProtocols() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val reply = if (body.has("questions")) {
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
            Evidence(listOf(screen(text = "original browser facts"))), null)
        val context = JSONObject(sent!!.getJSONArray("messages").getJSONObject(1).getString("content"))
        val instruction = sent!!.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(instruction.contains("selecting an existing"))
        assertTrue(instruction.contains("entering credentials"))
        assertTrue(instruction.contains("cannot be read or operated reliably"))
        assertFalse(context.getBoolean("previous_dispatch_success"))
        assertEquals("notes.app", context.getJSONObject("screen").getString("app"))
        assertEquals("original browser facts", context.getJSONArray("observations").getJSONObject(0)
            .getJSONArray("elements").getJSONObject(0).getString("text"))
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
}
