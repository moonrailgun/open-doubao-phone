package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screenshot and vision call run alongside Jev, so a step costs the slower of the two rather than their sum. */
@OptIn(ExperimentalCoroutinesApi::class)
class LookAheadTest {
    private val page = Screen("lark.app", 13, 1440, 3120, 0, listOf(
        Element("0.1", "", "", "android.widget.ImageView", "lark:id/function_btn_1", Bounds(1149, 208, 1233, 292), clickable = true)))

    private fun phone(actions: MutableList<Action>) = object : Phone {
        override fun canLaunch(packageName: String) = true
        override suspend fun observe() = page
        override suspend fun screenshot(screen: Screen) = "pixels"
        override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean { actions += action; return true }
    }

    private fun models(jevSure: Boolean, visions: MutableList<String>) = object : Models {
        var decisions = 0
        override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>) = Plan("lark.app", listOf("search"), emptyList())
        override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
            delay(2_000)
            return if (!jevSure) Decision(null, false, Effect.UNKNOWN)
            else if (++decisions == 1) Decision(Action.Click("0.1"), false, Effect.UNKNOWN) else Decision(null, true, Effect.YES)
        }
        override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
            visions += "started"
            delay(if (jevSure) 10_000 else 3_000)
            visions += "finished"
            return ModelStep(done = true, observations = "搜索页已打开", result = "已打开搜索")
        }
        override suspend fun summarize(goal: String, evidence: Evidence) = "done"
    }

    @Test fun visionDoesNotWaitForAnUnsureJev() = runTest {
        val visions = mutableListOf<String>()
        val controller = TaskController(this, phone(mutableListOf()), models(jevSure = false, visions))
        controller.start("打开搜索", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("已打开搜索", controller.state.value.result)
        // Jev's 2 s "no" overlaps the 3 s vision call; one after the other they took 5 s.
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(listOf("started", "finished"), visions)
    }

    @Test fun aSureJevCancelsTheVisionCall() = runTest {
        val actions = mutableListOf<Action>()
        val visions = mutableListOf<String>()
        val controller = TaskController(this, phone(actions), models(jevSure = true, visions))
        controller.start("打开搜索", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("lark.app"), Action.Click("0.1")), actions)
        // Two Jev steps of 2 s each; neither waited for its 10 s vision call, which never finished.
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(listOf("started", "started"), visions)
        assertEquals(2, controller.state.value.trace.count { "× 视觉模型 取消" in it })
    }

    /** Feishu on 2026-10-13: vision spent 5 s on the 7-node launch screen that had loaded long before it answered. */
    @Test fun aPageThatChangedWhileJevWasThinkingIsReadAgainWithoutWaitingForVision() = runTest {
        val splash = page.copy(elements = emptyList())
        var reads = 0
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = if (++reads == 1) splash else page
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?) = true
        }
        val models = object : Models {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>) = Plan("lark.app", listOf("search"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(1_000)
                return Decision(null, false, Effect.UNKNOWN)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                if (screen == splash) { delay(5_000); return ModelStep(Action.Wait) }
                delay(3_000)
                return ModelStep(done = true, observations = "搜索页已打开", result = "已打开搜索")
            }
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        val controller = TaskController(this, phone, models)
        controller.start("打开搜索", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        // 1 s of Jev on the launch screen, the 0.5 s pause before a re-read, then 3 s of vision on the loaded page.
        assertEquals(4_500L, testScheduler.currentTime)
        assertEquals(1, controller.state.value.trace.count { "× 视觉模型 取消" in it })
    }
}
