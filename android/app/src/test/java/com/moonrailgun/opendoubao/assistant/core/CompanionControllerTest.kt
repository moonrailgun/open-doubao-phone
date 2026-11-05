package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionControllerTest {
    private fun frame(image: String = "jpeg", app: String = "game") =
        CompanionFrame(app, 1080, 2400, 0, image)
    private fun reply(comment: String = "", summary: String = "记住这段画面") = CompanionReply(summary, comment)
    private fun TestScope.controller(capture: suspend () -> CompanionFrame? = { frame() },
        speak: (String) -> Unit = {}) = CompanionController(backgroundScope, capture, speak, now = { currentTime })
    private fun TestScope.tick(millis: Long = 1000) { advanceTimeBy(millis); runCurrent() }

    @Test fun capturesEverySecondAndSendsOnlyLatestFiveAfterFirstInterval() = runTest {
        val captures = mutableListOf<Long>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller(capture = { captures += currentTime; frame("$currentTime") })
        controller.start(10_000) { batch, _ -> batches += batch; reply() }
        runCurrent()
        tick(9_999)
        assertTrue(batches.isEmpty())
        assertEquals((0L..9L).map { it * 1000 }, captures)
        tick(1)
        assertEquals((6L..10L).map { it * 1000 }, batches.single().map { it.capturedAtMillis })
        assertEquals(batches.single().map { it.capturedAtMillis.toString() }, batches.single().map { it.image })
        tick(10_000)
        assertEquals((16L..20L).map { it * 1000 }, batches.last().map { it.capturedAtMillis })
        controller.stop()
    }

    @Test fun shorterIntervalsReduceImageCountWithoutPadding() = runTest {
        for (seconds in listOf(1, 2, 3, 4, 5, 10)) {
            val batches = mutableListOf<List<CompanionFrame>>()
            val controller = controller(capture = { frame("$currentTime") })
            controller.start(seconds * 1000L) { batch, _ -> batches += batch; reply() }
            runCurrent()
            tick(seconds * 1000L)
            assertEquals(minOf(seconds, 5), batches.single().size)
            assertEquals((1 until minOf(seconds, 5)).map { 1000L }, batches.single().zipWithNext { a, b -> b.capturedAtMillis - a.capturedAtMillis })
            controller.stop(); runCurrent()
        }
    }

    @Test fun slowRequestsDoNotBlockCaptureOrQueueAutomaticObservations() = runTest {
        val starts = mutableListOf<Long>()
        val captures = mutableListOf<Long>()
        val controller = controller(capture = { captures += currentTime; frame("$currentTime") })
        controller.start(2000) { _, _ -> starts += currentTime; delay(3500); reply() }
        runCurrent()
        tick(5999)
        assertEquals(listOf(2000L), starts)
        assertEquals((0L..5L).map { it * 1000 }, captures)
        tick(1)
        assertEquals(listOf(2000L, 6000L), starts)
        controller.stop()
    }

    @Test fun slowCaptureNeverOverlapsOrReplaysMissedTicks() = runTest {
        val captures = mutableListOf<Long>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller(capture = { captures += currentTime; delay(1500); frame() })
        controller.start(3000) { batch, _ -> batches += batch; reply() }
        runCurrent(); tick(6000)
        assertEquals(listOf(0L, 2000L, 4000L, 6000L), captures)
        assertEquals(listOf(1500L, 3500L), batches.single().map { it.capturedAtMillis })
        controller.stop()
    }

    @Test fun wholeBatchRetainsTransientAndLocalizedChangesAndSilenceAdvancesSummary() = runTest {
        var current = frame("A")
        val batches = mutableListOf<List<String>>()
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller(capture = { current })
        controller.start(5000) { batch, context ->
            batches += batch.map { it.image }; contexts += context
            reply(summary = "摘要${batches.size}")
        }
        runCurrent(); tick(5000)
        tick(5000)
        assertEquals(1, batches.size)
        current = frame("A-with-one-new-subtitle")
        tick(1000)
        current = frame("A")
        tick(4000)
        assertEquals(listOf("A-with-one-new-subtitle", "A", "A", "A", "A"), batches.last())
        assertEquals("摘要1", contexts.last().summary)
        assertEquals("", controller.state.value.comment)
        current = frame("B"); tick(5000)
        assertEquals("摘要2", contexts.last().summary)
        controller.stop()
    }

    @Test fun oneSecondIntervalStillObservesWhenCaptureTakesLongerThanOneSecond() = runTest {
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller(capture = { delay(1500); frame("$currentTime") })
        controller.start(1000) { batch, _ -> batches += batch; reply() }
        runCurrent(); tick(6000)
        assertEquals(listOf(1500L, 3500L, 5500L), batches.map { it.single().capturedAtMillis })
        controller.stop()
    }

    @Test fun typingPauseKeepsSummaryAndLastReplyForFollowupWithoutReusingOldImages() = runTest {
        var typing = false
        val contexts = mutableListOf<CompanionContext>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller(capture = { if (typing) null else frame() })
        controller.start(10_000) { batch, context ->
            contexts += context; batches += batch; reply("注意左边", "左侧出现敌人")
        }
        runCurrent(); tick(10_000)
        typing = true; controller.setSamplingPaused(true)
        controller.invalidateFrames()
        tick(6000)
        typing = false; controller.setSamplingPaused(false)
        controller.invalidateFrames()
        controller.submit("你刚才那句话是什么意思？"); runCurrent()
        assertEquals("左侧出现敌人", contexts.last().summary)
        assertEquals(listOf("注意左边"), contexts.last().recentComments)
        assertEquals(listOf("你刚才那句话是什么意思？"), contexts.last().userMessages)
        assertEquals(listOf(16000L), batches.last().map { it.capturedAtMillis })
        controller.stop()
    }

    @Test fun speechPauseDoesNotRepeatObservationOrCommentForUnchangedScreen() = runTest {
        var speaking = false
        var calls = 0
        val spoken = mutableListOf<String>()
        lateinit var controller: CompanionController
        controller = controller(capture = { if (speaking) null else frame() }, speak = {
            spoken += it
            speaking = true; controller.setSamplingPaused(true)
            backgroundScope.launch { delay(2000); speaking = false; controller.setSamplingPaused(false) }
        })
        controller.start(10_000) { _, _ -> calls++; reply("注意左边") }
        runCurrent(); tick(30_000)
        assertEquals(1, calls)
        assertEquals(listOf("注意左边"), spoken)
        controller.stop()
    }

    @Test fun inputSentBeforeKeyboardClosesResumesWithNextFrameWithoutRetryingEverySecond() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller()
        controller.start(300_000) { batch, context ->
            batches += batch; contexts += context
            if (contexts.size == 1) error("temporary provider failure")
            reply("回复")
        }
        runCurrent()
        controller.setSamplingPaused(true)
        controller.submit("看看现在"); runCurrent()
        assertTrue(contexts.isEmpty())
        tick(100); controller.setSamplingPaused(false)
        tick(900)
        assertEquals(1, contexts.size)
        assertTrue(contexts.single().manual)
        assertEquals(listOf("看看现在"), contexts.single().userMessages)
        assertEquals(listOf(1000L), batches.single().map { it.capturedAtMillis })
        tick(5000)
        assertEquals(1, contexts.size)
        controller.submit("再试一次"); runCurrent()
        assertEquals(listOf("看看现在", "再试一次"), contexts.last().userMessages)
        assertEquals("回复", controller.state.value.comment)
        controller.stop()
    }

    @Test fun pauseBetweenSamplesDiscardsPendingReplyButKeepsEstablishedContext() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val spoken = mutableListOf<String>()
        val controller = controller(speak = { spoken += it })
        controller.start(1000) { _, context ->
            contexts += context
            if (contexts.size == 2) delay(500)
            reply("回复${contexts.size}", "已有摘要")
        }
        runCurrent(); controller.submit("我是辅助"); runCurrent()
        tick(100); controller.submit("看看现在"); runCurrent()
        tick(100); controller.setSamplingPaused(true)
        tick(100); controller.setSamplingPaused(false)
        tick(300)
        assertEquals(listOf("回复1"), spoken)
        tick(400)
        assertEquals(listOf("回复1", "回复3"), spoken)
        assertEquals("已有摘要", contexts.last().summary)
        assertEquals(listOf("回复1"), contexts.last().recentComments)
        assertTrue(contexts.last().manual)
        controller.stop()
    }

    @Test fun sceneChangeDuringPauseStillClearsContextEvenAfterReturningBeforeNextSample() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller()
        controller.start(10_000) { _, context -> contexts += context; reply("旧回复", "旧摘要") }
        runCurrent(); tick(10_000)
        controller.setSamplingPaused(true)
        tick(100); controller.invalidateScene()
        tick(100); controller.invalidateScene()
        controller.setSamplingPaused(false)
        controller.submit("现在呢？"); runCurrent()
        assertEquals("", contexts.last().summary)
        assertTrue(contexts.last().recentComments.isEmpty())
        controller.stop()
    }

    @Test fun captureInterruptedByInputKeepsContextAndRetriesPendingQuestion() = runTest {
        var interrupted = false
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller(capture = {
            if (interrupted) { delay(500); error("keyboard blocked capture") }
            frame()
        })
        controller.start(1000) { _, context -> contexts += context; reply("已有回复", "已有摘要") }
        runCurrent(); controller.submit("我是辅助"); runCurrent()
        interrupted = true
        tick(100); controller.submit("再看看"); runCurrent()
        tick(100); controller.setSamplingPaused(true)
        tick(400)
        assertEquals(1, contexts.size)
        assertFalse(controller.state.value.retrying)
        interrupted = false; controller.setSamplingPaused(false)
        tick(400)
        assertEquals("已有摘要", contexts.last().summary)
        assertEquals(listOf("已有回复"), contexts.last().recentComments)
        assertEquals(listOf("我是辅助", "再看看"), contexts.last().userMessages)
        assertTrue(contexts.last().manual)
        controller.stop()
    }

    @Test fun differentScreenAfterPauseDropsOldSummaryEvenWithoutWindowEvent() = runTest {
        var current = frame()
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller(capture = { current })
        controller.start(10_000) { _, context -> contexts += context; reply("旧回复", "旧摘要") }
        runCurrent(); tick(10_000)
        controller.setSamplingPaused(true)
        current = frame(app = "other")
        tick(5000)
        controller.setSamplingPaused(false)
        controller.submit("现在呢？"); runCurrent()
        assertEquals("", contexts.last().summary)
        assertTrue(contexts.last().recentComments.isEmpty())
        controller.stop()
    }

    @Test fun jevReceivesWholeBatchAndRetainsBaselineOnSkips() = runTest {
        var current = frame("first")
        val compared = mutableListOf<Pair<String, List<String>>>()
        val commented = mutableListOf<String>()
        val controller = controller(capture = { current })
        controller.start(3000, unchanged = { previous, batch ->
            compared += previous.image to batch.map { it.image }
            batch.all { it.image == "noise" }
        }) { batch, _ -> commented += batch.last().image; reply() }
        runCurrent(); tick(3000)
        tick(3000)
        assertTrue(compared.isEmpty())
        current = frame("noise"); tick(3000)
        assertEquals(listOf("first"), commented)
        current = frame("event"); tick(1000)
        current = frame("first"); tick(2000)
        assertEquals("first" to listOf("event", "first", "first"), compared.last())
        current = frame("next"); tick(3000)
        assertEquals(listOf("first", "first", "next"), commented)
        assertEquals(listOf("first", "first", "first"), compared.map { it.first })
        controller.stop()
    }

    @Test fun manualInputIsImmediateUsesAvailableFramesAndKeepsContext() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val spoken = mutableListOf<String>()
        val controller = controller(speak = { spoken += it })
        assertFalse(controller.submit("未开始"))
        controller.start(10_000) { batch, context -> batches += batch; contexts += context; reply("同一句回复") }
        runCurrent(); tick(100)
        assertFalse(controller.submit("  "))
        controller.submit("  我在玩辅助  "); runCurrent()
        assertEquals(listOf("我在玩辅助"), contexts.single().userMessages)
        assertTrue(contexts.single().manual)
        assertEquals(1, batches.single().size)
        controller.submit("请继续"); runCurrent()
        assertEquals(2, spoken.size)
        assertEquals(listOf("我在玩辅助", "请继续"), contexts.last().userMessages)
        controller.stop()
    }

    @Test fun queuedInputsTakePriorityAfterCurrentRequestAndNeverOverlap() = runTest {
        val calls = mutableListOf<Pair<Long, CompanionContext>>()
        val controller = controller(capture = { frame("$currentTime") })
        controller.start(1000) { _, context -> calls += currentTime to context; delay(3000); reply("回复") }
        runCurrent(); tick()
        controller.submit("第一句"); controller.submit("第二句")
        tick(2999)
        assertEquals(1, calls.size)
        tick(1)
        assertEquals(4000L, calls.last().first)
        assertEquals(listOf("第一句"), calls.last().second.userMessages)
        tick(3000)
        assertEquals(listOf("第一句", "第二句"), calls.last().second.userMessages)
        assertEquals(listOf(1000L, 4000L, 7000L), calls.map { it.first })
        controller.stop()
    }

    @Test fun manualInputSurvivesUnavailableScreensStaleFramesAndRequestErrors() = runTest {
        var current: CompanionFrame? = null
        var calls = 0
        val controller = controller(capture = { current })
        controller.start(1000) { _, context ->
            assertEquals(listOf("这是什么？"), context.userMessages)
            assertTrue(context.manual)
            when (++calls) { 1 -> null; 2 -> error("private server error"); else -> reply("手动回复") }
        }
        runCurrent(); controller.submit("这是什么？"); runCurrent()
        assertEquals(0, calls)
        current = frame()
        tick(); tick(); tick()
        assertEquals(3, calls)
        assertEquals("手动回复", controller.state.value.comment)
        tick()
        assertEquals(3, calls)
        controller.stop()
    }

    @Test fun newInputWakesPendingRetryAndKeepsEarlierContext() = runTest {
        var current: CompanionFrame? = null
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller(capture = { current })
        controller.start(300_000) { _, context -> contexts += context; if (contexts.size == 1) null else reply("回复") }
        runCurrent(); controller.submit("这是什么？"); runCurrent()
        current = frame(); tick()
        controller.submit("已经回到游戏了"); runCurrent()
        assertEquals(listOf("这是什么？", "已经回到游戏了"), contexts.single().userMessages)
        controller.submit("再试一次"); runCurrent()
        assertEquals(2, contexts.size)
        assertEquals(listOf("这是什么？", "已经回到游戏了", "再试一次"), contexts.last().userMessages)
        controller.stop()
    }

    @Test fun appWindowRotationAndUnavailableScreensClearVisualContextButKeepDialogue() = runTest {
        for (next in listOf(frame(app = "other"), frame().copy(rotation = 1), frame().copy(width = 2000),
            frame().copy(windowId = 42), frame().copy(windowBounds = Bounds(0, 0, 10, 10)), null)) {
            var current: CompanionFrame? = frame()
            val contexts = mutableListOf<CompanionContext>()
            val batches = mutableListOf<List<CompanionFrame>>()
            val controller = controller(capture = { current })
            controller.start(10_000) { batch, context -> contexts += context; batches += batch; reply("回复", "旧场景") }
            runCurrent(); controller.submit("我在玩辅助"); runCurrent()
            current = next; tick()
            if (current == null) { current = frame(); tick() }
            controller.submit("现在呢"); runCurrent()
            assertEquals("", contexts.last().summary)
            assertTrue(contexts.last().recentComments.isEmpty())
            assertEquals(listOf("我在玩辅助", "现在呢"), contexts.last().userMessages)
            assertTrue(batches.last().all { it.sameScene(current!!) })
            assertTrue(batches.last().size <= 2)
            controller.stop(); runCurrent()
        }
    }

    @Test fun leavingAndReturningDuringRequestDiscardsOldResultAndKeepsInput() = runTest {
        var current: CompanionFrame? = frame()
        var calls = 0
        val controller = controller(capture = { current })
        controller.start(1000) { _, context ->
            assertTrue(context.manual)
            calls++
            if (calls == 1) delay(2500)
            reply(if (calls == 1) "旧结果" else "新结果")
        }
        runCurrent(); controller.submit("看看"); runCurrent()
        current = null; tick()
        current = frame(); tick()
        tick(500)
        assertEquals("", controller.state.value.comment)
        tick(500)
        assertEquals("新结果", controller.state.value.comment)
        controller.stop()
    }

    @Test fun changingSceneDuringComparisonPreventsOldBatchRequest() = runTest {
        var current: CompanionFrame? = frame("first")
        var calls = 0
        val controller = controller(capture = { current })
        controller.start(1000, unchanged = { _, _ -> delay(2500); false }) { _, _ -> calls++; reply() }
        runCurrent(); tick()
        current = frame("next"); tick()
        current = null; tick()
        current = frame(); tick(); tick(500)
        assertEquals(1, calls)
        tick(500)
        assertEquals(2, calls)
        controller.stop()
    }

    @Test fun windowEventsBetweenSamplesDiscardReplyAndRetainPendingInput() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val batches = mutableListOf<List<CompanionFrame>>()
        val spoken = mutableListOf<String>()
        val controller = controller(speak = { spoken += it })
        controller.start(1000) { batch, context ->
            contexts += context; batches += batch
            if (contexts.size == 2) delay(500)
            reply("回复${contexts.size}", "旧摘要")
        }
        runCurrent(); controller.submit("我是辅助"); runCurrent()
        tick(100); controller.submit("看看现在"); runCurrent()
        tick(100); controller.invalidateScene()
        tick(100); controller.invalidateScene()
        tick(300)
        assertEquals(listOf("回复1"), spoken)
        tick(400)
        assertEquals(listOf("回复1", "回复3"), spoken)
        assertEquals("", contexts.last().summary)
        assertTrue(contexts.last().recentComments.isEmpty())
        assertEquals(listOf("我是辅助", "看看现在"), contexts.last().userMessages)
        assertTrue(contexts.last().manual)
        assertEquals(listOf(1000L), batches.last().map { it.capturedAtMillis })
        controller.stop()
    }

    @Test fun windowEventsBetweenSamplesPreventRequestAfterComparison() = runTest {
        var image = "first"
        var calls = 0
        val controller = controller(capture = { frame(image) })
        controller.start(1000, unchanged = { _, _ -> delay(500); false }) { _, _ -> calls++; reply() }
        runCurrent(); tick()
        image = "next"; tick()
        tick(100); controller.invalidateScene()
        tick(100); controller.invalidateScene()
        tick(300)
        assertEquals(1, calls)
        tick(500)
        assertEquals(2, calls)
        controller.stop()
    }

    @Test fun windowEventDuringCaptureDropsThatImageBeforeManualRequest() = runTest {
        var slow = false
        val batches = mutableListOf<List<CompanionFrame>>()
        val controller = controller(capture = { if (slow) delay(500); frame() })
        controller.start(1000) { batch, context ->
            assertEquals(listOf("看看"), context.userMessages)
            batches += batch; reply()
        }
        runCurrent(); tick(100); slow = true
        controller.submit("看看"); runCurrent()
        tick(100); controller.invalidateScene()
        tick(100); controller.invalidateScene()
        tick(300)
        assertTrue(batches.isEmpty())
        slow = false; tick(400)
        assertEquals(listOf(1000L), batches.single().map { it.capturedAtMillis })
        controller.stop()
    }

    @Test fun stoppingAndRestartingWaitsForUncancellableWorkAndResetsSession() = runTest {
        val spoken = mutableListOf<String>()
        val controller = controller(speak = { spoken += it })
        controller.start(1000) { _, _ -> withContext(NonCancellable) { delay(3000) }; reply("旧结果") }
        runCurrent(); tick()
        controller.submit("不能进入新一轮")
        controller.stop()
        assertFalse(controller.submit("已停止"))
        var restarted = false
        controller.start(1000) { _, context -> restarted = true; assertEquals(CompanionContext(), context); reply("新结果") }
        runCurrent(); tick(2999)
        assertFalse(restarted)
        tick(1001)
        assertEquals(listOf("新结果"), spoken)
        controller.stop()
    }

    @Test fun errorsWaitForNextOpportunityWithoutLosingSummaryOrInput() = runTest {
        var calls = 0
        val contexts = mutableListOf<CompanionContext>()
        val controller = controller(capture = { frame("$currentTime") })
        controller.start(1000) { _, context ->
            contexts += context
            if (++calls == 2) error("private server text") else reply("回复", "有效摘要")
        }
        runCurrent(); tick(); tick()
        assertTrue(controller.state.value.retrying)
        assertFalse(controller.state.value.message.contains("private"))
        tick(999)
        assertEquals(2, calls)
        tick(1)
        assertEquals("有效摘要", contexts.last().summary)
        assertFalse(controller.state.value.retrying)
        controller.stop()
    }

    @Test fun duplicateAutomaticCommentsAreQuietAndHistoriesStayBounded() = runTest {
        val contexts = mutableListOf<CompanionContext>()
        val spoken = mutableListOf<String>()
        val controller = controller(capture = { frame("$currentTime") }, speak = { spoken += it })
        controller.start(1000) { _, context -> contexts += context; reply("重复") }
        runCurrent(); repeat(4) { tick() }
        assertEquals(listOf("重复"), spoken)
        repeat(10) { controller.submit("输入$it"); runCurrent() }
        assertEquals((2..9).map { "输入$it" }, contexts.last().userMessages)
        assertTrue(contexts.all { it.recentComments.size <= 3 })
        tick()
        assertFalse(contexts.last().manual)
        assertEquals((2..9).map { "输入$it" }, contexts.last().userMessages)
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
        assertNotNull(captureCompanion(phone, "assistant.app", emptySet()))
        assertFalse(before.matchesForAction(after))
        after = after.copy(windowId = after.windowId + 1)
        try {
            captureCompanion(phone, "assistant.app", emptySet())
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
        val captured = captureCompanion(phone, ownPackage, setOf("excluded.app"))!!
        assertEquals(1, captures)
        assertEquals("jpeg", captured.image)
        assertEquals("browser.app", captured.packageName)
        assertEquals(current.windowId, captured.windowId)
        assertEquals(current.windowBounds, captured.windowBounds)
    }


    @Test fun stoppingDuringSamplingPreventsFurtherCaptures() = runTest {
        var captures = 0
        val controller = controller(capture = { captures++; frame() })
        controller.start(1000) { _, _ -> reply() }
        runCurrent(); controller.stop(); tick(10000)
        assertEquals(1, captures)
        assertEquals(CompanionState(), controller.state.value)
    }

    @Test fun captureFailuresDropBufferedImagesAndRecoverWithoutLosingInput() = runTest {
        var available = false
        var calls = 0
        val controller = controller(capture = { if (!available) error("private details") else frame() })
        controller.start(1000) { _, context -> calls++; assertEquals(listOf("看看"), context.userMessages); reply("恢复了") }
        runCurrent()
        assertTrue(controller.state.value.retrying)
        assertFalse(controller.state.value.message.contains("private"))
        controller.submit("看看"); runCurrent()
        available = true; tick()
        assertEquals(1, calls)
        assertEquals("恢复了", controller.state.value.comment)
        assertFalse(controller.state.value.retrying)
        controller.stop()
    }
}
