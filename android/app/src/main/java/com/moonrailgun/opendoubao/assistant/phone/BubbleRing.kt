package com.moonrailgun.opendoubao.assistant.phone

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/** The bubble's border: an arc turning while a task runs, a ring breathing during a voice conversation, still otherwise. */
class BubbleRing(private val density: Float) : Drawable() {
    enum class Mode { IDLE, RUNNING, PAUSED, VOICE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private var mode = Mode.IDLE
    private var color = Color.TRANSPARENT
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun show(mode: Mode, color: Int) {
        if (this.mode == mode && this.color == color) return
        this.mode = mode; this.color = color
        restart()
    }

    override fun draw(canvas: Canvas) {
        val stroke = if (mode == Mode.IDLE) maxOf(1f, density) else 3 * density
        paint.strokeWidth = stroke
        oval.set(bounds); oval.inset(stroke / 2, stroke / 2)
        when (mode) {
            // No elevation on the ball: the small overlay window clips a shadow into a dark edge, so a hairline outlines it.
            Mode.IDLE -> { paint.color = Color.argb(40, 0, 0, 0); canvas.drawOval(oval, paint) }
            Mode.PAUSED -> { paint.color = color; canvas.drawOval(oval, paint) }
            Mode.VOICE -> { paint.color = color; paint.alpha = (255 * (0.35f + 0.65f * phase)).toInt(); canvas.drawOval(oval, paint) }
            Mode.RUNNING -> {
                paint.color = color; paint.alpha = 70
                canvas.drawOval(oval, paint)
                paint.alpha = 255
                canvas.drawArc(oval, phase * 360f - 90f, 250f, false, paint)
            }
        }
    }

    private fun restart() {
        animator?.cancel(); animator = null
        val moving = mode == Mode.RUNNING || mode == Mode.VOICE
        if (moving && isVisible && ValueAnimator.areAnimatorsEnabled()) animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (mode == Mode.RUNNING) 1_100 else 1_600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = if (mode == Mode.RUNNING) ValueAnimator.RESTART else ValueAnimator.REVERSE
            interpolator = if (mode == Mode.RUNNING) LinearInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
            start()
        } else phase = if (mode == Mode.VOICE) 1f else 0.25f
        invalidateSelf()
    }

    /** The view reports its visibility here, so a bubble folded away behind the card or panel stops animating. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean = super.setVisible(visible, restart).also { if (it) restart() }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
