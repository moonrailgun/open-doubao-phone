package com.openodoubao.assistant.voice

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OnlineSpeechTest {
    @Test fun streamsPcmAndUsesTheConfiguredModelAndVoice() = runBlocking {
        MockWebServer().use { server ->
            val pcm = ByteArray(20_000) { (it % 127).toByte() }
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/pcm")
                .setChunkedBody(Buffer().write(pcm), 333))
            val chunks = mutableListOf<ByteArray>()
            OnlineSpeech().stream(server.url("/v1/audio/speech").toString(), "token", "你好",
                model = "elevenlabs/custom-model", voice = "custom-voice") { chunks += it }
            assertTrue(chunks.size > 1)
            assertTrue(chunks.all { it.size % 2 == 0 })
            assertArrayEquals(pcm, chunks.fold(byteArrayOf()) { result, chunk -> result + chunk })
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("elevenlabs/custom-model", body.getString("model"))
            assertEquals("custom-voice", body.getString("voice"))
            assertEquals("你好", body.getString("input"))
            assertTrue(body.getBoolean("stream"))
            assertEquals("audio", body.getString("stream_format"))
            assertEquals("pcm", body.getString("response_format"))
            assertEquals("pcm_24000", body.getJSONObject("provider_options").getJSONObject("elevenlabs").getString("output_format"))
        }
    }

    @Test fun rejectsInvalidOrEmptyAudioAndDoesNotLeakErrors() = runBlocking {
        MockWebServer().use { server ->
            val speech = OnlineSpeech()
            for (response in listOf(
                MockResponse().setResponseCode(401).setBody("secret-private-response"),
                MockResponse().setHeader("Content-Type", "application/json").setBody("{}"),
                MockResponse().setHeader("Content-Type", "audio/pcm").setBody(""),
                MockResponse().setHeader("Content-Type", "audio/pcm").setBody("abc"),
            )) {
                server.enqueue(response)
                val error = runCatching { speech.stream(server.url("/").toString(), "secret", "你好") { fail("Invalid audio emitted") } }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(error!!.message.orEmpty().contains("secret"))
            }
            for (input in listOf("", "x".repeat(5001))) {
                assertTrue(runCatching { speech.stream(server.url("/").toString(), "token", input) {} }.isFailure)
            }
            assertTrue(runCatching { speech.stream(server.url("/").toString(), "token", "你好", model = " ") {} }.isFailure)
            assertTrue(runCatching { speech.stream(server.url("/").toString(), "token", "你好", voice = " ") {} }.isFailure)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun cancellationClosesTheCallWithoutEmittingAudio() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val pending = launch {
                OnlineSpeech(client).stream(server.url("/").toString(), "token", "你好") { fail("Cancelled request emitted audio") }
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            pending.cancelAndJoin()
            withTimeout(2000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue(pending.isCancelled)
        }
    }

    @Test fun redirectsAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirect")))
            val error = runCatching { OnlineSpeech().stream(server.url("/").toString(), "token", "你好") {} }.exceptionOrNull()
            assertNotNull(error)
            assertEquals(1, server.requestCount)
        }
    }
}
