package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPathTest {
    private val phone = object : Phone {
        override fun canLaunch(packageName: String) = true
        override suspend fun observe() = Screen("lark.app", 1, 1440, 3120, 0, emptyList())
        override suspend fun screenshot(screen: Screen) = "pixels"
        override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?) = true
    }

    private class Calls : Models {
        var planned = 0
        override suspend fun isChat(goal: String, screen: Screen?): Boolean { error("A quick command asks no model") }
        override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
        override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan { planned++; error("planned") }
        override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
            Decision(null, true, Effect.YES)
        override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
            ModelStep(Action.Wait)
        override suspend fun summarize(goal: String, evidence: Evidence): String = error("A quick command writes no summary")
    }

    @Test fun aQuickCommandFinishesWithItsFixedResult() = runTest {
        val models = Calls()
        val controller = TaskController(this, phone, models, quick = { goal -> if (goal == "打开飞书") "已打开飞书" else null })
        controller.start("打开飞书", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("已打开飞书", controller.state.value.result)
        assertEquals(0, models.planned)
        assertTrue(controller.state.value.trace.any { "快速通道：已打开飞书" in it })
    }

    @Test fun anythingElseOrAFailureTakesTheFullWay() = runTest {
        for (quick in listOf<suspend (String) -> String?>({ null }, { error("打不开") })) {
            val models = object : Models by Calls() {
                override suspend fun isChat(goal: String, screen: Screen?) = false
            }
            var planned = false
            val full = object : Models by models {
                override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                    planned = true; return Plan("lark.app", listOf("search"), emptyList())
                }
                override suspend fun summarize(goal: String, evidence: Evidence) = "done"
            }
            val controller = TaskController(this, phone, full, quick = quick)
            controller.start("打开飞书搜一下孙淼", setOf("lark.app"))
            advanceUntilIdle()
            assertTrue(planned)
            assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        }
    }
}
