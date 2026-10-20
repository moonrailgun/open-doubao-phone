package com.moonrailgun.opendoubao.assistant.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun VirtualDisplaySetup() {
    val context = LocalContext.current
    val status by ShellBridge.status.collectAsState()
    val display by ShellBridge.displayId.collectAsState()
    var backend by remember { mutableStateOf(shellBackend(context)) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ShellBridge.initialize(context); ShellBridge.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("普通系统也能使用虚拟屏", style = MaterialTheme.typography.titleSmall)
        Text("无需刷机或 Root。需要 Android 14 及以上、无障碍权限，以及下面任一种连接；未连接时自动模式使用前台，已选虚拟屏的任务会提示连接失败。",
            style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 34) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BACKEND_SHIZUKU to "Shizuku（手机端）", BACKEND_ADB to "ADB + scrcpy").forEach { (value, title) ->
                    FilterChip(selected = backend == value, enabled = display == null,
                        onClick = { runCatching { chooseShellBackend(context, value); backend = value; error = ""; ShellBridge.refresh() }
                            .onFailure { error = it.message.orEmpty() } }, label = { Text(title) })
                }
            }
            if (backend == BACKEND_SHIZUKU) {
                Text("1. 安装 Shizuku，在其中按提示通过无线调试启动。\n2. 返回这里点「授权并连接」，允许 Open Doubao。\n3. 上方选择虚拟屏或自动模式。手机重启后需重新启动 Shizuku。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        }.onFailure { error = "无法打开，请访问 shizuku.rikka.app/download/" }
                    }) { Text("安装 / 打开 Shizuku") }
                    TextButton(onClick = { ShellBridge.refresh(true) }) { Text("授权并连接") }
                }
            } else if (backend == BACKEND_ADB) {
                Text("1. 电脑安装 Android Platform Tools 和 scrcpy；手机开启 USB 调试并允许电脑。\n2. 在电脑终端执行下面的命令，保持终端打开；出现「已连接」后选择虚拟屏开始任务。\n3. 任务创建屏幕后可运行下方 scrcpy 命令查看。应用更新、退出或手机重启后重新连接。",
                    style = MaterialTheme.typography.bodySmall)
                val command = "adb shell 'CLASSPATH=\$(pm path ${context.packageName} | cut -d: -f2) app_process / ${ShellDisplayService::class.java.name}'"
                SelectionContainer { Text(command, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB 虚拟屏", command))
                }) { Text("复制启动命令") }
                if (display != null) {
                    val mirror = "scrcpy --display-id=$display --no-audio --no-control"
                    SelectionContainer { Text(mirror, style = MaterialTheme.typography.bodySmall) }
                } else Text("scrcpy 查看命令会在虚拟屏任务启动后显示。", style = MaterialTheme.typography.bodySmall)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (backend.isNotEmpty()) TextButton(enabled = display == null, onClick = {
                runCatching { chooseShellBackend(context, ""); backend = "" }.onFailure { error = it.message.orEmpty() }
            }) { Text("断开连接") }
        }
        Text("虚拟屏共用手机里的应用和账号，不是另一台手机。部分应用会转移已有窗口；密码、受保护页面可能无法截图，需要转到前台处理。锁屏会停止任务。",
            style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
