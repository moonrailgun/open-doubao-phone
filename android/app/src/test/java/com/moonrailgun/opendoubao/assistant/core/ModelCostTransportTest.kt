package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.data.ModelCosts
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class ModelCostTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun costs() = ModelCosts(File(temporary.newFolder(), "costs.enc")) { secret }
    private val body get() = JSONObject().put("model", "test-model")
    private fun usage(input: Int, output: Int) = """{"choices":[],"usage":{"prompt_tokens":$input,"completion_tokens":$output}}"""

    @Test fun jsonAndFinalStreamUsageAreCountedOnceInTheirOwnModes() = runBlocking {
        val costs = costs()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(usage(10, 2)))
            HttpTransport(costs = costs).post(server.url("/chat").toString(), "secret", body)
            server.enqueue(MockResponse().setBody("data: ${usage(20, 1)}\n\ndata: ${usage(20, 4)}\n\ndata: [DONE]\n\n"))
            HttpTransport(costs = costs, costMode = "companion").post(server.url("/chat").toString(), "secret", body, lines = {})
            val report = costs.snapshot()
            assertEquals(10L, report.getJSONObject("execution").getLong("inputTokens"))
            assertEquals(20L, report.getJSONObject("companion").getLong("inputTokens"))
            assertEquals(6L, report.getJSONObject("total").getLong("outputTokens"))
            assertEquals(2L, report.getJSONObject("total").getLong("knownCalls"))
            assertEquals(1, report.getJSONArray("models").length())
        }
    }

    @Test fun missingUsageHttpErrorsAndCancelledRequestsStayUnknown() = runBlocking {
        val costs = costs()
        MockWebServer().use { server ->
            val transport = HttpTransport(costs = costs)
            server.enqueue(MockResponse().setBody("{}"))
            transport.post(server.url("/chat").toString(), "secret", body)
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            assertTrue(runCatching { transport.post(server.url("/chat").toString(), "secret", body) }.isFailure)
            repeat(2) { server.takeRequest() }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { transport.post(server.url("/chat").toString(), "secret", body) }
            withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            val total = costs.snapshot().getJSONObject("total")
            assertEquals(3L, total.getLong("unknownCalls"))
            assertEquals(0L, total.getLong("knownCalls"))
            assertTrue(total.isNull("amount"))
        }
    }

    @Test fun statisticsNeverExposeKeysInModelNamesOrEndpointPaths() = runBlocking {
        val costs = costs()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(usage(10, 2)))
            HttpTransport(costs = costs).post(server.url("/key-secret/chat?token=query-secret").toString(), "key-secret",
                JSONObject().put("model", "model-query-secret"))
            assertEquals("/key-secret/chat?token=query-secret", server.takeRequest().path)
            val report = costs.snapshot().toString()
            assertFalse(report.contains("key-secret"))
            assertFalse(report.contains("query-secret"))
        }
    }

    @Test fun corruptStatisticsDoNotBlockExecutionOrCompanionRequests() = runBlocking {
        val file = temporary.newFile("costs.enc")
        val original = "corrupt statistics".toByteArray()
        file.writeBytes(original)
        val costs = ModelCosts(file) { secret }
        MockWebServer().use { server ->
            for (mode in listOf("execution", "companion")) {
                server.enqueue(MockResponse().setBody(usage(10, 2)))
                assertEquals(usage(10, 2), HttpTransport(costs = costs, costMode = mode)
                    .post(server.url("/chat").toString(), "secret", body))
            }
            assertEquals(2, server.requestCount)
        }
        assertArrayEquals(original, file.readBytes())
        assertEquals("成本统计无法读取，原文件已保留", runCatching { costs.snapshot() }.exceptionOrNull()?.message)
    }

    @Test fun failedRequestAccountingPreservesHistoryAndWarnsAcrossInstancesAndRecovery() = runBlocking {
        val file = File(temporary.newFolder(), "costs.enc")
        val costs = ModelCosts(file) { secret }
        MockWebServer().use { server ->
            val transport = HttpTransport(costs = costs)
            val url = server.url("/chat").toString()
            server.enqueue(MockResponse().setBody(usage(10, 2)))
            transport.post(url, "secret", body)
            assertFalse(costs.snapshot().has("warning"))
            val original = file.readBytes()
            val pending = File(file.path + ".tmp").apply { mkdir() }
            File(pending, "busy").writeText("block writes")
            server.enqueue(MockResponse().setBody(usage(20, 3)))
            assertEquals(usage(20, 3), transport.post(url, "secret", body))
            assertArrayEquals(original, file.readBytes())
            val report = ModelCosts(file) { secret }.snapshot()
            assertTrue(report.getString("warning").contains("累计费用可能偏低"))
            assertEquals(1L, report.getJSONObject("total").getLong("calls"))

            assertTrue(pending.deleteRecursively())
            server.enqueue(MockResponse().setBody(usage(30, 4)))
            assertEquals(usage(30, 4), transport.post(url, "secret", body))
            val recovered = file.copyTo(File(temporary.newFolder(), "recovered.enc"))
            val restored = ModelCosts(recovered) { secret }.snapshot()
            assertEquals(report.getString("warning"), restored.getString("warning"))
            assertEquals(2L, restored.getJSONObject("total").getLong("knownCalls"))
            assertEquals(40L, restored.getJSONObject("total").getLong("inputTokens"))
        }
    }

    @Test fun failedResponseAccountingPreservesJsonStreamsAndHttpErrors() = runBlocking {
        for (kind in listOf("json", "stream", "error")) {
            val file = File(temporary.newFolder(), "costs.enc")
            val costs = ModelCosts(file) { secret }
            val response = if (kind == "stream") "data: ${usage(10, 2)}\n\ndata: [DONE]\n\n" else usage(10, 2)
            var original = byteArrayOf()
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        original = file.readBytes()
                        val pending = File(file.path + ".tmp").apply { mkdir() }
                        File(pending, "busy").writeText("block writes")
                        return MockResponse().setResponseCode(if (kind == "error") 500 else 200).setBody(response)
                    }
                }
                val lines = mutableListOf<String>()
                val result = runCatching {
                    HttpTransport(costs = costs).post(server.url("/chat").toString(), "secret", body,
                        lines = if (kind == "stream") { line -> lines.add(line); Unit } else null)
                }
                if (kind == "error") assertTrue(result.exceptionOrNull()?.message.orEmpty().startsWith("模型服务返回 HTTP 500"))
                else assertEquals(response, result.getOrThrow())
                if (kind == "stream") assertEquals(listOf("data: ${usage(10, 2)}", "", "data: [DONE]", ""), lines)
                assertEquals(1, server.requestCount)
            }
            assertArrayEquals(original, file.readBytes())
            val report = costs.snapshot()
            assertTrue(report.getString("warning").contains("累计费用可能偏低"))
            assertEquals(1L, report.getJSONObject("total").getLong("unknownCalls"))
        }
    }

    @Test fun cancellationAfterReportedUsageRetainsThatUsage() = runBlocking {
        val costs = costs()
        MockWebServer().use { server ->
            val first = "data: ${usage(30, 5)}\n\n"
            server.enqueue(MockResponse().setBody(first + "data: [DONE]\n\n").throttleBody(first.toByteArray().size.toLong(), 1, TimeUnit.SECONDS))
            val seen = CompletableDeferred<Unit>()
            val job = launch {
                HttpTransport(costs = costs).post(server.url("/chat").toString(), "secret", body, lines = { seen.complete(Unit) })
            }
            withTimeout(3000) { seen.await() }
            job.cancelAndJoin()
            // OkHttp finishes the cancelled response on its callback thread.
            withTimeout(3000) { while (costs.snapshot().getJSONObject("total").getInt("knownCalls") == 0) delay(10) }
            assertEquals(30L, costs.snapshot().getJSONObject("total").getLong("inputTokens"))
            assertEquals(5L, costs.snapshot().getJSONObject("total").getLong("outputTokens"))
        }
    }
}
