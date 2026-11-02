package com.moonrailgun.opendoubao.assistant.remote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import com.journeyapps.barcodescanner.ViewfinderView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Rounded viewfinder with corner brackets and a sweeping scan line, replacing zxing's static laser. */
class PairingViewfinderView(context: Context, attrs: AttributeSet?) : ViewfinderView(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val radius = 20 * dp
    private val inset = 14 * dp
    private val glow = 64 * dp
    private val animated = ValueAnimator.areAnimatorsEnabled()
    private val frame = RectF()
    private var built: Rect? = null
    private val hole = Path()
    private val corner = Path()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * dp; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint()

    private fun rebuild(rect: Rect) {
        built = Rect(rect)
        frame.set(rect)
        hole.reset()
        hole.addRoundRect(frame, radius, radius, Path.Direction.CW)
        val len = 30 * dp
        corner.reset()
        corner.moveTo(frame.left, frame.top + len)
        corner.lineTo(frame.left, frame.top + radius)
        corner.arcTo(frame.left, frame.top, frame.left + 2 * radius, frame.top + 2 * radius, 180f, 90f, false)
        corner.lineTo(frame.left + len, frame.top)
        // Fades the line and its glow out toward the left and right edges.
        fun edges() = LinearGradient(frame.left + inset, 0f, frame.right - inset, 0f,
            intArrayOf(CLEAR, ACCENT, ACCENT, CLEAR), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
        linePaint.shader = edges()
        glowPaint.shader = ComposeShader(
            LinearGradient(0f, -glow, 0f, 0f, CLEAR, CLEAR or 0x55000000, Shader.TileMode.CLAMP),
            edges(), PorterDuff.Mode.DST_IN)
    }

    override fun onDraw(canvas: Canvas) {
        refreshSizes()
        val rect = framingRect ?: return
        if (rect != built) rebuild(rect)

        canvas.save()
        canvas.clipOutPath(hole)
        canvas.drawColor(MASK)
        canvas.restore()

        for ((sx, sy) in MIRRORS) {
            canvas.save()
            canvas.scale(sx, sy, frame.centerX(), frame.centerY())
            canvas.drawPath(corner, cornerPaint)
            canvas.restore()
        }

        // One top-to-bottom sweep per period, easing at both ends and fading in/out.
        val t = if (animated) (SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat() else 0.5f
        val y = frame.top + inset + (frame.height() - 2 * inset) * ((1 - cos(PI * t)) / 2).toFloat()
        val alpha = (255 * sin(PI * t)).toInt()
        glowPaint.alpha = alpha
        linePaint.alpha = alpha
        canvas.save()
        canvas.clipPath(hole)
        canvas.translate(0f, y)
        canvas.drawRect(frame.left + inset, -glow, frame.right - inset, 0f, glowPaint)
        canvas.drawRect(frame.left + inset, -dp, frame.right - inset, dp, linePaint)
        canvas.restore()

        if (animated) postInvalidateOnAnimation()
    }

    private companion object {
        const val ACCENT = 0xFF4A9BEA.toInt() // MainActivity's Accent
        const val CLEAR = ACCENT and 0x00FFFFFF
        const val MASK = 0xA6000000.toInt()
        const val PERIOD_MS = 2200L
        val MIRRORS = listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)
    }
}
