package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionControllerTest {
    private fun frame(color: Int = 0, app: String = "game") =
        CompanionFrame(app, 1080, 2400, 0, "jpeg", IntArray(1024) { color })

    @Test fun manualInputBypassesUnchangedScreenAndStaysInAutomaticContext() = runTest {
        var current = frame()
        val calls = mutableListOf<Pair<Long, CompanionContext>>()
        val spoken = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { current }, speak = { spoken += it })
        assertFalse(controller.submit("尚未开始"))
        controller.start(1000, unchanged = { _, _ -> false }) { _, context ->
            calls += currentTime to context
            "同一句回复"
        }
        runCurrent()
        advanceTimeBy(100)
        assertFalse(controller.submit("  "))
        assertTrue(controller.submit("  我在玩辅助  "))
        runCurrent()
        assertEquals(listOf(0L, 100L), calls.map { it.first })
        assertEquals(listOf("我在玩辅助"), calls.last().second.userMessages)
        assertTrue(calls.last().second.manual)
        assertEquals(2, spoken.size)
        current = current.copy(image = "next")
        advanceTimeBy(999); runCurrent()
        assertEquals(2, calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(1100L, calls.last().first)
        assertFalse(calls.last().second.manual)
        assertEquals(listOf("我在玩辅助"), calls.last().second.userMessages)
        controller.stop()
    }

    @Test fun queuedInputsNeverOverlapAndStopDiscardsTheOldSession() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val controller = CompanionController(backgroundScope, capture = { frame() })
        controller.start(1000) { _, context ->
            contexts += context
            withContext(NonCancellable) { delay(3000) }
            "回复"
        }
        runCurrent()
        controller.submit("第一句")
        controller.submit("第二句")
        advanceTimeBy(2999); runCurrent()
        assertEquals(1, contexts.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("第一句"), contexts.last().userMessages)
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("第一句", "第二句"), contexts.last().userMessages)
        controller.submit("不能进入新一轮")
        controller.stop()
        assertFalse(controller.submit("已停止"))
        controller.start(1000) { _, context -> contexts += context; "新回复" }
        runCurrent()
        assertEquals(3, contexts.size)
        advanceTimeBy(3000); runCurrent()
        assertEquals(CompanionContext(), contexts.last())
        assertEquals(4, contexts.size)
        controller.stop()
    }

    @Test fun manualInputSurvivesUnavailableScreensStaleFramesAndRequestErrors() = runTest {
        var current: CompanionFrame? = frame()
        var manualCalls = 0
        val controller = CompanionController(backgroundScope, capture = { current })
        controller.start(1000) { _, context ->
            if (!context.manual) "自动评论" else {
                assertEquals(listOf("这是什么？"), context.userMessages)
                when (++manualCalls) {
                    1 -> null
                    2 -> error("private server error")
                    else -> "手动回复"
                }
            }
        }
        runCurrent()
        current = null
        controller.submit("这是什么？")
        runCurrent()
        assertEquals(0, manualCalls)
        current = frame()
        repeat(3) { advanceTimeBy(1000); runCurrent() }
        assertEquals(3, manualCalls)
        assertEquals("手动回复", controller.state.value.comment)
        advanceTimeBy(1000); runCurrent()
        assertEquals(3, manualCalls)
        controller.stop()
    }

    @Test fun newInputWakesPendingRetriesAndKeepsEarlierContext() = runTest {
        var current: CompanionFrame? = frame()
        val calls = mutableListOf<Pair<Long, CompanionContext>>()
        val controller = CompanionController(backgroundScope, capture = { current })
        controller.start(300_000) { _, context ->
            calls += currentTime to context
            when (context.userMessages.size) {
                2 -> null
                3 -> error("request failed")
                else -> "回复"
            }
        }
        runCurrent()
        current = null
        assertTrue(controller.submit("这是什么？"))
        runCurrent()
        assertEquals(1, calls.size)

        advanceTimeBy(1000)
        current = frame()
        assertTrue(controller.submit("已经回到游戏了"))
        runCurrent()
        assertEquals(listOf(0L, 1000L), calls.map { it.first })
        assertEquals(listOf("这是什么？", "已经回到游戏了"), calls.last().second.userMessages)

        advanceTimeBy(1000)
        assertTrue(controller.submit("请关注辅助"))
        runCurrent()
        assertEquals(listOf(0L, 1000L, 2000L), calls.map { it.first })

        advanceTimeBy(1000)
        assertTrue(controller.submit("再试一次"))
        runCurrent()
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), calls.map { it.first })
        assertEquals(listOf("这是什么？", "已经回到游戏了", "请关注辅助", "再试一次"), calls.last().second.userMessages)
        assertTrue(calls.drop(1).all { it.second.manual })
        controller.stop()
    }

    @Test fun userContextKeepsTheLatestEightInputsInOrder() = runTest {
        var last = CompanionContext()
        val controller = CompanionController(backgroundScope, capture = { frame() })
        controller.start(1000) { _, context -> last = context; "回复" }
        runCurrent()
        repeat(10) { controller.submit("输入$it"); runCurrent() }
        assertEquals((2..9).map { "输入$it" }, last.userMessages)
        controller.stop()
    }

    @Test fun waitsFromCompletionAndNeverOverlapsRequests() = runTest {
        val starts = mutableListOf<Long>()
        var captures = 0
        val controller = CompanionController(backgroundScope, capture = { frame(app = "app${captures++}") })
        controller.start(1000) { _, _ -> starts += currentTime; delay(3000); "漂亮！" }
        runCurrent()
        advanceTimeBy(3999); runCurrent()
        assertEquals(listOf(0L), starts)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 4000L), starts)
        controller.stop()
    }

    @Test fun comparesAgainstLastRequestedFrameAndKeepsQuietOnSmallChanges() = runTest {
        var current: CompanionFrame? = frame()
        var calls = 0
        val spoken = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { current }, speak = { spoken += it })
        controller.start(1000) { _, _ -> calls++; if (calls == 1) "" else "新画面！" }
        runCurrent()
        current = frame(0x101010)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, calls)
        current = frame(0x202020)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf("新画面！"), spoken)
        current = null
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, calls)
        controller.stop()
    }

    @Test fun unchangedGateSkipsCommentsAndRetainsTheLastCommentedImage() = runTest {
        var current = frame().copy(image = "first")
        val compared = mutableListOf<Pair<String, String>>()
        val commented = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { current })
        controller.start(1000, unchanged = { previous, next ->
            compared += previous.image to next.image
            next.image in setOf("noise", "minor")
        }) { next, _ -> commented += next.image; "" }
        runCurrent()
        assertTrue(compared.isEmpty())
        current = current.copy(pixels = current.pixels.copyOf())
        advanceTimeBy(1000); runCurrent()
        assertTrue(compared.isEmpty())
        assertEquals(listOf("first"), commented)
        current = frame(0x101010).copy(image = "noise")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("first" to "noise"), compared)
        current = frame(0x202020).copy(image = "minor")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("first"), commented)
        current = frame(0x404040).copy(image = "changed")
        advanceTimeBy(1000); runCurrent()
        current = frame(0x606060).copy(image = "next")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("first" to "noise", "first" to "minor", "first" to "changed", "changed" to "next"), compared)
        assertEquals(listOf("first", "changed", "next"), commented)
        controller.stop()
    }

    @Test fun unchangedGateSeesLocalizedChangesEvenWhenTheColorGridIsIdentical() = runTest {
        var current = frame().copy(image = "first")
        val compared = mutableListOf<Pair<String, String>>()
        val commented = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { current })
        controller.start(1000, unchanged = { previous, next ->
            compared += previous.image to next.image
            false
        }) { next, _ -> commented += next.image; "" }
        runCurrent()
        // Subtitle changes fit in the bottom two rows of the sampled grid.
        current = frame().copy(image = "dialogue", pixels = IntArray(1024) { if (it >= 960) 0xffffff else 0 })
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("first" to "dialogue"), compared)
        assertEquals(listOf("first", "dialogue"), commented)
        current = current.copy(image = "result")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("first" to "dialogue", "dialogue" to "result"), compared)
        assertEquals(listOf("first", "dialogue", "result"), commented)
        controller.stop()
    }

    @Test fun intervalStartsAfterBothComparisonAndCommentComplete() = runTest {
        val captures = mutableListOf<Long>()
        val comments = mutableListOf<Long>()
        val controller = CompanionController(backgroundScope, capture = {
            captures += currentTime
            frame(app = "app${captures.size}")
        })
        controller.start(1000, unchanged = { _, _ -> delay(2000); false }) { _, _ ->
            comments += currentTime
            delay(3000)
            ""
        }
        runCurrent()
        advanceTimeBy(9999); runCurrent()
        assertEquals(listOf(0L, 4000L), captures)
        assertEquals(listOf(0L, 6000L), comments)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 4000L, 10000L), captures)
        controller.stop()
    }

    @Test fun stoppingDuringComparisonPreventsLateCommentsAndStateChanges() = runTest {
        var captures = 0
        var comments = 0
        val controller = CompanionController(backgroundScope, capture = { frame(app = "app${captures++}") })
        controller.start(1000, unchanged = { _, _ -> withContext(NonCancellable) { delay(2000) }; false }) { _, _ ->
            comments++
            ""
        }
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        controller.stop()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, comments)
        assertEquals(CompanionState(), controller.state.value)
    }

    @Test fun stoppingCancelsRequestAndRestartWaitsForItToFinish() = runTest {
        val spoken = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { frame() }, speak = { spoken += it })
        controller.start(1000) { _, _ -> withContext(NonCancellable) { delay(2000) }; "迟到的回复" }
        runCurrent()
        controller.stop()
        assertFalse(controller.state.value.active)
        var restarted = false
        controller.start(1000) { _, history -> restarted = true; assertEquals(CompanionContext(), history); "新的回复" }
        runCurrent()
        assertFalse(restarted)
        controller.stop()
        controller.start(1000) { _, history -> restarted = true; assertEquals(CompanionContext(), history); "新的回复" }
        runCurrent()
        assertFalse(restarted)
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf("新的回复"), spoken)
        controller.stop()
    }

    @Test fun stoppingDuringTheIntervalPreventsFurtherCaptures() = runTest {
        var captures = 0
        val controller = CompanionController(backgroundScope, capture = { captures++; frame() })
        controller.start(1000) { _, _ -> "漂亮！" }
        runCurrent()
        controller.stop()
        advanceTimeBy(10000); runCurrent()
        assertEquals(1, captures)
        assertEquals(CompanionState(), controller.state.value)
    }

    @Test fun abandonedFrameCanBeRequestedAgainAfterTheInterval() = runTest {
        var calls = 0
        val controller = CompanionController(backgroundScope, capture = { frame() })
        controller.start(1000) { _, history ->
            assertEquals(CompanionContext(), history)
            if (++calls == 1) null else "回到游戏了！"
        }
        runCurrent()
        assertEquals("", controller.state.value.comment)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, calls)
        assertEquals("回到游戏了！", controller.state.value.comment)
        controller.stop()
    }

    @Test fun passiveCaptureAllowsDynamicTextButStillRejectsAnotherWindow() = runTest {
        val before = screen(text = "00:01")
        var after = screen(text = "00:02")
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = false
            override suspend fun observe() = before
            override suspend fun screenshot(screen: Screen): String {
                if (!screen.matchesForAction(after)) throw ScreenChangedException()
                return "jpeg"
            }
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean = error("Must not act")
        }
        assertNotNull(captureCompanion(phone, "assistant.app", emptySet()) { intArrayOf(1) })
        assertFalse(before.matchesForAction(after))
        after = after.copy(windowId = after.windowId + 1)
        try {
            captureCompanion(phone, "assistant.app", emptySet()) { intArrayOf(1) }
            fail("Must reject a different window")
        } catch (_: ScreenChangedException) { }
    }

    @Test fun stoppingIgnoresLateCaptureErrors() = runTest {
        val controller = CompanionController(backgroundScope, capture = {
            withContext(NonCancellable) { delay(1000) }
            error("late capture failure")
        })
        controller.start(1000) { _, _ -> error("Must not request") }
        runCurrent()
        controller.stop()
        advanceTimeBy(1000); runCurrent()
        assertEquals(CompanionState(), controller.state.value)
    }

    @Test fun excludedOwnAndProtectedPagesAreNeverCaptured() = runTest {
        val ownPackage = "assistant.app"
        var current = screen()
        var captures = 0
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = false
            override suspend fun observe() = current
            override suspend fun screenshot(screen: Screen): String { captures++; return "jpeg" }
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean = error("Must not act")
        }
        for (page in listOf(screen(ownPackage), screen("excluded.app"),
            screen().let { it.copy(elements = it.elements.map { node -> node.copy(password = true) }) })) {
            current = page
            assertNull(captureCompanion(phone, ownPackage, setOf("excluded.app")) { error("Must not decode") })
        }
        assertEquals(0, captures)
        current = screen()
        val captured = captureCompanion(phone, ownPackage, setOf("excluded.app")) { intArrayOf(42) }!!
        assertEquals(1, captures)
        assertEquals("jpeg", captured.image)
        assertEquals("browser.app", captured.packageName)
        assertArrayEquals(intArrayOf(42), captured.pixels)
    }

    @Test fun errorsWaitBeforeRetryAndDoNotConsumeTheFrame() = runTest {
        var calls = 0
        val controller = CompanionController(backgroundScope, capture = { frame() })
        controller.start(1000) { _, _ -> if (++calls == 1) error("failed") else "回来了" }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, calls)
        assertEquals("回来了", controller.state.value.comment)
        controller.stop()
    }

    @Test fun ignoresDuplicateCommentsAndBoundsContext() = runTest {
        var capture = 0
        val spoken = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { frame(app = "app${capture++}") }, speak = { spoken += it })
        controller.start(1000) { _, history ->
            assertTrue(history.recentComments.size <= 3)
            if (capture <= 2) "重复" else "评论$capture"
        }
        runCurrent()
        repeat(8) { advanceTimeBy(1000); runCurrent() }
        assertEquals(1, spoken.count { it == "重复" })
        controller.stop()
    }

    @Test fun imageComparisonIgnoresNoiseButDetectsSceneColorAndGeometryChanges() {
        val original = frame()
        assertTrue(original.similarTo(frame(0x101010)))
        assertTrue(original.similarTo(frame().copy(pixels = IntArray(1024) { if (it < 20) 0xffffff else 0 })))
        assertFalse(original.similarTo(frame(0x00aa00)))
        assertFalse(original.similarTo(frame().copy(pixels = IntArray(1024) { if (it < 150) 0xffffff else 0 })))
        assertFalse(original.similarTo(frame(app = "video")))
        assertFalse(original.similarTo(original.copy(rotation = 1)))
    }
}
