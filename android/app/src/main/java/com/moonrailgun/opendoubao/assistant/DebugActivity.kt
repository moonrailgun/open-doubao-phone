package com.moonrailgun.opendoubao.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonrailgun.opendoubao.assistant.core.Diagnostics
import com.moonrailgun.opendoubao.assistant.core.Placement
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.CloudModels
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import com.moonrailgun.opendoubao.assistant.phone.EXECUTION_FOREGROUND
import com.moonrailgun.opendoubao.assistant.phone.EXECUTION_VIRTUAL
import com.moonrailgun.opendoubao.assistant.phone.executionChoice
import com.moonrailgun.opendoubao.assistant.phone.virtualDisplayAvailable
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hands-on checks of the voice path, the placement decision and the runtime state; opened from the settings page. */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { DebugScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun DebugScreen() {
        val scope = rememberCoroutineScope()
        val colors = MaterialTheme.colorScheme
        val service by AssistantService.connected.collectAsState()
        val virtualActive = service?.virtualActive?.collectAsState()?.value == true
        val voiceState by VoiceRuntime.state.collectAsState()
        val voiceError by VoiceRuntime.error.collectAsState()
        val wakeEnabled by VoiceRuntime.enabled.collectAsState()
        val listening by VoiceRuntime.listening.collectAsState()
        val events by Diagnostics.events.collectAsState()
        var note by remember { mutableStateOf("") }
        fun attempt(block: () -> Unit) { note = ""; try { block() } catch (e: Exception) { note = e.message ?: "操作失败" } }
        fun playing(started: Boolean) = check(started) { "现在不能播报：屏幕未解锁，或语音正忙" }
        Scaffold(topBar = { TopAppBar(title = { Text("调试") }, navigationIcon = { TextButton({ finish() }) { Text("返回") } }) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (note.isNotEmpty()) Text(note, color = colors.error, fontSize = 14.sp)

                DebugSection("提示音播放", "走和唤醒相同的链路：前台服务、音频焦点、AudioTrack。结果和耗时记在下方事件里。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("wake-1", "wake-2", "wake-3", "retry").forEach { name ->
                            OutlinedButton({ attempt { playing(VoiceRuntime.playPrompt(this@DebugActivity, "voice/prompts/$name.wav")) } }) { Text(name) }
                        }
                    }
                    val audio = remember {
                        val manager = getSystemService(AudioManager::class.java)
                        val minimum = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        "24 kHz 最小缓冲 $minimum 字节（${minimum / 48} ms）；启动阈值可调：${if (Build.VERSION.SDK_INT >= 31) "是" else "否（API ${Build.VERSION.SDK_INT}）"}；" +
                            "媒体音量 ${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
                    }
                    Text(audio, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                }

                DebugSection("播报测试", "按设置用本地或在线合成播报这段文字。") {
                    var text by remember { mutableStateOf("你好，我是豆馅，现在是语音播报测试。") }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = 2)
                    Button({ attempt { playing(VoiceRuntime.speak(this@DebugActivity, text)) } }) { Text("播报") }
                }

                DebugSection("唤醒流程", "模拟唤醒＝播提示音后开卡片收听（跳过唤醒词检测）；直接收听＝助手手势同款；识别自检只显示文字，不执行任务。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ attempt { VoiceRuntime.respondToWake(this@DebugActivity) } }) { Text("模拟唤醒") }
                        Button({ attempt { VoiceRuntime.listenForTask(this@DebugActivity) } }) { Text("直接收听") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ attempt { VoiceRuntime.listenForTask(this@DebugActivity, dispatch = false) } }) { Text("识别自检") }
                        OutlinedButton({ VoiceRuntime.cancel() }) { Text("取消") }
                    }
                    Text("状态：$voiceState" + if (voiceError.isNotEmpty()) "；错误：$voiceError" else "", fontSize = 13.sp)
                    listening?.let { card ->
                        Text("卡片：${card.text.ifEmpty { card.hint }}", fontSize = 13.sp)
                        LinearProgressIndicator(progress = { card.level.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    }
                }

                DebugSection("执行方式判断", "配置了 Jev 时只问 Jev，3 秒内没答复或出错按前台；未配置 Jev 时问 Chat 模型。") {
                    var goal by remember { mutableStateOf("帮我查一下明天北京的天气") }
                    var verdict by remember { mutableStateOf("") }
                    OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    Button({ scope.launch {
                        verdict = "判断中…"
                        verdict = try {
                            val config = withContext(Dispatchers.IO) { Vault(this@DebugActivity).read().config }
                            val started = SystemClock.uptimeMillis()
                            val (placement, how) = CloudModels(config).placement(goal)
                            "${if (placement == Placement.VIRTUAL) "虚拟屏" else "前台"}（$how，${SystemClock.uptimeMillis() - started} ms）"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { "失败：${e.message}" }
                        Diagnostics.log("执行方式试算「$goal」→ $verdict")
                    } }) { Text("判断") }
                    if (verdict.isNotEmpty()) Text(verdict, fontSize = 13.sp)
                }

                DebugSection("运行状态", null) {
                    val choice = remember { executionChoice(this@DebugActivity) }
                    val assistant = remember { Settings.Secure.getString(contentResolver, "voice_interaction_service").orEmpty() }
                    listOf(
                        "系统版本" to Build.DISPLAY,
                        "虚拟屏可用" to if (virtualDisplayAvailable(this@DebugActivity)) "是" else "否",
                        "虚拟屏会话" to if (virtualActive) "进行中" else "无",
                        "执行方式设置" to when (choice) { EXECUTION_VIRTUAL -> "固定虚拟屏"; EXECUTION_FOREGROUND -> "固定前台"; else -> "自动判断" },
                        "无障碍服务" to if (service != null) "已连接" else "未连接",
                        "默认助手" to if (assistant.startsWith(packageName)) "是" else "否（$assistant）",
                        "麦克风权限" to if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权",
                        "唤醒监听" to if (wakeEnabled) "开启" else "关闭",
                    ).forEach { (name, value) -> Text("$name：$value", fontSize = 13.sp) }
                }

                DebugSection("最近事件", "本进程最近 ${Diagnostics.LIMIT} 条：唤醒命中、播报结果与耗时、收听与识别、字幕解码耗时、执行方式判断。") {
                    OutlinedButton({
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("events", events.joinToString("\n")))
                        note = "已复制 ${events.size} 条"
                    }) { Text("复制全部") }
                    events.asReversed().take(80).forEach { Text(it, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace) }
                    if (events.isEmpty()) Text("还没有事件。", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable private fun DebugSection(title: String, description: String?, content: @Composable ColumnScope.() -> Unit) =
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 16.sp)
            description?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
