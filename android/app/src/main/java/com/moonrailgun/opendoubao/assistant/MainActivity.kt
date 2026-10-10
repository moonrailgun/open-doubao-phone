package com.moonrailgun.opendoubao.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.moonrailgun.opendoubao.assistant.core.Markdown
import com.moonrailgun.opendoubao.assistant.core.TaskState
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.data.Record
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import com.moonrailgun.opendoubao.assistant.phone.catalogSelection
import com.moonrailgun.opendoubao.assistant.phone.loadAppCatalog
import com.moonrailgun.opendoubao.assistant.phone.visibleAppCatalog
import com.moonrailgun.opendoubao.assistant.remote.RemoteControls
import com.moonrailgun.opendoubao.assistant.voice.rememberVoiceInput
import com.moonrailgun.opendoubao.assistant.voice.voiceAutoSend
import com.moonrailgun.opendoubao.assistant.voice.VoiceControls
import com.moonrailgun.opendoubao.assistant.voice.TtsControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Console = Color(0xFF1C1C1E)
private val Accent = Color(0xFF4A9BEA)
private val StopRed = Color(0xFFD23F35)

private val LightColors = lightColorScheme(
    primary = Console, onPrimary = Color.White, primaryContainer = Color(0xFFE8E8EB), onPrimaryContainer = Console,
    secondary = Color(0xFF5A5A5F), onSecondary = Color.White, secondaryContainer = Color(0xFFE8E8EB), onSecondaryContainer = Console,
    tertiary = Console, tertiaryContainer = Console, onTertiaryContainer = Color.White,
    background = Color(0xFFF5F5F7), onBackground = Console, surface = Color(0xFFF5F5F7), onSurface = Console,
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF636366),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFB), surfaceContainer = Color(0xFFECECEF),
    surfaceContainerHigh = Color(0xFFE5E5E9), surfaceContainerHighest = Color(0xFFDEDEE2),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F4), onPrimary = Console, primaryContainer = Color(0xFF2C2C2E), onPrimaryContainer = Color(0xFFE5E5EA),
    secondary = Color(0xFFAEAEB2), onSecondary = Console, secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFFE5E5EA), tertiaryContainer = Color(0xFF3A3A3C), onTertiaryContainer = Color(0xFFF2F2F4),
    background = Color(0xFF0E0E10), onBackground = Color(0xFFE5E5EA), surface = Color(0xFF0E0E10), onSurface = Color(0xFFE5E5EA),
    surfaceVariant = Color(0xFF3A3A3C), onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF18181A), surfaceContainerLow = Color(0xFF151517), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF262628), surfaceContainerHighest = Color(0xFF303032),
    outline = Color(0xFF8E8E93), outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val Languages = listOf("" to "跟随系统", "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語", "ko" to "한국어")

private val Examples = listOf(
    Triple("iPhone 买哪个", "在浏览器分别查 iPhone 18 和 iPhone Duo 的价格、主要配置和评测口碑，对比后告诉我哪个更值得买，并说明理由", R.drawable.lucide_smartphone),
    Triple("设工作日闹钟", "在时钟里新建一个闹钟：周一到周五早上 7:30 响，周末不响，标签写「起床跑步」", R.drawable.lucide_alarm_clock),
    Triple("算利息并记录", "用计算器算 30000 元按年利率 2.5% 存 3 年的单利利息，再把算式和结果记到备忘录新建的「存款利息」里", R.drawable.lucide_calculator),
    Triple("手机体检", "打开系统设置，查看剩余存储空间、当前电量和 Android 版本，再把屏幕亮度调到一半左右，最后把这些信息汇总告诉我", R.drawable.lucide_heart_pulse),
)

class MainActivity : ComponentActivity() {
    private var refreshVersion by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); refreshVersion++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) { AssistantApp(refreshVersion) }
        }
    }

    @Composable private fun AssistantApp(refresh: Int) {
        val vault = remember { Vault(this) }
        val initial = remember { runCatching { vault.read() } }
        if (initial.isFailure) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("无法读取本地数据", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${initial.exceptionOrNull()?.message}。请先不要清除应用数据，排查存储问题后重新打开应用。",
                        fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return
        }
        val saved = initial.getOrThrow()
        val installed = remember { loadAppCatalog(this) }
        val allApps = remember { installed.map { it.packageName }.toSet() }
        val launcherApps = remember { installed.filter { it.launchable }.map { it.packageName }.toSet() }
        var config by remember { mutableStateOf(saved.config) }
        var apps by remember { mutableStateOf(catalogSelection(installed, saved.apps, saved.appsConfigured)) }
        var records by remember { mutableStateOf(saved.records) }
        var goal by remember { mutableStateOf("") }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var settingsTab by rememberSaveable { mutableIntStateOf(0) }
        var message by remember { mutableStateOf("") }
        var choosingApps by remember { mutableStateOf(false) }
        var testing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()
        LaunchedEffect(message) { if (message.isNotEmpty() && tab != 2) scroll.animateScrollTo(0) }
        LaunchedEffect(tab, settingsTab) { scroll.scrollTo(0) }
        val service by AssistantService.connected.collectAsState()
        val task = service?.state?.collectAsState()?.value ?: TaskState()
        val paused = task.status == TaskStatus.PAUSED
        val busy = task.status == TaskStatus.RUNNING || paused
        val configReady = remember(config) { runCatching { config.validate() }.isSuccess }
        val appsLabel = when {
            apps.isEmpty() -> "未选择应用"
            apps == allApps -> "全部 ${apps.size} 个应用可用"
            else -> "${apps.size} 个应用可用"
        }
        fun save() {
            config.validate()
            vault.write(vault.read().copy(config = config, apps = apps, appsConfigured = true))
        }
        fun attempt(action: () -> Unit) {
            try { action() } catch (e: Exception) { message = e.message ?: "操作失败" }
        }
        val remoteEditVersion by AssistantService.remoteEditVersion.collectAsState()
        LaunchedEffect(remoteEditVersion) { attempt {
            val fresh = vault.read()
            config = fresh.config
            apps = catalogSelection(installed, fresh.apps, fresh.appsConfigured)
            records = fresh.records
        } }
        LaunchedEffect(refresh, task.status) { attempt { records = vault.read().records } }
        DisposableEffect(tab) {
            if (tab == 2) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        fun start() = attempt {
            check(configReady) { "请先在设置中配置模型" }
            check(goal.isNotBlank()) { "请先写下要做的事" }
            if (apps.isEmpty()) {
                message = ""
                choosingApps = true
            } else {
                save()
                checkNotNull(service) { "请先开启无障碍服务" }.startTask(goal)
            }
        }
        // Dictation continues the goal; auto-send starts it as soon as recognition ends.
        val voice = rememberVoiceInput({ goal = (goal + it).take(4000); if (voiceAutoSend(this@MainActivity)) start() }, { message = it })
        val colors = MaterialTheme.colorScheme
        val notice = @Composable {
            if (message.isNotEmpty()) Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f).padding(vertical = 8.dp)) { Text(message, fontSize = 14.sp, lineHeight = 21.sp) }
                    IconButton({ message = "" }) { Icon(painterResource(R.drawable.lucide_x), "关闭") }
                }
            }
        }
        Scaffold(bottomBar = {
            NavigationBar {
                listOf(Triple("任务", R.drawable.lucide_bot, 0), Triple("记录", R.drawable.lucide_history, 1),
                    Triple("设置", R.drawable.lucide_settings, 2)).forEach { (title, glyph, index) ->
                    NavigationBarItem(tab == index, { tab = index }, icon = { Icon(painterResource(glyph), title) })
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab != 2) notice()
                when (tab) {
                    0 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.app_logo), null,
                                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
                            Text("Open Odoubao", Modifier.padding(start = 10.dp).weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Surface(color = colors.surfaceContainer, shape = CircleShape) {
                                Icon(painterResource(if (service != null) R.drawable.lucide_plug else R.drawable.lucide_unplug),
                                    if (service != null) "服务已连接" else "服务未开启", Modifier.padding(8.dp).size(18.dp),
                                    tint = if (service != null) Color(0xFF2FA672) else colors.outline)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (paused) "任务已暂停，等你处理" else if (busy) "正在替你操作手机" else "想让手机帮你做什么？", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                            Text(if (paused) "手动处理当前页面后，点击继续就能接着执行，也可以停止任务。"
                                else if (busy) "可以切到别的界面看它工作，点开悬浮球随时能紧急停止。"
                                else "说一句话，它会自己打开应用、点按和输入，做完把结果告诉你。",
                                fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant)
                        }
                        if (service == null) Banner("还差一步：开启无障碍服务", "手机需要它读取屏幕、替你点按和输入。只在你开始任务后工作。") {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        if (!configReady && !busy) Banner("还没有设置模型", "填好两个模型服务的地址和密钥后才能开始任务。") { tab = 2; settingsTab = 0 }
                        if (task.status != TaskStatus.IDLE) RunPanel(task, onResume = {
                            moveTaskToBack(true)
                            scope.launch { delay(300); attempt { service?.resume() } }
                        }, onStop = { service?.stop() })
                        if (!busy) {
                            Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
                                Column {
                                    TextField(goal, { goal = it.take(4000) }, Modifier.fillMaxWidth().padding(top = 4.dp),
                                        placeholder = { Text("例如：在浏览器里搜索杭州这周末的天气，告诉我适不适合出门") },
                                        textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, lineHeight = 26.sp), minLines = 4, maxLines = 8,
                                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalIconButton({ attempt {
                                            voice()
                                        } }) { Icon(painterResource(R.drawable.lucide_mic), "语音输入") }
                                        Spacer(Modifier.weight(1f))
                                        TextButton({ choosingApps = true }, Modifier.semantics { contentDescription = appsLabel },
                                            colors = if (apps.isEmpty()) ButtonDefaults.textButtonColors(contentColor = colors.error)
                                            else ButtonDefaults.textButtonColors()) {
                                            Icon(painterResource(R.drawable.lucide_layout_grid), null, Modifier.size(18.dp))
                                            Text("${apps.size}", Modifier.padding(start = 6.dp))
                                        }
                                        FilledIconButton({ start() }, Modifier.size(48.dp), enabled = service != null && configReady && goal.isNotBlank()) {
                                            Icon(painterResource(R.drawable.lucide_play), "开始任务")
                                        }
                                    }
                                }
                            }
                            if (apps.isNotEmpty() && apps != launcherApps) Text("可用：" + installed.filter { it.packageName in apps }.joinToString("、") { it.label },
                                Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (goal.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Examples.forEach { (title, text, glyph) ->
                                    SuggestionChip({ goal = text }, { Text(title) }, shape = CircleShape,
                                        icon = { Icon(painterResource(glyph), null, Modifier.size(18.dp)) })
                                }
                            }
                            Text("开始后自动执行，可随时点开悬浮球紧急停止。遇到付款、发送、删除、输入密码或凭据等需要手动处理的操作会暂停，处理后点击继续，但无法保证识别所有敏感操作。",
                                Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                        }
                    }
                    1 -> {
                        PageTitle("任务记录", "本机保留最近 30 条，含每一步的执行日志。开启远程接收后，已绑定桌面也可读取。点开一条可查看和复制日志。")
                        if (records.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.lucide_history), null, Modifier.size(40.dp), tint = colors.outline)
                            Text("还没有任务记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("开始的任务会按时间出现在这里。", fontSize = 14.sp, color = colors.onSurfaceVariant)
                            TextButton({ tab = 0 }) { Text("去写一个任务") }
                        } else Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(24.dp)) {
                            Column {
                                records.forEachIndexed { index, record ->
                                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
                                    val status = if (record.status in setOf("RUNNING", "PAUSED") && (!busy || task.goal != record.goal)) "INTERRUPTED" else record.status
                                    key(record.id) {
                                        RecordRow(record, status, enabled = !busy) { attempt {
                                            val fresh = vault.read()
                                            vault.write(fresh.copy(records = fresh.records.filterNot { it.id == record.id }))
                                            records = vault.read().records
                                        } }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        PageTitle("设置", "配置模型服务、语音播报和远程连接。")
                        PrimaryTabRow(settingsTab) {
                            listOf("模型", "语音", "桌面远程").forEachIndexed { index, title ->
                                Tab(settingsTab == index, { settingsTab = index }, text = { Text(title) })
                            }
                        }
                        when (settingsTab) {
                            0 -> {
                                Section("Jev 决策模型", "每一步由它挑选要点按的元素，并判断上一步是否生效。") {
                                    SettingField("接口地址", config.jevEndpoint) { config = config.copy(jevEndpoint = it.trim()) }
                                    SettingField("模型", config.jevModel) { config = config.copy(jevModel = it.trim()) }
                                    KeyField("API Key", config.jevKey) { config = config.copy(jevKey = it.trim()) }
                                }
                                Section("规划与视觉模型", "拆解任务、看截图和总结结果。需要支持图片输入的 Chat Completions 接口。") {
                                    SettingField("完整接口地址", config.chatEndpoint, "通常以 /v1/chat/completions 结尾") { config = config.copy(chatEndpoint = it.trim()) }
                                    SettingField("模型", config.chatModel) { config = config.copy(chatModel = it.trim()) }
                                    KeyField("API Key", config.chatKey) { config = config.copy(chatKey = it.trim()) }
                                }
                                Section("语言", "任务结束时的总结使用这个语言。") {
                                    LanguageField(config.language) { config = config.copy(language = it) }
                                }
                                notice()
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button({ attempt { save(); message = "已保存" } }, Modifier.weight(1f).height(52.dp), enabled = !busy && !testing,
                                        shape = RoundedCornerShape(16.dp)) {
                                        Icon(painterResource(R.drawable.lucide_save), null, Modifier.size(18.dp))
                                        Text("保存", Modifier.padding(start = 8.dp))
                                    }
                                    OutlinedButton({ attempt {
                                        config.validate()
                                        val client = CloudModels(config)
                                        message = ""
                                        testing = true
                                        scope.launch {
                                            try { client.checkConnection(); message = "连接正常：两个模型服务都有响应" }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { message = e.message ?: "连接失败" }
                                            finally { testing = false }
                                        }
                                    } }, Modifier.weight(1f).height(52.dp), enabled = !busy && !testing,
                                        shape = RoundedCornerShape(16.dp)) {
                                        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        else Icon(painterResource(R.drawable.lucide_plug_zap), null, Modifier.size(18.dp))
                                        Text("测试连接", Modifier.padding(start = 8.dp))
                                    }
                                }
                                Text("测试连接会向两个服务各发一次请求，可能产生少量费用。密钥经 Android Keystore 加密后只存在本机，不写入日志和备份。",
                                    Modifier.padding(horizontal = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                            }
                            1 -> {
                                TtsControls(vault)
                                VoiceControls()
                            }
                            else -> RemoteControls(service, refresh, vault) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (choosingApps) {
            var query by remember { mutableStateOf("") }
            var showSystemComponents by remember { mutableStateOf(false) }
            val visibleApps = visibleAppCatalog(installed, query, showSystemComponents)
            val visiblePackages = visibleApps.map { it.packageName }.toSet()
            AlertDialog(onDismissRequest = { choosingApps = false }, title = { Text("选择可用应用") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("任务只会在勾选的应用里操作。", fontSize = 14.sp, color = colors.onSurfaceVariant)
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索应用名称或包名") }, singleLine = true,
                            leadingIcon = { Icon(painterResource(R.drawable.lucide_search), null, Modifier.size(20.dp)) },
                            shape = RoundedCornerShape(14.dp))
                        Row(Modifier.fillMaxWidth().toggleable(showSystemComponents, role = Role.Checkbox) { showSystemComponents = it },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(showSystemComponents, null)
                            Text("显示系统组件", fontSize = 14.sp)
                        }
                        Text(if (query.isBlank()) "开启后可选择 Google Play 服务等无启动入口的组件。" else "搜索包含系统组件，勾选后才可操作。",
                            fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ apps = apps + visiblePackages }, enabled = visiblePackages.any { it !in apps }) {
                                Icon(painterResource(R.drawable.lucide_list_checks), "全选当前列表")
                            }
                            IconButton({ apps = apps - visiblePackages }, enabled = visiblePackages.any { it in apps }) {
                                Icon(painterResource(R.drawable.lucide_list_x), "取消当前列表选择")
                            }
                            Text("已选 ${apps.size} 个", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 13.sp,
                                color = if (apps.isEmpty()) colors.error else colors.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            if (visibleApps.isEmpty()) Text("没有匹配的应用或组件", Modifier.padding(12.dp), fontSize = 14.sp, color = colors.onSurfaceVariant)
                            visibleApps.forEach { app ->
                                val pkg = app.packageName
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .toggleable(pkg in apps, role = Role.Checkbox) { apps = if (it) apps + pkg else apps - pkg }
                                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(pkg in apps, null, Modifier.padding(horizontal = 8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, fontSize = 15.sp)
                                        Text(pkg, fontSize = 11.sp, color = colors.onSurfaceVariant)
                                        if (!app.launchable) Text("系统组件 · 无启动入口", fontSize = 11.sp, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }, confirmButton = { IconButton({ attempt {
                    vault.write(vault.read().copy(apps = apps, appsConfigured = true))
                    choosingApps = false
                } }) { Icon(painterResource(R.drawable.lucide_check), "完成") } })
        }
    }
}

@Composable private fun PageTitle(title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    Text(subtitle, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun Banner(title: String, body: String, onClick: () -> Unit) =
    Surface(onClick, color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(body, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 8.dp))
        }
    }

@Composable private fun Section(title: String, description: String, content: @Composable ColumnScope.() -> Unit) =
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }

/** The live view of the current task: dark so it reads as the one thing happening on the phone right now. */
@Composable private fun RunPanel(task: TaskState, onResume: () -> Unit, onStop: () -> Unit) {
    val running = task.status == TaskStatus.RUNNING
    val paused = task.status == TaskStatus.PAUSED
    val label = statusStyle(task.status.name).first
    // Earlier steps, newest first; the current one is already shown large above them.
    val trail = task.log.filterNot { it == task.message }.takeLast(if (running) 5 else 3).reversed()
    Surface(color = Console, contentColor = Color.White, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(task.status.name)
                Text(label, Modifier.padding(start = 8.dp).weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (task.steps > 0) Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${task.steps} 步" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.lucide_footprints), null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.7f))
                    Text("${task.steps}", Modifier.padding(start = 4.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Text(task.goal, fontSize = 15.sp, lineHeight = 22.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(task.message, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (task.result.isNotBlank()) Surface(color = Color.White.copy(alpha = 0.09f), shape = RoundedCornerShape(18.dp)) {
                SelectionContainer { Text(markdown(task.result), Modifier.fillMaxWidth().padding(16.dp), fontSize = 15.sp, lineHeight = 24.sp) }
            }
            if (trail.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trail.forEach { line ->
                    Row {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).background(Color.White.copy(alpha = 0.4f), CircleShape))
                        Text(line, Modifier.padding(start = 10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = Color.White.copy(alpha = 0.6f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (paused) Button(onResume, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2FA672), contentColor = Color.White)) {
                Icon(painterResource(R.drawable.lucide_play), null, Modifier.size(20.dp))
                Text("继续任务", Modifier.padding(start = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (running || paused) Button(onStop, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StopRed, contentColor = Color.White)) {
                Icon(painterResource(R.drawable.lucide_octagon_x), null, Modifier.size(20.dp))
                Text("紧急停止", Modifier.padding(start = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable private fun RecordRow(record: Record, status: String, enabled: Boolean, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val lines = if (expanded) Int.MAX_VALUE else 3
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(status, 16.dp)
            Text(SimpleDateFormat("M月d日 HH:mm", LocalConfiguration.current.locales[0]).format(Date(record.time)),
                Modifier.padding(start = 8.dp).weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded && record.log.isNotEmpty()) {
                val context = LocalContext.current
                IconButton({
                    val header = "任务：${record.goal}\n时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(record.time)) +
                        "\n状态：$status\n结果：${record.result}\n\n"
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("任务日志", header + record.log.joinToString("\n")))
                }) { Icon(painterResource(R.drawable.lucide_copy), "复制日志", Modifier.size(20.dp)) }
            }
            IconButton(delete, enabled = enabled) { Icon(painterResource(R.drawable.lucide_trash_2), "删除", Modifier.size(20.dp)) }
        }
        Text(record.goal, Modifier.padding(end = 12.dp), fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
            maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (record.result.isNotBlank()) Text(markdown(record.result), Modifier.padding(end = 12.dp), fontSize = 14.sp, lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (expanded && record.log.isNotEmpty()) Surface(Modifier.padding(top = 6.dp, end = 12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
            SelectionContainer {
                Text(record.log.joinToString("\n"), Modifier.fillMaxWidth().padding(12.dp), fontSize = 11.sp, lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun StatusIcon(status: String, size: Dp = 18.dp) {
    val (label, color, glyph) = statusStyle(status)
    val rotation = if (status == "RUNNING") rememberInfiniteTransition(label = "loading")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rotation").value else 0f
    Icon(painterResource(glyph), label, Modifier.size(size).graphicsLayer { rotationZ = rotation }, tint = color)
}

@Composable private fun SettingField(label: String, value: String, hint: String? = null, change: (String) -> Unit) =
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        supportingText = hint?.let { { Text(it) } })

@Composable private fun KeyField(label: String, value: String, change: (String) -> Unit) =
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LanguageField(value: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(Languages.firstOrNull { it.first == value }?.second ?: value, {}, Modifier.fillMaxWidth()
            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable), readOnly = true, label = { Text("总结语言") }, singleLine = true,
            shape = RoundedCornerShape(14.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            Languages.forEach { (tag, name) -> DropdownMenuItem({ Text(name) }, { change(tag); open = false }) }
        }
    }
}

/** The same Markdown subset the floating panel renders, as Compose styles. */
private fun markdown(source: String) = Markdown.parse(source).let { md ->
    AnnotatedString(md.text, md.spans.map { AnnotatedString.Range(when (it.style) {
        Markdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Markdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Markdown.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
        Markdown.Style.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.15.em)
    }, it.start, it.end) })
}

/** Shared with the floating panel so both show the same glyph and color for a status. */
internal fun statusStyle(status: String) = when (status) {
    "RUNNING" -> Triple("正在执行", Accent, R.drawable.lucide_loader_circle)
    "COMPLETED" -> Triple("已完成", Color(0xFF3DBB82), R.drawable.lucide_circle_check)
    "FAILED" -> Triple("未完成", Color(0xFFE5675C), R.drawable.lucide_circle_x)
    "STOPPED" -> Triple("已停止", Color(0xFF9A9AA0), R.drawable.lucide_circle_stop)
    "PAUSED" -> Triple("已暂停", Color(0xFFE6B45C), R.drawable.lucide_circle_pause)
    "INTERRUPTED" -> Triple("已中断", Color(0xFF9A9AA0), R.drawable.lucide_circle_pause)
    else -> Triple("等待任务", Color(0xFF9A9AA0), R.drawable.lucide_bot)
}
