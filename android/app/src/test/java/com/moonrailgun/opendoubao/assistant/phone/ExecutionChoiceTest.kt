package com.moonrailgun.opendoubao.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionChoiceTest {
    @Test fun theOldTwoWaySettingKeepsItsMeaningAndFreshInstallsChooseAutomatically() {
        assertEquals(EXECUTION_AUTO, executionFrom(null, null))
        assertEquals(EXECUTION_FOREGROUND, executionFrom(null, true))
        assertEquals(EXECUTION_VIRTUAL, executionFrom(null, false))
        assertEquals(EXECUTION_AUTO, executionFrom(EXECUTION_AUTO, false))
    }
}
