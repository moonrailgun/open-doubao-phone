package com.openodoubao.assistant.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openodoubao.assistant.data.Saved
import com.openodoubao.assistant.data.Vault
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun TtsControls(vault: Vault) {
    val context = LocalContext.current
    val state by VoiceRuntime.state.collectAsState()
    val initial = remember(vault) { runCatching { vault.read() } }
    var endpoint by remember { mutableStateOf(initial.getOrNull()?.onlineTtsEndpoint.orEmpty()) }
    var token by remember { mutableStateOf(initial.getOrNull()?.onlineTtsToken.orEmpty()) }
    var model by remember { mutableStateOf(initial.getOrNull()?.onlineTtsModel ?: Saved.DEFAULT_TTS_MODEL) }
    var voice by remember { mutableStateOf(initial.getOrNull()?.onlineTtsVoice ?: Saved.DEFAULT_TTS_VOICE) }
    var status by remember { mutableStateOf(initial.exceptionOrNull()?.message.orEmpty()) }
    var failed by remember { mutableStateOf(initial.isFailure) }

    fun save(): Boolean = try {
        endpoint = endpoint.trim(); token = token.trim(); model = model.trim(); voice = voice.trim()
        if (endpoint.isNotEmpty()) {
            val url = endpoint.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) { "请输入不含凭据、查询参数或片段的 HTTPS 接口地址" }
        }
        require(token.isEmpty() || endpoint.isNotEmpty()) { "请填写在线播报接口地址" }
        require(model.isNotEmpty() && voice.isNotEmpty()) { "请填写在线播报模型和音色" }
        vault.write(vault.read().copy(onlineTtsEndpoint = endpoint, onlineTtsToken = token,
            onlineTtsModel = model, onlineTtsVoice = voice))
        status = if (token.isEmpty()) "已保存，使用本地播报" else "在线播报设置已保存"
        failed = false
        true
    } catch (e: Exception) {
        status = e.message ?: "保存失败"
        failed = true
        false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TTS 语音设置", style = MaterialTheme.typography.titleMedium)
            Text("默认使用 ElevenLabs v3。填写 Token 后启用在线播报；清空 Token 使用本地播报。在线请求出声前失败时会回退到本地。")
            OutlinedTextField(endpoint, { endpoint = it; status = "" }, label = { Text("在线播报接口地址") },
                supportingText = { Text("完整 HTTPS 地址，包含 /v1/audio/speech") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it; status = "" }, label = { Text("在线播报 Token") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(model, { model = it; status = "" }, label = { Text("播报模型") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(voice, { voice = it; status = "" }, label = { Text("音色 ID") },
                supportingText = { Text("填写服务支持的模型与音色标识") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("设置加密保存在本机。在线播报会将文本发送至所填接口，试听也可能产生费用。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ save() }) { Text("保存语音设置") }
                OutlinedButton({
                    if (save()) VoiceRuntime.speak(context, "你好，我是豆馅。很高兴为你服务。")
                }, enabled = state == "空闲" || state == "等待唤醒词") { Text("保存并试听") }
            }
            if (status.isNotBlank()) Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("当前语音状态：$state", style = MaterialTheme.typography.bodySmall)
        }
    }
}
