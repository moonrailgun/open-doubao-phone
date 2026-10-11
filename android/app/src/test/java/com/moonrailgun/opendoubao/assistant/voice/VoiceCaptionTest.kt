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

    @Test fun captionsKeepTheirHeadAndDropTrailingPunctuationAcrossPasses() {
        assertEquals("帮我看一下明", mergeCaption("帮我看一下", "帮我看一下明。"))
        // The next pass lost the first syllable but heard more: keep the head, take the new tail.
        assertEquals("帮我看一下明天上午", mergeCaption("帮我看一下明", "我看一下明天上午，"))
        // A pass that regains the head simply wins.
        assertEquals("帮我看一下明天", mergeCaption("我看一下明", "帮我看一下明天"))
        // Unrelated text replaces the caption; nothing is invented from an empty or one-character history.
        assertEquals("查天气", mergeCaption("那我看", "查天气。"))
        assertEquals("我", mergeCaption("帮", "我"))
        assertEquals("", mergeCaption("帮我", "。"))
        // A repeated head is an extension, never a lost syllable.
        assertEquals("哈哈", mergeCaption("哈哈", "哈哈"))
        assertEquals("查一查天气", mergeCaption("查一查", "查一查天气。"))
        assertEquals("帮我看一下", mergeCaption("帮我看一下明天", "帮我看一下"))
    }

    @Test fun levelIsSilentForSilenceAndSaturatesForShouting() {
        assertEquals(0f, voiceLevel(FloatArray(0)), 0f)
        assertEquals(0f, voiceLevel(FloatArray(512)), 0f)
        assertEquals(1f, voiceLevel(FloatArray(512) { if (it % 2 == 0) .5f else -.5f }), 0f)
        val speech = voiceLevel(FloatArray(512) { if (it % 2 == 0) .05f else -.05f })
        assertTrue(speech in 0.4f..0.9f)
    }
}
