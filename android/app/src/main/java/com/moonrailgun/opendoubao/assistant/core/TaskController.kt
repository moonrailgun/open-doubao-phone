package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Picks where the task runs once per task, before planning: the phone to use and how it was chosen, for the trace. */
    private val place: (suspend () -> Pair<Phone, String>)? = null,
) {
    private var phone = phone
    private val mutable = MutableStateFlow(TaskState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var apps = emptySet<String>()
    private var launchableApps = emptySet<String>()
    private var plan: Plan? = null
    private var previous: Previous? = null
    private val evidence = ArrayDeque<Screen>()
    private val sources = linkedSetOf<Source>()
    private var unchanged = 0
    private var remainingActiveMillis = TASK_TIMEOUT_MILLIS
    private var runStartedAtMillis = 0L

    fun start(goal: String, selectedApps: Set<String>) {
        require(goal.isNotBlank() && goal.length <= 4000) { "请填写 1–4000 字的任务" }
        require(selectedApps.isNotEmpty()) { "请选择任务允许访问的应用" }
        stopJob()
        apps = selectedApps.toSet()
        launchableApps = apps.filterTo(linkedSetOf(), phone::canLaunch)
        plan = null
        previous = null
        evidence.clear()
        sources.clear()
        unchanged = 0
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

    fun stop() {
        if (state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) return
        stopJob()
        finish(TaskStatus.STOPPED, "已紧急停止，不会再有新的操作")
    }

    fun resume() {
        if (state.value.status != TaskStatus.PAUSED) return
        previous = null
        unchanged = 0
        trace("继续任务：重新观察当前页面")
        launchRun()
    }

    private fun pause(reason: String) {
        remainingActiveMillis = (remainingActiveMillis - (monotonicMillis() - runStartedAtMillis)).coerceAtLeast(0L)
        mutable.value = state.value.copy(status = TaskStatus.PAUSED, message = reason)
        trace("暂停：$reason")
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
                        place?.let { choose ->
                            update("正在判断执行方式")
                            val (chosen, how) = timed("执行方式") { choose() }
                            checkCurrent(token)
                            phone = chosen
                            trace("执行方式：${chosen.mode}（$how）")
                        }
                        update("正在理解任务、规划步骤")
                        val planned = timed("规划") { withTimeout(60_000) { models.plan(state.value.goal, apps, launchableApps) } }
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
                        if (planned.app in launchableApps) {
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
                            if (evidence.lastOrNull()?.fingerprint != screen.fingerprint) {
                                screen.elements.forEach { node ->
                                    listOf(node.text, node.description).filter { it.isNotBlank() }.forEach {
                                        retain(Source(screen.packageName, it))
                                    }
                                }
                                evidence.addLast(screen)
                                while (evidence.size > 8) evidence.removeFirst()
                            }
                            update("正在决定下一步", logged = false)
                            val candidates = Candidates.build(screen, plan!!, apps, launchableApps)
                            val decision = try {
                                timed("Jev 决策（${candidates.size} 个候选）") {
                                    withTimeout(60_000) { models.decide(state.value.goal, screen, plan!!, candidates, previous) }
                                }
                            } catch (e: java.io.IOException) {
                                // Jev only speeds up element picks; when it is rate limited or down, the vision model takes the step.
                                trace("Jev 不可用：${e.message?.lineSequence()?.first()}")
                                Decision(null, false, Effect.UNKNOWN)
                            }
                            checkCurrent(token)
                            trace("Jev：${decision.action?.description(screen) ?: "无动作"}，完成=${decision.done}，上一步效果=${decision.effect}")
                            var action = decision.action
                            var done = decision.done
                            var image: String? = null
                            if (action == null && !done || previous != null &&
                                (decision.effect == Effect.NO || previous?.dispatched == false || unchanged >= 3)) {
                                update("没有顺利推进，正在看截图调整做法")
                                trace("转视觉模型：" + when {
                                    action == null && !done -> "Jev 没有可信的下一步"
                                    previous?.dispatched == false -> "上一步未能派发"
                                    decision.effect == Effect.NO -> "上一步没有生效"
                                    else -> "连续 $unchanged 次页面无变化"
                                })
                                image = timed("截图") { phone.screenshot(screen) }
                                val fallback = timed("视觉模型") {
                                    withTimeout(60_000) { models.fallback(state.value.goal, screen, plan!!, previous, taskEvidence(), image) }
                                }
                                checkCurrent(token)
                                trace("视觉模型：${fallback.action?.description(screen) ?: "无动作"}，完成=${fallback.done}" +
                                    (fallback.handover?.let { "，交还：$it" } ?: "") + "，观察 ${fallback.observations.length} 字")
                                if (fallback.observations.isNotBlank()) retain(Source(screen.packageName, fallback.observations, visual = true))
                                if (fallback.handover != null) {
                                    pause(fallback.handover)
                                    return@withTimeout
                                }
                                action = fallback.action
                                done = fallback.done
                            }
                            val fresh = observe(token) ?: return@withTimeout
                            checkCurrent(token)
                            if (fresh.fingerprint != screen.fingerprint ||
                                action?.gestureBounds() != null && (image == null || phone.screenshot(fresh) != image)) {
                                throw ScreenChangedException()
                            }
                            if (done) {
                                update("正在整理结果", logged = false)
                                val result = timed("总结") { withTimeout(60_000) { models.summarize(state.value.goal, taskEvidence()) } }
                                checkCurrent(token)
                                mutable.value = state.value.copy(result = result)
                                finish(TaskStatus.COMPLETED, "任务已完成")
                                return@withTimeout
                            }
                            if (action == null) {
                                pause("无法确定下一步操作，请手动处理后点击继续")
                                return@withTimeout
                            }
                            if (unchanged >= 6) {
                                pause("多次调整后屏幕仍没有变化，请手动处理后点击继续")
                                return@withTimeout
                            }
                            when (ActionPolicy.assess(action, fresh, apps)) {
                                Permission.REJECT -> {
                                    trace("本地检查拒绝：${action.description(fresh)}")
                                    pause("此操作超出本次任务的允许范围，请手动处理后点击继续")
                                    return@withTimeout
                                }
                                Permission.ALLOW -> {
                                    if (action is Action.OpenApp && action.packageName !in launchableApps) {
                                        pause("无法直接打开 ${action.packageName}，请通过其他应用或手动进入该页面后点击继续")
                                        return@withTimeout
                                    }
                                    perform(action, fresh, token, image)
                                }
                            }
                            stale = 0
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
                "当前应用 ${screen.packageName} 不在可用范围内，请手动处理并返回已选应用后点击继续"
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

    private fun taskEvidence() = Evidence(evidence.toList(), sources.toList())

    private fun retain(source: Source) {
        if (source in sources) return
        check(sources.sumOf { it.text.length } + source.text.length <= 64_000) { "任务来源已达容量上限，请拆分任务；已有来源未被丢弃" }
        sources += source
    }

    private suspend fun perform(action: Action, screen: Screen, token: Long, image: String? = null) {
        checkCurrent(token)
        val success = phone.execute(action, screen, image)
        checkCurrent(token)
        previous = Previous(screen, action, success)
        mutable.value = state.value.copy(steps = state.value.steps + 1)
        update("第 ${state.value.steps} 步：${action.description(screen)}${if (success) "" else "（未生效，正在调整）"}")
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
        val started = System.currentTimeMillis()
        fun elapsed() = "${System.currentTimeMillis() - started}ms"
        try {
            return block().also { trace("← $name ${elapsed()}") }
        } catch (e: TimeoutCancellationException) {
            trace("× $name 超时 ${elapsed()}"); throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace("× $name 失败 ${elapsed()}"); throw e
        }
    }
}
