package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionControllerTest {
    private fun frame(color: Int = 0, app: String = "game") =
        CompanionFrame(app, 1080, 2400, 0, "jpeg", IntArray(1024) { color })

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

    @Test fun stoppingCancelsRequestAndRestartWaitsForItToFinish() = runTest {
        val spoken = mutableListOf<String>()
        val controller = CompanionController(backgroundScope, capture = { frame() }, speak = { spoken += it })
        controller.start(1000) { _, _ -> withContext(NonCancellable) { delay(2000) }; "迟到的回复" }
        runCurrent()
        controller.stop()
        assertFalse(controller.state.value.active)
        var restarted = false
        controller.start(1000) { _, history -> restarted = true; assertTrue(history.isEmpty()); "新的回复" }
        runCurrent()
        assertFalse(restarted)
        controller.stop()
        controller.start(1000) { _, history -> restarted = true; assertTrue(history.isEmpty()); "新的回复" }
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
            assertTrue(history.isEmpty())
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
            assertTrue(history.size <= 3)
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
