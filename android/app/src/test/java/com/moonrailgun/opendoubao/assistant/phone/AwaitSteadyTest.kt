package com.moonrailgun.opendoubao.assistant.phone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Chrome on the virtual display, 2026-10-14: every click waited the full 2.5 s, as the session's stability listener never fires after node actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitSteadyTest {
    @Test fun aPageIsReadyOnceItHoldsStillForAQuietSpell() = runTest {
        var reads = 0
        // The page changes on the first three polls, then holds from 300 ms.
        awaitSteady({ minOf(++reads, 4) }, now = { currentTime })
        assertEquals(600, currentTime)
    }

    @Test fun aPageThatKeepsChangingStopsAtTheCap() = runTest {
        var reads = 0
        awaitSteady({ ++reads }, now = { currentTime })
        assertEquals(1_500, currentTime)
    }
}
