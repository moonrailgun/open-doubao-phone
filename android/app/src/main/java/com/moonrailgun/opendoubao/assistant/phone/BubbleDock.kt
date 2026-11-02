package com.moonrailgun.opendoubao.assistant.phone

internal data class BubbleDock(val left: Int, val top: Int, val size: Int)

internal fun bubbleDock(width: Int, barHeight: Int, maxSize: Int, padding: Int,
    onRight: Boolean, cutouts: List<IntRange>): BubbleDock? {
    val size = minOf(maxSize, barHeight - 2 * padding)
    if (size <= 0) return null
    var left = if (onRight) width - padding - size else padding
    val obstacles = if (onRight) cutouts.sortedByDescending { it.last } else cutouts.sortedBy { it.first }
    for (cutout in obstacles) {
        if (left < cutout.last + 1 + padding && left + size > cutout.first - padding) {
            left = if (onRight) cutout.first - padding - size else cutout.last + 1 + padding
        }
    }
    if (left < padding || left + size > width - padding) return null
    return BubbleDock(left, (barHeight - size) / 2, size)
}
