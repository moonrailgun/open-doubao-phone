package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskControllerTest {
    @Test fun ordinaryActionsRunToCompletionWithoutUserInteraction() = runTest {
        val phone = FakePhone().apply {
            current = screen().copy(elements = listOf(screen().elements[0].copy(editable = true)))
        }
        var turns = 0
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                when (++turns) {
                    1 -> Decision(Action.Click("0.1"), false, Effect.UNKNOWN)
                    2 -> Decision(Action.Type("0.1", "draft"), false, Effect.YES)
                    else -> Decision(null, true, Effect.YES)
                }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Write a draft", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Click("0.1"), Action.Type("0.1", "draft")), phone.actions)
        assertEquals("Observed result", controller.state.value.result)
    }

    @Test fun ineffectiveActionUsesVisualRecoveryAndFinishes() = runTest {
        val phone = FakePhone()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                if (previous?.action is Action.Back) Decision(null, true, Effect.YES)
                else Decision(Action.Wait, false, if (previous == null) Effect.UNKNOWN else Effect.NO)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                assertEquals("pixels", image)
                return ModelStep(Action.Back)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Recover the page", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Wait, Action.Back), phone.actions)
    }

    @Test fun rateLimitedJevHandsTheStepToTheVisionModel() = runTest {
        val phone = FakePhone()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision =
                throw java.io.IOException("Jev 决策请求失败：模型服务返回 HTTP 429")
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                if (previous == null) ModelStep(Action.Back) else ModelStep(done = true, observations = "page read")
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read the page", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Back), phone.actions)
        assertTrue(controller.state.value.trace.any { it.contains("Jev 不可用：Jev 决策请求失败：模型服务返回 HTTP 429") })
    }

    @Test fun failedDispatchReobservesAndChoosesAnotherAction() = runTest {
        val phone = FakePhone()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                phone.result = previous != null
                return Decision(Action.Wait, previous?.action is Action.Back, Effect.UNKNOWN)
            }
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                assertEquals(false, previous?.dispatched)
                return ModelStep(Action.Back)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Recover the page", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Wait, Action.Back), phone.actions)
    }

    @Test fun ambiguousPageWithNavigationLabelsStillGetsVisualEvidence() = runTest {
        val phone = FakePhone().apply {
            current = screen().copy(elements = listOf("返回", "标题", "帮助").mapIndexed { i, text ->
                screen().elements[0].copy(id = "0.$i", text = text)
            })
        }
        var receivedImage: String? = null
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                receivedImage = image
                return ModelStep(handover = "inspected")
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read the canvas", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals("pixels", receivedImage)
    }

    @Test fun coordinateDecisionIsDiscardedWhenOnlyPixelsChange() = runTest {
        val phone = FakePhone()
        var finish = false
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, finish, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                phone.pixels = "button moved"
                finish = true
                return ModelStep(Action.Tap(300, 300))
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Open the canvas item", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertFalse(phone.actions.any { it is Action.Tap })
    }

    @Test fun pageChangesDuringScreenshotAreReobservedAutomatically() = runTest {
        var captures = 0
        val phone = object : Phone by FakePhone() {
            override suspend fun screenshot(screen: Screen): String {
                if (++captures == 1) throw ScreenChangedException()
                return "fresh pixels"
            }
        }
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                assertEquals("fresh pixels", image)
                return ModelStep(done = true)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read the page", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(2, captures)
    }

    @Test fun continuouslyChangingScreensPauseAndCanResumeWhenStable() = runTest {
        var observations = 0
        var unstable = true
        val phone = object : Phone by FakePhone() {
            override suspend fun observe(): Screen {
                observations++
                if (unstable) throw ScreenChangedException()
                return screen()
            }
        }
        val controller = TaskController(this, phone, FakeModels().apply { finish = true })
        controller.start("Read the page", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(6, observations)
        assertTrue(controller.state.value.message.contains("页面持续变化"))
        unstable = false
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
    }

    @Test fun sourceSurvivesMoreThanEightIntermediatePages() = runTest {
        val phone = FakePhone().apply { current = screen(text = "Source fact 42"); changeAfterAction = true }
        var turns = 0
        var summaryEvidence = ""
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(Action.Wait, ++turns == 10, Effect.UNKNOWN)
            override suspend fun summarize(goal: String, evidence: Evidence): String {
                summaryEvidence = evidence.toString()
                return "result"
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read then summarize", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(summaryEvidence.contains("Source fact 42"))
    }

    @Test fun crossAppDraftingRetainsBrowserSourcesAfterOpeningANewNote() = runTest {
        var current = screen(text = "Original browser source")
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe() = current
            override suspend fun screenshot(screen: Screen) = "pixels"
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                if (action == Action.OpenApp("notes.app")) current = screen("notes.app", "Notes list")
                if (action is Action.Click) current = screen("notes.app", "Empty draft")
                return true
            }
        }
        var sourceContext = emptyList<Screen>()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(when (screen.elements[0].text) {
                    "Original browser source" -> Action.OpenApp("notes.app")
                    "Notes list" -> Action.Click("0.1")
                    else -> null
                }, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                assertEquals("notes.app", previous?.screen?.packageName)
                sourceContext = evidence.screens
                return ModelStep(handover = "Draft generation inspected")
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read browser sources and draft a note", setOf("browser.app", "notes.app"))
        advanceUntilIdle()
        assertTrue(sourceContext.any { it.packageName == "browser.app" && it.elements[0].text == "Original browser source" })
        assertEquals("Empty draft", sourceContext.last().elements[0].text)
    }

    @Test fun stoppedTaskCannotExecuteLateModelResponse() = runTest {
        val phone = FakePhone()
        val models = FakeModels().apply { slow = true }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(TaskStatus.STOPPED, controller.state.value.status)
        assertFalse(phone.actions.any { it is Action.Wait })
    }

    @Test fun pageChangesAreReobservedWithoutUserInteraction() = runTest {
        val phone = FakePhone()
        val models = FakeModels().apply { slow = true }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        runCurrent()
        phone.current = screen(text = "new page")
        models.slow = false
        models.finish = true
        advanceUntilIdle()
        assertEquals("new page", models.lastScreen?.elements?.first()?.text)
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
    }

    @Test fun unchangedScreensUseRecoveryThenPauseInsteadOfLoopingForever() = runTest {
        val phone = FakePhone()
        var recoveries = 0
        val models = object : Models by FakeModels() {
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                recoveries++
                return ModelStep(Action.Wait)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(recoveries > 0)
        assertEquals(6, phone.actions.count { it is Action.Wait })
    }

    @Test fun unavailableAppPausesAndResumesWithoutReopening() = runTest {
        val phone = FakePhone().apply { result = false }
        val models = FakeModels().apply { finish = true }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(1, phone.actions.size)
        phone.result = true
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, phone.actions.size)
    }

    @Test fun emergencyStopCancelsVisualRecoveryBeforeItCanDispatch() = runTest {
        val phone = FakePhone()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep {
                withContext(NonCancellable) { delay(1000) }
                return ModelStep(Action.Click("0.1"))
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(TaskStatus.STOPPED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
    }

    @Test fun stepLimitEndsWithoutDispatchingAnotherAction() = runTest {
        val phone = FakePhone().apply { changeAfterAction = true }
        val controller = TaskController(this, phone, FakeModels())
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, controller.state.value.status)
        assertEquals(30, controller.state.value.steps)
        val count = phone.actions.size
        advanceUntilIdle()
        assertEquals(count, phone.actions.size)
    }

    @Test fun taskTimeoutEndsWithoutDispatchingALateResponse() = runTest {
        val phone = FakePhone().apply { changeAfterAction = true }
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(45_000)
                return Decision(Action.Wait, false, Effect.UNKNOWN)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, controller.state.value.status)
        assertEquals(300_000, testScheduler.currentTime)
        assertEquals(6, phone.actions.count { it == Action.Wait })
    }

    @Test fun serviceInterruptionEndsAndCancelsThePendingDecision() = runTest {
        val phone = FakePhone()
        val controller = TaskController(this, phone, FakeModels().apply { slow = true })
        controller.start("search", setOf("browser.app"))
        runCurrent()
        controller.fail("service disconnected")
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, controller.state.value.status)
        assertEquals("service disconnected", controller.state.value.message)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
    }

    @Test fun failureLeavesATraceOfThePlanTheFailingCallAndTheError() = runTest {
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, false, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?): ModelStep =
                throw java.io.IOException("视觉模型请求失败：模型服务返回 HTTP 400")
        }
        val controller = TaskController(this, FakePhone(), models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        val trace = controller.state.value.trace.joinToString("\n")
        listOf("开始：search", "规划结果：browser.app", "页面 browser.app", "→ 视觉模型", "× 视觉模型", "HTTP 400",
            "java.io.IOException", "\tat ", "结束：FAILED").forEach { assertTrue("Missing $it in\n$trace", trace.contains(it)) }
    }

    @Test fun timeoutTraceNamesThePendingCall() = runTest {
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(61_000)
                error("unreachable")
            }
        }
        val controller = TaskController(this, FakePhone(), models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertTrue(controller.state.value.trace.any { "Jev 决策" in it && "超时" in it })
    }

    @Test fun outOfScopePageNamesTheApp() = runTest {
        val phone = FakePhone().apply { current = screen().copy(packageName = "com.moonrailgun.opendoubao.assistant") }
        val controller = TaskController(this, phone, FakeModels())
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertTrue(controller.state.value.message.contains("com.moonrailgun.opendoubao.assistant"))
    }

    @Test fun unselectedPagePausesImmediatelyWithoutExposingIt() = runTest {
        val phone = object : Phone by FakePhone() {
            override suspend fun observe() = screen("com.google.android.gms", "Account details")
            override suspend fun screenshot(screen: Screen): String = error("Must not capture an unselected app")
        }
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision =
                error("Must not send an unselected app to the model")
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(controller.state.value.message.contains("Google Play 服务"))
        assertFalse(controller.state.value.trace.any { "Account details" in it || "异常：" in it })
    }

    @Test fun resumeKeepsProgressAndSourcesAfterManualHandlingWithoutReplanningOrReopening() = runTest {
        val device = FakePhone().apply { current = screen(text = "Original source") }
        val phone = object : Phone by device {
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                val dispatched = device.execute(action, expected, expectedImage)
                if (action is Action.Click) device.current = screen("com.google.android.gms", "Account details")
                return dispatched
            }
        }
        var plans = 0
        var decisions = 0
        var summaryEvidence: Evidence? = null
        val models = object : Models by FakeModels() {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>): Plan {
                plans++
                return Plan("browser.app", listOf("Read source", "Summarize"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                decisions++
                if (decisions == 1) return Decision(Action.Click("0.1"), false, Effect.UNKNOWN)
                assertNull("Manual handling makes the previous action stale", previous)
                assertEquals("After manual handling", screen.elements[0].text)
                return Decision(null, true, Effect.UNKNOWN)
            }
            override suspend fun summarize(goal: String, evidence: Evidence): String {
                summaryEvidence = evidence
                return "Observed result"
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read and summarize", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(1, controller.state.value.steps)
        val savedPlan = controller.state.value.plan
        val savedLog = controller.state.value.log
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        device.current = screen(text = "After manual handling")
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, plans)
        assertEquals(2, decisions)
        assertEquals(1, controller.state.value.steps)
        assertEquals(savedPlan, controller.state.value.plan)
        assertTrue(controller.state.value.log.containsAll(savedLog))
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Click("0.1")), device.actions)
        assertTrue(summaryEvidence!!.sources.any { it.text == "Original source" })
        assertTrue(summaryEvidence!!.sources.any { it.text == "After manual handling" })
        assertTrue(summaryEvidence!!.screens.all { it.packageName == "browser.app" })
    }

    @Test fun resumingOnTheSameUnselectedPagePausesAgainWithoutDispatching() = runTest {
        val phone = FakePhone().apply { current = screen("com.google.android.gms") }
        val controller = TaskController(this, phone, FakeModels())
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(0, controller.state.value.steps)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
        assertEquals(2, controller.state.value.trace.count { "暂停：" in it })
    }

    @Test fun resumeUsesOnlyTheRemainingActiveTimeAndExcludesTimeSpentPaused() = runTest {
        val phone = FakePhone().apply { changeAfterAction = true }
        var decisions = 0
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                delay(45_000)
                if (++decisions == 6) phone.current = screen("com.google.android.gms")
                return Decision(Action.Wait, false, Effect.UNKNOWN)
            }
        }
        val controller = TaskController(this, phone, models, monotonicMillis = { testScheduler.currentTime })
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(270_000L, testScheduler.currentTime)
        assertEquals(5, controller.state.value.steps)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        phone.current = screen(text = "After manual handling")
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, controller.state.value.status)
        assertEquals("请求或任务超时", controller.state.value.message)
        assertEquals(900_000L, testScheduler.currentTime)
        assertEquals(5, controller.state.value.steps)
        assertEquals(5, phone.actions.count { it == Action.Wait })
    }

    @Test fun leavingSelectedAppDuringDecisionPausesAndDiscardsThePendingAction() = runTest {
        val phone = FakePhone()
        var decisions = 0
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                if (++decisions == 1) {
                    phone.current = screen("com.google.android.gms", "Account details")
                    return Decision(Action.Click("0.1"), false, Effect.UNKNOWN)
                }
                assertEquals("Returned page", screen.elements[0].text)
                return Decision(null, true, Effect.UNKNOWN)
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
        phone.current = screen(text = "Returned page")
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
    }

    @Test fun passwordPagePausesBeforeCaptureOrDecisionAndCanResumeAfterManualHandling() = runTest {
        val device = FakePhone().apply {
            current = screen().copy(elements = listOf(screen().elements[0].copy(password = true)))
        }
        val phone = object : Phone by device {
            override suspend fun screenshot(screen: Screen): String = error("Must not capture a password page")
        }
        val models = FakeModels().apply { finish = true }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.contains("密码输入框"))
        assertNull(models.lastScreen)
        device.current = screen(text = "Signed in")
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals("Signed in", models.lastScreen?.elements?.first()?.text)
    }

    @Test fun visualHandoverPausesAndRetainsItsObservationsForResume() = runTest {
        val phone = FakePhone()
        var finish = false
        var summarizedSources = emptyList<Source>()
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, finish, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.Click("0.1"), handover = "Please solve the challenge", observations = "Read before handover")
            override suspend fun summarize(goal: String, evidence: Evidence): String {
                summarizedSources = evidence.sources
                return "Observed result"
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals("Please solve the challenge", controller.state.value.message)
        finish = true
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(summarizedSources.any { it.text == "Read before handover" && it.visual })
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
    }

    @Test fun rejectedActionPausesWithoutDispatchAndResumeUsesANewDecision() = runTest {
        val phone = FakePhone()
        var decisions = 0
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                if (++decisions == 1) Decision(Action.OpenApp("unselected.app"), false, Effect.UNKNOWN)
                else Decision(null, true, Effect.UNKNOWN)
        }
        val controller = TaskController(this, phone, models)
        controller.start("search", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(2, decisions)
    }

    @Test fun pausedTasksCanBeStoppedOrFailedAndCannotResumeAfterward() = runTest {
        for (terminalStatus in listOf(TaskStatus.STOPPED, TaskStatus.FAILED)) {
            val phone = FakePhone().apply { current = screen("unselected.app") }
            val controller = TaskController(this, phone, FakeModels())
            controller.start("search", setOf("browser.app"))
            advanceUntilIdle()
            assertEquals(TaskStatus.PAUSED, controller.state.value.status)
            if (terminalStatus == TaskStatus.STOPPED) controller.stop() else controller.fail("service disconnected")
            controller.resume()
            advanceUntilIdle()
            assertEquals(terminalStatus, controller.state.value.status)
            assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
        }
    }

    @Test fun stoppedTaskCannotBePausedByALateObservation() = runTest {
        val phone = object : Phone by FakePhone() {
            override suspend fun observe(): Screen {
                withContext(NonCancellable) { delay(1000) }
                return screen("com.google.android.gms")
            }
        }
        val controller = TaskController(this, phone, FakeModels())
        controller.start("search", setOf("browser.app"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(TaskStatus.STOPPED, controller.state.value.status)
        assertFalse(controller.state.value.trace.any { "暂停：" in it })
    }

    @Test fun selectedSystemComponentCanBeObservedAndOperatedAfterAnotherAppOpensIt() = runTest {
        val component = "com.google.android.gms"
        val device = FakePhone().apply { nonlaunchableApps += component }
        val phone = object : Phone by device {
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                val result = device.execute(action, expected, expectedImage)
                if (action is Action.Click) device.current = screen(component, "Choose account")
                return result
            }
        }
        var turns = 0
        val models = object : Models by FakeModels() {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>): Plan {
                assertEquals(setOf("browser.app", component), apps)
                assertEquals(setOf("browser.app"), launchableApps)
                return Plan("browser.app", listOf("Select account"), emptyList())
            }
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
                assertFalse(candidates.any { it.action == Action.OpenApp(component) })
                if (++turns > 1) assertEquals(component, screen.packageName)
                return Decision(Action.Click("0.1"), turns == 3, Effect.YES)
            }
            override suspend fun summarize(goal: String, evidence: Evidence): String {
                assertTrue(evidence.sources.any { it.app == component && it.text == "Choose account" })
                return "Account selected"
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Select the account", setOf("browser.app", component))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf(Action.OpenApp("browser.app"), Action.Click("0.1"), Action.Click("0.1")), device.actions)
    }

    @Test fun nonlaunchablePlanPausesUntilManualEntryThenResumesWithoutReplanning() = runTest {
        val component = "com.google.android.gms"
        val phone = FakePhone().apply { nonlaunchableApps += component }
        var plans = 0
        val models = object : Models by (FakeModels().apply { finish = true }) {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>): Plan {
                plans++
                return Plan(component, listOf("Read account choices"), emptyList())
            }
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read account choices", setOf("browser.app", component))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.contains("无法直接打开"))
        assertTrue(phone.actions.isEmpty())
        phone.current = screen(component)
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertEquals(1, plans)
        assertTrue(phone.actions.isEmpty())
    }

    @Test fun nonlaunchablePlanAlreadyInForegroundRunsWithoutOpeningIt() = runTest {
        val component = "com.google.android.gms"
        val phone = FakePhone().apply { current = screen(component); nonlaunchableApps += component }
        val models = object : Models by (FakeModels().apply { finish = true }) {
            override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>) =
                Plan(component, listOf("Read choices"), emptyList())
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read choices", setOf(component))
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
        assertTrue(phone.actions.isEmpty())
    }

    @Test fun visualModelCannotLaunchANonlaunchableComponentAndCanResumeAfterManualEntry() = runTest {
        val component = "com.google.android.gms"
        val phone = FakePhone().apply { nonlaunchableApps += component }
        var finish = false
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, finish, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) =
                ModelStep(Action.OpenApp(component))
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read choices", setOf("browser.app", component))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.contains("无法直接打开"))
        assertEquals(listOf(Action.OpenApp("browser.app")), phone.actions)
        phone.current = screen(component)
        finish = true
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
    }

    @Test fun absentVisualActionPausesForManualHandlingAndCanResume() = runTest {
        val phone = FakePhone()
        var finish = false
        val models = object : Models by FakeModels() {
            override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                Decision(null, finish, Effect.UNKNOWN)
            override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) = ModelStep()
        }
        val controller = TaskController(this, phone, models)
        controller.start("Read choices", setOf("browser.app"))
        advanceUntilIdle()
        assertEquals(TaskStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.contains("无法确定下一步操作"))
        assertEquals(0, controller.state.value.steps)
        finish = true
        controller.resume()
        advanceUntilIdle()
        assertEquals(TaskStatus.COMPLETED, controller.state.value.status)
    }

    @Test fun unavailableObservationCaptureOrActionPausesAndResumesWithTheSamePlan() = runTest {
        for (stage in listOf("observe", "screenshot", "execute")) {
            val device = FakePhone()
            var unavailable = true
            val phone = object : Phone by device {
                override suspend fun observe(): Screen {
                    if (unavailable && stage == "observe") throw PhoneUnavailableException("Page unavailable")
                    return device.observe()
                }
                override suspend fun screenshot(screen: Screen): String {
                    if (unavailable && stage == "screenshot") throw PhoneUnavailableException("Capture unavailable")
                    return device.screenshot(screen)
                }
                override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
                    if (unavailable && stage == "execute" && action !is Action.OpenApp) throw PhoneUnavailableException("Action unavailable")
                    return device.execute(action, expected, expectedImage)
                }
            }
            val models = object : Models by FakeModels() {
                override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?) =
                    Decision(if (stage == "execute") Action.Click("0.1") else null, !unavailable, Effect.UNKNOWN)
            }
            val controller = TaskController(this, phone, models)
            controller.start("Read page", setOf("browser.app"))
            advanceUntilIdle()
            assertEquals(stage, TaskStatus.PAUSED, controller.state.value.status)
            assertEquals(stage, 0, controller.state.value.steps)
            unavailable = false
            controller.resume()
            advanceUntilIdle()
            assertEquals(stage, TaskStatus.COMPLETED, controller.state.value.status)
            assertEquals(stage, listOf(Action.OpenApp("browser.app")), device.actions)
        }
    }

    @Test fun stoppedTaskCannotBePausedByALatePhoneFailure() = runTest {
        val phone = object : Phone by FakePhone() {
            override suspend fun observe(): Screen {
                withContext(NonCancellable) { delay(1000) }
                throw PhoneUnavailableException("Page unavailable")
            }
        }
        val controller = TaskController(this, phone, FakeModels())
        controller.start("Read page", setOf("browser.app"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertEquals(TaskStatus.STOPPED, controller.state.value.status)
        assertFalse(controller.state.value.trace.any { "暂停：" in it })
    }

    private class FakePhone : Phone {
        var current = screen()
        val actions = mutableListOf<Action>()
        var result = true
        var changeAfterAction = false
        var pixels = "pixels"
        val nonlaunchableApps = mutableSetOf<String>()
        override fun canLaunch(packageName: String) = packageName !in nonlaunchableApps
        override suspend fun observe() = current
        override suspend fun screenshot(screen: Screen) = pixels
        override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean {
            actions += action
            if (changeAfterAction && action !is Action.OpenApp) current = screen(text = "page ${actions.size}")
            return result
        }
    }

    private class FakeModels : Models {
        var slow = false
        var finish = false
        var click = false
        var lastScreen: Screen? = null
        override suspend fun plan(goal: String, apps: Set<String>, launchableApps: Set<String>) = Plan("browser.app", listOf("search"), emptyList())
        override suspend fun decide(goal: String, screen: Screen, plan: Plan, candidates: List<Candidate>, previous: Previous?): Decision {
            lastScreen = screen
            if (slow) withContext(NonCancellable) { delay(1000) }
            return Decision(if (click) Action.Click("0.1") else Action.Wait, finish, Effect.UNKNOWN)
        }
        override suspend fun fallback(goal: String, screen: Screen, plan: Plan, previous: Previous?, evidence: Evidence, image: String?) = ModelStep(Action.Wait)
        override suspend fun summarize(goal: String, evidence: Evidence) = "Observed result"
    }
}
