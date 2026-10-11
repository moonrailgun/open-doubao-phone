package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.data.Record
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.phone.InstalledApp
import com.moonrailgun.opendoubao.assistant.remote.*
import com.moonrailgun.opendoubao.assistant.voice.TtsSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteProtocolTest {
    private val id = "ea6530be-cf90-4a2d-9628-b3cc0ebea6e2"
    private val otherId = "17f718cc-316d-4cbf-a84e-fe4af093f940"
    private val code = "odoubao://pair?v=1&host=192.168.1.9&port=9417&fingerprint=${"a".repeat(64)}&token=${"b".repeat(64)}"

    @Test fun pairingAcceptsOnlyLiteralPrivateIpv4AndExactCredentialFields() {
        assertEquals("192.168.1.9", Pairing.parse(code).host)
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "169.254.1.1").forEach {
            assertEquals(it, Pairing.parse(code.replace("192.168.1.9", it)).host)
        }
        listOf("8.8.8.8", "127.0.0.1", "172.32.0.1", "192.168.1.999", "192.168.001.9", "localhost", "[::1]").forEach {
            assertThrows(IllegalArgumentException::class.java) { Pairing.parse(code.replace("192.168.1.9", it)) }
        }
        listOf(code + "&host=10.0.0.1", code + "&extra=1", code.replace("v=1", "v=2"),
            code.replace("port=9417", "port=0"), code.replace("port=9417", "port=65536"),
            code.replace("b".repeat(64), "short"), code + "#fragment", code.replace("pair?", "pair/path?"),
            code.replace("192.168.1.9", "%31%39%32.168.1.9")).forEach {
            assertThrows(IllegalArgumentException::class.java) { Pairing.parse(it) }
        }
    }

    private class Phone : RemoteTasks {
        var current = RemoteSnapshot()
        val saved = mutableListOf<Record>()
        var starts = 0
        var stops = 0
        var config = ModelConfig(jevKey = "existing-jev-secret", chatEndpoint = "https://chat.example/v1", chatModel = "chat", chatKey = "existing-chat-secret")
        var configWrites = 0
        var failSave = false
        override fun config() = config
        override fun saveConfig(config: ModelConfig) {
            check(!failSave) { "保存失败" }
            this.config = config
            configWrites++
        }
        override fun snapshot() = current
        override fun records() = saved.toList()
        override fun start(goal: String, taskId: String): String {
            starts++
            current = RemoteSnapshot(taskId, TaskState(status = TaskStatus.RUNNING, goal = goal))
            saved += Record(taskId, 10, goal, "RUNNING", "")
            return taskId
        }
        override fun stop(taskId: String) { stops++; current = current.copy(task = current.task.copy(status = TaskStatus.STOPPED)) }
        var resumes = 0
        override fun resume(taskId: String) { resumes++; current = current.copy(task = current.task.copy(status = TaskStatus.RUNNING)) }
        override fun deleteRecord(taskId: String) { saved.removeAll { it.id == taskId } }
        val tested = mutableListOf<ModelConfig>()
        override suspend fun testConfig(config: ModelConfig) { tested += config }
        var access = AppAccess(listOf(InstalledApp("com.example.notes", "备忘录", true), InstalledApp("com.google.android.gms", "Google Play 服务", false)),
            setOf("com.example.notes"))
        override fun access() = access
        override fun saveAccess(apps: Set<String>) { access = access.copy(selected = apps) }
        var tts = TtsSettings("https://speech.example/v1/audio/speech", "existing-tts-token", "eleven", "voice-1")
        override fun tts() = tts
        override fun saveTts(tts: TtsSettings) { this.tts = tts }
        val exports = mutableListOf<Boolean>()
        val exportFile = java.io.File.createTempFile("calls", ".zip").apply { writeBytes(ByteArray(160 * 1024) { it.toByte() }); deleteOnExit() }
        override fun exportCalls(taskId: String, fresh: Boolean): java.io.File { exports += fresh; return exportFile }
    }

    @Test fun modelCallExportsStreamInChunksAndRebuildOnlyFromTheStart() {
        val phone = Phone().apply { saved += Record(id, 1, "goal", "COMPLETED", "") }
        val commands = RemoteCommands(phone)
        fun export(offset: Any) = commands.call(request("records.export", JSONObject().put("taskId", id).put("offset", offset)))
        val first = export(0).getJSONObject("data")
        assertEquals(160 * 1024L, first.getLong("size"))
        assertFalse(first.getBoolean("eof"))
        val chunk = java.util.Base64.getDecoder().decode(first.getString("data"))
        assertEquals(RemoteProtocol.EXPORT_CHUNK, chunk.size)
        assertEquals(5.toByte(), chunk[5])
        val last = export(RemoteProtocol.EXPORT_CHUNK).getJSONObject("data")
        assertTrue(last.getBoolean("eof"))
        assertEquals(10 * 1024, java.util.Base64.getDecoder().decode(last.getString("data")).size)
        assertEquals(listOf(true, false), phone.exports)
        assertFalse(export(200 * 1024).getBoolean("ok"))
        assertFalse(export(-1).getBoolean("ok"))
        assertFalse(commands.call(request("records.export", JSONObject().put("taskId", "11111111-1111-4111-8111-111111111111").put("offset", 0))).getBoolean("ok"))
    }

    // Commands only suspend for connection tests; the fakes return immediately.
    private fun RemoteCommands.call(text: String) = runBlocking { handle(text) }

    private fun request(method: String, params: JSONObject = JSONObject(), requestId: String = id) =
        JSONObject().put("type", "request").put("id", requestId).put("method", method).put("params", params).toString()

    @Test fun duplicateStartReturnsOriginalTaskAndNeverRunsTwice() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val message = request("task.start", JSONObject().put("goal", "read page"))
        assertEquals(id, commands.call(message).getJSONObject("data").getString("taskId"))
        assertTrue(commands.call(message).getBoolean("ok"))
        assertEquals(1, phone.starts)
        val changed = commands.call(request("task.start", JSONObject().put("goal", "different")))
        assertFalse(changed.getBoolean("ok"))
        assertEquals(1, phone.starts)
    }

    @Test fun stopForAnOldTaskCannotStopTheCurrentTask() {
        val phone = Phone().apply { current = RemoteSnapshot(id, TaskState(status = TaskStatus.RUNNING)) }
        val commands = RemoteCommands(phone)
        assertFalse(commands.call(request("task.stop", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertEquals(0, phone.stops)
        assertTrue(commands.call(request("task.stop", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.stops)
    }

    @Test fun pausedTaskRemainsCurrentAndCanBeStoppedButNotOverwritten() {
        val phone = Phone().apply { current = RemoteSnapshot(id, TaskState(status = TaskStatus.PAUSED)) }
        val commands = RemoteCommands(phone)
        val state = commands.call(request("status.get")).getJSONObject("data")
        assertEquals(id, state.getString("taskId"))
        assertEquals("PAUSED", state.getString("status"))
        val start = commands.call(request("task.start", JSONObject().put("goal", "new task"), otherId))
        assertFalse(start.getBoolean("ok"))
        assertEquals(0, phone.starts)
        assertEquals(TaskStatus.PAUSED, phone.current.task.status)
        assertFalse(commands.call(request("task.stop", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertEquals(0, phone.stops)
        assertTrue(commands.call(request("task.stop", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.stops)
        assertEquals(TaskStatus.STOPPED, phone.current.task.status)
    }

    @Test fun strictMethodTypesAndFrameBoundsRejectUntrustedCommands() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        listOf(request("settings.get"), request("task.start", JSONObject().put("goal", 42)),
            request("task.start", JSONObject().put("goal", " ")),
            request("task.start", JSONObject().put("goal", "x".repeat(4001))),
            request("task.start", JSONObject().put("goal", "hello").put("apps", "all")),
            request("status.get", JSONObject().put("extra", true))).forEach {
            assertFalse(it, commands.call(it).getBoolean("ok"))
        }
        listOf("{}", "[]", request("status.get", requestId = "not-a-uuid"),
            " ".repeat(RemoteProtocol.MAX_FRAME_BYTES + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { commands.call(it) }
        }
        assertEquals(0, phone.starts)
    }

    private fun configParams(config: ModelConfig) = JSONObject().put("jevEndpoint", config.jevEndpoint)
        .put("jevModel", config.jevModel).put("chatEndpoint", config.chatEndpoint).put("chatModel", config.chatModel)
        .put("language", config.language).put("jevKey", JSONObject.NULL).put("chatKey", JSONObject.NULL)

    @Test fun modelConfigReadsAndWritesNeverReturnKeysAndNullKeepsExistingKeys() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val read = commands.call(request("config.get"))
        assertTrue(read.toString(), read.getBoolean("ok"))
        val data = read.getJSONObject("data")
        assertTrue(data.getBoolean("hasJevKey"))
        assertTrue(data.getBoolean("hasChatKey"))
        assertFalse(data.has("jevKey"))
        assertFalse(data.has("chatKey"))
        assertFalse(read.toString().contains("secret"))
        val params = configParams(phone.config).put("chatModel", "new-model").put("language", "zh-CN")
        val saved = commands.call(request("config.set", params))
        assertTrue(saved.toString(), saved.getBoolean("ok"))
        assertFalse(saved.toString().contains("secret"))
        assertEquals("existing-chat-secret", phone.config.chatKey)
        assertEquals("new-model", phone.config.chatModel)
        assertEquals("zh-CN", phone.config.language)
        assertEquals(1, phone.configWrites)
        val replaced = commands.call(request("config.set", configParams(phone.config)
            .put("jevEndpoint", "https://other.example/v1").put("jevKey", "new-secret")))
        assertTrue(replaced.toString(), replaced.getBoolean("ok"))
        assertEquals("new-secret", phone.config.jevKey)
        assertFalse(replaced.toString().contains("secret"))
    }

    @Test fun invalidOrBusyConfigWritesLeaveSavedSettingsUntouched() {
        val phone = Phone()
        val before = phone.config
        val commands = RemoteCommands(phone)
        listOf(configParams(before).put("chatEndpoint", "https://elsewhere.example/v1"),
            configParams(before).put("jevKey", 3), configParams(before).put("jevKey", "bad key"),
            configParams(before).put("chatEndpoint", "http://insecure.example").put("chatKey", "new"),
            configParams(before).put("chatModel", ""), configParams(before).put("jevModel", "x".repeat(201)),
            configParams(before).put("language", "x".repeat(65)), configParams(before).put("apps", "all"),
            configParams(before).also { it.remove("jevKey") }).forEach {
            assertFalse(commands.call(request("config.set", it)).getBoolean("ok"))
        }
        for (status in listOf(TaskStatus.RUNNING, TaskStatus.PAUSED)) {
            phone.current = RemoteSnapshot(id, TaskState(status = status))
            assertFalse(commands.call(request("config.set", configParams(before))).getBoolean("ok"))
        }
        assertEquals(before, phone.config)
        assertEquals(0, phone.configWrites)
        phone.current = RemoteSnapshot()
        phone.failSave = true
        assertFalse(commands.call(request("config.set", configParams(before))).getBoolean("ok"))
        assertEquals(before, phone.config)
    }

    @Test fun resumeOnlyContinuesTheCurrentPausedTask() {
        val phone = Phone().apply { current = RemoteSnapshot(id, TaskState(status = TaskStatus.RUNNING)) }
        val commands = RemoteCommands(phone)
        assertFalse(commands.call(request("task.resume", JSONObject().put("taskId", id))).getBoolean("ok"))
        phone.current = RemoteSnapshot(id, TaskState(status = TaskStatus.PAUSED))
        assertFalse(commands.call(request("task.resume", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertEquals(0, phone.resumes)
        assertTrue(commands.call(request("task.resume", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.resumes)
        assertEquals(TaskStatus.RUNNING, phone.current.task.status)
    }

    @Test fun recordsAreDeletedOnlyWhenIdleAndPresent() {
        val phone = Phone().apply { saved += Record(id, 10, "done", "COMPLETED", "ok") }
        val commands = RemoteCommands(phone)
        phone.current = RemoteSnapshot(otherId, TaskState(status = TaskStatus.PAUSED))
        assertFalse(commands.call(request("records.delete", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.saved.size)
        phone.current = RemoteSnapshot()
        assertFalse(commands.call(request("records.delete", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertTrue(commands.call(request("records.delete", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertTrue(phone.saved.isEmpty())
    }

    @Test fun connectionTestUsesFormValuesButNeverSendsSavedKeysToANewEndpoint() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val ok = commands.call(request("config.test", configParams(phone.config).put("chatModel", "draft")))
        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals("draft", phone.tested.single().chatModel)
        assertEquals("existing-chat-secret", phone.tested.single().chatKey)
        assertFalse(commands.call(request("config.test", configParams(phone.config)
            .put("chatEndpoint", "https://attacker.example/v1"))).getBoolean("ok"))
        phone.current = RemoteSnapshot(id, TaskState(status = TaskStatus.RUNNING))
        assertFalse(commands.call(request("config.test", configParams(phone.config))).getBoolean("ok"))
        assertEquals(1, phone.tested.size)
        assertEquals(0, phone.configWrites)
    }

    @Test fun appScopeListsTheCatalogAndAcceptsOnlyInstalledNonEmptySelections() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val read = commands.call(request("apps.get")).getJSONObject("data")
        assertEquals(2, read.getJSONArray("apps").length())
        assertFalse(read.getJSONArray("apps").getJSONObject(1).getBoolean("launchable"))
        assertEquals("com.example.notes", read.getJSONArray("selected").getString(0))
        fun set(apps: List<Any>) = commands.call(request("apps.set",
            JSONObject().put("apps", org.json.JSONArray(apps)))).getBoolean("ok")
        assertFalse(set(emptyList()))
        assertFalse(set(listOf("com.not.installed")))
        assertFalse(set(listOf(42)))
        phone.current = RemoteSnapshot(id, TaskState(status = TaskStatus.PAUSED))
        assertFalse(set(listOf("com.google.android.gms")))
        assertEquals(setOf("com.example.notes"), phone.access.selected)
        phone.current = RemoteSnapshot()
        assertTrue(set(listOf("com.example.notes", "com.google.android.gms")))
        assertEquals(setOf("com.example.notes", "com.google.android.gms"), phone.access.selected)
    }

    @Test fun ttsSettingsNeverReturnTheTokenAndKeepItOnlyForTheSameEndpoint() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val read = commands.call(request("tts.get")).getJSONObject("data")
        assertEquals("https://speech.example/v1/audio/speech", read.getString("endpoint"))
        assertTrue(read.getBoolean("hasToken"))
        assertFalse(read.toString().contains("existing-tts-token"))
        fun set(endpoint: String, token: Any? = JSONObject.NULL, model: String = "eleven") = commands.call(request("tts.set",
            JSONObject().put("endpoint", endpoint).put("token", token).put("model", model).put("voice", " voice-2 ")))
        assertTrue(set("https://speech.example/v1/audio/speech").getBoolean("ok"))
        assertEquals(TtsSettings("https://speech.example/v1/audio/speech", "existing-tts-token", "eleven", "voice-2"), phone.tts)
        assertFalse(set("https://other.example/v1/audio/speech").getBoolean("ok"))
        assertFalse(set("http://speech.example/v1/audio/speech", "new").getBoolean("ok"))
        assertFalse(set("https://speech.example/v1/audio/speech", model = " ").getBoolean("ok"))
        assertEquals("existing-tts-token", phone.tts.token)
        assertTrue(set("https://other.example/v1/audio/speech", "new-token").getBoolean("ok"))
        assertEquals("new-token", phone.tts.token)
        // Clearing the endpoint switches to local playback and drops the token.
        assertFalse(set("").getJSONObject("data").getBoolean("hasToken"))
        assertEquals(TtsSettings("", "", "eleven", "voice-2"), phone.tts)
    }

    @Test fun historyListsAreBoundedWhileDetailAndLiveStateKeepDiagnostics() {
        val phone = Phone()
        repeat(30) { phone.saved += Record(if (it == 0) id else java.util.UUID.randomUUID().toString(), 10,
            "中".repeat(4000), "COMPLETED", "文".repeat(6000), listOf("12:00 read browser.app")) }
        val commands = RemoteCommands(phone)
        val list = commands.call(request("records.list"))
        assertTrue(list.toString().toByteArray().size <= RemoteProtocol.MAX_FRAME_BYTES)
        assertFalse(list.getJSONArray("data").getJSONObject(0).has("log"))
        val detail = commands.call(request("records.get", JSONObject().put("taskId", id))).getJSONObject("data")
        assertEquals(4000, detail.getString("goal").length)
        assertEquals(6000, detail.getString("result").length)
        assertEquals("12:00 read browser.app", detail.getJSONArray("log").getString(0))
        phone.current = RemoteSnapshot(id, TaskState(trace = listOf("diagnostic"), log = listOf("short UI log")))
        assertEquals("diagnostic", commands.call(request("status.get")).getJSONObject("data").getJSONArray("log").getString(0))
    }
}
