package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.data.ModelCosts
import com.moonrailgun.opendoubao.assistant.model.TokenUsage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class ModelCostsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(file: File) = ModelCosts(file) { secret }
    private fun price() = JSONObject("""{"input":"2","output":"8","cached":"0.5"}""")

    @Test fun estimatesSeparateModesModelsAndProvidersAndSurviveReopening() {
        val file = File(temporary.newFolder(), "costs.enc")
        val costs = store(file)
        costs.start("execution", "https://one.test/chat?key=secret", "model-a")(TokenUsage(1000, 100, 200))
        costs.start("companion", "https://one.test/chat?key=changed", "model-a")(TokenUsage(2000, 500, 0))
        costs.start("execution", "https://one.test/chat", "model-b")(TokenUsage(10, 20, 0))
        costs.start("execution", "https://two.test/chat", "model-a")(null)
        val id = costs.snapshot().getJSONArray("models").getJSONObject(0).getString("id")
        costs.setPrice(id, price())
        val report = store(file).snapshot()
        assertEquals(3, report.getJSONArray("models").length())
        assertEquals("0.0025", report.getJSONObject("execution").getString("amount"))
        assertEquals("0.008", report.getJSONObject("companion").getString("amount"))
        assertEquals("0.0105", report.getJSONObject("total").getString("amount"))
        assertEquals(1, report.getJSONObject("total").getInt("unknownCalls"))
        assertEquals(1, report.getJSONObject("total").getInt("unpricedCalls"))
        assertFalse(report.toString().contains("secret"))
        assertFalse(report.toString().contains("changed"))
        assertFalse(file.readText().contains("model-a"))
        costs.setPrice(id, JSONObject("""{"input":"4","output":"16","cached":"1"}"""))
        assertEquals("0.021", costs.snapshot().getJSONObject("total").getString("amount"))
    }

    @Test fun incompleteCallsPersistAsUnknownAndCompletionCountsOnlyOnceAcrossInstances() {
        val file = File(temporary.newFolder(), "costs.enc")
        val finish = store(file).start("execution", "https://one.test/chat", "model")
        assertEquals(1, store(file).snapshot().getJSONObject("total").getInt("unknownCalls"))
        val pool = Executors.newFixedThreadPool(4)
        repeat(20) { pool.submit { store(file).start("companion", "https://one.test/chat", "model")(TokenUsage(1, 2, 0)) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        finish(TokenUsage(3, 4, 0))
        finish(TokenUsage(3, 4, 0))
        val total = store(file).snapshot().getJSONObject("total")
        assertEquals(21, total.getInt("calls"))
        assertEquals(23, total.getInt("inputTokens"))
        assertEquals(44, total.getInt("outputTokens"))
        assertEquals(0, total.getInt("unknownCalls"))
        assertTrue(total.isNull("amount"))
    }

    @Test fun invalidUsageIsUnknownAndExplicitZeroIsKnown() {
        listOf("{}", """{"usage":{"total_tokens":5}}""",
            """{"usage":{"prompt_tokens":-1,"completion_tokens":1}}""",
            """{"usage":{"prompt_tokens":1.5,"completion_tokens":1}}""",
            """{"usage":{"prompt_tokens":"2","completion_tokens":1}}""",
            """{"usage":{"prompt_tokens":2,"completion_tokens":1,"prompt_tokens_details":"invalid"}}""",
            """{"usage":{"prompt_tokens":1,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":2}}}""")
            .forEach { assertNull(TokenUsage.parse(it)) }
        assertEquals(TokenUsage(0, 0, 0), TokenUsage.parse("""{"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))
        assertEquals(TokenUsage(12, 5, 2), TokenUsage.parse("""{"usage":{"input_tokens":12,"output_tokens":5,"input_tokens_details":{"cached_tokens":2}}}"""))
    }

    @Test fun invalidPricesAndCorruptFilesCannotOverwritePreviousStatistics() {
        val file = File(temporary.newFolder(), "costs.enc")
        val costs = store(file)
        costs.start("execution", "https://one.test/chat", "model")(TokenUsage(1, 1, 0))
        val id = costs.snapshot().getJSONArray("models").getJSONObject(0).getString("id")
        costs.setPrice(id, price())
        for (bad in listOf("-1", "NaN", "1e3", "1000000000", "0.000000001")) {
            assertTrue(runCatching { costs.setPrice(id, price().put("input", bad)) }.isFailure)
        }
        assertEquals("2", costs.snapshot().getJSONArray("models").getJSONObject(0).getJSONObject("price").getString("input"))
        file.writeText("corrupted")
        assertTrue(runCatching { costs.start("execution", "https://one.test/chat", "model") }.isFailure)
        assertEquals("corrupted", file.readText())
    }
}
