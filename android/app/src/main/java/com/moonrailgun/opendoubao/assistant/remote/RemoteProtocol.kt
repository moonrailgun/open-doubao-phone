package com.moonrailgun.opendoubao.assistant.remote

import com.moonrailgun.opendoubao.assistant.core.TaskState
import com.moonrailgun.opendoubao.assistant.core.TaskStatus
import com.moonrailgun.opendoubao.assistant.data.Record
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.phone.InstalledApp
import com.moonrailgun.opendoubao.assistant.voice.TtsSettings
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class Pairing(val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun json(): JSONObject = JSONObject().put("host", host).put("port", port).put("fingerprint", fingerprint).put("token", token)

    companion object {
        fun parse(code: String): Pairing {
            require(code.length <= 2048) { "设备码过长" }
            val uri = try { URI(code.trim()) } catch (_: Exception) { throw IllegalArgumentException("设备码格式无效") }
            require(uri.scheme == "odoubao" && uri.rawAuthority == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null) { "请粘贴桌面端的完整设备码" }
            val values = mutableMapOf<String, String>()
            for (entry in uri.rawQuery.orEmpty().split('&')) {
                val part = entry.split('=', limit = 2)
                require(part.size == 2 && values.put(part[0], part[1]) == null) { "设备码字段无效或重复" }
            }
            require(values.keys == setOf("v", "host", "port", "fingerprint", "token") && values["v"] == "1") { "不支持的设备码版本或字段" }
            return validate(values.getValue("host"), values.getValue("port"), values.getValue("fingerprint"), values.getValue("token"))
        }

        fun fromJson(json: JSONObject): Pairing {
            json.exactFields("host", "port", "fingerprint", "token")
            val port = json.opt("port")
            require(port is Int || port is Long) { "端口无效" }
            return validate(json.string("host"), port.toString(), json.string("fingerprint"), json.string("token"))
        }

        private fun validate(host: String, portText: String, fingerprint: String, token: String): Pairing {
            val parts = host.split('.')
            require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "仅支持局域网 IPv4 地址" }
            val ip = parts.map(String::toInt)
            require(ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168 || ip[0] == 169 && ip[1] == 254) { "仅支持局域网 IPv4 地址" }
            require(portText.matches(Regex("[1-9][0-9]{0,4}")) && portText.toInt() <= 65535) { "端口无效" }
            require(listOf(fingerprint, token).all { it.matches(Regex("[0-9a-fA-F]{64}")) }) { "设备码指纹或凭证无效" }
            return Pairing(host, portText.toInt(), fingerprint.lowercase(), token.lowercase())
        }
    }
}

data class RemoteConfig(val deviceId: String = "", val binding: Pairing? = null, val enabled: Boolean = false)
data class RemoteSnapshot(val taskId: String? = null, val task: TaskState = TaskState()) {
    fun json(): JSONObject = JSONObject().put("taskId", taskId ?: JSONObject.NULL).put("status", task.status.name)
        .put("goal", task.goal.take(4000)).put("message", task.message.take(4000)).put("result", task.result.take(6000)).put("steps", task.steps)
        .put("log", JSONArray(task.trace))
}

object RemoteProtocol {
    const val MAX_FRAME_BYTES = 256 * 1024
    fun json(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES) { "消息超过 256 KiB" }
        return try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("消息必须是 JSON 对象") }
    }
    fun uuid(value: String): String {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "任务或请求 ID 无效" }
        return value
    }
}

internal fun JSONObject.string(key: String): String = (opt(key) as? String)
    ?: throw IllegalArgumentException("字段 $key 必须是字符串")
internal fun JSONObject.exactFields(vararg names: String) {
    require(keys().asSequence().toSet() == names.toSet()) { "消息字段不完整或包含未知字段" }
}

/** The apps a task may touch, as the phone's app picker shows them. */
data class AppAccess(val catalog: List<InstalledApp>, val selected: Set<String>)

interface RemoteTasks {
    fun snapshot(): RemoteSnapshot
    fun records(): List<Record>
    fun start(goal: String, taskId: String): String
    fun stop(taskId: String)
    fun resume(taskId: String)
    fun deleteRecord(taskId: String)
    fun config(): ModelConfig
    fun saveConfig(config: ModelConfig)
    suspend fun testConfig(config: ModelConfig)
    fun access(): AppAccess
    fun saveAccess(apps: Set<String>)
    fun tts(): TtsSettings
    fun saveTts(tts: TtsSettings)
}

class RemoteCommands(private val tasks: RemoteTasks) {
    private fun idle() = tasks.snapshot().task.status !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED)

    /** Shared by config.set and config.test: a null key keeps the saved one only for the same endpoint. */
    private fun remoteConfig(params: JSONObject): ModelConfig {
        params.exactFields("jevEndpoint", "jevModel", "jevKey", "chatEndpoint", "chatModel", "chatKey", "language")
        val old = tasks.config()
        fun field(name: String, limit: Int): String = params.string(name).also {
            require(it.length <= limit) { "字段 $name 过长" }
        }.trim()
        val jevEndpoint = field("jevEndpoint", 2048)
        val chatEndpoint = field("chatEndpoint", 2048)
        fun key(name: String, endpoint: String, previousEndpoint: String, previousKey: String): String {
            if (!params.isNull(name)) return field(name, 8192)
            // Never forward an existing secret to a remotely changed endpoint.
            require(endpoint == previousEndpoint) { "修改接口地址时请填写新的 API Key" }
            return previousKey
        }
        return ModelConfig(jevEndpoint, field("jevModel", 200), key("jevKey", jevEndpoint, old.jevEndpoint, old.jevKey),
            chatEndpoint, field("chatModel", 200), key("chatKey", chatEndpoint, old.chatEndpoint, old.chatKey), field("language", 64))
            .also { it.validate() }
    }

    suspend fun handle(text: String): JSONObject {
        val request = RemoteProtocol.json(text)
        request.exactFields("type", "id", "method", "params")
        require(request.string("type") == "request") { "不支持的消息类型" }
        val id = RemoteProtocol.uuid(request.string("id"))
        val response = JSONObject().put("type", "response").put("id", id)
        return try {
            val params = request.opt("params") as? JSONObject ?: throw IllegalArgumentException("params 必须是对象")
            val data: Any = when (request.string("method")) {
                "task.start" -> {
                    params.exactFields("goal")
                    val rawGoal = params.string("goal")
                    require(rawGoal.isNotBlank() && rawGoal.length <= 4000) { "请填写 1–4000 字的任务" }
                    val goal = rawGoal.trim()
                    val existing = tasks.records().firstOrNull { it.id == id }
                    if (existing != null) require(existing.goal == goal) { "请求 ID 已用于其他任务" }
                    else {
                        val status = tasks.snapshot().task.status
                        check(status != TaskStatus.RUNNING && status != TaskStatus.PAUSED) { "请先停止当前任务" }
                        check(tasks.start(goal, id) == id) { "任务 ID 不一致" }
                    }
                    JSONObject().put("taskId", id)
                }
                "task.stop" -> {
                    params.exactFields("taskId")
                    val taskId = RemoteProtocol.uuid(params.string("taskId"))
                    check(tasks.snapshot().taskId == taskId) { "该任务不是当前任务" }
                    tasks.stop(taskId)
                    JSONObject().put("taskId", taskId)
                }
                "task.resume" -> {
                    params.exactFields("taskId")
                    val taskId = RemoteProtocol.uuid(params.string("taskId"))
                    val current = tasks.snapshot()
                    check(current.taskId == taskId) { "该任务不是当前任务" }
                    check(current.task.status == TaskStatus.PAUSED) { "任务没有暂停，无需继续" }
                    tasks.resume(taskId)
                    JSONObject().put("taskId", taskId)
                }
                "records.list" -> {
                    params.exactFields()
                    JSONArray(tasks.records().take(30).map { it.json(preview = true) })
                }
                "records.get" -> {
                    params.exactFields("taskId")
                    val taskId = RemoteProtocol.uuid(params.string("taskId"))
                    (tasks.records().firstOrNull { it.id == taskId } ?: error("未找到任务记录")).json()
                }
                "records.delete" -> {
                    params.exactFields("taskId")
                    val taskId = RemoteProtocol.uuid(params.string("taskId"))
                    check(idle()) { "请先停止当前任务再删除记录" }
                    check(tasks.records().any { it.id == taskId }) { "未找到任务记录" }
                    tasks.deleteRecord(taskId)
                    JSONObject().put("taskId", taskId)
                }
                "status.get" -> { params.exactFields(); tasks.snapshot().json() }
                "config.get" -> { params.exactFields(); tasks.config().remoteJson() }
                "config.set" -> {
                    check(idle()) { "请先停止当前任务再修改模型配置" }
                    val config = remoteConfig(params)
                    tasks.saveConfig(config)
                    config.remoteJson()
                }
                "config.test" -> {
                    check(idle()) { "请先停止当前任务再测试连接" }
                    tasks.testConfig(remoteConfig(params))
                    JSONObject()
                }
                "apps.get" -> {
                    params.exactFields()
                    val access = tasks.access()
                    JSONObject().put("selected", JSONArray(access.selected.toList()))
                        .put("apps", JSONArray(access.catalog.map {
                            JSONObject().put("packageName", it.packageName).put("label", it.label.take(200)).put("launchable", it.launchable)
                        }))
                }
                "apps.set" -> {
                    params.exactFields("apps")
                    check(idle()) { "请先停止当前任务再修改应用范围" }
                    val list = params.opt("apps") as? JSONArray ?: throw IllegalArgumentException("apps 必须是数组")
                    val apps = (0 until list.length()).map { list.opt(it) as? String ?: throw IllegalArgumentException("应用包名无效") }.toSet()
                    require(apps.isNotEmpty()) { "请至少选择一个应用" }
                    val installed = tasks.access().catalog.map { it.packageName }.toSet()
                    require(installed.containsAll(apps)) { "包含手机上不存在的应用" }
                    tasks.saveAccess(apps)
                    JSONObject().put("selected", apps.size)
                }
                "tts.get" -> { params.exactFields(); tasks.tts().remoteJson() }
                "tts.set" -> {
                    params.exactFields("endpoint", "token", "model", "voice")
                    fun field(name: String, limit: Int) = params.string(name).also { require(it.length <= limit) { "字段 $name 过长" } }.trim()
                    val old = tasks.tts()
                    val endpoint = field("endpoint", 2048)
                    val token = when {
                        !params.isNull("token") -> field("token", 8192)
                        endpoint.isEmpty() -> "" // No endpoint means local playback.
                        else -> {
                            // Never forward an existing token to a remotely changed endpoint.
                            require(endpoint == old.endpoint || old.token.isEmpty()) { "修改接口地址时请填写新的 Token" }
                            old.token
                        }
                    }
                    val tts = TtsSettings(endpoint, token, field("model", 200), field("voice", 200)).validated()
                    tasks.saveTts(tts)
                    tts.remoteJson()
                }
                else -> error("不支持的远程方法")
            }
            response.put("ok", true).put("data", data)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            response.put("ok", false).put("error", e.message?.take(500) ?: "请求失败")
        }
    }
}

private fun ModelConfig.remoteJson(): JSONObject = JSONObject().put("jevEndpoint", jevEndpoint).put("jevModel", jevModel)
    .put("chatEndpoint", chatEndpoint).put("chatModel", chatModel).put("language", language)
    .put("hasJevKey", jevKey.isNotBlank()).put("hasChatKey", chatKey.isNotBlank())

private fun TtsSettings.remoteJson(): JSONObject = JSONObject().put("endpoint", endpoint).put("model", model)
    .put("voice", voice).put("hasToken", token.isNotEmpty())

private fun Record.json(preview: Boolean = false): JSONObject = JSONObject().put("id", id).put("time", time)
    .put("goal", if (preview) goal.take(240) else goal).put("status", status)
    .put("result", if (preview) result.take(500) else result).also { if (!preview) it.put("log", JSONArray(log)) }
