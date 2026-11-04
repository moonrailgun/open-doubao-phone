package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.CompanionConfig
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompanionModelsTest {
    private val general = ModelConfig(jevKey = "jev-key", jevVision = true,
        chatEndpoint = "https://general.test/chat", chatKey = "general-key", chatModel = "large")
    private val previous = CompanionFrame("game.app", 1080, 2400, 0, "previous-pixels", intArrayOf(1))
    private val current = previous.copy(image = "current-pixels")

    @Test fun jevComparisonSupportsBothProtocolsAndOnlySkipsOnAValidConfidentAnswer() = runBlocking {
        for (native in listOf(true, false)) {
            val config = general.copy(jevEndpoint = if (native) general.jevEndpoint else "https://gateway.test/decisions")
            var reply = ""
            var failed = false
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals(config.jevEndpoint, chain.request().url.toString())
                assertEquals("Bearer jev-key", chain.request().header("Authorization"))
                assertEquals(if (native) "true" else null, chain.request().header("TypeSafe-Danger-Zone-Images"))
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                assertEquals(config.jevModel, body.getString("model"))
                assertFalse(body.has("messages"))
                assertEquals("noul", body.getJSONObject("questions").getJSONObject("unchanged").getString("type"))
                val state = body.getJSONObject("state")
                for ((name, pixels) in listOf("screenshot" to current.image, "previous_screenshot" to previous.image)) {
                    if (native) {
                        val asset = body.getJSONObject("assets").getJSONObject(state.getString(name))
                        assertEquals("image", asset.getString("type"))
                        assertEquals("jpg", asset.getString("format"))
                        assertEquals(pixels, asset.getString("data_base64"))
                    } else {
                        assertFalse(body.has("assets"))
                        assertEquals("image_url", state.getJSONObject(name).getString("type"))
                        assertEquals("data:image/jpeg;base64,$pixels", state.getJSONObject(name).getJSONObject("image_url").getString("url"))
                    }
                }
                if (native) assertEquals(2, body.getJSONObject("assets").length())
                if (failed) throw IOException("unavailable")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(reply.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val models = CloudModels(config, HttpTransport(client))
            for ((answer, expected) in listOf("0.95" to true, "0.1" to false, "0.55" to false,
                "1.2" to false, "\"0.95\"" to false)) {
                reply = """{"answers":{"unchanged":{"type":"noul","noul":$answer}}}"""
                assertEquals(expected, models.companionUnchanged(previous, current))
            }
            for (invalid in listOf("invalid-json", """{"answers":{"unchanged":{"type":"choice","noul":0.99}}}""")) {
                reply = invalid
                assertFalse(models.companionUnchanged(previous, current))
            }
            failed = true
            assertFalse(models.companionUnchanged(previous, current))
            val beforeDisabled = calls
            assertFalse(CloudModels(config.copy(jevVision = false), HttpTransport(client)).companionUnchanged(previous, current))
            assertEquals(beforeDisabled, calls)
        }
    }

    @Test fun comparisonTimeoutFallsBackButExternalCancellationPropagates() = runBlocking {
        for (cancel in listOf(false, true)) {
            val started = CompletableDeferred<okhttp3.Call>()
            val release = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                started.complete(chain.call())
                release.await(10, TimeUnit.SECONDS)
                throw IOException("released")
            }.build()
            val models = CloudModels(general, HttpTransport(client))
            var returned = false
            try {
                val result = async { models.companionUnchanged(previous, current).also { returned = true } }
                val call = withTimeout(5000) { started.await() }
                if (cancel) {
                    result.cancelAndJoin()
                    assertTrue(result.isCancelled)
                    assertFalse(returned)
                } else assertFalse(withTimeout(5000) { result.await() })
                assertTrue(call.isCanceled())
            } finally { release.countDown() }
        }
    }

    @Test fun dedicatedVisionRequestUsesBoundedContextAndRepliesWithoutExtraModelCalls() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        var reply = "这波配合真漂亮！"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://companion.test/chat", chain.request().url.toString())
            assertEquals("Bearer companion-key", chain.request().header("Authorization"))
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += JSONObject(buffer.readUtf8())
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val general = ModelConfig(jevKey = "jev-key", jevVision = true,
            chatEndpoint = "https://general.test/chat", chatKey = "general-key", chatModel = "large")
        val config = CompanionConfig(20, true, "https://companion.test/chat", "small-vision", "companion-key").modelConfig(general)
        val models = CloudModels(config, HttpTransport(client))
        val frame = CompanionFrame("game.app", 1080, 2400, 0, "screen-pixels", intArrayOf(1))
        val userMessages = (1..10).map { "用户上下文$it" }
        assertEquals(reply, models.companionComment(frame, CompanionContext(
            recentComments = listOf("旧的", "第一句", "第二句", "第三句"), userMessages = userMessages)))
        val request = requests.single()
        assertEquals("small-vision", request.getString("model"))
        assertEquals(160, request.getInt("max_tokens"))
        assertFalse(request.getBoolean("stream"))
        assertFalse(request.has("tools"))
        val messages = request.getJSONArray("messages")
        assertEquals(2, messages.length())
        val content = messages.getJSONObject(1).getJSONArray("content")
        val context = JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals("game.app", context.getString("app"))
        assertEquals(listOf("第一句", "第二句", "第三句"), context.getJSONArray("recentComments").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertEquals(userMessages.takeLast(8), context.getJSONArray("userMessages").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(context.has("currentUserMessage"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("If nothing warrants a comment"))
        assertEquals("data:image/jpeg;base64,screen-pixels", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val question = "我玩的是辅助，\"这件装备\"适合吗？\n不要剧透"
        val followUp = CompanionContext(userMessages = userMessages + question, manual = true)
        assertEquals(reply, models.companionComment(frame, followUp))
        val manual = requests.last().getJSONArray("messages")
        assertTrue(manual.getJSONObject(0).getString("content").contains("Respond directly to it, even if the screen is unchanged"))
        assertFalse(manual.getJSONObject(0).getString("content").contains("If nothing warrants a comment"))
        val manualContent = manual.getJSONObject(1).getJSONArray("content")
        val manualContext = JSONObject(manualContent.getJSONObject(0).getString("text"))
        assertEquals(question, manualContext.getString("currentUserMessage"))
        assertEquals((userMessages + question).takeLast(8), manualContext.getJSONArray("userMessages").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertEquals("data:image/jpeg;base64,screen-pixels", manualContent.getJSONObject(1).getJSONObject("image_url").getString("url"))

        assertEquals(reply, models.companionComment(frame.copy(image = "next-screen"), followUp.copy(manual = false)))
        val nextContent = requests.last().getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val nextContext = JSONObject(nextContent.getJSONObject(0).getString("text"))
        assertFalse(nextContext.has("currentUserMessage"))
        assertEquals(manualContext.getJSONArray("userMessages").toString(), nextContext.getJSONArray("userMessages").toString())
        assertEquals("data:image/jpeg;base64,next-screen", nextContent.getJSONObject(1).getJSONObject("image_url").getString("url"))
        reply = " [SILENT] \n"
        assertEquals("", models.companionComment(frame, CompanionContext()))
        reply = "长".repeat(500)
        val bounded = models.companionComment(frame, CompanionContext())
        assertTrue(bounded.length <= 120)
        assertTrue(bounded.endsWith("…"))
        reply = "短评\n\n  很精彩！"
        assertEquals("短评 很精彩！", models.companionComment(frame, CompanionContext()))
        assertEquals(6, requests.size)
    }
}
