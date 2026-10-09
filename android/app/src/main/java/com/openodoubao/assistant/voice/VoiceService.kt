package com.openodoubao.assistant.voice

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.IBinder
import android.os.PowerManager
import com.openodoubao.assistant.MainActivity
import com.openodoubao.assistant.R
import kotlinx.coroutines.*

class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing: Job? = null
    private var latestStart = 0
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { VoiceRuntime.cancel() }
    }

    @SuppressLint("WakelockTimeout") // Released with the service; continuous wake listening needs an unbounded lease.
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("voice", "本地语音", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice").apply { acquire() }
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        startForeground(42, notification(VoiceRuntime.state.value), VoiceRuntime.foregroundType)
        VoiceRuntime.serviceReady()
        if (intent?.action == "stop") VoiceRuntime.cancel()
        if (observing == null) observing = scope.launch {
            VoiceRuntime.state.collect { state ->
                if (state == "空闲") stopSelfResult(latestStart)
                else getSystemService(NotificationManager::class.java).notify(42, notification(state))
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(state: String): Notification = Notification.Builder(this, "voice")
        .setSmallIcon(R.drawable.lucide_mic).setContentTitle("豆馅本地语音").setContentText(state)
        .setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(Notification.Action.Builder(null, "关闭", PendingIntent.getService(this, 1,
            Intent(this, VoiceService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)).build()).build()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        VoiceRuntime.serviceStopped()
        scope.cancel()
        unregisterReceiver(screen)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
