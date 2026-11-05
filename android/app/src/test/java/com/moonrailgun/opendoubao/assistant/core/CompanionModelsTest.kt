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
    private val previous = CompanionFrame("game.app", 1080, 2400, 0, "previous-pixels")
    private val batch = listOf(previous.copy(capturedAtMillis = 6000),
        previous.copy(image = "brief-subtitle", capturedAtMillis = 7000), previous.copy(capturedAtMillis = 8000))

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
                val expectedImages = listOf("previous_screenshot" to previous.image) +
                    batch.mapIndexed { index, frame -> "screenshot_${index + 1}" to frame.image }
                val frames = state.getJSONArray("frames")
                assertEquals(batch.size, frames.length())
                batch.forEachIndexed { index, frame ->
                    assertEquals("screenshot_${index + 1}", frames.getJSONObject(index).getString("image"))
                    assertEquals(frame.capturedAtMillis, frames.getJSONObject(index).getLong("capturedAtMillis"))
                }
                assertTrue(body.getJSONObject("questions").getJSONObject("unchanged").getString("instructions").contains("EVERY"))
                for ((name, pixels) in expectedImages) {
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
                if (native) assertEquals(batch.size + 1, body.getJSONObject("assets").length())
                if (failed) throw IOException("unavailable")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(reply.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val models = CloudModels(config, HttpTransport(client))
            for ((answer, expected) in listOf("0.95" to true, "0.1" to false, "0.55" to false,
                "1.2" to false, "\"0.95\"" to false)) {
                reply = """{"answers":{"unchanged":{"type":"noul","noul":$answer}}}"""
                assertEquals(expected, models.companionUnchanged(previous, batch))
            }
            for (invalid in listOf("invalid-json", """{"answers":{"unchanged":{"type":"choice","noul":0.99}}}""")) {
                reply = invalid
                assertFalse(models.companionUnchanged(previous, batch))
            }
            failed = true
            assertFalse(models.companionUnchanged(previous, batch))
            val beforeDisabled = calls
            assertFalse(CloudModels(config.copy(jevVision = false), HttpTransport(client)).companionUnchanged(previous, batch))
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
                val result = async { models.companionUnchanged(previous, batch).also { returned = true } }
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

    @Test fun dedicatedVisionRequestUsesOrderedImagesBoundedContextAndOneStructuredReply() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val result = CompanionReply("正在配合队友，用户玩辅助", "这波配合真漂亮！")
        val config = CompanionConfig(10, true, "https://companion.test/chat", "small-vision", "companion-key").modelConfig(general)
        val models = mockModels(config, { encoded(result) }) { request -> requests += request }
        val userMessages = (1..10).map { "用户上下文$it" }
        assertEquals(result, models.companionComment(batch, CompanionContext(
            recentComments = listOf("旧的", "第一句", "第二句", "第三句"), userMessages = userMessages, summary = "此前在准备团战")))
        val request = requests.single()
        assertEquals("small-vision", request.getString("model"))
        assertTrue(request.getInt("max_tokens") in 400..1024)
        assertFalse(request.getBoolean("stream"))
        assertFalse(request.has("tools"))
        val messages = request.getJSONArray("messages")
        assertEquals(2, messages.length())
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(batch.size + 1, content.length())
        val context = JSONObject(content.getJSONObject(0).getString("text"))
        assertEquals("game.app", context.getString("app"))
        assertEquals("此前在准备团战", context.getString("summary"))
        assertEquals(listOf("第一句", "第二句", "第三句"), context.getJSONArray("recentComments").strings())
        assertEquals(userMessages.takeLast(8), context.getJSONArray("userMessages").strings())
        assertFalse(context.has("currentUserMessage"))
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("If nothing warrants a comment"))
        assertTrue(system.contains("elapsed milliseconds"))
        assertTrue(system.contains("Do not invent unseen events"))
        assertFalse(system.contains("TEXT ONLY"))
        batch.forEachIndexed { index, frame ->
            assertEquals(frame.capturedAtMillis, context.getJSONArray("frames").getJSONObject(index).getLong("capturedAtMillis"))
            assertEquals("data:image/jpeg;base64,${frame.image}", content.getJSONObject(index + 1).getJSONObject("image_url").getString("url"))
        }
        val question = "我玩的是辅助，\"这件装备\"适合吗？\n不要剧透"
        val followUp = CompanionContext(userMessages = userMessages + question, manual = true, summary = result.summary)
        assertEquals(result, models.companionComment(listOf(batch.last()), followUp))
        val manual = requests.last().getJSONArray("messages")
        assertTrue(manual.getJSONObject(0).getString("content").contains("Respond directly to it, even if the screen is unchanged"))
        assertFalse(manual.getJSONObject(0).getString("content").contains("If nothing warrants a comment"))
        val manualContent = manual.getJSONObject(1).getJSONArray("content")
        assertEquals(2, manualContent.length())
        val manualContext = JSONObject(manualContent.getJSONObject(0).getString("text"))
        assertEquals(question, manualContext.getString("currentUserMessage"))
        assertEquals((userMessages + question).takeLast(8), manualContext.getJSONArray("userMessages").strings())
        assertEquals(result, models.companionComment(batch, followUp.copy(manual = false)))
        val nextContext = JSONObject(requests.last().getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(nextContext.has("currentUserMessage"))
        assertEquals(manualContext.getJSONArray("userMessages").toString(), nextContext.getJSONArray("userMessages").toString())
        assertEquals(3, requests.size)
    }

    @Test fun silentResponsesStillReturnSummaryAndOutputIsBoundedWithoutBrokenEmoji() = runBlocking {
        var raw = encoded(CompanionReply("仍在等待", ""))
        val models = mockModels(general, { raw })
        assertEquals(CompanionReply("仍在等待", ""), models.companionComment(batch, CompanionContext()))
        raw = "```json\n" + encoded(CompanionReply("摘要\n  更新", "短评\n\n  很精彩！")) + "\n```"
        assertEquals(CompanionReply("摘要 更新", "短评 很精彩！"), models.companionComment(batch, CompanionContext()))
        raw = encoded(CompanionReply("长".repeat(298) + "😀".repeat(10), "长".repeat(118) + "😀".repeat(10)))
        val bounded = models.companionComment(batch, CompanionContext())
        assertEquals("长".repeat(298) + "…", bounded.summary)
        assertEquals("长".repeat(118) + "…", bounded.comment)
    }

    @Test fun invalidStructuredRepliesAndEmptyManualRepliesFailWithoutLeakingResponse() = runBlocking {
        var raw = ""
        val models = mockModels(general, { raw })
        for (invalid in listOf("private-provider-response", "[SILENT]", "{}", "[]",
            """{"summary": "private", "comment": null}""", """{"summary": 42, "comment": "private"}""",
            """{"summary": "private"}""", """{"comment": "private"}""")) {
            raw = invalid
            try { models.companionComment(batch, CompanionContext()); fail("Accepted invalid reply") }
            catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("private")) }
        }
        raw = encoded(CompanionReply("等待", "  "))
        try { models.companionComment(batch, CompanionContext(userMessages = listOf("你好"), manual = true)); fail("Lost manual input") }
        catch (_: IllegalArgumentException) { }
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun encoded(reply: CompanionReply) = JSONObject().put("summary", reply.summary).put("comment", reply.comment).toString()
    private fun mockModels(config: ModelConfig, reply: () -> String, request: (JSONObject) -> Unit = {}): CloudModels {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(config.chatEndpoint, chain.request().url.toString())
            assertEquals("Bearer ${config.chatKey}", chain.request().header("Authorization"))
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            request(JSONObject(buffer.readUtf8()))
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply()))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudModels(config, HttpTransport(client))
    }
}
