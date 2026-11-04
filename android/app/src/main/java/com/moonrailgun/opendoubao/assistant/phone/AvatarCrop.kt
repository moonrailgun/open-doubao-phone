package com.moonrailgun.opendoubao.assistant.phone

internal data class AvatarCrop(val left: Float, val top: Float, val size: Float) {
    fun transform(
        width: Int, height: Int, viewport: Float, panX: Float, panY: Float,
        zoom: Float = 1f, focusX: Float = viewport / 2, focusY: Float = viewport / 2,
    ): AvatarCrop {
        if (viewport <= 0f) return this
        val shortest = minOf(width, height).toFloat()
        val nextSize = (size / zoom).coerceIn(maxOf(1f, shortest / 5), shortest)
        return AvatarCrop(
            (left + focusX / viewport * (size - nextSize) - panX / viewport * nextSize).coerceIn(0f, width - nextSize),
            (top + focusY / viewport * (size - nextSize) - panY / viewport * nextSize).coerceIn(0f, height - nextSize),
            nextSize,
        )
    }

    companion object {
        fun centered(width: Int, height: Int): AvatarCrop {
            val size = minOf(width, height).toFloat()
            return AvatarCrop((width - size) / 2, (height - size) / 2, size)
        }
    }
}
