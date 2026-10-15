package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feishu on 2026-10-13: planning took 5 to 9 s and only then did the 5 s cold start begin; Jev names the app in about 1 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGuessTest {
    @Test fun jevIsAskedWithLabelsAndContextAndOnlyASureAnswerCounts() {
        val request = JevProtocol.appRequest("jev", "打开和孙淼的聊天窗口", listOf("lark.app" to "飞书", "notes.app" to ""),
            JSONObject().put("recent_tasks", "已在飞书中打开与孙淼的单聊窗口"))
        val question = request.getJSONObject("questions").getJSONObject("app")
        assertEquals("飞书 (lark.app)", question.getJSONObject("criteria").getString("a0"))
        assertEquals("notes.app", question.getJSONObject("criteria").getString("a1"))
        assertEquals(setOf("goal", "recent_tasks"), request.getJSONObject("state").keys().asSequence().toSet())
        fun answer(choice: String, confidence: Double, probability: Double) = """{"answers":{"app":{"type":"choice","choice":"$choice",
            "confidence":$confidence,"probabilities":{"none":${1 - probability},"a0":$probability,"a1":0}}}}"""
        val apps = listOf("lark.app", "notes.app")
        assertEquals("lark.app", JevProtocol.parseApp(answer("a0", .9, .9), apps))
        assertEquals("lark.app", JevProtocol.parseApp(answer("a0", .7, .7), apps))
        assertNull(JevProtocol.parseApp(answer("a0", .699, .9), apps))
        assertNull(JevProtocol.parseApp(answer("a0", .9, .699), apps))
        assertNull(JevProtocol.parseApp(answer("none", .9, .1), apps))
    }

    private val page = Screen("lark.app", 13, 1440, 3120, 0, listOf(
        Element("0.1", "搜索", "", "android.widget.TextView", "", Bounds(0, 0, 100, 100), clickable = true)))

    private class Opened(val action: Action, val at: Long)

    private fun TestScope.run(guess: String?, plan: Plan, guessMillis: Long = 1_000): Pair<TaskController, List<Opened>> {
        val opened = mutableListOf<Opened>()
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = if (opened.isEmpty()) page.copy(packageName = "launcher.app") else page
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                opened += Opened(action, testScheduler.currentTime)
                return true
            }
        }
        val models = object : Models {
            override suspend fun isChat(goal: String, screen: Screen?) = false
            override suspend fun reply(goal: String, screen: Screen, image: String): Plan = error("Unexpected chat")
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>, screen: Screen?, image: String?): Plan { delay(5_000); return plan }
            override suspend fun guessApp(goal: String, apps: Set<String>): String? { delay(guessMillis); return guess }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, true, Effect.YES)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Wait)
            override suspend fun summarize(goal: String, evidence: Evidence) = "done"
        }
        val controller = TaskController(this, phone, models)
        controller.start("打开和孙淼的聊天窗口", setOf("lark.app", "browser.app"))
        advanceUntilIdle()
        return controller to opened
    }

    @Test fun aRightGuessOpensTheAppDuringPlanningAndOnlyOnce() = runTest {
        val (controller, opened) = run("lark.app", Plan("lark.app", listOf("search"), emptyList()))
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("lark.app")), opened.map { it.action })
        assertEquals(1_000L, opened.single().at)
        assertTrue(controller.state.value.trace.any { "规划同为 lark.app，已提前打开" in it })
    }

    @Test fun aWrongGuessIsFollowedByThePlannedApp() = runTest {
        val (controller, opened) = run("browser.app", Plan("lark.app", listOf("search"), emptyList()))
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.OpenApp("lark.app")), opened.map { it.action })
        assertEquals(listOf(1_000L, 5_000L), opened.map { it.at })
    }

    @Test fun anUnsureGuessOrAReplyOpensNothingEarly() = runTest {
        val (unsure, opened) = run(null, Plan("lark.app", listOf("search"), emptyList()))
        assertEquals(TaskStatus.COMPLETED, unsure.state.value.status)
        assertEquals(listOf(5_000L), opened.map { it.at })
        assertTrue(unsure.state.value.trace.any { "Jev 预判应用：不确定，等规划" in it })
        // A reply needs no phone, so a guess still pending when it arrives is dropped.
        val (replied, none) = run("lark.app", Plan("", emptyList(), emptyList(), reply = "孙淼在飞书"), guessMillis = 8_000)
        assertEquals(TaskStatus.COMPLETED, replied.state.value.status)
        assertEquals(emptyList<Action>(), none.map { it.action })
    }
}
