package com.moonrailgun.opendoubao.assistant.voice

import com.moonrailgun.opendoubao.assistant.data.Saved
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The speech endpoint returns raw mono PCM16 at 24 kHz; callbacks run on the HTTP worker. */
class OnlineSpeech(private val client: OkHttpClient = http) {
    suspend fun stream(endpoint: String, token: String, text: String,
        model: String = Saved.DEFAULT_TTS_MODEL, voice: String = Saved.DEFAULT_TTS_VOICE, audio: (ByteArray) -> Unit) {
        require(token.isNotBlank()) { "请配置在线播报 Token" }
        require(model.isNotBlank() && voice.isNotBlank()) { "请填写在线播报模型和音色" }
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 5000) { "播报文本须为 1–5000 个字符" }
        val body = JSONObject().put("model", model).put("voice", voice)
            .put("input", text).put("stream", true).put("stream_format", "audio").put("response_format", "pcm")
            .put("provider_options", JSONObject().put("elevenlabs", JSONObject().put("output_format", "pcm_24000")))
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("在线播报连接失败"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(it.isSuccessful) { "在线播报请求失败（HTTP ${it.code}）" }
                            val data = checkNotNull(it.body) { "在线播报未返回音频" }
                            val type = data.contentType()?.let { mime -> "${mime.type}/${mime.subtype}" }
                            check(type in setOf("audio/pcm", "audio/x-pcm", "application/octet-stream")) { "在线播报返回了不支持的音频格式" }
                            check(data.contentLength() <= MAX_BYTES) { "在线播报音频过大" }
                            val source = data.source()
                            var total = 0L
                            while (continuation.isActive && !source.exhausted()) {
                                source.request(8192)
                                val count = minOf(source.buffer.size, 8192L)
                                check(count % 2 == 0L) { "在线播报音频不完整" }
                                total += count
                                check(total <= MAX_BYTES) { "在线播报音频过大" }
                                audio(source.readByteArray(count))
                            }
                            check(total > 0) { "在线播报未返回音频" }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is IllegalStateException) e else IOException("在线播报音频接收失败"))
                    }
                }
            })
        }
    }

    companion object {
        private const val MAX_BYTES = 24_000L * 2 * 300
        private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
