package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vision model's done may carry the user's answer, which replaces the separate summary call. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisionAnswerTest {
    @Test fun doneCarriesAnAnswerOnlyWhenItFits() {
        assertEquals("**已打开**", ModelProtocol.parseStep("""{"action":"done","observations":"chat open","result":" **已打开** "}""").result)
        assertEquals("", ModelProtocol.parseStep("""{"action":"done","observations":"chat open"}""").result)
        assertEquals("", ModelProtocol.parseStep("""{"action":"done","observations":"x","result":"${"长".repeat(4001)}"}""").result)
        assertThrows(IllegalArgumentException::class.java) { ModelProtocol.parseStep("""{"action":"done","observations":"x","result":42}""") }
    }

    @Test fun visionAnswerSkipsTheSummaryCall() = runTest {
        val models = VisionModels(answer = "**已在飞书中打开与张三的聊天窗口**")
        val controller = TaskController(this, StillPhone(), models)
        controller.start("打开和张三的聊天窗口", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("**已在飞书中打开与张三的聊天窗口**", controller.state.value.result)
        assertEquals(0, models.summaries)
        assertTrue(controller.state.value.trace.any { "沿用视觉模型给出的结果" in it })
        assertFalse(controller.state.value.trace.any { "→ 总结" in it })
    }

    @Test fun doneWithoutAnAnswerStillSummarizes() = runTest {
        val models = VisionModels(answer = "")
        val controller = TaskController(this, StillPhone(), models)
        controller.start("读一下页面", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("summary", controller.state.value.result)
        assertEquals(1, models.summaries)
    }

    @Test fun doneSurvivesUpdatesInsideTheSameWindow() = runTest {
        // Feishu's chat page changed between the judgement and the re-check twice on 2026-10-13, costing two more rounds.
        var reads = 0
        val phone = object : Phone by StillPhone() { override suspend fun observe() = screen(text = "status ${++reads}") }
        val models = VisionModels(answer = "已打开")
        val controller = TaskController(this, phone, models)
        controller.start("打开和张三的聊天窗口", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, models.visions)
        assertFalse(controller.state.value.trace.any { "决策期间页面变化" in it })
    }

    @Test fun jevsDoneAlsoSurvivesUpdatesInsideTheSameWindow() = runTest {
        // 2026-10-15: "打开飞书" was done by Jev at 4.3 s, then Feishu redrew and judging again took another 4 s.
        var reads = 0
        val phone = object : Phone by StillPhone() { override suspend fun observe() = screen(text = "status ${++reads}") }
        var decides = 0
        var summarized: Evidence? = null
        val models = object : Models by VisionModels(answer = "") {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, true, Effect.YES).also { decides++ }
            override suspend fun summarize(goal: String, evidence: Evidence): String { summarized = evidence; return "summary" }
        }
        val controller = TaskController(this, phone, models)
        controller.start("打开飞书", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, decides)
        // The summary reads the page as it stands now, not as it was when Jev judged it.
        assertEquals("status $reads", summarized!!.screens.last().elements.first().text)
        assertFalse(controller.state.value.trace.any { "决策期间页面变化" in it })
    }

    @Test fun doneOnAWindowThatIsGoneIsJudgedAgain() = runTest {
        var reads = 0
        // The judged window is replaced by another one before the re-check, so the judgement must be made again.
        val phone = object : Phone by StillPhone() { override suspend fun observe() = screen().copy(windowId = if (++reads <= 2) 7 else 8) }
        val models = VisionModels(answer = "已打开")
        val controller = TaskController(this, phone, models)
        controller.start("打开和张三的聊天窗口", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(2, models.visions)
        assertTrue(controller.state.value.trace.any { "决策期间页面变化" in it })
    }

    /** Jev never finds a step, so every step goes to the vision model, which declares the goal done at once. */
    private class VisionModels(private val answer: String) : Models {
        var summaries = 0
        var visions = 0
        override suspend fun isChat(goal: String, screen: Screen?) = false
        override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
        override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan("browser.app", listOf("open"), emptyList())
        override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
            Decision(null, false, Effect.UNKNOWN)
        override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
            visions++
            return ModelStep(done = true, observations = "chat window open", result = answer)
        }
        override suspend fun summarize(goal: String, evidence: Evidence): String { summaries++; return "summary" }
    }

    private class StillPhone : Phone {
        override fun canLaunch(packageName: String) = true
        override suspend fun observe() = screen()
        override suspend fun screenshot(screen: Screen) = "pixels"
        override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?) = true
    }
}
