package com.moonrailgun.opendoubao.assistant.phone

import org.junit.Assert.*
import org.junit.Test

class BubbleDockTest {
    @Test fun capsuleUsesItsFullWidthWhenAvoidingCamerasAndScreenEdges() {
        assertEquals(BubbleDock(298, 2, 20, 100), bubbleDock(400, 24, 28, 2, true, emptyList(), 100))
        assertEquals(BubbleDock(248, 10, 28, 100), bubbleDock(400, 48, 28, 2, true, listOf(350..399), 100))
        assertEquals(BubbleDock(52, 10, 28, 100), bubbleDock(400, 48, 28, 2, false, listOf(0..49), 100))
        assertNull(bubbleDock(120, 48, 28, 2, true, listOf(50..70), 100))
    }

    @Test fun fitsInsideShortStatusBar() {
        assertEquals(BubbleDock(378, 2, 20), bubbleDock(400, 24, 28, 2, true, emptyList()))
    }

    @Test fun avoidsCornerCameraOnEitherSide() {
        assertEquals(BubbleDock(52, 10, 28), bubbleDock(400, 48, 28, 2, false, listOf(0..49)))
        assertEquals(BubbleDock(320, 10, 28), bubbleDock(400, 48, 28, 2, true, listOf(350..399)))
    }

    @Test fun centralCameraDoesNotMoveEdgeAvatar() {
        assertEquals(BubbleDock(370, 10, 28), bubbleDock(400, 48, 28, 2, true, listOf(180..220)))
    }

    @Test fun unavailableSpaceDoesNotDock() {
        assertNull(bubbleDock(400, 0, 28, 2, true, emptyList()))
        assertNull(bubbleDock(400, 48, 28, 2, false, listOf(0..399)))
    }
}
