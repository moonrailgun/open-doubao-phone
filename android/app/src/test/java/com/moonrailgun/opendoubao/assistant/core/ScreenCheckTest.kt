package com.moonrailgun.opendoubao.assistant.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ScreenCheckTest {
    @Test fun contentOverrideReachesScreenshotChildrenAndExpiresWithTheOperation() = runTest {
        val before = screen(text = "timer 1")
        val after = screen(text = "timer 2")
        assertTrue(before.matchesForAction(before))
        assertFalse(before.matchesForAction(after))
        val override = ScreenChangeOverride(true)
        withContext(override) {
            assertTrue(async { before.matchesForAction(after) }.await())
            assertTrue(before.canIgnoreChanges(before)) // Pixels can change without accessibility nodes changing.
            override.active = false
            assertFalse(before.matchesForAction(after))
            assertFalse(before.canIgnoreChanges(before))
        }
        withContext(ScreenChangeOverride(true)) { assertTrue(before.matchesForAction(after)) }
        assertFalse(before.matchesForAction(after))
    }

    @Test fun forceStillRejectsChangedAppWindowGeometryAndProtectedPages() = runTest {
        val before = screen()
        val blocked = listOf(
            before.copy(packageName = "other.app"), before.copy(windowId = 8),
            before.copy(width = 1920), before.copy(height = 1080), before.copy(rotation = 1),
            before.copy(windowBounds = Bounds(0, 100, 1080, 1920)),
            before.copy(elements = listOf(before.elements.single().copy(password = true))),
        )
        withContext(ScreenChangeOverride(true)) {
            for (after in blocked) {
                assertFalse(before.canIgnoreChanges(after))
                assertFalse(before.matchesForAction(after))
            }
            assertFalse(blocked.last().matchesForAction(blocked.last()))
        }
    }
}
