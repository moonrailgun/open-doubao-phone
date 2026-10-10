package com.moonrailgun.opendoubao.assistant.voice

import android.content.*
import android.service.voice.VoiceInteractionService

class DouxianInteractionService : VoiceInteractionService() {
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { VoiceRuntime.refresh(context) }
    }
    override fun onCreate() {
        super.onCreate()
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }
    override fun onReady() { super.onReady(); VoiceRuntime.assistantReady(this, true) }
    override fun onShutdown() { VoiceRuntime.assistantReady(this, false); super.onShutdown() }
    override fun onDestroy() {
        VoiceRuntime.assistantReady(this, false)
        unregisterReceiver(unlocked)
        super.onDestroy()
    }
}
