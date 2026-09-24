package com.example.aiwebtabautomator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SetupOverlayView(context: Context) : View(context) {
    var onPointSelected: ((step: Int, x: Float, y: Float) -> Unit)? = null

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private var firstPoint: Pair<Float, Float>? = null
    private var step = 1

    fun reset() {
        firstPoint = null
        step = 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val label = if (step == 1) {
            "Tap the CENTER of the text input"
        } else {
            "Tap the CENTER of the send button"
        }
        canvas.drawText(label, width / 2f, max(48f, height / 2f - 24f), textPaint)
        firstPoint?.let { canvas.drawCircle(it.first, it.second, 22f, markerPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = min(width.toFloat(), max(0f, event.x))
        val y = min(height.toFloat(), max(0f, event.y))
        onPointSelected?.invoke(step, x, y)
        if (step == 1) {
            firstPoint = x to y
            step = 2
            invalidate()
        } else {
            // The Activity hides this view after saving the second point.
            invalidate()
        }
        return true
    }
}
