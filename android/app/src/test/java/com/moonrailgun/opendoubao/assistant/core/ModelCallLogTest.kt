package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.data.ModelCallLog
import com.moonrailgun.opendoubao.assistant.data.Record
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.KeyGenerator

class ModelCallLogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val taskId = UUID.randomUUID().toString()
    private val callId = UUID.randomUUID().toString()
    private fun event(body: String, kind: String = "request") = JSONObject()
        .put("callId", callId).put("event", kind).put("body", body)
    private fun record() = Record(taskId, 123, "测试任务", "FAILED", "中断", listOf("执行日志"))

    @Test fun encryptedEventsSurviveReopeningAndExportFullBodiesWithTaskContext() {
        val root = temporary.newFolder()
        val logs = ModelCallLog(root) { secret }
        val body = "完整截图与提示词".repeat(10000)
        logs.create(taskId)
        logs.append(taskId, event(body))
        val persisted = File(root, taskId).listFiles()!!.single()
        assertFalse(persisted.readText().contains("完整截图"))
        val output = ByteArrayOutputStream()
        ModelCallLog(root) { secret }.export(record(), output)
        val entries = mutableMapOf<String, JSONObject>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
        assertEquals("测试任务", entries.getValue("task.json").getString("goal"))
        val request = entries.getValue("calls/$callId.request.json")
        assertEquals(body, request.getString("body"))
        assertEquals(taskId, request.getString("taskId"))
        assertEquals(2, entries.size) // A request without a response remains exportable after interruption.
    }

    @Test fun pruningKeepsCurrentTasksAndLateResponsesDoNotRecreateDeletedTasks() {
        val root = temporary.newFolder()
        val logs = ModelCallLog(root) { secret }
        val other = UUID.randomUUID().toString()
        logs.create(taskId); logs.create(other)
        logs.append(taskId, event("request"))
        logs.retain(setOf(taskId))
        assertFalse(File(root, other).exists())
        assertTrue(File(root, taskId).isDirectory)
        logs.retain(emptySet())
        logs.append(taskId, event("late", "response"))
        assertFalse(File(root, taskId).exists())
        assertTrue(runCatching { logs.export(record(), ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun corruptedCiphertextAndPathTraversalAreRejected() {
        val root = temporary.newFolder()
        val logs = ModelCallLog(root) { secret }
        assertTrue(runCatching { logs.create("../outside") }.isFailure)
        logs.create(taskId)
        assertTrue(runCatching { logs.append(taskId, event("x").put("callId", "../outside")) }.isFailure)
        logs.append(taskId, event("x"))
        val file = File(root, taskId).listFiles()!!.single()
        val bytes = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertTrue(runCatching { logs.export(record(), ByteArrayOutputStream()) }.isFailure)
        assertTrue(file.exists())
    }
}
