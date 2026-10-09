package com.openodoubao.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Launcher3 discovers the system package's partner_default_layout through this receiver.
class LauncherCustomization : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
