package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feishu's search page on 2026-10-13: 107 of 129 nodes were layout containers with nothing to read or press. */
class CompactScreenTest {
    private val page = Screen("com.ss.android.lark", 13, 1440, 3120, 0, listOf(
        Element("0", "", "", "android.widget.FrameLayout", "", Bounds(0, 0, 1440, 3120)),
        Element("0.0", "", "", "android.widget.FrameLayout", "com.ss.android.lark:id/avatar_item_left_frame", Bounds(28, 726, 294, 894)),
        Element("0.1", "", "", "android.widget.ImageView", "com.ss.android.lark:id/function_btn_1", Bounds(1149, 208, 1233, 292), clickable = true),
        Element("0.2", "小张", "", "android.widget.TextView", "", Bounds(266, 1400, 638, 1472)),
        Element("0.3", "", "", "android.widget.EditText", "", Bounds(0, 0, 10, 10), editable = true, enabled = false),
        Element("0.4", "secret", "", "android.widget.EditText", "", Bounds(0, 0, 10, 10), editable = true, password = true),
    ))

    private fun ids(screen: JSONObject) = screen.getJSONArray("elements").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }

    @Test fun compactKeepsOnlyNodesWithTextOrAnActionAndOnlyTheFieldsThatAreSet() {
        val compact = page.json(compact = true)
        assertEquals(listOf("0.1", "0.2", "0.3"), ids(compact))
        val elements = compact.getJSONArray("elements")
        fun fields(index: Int) = elements.getJSONObject(index).let { node -> node.keys().asSequence().associateWith { node.get(it).toString() } }
        assertEquals(mapOf("id" to "0.1", "class" to "ImageView", "resource" to "function_btn_1", "bounds" to "[1149,208,1233,292]",
            "clickable" to "true"), fields(0))
        assertEquals(mapOf("id" to "0.2", "text" to "小张", "class" to "TextView", "bounds" to "[266,1400,638,1472]"), fields(1))
        assertEquals(mapOf("id" to "0.3", "class" to "EditText", "bounds" to "[0,0,10,10]", "editable" to "true", "enabled" to "false"), fields(2))
        // The full form still carries every node but the password field, field for field.
        assertEquals(listOf("0", "0.0", "0.1", "0.2", "0.3"), ids(page.json()))
        assertFalse(page.json(compact = true).toString().contains("secret"))
    }

    private fun visionRequest(compactScreens: Boolean): JSONObject = runBlocking {
        var sent: JSONObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            val response = """{"choices":[{"message":{"content":"{\"action\":\"click\",\"node\":\"0.1\"}"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config = ModelConfig(jevKey = "test", chatEndpoint = "https://example.com/chat", chatKey = "test", chatModel = "test")
        CloudModels(config, HttpTransport(client), compactScreens = compactScreens)
            .fallback("打开和张三的聊天窗口", page, Plan("com.ss.android.lark", listOf("search"), emptyList()),
                Previous(page, Action.Click("0.1"), true), Evidence(emptyList()), null)
        sent!!
    }

    @Test fun theSettingChoosesBetweenCompactAndFullScreensForTheVisionModel() {
        for (compact in listOf(true, false)) {
            val request = visionRequest(compact)
            val messages = request.getJSONArray("messages")
            val state = JSONObject(messages.getJSONObject(1).getString("content"))
            val expected = if (compact) listOf("0.1", "0.2", "0.3") else listOf("0", "0.0", "0.1", "0.2", "0.3")
            assertEquals(expected, ids(state.getJSONObject("screen")))
            assertEquals(expected, ids(state.getJSONObject("previous_screen")))
            assertEquals(compact, messages.getJSONObject(0).getString("content").contains("absent flags are false"))
        }
        assertTrue(visionRequest(true).toString().length < visionRequest(false).toString().length)
    }
}
