package com.moonrailgun.opendoubao.assistant.core

import org.junit.Assert.*
import org.junit.Test

class ImageMatchTest {
    private val w = 100
    private val h = 200
    private fun page() = IntArray(w * h) { 200 }
    private fun IntArray.paint(left: Int, top: Int, right: Int, bottom: Int, value: Int = 30) = also {
        for (y in top until bottom) for (x in left until right) this[y * w + x] = value
    }
    private val tap = Bounds(50, 150, 51, 151)

    @Test fun identicalAndNoisyPagesMatch() {
        assertTrue(ImageMatch.same(page(), page(), w, h, tap))
        assertTrue(ImageMatch.same(page(), IntArray(w * h) { 200 + it % 3 * 10 }, w, h, tap))
    }

    @Test fun aBlinkingCursorAwayFromTheTapDoesNotBlockIt() {
        assertTrue(ImageMatch.same(page(), page().paint(20, 10, 21, 25), w, h, tap))
    }

    @Test fun changesNearTheTapOrAcrossThePageBlockIt() {
        assertFalse(ImageMatch.same(page(), page().paint(54, 152, 56, 154), w, h, tap))
        assertFalse(ImageMatch.same(page(), page().paint(0, 0, 100, 40), w, h, tap))
    }
}
