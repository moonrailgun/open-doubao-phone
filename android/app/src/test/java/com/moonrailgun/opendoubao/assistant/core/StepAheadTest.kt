package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-15: the guessed app was open 2–3 s before the plan came back, and nothing happened in between. */
@OptIn(ExperimentalCoroutinesApi::class)
class StepAheadTest {
    private class Run(val scope: TestScope, val first: Action, val plannedApp: String = "browser.app", val planMillis: Long = 5_000) {
        val done = mutableListOf<Pair<Action, Long>>()
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen(if (done.isEmpty()) "launcher.app" else "browser.app", text = "Page ${done.size}")
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                done += action to scope.currentTime; return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun guessApp(goal: String, apps: Set<String>) = "browser.app"
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                delay(planMillis); return Plan(plannedApp, listOf("search"), listOf("咖啡"))
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(1_000)
                return if (done.size == 1) Decision(first, false, Effect.UNKNOWN) else Decision(null, true, Effect.YES)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
    }

    @Test fun aSureClickGoesAheadWhilePlanningIsUnderWay() = runTest {
        val run = Run(this, Action.Click("0.1"))
        val controller = TaskController(this, run.phone, run.models)
        controller.start("搜索咖啡", setOf("browser.app", "other.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        val (click, at) = run.done[1]
        assertEquals(Action.Click("0.1"), click)
        assertTrue("clicked at $at", at < 5_000)
        assertTrue(controller.state.value.trace.any { "规划还没返回" in it })
    }

    @Test fun typingWaitsForThePlan() = runTest {
        val run = Run(this, Action.Type("0.1", "咖啡"))
        val controller = TaskController(this, run.phone, run.models)
        controller.start("搜索咖啡", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(listOf(Action.OpenApp("browser.app")), run.done.filter { it.second < 5_000 }.map { it.first })
    }

    @Test fun anOffscreenPhoneOpensNothingBeforeThePlanIsBack() = runTest {
        // 2026-10-15: opening Settings early on the virtual display raised its consent dialog, and the plan then answered
        // from memory, leaving the dialog on screen with no task behind it.
        val run = Run(this, Action.Click("0.1"))
        val offscreen = object : Phone by run.phone {}
        val replying = object : Models by run.models {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                delay(5_000); return Plan("", emptyList(), emptyList(), reply = "Android 17")
            }
        }
        val controller = TaskController(this, run.phone, replying, place = { offscreen to "测试" })
        controller.start("查一下安卓版本", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals("Android 17", controller.state.value.result)
        assertTrue(run.done.isEmpty())
    }

    @Test fun aClickIsNotSentAgainstAPageThatRedrewMeanwhile() = runTest {
        // 2026-10-15: a click chosen on a cold-starting app's first frame was sent against it, failed, and sent the first
        // planned step to the vision model ("上一步未能派发").
        var reads = 0
        val sent = mutableListOf<Action>()
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = if (sent.isEmpty()) screen("launcher.app") else screen(text = if (++reads == 1) "Loading" else "Ready")
            override suspend fun screenshot(screen: Screen) = "pixels"
            // Like the real phones: an action against a page that is no longer there is not dispatched.
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (expected != null && expected.fingerprint != screen(text = "Ready").fingerprint) return false
                sent += action; return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun guessApp(goal: String, apps: Set<String>) = "browser.app"
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                delay(5_000); return Plan("browser.app", listOf("search"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(1_000)
                return if (sent.size < 2) Decision(Action.Click("0.1"), false, Effect.UNKNOWN) else Decision(null, true, Effect.YES)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        val controller = TaskController(this, phone, models)
        controller.start("搜索", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertFalse(controller.state.value.trace.joinToString("\n"), controller.state.value.trace.any { "未生效" in it || "未能派发" in it })
    }

    @Test fun nothingGoesAheadInAnAppThePlanDidNotChoose() = runTest {
        // The plan comes back while Jev is still choosing.
        val run = Run(this, Action.Click("0.1"), plannedApp = "other.app", planMillis = 500)
        val controller = TaskController(this, run.phone, run.models)
        controller.start("搜索咖啡", setOf("browser.app", "other.app"))
        advanceUntilIdle()
        // The click Jev chose before the plan came back is dropped once the plan names another app.
        assertEquals(Action.OpenApp("other.app"), run.done[1].first)
    }
}
