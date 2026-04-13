package com.infusory.lib3drenderer.containerview.label

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws L-shaped connecting lines from 3D anchor points to label views.
 * Zero allocations per frame — uses flat float arrays.
 */
class LabelLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Flat array: each line = 4 floats [anchorX, anchorY, labelX, labelY]
    private var lineData = FloatArray(0)
    private var lineCount = 0

    private val linePaint = Paint().apply {
        color = Color.parseColor("#CC1B4F72")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val dotFillPaint = Paint().apply {
        color = Color.parseColor("#FF1B4F72")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dotStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun ensureCapacity(count: Int) {
        val needed = count * 4
        if (lineData.size < needed) {
            lineData = FloatArray(needed)
        }
        lineCount = count
    }

    fun setLineCount(count: Int) {
        lineCount = count
    }

    fun setLine(index: Int, anchorX: Float, anchorY: Float, labelX: Float, labelY: Float) {
        val offset = index * 4
        lineData[offset] = anchorX
        lineData[offset + 1] = anchorY
        lineData[offset + 2] = labelX
        lineData[offset + 3] = labelY
    }

    fun commitLines() {
        invalidate()
    }

    fun clear() {
        lineCount = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (i in 0 until lineCount) {
            val offset = i * 4
            val ax = lineData[offset]
            val ay = lineData[offset + 1]
            val lx = lineData[offset + 2]
            val ly = lineData[offset + 3]

            canvas.drawLine(ax, ay, lx, ay, linePaint)
            canvas.drawLine(lx, ay, lx, ly, linePaint)
            canvas.drawCircle(ax, ay, 5f, dotFillPaint)
            canvas.drawCircle(ax, ay, 5f, dotStrokePaint)
        }
    }
}
