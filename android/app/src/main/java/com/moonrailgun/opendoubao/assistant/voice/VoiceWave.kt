package com.moonrailgun.opendoubao.assistant.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Siri-style flowing lines whose height follows [level]; drawn still when system animations are off. */
class VoiceWaveView(context: Context) : View(context) {
    /** Microphone level 0..1; the lines ease toward it. */
    var level = 0f

    private class Wave(val color: Int, val frequency: Float, val speed: Float, val share: Float)
    private val waves = listOf(
        Wave(Color.argb(190, 74, 155, 234), 1.5f, 4.2f, 1f),
        Wave(Color.argb(170, 236, 92, 164), 2.1f, -3.1f, 0.8f),
        Wave(Color.argb(150, 62, 213, 200), 1.1f, 2.4f, 0.65f),
        Wave(Color.argb(220, 255, 255, 255), 1.8f, 5.3f, 0.4f),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2 * resources.displayMetrics.density
    }
    private val path = Path()
    private var eased = 0f

    override fun onDraw(canvas: Canvas) {
        val animating = ValueAnimator.areAnimatorsEnabled()
        eased = if (animating) eased + (level.coerceIn(0f, 1f) - eased) * 0.2f else 0.35f
        val time = if (animating) SystemClock.uptimeMillis() / 1000f else 0f
        val middle = height / 2f
        // A quiet floor keeps the lines alive while waiting; speech lifts them to the full height.
        val amplitude = (middle - paint.strokeWidth) * (0.08f + 0.92f * eased)
        waves.forEach { wave ->
            path.reset()
            var x = 0f
            while (x <= width) {
                val t = x / width * 4 - 2
                // Pinches both ends to a point, like Siri's classic wave.
                val envelope = (4 / (4 + t.pow(4))).pow(4)
                val y = middle + amplitude * wave.share * envelope * sin(wave.frequency * t * PI.toFloat() - time * wave.speed)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 3f
            }
            paint.color = wave.color
            canvas.drawPath(path, paint)
        }
        if (animating) postInvalidateOnAnimation()
    }
}

/** One typewriter tick toward [target]: reveal the next character, or back up to the shared prefix when recognition rewrote it. */
internal fun typewriterStep(shown: String, target: String): String =
    if (target.startsWith(shown)) target.take(shown.length + 1) else shown.commonPrefixWith(target)
