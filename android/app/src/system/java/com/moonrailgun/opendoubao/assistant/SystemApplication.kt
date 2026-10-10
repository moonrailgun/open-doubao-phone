package com.moonrailgun.opendoubao.assistant

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import android.util.Log
import com.moonrailgun.opendoubao.assistant.phone.AssistantService

class SystemApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the ROM's privileged variant can restore its accessibility overlay at startup.
        runCatching {
            val own = ComponentName(this, AssistantService::class.java)
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val enabled = Settings.Secure.getString(contentResolver, key).orEmpty()
            if (enabled.split(':').none { ComponentName.unflattenFromString(it) == own }) {
                val services = listOf(enabled, own.flattenToShortString()).filter { it.isNotEmpty() }.joinToString(":")
                check(Settings.Secure.putString(contentResolver, key, services))
            }
            if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                check(Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
            }
        }.onFailure { Log.e("SystemApplication", "Cannot enable system accessibility service", it) }
    }
}
