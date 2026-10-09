package com.openodoubao.assistant.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openodoubao.assistant.data.Vault
import com.openodoubao.assistant.phone.AssistantService
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun RemoteControls(service: AssistantService?, refresh: Int, vault: Vault, openAccessibility: () -> Unit) {
    var saved by remember(refresh, service) { mutableStateOf(runCatching { vault.read().remote }.getOrDefault(RemoteConfig())) }
    val receiver = service?.remote
    val status = receiver?.state?.collectAsState()?.value ?: ReceiverStatus(saved.enabled, saved.binding != null, false,
        service?.remoteError?.takeIf { it.isNotEmpty() } ?: "无障碍服务未连接，无法接收远程任务",
        saved.binding?.let { "${it.host}:${it.port}" }.orEmpty())
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(status.bound) { if (status.bound) code = "" }
    fun attempt(action: () -> Unit) {
        try { action(); error = ""; saved = vault.read().remote }
        catch (e: Exception) { error = e.message ?: "操作失败" }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) attempt {
            checkNotNull(receiver) { "无障碍服务已断开，请开启后重新扫码" }.pair(contents)
        }
        else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            error = "未获得相机权限。请在系统设置中允许使用相机，或粘贴设备码绑定。"
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("桌面远程控制", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("手机和电脑连接同一局域网。绑定后，桌面可下发任务、紧急停止，以及读取任务结果和执行日志。请先保存模型、应用选择和数据使用同意。",
                fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status.message, fontSize = 14.sp, lineHeight = 21.sp,
                color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.address.isNotEmpty()) Text(status.address, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.bound) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许桌面下发任务", Modifier.weight(1f), fontSize = 15.sp)
                    Switch(status.enabled, { enabled -> attempt {
                        if (receiver != null) receiver.setEnabled(enabled)
                        else vault.write(vault.read().let { it.copy(remote = it.remote.copy(enabled = false)) })
                    } }, enabled = receiver != null || status.enabled)
                }
                OutlinedButton({ attempt {
                    if (receiver != null) receiver.forget()
                    else vault.write(vault.read().let { it.copy(remote = it.remote.copy(binding = null, enabled = false)) })
                } }) { Text("解除绑定") }
            } else {
                Button({ attempt {
                    scanner.launch(ScanOptions().setCaptureActivity(PairingCaptureActivity::class.java)
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描桌面端的绑定二维码")
                        .setBeepEnabled(false).setBarcodeImageEnabled(false)
                        .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false))
                } }, enabled = receiver != null && !status.enabled) { Text("扫码绑定") }
                OutlinedTextField(code, { code = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("桌面设备码") },
                    placeholder = { Text("粘贴 odoubao://pair…") }, singleLine = true,
                    enabled = receiver != null && !status.enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(14.dp))
                if (status.enabled) OutlinedButton({ attempt { receiver?.setEnabled(false) } }) { Text("取消连接") }
                else Button({ attempt { checkNotNull(receiver).pair(code) } }, enabled = receiver != null && code.isNotBlank()) { Text("绑定并开启接收") }
            }
            if (receiver == null) OutlinedButton(openAccessibility) { Text("开启无障碍服务") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Text("连接经过加密并校验桌面证书，二维码和设备码请勿转发。关闭接收或解除绑定会立即断开连接；正在执行的任务可在手机上停止。",
                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
