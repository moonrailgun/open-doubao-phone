package com.openodoubao.assistant

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.openodoubao.assistant.phone.AssistantService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SystemDefaultsTest {
    @Test fun startupEnablesAccessibilityWithoutReplacingOtherServices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        assumeTrue(app.javaClass.name == "com.openodoubao.assistant.SystemApplication")
        val resolver = app.contentResolver
        val servicesKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabledKey = Settings.Secure.ACCESSIBILITY_ENABLED
        val originalServices = Settings.Secure.getString(resolver, servicesKey)
        val originalEnabled = Settings.Secure.getString(resolver, enabledKey)
        val own = ComponentName(app, AssistantService::class.java)
        val other = "com.example.reader/.AccessibilityService"
        fun write(services: String?, enabled: String?) {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                assertTrue(Settings.Secure.putString(resolver, servicesKey, services))
                assertTrue(Settings.Secure.putString(resolver, enabledKey, enabled))
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        try {
            write(other, "0")
            instrumentation.runOnMainSync { app.onCreate() }
            assertEquals("$other:${own.flattenToShortString()}", Settings.Secure.getString(resolver, servicesKey))
            assertEquals(1, Settings.Secure.getInt(resolver, enabledKey))
            assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"))

            // Android also persists fully qualified component names; startup must not duplicate them.
            val full = "$other:${own.flattenToString()}"
            write(full, "1")
            repeat(2) { instrumentation.runOnMainSync { app.onCreate() } }
            assertEquals(full, Settings.Secure.getString(resolver, servicesKey))
        } finally { write(originalServices, originalEnabled) }
    }
}
