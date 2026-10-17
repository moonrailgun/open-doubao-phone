package com.moonrailgun.opendoubao.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.moonrailgun.opendoubao.assistant.core.DefaultMemory
import com.moonrailgun.opendoubao.assistant.core.Memory
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import com.moonrailgun.opendoubao.assistant.remote.Pairing
import com.moonrailgun.opendoubao.assistant.remote.RemoteConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Record(val id: String, val time: Long, val goal: String, val status: String, val result: String,
    val log: List<String> = emptyList())
/** [excludedApps]: apps the assistant must not operate; every other installed app and component is allowed. */
data class Saved(val config: ModelConfig = ModelConfig(), val excludedApps: Set<String> = emptySet(),
    val records: List<Record> = emptyList(),
    val remote: RemoteConfig = RemoteConfig(), val onlineTtsToken: String = "", val onlineTtsEndpoint: String = "",
    val onlineTtsModel: String = DEFAULT_TTS_MODEL, val onlineTtsVoice: String = DEFAULT_TTS_VOICE,
    val memory: List<Memory> = emptyList(), val memorySeeded: Set<String> = emptySet()) {
    companion object {
        const val DEFAULT_TTS_MODEL = "elevenlabs/eleven_v3"
        const val DEFAULT_TTS_VOICE = "bhJUNIXWQQ94l8eI2VUf"
    }
}

/**
 * One copy of a value per process: the first read loads it, writes store it before anyone sees it, and [update] runs
 * read, change and store as one step, so a write from another thread cannot slip in between and be lost.
 */
internal class Guarded<T : Any> {
    @Volatile private var value: T? = null

    fun read(load: () -> T): T = value ?: synchronized(this) { value ?: load().also { value = it } }

    fun write(next: T, store: (T) -> T): T = synchronized(this) { store(next).also { value = it } }

    fun update(load: () -> T, store: (T) -> T, transform: (T) -> T): T = synchronized(this) { write(transform(read(load)), store) }
}

/**
 * Any thread; decrypts once per process, so reads are free and each change costs one encryption.
 * Atomic replacement preserves the previous file if a write fails.
 */
class Vault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "assistant.enc"))
    private val alias = "open-doubao-v1"
    val modelCalls = ModelCallLog(File(context.noBackupFilesDir, "model-calls"), ::key)

    private companion object {
        val cache = Guarded<Saved>()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): Saved = cache.read(::load)

    fun write(saved: Saved) { cache.write(saved, ::store) }

    /** Reads, changes and writes with no other write in between. */
    fun update(transform: (Saved) -> Saved): Saved = cache.update(::load, ::store, transform)

    fun record(record: Record) {
        update { saved -> saved.copy(records = (listOf(record) + saved.records.filterNot { it.id == record.id }).take(30)) }
    }

    /** Built-in memories join here, so a fresh install and an upgrade both get them; the next write persists them. */
    private fun load(): Saved = readFile().let { saved ->
        val (memory, seeded) = DefaultMemory.seed(saved.memory, saved.memorySeeded)
        saved.copy(memory = memory, memorySeeded = seeded)
    }

    private fun readFile(): Saved {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Saved()
        try {
            val data = file.readFully()
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
            val cfg = json.getJSONObject("config")
            // The allow list in "apps" (v1.50 and older) is dropped: everything is allowed until the user excludes an app.
            val excluded = json.optJSONArray("excludedApps") ?: JSONArray()
            val records = json.getJSONArray("records")
            val remote = json.optJSONObject("remote")
            val memory = json.optJSONArray("memory") ?: JSONArray()
            return Saved(ModelConfig(cfg.getString("jevEndpoint"), cfg.getString("jevModel"), cfg.getString("jevKey"),
                cfg.getString("chatEndpoint"), cfg.getString("chatModel"), cfg.getString("chatKey"), cfg.optString("language"), cfg.optBoolean("jevVision", false)),
                (0 until excluded.length()).map { excluded.getString(it) }.toSet(),
                (0 until records.length()).map { records.getJSONObject(it).let { r ->
                    val log = r.optJSONArray("log") ?: JSONArray()
                    Record(r.getString("id"), r.getLong("time"), r.getString("goal"), r.getString("status"), r.getString("result"),
                        (0 until log.length()).map { log.getString(it) }) } },
                RemoteConfig(remote?.optString("deviceId").orEmpty(), remote?.optJSONObject("binding")?.let(Pairing::fromJson),
                    remote?.optBoolean("enabled") ?: false), json.optString("onlineTtsToken"), json.optString("onlineTtsEndpoint"),
                json.optString("onlineTtsModel", Saved.DEFAULT_TTS_MODEL), json.optString("onlineTtsVoice", Saved.DEFAULT_TTS_VOICE),
                (0 until memory.length()).map { memory.getJSONObject(it).let { m -> Memory(m.getString("id"), m.getString("text"), m.getLong("updated")) } },
                json.optJSONArray("memorySeeded")?.let { seeded -> (0 until seeded.length()).map { seeded.getString(it) }.toSet() }.orEmpty())
        } catch (e: Exception) { throw IllegalStateException("本地加密记录无法读取，原文件已保留", e) }
    }

    /** Writes what a later [load] would return, and returns that, so the cache matches the file. */
    private fun store(next: Saved): Saved {
        val saved = next.copy(records = next.records.take(30).map { it.copy(result = it.result.take(6000), log = it.log.takeLast(400)) })
        val cfg = saved.config
        val json = JSONObject().put("config", JSONObject().put("jevEndpoint", cfg.jevEndpoint).put("jevModel", cfg.jevModel)
            .put("jevKey", cfg.jevKey).put("chatEndpoint", cfg.chatEndpoint).put("chatModel", cfg.chatModel).put("chatKey", cfg.chatKey)
            .put("language", cfg.language).put("jevVision", cfg.jevVision))
            .put("excludedApps", JSONArray(saved.excludedApps.toList()))
            // Older images require "apps"; an empty, unconfigured one lets a rollback start with all launchable apps.
            .put("apps", JSONArray()).put("appsConfigured", false)
            .put("onlineTtsToken", saved.onlineTtsToken)
            .put("onlineTtsEndpoint", saved.onlineTtsEndpoint)
            .put("onlineTtsModel", saved.onlineTtsModel).put("onlineTtsVoice", saved.onlineTtsVoice)
            .put("memory", JSONArray(saved.memory.map { it.json().put("updated", it.updated) }))
            .put("memorySeeded", JSONArray(saved.memorySeeded.toList()))
            .put("remote", JSONObject().put("deviceId", saved.remote.deviceId).put("enabled", saved.remote.enabled)
                .put("binding", saved.remote.binding?.json() ?: JSONObject.NULL))
            .put("records", JSONArray(saved.records.map { r -> JSONObject().put("id", r.id).put("time", r.time)
                .put("goal", r.goal).put("status", r.status).put("result", r.result).put("log", JSONArray(r.log)) }))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw IllegalStateException("保存失败，原记录已保留", e) }
        modelCalls.retain(saved.records.map { it.id }.toSet())
        return saved
    }
}
