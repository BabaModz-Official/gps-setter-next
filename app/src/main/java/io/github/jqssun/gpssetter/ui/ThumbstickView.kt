package io.github.jqssun.gpssetter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class ThumbstickView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onMove: ((bearing: Float, strength: Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var range = 0f
    private var knobRadius = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var knobFill: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = min(w, h) / 2f
        knobRadius = radius * 0.36f
        range = radius * 0.54f
        if (knobRadius > 0f) {
            knobFill = RadialGradient(
                -knobRadius * 0.25f, -knobRadius * 0.35f, knobRadius * 1.35f,
                intArrayOf(0xff85b8ff.toInt(), 0xff3d82f6.toInt(), 0xff19418c.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) / 2f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        paint.color = 0xff121b29.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.96f, paint)
        paint.color = 0xff29384e.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.89f, paint)
        paint.color = 0xff1c283a.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.85f, paint)

        canvas.translate(offsetX, offsetY)
        paint.color = 0x66000000
        canvas.drawCircle(0f, knobRadius * 0.13f, knobRadius * 1.04f, paint)
        paint.color = Color.WHITE
        paint.shader = knobFill
        canvas.drawCircle(0f, 0f, knobRadius, paint)
        paint.shader = null
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(0f, 0f, knobRadius * 0.54f, paint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                move(event.getX(0), event.getY(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) move(event.getX(index), event.getY(index)) else release()
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) release()
            MotionEvent.ACTION_UP -> {
                release()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun move(x: Float, y: Float) {
        if (range <= 0f) return
        val dx = x - width / 2f
        val dy = y - height / 2f
        val distance = hypot(dx, dy)
        val scale = if (distance > range) range / distance else 1f
        offsetX = dx * scale
        offsetY = dy * scale
        val bearing = ((Math.toDegrees(atan2(dx, -dy).toDouble()) + 360.0) % 360.0).toFloat()
        val strength = ((distance / range - 0.12f) / 0.88f).coerceIn(0f, 1f)
        onMove?.invoke(bearing, strength)
        invalidate()
    }

    fun release() {
        if (pointer == MotionEvent.INVALID_POINTER_ID) return
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        offsetX = 0f
        offsetY = 0f
        onMove?.invoke(0f, 0f)
        invalidate()
    }
}
