package com.moonrailgun.opendoubao.assistant.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** 2026-10-15: captions read "打开飞书" while the VAD's own segment, which starts at its threshold, decoded as "海飞书". */
class UtteranceTest {
    private val heard = FloatArray(20_000) { it.toFloat() }

    @Test fun theFinalDecodeTakesTheCaptionsLeadIn() {
        val segment = heard.copyOfRange(10_000, 15_000)
        assertArrayEquals(heard.copyOfRange(3_600, 15_000), utterance(heard, 20_000, 10_000, segment), 0f)
    }

    @Test fun theLeadInStopsAtTheFirstSample() {
        val segment = heard.copyOfRange(3_000, 8_000)
        assertArrayEquals(heard.copyOfRange(0, 8_000), utterance(heard, 20_000, 3_000, segment), 0f)
    }

    @Test fun aSegmentPastWhatTheBufferHeldIsUsedAsIs() {
        val segment = FloatArray(5_000) { -1f }
        assertArrayEquals(segment, utterance(heard, 12_000, 10_000, segment), 0f)
    }
}
