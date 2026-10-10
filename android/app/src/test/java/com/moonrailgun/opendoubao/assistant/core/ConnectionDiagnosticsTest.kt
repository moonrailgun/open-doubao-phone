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
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class ConnectionDiagnosticsTest {
    private val config = ModelConfig(
        jevEndpoint = "https://gateway.example/v1/systemone?token=query%2Dsecret",
        jevModel = "openrouter/~typesafe/jev-latest", jevKey = "jev-secret",
        chatEndpoint = "https://gateway.example/v1/chat/completions", chatModel = "planner", chatKey = "chat-secret",
    )
    private val jevReply = """{"answers":{"connected":{"type":"noul","noul":1}}}"""

    private fun models(reply: (String) -> Pair<Int, String>): CloudModels {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = reply(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                .header("x-request-id", "header-request-123")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudModels(config, HttpTransport(client))
    }

    @Test fun jev404IdentifiesServiceRouteModelAndServerReasonWithoutSecrets() = runBlocking {
        val models = models {
            404 to """{"error":{"message":"no route registered for POST /v1/systemone; Bearer jev-secret; chat-secret; ?token=query-secret; query%2Dsecret","type":"not_found_error","code":"route_missing","request_id":"body-request-456"}}"""
        }
        val message = runCatching { models.checkConnection() }.exceptionOrNull()!!.message!!
        listOf("Jev", "POST https://gateway.example/v1/systemone", config.jevModel, "HTTP 404",
            "no route registered", "not_found_error", "route_missing", "body-request-456").forEach {
            assertTrue("Missing $it in $message", message.contains(it))
        }
        listOf("jev-secret", "chat-secret", "query-secret", "query%2Dsecret").forEach { assertFalse(message.contains(it)) }
    }

    @Test fun plannerFailureIdentifiesSecondServiceAndHeaderRequestId() = runBlocking {
        val models = models { path ->
            if (path.endsWith("systemone")) 200 to jevReply
            else 401 to """{"error":{"message":"Invalid API key","type":"authentication_error"}}"""
        }
        val message = runCatching { models.checkConnection() }.exceptionOrNull()!!.message!!
        listOf("规划与视觉模型", config.chatEndpoint, "planner", "HTTP 401", "Invalid API key", "header-request-123")
            .forEach { assertTrue("Missing $it in $message", message.contains(it)) }
    }

    @Test fun nonJsonErrorRetainsStatusWithoutDumpingHtml() = runBlocking {
        val models = models { 502 to "<html>private upstream proxy page</html>" }
        val message = runCatching { models.checkConnection() }.exceptionOrNull()!!.message!!
        assertTrue(message.contains("HTTP 502"))
        assertTrue(message.contains("JSON"))
        assertFalse(message.contains("private upstream"))
    }

    @Test fun malformedSuccessIdentifiesProtocolWithoutEchoingResponse() = runBlocking {
        val models = models { 200 to "private invalid response" }
        val message = runCatching { models.checkConnection() }.exceptionOrNull()!!.message!!
        assertTrue(message.contains("Jev"))
        assertTrue(message.contains("Decisions"))
        assertTrue(message.contains("https://gateway.example/v1/systemone"))
        assertFalse(message.contains("private invalid response"))
    }

    @Test fun oversizedErrorKeepsStatusAndBoundedDetails() = runBlocking {
        val models = models { 503 to """{"error":{"message":"${"x".repeat(9000)}jev-secret"}}""" }
        val message = runCatching { models.checkConnection() }.exceptionOrNull()!!.message!!
        assertTrue(message.contains("HTTP 503"))
        assertTrue(message.contains("8 KB"))
        assertTrue(message.length <= 4096)
        assertFalse(message.contains("jev-secret"))
    }

    @Test fun dnsFailureExplainsCauseWithoutEchoingExceptionSecrets() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw UnknownHostException("private network jev-secret") }.build()
        val message = runCatching { CloudModels(config, HttpTransport(client)).checkConnection() }.exceptionOrNull()!!.message!!
        assertTrue(message.contains("域名解析失败"))
        assertTrue(message.contains("UnknownHostException"))
        assertFalse(message.contains("private network"))
    }

    @Test fun taskFailureNamesTheCallAndKeepsServerReasonWithoutSecrets() = runBlocking {
        val models = models {
            400 to """{"error":{"message":"state must be a string; Bearer jev-secret","type":"invalid_request_error","request_id":"body-request-789"},"echo":"private screen text"}"""
        }
        val screen = Screen("browser.app", 1, 1080, 2400, 0, emptyList())
        val message = runCatching { models.decide("goal", screen, Plan("browser.app", listOf("search"), emptyList()), emptyList(), null) }
            .exceptionOrNull()!!.message!!
        listOf("Jev 决策", "HTTP 400", "state must be a string", "invalid_request_error", "body-request-789")
            .forEach { assertTrue("Missing $it in $message", message.contains(it)) }
        listOf("jev-secret", "private screen text").forEach { assertFalse(message.contains(it)) }
    }

    @Test fun bothValidServicesStillPass() = runBlocking {
        val paths = mutableListOf<String>()
        models { path ->
            paths += path
            200 to if (path.endsWith("systemone")) jevReply else """{"choices":[{"message":{"content":"OK"}}]}"""
        }.checkConnection()
        assertEquals(listOf("/v1/systemone", "/v1/chat/completions"), paths)
    }
}
