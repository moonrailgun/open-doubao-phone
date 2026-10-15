package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-15: Google Maps never loaded its results, and clicking the search box and submitting again ran on to step 18. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopTest {
    /** Each action shows the page [next] gives for the step count; Jev clicks until [doneAfter] steps. */
    private class Run(val next: (Int) -> String, val doneAfter: Int = Int.MAX_VALUE) {
        var steps = 0
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = screen(text = next(steps))
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action is Action.Click) steps++
                return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan("browser.app", listOf("search"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                if (steps >= doneAfter) Decision(null, true, Effect.YES) else Decision(Action.Click("0.1"), false, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
    }

    @Test fun theSameActionOnTheSamePageAThirdTimePauses() = runTest {
        val run = Run({ if (it % 2 == 0) "Search box" else "No results" })
        val controller = TaskController(this, run.phone, run.models)
        controller.start("搜索附近的咖啡店", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message, "同一页面上的同一操作" in controller.state.value.message)
        assertEquals(4, run.steps)
    }

    @Test fun repeatingAnActionThatChangesThePageGoesOn() = runTest {
        val run = Run({ "Count $it" }, doneAfter = 6)
        val controller = TaskController(this, run.phone, run.models)
        controller.start("加 6 份", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(6, run.steps)
    }
}
