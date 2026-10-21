package com.moonrailgun.opendoubao.assistant.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moonrailgun.opendoubao.assistant.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppUpdates(visible: Boolean) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val systemBuild = BuildConfig.BUILD_TYPE == "system"
    val supportsApk = "arm64-v8a" in Build.SUPPORTED_ABIS
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<ReleaseUpdate?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    suspend fun check(manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) { showDialog = true; status = "正在检查更新…" }
        try {
            update = checkReleaseUpdate(version)
            status = if (update == null) "已是最新版本（$version）" else "发现新版本 ${update!!.version}"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { status = "检查失败：${e.message ?: "网络异常"}。请稍后重试。" }
        finally { checking = false }
    }

    LaunchedEffect(Unit) { if (!systemBuild) check(false) }
    if (visible) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于应用 · v$version", style = MaterialTheme.typography.titleMedium)
            if (systemBuild) Text("系统版请随系统镜像更新。", style = MaterialTheme.typography.bodySmall)
            else OutlinedButton(onClick = {
                if (update != null) showDialog = true else scope.launch { check(true) }
            }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text(if (checking) "正在检查更新…" else update?.let { "发现新版本 ${it.version}" } ?: "检查更新")
            }
        }
    }
    if (showDialog) {
        val available = update
        val download = available?.downloadUrl?.takeIf { supportsApk }
        AlertDialog(onDismissRequest = { showDialog = false }, title = { Text("应用更新") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                if (available != null) {
                    Text(available.notes)
                    Text(if (download == null) "暂未找到适用于本机的安装包。" else "下载后打开 APK 即可更新。", style = MaterialTheme.typography.bodySmall)
                    if (BuildConfig.DEBUG) Text("调试版与正式版签名不同，可能无法覆盖安装。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = {
            if (available != null) TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download ?: available.releaseUrl))) }
                catch (e: Exception) { status = "无法打开浏览器：${e.message}" }
            }) { Text(if (download != null) "下载安装包" else "查看 Release") }
            else TextButton(onClick = { showDialog = false }) { Text("知道了") }
        }, dismissButton = { if (available != null) TextButton(onClick = { showDialog = false }) { Text("稍后") } })
    }
}
