package com.moonrailgun.opendoubao.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One durable file per event keeps an interrupted request readable without rewriting earlier screenshots. */
class ModelCallLog(private val root: File, private val key: () -> SecretKey) {
    private fun directory(taskId: String): File {
        require(UUID.fromString(taskId).toString() == taskId) { "任务 ID 无效" }
        return File(root, taskId)
    }

    fun create(taskId: String) = synchronized(lock) {
        val dir = directory(taskId)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建模型调试记录" }
    }

    fun append(taskId: String, event: JSONObject) = synchronized(lock) {
        val dir = directory(taskId)
        // A late memory/voice response must not resurrect a deleted or expired task.
        if (!dir.isDirectory) return@synchronized
        val callId = event.getString("callId")
        require(UUID.fromString(callId).toString() == callId)
        val kind = event.getString("event")
        require(kind == "request" || kind == "response")
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(JSONObject(event.toString()).put("taskId", taskId).toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val target = File(dir, "$callId.$kind.enc")
        val pending = File(dir, "$callId.$kind.tmp")
        try {
            FileOutputStream(pending).use { it.write(cipher.iv + cipher.doFinal(bytes)); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    fun retain(taskIds: Set<String>) = synchronized(lock) {
        root.listFiles()?.filter { it.name !in taskIds }?.forEach {
            check(it.deleteRecursively()) { "旧模型调试记录清理失败" }
        }
    }

    fun export(record: Record, output: OutputStream) = synchronized(lock) {
        val files = directory(record.id).listFiles()?.filter { it.extension == "enc" }?.sortedBy { it.name }.orEmpty()
        check(files.isNotEmpty()) { "该任务没有模型调试记录；启用此功能后执行的新任务才会记录" }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry("task.json", JSONObject().put("id", record.id).put("time", record.time).put("goal", record.goal)
                .put("status", record.status).put("result", record.result).put("log", JSONArray(record.log)).toString(2).toByteArray(Charsets.UTF_8))
            files.forEach { file ->
                val data = file.readBytes()
                require(data.size > 28) { "模型调试记录已损坏" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                }
                val plain = cipher.doFinal(data.copyOfRange(12, data.size))
                val json = GZIPInputStream(plain.inputStream()).use { it.readBytes() }
                entry("calls/${file.nameWithoutExtension}.json", json)
            }
        }
    }

    private companion object { val lock = Any() }
}
