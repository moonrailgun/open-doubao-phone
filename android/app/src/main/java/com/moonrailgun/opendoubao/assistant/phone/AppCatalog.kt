package com.moonrailgun.opendoubao.assistant.phone

import android.content.Context
import android.content.Intent

data class InstalledApp(val packageName: String, val label: String, val launchable: Boolean)

fun loadAppCatalog(context: Context): List<InstalledApp> {
    val packages = context.packageManager
    // Match getLaunchIntentForPackage, which also accepts CATEGORY_INFO entry points.
    val launchable = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO).flatMap { category ->
        packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo.packageName }
    }.toSet()
    return packages.getInstalledApplications(0)
        .filter { it.enabled && it.packageName != context.packageName }
        .map { InstalledApp(it.packageName, it.loadLabel(packages).toString(), it.packageName in launchable) }
        .sortedWith(compareBy<InstalledApp> { it.label }.thenBy { it.packageName })
}

fun catalogSelection(catalog: List<InstalledApp>, savedApps: Set<String>, appsConfigured: Boolean): Set<String> =
    if (appsConfigured) savedApps.intersect(catalog.map { it.packageName }.toSet())
    else catalog.filter { it.launchable }.map { it.packageName }.toSet()

fun visibleAppCatalog(catalog: List<InstalledApp>, query: String, showSystemComponents: Boolean): List<InstalledApp> {
    val term = query.trim()
    return catalog.filter {
        if (term.isEmpty()) it.launchable || showSystemComponents
        else it.label.contains(term, ignoreCase = true) || it.packageName.contains(term, ignoreCase = true)
    }
}
