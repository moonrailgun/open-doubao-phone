package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-15: the chat-or-task check ran ahead of everything and timed out at 3 s in three of five tasks. */
@OptIn(ExperimentalCoroutinesApi::class)
class IntentAlongsideTest {
    private class Run(val scope: TestScope, val chat: Boolean) {
        var planStarted: Long? = null
        var placed: Long? = null
        val opened = mutableListOf<Pair<Action, Long>>()
        var replied = false
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = Screen(if (!chat && opened.isEmpty()) "launcher.app" else "lark.app", 1, 1440, 3120, 0, emptyList())
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                opened += action to scope.currentTime; return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?): Boolean { delay(3_000); return chat }
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan {
                replied = true; return Plan("", emptyList(), emptyList(), reply = "屏幕上是飞书")
            }
            override suspend fun guessApp(goal: String, apps: Set<String>): String = "lark.app"
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan {
                planStarted = scope.currentTime; delay(5_000); return Plan("lark.app", listOf("open"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, true, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        fun controller() = TaskController(scope, phone, models, place = { placed = scope.currentTime; phone to "测试" })
    }

    @Test fun planningStartsWithTheIntentCheckButNothingOpensBeforeItSaysTask() = runTest {
        val run = Run(this, chat = false)
        val controller = run.controller()
        controller.start("打开飞书看看", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(0L, run.planStarted)
        assertTrue(run.placed!! >= 3_000)
        val (open, at) = run.opened.first()
        assertEquals(Action.OpenApp("lark.app"), open)
        assertTrue(at >= 3_000)
        // The plan (5 s) is the longest wait, not intent (3 s) followed by plan (5 s).
        assertTrue(controller.state.value.trace.any { "规划同为 lark.app，已提前打开" in it })
    }

    @Test fun aChatDropsThePlanAndTouchesNothing() = runTest {
        val run = Run(this, chat = true)
        val controller = run.controller()
        controller.start("屏幕上是什么", setOf("lark.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("屏幕上是飞书", controller.state.value.result)
        assertTrue(run.replied)
        assertNull(run.placed)
        assertTrue(run.opened.isEmpty())
        assertEquals(3_000L, currentTime)
    }
}
