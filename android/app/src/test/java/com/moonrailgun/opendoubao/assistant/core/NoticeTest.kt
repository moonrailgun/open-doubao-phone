package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chrome and Maps on 2026-10-14: the system's consent dialog held each task for a minute while the card still said it was planning. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeTest {
    @Test fun aNoticeShowsWhileRunningAndIsIgnoredAfterwards() = runTest {
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = Screen("lark.app", 13, 1440, 3120, 0, emptyList())
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?) = true
        }
        val models = object : Models {
            override suspend fun isChat(goal: String) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>): Plan {
                delay(5_000); return Plan("lark.app", listOf("open"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, true, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        val controller = TaskController(this, phone, models)
        controller.start("打开飞书", setOf("lark.app"))
        advanceTimeBy(1_000)
        controller.notice("等你在系统弹窗里允许操作飞书")
        assertEquals("等你在系统弹窗里允许操作飞书", controller.state.value.message)
        assertTrue(controller.state.value.trace.any { "等你在系统弹窗里允许操作飞书" in it })
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        controller.notice("迟到的提示")
        assertFalse(controller.state.value.trace.any { "迟到的提示" in it })
    }
}
