package com.moonrailgun.opendoubao.assistant

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.moonrailgun.opendoubao.assistant.model.HttpTransport
import com.moonrailgun.opendoubao.assistant.phone.AssistantService
import com.moonrailgun.opendoubao.assistant.phone.ShellBridge
import com.moonrailgun.opendoubao.assistant.voice.VoiceRuntime

/** Android only delivers task removal to started services, not the bound accessibility service. */
class AppLifecycleService : Service() {
    companion object {
        fun isExited(context: Context) = context.getSharedPreferences("lifecycle", MODE_PRIVATE).getBoolean("exited", false)

        fun open(context: Context) {
            context.getSharedPreferences("lifecycle", MODE_PRIVATE).edit().putBoolean("exited", false).apply()
            context.startService(Intent(context, AppLifecycleService::class.java))
            AssistantService.connected.value?.activate()
            ShellBridge.initialize(context)
            ShellBridge.refresh()
            VoiceRuntime.refresh(context)
        }

        fun exitIfClosed(context: Context) {
            if (context.getSharedPreferences("overlay", MODE_PRIVATE).getBoolean("enabled", true) ||
                context.getSystemService(ActivityManager::class.java).appTasks.any { it.taskInfo.numActivities > 0 }) return
            // Persist before stopping services: their destruction or a later system rebind must not restart work.
            context.getSharedPreferences("lifecycle", MODE_PRIVATE).edit().putBoolean("exited", true).apply()
            VoiceRuntime.exit(context)
            AssistantService.connected.value?.exit()
            ShellBridge.disconnect()
            HttpTransport.cancelAll()
            context.stopService(Intent(context, AppLifecycleService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onTaskRemoved(rootIntent: Intent?) { exitIfClosed(this) }
    override fun onBind(intent: Intent?): IBinder? = null
}
