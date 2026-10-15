package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 2026-10-15: every task built its own HTTP client, so its first Jev call (the intent check) paid a fresh TLS handshake
 * to the router in us-east-2 (0.5–1 s) and timed out at 3 s in three of five tasks.
 */
class WarmConnectionTest {
    @Test fun aWarmedConnectionCarriesTheNextTasksFirstCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("{}"))
            val endpoint = server.url("/api/alpha/decisions").toString()
            HttpTransport.warm(listOf(endpoint, endpoint))
            val warm = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("HEAD", warm.method)
            assertEquals("/", warm.path)
            assertNull(warm.getHeader("Authorization"))
            Thread.sleep(300)
            // A new transport per task, as AssistantService makes, still reuses the warmed connection.
            HttpTransport().post(endpoint, "key", JSONObject())
            assertEquals(1, server.takeRequest(5, TimeUnit.SECONDS)!!.sequenceNumber)
        }
    }
}
