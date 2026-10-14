package com.moonrailgun.opendoubao.assistant.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val candidates = listOf(Candidate("c0", Action.Click("0.1"), "Search button"))

    @Test fun visualObservationsArePreservedAndValidated() {
        assertEquals("42 units", ModelProtocol.parseStep("""{"action":"done","observations":"42 units"}""").observations)
        listOf(JSONObject.NULL, 42, "x".repeat(8001)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                ModelProtocol.parseStep(JSONObject().put("action", "done").put("observations", it).toString())
            }
        }
    }

    @Test fun jevUsesTypedQuestionsAndNeverSendsPixels() {
        val request = JevProtocol.request("jev-latest", "search cats", screen(), candidates, null)!!
        val questions = request.getJSONObject("questions")
        assertEquals("choice", questions.getJSONObject("next").getString("type"))
        assertEquals("noul", questions.getJSONObject("done").getString("type"))
        assertTrue(questions.getJSONObject("next").getJSONObject("criteria").has("none"))
        assertEquals("search cats", request.getJSONObject("state").getString("goal"))
        assertFalse(request.toString().contains("base64"))
    }

    @Test fun jevRequestIsTrimmedToItsInputBudget() {
        val big = screen().copy(elements = (0 until 350).map {
            Element("0.$it", "长".repeat(2000), "", "android.widget.TextView", "", Bounds(0, 0, 1, 1))
        })
        val request = JevProtocol.request("jev-latest", "goal", big, candidates, Previous(big, Action.Back, true), listOf("step"))!!
        assertTrue(request.toString().toByteArray().size <= JevProtocol.BUDGET)
        val state = request.getJSONObject("state")
        assertEquals("step", state.getJSONArray("subgoals").getString(0))
        assertTrue(state.getJSONObject("screen").getBoolean("truncated"))
        assertTrue(state.getJSONObject("screen").getJSONArray("elements").length() > 0)
        assertEquals(0, state.getJSONObject("previous").getJSONObject("screen").getJSONArray("elements").length())
        val tooMany = (0 until 200).map { Candidate("c$it", Action.Type("0.1", "长".repeat(8000)), "") }
        assertNull(JevProtocol.request("jev-latest", "goal", screen(), tooMany, null))
    }

    @Test fun jevReturnsOnlyAnExistingCandidate() {
        val decision = JevProtocol.parse(answer("c0", .92), candidates)
        assertEquals(Action.Click("0.1"), decision.action)
        assertFalse(decision.done)
        assertEquals(Effect.UNKNOWN, decision.effect)
    }

    @Test fun lowConfidenceChoiceFallsBackInsteadOfExecuting() {
        assertNull(JevProtocol.parse(answer("c0", .3), candidates).action)
        assertNull(JevProtocol.parse(answer("none", .99), candidates).action)
    }

    @Test fun invalidChoiceAndProbabilityAreRejected() {
        listOf(answer("invented", .9), answer("c0", 1.1), answer("c0", -.1), "{}").forEach {
            assertThrows(IllegalArgumentException::class.java) { JevProtocol.parse(it, candidates) }
        }
    }

    @Test fun fallbackRejectsUnknownActionsAndOutOfRangeCoordinates() {
        listOf("{\"action\":\"shell\",\"command\":\"rm -rf\"}",
            "{\"action\":\"tap\",\"x\":-1,\"y\":10}",
            "{\"action\":\"type\",\"node\":\"0.1\",\"text\":null}").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parseAction(JSONObject(it)) }
        }
    }

    @Test fun planningCannotSelectAnUnapprovedApp() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProtocol.parsePlan("{\"app\":\"bank.app\",\"steps\":[\"open\"],\"texts\":[]}", setOf("browser.app"))
        }
    }

    @Test fun placementIsOneChoiceQuestionWithOnlyTheGoal() {
        val request = JevProtocol.placementRequest("jev-latest", "帮我查明天天气")
        assertEquals("jev-latest", request.getString("model"))
        assertEquals("帮我查明天天气", request.getJSONObject("state").getString("goal"))
        assertEquals(setOf("goal"), request.getJSONObject("state").keys().asSequence().toSet())
        val question = request.getJSONObject("questions").getJSONObject("placement")
        assertEquals("choice", question.getString("type"))
        assertEquals(setOf("virtual", "foreground"), question.getJSONObject("criteria").keys().asSequence().toSet())
    }

    @Test fun placementTakesTheLikelierOfTheTwo() {
        assertEquals(Placement.FOREGROUND, JevProtocol.parsePlacement(placementAnswer("foreground", .91)))
        assertEquals(Placement.VIRTUAL, JevProtocol.parsePlacement(placementAnswer("virtual", .7)))
        // Feishu on 2026-10-13: foreground at .82 with .64 confidence used to wait 4 s for the chat model.
        assertEquals(Placement.FOREGROUND, JevProtocol.parsePlacement("""{"answers":{"placement":{"type":"choice","choice":"foreground",
            "confidence":0.64,"probabilities":{"virtual":0.18,"foreground":0.82}}}}"""))
        assertEquals(Placement.VIRTUAL, JevProtocol.parsePlacement(placementAnswer("foreground", .5)))
        assertThrows(IllegalArgumentException::class.java) { JevProtocol.parsePlacement(placementAnswer("desktop", .9)) }
        assertEquals(Placement.FOREGROUND, ModelProtocol.parsePlacement("""```json
{"placement":"foreground"}
```"""))
        assertEquals(Placement.VIRTUAL, ModelProtocol.parsePlacement("""{"placement":"virtual"}"""))
        assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parsePlacement("""{"placement":"later"}""") }
    }

    private fun placementAnswer(choice: String, confidence: Double) = """{"answers":{"placement":{"type":"choice","choice":"$choice",
        "confidence":$confidence,"probabilities":{"virtual":${if (choice == "virtual") confidence else 1 - confidence},
        "foreground":${if (choice == "foreground") confidence else 1 - confidence}}}}}"""

    private fun answer(choice: String, confidence: Double) = """{
      "model":"jev-1.13.0","answers":{
        "next":{"type":"choice","choice":"$choice","confidence":$confidence,
          "probabilities":{"c0":0.92,"none":0.08}},
        "done":{"type":"noul","noul":0.02},
        "effect":{"type":"choice","choice":"unknown","confidence":0.8,
          "probabilities":{"yes":0.1,"no":0.1,"unknown":0.8}}},
      "usage":{"input_tokens":400,"output_tokens":40}}"""
}

fun screen(packageName: String = "browser.app", text: String = "Search") = Screen(
    packageName, 7, 1080, 1920, 0,
    listOf(Element("0.1", text, "", "android.widget.Button", "search", Bounds(0, 0, 200, 100), clickable = true)),
)
