package com.moonrailgun.opendoubao.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moonrailgun.opendoubao.assistant.data.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable internal fun CostControls(vault: Vault) {
    var report by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(vault) {
        while (true) {
            try {
                report = withContext(Dispatchers.IO) { vault.modelCosts.snapshot().toString() }
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "成本统计读取失败" }
            delay(3000)
        }
    }
    Section("成本统计", "按当前填写的单价估算人民币费用，修改单价会重新估算全部累计用量。仅统计启用此功能后的执行与陪伴模型请求，不含连接测试、语音识别和语音合成，以服务商账单为准。") {
        Text("按服务返回的用量累计；缺少用量或单价的调用不计入金额。失败、取消或中断的调用也可能收费，返回的用量可能不完整。")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        val data = remember(report) { report?.let(::JSONObject) }
        if (data == null) Text("正在读取统计…")
        else {
            if (data.optString("warning").isNotBlank()) Text(data.getString("warning"), color = MaterialTheme.colorScheme.error)
            if (data.getLong("since") > 0) Text("累计自 ${DateFormat.getDateTimeInstance().format(Date(data.getLong("since")))}")
            CostSummary("合计", data.getJSONObject("total"))
            HorizontalDivider()
            CostSummary("任务执行", data.getJSONObject("execution"))
            CostSummary("陪伴模式", data.getJSONObject("companion"))
        }
    }
    val models = remember(report) { report?.let { JSONObject(it).getJSONArray("models") } }
    if (models?.length() == 0) Text("还没有模型调用。执行任务或开启陪伴后，会在这里按模型显示用量并设置单价。")
    if (models != null) repeat(models.length()) { index ->
        val model = models.getJSONObject(index)
        key(model.getString("id")) {
            Section(model.getString("model"), model.getString("endpoint")) {
                CostSummary("模型合计", model.getJSONObject("total"))
                CostSummary("任务执行", model.getJSONObject("execution"))
                CostSummary("陪伴模式", model.getJSONObject("companion"))
                TextButton({ editing = model }) { Text(if (model.isNull("price")) "设置单价" else "修改单价") }
            }
        }
    }
    editing?.let { model ->
        key(model.getString("id")) { CostPriceDialog(model, vault, changed = { report = it }, close = { editing = null }) }
    }
}

@Composable private fun CostSummary(title: String, data: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$title · ${if (data.isNull("amount")) "暂无法估算" else "已计价估算 ¥${data.getString("amount")}"}", fontWeight = FontWeight.SemiBold)
        Text("${data.getLong("calls")} 次调用 · 输入 ${data.getLong("inputTokens")} / 输出 ${data.getLong("outputTokens")} Token（输入含缓存 ${data.getLong("cachedTokens")}）")
        if (data.getLong("unknownCalls") > 0 || data.getLong("unpricedCalls") > 0)
            Text("未计入金额：用量未知 ${data.getLong("unknownCalls")} 次 · 未设单价 ${data.getLong("unpricedCalls")} 次", color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun CostPriceDialog(model: JSONObject, vault: Vault, changed: (String) -> Unit, close: () -> Unit) {
    val price = model.optJSONObject("price")
    var input by remember { mutableStateOf(price?.getString("input").orEmpty()) }
    var output by remember { mutableStateOf(price?.getString("output").orEmpty()) }
    var cached by remember { mutableStateOf(price?.getString("cached").orEmpty()) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text("模型单价") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(model.getString("model"))
            Text("单位：元 / 百万 Token。不区分缓存价格时填相同输入单价；明确免费时填 0。修改后按新单价重新估算历史用量。")
            listOf(Triple("输入（非缓存）", input, { value: String -> input = value }),
                Triple("输出", output, { value: String -> output = value }),
                Triple("缓存输入", cached, { value: String -> cached = value })).forEach { (label, value, update) ->
                OutlinedTextField(value, update, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                    label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton({ scope.launch {
            saving = true
            try {
                val result = withContext(Dispatchers.IO) {
                    vault.modelCosts.setPrice(model.getString("id"), JSONObject().put("input", input.trim()).put("output", output.trim()).put("cached", cached.trim()))
                    vault.modelCosts.snapshot().toString()
                }
                changed(result); close()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "单价保存失败" }
            finally { saving = false }
        } }, enabled = !saving) { Text(if (saving) "保存中…" else "保存") }
    }, dismissButton = { TextButton(close, enabled = !saving) { Text("取消") } })
}
