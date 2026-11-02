package com.moonrailgun.opendoubao.assistant.phone

import android.view.accessibility.AccessibilityWindowInfo
import com.moonrailgun.opendoubao.assistant.core.Bounds
import com.moonrailgun.opendoubao.assistant.core.PhoneUnavailableException
import com.moonrailgun.opendoubao.assistant.core.ScreenChangedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotWindowsTest {
    @Test fun aHiddenOverlayCanDisappearAfterTheFirstWindowCheck() = runTest {
        val target = awaitScreenshotReady {
            if (currentTime < 250) throw PhoneUnavailableException("overlay still listed")
            "current app"
        }
        assertEquals("current app", target)
        assertTrue(currentTime in 250..1_000)
    }

    @Test fun aPersistentBlockerStillStopsCaptureWithFreshDiagnostics() = runTest {
        try {
            awaitScreenshotReady {
                throw PhoneUnavailableException("blocked", if (currentTime < 250) "old overlay" else "keyboard")
            }
            fail("A real blocker must not be ignored")
        } catch (e: PhoneUnavailableException) {
            assertEquals("keyboard", e.diagnostics)
            assertTrue(currentTime in 250..1_000)
        }
    }

    @Test fun changingTheAppWhileWaitingAbortsCapture() = runTest {
        try {
            awaitScreenshotReady {
                if (currentTime == 0L) throw PhoneUnavailableException("overlay still listed")
                throw ScreenChangedException()
            }
            fail("A different screen must not be captured")
        } catch (_: ScreenChangedException) {
            assertTrue(currentTime in 1..250)
        }
    }

    private fun navigation(title: String? = "GestureNavLeft", bounds: Bounds = Bounds(0, 122, 54, 2700),
        manufacturer: String = "HUAWEI", type: Int = AccessibilityWindowInfo.TYPE_SYSTEM,
        packageName: String? = "android", active: Boolean = false, focused: Boolean = false,
        width: Int = 1228, height: Int = 2700) =
        isHuaweiGestureNavigation(manufacturer, type, title, packageName, active, focused, bounds, width, height)

    @Test fun transparentHuaweiGestureRegionsDoNotBlockScreenshots() {
        // Bounds captured from the affected Android 12 device, not inferred from the classifier.
        assertTrue(navigation())
        assertTrue(navigation("GestureNavRight", Bounds(1174, 122, 1228, 2700)))
        assertTrue(navigation("GestureNavBottom", Bounds(135, 2626, 1093, 2700)))
        assertTrue(navigation(packageName = null))
    }

    @Test fun navigationRegionsAlsoWorkAfterDisplayRotation() {
        assertTrue(navigation(bounds = Bounds(0, 0, 54, 1228), width = 2700, height = 1228))
        assertTrue(navigation("GestureNavRight", Bounds(2646, 0, 2700, 1228), width = 2700, height = 1228))
        assertTrue(navigation("GestureNavBottom", Bounds(135, 1154, 2565, 1228), width = 2700, height = 1228))
    }

    @Test fun realKeyboardAndOverlaysStayBlockedEvenWithNavigationTitle() {
        for (type in listOf(AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)) {
            assertFalse(navigation(type = type))
        }
        assertFalse(navigation(packageName = "com.example.overlay"))
        assertFalse(navigation(active = true))
        assertFalse(navigation(focused = true))
        assertFalse(navigation(manufacturer = "other"))
        assertFalse(navigation(title = "NotificationShade"))
        assertFalse(navigation(title = null))
    }

    @Test fun onlyNarrowRegionsAtTheMatchingDisplayEdgeAreExempt() {
        assertFalse(navigation(bounds = Bounds(0, 0, 1228, 2700)))
        assertFalse(navigation(bounds = Bounds(54, 122, 108, 2700)))
        assertFalse(navigation("GestureNavRight", Bounds(0, 122, 54, 2700)))
        assertFalse(navigation("GestureNavBottom", Bounds(135, 1000, 1093, 2700)))
        assertFalse(navigation("GestureNavBottom", Bounds(135, 2552, 1093, 2626)))
        assertFalse(navigation(bounds = Bounds(0, 122, 54, 122)))
        assertFalse(navigation(bounds = Bounds(-1, 122, 54, 2700)))
        assertFalse(navigation(width = 0))
        assertFalse(navigation(height = 0))
    }
}
