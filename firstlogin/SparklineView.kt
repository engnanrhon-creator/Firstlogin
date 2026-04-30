package com.example.firstlogin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ===== DRAW CONFIGURATION =====
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        color = ContextCompat.getColor(context, R.color.stat_accent)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.stat_accent)
    }

    private val path = Path()
    private var data: List<Float> = emptyList()

    // Update sparkline source data and redraw.
    fun setData(values: List<Float>) {
        data = values
        invalidate()
    }

    // Theme helper for per-metric chart colors.
    fun setLineColor(color: Int) {
        linePaint.color = color
        dotPaint.color = color
        invalidate()
    }

    // Draw normalized line chart (or single-point dot when only one value exists).
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val minVal = data.minOrNull() ?: return
        val maxVal = data.maxOrNull() ?: return
        val range = if (maxVal - minVal < 0.0001f) 1f else (maxVal - minVal)

        path.reset()
        val count = max(1, data.size - 1)
        var lastX = 0f
        var lastY = 0f
        data.forEachIndexed { index, value ->
            val x = paddingLeft + (w * index.toFloat() / count.toFloat())
            val normalized = (value - minVal) / range
            val y = paddingTop + h - (h * normalized)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }

        if (data.size > 1) {
            canvas.drawPath(path, linePaint)
        } else {
            val radius = resources.displayMetrics.density * 3f
            canvas.drawCircle(lastX, lastY, radius, dotPaint)
        }
    }
}
