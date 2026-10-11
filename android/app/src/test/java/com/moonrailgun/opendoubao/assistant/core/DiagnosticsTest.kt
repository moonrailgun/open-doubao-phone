package com.moonrailgun.opendoubao.assistant.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun keepsOnlyTheNewestEventsInOrder() {
        repeat(Diagnostics.LIMIT + 5) { Diagnostics.log("event $it") }
        val events = Diagnostics.events.value
        assertEquals(Diagnostics.LIMIT, events.size)
        assertTrue(events.first().endsWith("event 5"))
        assertTrue(events.last().endsWith("event ${Diagnostics.LIMIT + 4}"))
    }
}
