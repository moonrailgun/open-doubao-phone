package com.openodoubao.tts

import android.media.AudioFormat
import android.speech.tts.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Adapted from sherpa-onnx v1.13.8's Android TtsService. Synthesis only: never opens an audio device. */
class LocalTtsService : TextToSpeechService() {
    private var engine: OfflineTts? = null
    private val generation = AtomicLong()

    override fun onGetLanguage() = arrayOf("zho", "CHN", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?) =
        if (lang in setOf("zho", "chi", "eng")) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?) =
        onIsLanguageAvailable(lang, country, variant)
    override fun onStop() { generation.incrementAndGet() }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val id = generation.get()
        if (request.charSequenceText.length > 4000 ||
            onIsLanguageAvailable(request.language, request.country, request.variant) < 0) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        try {
            val tts = engine ?: loadEngine().also { engine = it }
            if (generation.get() != id) return
            if (callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
            tts.generateWithConfigAndCallback(request.charSequenceText.toString(),
                GenerationConfig(sid = 3, speed = (request.speechRate / 100f).coerceIn(0.5f, 2f))) { samples ->
                val pcm = ByteArray(samples.size * 2)
                samples.forEachIndexed { index, value ->
                    val sample = (value.coerceIn(-1f, 1f) * 32767).toInt()
                    pcm[2 * index] = sample.toByte()
                    pcm[2 * index + 1] = (sample shr 8).toByte()
                }
                var offset = 0
                var ok = true
                while (offset < pcm.size && generation.get() == id && ok) {
                    val count = minOf(callback.maxBufferSize, pcm.size - offset)
                    ok = count > 0 && callback.audioAvailable(pcm, offset, count) == TextToSpeech.SUCCESS
                    offset += count
                }
                if (ok && generation.get() == id) 1 else 0
            }
            if (generation.get() == id) callback.done()
        } catch (_: Exception) { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
    }

    private fun loadEngine(): OfflineTts {
        val preferences = getSharedPreferences("engine", MODE_PRIVATE)
        check(!preferences.getBoolean("initializing", false)) { "Previous native initialization failed" }
        val data = File(filesDir, "espeak-v1_13_8")
        if (!File(data, ".complete").exists()) {
            copyAssets("kokoro/espeak-ng-data", data)
            File(data, ".complete").writeText("1")
        }
        check(preferences.edit().putBoolean("initializing", true).commit())
        val tts = OfflineTts(assets, OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = "kokoro/model.int8.onnx", voices = "kokoro/voices.bin",
                tokens = "kokoro/tokens.txt", dataDir = data.absolutePath,
                lexicon = "kokoro/lexicon-zh.txt,kokoro/lexicon-us-en.txt"), numThreads = 2,
        ), ruleFsts = "kokoro/date-zh.fst,kokoro/number-zh.fst,kokoro/phone-zh.fst"))
        check(preferences.edit().putBoolean("initializing", false).commit())
        return tts
    }

    private fun copyAssets(path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile!!.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else children.forEach { copyAssets("$path/$it", File(target, it)) }
    }
}
