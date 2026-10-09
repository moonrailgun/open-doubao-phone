package com.openodoubao.assistant.voice

import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VoiceControls() {
    val context = LocalContext.current
    val state by VoiceRuntime.state.collectAsState()
    val error by VoiceRuntime.error.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地语音", style = MaterialTheme.typography.titleMedium)
            Text("识别在本机运行，麦克风按钮只填写输入框。播报需要安装豆馅本地语音引擎。")
            Text(state)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ VoiceRuntime.speak(context, "你好，我是豆馅。现在使用本地语音播报。") }, enabled = state == "空闲") { Text("测试播报") }
                OutlinedButton({ VoiceRuntime.cancel() }, enabled = state != "空闲") { Text("停止") }
            }
            TextButton({ VoiceRuntime.retryInitialization(context) }, enabled = state == "空闲") { Text("重试识别引擎") }
            TextButton({
                runCatching { context.startActivity(Intent().setComponent(ComponentName("com.openodoubao.tts", "com.openodoubao.tts.EngineActivity"))) }
            }) { Text("播报引擎设置") }
        }
    }
}
