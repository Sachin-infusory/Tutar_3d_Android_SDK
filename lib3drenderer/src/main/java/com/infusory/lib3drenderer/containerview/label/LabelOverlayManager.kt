package com.infusory.lib3drenderer.containerview.label

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.filament.Camera
import kotlin.math.abs

/**
 * Manages label overlays on top of the Filament SurfaceView inside Container3D.
 *
 * Labels are extracted from glTF node extras.prop and rendered as Android TextViews
 * positioned on the sides of the container with connecting lines to the 3D anchor points.
 *
 * Supports live animated positions via optional livePositions parameter.
 * All projection math uses the Filament Camera directly — no SceneView dependency.
 * Zero allocations per frame.
 */
class LabelOverlayManager(private val context: Context) {

    private val TAG = "LabelOverlayManager"

    private class LabelEntry(
        val info: GlbLabelExtractor.LabelInfo,
        val view: TextView,
        var measuredWidth: Int = 0,
        var measuredHeight: Int = 0,
        var measured: Boolean = false
    )

    private var overlayContainer: FrameLayout? = null
    private var lineView: LabelLineView? = null
    private val entries = mutableListOf<LabelEntry>()
    private var isVisible = false
    private var labels: List<GlbLabelExtractor.LabelInfo> = emptyList()

    // Positioning
    private val SIDE_MARGIN = dpToPx(12f)
    private val LABEL_SPACING = dpToPx(4f)
    private val MAX_OFFSET = dpToPx(100f)
    private val TOP_MARGIN = dpToPx(8f)
    private val BOTTOM_MARGIN = dpToPx(8f)

    // Pre-allocated for projection (zero GC per frame)
    private val _viewMatrix = FloatArray(16)
    private val _projMatrix = FloatArray(16)
    private val _viewProj = FloatArray(16)
    private val _viewDouble = DoubleArray(16)
    private val _projDouble = DoubleArray(16)
    private val _screenPoint = PointF()

    // Reusable overlap lists
    private val usedLeftY = mutableListOf<Float>()
    private val usedRightY = mutableListOf<Float>()

    // ─── Setup ───────────────────────────────────────────────────────────────

    fun attachTo(contentContainer: FrameLayout) {
        lineView = LabelLineView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        contentContainer.addView(lineView)

        overlayContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            visibility = View.GONE
        }
        contentContainer.addView(overlayContainer)
    }

    fun setLabels(newLabels: List<GlbLabelExtractor.LabelInfo>) {
        clearLabelViews()
        labels = newLabels

        if (newLabels.isEmpty()) return

        val container = overlayContainer ?: return
        lineView?.ensureCapacity(newLabels.size)

        val labelBg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC1B4F72"))
            cornerRadius = dpToPx(6f)
            setStroke(1, Color.parseColor("#4DFFFFFF"))
        }

        newLabels.forEach { labelInfo ->
            val tv = TextView(context).apply {
                text = labelInfo.text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dpToPx(10f).toInt(), dpToPx(5f).toInt(), dpToPx(10f).toInt(), dpToPx(5f).toInt())
                maxWidth = dpToPx(160f).toInt()
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = (labelBg.constantState?.newDrawable()?.mutate()) ?: GradientDrawable().apply {
                    setColor(Color.parseColor("#CC1B4F72"))
                    cornerRadius = dpToPx(6f)
                }
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(tv)
            entries.add(LabelEntry(info = labelInfo, view = tv))
        }

        Log.d(TAG, "Set ${entries.size} labels")
    }

    fun hasLabels(): Boolean = labels.isNotEmpty()

    // ─── Visibility ──────────────────────────────────────────────────────────

    fun showLabels() {
        if (labels.isEmpty()) return
        isVisible = true
        overlayContainer?.visibility = View.VISIBLE
        lineView?.visibility = View.VISIBLE
    }

    fun hideLabels() {
        isVisible = false
        overlayContainer?.visibility = View.GONE
        lineView?.visibility = View.GONE
        lineView?.clear()
    }

    fun toggleLabels() {
        if (isVisible) hideLabels() else showLabels()
    }

    fun isLabelsVisible(): Boolean = isVisible

    // ─── Per-Frame Update ────────────────────────────────────────────────────

    /**
     * Update label positions each frame.
     *
     * @param camera Filament Camera for projection
     * @param viewportWidth Current viewport width in pixels
     * @param viewportHeight Current viewport height in pixels
     * @param livePositions Optional list of current world positions (from TransformManager).
     *                      If provided, uses these instead of static localPosition from GLB JSON.
     *                      Must be same size as labels list. Each entry is [x, y, z].
     */
    fun updatePositions(
        camera: Camera?,
        viewportWidth: Int,
        viewportHeight: Int,
        livePositions: List<FloatArray>? = null
    ) {
        if (!isVisible || entries.isEmpty() || camera == null) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        // Compute view-projection matrix ONCE per frame
        try {
            camera.getViewMatrix(_viewDouble)
            camera.getProjectionMatrix(_projDouble)
            for (i in 0..15) {
                _viewMatrix[i] = _viewDouble[i].toFloat()
                _projMatrix[i] = _projDouble[i].toFloat()
            }
            android.opengl.Matrix.multiplyMM(_viewProj, 0, _projMatrix, 0, _viewMatrix, 0)
        } catch (e: Exception) {
            return
        }

        val vp = _viewProj
        val wf = viewportWidth.toFloat()
        val hf = viewportHeight.toFloat()
        val centerX = wf / 2f

        usedLeftY.clear()
        usedRightY.clear()

        var visibleCount = 0

        for (i in entries.indices) {
            val entry = entries[i]

            // Use live animated position if available, otherwise fall back to static
            val pos = livePositions?.getOrNull(i) ?: entry.info.localPosition

            // Project 3D → 2D (inline, no method call)
            val x = pos[0]; val y = pos[1]; val z = pos[2]
            val clipW = vp[3]*x + vp[7]*y + vp[11]*z + vp[15]

            if (clipW <= 0f) {
                entry.view.visibility = View.INVISIBLE
                continue
            }

            val invW = 1f / clipW
            val clipX = vp[0]*x + vp[4]*y + vp[8]*z + vp[12]
            val clipY = vp[1]*x + vp[5]*y + vp[9]*z + vp[13]
            val screenX = (clipX * invW * 0.5f + 0.5f) * wf
            val screenY = (1f - (clipY * invW * 0.5f + 0.5f)) * hf

            // Hide if off-screen
            if (screenX < -100 || screenX > wf + 100 || screenY < -100 || screenY > hf + 100) {
                entry.view.visibility = View.INVISIBLE
                continue
            }

            // Lazy measure
            if (!entry.measured && entry.view.width > 0) {
                entry.measuredWidth = entry.view.width
                entry.measuredHeight = entry.view.height
                entry.measured = true
            }
            val lw = if (entry.measured) entry.measuredWidth.toFloat() else dpToPx(80f)
            val lh = if (entry.measured) entry.measuredHeight.toFloat() else dpToPx(24f)

            val isLeft = screenX < centerX

            // Label X: offset from anchor
            val labelX = if (isLeft) {
                (screenX - MAX_OFFSET - lw).coerceAtLeast(SIDE_MARGIN)
            } else {
                (screenX + MAX_OFFSET).coerceAtMost(wf - lw - SIDE_MARGIN)
            }

            // Label Y: aligned with anchor, avoid overlap
            var labelY = (screenY - lh / 2f).coerceIn(TOP_MARGIN, hf - lh - BOTTOM_MARGIN)
            val usedList = if (isLeft) usedLeftY else usedRightY
            for (usedY in usedList) {
                if (abs(labelY - usedY) < lh + LABEL_SPACING) {
                    labelY = usedY + lh + LABEL_SPACING
                }
            }
            labelY = labelY.coerceIn(TOP_MARGIN, hf - lh - BOTTOM_MARGIN)
            usedList.add(labelY)

            // Position label (hardware-accelerated, no layout pass)
            entry.view.translationX = labelX
            entry.view.translationY = labelY
            entry.view.visibility = View.VISIBLE

            // Line endpoint
            val lineEndX = if (isLeft) labelX + lw + dpToPx(4f) else labelX - dpToPx(4f)
            val lineEndY = labelY + lh / 2f

            lineView?.setLine(visibleCount, screenX, screenY, lineEndX, lineEndY)
            visibleCount++
        }

        lineView?.setLineCount(visibleCount)
        lineView?.commitLines()
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    private fun clearLabelViews() {
        entries.forEach { overlayContainer?.removeView(it.view) }
        entries.clear()
        lineView?.clear()
    }

    fun destroy() {
        clearLabelViews()
        labels = emptyList()
        overlayContainer = null
        lineView = null
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}