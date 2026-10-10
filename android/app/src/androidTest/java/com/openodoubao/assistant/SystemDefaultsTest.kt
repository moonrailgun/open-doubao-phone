package com.openodoubao.assistant

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.openodoubao.assistant.phone.AssistantService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SystemDefaultsTest {
    @Test fun systemImageGrantsRuntimePermissions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        assumeTrue(app.javaClass.name == "com.openodoubao.assistant.SystemApplication")
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS))
            assertEquals(permission, PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission))
    }

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
        // The framework removes service names that do not belong to an installed service.
        val other = app.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
            .first { it != own }.flattenToString()
        fun assertServices() {
            val components = Settings.Secure.getString(resolver, servicesKey).split(':')
                .map { ComponentName.unflattenFromString(it) }
            assertEquals(setOf(ComponentName.unflattenFromString(other), own), components.toSet())
            assertEquals(2, components.size)
        }
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
            assertServices()
            assertEquals(1, Settings.Secure.getInt(resolver, enabledKey))
            assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS"))

            // Android also persists fully qualified component names; startup must not duplicate them.
            val full = "$other:${own.flattenToString()}"
            write(full, "1")
            repeat(2) { instrumentation.runOnMainSync { app.onCreate() } }
            assertServices()
        } finally { write(originalServices, originalEnabled) }
    }
}
