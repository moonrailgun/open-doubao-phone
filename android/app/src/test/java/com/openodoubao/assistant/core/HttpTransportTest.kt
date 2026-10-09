package com.openodoubao.assistant.core

import com.openodoubao.assistant.model.HttpTransport
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpTransportTest {
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
