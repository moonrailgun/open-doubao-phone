package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JevVisionTest {
    private val config = ModelConfig(jevKey = "jev-key", jevModel = "jev-1.14.0", chatEndpoint = "https://chat.example/v1",
        chatModel = "text-model", chatKey = "chat-key", jevVision = true)
    private val plan = Plan("browser.app", listOf("open search"), emptyList())
    private val candidates = listOf(Candidate("c0", Action.Click("0.1"), "Search"))
    private val approved = """{"answers":{"approved":{"type":"noul","noul":0.95}}}"""

    private fun models(settings: ModelConfig = config, respond: (Request, JSONObject) -> String): CloudModels {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val raw = respond(chain.request(), JSONObject(buffer.readUtf8()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(raw.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudModels(settings, HttpTransport(client))
    }

    private fun chat(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("message", JSONObject().put("content", content)))).toString()
    private fun noul(value: Double) = JSONObject().put("type", "noul").put("noul", value)
    private fun choice(options: Set<String>, selected: String, confidence: Double = .95): JSONObject {
        val odds = JSONObject()
        options.forEach { odds.put(it, if (it == selected) confidence else (1 - confidence) / (options.size - 1)) }
        return JSONObject().put("type", "choice").put("choice", selected).put("confidence", confidence).put("probabilities", odds)
    }
    private fun decision(options: Set<String> = setOf("c0", "none", "visual_tap"), selected: String = "visual_tap") = JSONObject()
        .put("answers", JSONObject().put("done", noul(.1))
            .put("next", choice(options, selected)).put("present", noul(.95))
            .put("target", JSONObject("""{"type":"box","confidence":0.95,"box":{"left":0.1,"right":0.3,"top":0.1,"bottom":0.2}}""")))
    private fun planning(body: JSONObject, text: Double = .1): String {
        val options = body.getJSONObject("questions").getJSONObject("app").getJSONObject("criteria").keys().asSequence().toSet()
        return JSONObject().put("answers", JSONObject().put("app", choice(options, "a0", .6)).put("text_plan", noul(text))).toString()
    }

    @Test fun experimentDefaultsOffAndRequiresJevWhenEnabled() {
        assertFalse(ModelConfig().jevVision)
        assertThrows(IllegalArgumentException::class.java) { config.copy(jevKey = "").validate() }
        config.copy(jevKey = "", jevVision = false).validate()
    }

    @Test fun planningActionsAndCompletionUseOnlyJevWithImages() = runBlocking {
        var calls = 0
        val models = models { request, body ->
            calls++
            assertFalse(body.has("messages"))
            assertFalse(body.getJSONObject("questions").has("read_only"))
            assertEquals("true", request.header("TypeSafe-Danger-Zone-Images"))
            val id = body.getJSONObject("state").getString("screenshot")
            val asset = body.getJSONObject("assets").getJSONObject(id)
            assertEquals("jpg", asset.getString("format"))
            assertEquals("pixels", asset.getString("data_base64"))
            if (body.getJSONObject("questions").has("app")) planning(body) else {
                val options = body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria").keys().asSequence().toSet()
                decision(options).apply { if (calls == 3) getJSONObject("answers").put("done", noul(.95)) }.toString()
            }
        }
        val planned = models.plan("search", setOf("browser.app"), setOf("browser.app"), screen(), "pixels")
        assertEquals(Plan("browser.app", listOf("search"), emptyList()), planned)
        assertEquals(Decision(null, false, Effect.UNKNOWN), models.decide("search", screen(), planned, candidates, null))
        val step = withContext(EarlyAction { error("Action must wait for Jev") }) {
            models.fallback("search", screen(), planned, null, Evidence(emptyList()), "pixels")
        }
        assertEquals(Action.Tap(216, 288), step.action)
        assertTrue(models.fallback("search", screen(), planned, null, Evidence(emptyList()), "pixels").done)
        assertEquals(3, calls)
    }

    @Test fun textInputUsesOneTextOnlyPlannerAndThenDirectJevActions() = runBlocking {
        var chats = 0
        val models = models { _, body ->
            if (body.has("messages")) {
                chats++
                assertFalse(body.toString().contains("pixels"))
                assertTrue(body.getJSONArray("messages").getJSONObject(1).get("content") is String)
                chat("""{"app":"browser.app","steps":["search"],"texts":["cats"]}""")
            } else if (body.getJSONObject("questions").has("app")) planning(body, .6)
            else decision(body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria").keys().asSequence().toSet()).toString()
        }
        val planned = models.plan("search cats", setOf("browser.app"), setOf("browser.app"), screen(), "pixels")
        assertEquals(listOf("cats"), planned.texts)
        assertNotNull(models.fallback("search cats", screen(), planned, null, Evidence(emptyList()), "pixels").action)
        assertEquals(1, chats)
    }

    @Test fun uncertainJevUsesVisionForOneStepThenReturnsToJev() = runBlocking {
        for (uncertainty in listOf("confidence", "probability", "present", "target", "none")) {
            val calls = mutableListOf<String>()
            val models = models { _, body ->
                if (body.has("messages")) {
                    calls += "vision"
                    val messages = body.getJSONArray("messages")
                    assertFalse(messages.getJSONObject(0).getString("content").contains("TEXT ONLY"))
                    assertEquals("data:image/jpeg;base64,pixels", messages.getJSONObject(1).getJSONArray("content")
                        .getJSONObject(1).getJSONObject("image_url").getString("url"))
                    chat("""{"action":"click","node":"0.1","observations":"Search is visible"}""")
                } else {
                    calls += "jev"
                    val options = body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria").keys().asSequence().toSet()
                    decision(options).apply {
                        val answers = getJSONObject("answers")
                        if (calls.size > 1) answers.put("done", noul(.6)) else when (uncertainty) {
                            "confidence" -> answers.getJSONObject("next").put("confidence", .599)
                            "probability" -> answers.put("next", choice(options, "visual_tap", .599).put("confidence", .95))
                            "present" -> answers.put("present", noul(.599))
                            "target" -> answers.getJSONObject("target").put("confidence", .599)
                            "none" -> answers.put("next", choice(options, "none"))
                        }
                    }.toString()
                }
            }
            assertEquals(ModelStep(Action.Click("0.1"), observations = "Search is visible"),
                models.fallback("search", screen(), plan, null, Evidence(emptyList()), "pixels"))
            assertTrue(models.fallback("search", screen(), plan, Previous(screen(), Action.Click("0.1"), true),
                Evidence(emptyList()), "pixels").done)
            assertEquals(uncertainty, listOf("jev", "vision", "jev"), calls)
        }
    }

    @Test fun visionRecoveryKeepsImageOnSchemaRetryAndPreservesHandover() = runBlocking {
        var chats = 0
        val models = models { _, body ->
            if (body.has("messages")) {
                chats++
                val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
                assertEquals("data:image/jpeg;base64,pixels", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                if (chats == 1) chat("""{"action":"done","result":"unsupported"}""") else {
                    assertTrue(content.getJSONObject(0).getString("text").contains("previous_reply_rejected"))
                    chat("""{"action":"handover","reason":"Please enter your password"}""")
                }
            } else decision(body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria")
                .keys().asSequence().toSet(), "none").toString()
        }
        assertEquals("Please enter your password", models.fallback("search", screen(), plan, null, Evidence(emptyList()), "pixels").handover)
        assertEquals(2, chats)
    }

    @Test fun openRouterReceivesInlineImageAndMissingImageNeverCallsModels() = runBlocking {
        var count = 0
        val models = models(config.copy(jevEndpoint = "https://openrouter.ai/api/v1/systemone")) { request, body ->
            count++
            assertFalse(body.has("messages"))
            assertNull(request.header("TypeSafe-Danger-Zone-Images"))
            assertFalse(body.has("assets"))
            assertEquals("data:image/jpeg;base64,pixels", body.getJSONObject("state").getJSONObject("screenshot")
                .getJSONObject("image_url").getString("url"))
            decision(body.getJSONObject("questions").getJSONObject("next").getJSONObject("criteria").keys().asSequence().toSet()).toString()
        }
        assertNotNull(models.fallback("search", screen(), plan, null, Evidence(emptyList()), "pixels").action)
        assertNotNull(models.fallback("search", screen(), plan, null, Evidence(emptyList()), null).handover)
        assertEquals(1, count)
    }

    @Test fun invalidJevResponseDoesNotTriggerVisionRecovery() = runBlocking {
        var calls = 0
        val models = models { _, body ->
            calls++
            assertFalse(body.has("messages"))
            decision().apply { getJSONObject("answers").getJSONObject("next").put("choice", "invented") }.toString()
        }
        try { models.fallback("search", screen(), plan, null, Evidence(emptyList()), "pixels"); fail("Invalid choice must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
    }

    @Test fun unsupportedRepliesAreNotReturnedAndVisionFailureIsReported() = runBlocking {
        var count = 0
        var failed = false
        val models = models { _, body ->
            count++
            if (body.has("messages")) {
                assertFalse(body.toString().contains("pixels"))
                chat("""{"reply":"unsupported screen fact"}""")
            } else {
                if (failed) throw java.io.IOException("unavailable")
                approved.replace("0.95", "0.2")
            }
        }
        assertFalse(models.reply("read", screen(), "pixels").reply!!.contains("unsupported"))
        failed = true
        try { models.reply("read", screen(), "pixels"); fail("Jev failure must be reported") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("Jev 多模态")) }
        assertEquals(4, count)
    }

    @Test fun disabledExperimentKeepsChatVisionAndTheOriginalActionPath() = runBlocking {
        var calls = 0
        val models = models(config.copy(jevVision = false)) { _, body ->
            calls++
            assertTrue(body.has("messages"))
            assertFalse(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Jev vision experiment"))
            assertEquals("data:image/jpeg;base64,pixels", body.getJSONArray("messages").getJSONObject(1)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
            if (calls < 3) chat("""{"reply":"Search"}""") else chat("""{"action":"tap","x":20,"y":30}""")
        }
        assertEquals("Search", models.plan("read", setOf("browser.app"), setOf("browser.app"), screen(), "pixels").reply)
        assertEquals("Search", models.reply("read", screen(), "pixels").reply)
        assertEquals(Action.Tap(20, 30), models.fallback("search", screen(), plan, null, Evidence(emptyList()), "pixels").action)
        assertEquals(3, calls)
    }

    @Test fun disabledExperimentKeepsOrdinaryTextJevProtocolAndThreshold() = runBlocking {
        val models = models(config.copy(jevVision = false)) { _, body ->
            val expected = JevProtocol.request(config.jevModel, "search", screen(), candidates, null, plan.steps,
                JSONObject().put("memory", JSONArray()).put("recent_tasks", JSONArray()))!!
            assertEquals(expected.toString(), body.toString())
            JSONObject().put("answers", JSONObject().put("done", noul(.1))
                .put("next", choice(setOf("c0", "none"), "c0", .7))
                .put("effect", choice(setOf("yes", "no", "unknown"), "unknown"))).toString()
        }
        assertEquals(Action.Click("0.1"), models.decide("search", screen(), plan, candidates, null).action)
    }

    @Test fun uncertainNodeActionsAndVisualTargetsNeverExecute() {
        for (selected in listOf("c0", "visual_tap")) {
            val raw = decision(selected = selected)
            for (score in listOf(.599, .6, .7, .8)) {
                for (field in listOf("confidence", "probability")) {
                    val answer = choice(setOf("c0", "none", "visual_tap"), selected, if (field == "probability") score else .95)
                        .put("confidence", if (field == "confidence") score else .95)
                    raw.getJSONObject("answers").put("next", answer)
                    assertEquals(score >= .6, JevVisionProtocol.parse(raw.toString(), candidates, screen()).action != null)
                }
            }
        }
        for ((name, field) in listOf("present" to "noul", "target" to "confidence")) {
            val raw = decision().apply { getJSONObject("answers").getJSONObject(name).put(field, .599) }
            assertNotNull(JevVisionProtocol.parse(raw.toString(), candidates, screen()).handover)
            raw.getJSONObject("answers").getJSONObject(name).put(field, .6)
            assertEquals(Action.Tap(216, 288), JevVisionProtocol.parse(raw.toString(), candidates, screen()).action)
        }
    }

    @Test fun completionUsesPointSixRegardlessOfReadOnlyClassification() {
        for (value in listOf(.599, .6, .7, .8)) {
            val raw = decision(selected = "none").apply { getJSONObject("answers").put("done", noul(value)) }
            assertEquals(value >= .6, JevVisionProtocol.parse(raw.toString(), candidates, screen()).done)
            for (readOnly in listOf(.0, 1.0)) {
                raw.getJSONObject("answers").put("read_only", noul(readOnly))
                assertEquals(value >= .6, JevVisionProtocol.parse(raw.toString(), candidates, screen()).done)
            }
        }
    }

    @Test fun repliesUsePointSixForBothReadOnlyAndStateChangingGoals() = runBlocking {
        var score = .6
        val models = models { _, body ->
            if (body.has("messages")) chat("""{"reply":"Search"}""") else {
                assertEquals(setOf("approved"), body.getJSONObject("questions").keys().asSequence().toSet())
                JSONObject().put("answers", JSONObject().put("approved", noul(score))).toString()
            }
        }
        for (goal in listOf("read", "change setting")) {
            for (value in listOf(.599, .6, .7, .8)) {
                score = value
                assertEquals(score >= .6, models.reply(goal, screen(), "pixels").reply == "Search")
            }
        }
    }

    @Test fun intentAndAppConfidenceFollowTheConfiguredMode() = runBlocking {
        for (experimental in listOf(false, true)) {
            for (score in listOf(.599, .6, .699, .7)) {
                for (field in listOf("confidence", "probability")) {
                    val models = models(config.copy(jevVision = experimental)) { _, body ->
                        val name = if (body.getJSONObject("questions").has("intent")) "intent" else "app"
                        val options = body.getJSONObject("questions").getJSONObject(name).getJSONObject("criteria").keys().asSequence().toSet()
                        val answer = choice(options, if (name == "intent") "chat" else "a0", if (field == "probability") score else .95)
                            .put("confidence", if (field == "confidence") score else .95)
                        JSONObject().put("answers", JSONObject().put(name, answer)).toString()
                    }
                    val accepted = score >= if (experimental) .6 else .7
                    assertEquals(accepted, models.isChat("read"))
                    assertEquals(if (accepted) "browser.app" else null, models.guessApp("search", setOf("browser.app")))
                }
            }
        }
    }

    @Test fun malformedProbabilitiesOrBoxesAreRejected() {
        for (invalid in listOf<Any>("0.99", -.1, 1.1)) {
            val raw = decision().apply { getJSONObject("answers").getJSONObject("done").put("noul", invalid) }
            assertThrows(IllegalArgumentException::class.java) { JevVisionProtocol.parse(raw.toString(), candidates, screen()) }
        }
        for ((field, value) in listOf("left" to -.1, "right" to 1.1, "left" to .4, "top" to .2, "left" to "0.1")) {
            val raw = decision().apply { getJSONObject("answers").getJSONObject("target").getJSONObject("box").put(field, value) }
            assertThrows(IllegalArgumentException::class.java) { JevVisionProtocol.parse(raw.toString(), candidates, screen()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            JevVisionProtocol.parse(decision().toString(), candidates, screen().copy(windowBounds = Bounds(500, 0, 1080, 1920)))
        }
        val wrong = decision().apply { getJSONObject("answers").getJSONObject("next").getJSONObject("probabilities").put("invented", .1) }
        assertThrows(IllegalArgumentException::class.java) { JevVisionProtocol.parse(wrong.toString(), candidates, screen()) }
    }
}
