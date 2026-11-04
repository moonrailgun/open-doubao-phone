package com.moonrailgun.opendoubao.assistant.data

import com.moonrailgun.opendoubao.assistant.model.TokenUsage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent of task retention and settings writes; all Vault instances share the same write lock. */
class ModelCosts(private val file: File, private val key: () -> SecretKey) {
    internal fun markIncomplete() = synchronized(lock) {
        incompleteFiles.add(file.absolutePath)
    }

    fun start(mode: String, endpoint: String, model: String): (TokenUsage?) -> Unit {
        require(mode in modes)
        val uri = URI(endpoint)
        val address = "${uri.scheme}://${uri.host}${if (uri.port < 0) "" else ":${uri.port}"}${uri.rawPath.orEmpty()}"
        val id = MessageDigest.getInstance("SHA-256").digest("$address\n$model".toByteArray())
            .joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            val data = load()
            if (data.getLong("since") == 0L) data.put("since", System.currentTimeMillis())
            val models = data.getJSONObject("models")
            val row = models.optJSONObject(id) ?: JSONObject().put("id", id).put("endpoint", address).put("model", model)
                .put("execution", counts()).put("companion", counts()).also { models.put(id, it) }
            val count = row.getJSONObject(mode)
            count.put("calls", Math.addExact(count.getLong("calls"), 1))
            store(data)
        }
        var completed = false
        return { usage -> synchronized(lock) {
            if (!completed && usage != null) {
                val data = load()
                val count = data.getJSONObject("models").getJSONObject(id).getJSONObject(mode)
                mapOf("knownCalls" to 1L, "inputTokens" to usage.input, "outputTokens" to usage.output,
                    "cachedTokens" to usage.cached).forEach { (field, value) -> count.put(field, Math.addExact(count.getLong(field), value)) }
                store(data)
            }
            completed = true
        } }
    }

    fun setPrice(id: String, price: JSONObject) = synchronized(lock) {
        require(price.keys().asSequence().toSet() == setOf("input", "output", "cached")) { "请填写输入、输出和缓存输入单价" }
        val clean = JSONObject()
        for (field in listOf("input", "output", "cached")) {
            val value = price.opt(field)
            require(value is String && value.matches(Regex("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,8})?"))) { "单价须为非负数，最多 9 位整数和 8 位小数" }
            clean.put(field, value.toBigDecimal().stripTrailingZeros().toPlainString())
        }
        val data = load()
        val row = data.getJSONObject("models").optJSONObject(id) ?: throw IllegalArgumentException("模型统计不存在，请刷新")
        row.put("price", clean)
        store(data)
    }

    /** Amounts are CNY estimates using today's user-entered rates, including when rates are edited later. */
    fun snapshot(): JSONObject = synchronized(lock) {
        val data = load()
        val models = data.getJSONObject("models")
        val rows = models.keys().asSequence().map { models.getJSONObject(it) }.sortedBy { it.getString("endpoint") + "\n" + it.getString("model") }.toList()
        fun pairs(row: JSONObject, mode: String) = row.getJSONObject(mode) to row.optJSONObject("price")
        val result = JSONObject().put("since", data.getLong("since"))
        if (data.optBoolean("incomplete") || file.absolutePath in incompleteFiles)
            result.put("warning", "部分模型用量未能保存，累计费用可能偏低；任务执行和陪伴可继续使用。")
        modes.forEach { mode -> result.put(mode, summary(rows.map { pairs(it, mode) })) }
        result.put("total", summary(rows.flatMap { row -> modes.map { pairs(row, it) } }))
        result.put("models", JSONArray(rows.map { row ->
            JSONObject().put("id", row.getString("id")).put("endpoint", row.getString("endpoint")).put("model", row.getString("model"))
                .put("price", row.optJSONObject("price") ?: JSONObject.NULL)
                .put("execution", summary(listOf(pairs(row, "execution"))))
                .put("companion", summary(listOf(pairs(row, "companion"))))
                .put("total", summary(modes.map { pairs(row, it) }))
        }))
    }

    private fun summary(rows: List<Pair<JSONObject, JSONObject?>>): JSONObject {
        val result = counts().put("unknownCalls", 0L).put("unpricedCalls", 0L).put("pricedCalls", 0L)
        var amount = BigDecimal.ZERO
        fun add(field: String, value: Long) { result.put(field, Math.addExact(result.getLong(field), value)) }
        rows.forEach { (usage, price) ->
            fields.forEach { add(it, usage.getLong(it)) }
            val known = usage.getLong("knownCalls")
            add("unknownCalls", usage.getLong("calls") - known)
            if (price == null) add("unpricedCalls", known)
            else {
                add("pricedCalls", known)
                val cached = usage.getLong("cachedTokens")
                amount += ((usage.getLong("inputTokens") - cached).toBigDecimal() * price.getString("input").toBigDecimal() +
                    usage.getLong("outputTokens").toBigDecimal() * price.getString("output").toBigDecimal() +
                    cached.toBigDecimal() * price.getString("cached").toBigDecimal()).movePointLeft(6)
            }
        }
        return result.put("amount", if (result.getLong("calls") > 0 && result.getLong("pricedCalls") == 0L) JSONObject.NULL
            else amount.stripTrailingZeros().toPlainString())
    }

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject().put("version", 1).put("since", 0L).put("models", JSONObject())
        try {
            val data = file.readBytes()
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            }
            return JSONObject(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
        } catch (e: Exception) { throw IllegalStateException("成本统计无法读取，原文件已保留", e) }
    }

    private fun store(data: JSONObject) {
        if (file.absolutePath in incompleteFiles) data.put("incomplete", true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val pending = File(file.path + ".tmp")
        try {
            FileOutputStream(pending).use { it.write(cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            incompleteFiles.remove(file.absolutePath)
        } finally { pending.delete() }
    }

    private companion object {
        val lock = Any()
        // Keep failures visible across Vault instances until a successful write can persist them.
        val incompleteFiles = mutableSetOf<String>()
        val modes = listOf("execution", "companion")
        val fields = listOf("calls", "knownCalls", "inputTokens", "outputTokens", "cachedTokens")
        fun counts() = JSONObject().apply { fields.forEach { put(it, 0L) } }
    }
}
