package com.moonrailgun.opendoubao.assistant

import android.os.Bundle
import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import com.moonrailgun.opendoubao.assistant.voice.LocalSpeechEngine
import com.moonrailgun.opendoubao.assistant.voice.LocalRecognitionService
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime
import com.moonrailgun.opendoubao.assistant.voice.VoiceService
import com.moonrailgun.opendoubao.assistant.voice.createKeywordSpotter
import com.moonrailgun.opendoubao.assistant.voice.wakePhrases
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class LocalVoiceTest {
    @Test fun playbackStartsBeforeTheWholeUtteranceIsSynthesized() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val wasEnabled = context.getSharedPreferences("voice", 0).getBoolean("wake_enabled", false)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrument.runOnMainSync {
            activity.setTurnScreenOn(true)
            context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
            VoiceRuntime.setEnabled(context, false)
        }
        try {
            await("Device must be unlocked") { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            await("Previous voice request did not finish") { VoiceRuntime.state.value == "空闲" }
            val existingCache = context.cacheDir.listFiles().orEmpty().toSet()
            instrument.runOnMainSync {
                assertTrue(VoiceRuntime.speak(context, "我在！你好呀，我是豆馅。今天也一起加油吧！有什么想做的，告诉我就好。"))
            }
            await("Playback waited for the complete WAV instead of starting with the first audio", 30_000) {
                val file = context.cacheDir.listFiles().orEmpty().firstOrNull { it.name.startsWith("speech-") && it !in existingCache }
                // Android fills in the RIFF header only when file synthesis finishes.
                file != null && file.length() > 44 && file.inputStream().use { input ->
                    val header = ByteArray(4)
                    input.read(header) == 4 && header.all { it == 0.toByte() }
                } &&
                    audio.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }
            }
            assertEquals("正在播报", VoiceRuntime.state.value)
            instrument.runOnMainSync { VoiceRuntime.cancel() }
            await("Cancel did not stop playback") {
                VoiceRuntime.state.value == "空闲" &&
                    audio.activePlaybackConfigurations.none { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANT }
            }
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("speech-") && it !in existingCache })
        } finally {
            instrument.runOnMainSync {
                VoiceRuntime.cancel()
                VoiceRuntime.setEnabled(context, wasEnabled)
                activity.finish()
            }
        }
    }

    @Test fun wakeReplyFinishesBeforeListeningAndCancellationStopsTheChain() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val wasEnabled = context.getSharedPreferences("voice", 0).getBoolean("wake_enabled", false)
        // Kotlin internal names differ between the debug test target and the installed system variant.
        val respondToWake = VoiceRuntime::class.java.methods.single { it.name.startsWith("respondToWake\$") }
        @Suppress("DEPRECATION")
        fun serviceStopped() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .none { it.service.className == VoiceService::class.java.name }
        instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrument.runOnMainSync {
            activity.setTurnScreenOn(true)
            context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
            VoiceRuntime.setEnabled(context, false)
        }
        try {
            await("Device must be unlocked for playback") {
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            }
            await("Previous voice service did not stop", condition = ::serviceStopped)
            val existingCache = context.cacheDir.listFiles().orEmpty().toSet()
            instrument.runOnMainSync {
                respondToWake.invoke(VoiceRuntime, context)
                assertEquals("正在播报", VoiceRuntime.state.value)
                assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, VoiceRuntime.foregroundType)
            }
            await("Wake reply did not continue into listening: ${VoiceRuntime.error.value}", 30_000) {
                VoiceRuntime.state.value == "正在听，请说话"
            }
            SystemClock.sleep(2_000)
            assertEquals("Wake reply must keep waiting for the task", "正在听，请说话", VoiceRuntime.state.value)
            assertEquals("", VoiceRuntime.error.value)
            assertFalse("Reply left a playback file", context.cacheDir.listFiles().orEmpty()
                .any { it.name.startsWith("speech-") && it !in existingCache })
            instrument.runOnMainSync { VoiceRuntime.cancel() }
            await("Cancel did not release microphone") { VoiceRuntime.state.value == "空闲" }
            await("Cancelled voice service did not stop", condition = ::serviceStopped)
            // The bundled prompt plays at once; cancel while it is still playing.
            instrument.runOnMainSync { respondToWake.invoke(VoiceRuntime, context); VoiceRuntime.cancel() }
            await("Cancelled reply did not finish") { VoiceRuntime.state.value == "空闲" }
            SystemClock.sleep(1_000)
            assertEquals("Cancelled reply must not start recording", "空闲", VoiceRuntime.state.value)
        } finally {
            instrument.runOnMainSync {
                VoiceRuntime.cancel()
                VoiceRuntime.setEnabled(context, wasEnabled)
                activity.finish()
            }
        }
    }

    @Test fun foregroundPlaybackAndMicrophoneCancelReleaseTheRuntime() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrument.runOnMainSync {
            activity.setTurnScreenOn(true)
            context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
        }
        await("Device must be unlocked for playback") {
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }
        var recognizer: SpeechRecognizer? = null
        try {
            instrument.runOnMainSync { assertTrue(VoiceRuntime.speak(context, "你好，我是豆馅。")) }
            await("Playback did not finish", 30_000) { VoiceRuntime.state.value == "空闲" }
            assertEquals("", VoiceRuntime.error.value)
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("speech-") })

            val ready = CountDownLatch(1)
            var error = 0
            instrument.runOnMainSync {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context,
                    ComponentName(context, LocalRecognitionService::class.java))
                recognizer!!.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { ready.countDown() }
                    override fun onError(value: Int) { error = value; ready.countDown() }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onResults(results: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                recognizer!!.startListening(Intent())
            }
            assertTrue("Microphone did not become ready", ready.await(20, TimeUnit.SECONDS))
            assertEquals("Microphone error", 0, error)
            instrument.runOnMainSync { recognizer!!.cancel() }
            await("Cancel did not release microphone") { VoiceRuntime.state.value == "空闲" }
        } finally {
            instrument.runOnMainSync {
                VoiceRuntime.cancel()
                recognizer?.destroy()
                activity.finish()
            }
        }
    }

    private fun await(message: String, timeout: Long = 5_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(message, condition())
    }

    @Test fun packagedModelsRecognizeChineseAndSeparateEngineSynthesizesASeekableFile() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val wav = instrument.context.assets.open("zh.wav").use { it.readBytes() }
        val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (String(wav, offset, 4, Charsets.US_ASCII) != "data") {
            val length = data.getInt(offset + 4)
            offset += 8 + length + (length % 2)
        }
        val samples = FloatArray(data.getInt(offset + 4) / 2) { data.getShort(offset + 8 + it * 2) / 32768f }
        val text = LocalSpeechEngine(context).decode(samples)
        assertTrue("Recognized: $text", text.count { it in '\u4e00'..'\u9fff' } >= 6)

        val initialized = CountDownLatch(1)
        var status = -1
        var synthesisError = false
        lateinit var tts: TextToSpeech
        instrument.runOnMainSync {
            tts = TextToSpeech(context, { status = it; initialized.countDown() }, "com.moonrailgun.opendoubao.tts")
        }
        val file = File.createTempFile("voice-test-", ".wav", context.cacheDir)
        try {
            assertTrue("TTS bind timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            assertTrue(tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= 0)
            for (phrase in wakePhrases) {
                val completed = CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { completed.countDown() }
                    @Deprecated("Platform callback")
                    override fun onError(utteranceId: String?) { synthesisError = true; completed.countDown() }
                })
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                    assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("$phrase。", Bundle(), it, "local-test"))
                }
                assertTrue("Synthesis timed out", completed.await(120, TimeUnit.SECONDS))
                assertFalse("Synthesis failed", synthesisError)
                val audio = file.readBytes()
                assertTrue("Empty WAV", audio.size > 44)
                assertEquals("RIFF", String(audio, 0, 4, Charsets.US_ASCII))
                assertTrue("Silent WAV", audio.drop(44).any { abs(it.toInt()) > 20 })
            }
        } finally { tts.shutdown(); file.delete() }
    }

    @Test fun fixedRecordingsRecognizeEveryWakePhrase() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        // Keep KWS inputs fixed so changing the playback voice does not change this regression test.
        wakePhrases.forEachIndexed { index, phrase ->
            val audio = instrument.context.assets.open("wake/wake-$index.wav").use { it.readBytes() }
            val pcm = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
            var chunk = 12
            var sampleRate = 0
            while (String(audio, chunk, 4, Charsets.US_ASCII) != "data") {
                if (String(audio, chunk, 4, Charsets.US_ASCII) == "fmt ") sampleRate = pcm.getInt(chunk + 12)
                val length = pcm.getInt(chunk + 4)
                chunk += 8 + length + (length % 2)
            }
            assertTrue(sampleRate > 0)
            val count = pcm.getInt(chunk + 4) / 2
            val spoken = FloatArray(count + sampleRate) { if (it < count) pcm.getShort(chunk + 8 + it * 2) / 32768f else 0f }
            val spotter = createKeywordSpotter(context)
            val stream = spotter.createStream()
            val found = mutableSetOf<String>()
            try {
                spoken.asList().chunked(1024).forEach { frame ->
                    stream.acceptWaveform(frame.toFloatArray(), sampleRate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        spotter.getResult(stream).keyword.takeIf { it.isNotEmpty() }?.let {
                            found += it; spotter.reset(stream)
                        }
                    }
                }
                assertEquals("Synthetic voice keyword detection: $phrase", setOf(phrase), found)
            } finally { stream.release(); spotter.release() }
        }
    }
}
