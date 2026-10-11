package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class HttpTransportTest {
    @Test fun loggingFailurePreventsSendingAnUnrecordedRequest() = runBlocking {
        MockWebServer().use { server ->
            val failure = runCatching {
                HttpTransport(log = { throw java.io.IOException("disk full") })
                    .post(server.url("/").toString(), "test-key", JSONObject())
            }.exceptionOrNull()
            assertEquals("模型调试记录保存失败", failure?.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun redactionNeverChangesEventIdentifiers() = runBlocking {
        MockWebServer().use { server ->
            val events = mutableListOf<JSONObject>()
            server.enqueue(MockResponse().setBody("{}"))
            HttpTransport(log = { events += it }).post(server.url("/?token=1").toString(), "request", JSONObject())
            assertEquals("request", events.first().getString("event"))
            java.util.UUID.fromString(events.first().getString("callId"))
            assertEquals(events.first().getString("callId"), events.last().getString("callId"))
        }
    }

    @Test fun diagnosticsKeepFullBodiesAndCorrelateCallsWithoutCredentials() = runBlocking {
        MockWebServer().use { server ->
            val events = CopyOnWriteArrayList<JSONObject>()
            val prompt = "完整提示词".repeat(5000)
            val raw = """{"choices":[],"usage":{"total_tokens":42},"echo":"test-key"}"""
            server.enqueue(MockResponse().setBody(raw).setHeader("x-request-id", "req-123"))
            val result = HttpTransport(log = { events += it }).post(
                server.url("/chat?token=query-secret").toString(), "test-key", JSONObject().put("prompt", prompt), "规划")
            assertEquals(raw, result)
            assertEquals(2, events.size)
            val request = events[0]
            val response = events[1]
            assertEquals("request", request.getString("event"))
            assertEquals("规划", request.getString("stage"))
            assertEquals(prompt, JSONObject(request.getString("body")).getString("prompt"))
            assertEquals(request.getString("callId"), response.getString("callId"))
            assertEquals(200, response.getInt("status"))
            assertEquals("req-123", response.getString("requestId"))
            assertEquals(42, JSONObject(response.getString("body")).getJSONObject("usage").getInt("total_tokens"))
            assertFalse(events.toString().contains("test-key"))
            assertFalse(events.toString().contains("query-secret"))
        }
    }

    @Test fun diagnosticsKeepRawHttpErrorsEvenWhenTheyAreNotJson() = runBlocking {
        MockWebServer().use { server ->
            val events = mutableListOf<JSONObject>()
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>gateway offline</html>"))
            val failure = runCatching { HttpTransport(log = { events += it }).post(server.url("/").toString(), "key", JSONObject()) }
            assertTrue(failure.isFailure)
            assertEquals(502, events.last().getInt("status"))
            assertEquals("<html>gateway offline</html>", events.last().getString("body"))
            assertEquals("failed", events.last().getString("outcome"))
        }
    }

    @Test fun requestIsRecordedBeforeWaitingAndCancellationLeavesATerminalEvent() = runBlocking {
        MockWebServer().use { server ->
            val events = CopyOnWriteArrayList<JSONObject>()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { HttpTransport(log = { events += it }).post(server.url("/").toString(), "key", JSONObject()) }
            withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            assertEquals("request", events.single().getString("event"))
            pending.cancelAndJoin()
            assertEquals(2, events.size)
            assertEquals("cancelled", events.last().getString("outcome"))
        }
    }

    @Test fun diagnosticsMarkOversizedResponsesAsIncomplete() = runBlocking {
        MockWebServer().use { server ->
            val events = mutableListOf<JSONObject>()
            server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            assertTrue(runCatching { HttpTransport(log = { events += it }).post(server.url("/").toString(), "key", JSONObject()) }.isFailure)
            assertTrue(events.last().getBoolean("truncated"))
            assertEquals(1_048_576, events.last().getString("body").length)
        }
    }

    @Test fun slowNonStreamingModelCanUseTheFullRequestBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}").setHeadersDelay(11, TimeUnit.SECONDS))
            val result = HttpTransport().post(server.url("/").toString(), "test-key", JSONObject())
            assertTrue(JSONObject(result).getBoolean("ok"))
        }
    }

    @Test fun postsBearerJsonAndParsesSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val result = HttpTransport().post(server.url("/decide").toString(), "test-key", JSONObject().put("goal", "test"))
            assertTrue(JSONObject(result).getBoolean("ok"))
            val request = server.takeRequest()
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("test", JSONObject(request.body.readUtf8()).getString("goal"))
        }
    }

    @Test fun cancellationClosesTheInFlightCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val transport = HttpTransport(client)
            val pending = launch { transport.post(server.url("/").toString(), "test-key", JSONObject()) }
            withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) ?: error("Request never arrived") }
            pending.cancelAndJoin()
            withTimeout(2000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun errorsDoNotRevealResponseBodyOrAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-key-and-screen-data"))
            val failure = runCatching { HttpTransport().post(server.url("/").toString(), "secret", JSONObject()) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure!!.message!!.contains("private-key"))
            assertFalse(failure.message!!.contains("secret"))
            assertTrue(failure.message!!.contains("401"))
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            assertTrue(runCatching { HttpTransport().post(server.url("/").toString(), "", JSONObject()) }.isFailure)
        }
    }
}
