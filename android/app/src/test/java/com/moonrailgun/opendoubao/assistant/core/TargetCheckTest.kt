package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feishu on 2026-10-13: a busy group chat kept shifting the message list while the search button stayed put. */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetCheckTest {
    private fun page(preview: String, buttonTop: Int = 208) = Screen("lark.app", 13, 1440, 3120, 0, listOf(
        Element("0.1", "", "", "android.widget.ImageView", "lark:id/function_btn_1", Bounds(1149, buttonTop, 1233, buttonTop + 84), clickable = true),
        Element("0.2", preview, "", "android.widget.TextView", "", Bounds(266, 1400, 638, 1472)),
    ))

    @Test fun onlyAnIdenticalNodeAtTheSamePathCounts() {
        val click = Action.Click("0.1")
        assertTrue(sameTarget(click, page("李四: 写一个复盘"), page("小张: 谁写")))
        assertFalse(sameTarget(click, page("a"), page("a", buttonTop = 300)))
        assertFalse(sameTarget(click, page("a"), page("a").copy(windowId = 14)))
        assertFalse(sameTarget(Action.Click("0.9"), page("a"), page("a")))
        assertFalse(sameTarget(Action.Back, page("a"), page("b")))
        assertTrue(sameTarget(Action.Type("0.1", "小张"), page("a"), page("b")))
        assertTrue(sameTarget(Action.Submit("0.1"), page("a"), page("b")))
    }

    @Test fun aClickProceedsWhileTheListBelowKeepsChanging() = runTest {
        var reads = 0
        var clicked = false
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            // Every read before the click shows a new message preview; after it the page settles.
            override suspend fun observe() = if (clicked) page("settled") else page("消息 ${++reads}")
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action == Action.Click("0.1")) clicked = true
                return true
            }
        }
        var decisions = 0
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?) = Plan("lark.app", listOf("search"), emptyList())
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision =
                if (++decisions == 1) Decision(Action.Click("0.1"), false, Effect.UNKNOWN) else Decision(null, true, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) = ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        val controller = TaskController(this, phone, models)
        controller.start("打开搜索", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(clicked)
        assertEquals(2, decisions)
        assertFalse(controller.state.value.trace.any { "决策期间页面变化" in it })
    }
}
