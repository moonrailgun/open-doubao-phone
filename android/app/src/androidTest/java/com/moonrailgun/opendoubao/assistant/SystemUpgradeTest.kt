package com.moonrailgun.opendoubao.assistant

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.data.Saved
import com.moonrailgun.opendoubao.assistant.data.Vault
import com.moonrailgun.opendoubao.assistant.model.ModelConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run prepare before a no-wipe system upgrade, then verify on the same device. */
class SystemUpgradeTest {
    @Test fun dataAndKeystoreSurviveSystemUpgrade() {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.noBackupFilesDir, "system-upgrade-check")
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        val sentinel = Saved(config = ModelConfig(chatKey = "system-upgrade-keystore-sentinel"))
        val baseline = File(folder, "baseline.json")
        fun digest(file: File): String = if (file.exists()) MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) } else "absent"
        fun snapshot() = JSONObject()
            .put("uid", context.applicationInfo.uid)
            .put("vault", digest(File(context.noBackupFilesDir, "assistant.enc")))
            .put("voice", JSONObject(context.getSharedPreferences("voice", 0).all))

        // Also exercise decryption of the actual configuration without logging its contents.
        Vault(context).read()
        if (phase == "prepare") {
            assertFalse("Refuse to overwrite an earlier baseline", baseline.exists())
            assertTrue(folder.mkdirs())
            Vault(isolated).write(sentinel)
            baseline.writeText(snapshot().toString())
        } else {
            val before = JSONObject(baseline.readText())
            val after = snapshot()
            assertEquals(before.getInt("uid"), after.getInt("uid"))
            assertEquals(before.getString("vault"), after.getString("vault"))
            val voice = before.getJSONObject("voice")
            val currentVoice = after.getJSONObject("voice")
            // Retrying initialization removes false guards; missing guards also default to false.
            listOf("initializing", "initializing_asr", "initializing_vad", "initializing_kws").forEach { key ->
                if (!voice.optBoolean(key, false)) voice.remove(key)
                if (!currentVoice.optBoolean(key, false)) currentVoice.remove(key)
            }
            assertEquals(voice.length(), currentVoice.length())
            voice.keys().forEach { assertEquals("Voice preference $it", voice.get(it), currentVoice.get(it)) }
            assertEquals(sentinel, Vault(isolated).read())
            assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_PERSISTENT != 0)
            assertEquals(1001L, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        }
    }
}
