package com.moonrailgun.opendoubao.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCaptionTest {
    @Test fun captionsTypeOneCharacterPerTickAndBackUpWhenRewritten() {
        val ticks = generateSequence("") { shown -> typewriterStep(shown, "查天气").takeIf { it != shown } }.toList()
        assertEquals(listOf("", "查", "查天", "查天气"), ticks)
        // A later decode rewrote "天气" as "天津": back up to the shared prefix, then type forward again.
        assertEquals("查天", typewriterStep("查天气", "查天津的"))
        assertEquals("查天津", typewriterStep("查天", "查天津的"))
        assertEquals("", typewriterStep("你好", "帮我"))
        assertEquals("查天气", typewriterStep("查天气", "查天气"))
    }

    @Test fun levelIsSilentForSilenceAndSaturatesForShouting() {
        assertEquals(0f, voiceLevel(FloatArray(0)), 0f)
        assertEquals(0f, voiceLevel(FloatArray(512)), 0f)
        assertEquals(1f, voiceLevel(FloatArray(512) { if (it % 2 == 0) .5f else -.5f }), 0f)
        val speech = voiceLevel(FloatArray(512) { if (it % 2 == 0) .05f else -.05f })
        assertTrue(speech in 0.4f..0.9f)
    }
}
