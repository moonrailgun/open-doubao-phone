package com.moonrailgun.opendoubao.assistant.phone

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

const val BACKEND_ADB = "adb"
const val BACKEND_SHIZUKU = "shizuku"
fun shellBackend(context: Context): String = context.getSharedPreferences("task", Context.MODE_PRIVATE).getString("shellBackend", "") ?: ""
fun chooseShellBackend(context: Context, value: String) {
    check(ShellBridge.displayId.value == null) { "请先停止虚拟屏任务" }
    ShellBridge.disconnect()
    context.getSharedPreferences("task", Context.MODE_PRIVATE).edit().putString("shellBackend", value).apply()
}

/** No shell command channel: the remote service only owns a display, launches apps, and sends Back/Home. */
object ShellBridge {
    private val mutableStatus = MutableStateFlow("未连接")
    val status = mutableStatus.asStateFlow()
    val displayId = MutableStateFlow<Int?>(null)
    private val owner = Binder()
    @Volatile private var remote: IShellDisplay? = null
    @Volatile private var source = ""
    private var initialized = false
    private lateinit var context: Context
    private val args get() = Shizuku.UserServiceArgs(ComponentName(context, ShellDisplayService::class.java))
        .daemon(false).processNameSuffix("virtual_display").tag("virtual_display").version(1)
    private var binding: ServiceConnection? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        this.context = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { if (source == BACKEND_SHIZUKU) lost(remote?.asBinder()) }
        Shizuku.addRequestPermissionResultListener { code, grant ->
            if (code == 41) {
                if (grant == PackageManager.PERMISSION_GRANTED) refresh()
                else mutableStatus.value = "Shizuku 授权被拒绝，可在 Shizuku 的已授权应用中重新允许"
            }
        }
    }

    @Synchronized fun connect(binder: IBinder, backend: String) {
        check(remote == null) { "已有连接，请先断开旧连接" }
        val service = IShellDisplay.Stub.asInterface(binder)
        service.attach(owner)
        binder.linkToDeath({ lost(binder) }, 0)
        remote = service
        source = backend
        mutableStatus.value = "${if (backend == BACKEND_ADB) "ADB" else "Shizuku"} 已连接"
    }

    @Synchronized private fun lost(binder: IBinder?) {
        if (remote?.asBinder() !== binder) return
        if (source == BACKEND_SHIZUKU) {
            val connection = binding
            binding = null
            if (connection != null && Shizuku.pingBinder()) runCatching { Shizuku.unbindUserService(args, connection, false) }
        }
        remote = null
        displayId.value = null
        mutableStatus.value = "虚拟屏连接已断开，请重新启动连接"
    }

    fun ready(context: Context) = Build.VERSION.SDK_INT >= 34 && source == shellBackend(context) && remote?.asBinder()?.isBinderAlive == true
    fun service(context: Context): IShellDisplay {
        check(ready(context)) { "虚拟屏未连接，请先在执行方式设置中启动 ADB 或 Shizuku" }
        return checkNotNull(remote)
    }

    @Synchronized fun refresh(requestPermission: Boolean = false) {
        if (!initialized || shellBackend(context) != BACKEND_SHIZUKU) return
        runCatching {
            check(Build.VERSION.SDK_INT >= 34) { "普通系统虚拟屏需要 Android 14 或更新版本" }
            check(Shizuku.pingBinder()) { "请先安装并启动 Shizuku，再返回这里授权" }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                mutableStatus.value = "等待 Shizuku 授权"
                if (requestPermission) Shizuku.requestPermission(41)
            } else if (remote == null && binding == null) {
                mutableStatus.value = "正在连接 Shizuku…"
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(ShellBridge) {
                        if (binding !== this || shellBackend(context) != BACKEND_SHIZUKU) return@synchronized
                        runCatching { connect(service, BACKEND_SHIZUKU) }
                            .onFailure {
                                binding = null
                                runCatching {
                                    Shizuku.unbindUserService(args, this, false)
                                    Shizuku.unbindUserService(args, this, true)
                                }
                                mutableStatus.value = "连接失败：${it.message}"
                            }
                    }
                    override fun onServiceDisconnected(name: ComponentName) = synchronized(ShellBridge) {
                        if (binding === this) { binding = null; lost(remote?.asBinder()) }
                    }
                }
                binding = connection
                Shizuku.bindUserService(args, connection)
            }
        }.onFailure { binding = null; mutableStatus.value = it.message ?: "无法连接 Shizuku" }
    }

    @Synchronized fun disconnect() {
        check(displayId.value == null) { "请先停止虚拟屏任务" }
        val connection = binding
        binding = null
        if (connection != null && Shizuku.pingBinder()) runCatching {
            // Clear Shizuku's cached callbacks before the old service dies during a quick reconnect.
            Shizuku.unbindUserService(args, connection, false)
            Shizuku.unbindUserService(args, connection, true)
        }
        remote?.let { runCatching { it.destroy() } }
        remote = null
        source = ""
        mutableStatus.value = "未连接"
    }
}
