package com.moonrailgun.opendoubao.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.method.ScrollingMovementMethod
import android.os.SystemClock
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.moonrailgun.opendoubao.assistant.R
import com.moonrailgun.opendoubao.assistant.core.*
import com.moonrailgun.opendoubao.assistant.data.Record
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.remote.AppAccess
import com.moonrailgun.opendoubao.assistant.remote.RemoteProtocol
import com.moonrailgun.opendoubao.assistant.remote.RemoteReceiver
import com.moonrailgun.opendoubao.assistant.remote.RemoteSnapshot
import com.moonrailgun.opendoubao.assistant.remote.RemoteTasks
import com.moonrailgun.opendoubao.assistant.statusStyle
import com.moonrailgun.opendoubao.assistant.voice.LocalVoiceInput
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime
import com.moonrailgun.opendoubao.assistant.voice.TtsSettings
import com.moonrailgun.opendoubao.assistant.voice.tts
import com.moonrailgun.opendoubao.assistant.voice.withTts
import com.moonrailgun.opendoubao.assistant.voice.voiceAutoSend
import com.moonrailgun.opendoubao.assistant.voice.taskScreenAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private val VoiceRing = androidx.compose.ui.graphics.Color(0xFF3DBB82)

class AssistantService : AccessibilityService() {
    companion object {
        private val connection = MutableStateFlow<AssistantService?>(null)
        val connected = connection.asStateFlow()
        private val remoteEdits = MutableStateFlow(0)
        /** Bumped when the desktop changes saved settings or records, so open screens reload them. */
        val remoteEditVersion = remoteEdits.asStateFlow()
        init {
            // A crash mid-task would otherwise leave its record stuck at RUNNING with no reason.
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { connection.value?.recordCrash(error) }
                previous?.uncaughtException(thread, error)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(TaskState())
    val state = mutable.asStateFlow()
    private val remoteSnapshot = MutableStateFlow(RemoteSnapshot())
    var remote: RemoteReceiver? = null
        private set
    var remoteError: String = ""
        private set
    lateinit var phone: AndroidPhone
        private set
    private var controller: TaskController? = null
    private var active: Record? = null
    private var watcher: Job? = null
    private var overlay: FrameLayout? = null
    private var bubble: View? = null
    private var panel: View? = null
    private var statusIcon: ImageView? = null
    private var statusText: TextView? = null
    private var stopButton: View? = null
    private var resumeButton: View? = null
    private var handOverButton: View? = null
    private var peek: View? = null
    private var mirror: AutoCloseable? = null
    private var virtual: VirtualPhone? = null
    private var openButton: View? = null
    private var inputRow: View? = null
    private var input: EditText? = null
    private var voiceInput: LocalVoiceInput? = null
    private var voiceStatus: TextView? = null
    private var layout: WindowManager.LayoutParams? = null
    private var captureHidden = false
    private var locked = false
    private var running = false
    private var paused = false
    private var conversing = false
    private var expanded = false
    private var typing = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The overlay sits above the keyguard, so it stays hidden from screen off until the phone is unlocked.
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { locked = true; controller?.fail("手机已息屏") }
            else locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            applyOverlay()
        }
    }

    override fun onServiceConnected() {
        phone = AndroidPhone(this)
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        registerReceiver(lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        createOverlay()
        scope.launch { VoiceRuntime.conversing.collect { conversing = it; ringBubble() } }
        // Tasks never outlive the service, including those waiting for manual intervention.
        runCatching {
            val vault = Vault(this)
            val saved = vault.read()
            if (saved.records.any { it.status in setOf("RUNNING", "PAUSED") }) vault.write(saved.copy(records = saved.records.map {
                if (it.status !in setOf("RUNNING", "PAUSED")) it else it.copy(status = "INTERRUPTED", result = "任务进行中应用进程被终止",
                    log = it.log + logLine("服务重新连接时任务仍未结束：进程可能被系统回收、强制停止或崩溃"))
            }))
        }
        runCatching {
            val vault = Vault(this)
            remote = RemoteReceiver(scope, vault.read().remote, { config -> vault.write(vault.read().copy(remote = config)) },
                object : RemoteTasks {
                    override fun snapshot() = remoteSnapshot.value
                    override fun records() = vault.read().records
                    override fun config() = vault.read().config
                    override fun saveConfig(config: ModelConfig) {
                        check(state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) { "请先停止当前任务再修改模型配置" }
                        vault.write(vault.read().copy(config = config))
                        remoteEdits.value++
                    }
                    override suspend fun testConfig(config: ModelConfig) = CloudModels(config).checkConnection()
                    override fun access(): AppAccess {
                        val saved = vault.read()
                        val catalog = loadAppCatalog(this@AssistantService)
                        return AppAccess(catalog, catalogSelection(catalog, saved.apps, saved.appsConfigured))
                    }
                    override fun saveAccess(apps: Set<String>) {
                        check(state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) { "请先停止当前任务再修改应用范围" }
                        vault.write(vault.read().copy(apps = apps, appsConfigured = true))
                        remoteEdits.value++
                    }
                    override fun tts() = vault.read().tts()
                    override fun saveTts(tts: TtsSettings) {
                        vault.write(vault.read().withTts(tts))
                        remoteEdits.value++
                    }
                    override fun start(goal: String, taskId: String): String {
                        return startTask(goal, taskId)
                    }
                    override fun stop(taskId: String) {
                        check(remoteSnapshot.value.taskId == taskId) { "该任务不是当前任务" }
                        this@AssistantService.stop()
                    }
                    override fun resume(taskId: String) {
                        check(remoteSnapshot.value.taskId == taskId) { "该任务不是当前任务" }
                        this@AssistantService.resume()
                    }
                    override fun deleteRecord(taskId: String) {
                        check(state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) { "请先停止当前任务再删除记录" }
                        val saved = vault.read()
                        vault.write(saved.copy(records = saved.records.filterNot { it.id == taskId }))
                        remoteEdits.value++
                    }
                }, remoteSnapshot, android.os.Build.MODEL)
        }.onFailure { remoteError = it.message ?: "远程接收初始化失败" }
        connection.value = this
    }

    private fun recordCrash(error: Throwable) {
        val record = active ?: return
        if (state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) return
        Vault(this).record(record.copy(status = "FAILED", result = "应用崩溃：${error.javaClass.simpleName}",
            log = state.value.trace + logLine("崩溃：" + error.stackTraceToString())))
    }

    fun startTask(goal: String, taskId: String = UUID.randomUUID().toString()): String {
        requireUnlockedScreen()
        require(goal.isNotBlank() && goal.length <= 4000) { "请填写 1–4000 字的任务" }
        RemoteProtocol.uuid(taskId)
        check(state.value.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) { "请先停止当前任务" }
        val vault = Vault(this)
        val saved = vault.read()
        check(saved.records.none { it.id == taskId }) { "任务 ID 已存在" }
        val record = Record(taskId, System.currentTimeMillis(), goal.trim(), "RUNNING", "")
        // Same default as the app pickers: all launchable apps until a scope is saved.
        val apps = catalogSelection(loadAppCatalog(this), saved.apps, saved.appsConfigured)
        val models = try {
            check(apps.isNotEmpty()) { "请先选择本次任务允许访问的应用" }
            CloudModels(saved.config)
        } catch (e: Exception) {
            // A task rejected before it starts still leaves a record saying why.
            runCatching { vault.record(record.copy(status = "FAILED", result = e.message ?: "无法开始任务",
                log = listOf(logLine("开始：${goal.trim()}"), logLine("启动前检查未通过：${e.message}")))) }
            throw e
        }
        vault.record(record)
        active = record
        watcher?.cancel()
        virtual?.close()
        // Chosen per task: the setting may change between tasks, and other systems always run in the foreground.
        val offscreen = if (!foregroundChosen(this) && virtualDisplayAvailable(this)) VirtualPhone(this, phone) { controller?.fail(it) } else null
        virtual = offscreen
        val next = TaskController(scope, offscreen ?: phone, models)
        controller = next
        next.start(goal, apps)
        watcher = scope.launch {
            var persisted: String? = null
            next.state.collect { value ->
                if (value.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.STOPPED)) offscreen?.close()
                mutable.value = value
                remoteSnapshot.value = RemoteSnapshot(record.id, value)
                renderOverlay(value)
                if (value.status == TaskStatus.IDLE) return@collect
                // Saved once per step while running, then for every late line after the end, so a killed process keeps its log.
                val mark = "${value.status}:${if (value.status == TaskStatus.RUNNING) value.steps else value.trace.size}"
                if (mark == persisted) return@collect
                persisted = mark
                try { vault.record(record.copy(status = value.status.name,
                    result = value.result.ifBlank { if (value.status == TaskStatus.RUNNING) "" else value.message }, log = value.trace)) }
                catch (_: Exception) {
                    if (value.status in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) next.fail("本地记录保存失败")
                    else {
                        mutable.value = value.copy(message = value.message + "；记录保存失败")
                        remoteSnapshot.value = RemoteSnapshot(record.id, mutable.value)
                        renderOverlay(mutable.value)
                    }
                }
            }
        }
        return record.id
    }

    fun stop() = controller?.stop()

    fun resume() {
        requireUnlockedScreen()
        if (controller?.state?.value?.status != TaskStatus.PAUSED) return
        collapse()
        virtual?.resume()
        controller?.resume()
    }

    private fun requireUnlockedScreen() {
        check(taskScreenAvailable(getSystemService(PowerManager::class.java).isInteractive,
            getSystemService(KeyguardManager::class.java).isKeyguardLocked)) { "请先点亮并解锁手机" }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != packageName && ::phone.isInitialized) phone.lastEvent = SystemClock.uptimeMillis()
    }
    override fun onInterrupt() { controller?.fail("无障碍服务被系统中断") }
    override fun onUnbind(intent: Intent?): Boolean { disconnected(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnected(); super.onDestroy() }

    private fun disconnected() {
        controller?.fail("无障碍连接已断开")
        virtual?.close()
        remote?.close()
        scope.cancel()
        if (connection.value === this) connection.value = null
        runCatching { unregisterReceiver(lockReceiver) }
        voiceInput?.destroy(); voiceInput = null
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    fun setOverlayVisible(visible: Boolean) {
        captureHidden = !visible
        applyOverlay()
    }

    private fun createOverlay() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
        val ink = Color.rgb(28, 28, 30)
        val windows = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END; y = dp(70)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            }
        // Padding leaves room for the shadows; the window wraps its content.
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) collapse()
                return true
            }
        }.apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val ball = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            clipToOutline = true
            setOnClickListener { expanded = true; applyOverlay() }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var onRight = true
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val drag = View.OnTouchListener { view, event ->
            val screen = windows.currentWindowMetrics.bounds
            when (event.actionMasked) {
                // Keyboard taps also arrive as outside touches, so typing keeps the panel open.
                MotionEvent.ACTION_OUTSIDE -> {
                    if (expanded && !captureHidden && !typing) collapse()
                    return@OnTouchListener false
                }
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        // With END gravity x counts from the right edge, so moving right shrinks it.
                        params.x = (startX + (if (onRight) -dx else dx).toInt()).coerceIn(0, (screen.width() - root.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screen.height() - root.height).coerceAtLeast(0))
                        windows.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when {
                    dragging && view === ball -> {
                        // Snap to the nearer edge; the gravity follows so the panel always opens toward the screen.
                        val center = if (onRight) screen.width() - params.x - root.width / 2 else params.x + root.width / 2
                        onRight = center > screen.width() / 2
                        params.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
                        params.x = 0
                        windows.updateViewLayout(root, params)
                    }
                    !dragging && event.actionMasked == MotionEvent.ACTION_UP -> view.performClick()
                }
            }
            true
        }
        ball.setOnTouchListener(drag)
        root.setOnTouchListener(drag)
        root.addView(ball, FrameLayout.LayoutParams(dp(48), dp(48)))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = rounded(ink, 20)
            elevation = dp(8).toFloat()
            setOnTouchListener(drag)
        }
        fun iconButton(glyph: Int, description: String, click: () -> Unit) = ImageView(this).apply {
            setImageResource(glyph); imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = description
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { click() }
        }
        // Status glyph, then the message or summary, then collapse: the header carries no label of its own.
        statusIcon = ImageView(this).apply { setOnTouchListener(drag) }
        statusText = TextView(this).apply {
            setTextColor(Color.argb(210, 255, 255, 255)); textSize = 13f
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(8), dp(9), 0, dp(8))
            setOnTouchListener { view, event -> if (text.isEmpty()) drag.onTouch(view, event) else false }
        }
        card.addView(LinearLayout(this).apply {
            setOnTouchListener(drag)
            addView(statusIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(10) })
            addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            openButton = iconButton(R.drawable.lucide_external_link, "打开 Open Doubao") {
                collapse()
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            addView(openButton, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(iconButton(R.drawable.lucide_minimize_2, "收起") { collapse() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        })
        val field = EditText(this).apply {
            hint = "下一条命令"; textSize = 14f; maxLines = 4
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(140, 255, 255, 255))
            background = rounded(Color.argb(40, 255, 255, 255), 12)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // Single-line input type keeps the Go key; wrapping still shows long commands.
            inputType = EditorInfo.TYPE_CLASS_TEXT; setHorizontallyScrolling(false)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> submit(); true }
            // The window only takes focus while typing, so an auto-opened summary never steals the keyboard.
            // A first tap only focuses the field, so touch starts typing on down; the click covers TalkBack.
            fun startTyping() { if (!typing) { typing = true; applyOverlay() } }
            setOnClickListener { startTyping() }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) startTyping()
                false
            }
        }
        // Live view of the virtual display while the panel is open; the mirror exists only while it is on screen.
        val peekSurface = SurfaceView(this).apply {
            setZOrderOnTop(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (mirror == null) mirror = virtual?.peek(surfaceControl, width, height)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { mirror?.let { runCatching { it.close() } }; mirror = null }
            })
        }
        peek = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 12)
            // Visible only until the mirror draws over it.
            addView(TextView(this@AssistantService).apply {
                text = "正在连接虚拟屏画面"; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.argb(150, 255, 255, 255))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(peekSurface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        card.addView(peek, LinearLayout.LayoutParams(dp(166), dp(360)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        input = field
        // Status sits below the field: the hint disappears once the command has text.
        val status = TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255)); textSize = 12f
            setPadding(dp(10), dp(4), 0, 0); visibility = View.GONE
        }
        voiceStatus = status
        // Each dictation continues the command; it is sent right away only when auto-send is on.
        val voice = LocalVoiceInput(this, { field.append(it); if (voiceAutoSend(this)) submit() }, {
            status.text = it; status.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
        })
        voiceInput = voice
        inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@AssistantService).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnTouchListener(drag)
                addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(R.drawable.lucide_mic, "语音输入") { voice.start() },
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
                addView(iconButton(R.drawable.lucide_play, "执行") { submit() },
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            })
            addView(status)
        }
        card.addView(inputRow)
        handOverButton = Button(this).apply {
            text = "转到前台处理"; contentDescription = "把应用移到主屏幕手动处理"; textSize = 15f; isAllCaps = false
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.argb(50, 255, 255, 255), 14)
            stateListAnimator = null
            setOnClickListener {
                runCatching { virtual?.handOver() }.onSuccess { collapse() }.onFailure { statusText?.text = "无法转到前台：${it.message}" }
            }
        }
        card.addView(handOverButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })
        resumeButton = Button(this).apply {
            text = "继续"; contentDescription = "继续任务"; textSize = 15f; isAllCaps = false
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(47, 166, 114), 14)
            stateListAnimator = null
            setOnClickListener { runCatching { resume() }.onFailure { statusText?.text = it.message } }
        }
        card.addView(resumeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) })
        stopButton = Button(this).apply {
            // Emergency stop keeps its words next to the glyph; it is the one control that must never be guessed.
            val glyph = getDrawable(R.drawable.lucide_octagon_x)!!.mutate().apply { setTint(Color.WHITE); setBounds(0, 0, dp(18), dp(18)) }
            text = SpannableStringBuilder().append(" ", ImageSpan(glyph, ImageSpan.ALIGN_CENTER), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append("  紧急停止")
            contentDescription = "紧急停止"; textSize = 15f; isAllCaps = false
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(210, 63, 53), 14)
            stateListAnimator = null
            setOnClickListener { stop() }
        }
        card.addView(stopButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(card, FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT))

        windows.addView(root, params)
        overlay = root; bubble = ball; panel = card; layout = params
        renderOverlay(state.value)
    }

    private fun submit() {
        val goal = input?.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) return
        runCatching { startTask(goal) }
            .onSuccess { input?.text?.clear() }
            .onFailure { statusText?.text = it.message ?: "无法开始任务" }
    }

    private fun collapse() {
        expanded = false; typing = false
        applyOverlay()
    }

    private fun renderOverlay(value: TaskState) {
        val wasRunning = running
        running = value.status == TaskStatus.RUNNING
        paused = value.status == TaskStatus.PAUSED
        // Fold away while working; open for manual intervention or the final result.
        if (running != wasRunning || paused) { expanded = !running; typing = false }
        if (running) voiceStatus?.visibility = View.GONE
        val (label, color, glyph) = statusStyle(value.status.name)
        statusIcon?.apply {
            setImageResource(glyph); imageTintList = ColorStateList.valueOf(color.toArgb()); contentDescription = label
        }
        statusText?.apply {
            text = when (value.status) {
                TaskStatus.IDLE -> ""
                TaskStatus.COMPLETED -> value.result.takeIf { it.isNotBlank() }?.let { styled(Markdown.parse(it)) } ?: value.message
                else -> value.message
            }
            maxLines = if (running) 2 else 18
            ellipsize = if (running) TextUtils.TruncateAt.END else null
            scrollTo(0, 0)
        }
        ringBubble()
        applyOverlay()
    }

    /** The collapsed bubble's border shows a running or paused task, else a voice conversation. */
    private fun ringBubble() {
        val ball = bubble ?: return
        val color = when {
            running -> statusStyle(TaskStatus.RUNNING.name).second
            paused -> statusStyle(TaskStatus.PAUSED.name).second
            conversing -> VoiceRing
            else -> null
        }
        val density = resources.displayMetrics.density
        // No elevation: the small overlay window clips its shadow into a dark edge, so a hairline outlines the idle ball.
        ball.foreground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (color != null) setStroke((3 * density).toInt(), color.toArgb())
            else setStroke(maxOf(1, density.toInt()), Color.argb(40, 0, 0, 0))
        }
        ball.contentDescription = when {
            running -> "Open Doubao 正在操作手机，点按展开紧急停止"
            paused -> "Open Doubao 已暂停，点按展开继续任务"
            conversing -> "Open Doubao 正在语音对话，点按展开"
            else -> "Open Doubao，点按查看结果或下达命令"
        }
    }

    private fun styled(md: Markdown) = SpannableStringBuilder(md.text).apply {
        md.spans.forEach { (style, start, end) ->
            when (style) {
                Markdown.Style.BOLD -> listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(Color.WHITE))
                Markdown.Style.ITALIC -> listOf(StyleSpan(Typeface.ITALIC))
                Markdown.Style.CODE -> listOf(TypefaceSpan("monospace"))
                Markdown.Style.HEADING -> listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(Color.WHITE), RelativeSizeSpan(1.15f))
            }.forEach { setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun applyOverlay() {
        val root = overlay ?: return
        val params = layout ?: return
        root.visibility = if (captureHidden || locked) View.GONE else View.VISIBLE
        bubble?.visibility = if (expanded) View.GONE else View.VISIBLE
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
        stopButton?.visibility = if (running || paused) View.VISIBLE else View.GONE
        resumeButton?.visibility = if (paused) View.VISIBLE else View.GONE
        val offscreen = virtual?.session != null
        peek?.visibility = if (expanded && offscreen) View.VISIBLE else View.GONE
        handOverButton?.visibility = if (paused && offscreen) View.VISIBLE else View.GONE
        // Keep the current app visible while automatic actions are running.
        openButton?.visibility = if (running) View.GONE else View.VISIBLE
        inputRow?.visibility = if (running || paused) View.GONE else View.VISIBLE
        val focusable = typing && expanded && !running && !paused && !captureHidden && !locked
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags != params.flags) {
            if (!focusable) input?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
            params.flags = flags
            getSystemService(WindowManager::class.java).updateViewLayout(root, params)
        }
    }
}
