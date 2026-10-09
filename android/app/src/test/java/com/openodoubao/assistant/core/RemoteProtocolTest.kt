package com.openodoubao.assistant.core

import com.openodoubao.assistant.data.Record
import com.openodoubao.assistant.remote.*
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
        override fun snapshot() = current
        override fun records() = saved.toList()
        override fun start(goal: String, taskId: String): String {
            starts++
            current = RemoteSnapshot(taskId, TaskState(status = TaskStatus.RUNNING, goal = goal))
            saved += Record(taskId, 10, goal, "RUNNING", "")
            return taskId
        }
        override fun stop(taskId: String) { stops++; current = current.copy(task = current.task.copy(status = TaskStatus.STOPPED)) }
    }

    private fun request(method: String, params: JSONObject = JSONObject(), requestId: String = id) =
        JSONObject().put("type", "request").put("id", requestId).put("method", method).put("params", params).toString()

    @Test fun duplicateStartReturnsOriginalTaskAndNeverRunsTwice() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        val message = request("task.start", JSONObject().put("goal", "read page"))
        assertEquals(id, commands.handle(message).getJSONObject("data").getString("taskId"))
        assertTrue(commands.handle(message).getBoolean("ok"))
        assertEquals(1, phone.starts)
        val changed = commands.handle(request("task.start", JSONObject().put("goal", "different")))
        assertFalse(changed.getBoolean("ok"))
        assertEquals(1, phone.starts)
    }

    @Test fun stopForAnOldTaskCannotStopTheCurrentTask() {
        val phone = Phone().apply { current = RemoteSnapshot(id, TaskState(status = TaskStatus.RUNNING)) }
        val commands = RemoteCommands(phone)
        assertFalse(commands.handle(request("task.stop", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertEquals(0, phone.stops)
        assertTrue(commands.handle(request("task.stop", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.stops)
    }

    @Test fun pausedTaskRemainsCurrentAndCanBeStoppedButNotOverwritten() {
        val phone = Phone().apply { current = RemoteSnapshot(id, TaskState(status = TaskStatus.PAUSED)) }
        val commands = RemoteCommands(phone)
        val state = commands.handle(request("status.get")).getJSONObject("data")
        assertEquals(id, state.getString("taskId"))
        assertEquals("PAUSED", state.getString("status"))
        val start = commands.handle(request("task.start", JSONObject().put("goal", "new task"), otherId))
        assertFalse(start.getBoolean("ok"))
        assertEquals(0, phone.starts)
        assertEquals(TaskStatus.PAUSED, phone.current.task.status)
        assertFalse(commands.handle(request("task.stop", JSONObject().put("taskId", otherId))).getBoolean("ok"))
        assertEquals(0, phone.stops)
        assertTrue(commands.handle(request("task.stop", JSONObject().put("taskId", id))).getBoolean("ok"))
        assertEquals(1, phone.stops)
        assertEquals(TaskStatus.STOPPED, phone.current.task.status)
    }

    @Test fun strictMethodTypesAndFrameBoundsRejectUntrustedCommands() {
        val phone = Phone()
        val commands = RemoteCommands(phone)
        listOf(request("config.get"), request("task.start", JSONObject().put("goal", 42)),
            request("task.start", JSONObject().put("goal", " ")),
            request("task.start", JSONObject().put("goal", "x".repeat(4001))),
            request("task.start", JSONObject().put("goal", "hello").put("apps", "all")),
            request("status.get", JSONObject().put("extra", true))).forEach {
            assertFalse(it, commands.handle(it).getBoolean("ok"))
        }
        listOf("{}", "[]", request("status.get", requestId = "not-a-uuid"),
            " ".repeat(RemoteProtocol.MAX_FRAME_BYTES + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { commands.handle(it) }
        }
        assertEquals(0, phone.starts)
    }

    @Test fun historyListsAreBoundedWhileDetailAndLiveStateKeepDiagnostics() {
        val phone = Phone()
        repeat(30) { phone.saved += Record(if (it == 0) id else java.util.UUID.randomUUID().toString(), 10,
            "中".repeat(4000), "COMPLETED", "文".repeat(6000), listOf("12:00 read browser.app")) }
        val commands = RemoteCommands(phone)
        val list = commands.handle(request("records.list"))
        assertTrue(list.toString().toByteArray().size <= RemoteProtocol.MAX_FRAME_BYTES)
        assertFalse(list.getJSONArray("data").getJSONObject(0).has("log"))
        val detail = commands.handle(request("records.get", JSONObject().put("taskId", id))).getJSONObject("data")
        assertEquals(4000, detail.getString("goal").length)
        assertEquals(6000, detail.getString("result").length)
        assertEquals("12:00 read browser.app", detail.getJSONArray("log").getString(0))
        phone.current = RemoteSnapshot(id, TaskState(trace = listOf("diagnostic"), log = listOf("short UI log")))
        assertEquals("diagnostic", commands.handle(request("status.get")).getJSONObject("data").getJSONArray("log").getString(0))
    }
}
