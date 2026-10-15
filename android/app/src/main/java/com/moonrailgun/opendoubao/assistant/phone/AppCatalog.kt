package com.moonrailgun.opendoubao.assistant.phone

import android.content.Context
import android.content.Intent

data class InstalledApp(val packageName: String, val label: String, val launchable: Boolean)

/**
 * Never offered or allowed: their screens grant permissions, install or remove apps, or let an app automate others.
 * A task that reaches one pauses for the user, as it did before every component was allowed by default.
 */
private val PROTECTED = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.virtualdevicemanager")

fun loadAppCatalog(context: Context): List<InstalledApp> {
    val packages = context.packageManager
    // Match getLaunchIntentForPackage, which also accepts CATEGORY_INFO entry points.
    val launchable = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO).flatMap { category ->
        packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo.packageName }
    }.toSet()
    return packages.getInstalledApplications(0)
        .filter { it.enabled && it.packageName != context.packageName && it.packageName !in PROTECTED }
        .map { InstalledApp(it.packageName, it.loadLabel(packages).toString(), it.packageName in launchable) }
        .sortedWith(compareBy<InstalledApp> { it.label }.thenBy { it.packageName })
}

/** Every installed app and component except the ones the user left out; an uninstalled one there no longer matters. */
fun allowedApps(catalog: List<InstalledApp>, excluded: Set<String>): Set<String> = catalog.map { it.packageName }.toSet() - excluded

/**
 * What a list of allowed apps (the desktop's apps.set) leaves out, which is what is stored. Apps excluded before that are
 * disabled or uninstalled right now are not in [catalog] and stay excluded, so they are not opened up when they return.
 */
fun excludedApps(catalog: List<InstalledApp>, allowed: Set<String>, before: Set<String>): Set<String> =
    catalog.map { it.packageName }.toSet().let { installed -> before - installed + (installed - allowed) }

fun visibleAppCatalog(catalog: List<InstalledApp>, query: String, showSystemComponents: Boolean): List<InstalledApp> {
    val term = query.trim()
    return catalog.filter {
        if (term.isEmpty()) it.launchable || showSystemComponents
        else it.label.contains(term, ignoreCase = true) || it.packageName.contains(term, ignoreCase = true)
    }
}
