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

    @Test fun jevKeepsMemoryAndRecentTasksWhenTheScreensAreTrimmed() {
        // Feishu on 2026-10-13: 孙淼 shows as 孙大炮, which only an earlier task's result said, and Jev scored the open chat 0.13–0.16 done.
        val context = JSONObject().put("memory", org.json.JSONArray()).put("recent_tasks", org.json.JSONArray()
            .put(JSONObject().put("goal", "打开和孙淼的聊天窗口").put("result", "孙淼在飞书中的显示名为“孙大炮”")))
        val state = JevProtocol.request("jev-latest", "打开和孙淼的聊天窗口", screen(), candidates, null, context = context)!!.getJSONObject("state")
        assertEquals("孙淼在飞书中的显示名为“孙大炮”", state.getJSONArray("recent_tasks").getJSONObject(0).getString("result"))
        assertTrue(state.has("memory"))
        val big = screen().copy(elements = (0 until 350).map {
            Element("0.$it", "长".repeat(2000), "", "android.widget.TextView", "", Bounds(0, 0, 1, 1))
        })
        // 2026-10-15: on Feishu the previous page alone filled the budget, and dropping the memory first left Jev without
        // the 孙淼 = 孙大炮 alias on every page but the splash. The screens give way first now.
        val trimmed = JevProtocol.request("jev-latest", "goal", big, candidates, null, context = context)!!
        assertTrue(trimmed.toString().toByteArray().size <= JevProtocol.BUDGET)
        assertTrue(trimmed.getJSONObject("state").has("recent_tasks"))
        assertTrue(trimmed.getJSONObject("state").getJSONObject("screen").getBoolean("truncated"))
        val afterBigPage = JevProtocol.request("jev-latest", "goal", screen(), candidates, Previous(big, Action.Back, true), context = context)!!
            .getJSONObject("state")
        assertTrue(afterBigPage.has("recent_tasks"))
        assertEquals(1, afterBigPage.getJSONObject("screen").getJSONArray("elements").length())
        assertEquals(0, afterBigPage.getJSONObject("previous").getJSONObject("screen").getJSONArray("elements").length())
    }

    @Test fun jevReturnsOnlyAnExistingCandidate() {
        val decision = JevProtocol.parse(answer("c0", .92), candidates)
        assertEquals(Action.Click("0.1"), decision.action)
        assertFalse(decision.done)
        assertEquals(Effect.UNKNOWN, decision.effect)
    }

    @Test fun nestedClicksOnOneTargetShareTheirProbability() {
        // 2026-10-15: a Feishu contact row came as the row and its avatar; Jev gave 0.56 + 0.32, then 0.64 + 0.21, neither
        // alone reaching 0.7, and both steps went to the vision model, which clicked the avatar.
        val row = listOf(Candidate("c0", Action.Click("0.1"), "点击「」"), Candidate("c1", Action.Click("0.1.0"), "点击「avatar_item」"),
            Candidate("c2", Action.Click("0.1.0.2"), "点击「」"), Candidate("c3", Action.Click("0.2"), "点击「btn_send」"))
        assertEquals(Action.Click("0.1.0"), JevProtocol.parse(answer("c1", .55,
            mapOf("c0" to .32, "c1" to .56, "c2" to .02, "c3" to .05, "none" to .05)), row).action)
        assertNull(JevProtocol.parse(answer("c1", .55, mapOf("c0" to .02, "c1" to .56, "c2" to .02, "c3" to .35, "none" to .05)), row).action)
        // A header around the profile button and the search button is not one target.
        val header = listOf(Candidate("c0", Action.Click("0.1"), "点击「tab_title_container」"),
            Candidate("c1", Action.Click("0.1.0"), "点击「avatar_click_wrapper」"), Candidate("c2", Action.Click("0.1.5"), "点击「function_btn_1」"))
        assertNull(JevProtocol.parse(answer("c2", .55, mapOf("c0" to .32, "c1" to .06, "c2" to .56, "none" to .06)), header).action)
        // Typing into a field is another action than clicking it.
        val field = listOf(Candidate("c0", Action.Click("0.1"), "点击「搜索」"), Candidate("c1", Action.Type("0.1.0", "Lyon"), "填写「搜索」：Lyon"))
        assertNull(JevProtocol.parse(answer("c0", .55, mapOf("c0" to .56, "c1" to .4, "none" to .04)), field).action)
    }

    @Test fun lowConfidenceChoiceFallsBackInsteadOfExecuting() {
        assertNull(JevProtocol.parse(answer("c0", .3), candidates).action)
        assertNull(JevProtocol.parse(answer("none", .99), candidates).action)
    }

    @Test fun normalModeUsesPointSevenForActionsCompletionAndEffects() {
        for (score in listOf(.6, .699, .7, .8)) {
            assertEquals(score >= .7, JevProtocol.parse(answer("none", .99, done = score), candidates).done)
            assertEquals(score >= .7, JevProtocol.parse(answer("c0", score), candidates).action != null)
            val raw = JSONObject(answer("c0", .99))
            raw.getJSONObject("answers").getJSONObject("next").put("probabilities", JSONObject().put("c0", score).put("none", 1 - score))
            assertEquals(score >= .7, JevProtocol.parse(raw.toString(), candidates).action != null)
            raw.getJSONObject("answers").getJSONObject("effect").put("choice", "yes").put("confidence", score)
                .put("probabilities", JSONObject().put("yes", .95).put("no", .04).put("unknown", .01))
            assertEquals(if (score >= .7) Effect.YES else Effect.UNKNOWN, JevProtocol.parse(raw.toString(), candidates).effect)
        }
    }

    @Test fun invalidChoiceAndProbabilityAreRejected() {
        listOf(answer("invented", .9), answer("c0", 1.1), answer("c0", -.1), "{}").forEach {
            assertThrows(IllegalArgumentException::class.java) { JevProtocol.parse(it, candidates) }
        }
    }

    @Test fun submitNamesTheFieldItPresses() {
        assertEquals(Action.Submit("0.1"), ModelProtocol.parseAction(JSONObject("""{"action":"submit","node":"0.1"}""")))
        assertEquals(JSONObject().put("action", "submit").put("node", "0.1").toString(), Action.Submit("0.1").json().toString())
        assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parseAction(JSONObject("""{"action":"submit","node":"x"}""")) }
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

    @Test fun theVirtualDisplayIsOnlyForPureLookups() {
        // "打开飞书" ran unseen on 2026-10-15; the owner wants the virtual display only when nothing is shown to them.
        assertTrue(JevProtocol.PLACEMENT_RULE.contains("Choose virtual only when"))
        assertTrue(JevProtocol.PLACEMENT_RULE.endsWith("When unsure, choose foreground."))
    }

    @Test fun placementTakesTheLikelierOfTheTwo() {
        assertEquals(Placement.FOREGROUND, JevProtocol.parsePlacement(placementAnswer("foreground", .91)))
        assertEquals(Placement.VIRTUAL, JevProtocol.parsePlacement(placementAnswer("virtual", .7)))
        // Feishu on 2026-10-13: foreground at .82 with .64 confidence used to wait 4 s for the chat model.
        assertEquals(Placement.FOREGROUND, JevProtocol.parsePlacement("""{"answers":{"placement":{"type":"choice","choice":"foreground",
            "confidence":0.64,"probabilities":{"virtual":0.18,"foreground":0.82}}}}"""))
        // A tie is unsure, and unsure runs where the user can see it.
        assertEquals(Placement.FOREGROUND, JevProtocol.parsePlacement(placementAnswer("foreground", .5)))
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

    private fun answer(choice: String, confidence: Double, probabilities: Map<String, Double>) = JSONObject(answer(choice, confidence))
        .apply { getJSONObject("answers").getJSONObject("next").put("probabilities", JSONObject(probabilities)) }.toString()

    private fun answer(choice: String, confidence: Double, done: Double = .02) = """{
      "model":"jev-1.13.0","answers":{
        "next":{"type":"choice","choice":"$choice","confidence":$confidence,
          "probabilities":{"c0":0.92,"none":0.08}},
        "done":{"type":"noul","noul":$done},
        "effect":{"type":"choice","choice":"unknown","confidence":0.8,
          "probabilities":{"yes":0.1,"no":0.1,"unknown":0.8}}},
      "usage":{"input_tokens":400,"output_tokens":40}}"""
}

fun screen(packageName: String = "browser.app", text: String = "Search") = Screen(
    packageName, 7, 1080, 1920, 0,
    listOf(Element("0.1", text, "", "android.widget.Button", "search", Bounds(0, 0, 200, 100), clickable = true)),
)
