package com.openodoubao.assistant

import android.os.Bundle
import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import com.openodoubao.assistant.voice.LocalSpeechEngine
import com.openodoubao.assistant.voice.LocalRecognitionService
import com.openodoubao.assistant.voice.VoiceRuntime
import com.openodoubao.assistant.voice.createKeywordSpotter
import com.openodoubao.assistant.voice.wakePhrases
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
        val completed = CountDownLatch(1)
        var status = -1
        var synthesisError = false
        lateinit var tts: TextToSpeech
        instrument.runOnMainSync {
            tts = TextToSpeech(context, { status = it; initialized.countDown() }, "com.openodoubao.tts")
        }
        val file = File.createTempFile("voice-test-", ".wav", context.cacheDir)
        try {
            assertTrue("TTS bind timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            assertTrue(tts.setLanguage(Locale.SIMPLIFIED_CHINESE) >= 0)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { completed.countDown() }
                @Deprecated("Platform callback")
                override fun onError(utteranceId: String?) { synthesisError = true; completed.countDown() }
            })
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("豆馅你好。你好豆馅。豆馅同志。", Bundle(), it, "local-test"))
            }
            assertTrue("Synthesis timed out", completed.await(120, TimeUnit.SECONDS))
            assertFalse("Synthesis failed", synthesisError)
            val audio = file.readBytes()
            assertTrue("Empty WAV", audio.size > 48000)
            assertEquals("RIFF", String(audio, 0, 4, Charsets.US_ASCII))
            assertTrue("Silent WAV", audio.drop(44).any { abs(it.toInt()) > 20 })
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
                assertEquals("Synthetic voice keyword detections", wakePhrases.toSet(), found)
            } finally { stream.release(); spotter.release() }
        } finally { tts.shutdown(); file.delete() }
    }
}
