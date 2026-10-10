package com.moonrailgun.opendoubao.assistant

import com.moonrailgun.opendoubao.assistant.voice.VoiceRequests
import com.moonrailgun.opendoubao.assistant.voice.speechText
import org.junit.Assert.*
import org.junit.Test

class VoiceRequestsTest {
    @Test fun onlyOneRequestOwnsTheMicrophoneUntilItHasReleasedIt() {
        val requests = VoiceRequests()
        val first = requests.begin()!!
        assertNull(requests.begin())
        requests.cancel()
        assertFalse(requests.accepts(first))
        // Cancellation does not let the next caller race the old recorder's cleanup.
        assertNull(requests.begin())
        assertFalse(requests.finish(first))
        val next = requests.begin()!!
        assertNotEquals(first, next)
        assertFalse(requests.finish(first))
        requests.cancel(first)
        assertTrue(requests.accepts(next))
        assertTrue(requests.finish(next))
    }

    @Test fun speechUsesPlainTextAndTruncatesAtAUnicodeCharacterBoundary() {
        assertEquals("结果：完成", speechText("**结果：**完成"))
        assertEquals("😀".repeat(300) + "。更多内容请查看全文。", speechText("😀".repeat(301)))
        assertEquals("豆".repeat(300), speechText("豆".repeat(300)))
    }
}
