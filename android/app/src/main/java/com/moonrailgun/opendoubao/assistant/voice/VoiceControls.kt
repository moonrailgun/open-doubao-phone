package com.moonrailgun.opendoubao.assistant.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun VoiceControls() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by VoiceRuntime.state.collectAsState()
    val error by VoiceRuntime.error.collectAsState()
    val enabled by VoiceRuntime.enabled.collectAsState()
    val prefs = remember { context.getSharedPreferences("voice", Context.MODE_PRIVATE) }
    var autoSend by remember { mutableStateOf(voiceAutoSend(context)) }
    fun isAssistant() = VoiceInteractionService.isActiveService(context, ComponentName(context, DouxianInteractionService::class.java))
    var selected by remember { mutableStateOf(isAssistant()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { selected = isAssistant(); VoiceRuntime.refresh(context) }
        }
        lifecycle.addObserver(observer)
        VoiceRuntime.refresh(context)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && isAssistant()) {
            VoiceRuntime.setEnabled(context, true)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("语音识别与唤醒", style = MaterialTheme.typography.titleMedium)
            Text("识别在本机运行。麦克风按钮把识别结果接在输入框后面，可以多次输入；唤醒后可直接下达任务。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("语音输入后自动发送")
                Switch(checked = autoSend, onCheckedChange = { autoSend = it; prefs.edit().putBoolean("auto_send", it).apply() })
            }
            Text("唤醒词：${wakePhrases.joinToString("、")}。说“停止任务”或“继续任务”可控制当前任务。")
            TextButton({ context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) {
                Text(if (selected) "已设为默认助手 · 查看设置" else "第一步：设为默认数字助手")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("首次解锁后持续监听")
                Switch(checked = enabled, enabled = selected || enabled, onCheckedChange = { value ->
                    if (!value) VoiceRuntime.setEnabled(context, false)
                    else permissions.launch(if (Build.VERSION.SDK_INT >= 33)
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.RECORD_AUDIO))
                })
            }
            Text("持续监听会增加耗电。锁屏时请先解锁，再重新下达指令；通话和麦克风隐私开启时暂停监听。")
            Text(state)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ VoiceRuntime.setEnabled(context, false); VoiceRuntime.cancel() }, enabled = state != "空闲" || enabled) { Text("停止语音") }
            }
            TextButton({ VoiceRuntime.retryInitialization(context) }, enabled = state == "空闲") { Text("重试识别引擎") }
            TextButton({
                runCatching { context.startActivity(Intent().setComponent(ComponentName("com.moonrailgun.opendoubao.tts", "com.moonrailgun.opendoubao.tts.EngineActivity"))) }
            }) { Text("播报引擎设置") }
        }
    }
}
