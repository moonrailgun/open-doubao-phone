package com.moonrailgun.opendoubao.assistant.core

import kotlin.math.abs

/**
 * Whether two captures of one page agree enough to tap or swipe on it. Byte-equal screenshots were required before, and a
 * blinking cursor in Amap's search field kept every coordinate tap from being sent (2026-10-16). Small changes away from
 * the gesture now pass; anything moving near it, or a page that changed broadly (a carousel, a new screen), still stops it.
 */
object ImageMatch {
    /** Brightness change, out of 255, that counts as a changed pixel; JPEG noise on an unchanged page stays well below. */
    const val STEP = 40
    /** Share of the image that may change elsewhere: a cursor or a clock, not a banner. */
    const val MAX_SHARE = 0.005
    /** Around the gesture, as a share of the image width, where nothing may change. */
    const val MARGIN = 0.08

    /** [a] and [b] hold [width] by [height] brightness values; [target] is the gesture's box in that same grid. */
    fun same(a: IntArray, b: IntArray, width: Int, height: Int, target: Bounds): Boolean {
        require(a.size == width * height && b.size == a.size)
        val margin = (width * MARGIN).toInt()
        val near = Bounds(target.left - margin, target.top - margin, target.right + margin, target.bottom + margin)
        var changed = 0
        for (i in a.indices) {
            if (abs(a[i] - b[i]) <= STEP) continue
            if (near.contains(i % width, i / width)) return false
            changed++
        }
        return changed <= a.size * MAX_SHARE
    }
}
