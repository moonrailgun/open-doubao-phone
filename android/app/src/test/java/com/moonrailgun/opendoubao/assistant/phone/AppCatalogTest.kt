package com.moonrailgun.opendoubao.assistant.phone

import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    private val browser = InstalledApp("browser.app", "Browser", launchable = true)
    private val settings = InstalledApp("settings.app", "Settings", launchable = true)
    private val gms = InstalledApp("com.google.android.gms", "Google Play services", launchable = false)
    private val catalog = listOf(browser, settings, gms)

    @Test fun defaultsOnlySelectLaunchableApps() {
        assertEquals(setOf(browser.packageName, settings.packageName), catalogSelection(catalog, emptySet(), false))
    }

    @Test fun savedComponentsRemainSelectedAndUninstalledPackagesAreRemoved() {
        assertEquals(setOf(gms.packageName), catalogSelection(catalog, setOf(gms.packageName, "removed.app"), true))
        assertEquals(emptySet<String>(), catalogSelection(catalog, emptySet(), true))
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
