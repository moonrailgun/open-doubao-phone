package com.openodoubao.assistant.remote

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** The pairing code authorizes exactly one leaf certificate, including self-signed certificates. */
@SuppressLint("CustomX509TrustManager") // Exact SHA-256 leaf pin + validity checks are covered by certificate tests.
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    init { require(fingerprint.matches(Regex("[0-9a-fA-F]{64}"))) }
    fun verify(certificate: X509Certificate) {
        certificate.checkValidity()
        val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("桌面证书指纹不匹配")
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("桌面没有提供证书")
        verify(chain[0])
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("不支持客户端证书")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

data class ReceiverStatus(val enabled: Boolean = false, val bound: Boolean = false, val connected: Boolean = false,
    val message: String = "尚未绑定桌面端", val address: String = "")

/** Every state transition and command runs on the service's main-thread scope. */
class RemoteReceiver(
    private val scope: CoroutineScope,
    initial: RemoteConfig,
    private val save: (RemoteConfig) -> Unit,
    private val tasks: RemoteTasks,
    snapshots: StateFlow<RemoteSnapshot>,
    private val name: String = "Android",
) {
    private var config = initial.copy(deviceId = initial.deviceId.ifEmpty { UUID.randomUUID().toString() },
        enabled = initial.enabled && initial.binding != null)
    private var candidate = config.binding
    private val mutable = MutableStateFlow(ReceiverStatus())
    val state = mutable.asStateFlow()
    private val commands = RemoteCommands(tasks)
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var generation = 0L
    private var ready = false
    private var attempts = 0
    private var reconnect: Job? = null
    private var welcomeTimeout: Job? = null
    private val watcher: Job

    init {
        if (config.deviceId != initial.deviceId) save(config)
        update(if (config.binding == null) "尚未绑定桌面端" else "远程接收已关闭")
        watcher = scope.launch { snapshots.collect { if (ready) send(JSONObject().put("type", "state").put("data", it.json())) } }
        if (config.enabled && candidate != null) connect()
    }

    fun pair(code: String) {
        check(config.binding == null) { "请先解除现有绑定" }
        val pairing = Pairing.parse(code)
        disconnect()
        candidate = pairing
        config = config.copy(enabled = true)
        attempts = 0
        connect()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) check(config.binding != null) { "请先绑定桌面端" }
        disconnect()
        config = config.copy(enabled = enabled)
        candidate = config.binding
        attempts = 0
        try { save(config) } catch (e: Exception) { config = config.copy(enabled = false); update("保存设置失败，连接已断开"); throw e }
        if (enabled) connect() else update("远程接收已关闭")
    }

    fun forget() {
        disconnect()
        val next = config.copy(binding = null, enabled = false)
        try { save(next) } catch (e: Exception) {
            config = config.copy(enabled = false)
            update("解绑保存失败，当前连接已断开；请重试")
            throw e
        }
        config = next
        candidate = null
        update("已解除绑定，桌面端无法再连接")
    }

    fun close() { disconnect(); watcher.cancel(); update("无障碍服务已断开") }

    private fun update(message: String) {
        mutable.value = ReceiverStatus(config.enabled, config.binding != null, ready, message,
            candidate?.let { "${it.host}:${it.port}" }.orEmpty())
    }

    private fun disconnect() {
        generation++
        ready = false
        reconnect?.cancel(); reconnect = null
        welcomeTimeout?.cancel(); welcomeTimeout = null
        socket?.cancel(); socket = null
        client?.connectionPool?.evictAll()
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    private fun connect() {
        val target = candidate ?: return
        if (!config.enabled) return
        disconnect()
        val token = generation
        val trust = PinnedTrustManager(target.fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val next = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .proxy(Proxy.NO_PROXY)
            .hostnameVerifier { host, session -> host == target.host && runCatching {
                trust.verify(session.peerCertificates.first() as X509Certificate)
            }.isSuccess }
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS).build()
        client = next
        update(if (config.binding == null) "正在验证桌面端…" else "正在连接桌面端…")
        socket = next.newWebSocket(Request.Builder().url("wss://${target.host}:${target.port}/").build(), object : WebSocketListener() {
            private fun live(webSocket: WebSocket) = generation == token && socket === webSocket && config.enabled
            private fun current(webSocket: WebSocket, block: suspend () -> Unit) {
                scope.launch { if (live(webSocket)) block() }
            }
            override fun onOpen(webSocket: WebSocket, response: Response) = current(webSocket) {
                send(JSONObject().put("type", "hello").put("version", 1).put("token", target.token)
                    .put("deviceId", config.deviceId).put("name", name.take(80).ifBlank { "Android" }))
                if (generation != token) return@current
                welcomeTimeout = scope.launch { delay(15_000); if (generation == token && !ready) retry("桌面端没有完成验证") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = current(webSocket) {
                try {
                    if (!ready) {
                        val welcome = RemoteProtocol.json(text)
                        welcome.exactFields("type", "version")
                        require(welcome.string("type") == "welcome" && welcome.opt("version") == 1) { "桌面端协议版本不匹配" }
                        // Persist the credential only after the pinned desktop has accepted it.
                        val bound = config.copy(binding = target, enabled = true)
                        save(bound)
                        config = bound
                        ready = true
                        attempts = 0
                        welcomeTimeout?.cancel(); welcomeTimeout = null
                        update("已连接，可从桌面下发任务并查看记录")
                        send(JSONObject().put("type", "state").put("data", tasks.snapshot().json()))
                    } else {
                        val reply = commands.handle(text)
                        // A connection test can outlive its socket; its late reply must not reach a newer connection.
                        if (live(webSocket)) send(reply)
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { halt("桌面协议或本地存储异常，已停止接收；请重新开启或绑定") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = current(webSocket) { halt("不支持二进制消息，已停止接收") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = current(webSocket) {
                // 1005 represents a peer's empty Close frame and cannot be sent on the wire.
                webSocket.close(if (code == 1005) 1000 else code, null)
                if (code == 1008 || code in 4000..4999) halt("桌面端拒绝验证，已停止接收；请重新绑定")
                else retry("桌面连接已断开")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = current(webSocket) { retry("桌面连接已关闭") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = current(webSocket) {
                val badCertificate = generateSequence(t) { it.cause }.any { it is CertificateException }
                if (badCertificate) halt("桌面证书校验失败，已停止接收；请核对设备码并重新绑定")
                else if (response?.code in listOf(401, 403)) halt("桌面端拒绝验证，已停止接收；请重新绑定")
                else retry("无法连接桌面端")
            }
        })
    }

    private fun send(value: JSONObject) {
        var text = value.toString()
        if (text.toByteArray(Charsets.UTF_8).size > RemoteProtocol.MAX_FRAME_BYTES) {
            if (value.optString("type") == "response") text = JSONObject().put("type", "response").put("id", value.getString("id"))
                .put("ok", false).put("error", "记录超过传输上限，请在手机查看").toString()
            else { halt("任务状态超过传输上限，请在手机查看"); return }
        }
        if (socket?.send(text) != true) retry("桌面连接不可用")
    }

    private fun retry(message: String) {
        disconnect()
        if (!config.enabled) return
        attempts++
        if (config.binding == null && attempts >= 3) { halt("绑定未完成，请确认同一局域网、桌面服务和设备码后重试"); return }
        val wait = (2L shl (attempts - 1).coerceAtMost(4)).coerceAtMost(30)
        update("$message，${wait} 秒后重连")
        reconnect = scope.launch { delay(wait * 1000); connect() }
    }

    private fun halt(message: String) {
        disconnect()
        config = config.copy(enabled = false)
        if (config.binding == null) candidate = null
        runCatching { save(config) }
        update(message)
    }
}
