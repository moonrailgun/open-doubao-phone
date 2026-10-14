package com.moonrailgun.opendoubao.assistant.phone

import com.moonrailgun.opendoubao.assistant.core.PhoneUnavailableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitPackageTest {
    private fun TestScope.clock() = { currentTime }

    @Test fun aColdStartsUnreadableSplashIsWaitedOut() = runTest {
        // Feishu on 2026-10-13: its splash had nothing readable for 1.3 s, past the 1 s a single read allows.
        var reads = 0
        val arrived = awaitPackage("com.ss.android.lark", 5_000, {}, {
            if (++reads <= 13) throw PhoneUnavailableException("无法读取当前页面的屏幕内容") else "com.ss.android.lark"
        }, clock())
        assertTrue(arrived)
        assertEquals(14, reads)
    }

    @Test fun anotherAppInFrontWaitsTheWholeTimeoutWithoutFailing() = runTest {
        assertFalse(awaitPackage("com.ss.android.lark", 5_000, {}, { "com.android.launcher3" }, clock()))
        assertEquals(5_000, currentTime)
    }

    @Test fun aLockedPhoneStillStopsAtOnce() = runTest {
        val error = runCatching {
            awaitPackage("com.ss.android.lark", 5_000, { throw PhoneUnavailableException("手机已锁定或息屏") }, { "x" }, clock())
        }.exceptionOrNull()
        assertTrue(error is PhoneUnavailableException)
        assertEquals(0, currentTime)
    }
}
