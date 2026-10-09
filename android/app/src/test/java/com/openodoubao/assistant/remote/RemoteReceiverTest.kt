package com.openodoubao.assistant.remote

import com.openodoubao.assistant.data.Record
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.coroutines.CoroutineContext

class RemoteReceiverTest {
    private val deviceId = "ea6530be-cf90-4a2d-9628-b3cc0ebea6e2"
    private val binding = Pairing("192.168.1.9", 38687, "a".repeat(64), "b".repeat(64))
    private val code = "odoubao://pair?v=1&host=${binding.host}&port=${binding.port}&fingerprint=${binding.fingerprint}&token=${binding.token}"

    @Test fun disablingPreservesTheBindingWhileForgetRemovesIt() {
        Fixture(RemoteConfig(deviceId, binding, false)).use { fixture ->
            val receiver = fixture.receiver
            assertTrue(receiver.state.value.bound)
            assertFalse(receiver.state.value.enabled)
            receiver.setEnabled(false)
            assertEquals(RemoteConfig(deviceId, binding, false), fixture.saved.last())
            receiver.forget()
            assertEquals(RemoteConfig(deviceId), fixture.saved.last())
            assertFalse(receiver.state.value.bound)
            assertFalse(receiver.state.value.connected)
            assertEquals("", receiver.state.value.address)
        }
    }

    @Test fun existingBindingMustBeForgottenBeforePairingAnotherDesktop() {
        Fixture(RemoteConfig(deviceId, binding, false)).use { fixture ->
            assertThrows(IllegalStateException::class.java) { fixture.receiver.pair(code) }
            assertTrue(fixture.saved.isEmpty())
            assertTrue(fixture.receiver.state.value.bound)
        }
    }

    @Test fun malformedPairingCannotEnableOrSaveTheReceiver() {
        Fixture(RemoteConfig(deviceId)).use { fixture ->
            assertThrows(IllegalArgumentException::class.java) { fixture.receiver.pair("not-a-device-code") }
            assertTrue(fixture.saved.isEmpty())
            assertFalse(fixture.receiver.state.value.enabled)
            assertFalse(fixture.receiver.state.value.bound)
            assertThrows(IllegalStateException::class.java) { fixture.receiver.setEnabled(true) }
        }
    }

    @Test fun aPendingPairingIsNotSavedAndCanBeDisabledBeforeWelcome() {
        Fixture(RemoteConfig(deviceId)).use { fixture ->
            fixture.receiver.pair(code)
            assertTrue(fixture.receiver.state.value.enabled)
            assertFalse(fixture.receiver.state.value.bound)
            assertTrue(fixture.saved.isEmpty())
            fixture.receiver.setEnabled(false)
            fixture.dispatcher.runPending()
            assertEquals(RemoteConfig(deviceId), fixture.saved.last())
            assertFalse(fixture.receiver.state.value.enabled)
            assertEquals("", fixture.receiver.state.value.address)
        }
    }

    @Test fun aQueuedWelcomeCannotReenableADisabledReceiver() {
        TlsDesktop().use { desktop ->
            Fixture(RemoteConfig(deviceId, desktop.binding, true)).use { fixture ->
                val socket = desktop.socket.get(5, TimeUnit.SECONDS)
                fixture.dispatcher.runUntil { desktop.hello.isDone }
                assertEquals(deviceId, desktop.hello.get().getString("deviceId"))
                assertEquals(desktop.binding.token, desktop.hello.get().getString("token"))
                fixture.dispatcher.runPending()
                assertTrue(socket.send("{\"type\":\"welcome\",\"version\":1}"))
                val lateWelcome = fixture.dispatcher.next()
                fixture.receiver.setEnabled(false)
                lateWelcome.run()
                fixture.dispatcher.runPending()
                assertEquals(listOf(RemoteConfig(deviceId, desktop.binding, false)), fixture.saved)
                assertFalse(fixture.receiver.state.value.enabled)
                assertFalse(fixture.receiver.state.value.connected)
            }
        }
    }

    @Test fun aQueuedRemoteCommandCannotRunAfterUnbinding() {
        TlsDesktop().use { desktop ->
            Fixture(RemoteConfig(deviceId, desktop.binding, true)).use { fixture ->
                val socket = desktop.socket.get(5, TimeUnit.SECONDS)
                fixture.dispatcher.runUntil { desktop.hello.isDone }
                assertTrue(socket.send("{\"type\":\"welcome\",\"version\":1}"))
                fixture.dispatcher.runUntil { fixture.receiver.state.value.connected }
                fixture.dispatcher.runPending()
                assertTrue(socket.send("{\"type\":\"request\",\"id\":\"$deviceId\",\"method\":\"task.start\",\"params\":{\"goal\":\"read the page\"}}"))
                val lateCommand = fixture.dispatcher.next()
                fixture.receiver.forget()
                lateCommand.run()
                fixture.dispatcher.runPending()
                assertEquals(0, fixture.tasks.starts)
                assertEquals(RemoteConfig(deviceId), fixture.saved.last())
                assertFalse(fixture.receiver.state.value.bound)
                assertFalse(fixture.receiver.state.value.connected)
            }
        }
    }

    @Test fun aTlsPinMismatchStopsReceptionBeforeSendingCredentials() {
        TlsDesktop().use { desktop ->
            val mismatching = desktop.binding.copy(fingerprint = "0".repeat(64))
            Fixture(RemoteConfig(deviceId, mismatching, true)).use { fixture ->
                fixture.dispatcher.runUntil { !fixture.receiver.state.value.enabled }
                assertFalse(desktop.hello.isDone)
                assertFalse(fixture.receiver.state.value.connected)
                assertTrue(fixture.receiver.state.value.message.contains("证书"))
                assertEquals(RemoteConfig(deviceId, mismatching, false), fixture.saved.last())
            }
        }
    }

    @Test fun anEmptyCloseFrameSchedulesReconnectWithoutThrowing() {
        TlsDesktop(emptyCloseFrame = true).use { desktop ->
            Fixture(RemoteConfig(deviceId, desktop.binding, true)).use { fixture ->
                fixture.dispatcher.runUntil {
                    fixture.errors.isNotEmpty() || fixture.receiver.state.value.message.contains("重连")
                }
                assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
                assertTrue(fixture.receiver.state.value.enabled)
                assertTrue(fixture.receiver.state.value.bound)
                assertFalse(fixture.receiver.state.value.connected)
                assertTrue(fixture.receiver.state.value.message.contains("重连"))
            }
        }
    }

    private class Tasks : RemoteTasks {
        var starts = 0
        override fun config() = com.openodoubao.assistant.model.ModelConfig()
        override fun saveConfig(config: com.openodoubao.assistant.model.ModelConfig) = Unit
        override fun snapshot() = RemoteSnapshot()
        override fun records() = emptyList<Record>()
        override fun start(goal: String, taskId: String): String { starts++; return taskId }
        override fun stop(taskId: String) = Unit
    }

    private class Fixture(config: RemoteConfig) : AutoCloseable {
        val dispatcher = QueuedDispatcher()
        val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val saved = mutableListOf<RemoteConfig>()
        val tasks = Tasks()
        val receiver = RemoteReceiver(scope, config, { saved += it }, tasks, MutableStateFlow(RemoteSnapshot()))
        override fun close() { receiver.close(); scope.cancel(); dispatcher.runPending() }
    }

    // Hold real WebSocket callbacks until the test chooses which lifecycle transition runs first.
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = queue.poll(5, TimeUnit.SECONDS) ?: error("Receiver callback did not arrive")
        fun runPending() { while (true) (queue.poll() ?: return).run() }
        fun runUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Receiver state did not arrive" }
                queue.poll(10, TimeUnit.MILLISECONDS)?.run()
            }
        }
    }

    private class TlsDesktop(emptyCloseFrame: Boolean = false) : AutoCloseable {
        private val server = MockWebServer()
        val socket = CompletableFuture<WebSocket>()
        val hello = CompletableFuture<JSONObject>()
        val binding: Pairing
        init {
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                PinnedTrustManagerTest.VALID_CERTIFICATE.trimIndent().byteInputStream())
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(
                "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgp9zRz6ExBnNg8G6L" +
                    "3LGPOzg4NjOWd3AbUKLQM8Esc5KhRANCAAT3aTFlW1oectCw+l5JLxFUUTmbm4qI" +
                    "KxaDvNdjhj7NLxLXNrALh9HgFlP7JSL2rUmNm3rpMhzAR7GdMEi1th7m")))
            val password = "test-only".toCharArray()
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry("test", key, password, arrayOf(certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            server.useHttps(ssl.socketFactory, false)
            if (emptyCloseFrame) server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = request.getHeader("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(key.toByteArray()))
                    // A zero-length Close frame is legal; OkHttp reports the absent status as 1005.
                    return MockResponse().setResponseCode(101).setHeader("Connection", "Upgrade")
                        .setHeader("Upgrade", "websocket").setHeader("Sec-WebSocket-Accept", accept)
                        .setBody(Buffer().write(byteArrayOf(0x88.toByte(), 0))).setBodyDelay(200, TimeUnit.MILLISECONDS)
                }
            } else server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    if (message.optString("type") == "hello") hello.complete(message)
                }
            }))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
            binding = Pairing("127.0.0.1", server.port, fingerprint, "c".repeat(64))
        }
        override fun close() { server.close() }
    }
}
