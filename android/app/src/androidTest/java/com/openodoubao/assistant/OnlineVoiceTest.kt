package com.openodoubao.assistant

import android.app.KeyguardManager
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.openodoubao.assistant.data.Saved
import com.openodoubao.assistant.data.Vault
import com.openodoubao.assistant.voice.FileSpeech
import com.openodoubao.assistant.voice.OnlineSpeech
import com.openodoubao.assistant.voice.VoiceRuntime
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OnlineVoiceTest {
    @Test fun failedOnlineRequestFallsBackToLocalSynthesis() = withSpeech { context, start ->
        val attempted = AtomicBoolean()
        val client = OkHttpClient.Builder().addInterceptor {
            attempted.set(true)
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(401)
                .message("Unauthorized").body("private-server-error".toResponseBody()).build()
        }.build()
        val completed = CountDownLatch(1)
        var error: String? = "not completed"
        start(OnlineSpeech(client)) { error = it; completed.countDown() }
        await("No local WAV was created after the online failure") {
            context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("speech-") }
        }
        assertTrue(completed.await(30, TimeUnit.SECONDS))
        assertTrue(attempted.get())
        assertNull(error)
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("speech-") })
    }

    @Test fun interruptedAudioDoesNotRepeatUsingLocalSynthesis() = withSpeech { context, start ->
        val pcm = Buffer().write(ByteArray(8192))
        val client = OkHttpClient.Builder().addInterceptor {
            val body = object : ResponseBody() {
                override fun contentType() = "audio/pcm".toMediaType()
                override fun contentLength() = -1L
                override fun source() = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (pcm.exhausted()) throw IOException("private-stream-error")
                        return pcm.read(sink, byteCount)
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            }
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body).build()
        }.build()
        val completed = CountDownLatch(1)
        var error: String? = null
        start(OnlineSpeech(client)) { error = it; completed.countDown() }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertEquals("在线播报音频接收失败", error)
        assertFalse("Partial speech must not restart locally", context.cacheDir.listFiles().orEmpty()
            .any { it.name.startsWith("speech-") })
    }

    private fun withSpeech(test: (ContextWrapper, (OnlineSpeech, (String?) -> Unit) -> Unit) -> Unit) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val target = instrument.targetContext
        val folder = File(target.cacheDir, "online-voice-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = folder
            override fun getCacheDir() = folder
        }
        val wasEnabled = target.getSharedPreferences("voice", 0).getBoolean("wake_enabled", false)
        val activity = instrument.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var speech: FileSpeech? = null
        try {
            instrument.runOnMainSync {
                activity.setTurnScreenOn(true)
                target.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
                VoiceRuntime.setEnabled(target, false)
                VoiceRuntime.cancel()
                Vault(context).write(Saved(onlineTtsEndpoint = "https://speech.example/v1/audio/speech", onlineTtsToken = "test-token"))
            }
            await("Device must be unlocked") { !target.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            test(context) { online, finished -> instrument.runOnMainSync {
                speech = FileSpeech(context, online = online, finished = finished).also { it.speak("你好，我是豆馅。") }
            } }
        } finally {
            instrument.runOnMainSync {
                speech?.stop()
                VoiceRuntime.setEnabled(target, wasEnabled)
                activity.finish()
            }
            folder.deleteRecursively()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10)
        assertTrue(message, condition())
    }
}
