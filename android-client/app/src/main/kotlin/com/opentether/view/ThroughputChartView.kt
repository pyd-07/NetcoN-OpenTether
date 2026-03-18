package com.opentether.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Draws two scrolling polylines (download ↓ green, upload ↑ purple) on a
 * transparent dark background, matching the SVG chart in the HTML design.
 *
 * Usage:
 *   chartView.addDataPoint(downloadMbps, uploadMbps)   // call every second
 */
class ThroughputChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 30

    private val dlPoints = ArrayDeque<Float>()  // download (green)
    private val ulPoints = ArrayDeque<Float>()  // upload   (purple)

    private val dlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#1D9E75")
        strokeWidth = 3f * resources.displayMetrics.density
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val ulPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#534AB7")
        strokeWidth = 3f * resources.displayMetrics.density
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val dlPath = Path()
    private val ulPath = Path()

    // Seed with flat zero values so the chart looks populated on first render.
    init {
        repeat(maxPoints) {
            dlPoints.addLast(0f)
            ulPoints.addLast(0.02f)   // tiny non-zero keeps upload line visible
        }
    }

    /** Add one data point. Drops the oldest point when the buffer is full. */
    fun addDataPoint(downloadMbps: Float, uploadMbps: Float) {
        dlPoints.addLast(downloadMbps)
        ulPoints.addLast(uploadMbps)
        if (dlPoints.size > maxPoints) dlPoints.removeFirst()
        if (ulPoints.size > maxPoints) ulPoints.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f || dlPoints.size < 2) return

        // Dynamic ceiling: whichever line is highest, with a 10 % margin.
        val peak  = maxOf(dlPoints.max(), ulPoints.max(), 0.1f)
        val scale = h * 0.88f / peak

        fun buildPath(points: ArrayDeque<Float>, path: Path) {
            path.reset()
            val step  = w / (maxPoints - 1).coerceAtLeast(1)
            val start = (maxPoints - points.size) * step
            points.forEachIndexed { i, v ->
                val x = start + i * step
                val y = h - v * scale - h * 0.06f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }

        buildPath(dlPoints, dlPath)
        buildPath(ulPoints, ulPath)

        canvas.drawPath(dlPath, dlPaint)
        canvas.drawPath(ulPath, ulPaint)
    }
}