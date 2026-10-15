package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class TaskStatus { IDLE, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }
data class TaskState(
    val status: TaskStatus = TaskStatus.IDLE, val goal: String = "", val message: String = "等待任务",
    val steps: Int = 0, val result: String = "",
    val log: List<String> = emptyList(), val plan: List<String> = emptyList(),
    /** Timestamped diagnostics kept with the task record: calls, timings, decisions and errors. */
    val trace: List<String> = emptyList(),
)

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private const val TASK_TIMEOUT_MILLIS = 300_000L
fun logLine(entry: String): String = clock.format(LocalTime.now()) + " " + entry.take(4000)

/** Called on the main dispatcher; action dispatch and user controls share that single thread. */
class TaskController(
    private val scope: CoroutineScope, phone: Phone, private val models: Models,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Saves the planner's memory edits and returns what changed, for the trace. */
    private val remember: (List<MemoryOp>) -> String = { "无变化" },
    /** Picks where the task runs once per task, alongside planning: the phone to use and how it was chosen, for the trace. */
    private val place: (suspend () -> Pair<Phone, String>)? = null,
    /** Carries out a plain command at once (see QuickCommand) and returns its fixed result, or null for the full flow. */
    private val quick: (suspend (String) -> String?)? = null,
) {
    private var phone = phone
    private val mutable = MutableStateFlow(TaskState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var apps = emptySet<String>()
    private var launchableApps = emptySet<String>()
    private var plan: Plan? = null
    private var chat: Boolean? = null
    private var previous: Previous? = null
    private val evidence = ArrayDeque<Screen>()
    private val sources = linkedSetOf<Source>()
    private var unchanged = 0
    private var failures = 0
    /** A vision reply whose action already went ahead, with the app it looked at, until its observations are kept. */
    private var trailing: Pair<String, Deferred<Result<Pair<String, ModelStep>>>>? = null
    /** How often each action ran on each exact page; a third run means the task is going round in a loop. */
    private val attempts = mutableMapOf<Pair<String, Action>, Int>()
    /** Handover requests the user answered with continue; the vision model may then do what each one asked permission for. */
    private val approvals = mutableListOf<String>()
    private var asking: String? = null
    private var remainingActiveMillis = TASK_TIMEOUT_MILLIS
    private var runStartedAtMillis = 0L

    fun start(goal: String, selectedApps: Set<String>) {
        require(goal.isNotBlank() && goal.length <= 4000) { "请填写 1–4000 字的任务" }
        require(selectedApps.isNotEmpty()) { "请选择任务允许访问的应用" }
        stopJob()
        apps = selectedApps.toSet()
        launchableApps = apps.filterTo(linkedSetOf(), phone::canLaunch)
        plan = null
        chat = null
        previous = null
        evidence.clear()
        sources.clear()
        unchanged = 0
        failures = 0
        attempts.clear()
        trailing = null
        approvals.clear()
        asking = null
        remainingActiveMillis = TASK_TIMEOUT_MILLIS
        mutable.value = TaskState(goal = goal.trim())
        trace("开始：${goal.trim()}（可用应用 ${apps.size} 个，${if (place == null) "${phone.mode}执行" else "执行方式待判断"}）")
        launchRun()
    }

    fun fail(reason: String) {
        if (state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) return
        stopJob()
        finish(TaskStatus.FAILED, reason)
    }

    /** Shows what the task is waiting on outside its own steps, such as the system's consent dialog. */
    fun notice(message: String) {
        if (state.value.status == TaskStatus.RUNNING) update(message)
    }

    fun stop() {
        if (state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) return
        stopJob()
        finish(TaskStatus.STOPPED, "已停止，不会再有新的操作")
    }

    /** Continuing after a handover grants what it asked for, so the vision model may then do it instead of asking again. */
    fun resume() {
        if (state.value.status != TaskStatus.PAUSED) return
        previous = null
        unchanged = 0
        failures = 0
        attempts.clear()
        asking?.let { approvals += it }
        trace(asking?.let { "继续任务：已同意「$it」，重新观察当前页面" } ?: "继续任务：重新观察当前页面")
        asking = null
        launchRun()
    }

    private fun pause(reason: String, ask: Boolean = false) {
        remainingActiveMillis = (remainingActiveMillis - (monotonicMillis() - runStartedAtMillis)).coerceAtLeast(0L)
        asking = if (ask) reason else null
        mutable.value = state.value.copy(status = TaskStatus.PAUSED, message = reason)
        trace("暂停：$reason")
    }

    /** A step that cannot go ahead is retried; only the third failure in a row pauses with [message]. Returns whether it paused. */
    private fun stuck(message: String): Boolean {
        trace("没能推进（连续第 ${++failures} 次）：${message.substringBefore("，请")}")
        if (failures < 3) return false
        pause(message)
        return true
    }

    private fun stopJob() {
        generation++
        job?.cancel()
        job = null
    }

    private fun launchRun() {
        generation++
        val token = generation
        val started = monotonicMillis()
        runStartedAtMillis = started
        mutable.value = state.value.copy(status = TaskStatus.RUNNING, message = "准备开始")
        job = scope.launch {
            try {
                withTimeout(remainingActiveMillis) {
                    if (plan == null) {
                        if (chat == null && quick != null) {
                            // A plain command needs no model: done the moment it is carried out, with a fixed result.
                            val done = try { timed("快速通道") { quick.invoke(state.value.goal) } }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { trace("快速通道没成功（${e.message}），改走完整流程"); null }
                            checkCurrent(token)
                            if (done != null) {
                                trace("快速通道：$done")
                                mutable.value = state.value.copy(result = done)
                                finish(TaskStatus.COMPLETED, "任务已完成")
                                return@withTimeout
                            }
                        }
                        val initialScreen = try {
                            phone.observe().takeIf { it.packageName in apps && !it.protected }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { trace("未读到初始页面：${e.message}"); null }
                        checkCurrent(token)
                        val initialImage = initialScreen?.let {
                            keep(it)
                            try { timed("初始截图") { phone.screenshot(it) } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { trace("初始截图不可用，使用页面文字：${e.message}"); null }
                        }
                        checkCurrent(token)
                        // Both models see the same page before anything can switch apps.
                        val planning: Deferred<Result<Plan>>? = if (chat != true) async {
                            try { Result.success(timed("规划") { withTimeout(60_000) {
                                models.plan(state.value.goal, apps, launchableApps, initialScreen, initialImage)
                            } }) }
                            catch (e: TimeoutCancellationException) { Result.failure<Plan>(e) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Result.failure<Plan>(e) }
                        } else null
                        if (chat == null) {
                            update("正在判断聊天还是任务")
                            val isChat = timed("意图判断") { models.isChat(state.value.goal, initialScreen) }
                            checkCurrent(token)
                            chat = isChat
                            trace("指令类型：${if (chat == true) "聊天" else "任务"}")
                        }
                        var early: Deferred<String?>? = null
                        val planned = if (chat == true) {
                            planning?.cancelAndJoin()
                            update("正在看当前屏幕、回复")
                            val screen = initialScreen ?: observe(token) ?: return@withTimeout
                            val image = initialImage ?: timed("截图") { phone.screenshot(screen) }
                            checkCurrent(token)
                            timed("聊天回复") { withTimeout(60_000) { models.reply(state.value.goal, screen, image) } }
                                .also { require(it.reply != null) { "聊天模型没有返回回复" } }
                        } else {
                            update(if (place == null) "正在理解任务、规划步骤" else "正在判断执行方式、规划步骤")
                            // An early open or virtual display would lose the page the user is referring to.
                            val placing = if (initialScreen == null) place?.let { choose -> async { timed("执行方式") { choose() } } } else null
                            if (initialScreen == null) early = async { openEarly(placing, planning!!, token) }
                            val result = planning!!.await().getOrThrow()
                            checkCurrent(token)
                            if (result.reply != null) { placing?.cancel(); early?.cancel() }
                            else if (result.app != initialScreen?.packageName) {
                                (placing?.await() ?: place?.let { timed("执行方式") { it() } })?.let { (chosen, how) ->
                                    phone = chosen; trace("执行方式：${chosen.mode}（$how）")
                                }
                            }
                            result
                        }
                        checkCurrent(token)
                        if (planned.memory.isNotEmpty()) trace("记忆：${remember(planned.memory)}")
                        if (planned.reply != null) {
                            trace("直接回复，不操作手机")
                            mutable.value = state.value.copy(result = planned.reply)
                            finish(TaskStatus.COMPLETED, "任务已完成")
                            return@withTimeout
                        }
                        trace("规划结果：${planned.app}；步骤：${planned.steps.joinToString(" / ")}；待输入 ${planned.texts.size} 段，网址 ${planned.urls.size} 个")
                        require(planned.app in apps) { "规划选择了未授权应用" }
                        plan = planned
                        mutable.value = state.value.copy(plan = planned.steps)
                        val guessed = early?.await()
                        checkCurrent(token)
                        if (planned.app == initialScreen?.packageName) {
                            trace("继续当前页面 ${planned.app}，不重新打开应用")
                        } else if (guessed == planned.app) {
                            trace("规划同为 ${planned.app}，已提前打开")
                        } else if (planned.app in launchableApps) {
                            val opened = phone.execute(Action.OpenApp(planned.app), null)
                            checkCurrent(token)
                            trace("打开 ${planned.app}：${if (opened) "成功" else "失败"}")
                            if (!opened) {
                                pause("无法打开目标应用 ${planned.app}，请手动进入后点击继续")
                                return@withTimeout
                            }
                        } else {
                            val current = phone.observe()
                            checkCurrent(token)
                            if (current.packageName != planned.app) {
                                pause("无法直接打开 ${planned.app}，请通过其他应用或手动进入该页面后点击继续")
                                return@withTimeout
                            }
                        }
                    }
                    var stale = 0
                    while (true) {
                        checkCurrent(token)
                        if (state.value.steps >= 30) {
                            finish(TaskStatus.FAILED, "已到 30 步上限，任务仍未完成")
                            return@withTimeout
                        }
                        try {
                            val screen = observe(token) ?: return@withTimeout
                            if (previous != null) {
                                unchanged = if (screen.fingerprint == previous?.screen?.fingerprint) unchanged + 1 else 0
                            }
                            trace("页面 ${screen.packageName}：${screen.elements.size} 个节点${if (screen.truncated) "（已截断）" else ""}" +
                                if (unchanged > 0) "，连续 $unchanged 次无变化" else "")
                            keep(screen)
                            update("正在决定下一步", logged = false)
                            // These cases always need vision, so there is no Jev result worth waiting for.
                            val recovery = when {
                                previous?.dispatched == false -> "上一步未能派发"
                                previous != null && unchanged >= 3 -> "连续 $unchanged 次页面无变化"
                                else -> null
                            }
                            // The screenshot and vision call start alongside Jev rather than after it: on pages it does not know
                            // Jev is rarely sure, and its 1–2 s "no" sat in front of every vision step. A sure Jev cancels them, at
                            // the price of a vision request on steps Jev could do alone. Failures wait for a step that needs the result.
                            val early = CompletableDeferred<Pair<String, ModelStep>>()
                            val look = async {
                                try {
                                    val shot = timed("截图") { phone.screenshot(screen) }
                                    Result.success(shot to timed("视觉模型") {
                                        withTimeout(60_000) { withContext(EarlyAction { early.complete(shot to it) }) {
                                            models.fallback(state.value.goal, screen, plan!!, previous, taskEvidence(), shot)
                                        } }
                                    })
                                } catch (e: TimeoutCancellationException) { Result.failure(e) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { Result.failure(e) }
                            }
                            val decision = if (recovery != null) null else try {
                                val candidates = Candidates.build(screen, plan!!, apps, launchableApps)
                                try {
                                    timed("Jev 决策（${candidates.size} 个候选）") {
                                        withTimeout(60_000) { models.decide(state.value.goal, screen, plan!!, candidates, previous) }
                                    }
                                } catch (e: java.io.IOException) {
                                    // Jev only speeds up element picks; when it is rate limited or down, the vision model takes the step.
                                    trace("Jev 不可用：${e.message?.lineSequence()?.first()}")
                                    Decision(null, false, Effect.UNKNOWN)
                                }
                            } catch (e: Throwable) { look.cancel(); throw e }
                            checkCurrent(token)
                            if (decision != null) trace("Jev：${decision.action?.description(screen) ?: "无动作"}，完成=${decision.done}（${"%.2f".format(decision.doneProbability)}），上一步效果=${decision.effect}")
                            var action = decision?.action
                            var done = decision?.done == true
                            var image: String? = null
                            var answer = ""
                            val fallbackReason = recovery ?: when {
                                action == null && !done -> "Jev 没有可信的下一步"
                                previous != null && decision?.effect == Effect.NO -> "上一步没有生效"
                                else -> null
                            }
                            // Joined so its cancellation is logged and the overlay is back before anything else happens.
                            if (fallbackReason == null) look.cancelAndJoin() else {
                                update("没有顺利推进，正在看截图调整做法")
                                trace("转视觉模型：$fallbackReason")
                                // Vision is looking at the page Jev was given. If that page had nothing to read or press and has
                                // changed since (an app still loading), read it again now rather than wait seconds for an answer
                                // about a blank page. On pages with content its answer can still stand (stillThere, targetKept).
                                if (!look.isCompleted && screen.elements.none { it.useful } && phone.observe().fingerprint != screen.fingerprint) {
                                    look.cancelAndJoin()
                                    throw ScreenChangedException()
                                }
                                // The action can arrive ahead of the observations still being written; those are kept once they are in.
                                val first = select { early.onAwait { it }; look.onAwait { it.getOrThrow() } }
                                val following = !look.isCompleted
                                if (following) trailing = screen.packageName to look
                                val (shot, fallback) = if (following) first else look.await().getOrNull() ?: first
                                image = shot
                                checkCurrent(token)
                                trace("视觉模型：${fallback.action?.description(screen) ?: "无动作"}，完成=${fallback.done}" +
                                    (fallback.handover?.let { "，交还：$it" } ?: "") +
                                    if (following) "，动作先到，观察随后补上" else "，观察 ${fallback.observations.length} 字")
                                if (fallback.observations.isNotBlank()) retain(Source(screen.packageName, fallback.observations, visual = true))
                                if (fallback.handover != null) {
                                    pause(fallback.handover, ask = true)
                                    return@withTimeout
                                }
                                action = fallback.action
                                done = fallback.done
                                answer = fallback.result
                            }
                            val fresh = observe(token) ?: return@withTimeout
                            checkCurrent(token)
                            // A done stays done while the same window keeps updating (a chat's status line, a list still loading):
                            // judging again costs another 4 s with Jev and 8 s or so with vision. Another app or window means it
                            // looked at a page now gone.
                            val stillThere = done && fresh.packageName == screen.packageName && fresh.windowId == screen.windowId
                            // Likewise an element action goes ahead while unrelated parts of the page change, if its own node is untouched.
                            val targetKept = !done && sameTarget(action, screen, fresh)
                            if (!stillThere && !targetKept && (fresh.fingerprint != screen.fingerprint ||
                                action?.gestureBounds() != null && (image == null || phone.screenshot(fresh) != image))) {
                                throw ScreenChangedException()
                            }
                            if (done) {
                                // A done that stood through an update summarizes the page as it is now.
                                keep(fresh)
                                // The vision model usually writes the answer along with its done, which saves a second call.
                                val result = if (answer.isNotBlank()) answer.also { trace("沿用视觉模型给出的结果，不再单独总结") } else {
                                    update("正在整理结果", logged = false)
                                    timed("总结") { withTimeout(60_000) { models.summarize(state.value.goal, taskEvidence()) } }
                                }
                                checkCurrent(token)
                                mutable.value = state.value.copy(result = result)
                                finish(TaskStatus.COMPLETED, "任务已完成")
                                return@withTimeout
                            }
                            if (action == null) {
                                if (stuck("无法确定下一步操作，请手动处理后点击继续")) return@withTimeout
                                continue
                            }
                            if (unchanged >= 6) {
                                pause("多次调整后屏幕仍没有变化，请手动处理后点击继续")
                                return@withTimeout
                            }
                            when (ActionPolicy.assess(action, fresh, apps)) {
                                Permission.REJECT -> {
                                    trace("本地检查拒绝：${action.description(fresh)}")
                                    if (stuck("此操作超出本次任务的允许范围，请手动处理后点击继续")) return@withTimeout
                                    continue
                                }
                                Permission.ALLOW -> {
                                    if (action is Action.OpenApp && action.packageName !in launchableApps) {
                                        if (stuck("无法直接打开 ${action.packageName}，请通过其他应用或手动进入该页面后点击继续")) return@withTimeout
                                        continue
                                    }
                                    // Waiting on the same page is what unchanged counts; scrolling or counting up changes the page.
                                    if (action != Action.Wait && (attempts.merge(fresh.fingerprint to action, 1, Int::plus) ?: 0) > 2) {
                                        pause("同一页面上的同一操作已做过 2 次仍没有进展，请手动处理后点击继续")
                                        return@withTimeout
                                    }
                                    perform(action, fresh, token, image)
                                }
                            }
                            stale = 0
                            failures = 0
                        } catch (e: PhoneUnavailableException) {
                            checkCurrent(token)
                            if (stuck("${e.message ?: "当前页面无法操作"}，请手动处理后点击继续")) return@withTimeout
                            delay(500)
                        } catch (_: ScreenChangedException) {
                            trace("决策期间页面变化（第 ${stale + 1} 次）")
                            if (++stale >= 6) {
                                pause("页面持续变化，无法可靠执行，请手动处理后点击继续")
                                return@withTimeout
                            }
                            update("屏幕有变化，正在重新查看")
                            delay(500)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (generation == token) {
                    trace("任务超时：累计运行 ${(TASK_TIMEOUT_MILLIS - remainingActiveMillis + monotonicMillis() - started) / 1000} 秒")
                    finish(TaskStatus.FAILED, "请求或任务超时")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneUnavailableException) {
                if (generation == token && state.value.status == TaskStatus.RUNNING) {
                    pause("${e.message ?: "当前页面无法操作"}，请手动处理后点击继续")
                }
            } catch (e: ScreenChangedException) {
                if (generation == token) pause("当前页面已变化，请点击继续重新读取")
            } catch (e: Exception) {
                if (generation == token) {
                    trace("异常：" + e.stackTraceToString())
                    finish(TaskStatus.FAILED, e.message?.take(1000) ?: "执行中断")
                }
            }
        }
    }

    private suspend fun observe(token: Long): Screen? {
        val screen = phone.observe()
        checkCurrent(token)
        if (screen.packageName !in apps) {
            val reason = if (screen.packageName == "com.google.android.gms") {
                "当前是 Google Play 服务页面（com.google.android.gms），请手动完成登录或选择账号，返回可用应用后点击继续"
            } else {
                "当前应用 ${screen.packageName} 不在助手可操作的范围内，请手动处理后点击继续"
            }
            pause(reason)
            return null
        }
        if (screen.protected) {
            pause("检测到密码输入框，请手动处理后点击继续")
            return null
        }
        return screen
    }

    private suspend fun taskEvidence(): Evidence {
        trailing?.let { (app, look) ->
            // Cleared only once its observations are in: a caller cancelled while waiting (a sure Jev cancels the next
            // vision call) leaves them for the next one.
            val observations = try { look.await().getOrNull()?.second?.observations } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive(); null
            }
            if (trailing?.second === look) {
                trailing = null
                if (!observations.isNullOrBlank()) retain(Source(app, observations, visual = true))
            }
        }
        return Evidence(evidence.toList(), sources.toList(), approvals.toList())
    }

    /** Adds a page to the evidence and its text to the task's sources, once per distinct page. */
    private fun keep(screen: Screen) {
        if (evidence.lastOrNull()?.fingerprint == screen.fingerprint) return
        screen.elements.forEach { node ->
            listOf(node.text, node.description).filter { it.isNotBlank() }.forEach { retain(Source(screen.packageName, it)) }
        }
        evidence.addLast(screen)
        while (evidence.size > 8) evidence.removeFirst()
    }

    private fun retain(source: Source) {
        if (source in sources) return
        check(sources.sumOf { it.text.length } + source.text.length <= 64_000) { "任务来源已达容量上限，请拆分任务；已有来源未被丢弃" }
        sources += source
    }

    private suspend fun perform(action: Action, screen: Screen, token: Long, image: String? = null, on: Phone = phone) {
        checkCurrent(token)
        val success = on.execute(action, screen, image)
        checkCurrent(token)
        previous = Previous(screen, action, success)
        mutable.value = state.value.copy(steps = state.value.steps + 1)
        update("第 ${state.value.steps} 步：${action.description(screen)}${if (success) "" else "（未生效，正在调整）"}")
    }

    /**
     * Opens Jev's guess at the task's app on the phone placement chose, then takes the clicks Jev is sure of until the plan
     * is back; typing waits for the plan's texts. Returns the package once it is open, else null.
     */
    private suspend fun openEarly(placing: Deferred<Pair<Phone, String>>?, planning: Deferred<Result<Plan>>, token: Long): String? {
        val app = try { timed("应用预判") { models.guessApp(state.value.goal, launchableApps) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        trace("Jev 预判应用：${app ?: "不确定，等规划"}")
        if (app == null) return null
        val target = placing?.await()?.first ?: phone
        // Opening an app on the virtual display asks for the user's consent. Asked before the plan was back, the dialog
        // stayed on screen when the plan then answered from memory (2026-10-15), so there the app opens after the plan.
        if (target !== phone) {
            trace("虚拟屏上等规划返回再打开 $app")
            return null
        }
        val opened = try { target.execute(Action.OpenApp(app), null) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        trace("打开 $app：${if (opened) "成功" else "失败"}（Jev 预判，规划同时进行）")
        if (!opened) return null
        val provisional = Plan(app, listOf(state.value.goal), emptyList())
        // Anything that goes wrong here only ends the head start; the main loop meets it again with the plan in hand.
        try {
            while (!planning.isCompleted) {
                val screen = target.observe()
                checkCurrent(token)
                if (screen.packageName != app) break
                val decision = timed("Jev 决策（规划还没返回）") {
                    models.decide(state.value.goal, screen, provisional, Candidates.build(screen, provisional, apps, launchableApps), previous)
                }
                checkCurrent(token)
                val click = decision.action as? Action.Click ?: break
                // A plan that came back meanwhile may name another app, where this click has no business.
                if (decision.done || planning.isCompleted && planning.await().getOrNull()?.app != app) break
                // Jev took a second or so, and a cold-starting app redraws meanwhile: a click against the page it saw would
                // not be sent, and the failure would hand the first planned step to the vision model.
                val fresh = target.observe()
                checkCurrent(token)
                if (!sameTarget(click, screen, fresh) || ActionPolicy.assess(click, fresh, apps) != Permission.ALLOW) break
                trace("规划还没返回，先做 Jev 有把握的点击")
                perform(click, fresh, token, on = target)
                if (previous?.dispatched == false) { previous = null; break }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { trace("规划前先走一步中止：${e.message}") }
        return app
    }

    private suspend fun checkCurrent(token: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != token || state.value.status != TaskStatus.RUNNING) throw CancellationException()
    }

    private fun update(message: String, logged: Boolean = true) {
        mutable.value = state.value.copy(message = message, log = if (logged) (state.value.log + message).takeLast(40) else state.value.log)
        if (logged) trace(message)
    }

    private fun finish(status: TaskStatus, message: String) {
        mutable.value = state.value.copy(status = status, message = message)
        trace("结束：${status.name}，$message")
    }

    // ponytail: oldest entries drop past 24,000 characters; keep the head too if long tasks ever lose their plan.
    private fun trace(entry: String) {
        val lines = state.value.trace + logLine(entry)
        var size = lines.sumOf { it.length }
        mutable.value = state.value.copy(trace = lines.dropWhile { line -> (size > 24_000).also { if (it) size -= line.length } })
    }

    /** Times a model or capture call; a timeout names the call that was still pending. */
    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        trace("→ $name")
        val token = generation
        val started = System.currentTimeMillis()
        fun elapsed() = "${System.currentTimeMillis() - started}ms"
        try {
            return block().also { trace("← $name ${elapsed()}") }
        } catch (e: TimeoutCancellationException) {
            trace("× $name 超时 ${elapsed()}"); throw e
        } catch (e: CancellationException) {
            // Only a call this run dropped itself, such as vision once Jev was sure; a stopped task has already logged its end.
            if (generation == token && state.value.status == TaskStatus.RUNNING) trace("× $name 取消 ${elapsed()}")
            throw e
        } catch (e: Exception) {
            trace("× $name 失败 ${elapsed()}"); throw e
        }
    }
}
