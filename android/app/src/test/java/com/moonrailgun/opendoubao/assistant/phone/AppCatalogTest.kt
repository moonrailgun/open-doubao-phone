package com.moonrailgun.opendoubao.assistant.phone

import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    private val browser = InstalledApp("browser.app", "Browser", launchable = true)
    private val settings = InstalledApp("settings.app", "Settings", launchable = true)
    private val gms = InstalledApp("com.google.android.gms", "Google Play services", launchable = false)
    private val catalog = listOf(browser, settings, gms)

    @Test fun everyInstalledAppAndComponentIsAllowedByDefault() {
        // 2026-10-15: Settings' search page is a separate component, and a task paused there because it was not picked.
        assertEquals(setOf(browser.packageName, settings.packageName, gms.packageName), allowedApps(catalog, emptySet()))
    }

    @Test fun onlyExcludedAppsAreLeftOut() {
        assertEquals(setOf(settings.packageName, gms.packageName), allowedApps(catalog, setOf(browser.packageName, "removed.app")))
        assertEquals(emptySet<String>(), allowedApps(catalog, catalog.map { it.packageName }.toSet()))
    }

    @Test fun anAllowedListFromTheDesktopIsStoredAsTheAppsItLeavesOut() {
        // An app excluded earlier that is disabled or uninstalled right now stays excluded when it comes back.
        assertEquals(setOf(gms.packageName, "bank.app"),
            excludedApps(catalog, setOf(browser.packageName, settings.packageName), setOf("bank.app", browser.packageName)))
    }

    @Test fun unfilteredListOnlyShowsComponentsWhenRequested() {
        assertEquals(listOf(browser, settings), visibleAppCatalog(catalog, "", false))
        assertEquals(catalog, visibleAppCatalog(catalog, " ", true))
    }

    @Test fun explicitSearchFindsComponentsByNameOrPackageWithoutEnablingTheCategory() {
        assertEquals(listOf(gms), visibleAppCatalog(catalog, " GMS ", false))
        assertEquals(listOf(gms), visibleAppCatalog(catalog, "play SERVICES", false))
        assertEquals(listOf(browser), visibleAppCatalog(catalog, "Browser", true))
        assertEquals(emptyList<InstalledApp>(), visibleAppCatalog(catalog, "missing", true))
    }
}
