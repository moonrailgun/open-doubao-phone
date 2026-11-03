package com.moonrailgun.opendoubao.assistant.phone

internal data class BubbleDock(val left: Int, val top: Int, val size: Int, val width: Int = size)

internal fun bubbleDock(width: Int, barHeight: Int, maxSize: Int, padding: Int,
    onRight: Boolean, cutouts: List<IntRange>, capsuleWidth: Int = 0): BubbleDock? {
    val size = minOf(maxSize, barHeight - 2 * padding)
    if (size <= 0) return null
    val dockWidth = maxOf(size, capsuleWidth)
    var left = if (onRight) width - padding - dockWidth else padding
    val obstacles = if (onRight) cutouts.sortedByDescending { it.last } else cutouts.sortedBy { it.first }
    for (cutout in obstacles) {
        if (left < cutout.last + 1 + padding && left + dockWidth > cutout.first - padding) {
            left = if (onRight) cutout.first - padding - dockWidth else cutout.last + 1 + padding
        }
    }
    if (left < padding || left + dockWidth > width - padding) return null
    return BubbleDock(left, (barHeight - size) / 2, size, dockWidth)
}
