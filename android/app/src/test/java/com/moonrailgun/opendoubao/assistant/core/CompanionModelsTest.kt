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

class CompanionModelsTest {
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
        assertEquals(reply, models.companionComment(frame, listOf("旧的", "第一句", "第二句", "第三句")))
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
        assertEquals("data:image/jpeg;base64,screen-pixels", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        reply = " [SILENT] \n"
        assertEquals("", models.companionComment(frame, emptyList()))
        reply = "长".repeat(500)
        val bounded = models.companionComment(frame, emptyList())
        assertTrue(bounded.length <= 120)
        assertTrue(bounded.endsWith("…"))
        reply = "短评\n\n  很精彩！"
        assertEquals("短评 很精彩！", models.companionComment(frame, emptyList()))
        assertEquals(4, requests.size)
    }
}
