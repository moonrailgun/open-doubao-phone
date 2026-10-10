package com.moonrailgun.opendoubao.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
data class Saved(val config: ModelConfig = ModelConfig(), val apps: Set<String> = emptySet(),
    val records: List<Record> = emptyList(), val appsConfigured: Boolean = apps.isNotEmpty(),
    val remote: RemoteConfig = RemoteConfig(), val onlineTtsToken: String = "", val onlineTtsEndpoint: String = "",
    val onlineTtsModel: String = DEFAULT_TTS_MODEL, val onlineTtsVoice: String = DEFAULT_TTS_VOICE) {
    companion object {
        const val DEFAULT_TTS_MODEL = "elevenlabs/eleven_v3"
        const val DEFAULT_TTS_VOICE = "bhJUNIXWQQ94l8eI2VUf"
    }
}

/** Main-thread access; atomic replacement preserves the previous file if a write fails. */
class Vault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "assistant.enc"))
    private val alias = "open-odoubao-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): Saved {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Saved()
        try {
            val data = file.readFully()
            require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
            val cfg = json.getJSONObject("config")
            val apps = json.getJSONArray("apps")
            val records = json.getJSONArray("records")
            val remote = json.optJSONObject("remote")
            return Saved(ModelConfig(cfg.getString("jevEndpoint"), cfg.getString("jevModel"), cfg.getString("jevKey"),
                cfg.getString("chatEndpoint"), cfg.getString("chatModel"), cfg.getString("chatKey"), cfg.optString("language")),
                (0 until apps.length()).map { apps.getString(it) }.toSet(),
                (0 until records.length()).map { records.getJSONObject(it).let { r ->
                    val log = r.optJSONArray("log") ?: JSONArray()
                    Record(r.getString("id"), r.getLong("time"), r.getString("goal"), r.getString("status"), r.getString("result"),
                        (0 until log.length()).map { log.getString(it) }) } },
                json.optBoolean("appsConfigured", apps.length() > 0),
                RemoteConfig(remote?.optString("deviceId").orEmpty(), remote?.optJSONObject("binding")?.let(Pairing::fromJson),
                    remote?.optBoolean("enabled") ?: false), json.optString("onlineTtsToken"), json.optString("onlineTtsEndpoint"),
                json.optString("onlineTtsModel", Saved.DEFAULT_TTS_MODEL), json.optString("onlineTtsVoice", Saved.DEFAULT_TTS_VOICE))
        } catch (e: Exception) { throw IllegalStateException("本地加密记录无法读取，原文件已保留", e) }
    }

    fun write(saved: Saved) {
        val cfg = saved.config
        val json = JSONObject().put("config", JSONObject().put("jevEndpoint", cfg.jevEndpoint).put("jevModel", cfg.jevModel)
            .put("jevKey", cfg.jevKey).put("chatEndpoint", cfg.chatEndpoint).put("chatModel", cfg.chatModel).put("chatKey", cfg.chatKey)
            .put("language", cfg.language))
            .put("apps", JSONArray(saved.apps.toList())).put("appsConfigured", saved.appsConfigured)
            .put("onlineTtsToken", saved.onlineTtsToken)
            .put("onlineTtsEndpoint", saved.onlineTtsEndpoint)
            .put("onlineTtsModel", saved.onlineTtsModel).put("onlineTtsVoice", saved.onlineTtsVoice)
            .put("remote", JSONObject().put("deviceId", saved.remote.deviceId).put("enabled", saved.remote.enabled)
                .put("binding", saved.remote.binding?.json() ?: JSONObject.NULL))
            .put("records", JSONArray(saved.records.take(30).map { r -> JSONObject().put("id", r.id).put("time", r.time)
                .put("goal", r.goal).put("status", r.status).put("result", r.result.take(6000)).put("log", JSONArray(r.log.takeLast(400))) }))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw IllegalStateException("保存失败，原记录已保留", e) }
    }

    fun record(record: Record) {
        val saved = read()
        write(saved.copy(records = (listOf(record) + saved.records.filterNot { it.id == record.id }).take(30)))
    }
}
