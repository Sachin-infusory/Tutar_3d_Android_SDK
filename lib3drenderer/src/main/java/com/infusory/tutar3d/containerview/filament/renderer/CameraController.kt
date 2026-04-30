package com.infusory.tutar3d.containerview.filament.renderer

import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
import android.view.MotionEvent
import com.google.android.filament.Camera
import com.google.android.filament.Viewport
import com.google.android.filament.View
import com.infusory.tutar3d.containerview.filament.config.FilamentConfig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CameraController {

    companion object {
        private const val TAG = "CameraController"

        // Touch sensitivity
        private const val ORBIT_SENSITIVITY = 0.005f
        private const val ZOOM_SENSITIVITY = 0.01f
        private const val PAN_SENSITIVITY = 0.001f

        // Camera constraints
        private const val MIN_DISTANCE = 1.0f
        private const val MAX_DISTANCE = 20.0f
        private const val MIN_POLAR = 0.1f
        private const val MAX_POLAR = Math.PI.toFloat() - 0.1f
    }

    // Camera reference
    private var camera: Camera? = null
    private var view: View? = null

    // Viewport dimensions
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Spherical coordinates for orbit camera
    private var azimuth = 0f
    private var polar = Math.PI.toFloat() / 2f
    private var distance = FilamentConfig.DEFAULT_CAMERA_DISTANCE.toFloat()

    // Target point (what camera looks at)
    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f

    // ============ CACHED TRIGONOMETRY ============
    private var cachedSinPolar = 1f
    private var cachedCosPolar = 0f
    private var cachedSinAz = 0f
    private var cachedCosAz = 1f
    private var trigDirty = true

    // Cached eye position
    private var eyeX = 0.0
    private var eyeY = 0.0
    private var eyeZ = 0.0
    private var positionDirty = true

    // Touch tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTouchX2 = 0f
    private var lastTouchY2 = 0f
    private var initialPinchDistance = 0f
    private var touchMode = TouchMode.NONE

    enum class TouchMode {
        NONE, ORBIT, PAN, ZOOM
    }

    /**
     * Camera state for save/restore
     */
    data class CameraState(
        val azimuth: Float,
        val polar: Float,
        val distance: Float,
        val targetX: Float,
        val targetY: Float,
        val targetZ: Float
    )

    /**
     * Initialize with a camera and view
     */
    fun initialize(camera: Camera, view: View) {
        this.camera = camera
        this.view = view
        resetToDefault()
    }

    /**
     * Update viewport dimensions
     */
    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (viewportWidth == width && viewportHeight == height) return

        viewportWidth = width
        viewportHeight = height

        view?.viewport = Viewport(0, 0, width, height)
        updateProjection()

        SdkLog.d(TAG, "Viewport set: ${width}x${height}")
    }

    /**
     * Update camera projection matrix
     */
    fun updateProjection() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        camera?.setProjection(
            FilamentConfig.DEFAULT_CAMERA_FOV,
            viewportWidth.toDouble() / viewportHeight.toDouble(),
            FilamentConfig.DEFAULT_CAMERA_NEAR,
            FilamentConfig.DEFAULT_CAMERA_FAR,
            Camera.Fov.VERTICAL
        )
    }

    /**
     * Reset camera to default position
     */
    fun resetToDefault() {
        azimuth = 0f
        polar = Math.PI.toFloat() / 2f
        distance = FilamentConfig.DEFAULT_CAMERA_DISTANCE.toFloat()
        targetX = 0f
        targetY = 0f
        targetZ = 0f

        trigDirty = true
        positionDirty = true
        applyTransform()
    }

    /**
     * Save current camera state
     */
    fun saveState(): CameraState? {
        return CameraState(
            azimuth = azimuth,
            polar = polar,
            distance = distance,
            targetX = targetX,
            targetY = targetY,
            targetZ = targetZ
        )
    }

    /**
     * Restore camera state
     */
    fun restoreState(state: CameraState) {
        try {
            azimuth = state.azimuth
            polar = state.polar
            distance = state.distance

            targetX = state.targetX
            targetY = state.targetY
            targetZ = state.targetZ

            trigDirty = true
            positionDirty = true

            applyTransform()

            SdkLog.d(TAG, "Camera state restored (FULL: rotation + pan + zoom)")
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to restore camera state", e)
        }
    }

    fun updateProjectionOnly(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        viewportWidth = width
        viewportHeight = height

        updateProjection()

        SdkLog.d(TAG, "Projection updated for ${width}x${height}")
    }

    /**
     * Handle touch events for camera manipulation
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchMode = TouchMode.ORBIT
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    lastTouchX = event.getX(0)
                    lastTouchY = event.getY(0)
                    lastTouchX2 = event.getX(1)
                    lastTouchY2 = event.getY(1)
                    initialPinchDistance = getPinchDistance(event)
                    touchMode = TouchMode.ZOOM
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    TouchMode.ORBIT -> handleOrbit(event)
                    TouchMode.ZOOM -> {
                        if (event.pointerCount >= 2) {
                            val currentDistance = getPinchDistance(event)
                            val delta = currentDistance - initialPinchDistance

                            if (kotlin.math.abs(delta) > 5f) {
                                handleZoom(event)
                            } else {
                                val midX = (event.getX(0) + event.getX(1)) / 2f
                                val midY = (event.getY(0) + event.getY(1)) / 2f

                                val prevMidX = (lastTouchX + lastTouchX2) / 2f
                                val prevMidY = (lastTouchY + lastTouchY2) / 2f

                                val dx = midX - prevMidX
                                val dy = midY - prevMidY

                                pan(-dx, dy)

                                lastTouchX = event.getX(0)
                                lastTouchY = event.getY(0)
                                lastTouchX2 = event.getX(1)
                                lastTouchY2 = event.getY(1)
                            }
                        }
                    }
                    TouchMode.PAN -> handlePan(event)
                    TouchMode.NONE -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = TouchMode.NONE
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    touchMode = TouchMode.ORBIT
                }
                return true
            }
        }

        return false
    }

    /**
     * Orbit camera around target
     */
    fun orbit(deltaAzimuth: Float, deltaPolar: Float) {
        azimuth += deltaAzimuth
        polar = (polar + deltaPolar).coerceIn(MIN_POLAR, MAX_POLAR)

        trigDirty = true
        positionDirty = true
        applyTransform()
    }

    /**
     * Zoom camera (change distance)
     */
    fun zoom(delta: Float) {
        distance = (distance - delta).coerceIn(MIN_DISTANCE, MAX_DISTANCE)

        positionDirty = true
        applyTransform()
    }

    /**
     * Pan camera (move target)
     */
    fun pan(deltaX: Float, deltaY: Float) {
        if (trigDirty) updateTrigCache()

        val rightX = cachedCosAz
        val rightZ = -cachedSinAz
        val upY = 1f

        val panScale = distance * PAN_SENSITIVITY
        targetX += rightX * deltaX * panScale
        targetZ += rightZ * deltaX * panScale
        targetY += upY * deltaY * panScale

        positionDirty = true
        applyTransform()
    }

    /**
     * Clear camera reference
     */
    fun clear() {
        camera = null
        view = null
    }

    /**
     * Get current viewport width in pixels
     */
    fun getViewportWidth(): Int = viewportWidth

    /**
     * Get current viewport height in pixels
     */
    fun getViewportHeight(): Int = viewportHeight

    // ========== Private Methods ==========

    private fun updateTrigCache() {
        cachedSinPolar = sin(polar)
        cachedCosPolar = cos(polar)
        cachedSinAz = sin(azimuth)
        cachedCosAz = cos(azimuth)
        trigDirty = false
    }

    private fun updatePositionCache() {
        if (trigDirty) updateTrigCache()

        eyeX = (targetX + distance * cachedSinPolar * cachedSinAz).toDouble()
        eyeY = (targetY + distance * cachedCosPolar).toDouble()
        eyeZ = (targetZ + distance * cachedSinPolar * cachedCosAz).toDouble()
        positionDirty = false
    }

    private fun applyTransform() {
        camera?.let { cam ->
            if (positionDirty) updatePositionCache()

            cam.lookAt(
                eyeX, eyeY, eyeZ,
                targetX.toDouble(), targetY.toDouble(), targetZ.toDouble(),
                0.0, 1.0, 0.0
            )
        }
    }

    private fun updateSphericalFromCartesian(eyeX: Float, eyeY: Float, eyeZ: Float) {
        val dx = eyeX - targetX
        val dy = eyeY - targetY
        val dz = eyeZ - targetZ

        distance = sqrt(dx * dx + dy * dy + dz * dz)

        if (distance > 0) {
            polar = kotlin.math.acos((dy / distance).coerceIn(-1f, 1f))
            azimuth = kotlin.math.atan2(dx, dz)
        }

        trigDirty = true
        positionDirty = true
    }

    private fun handleOrbit(event: MotionEvent) {
        val deltaX = event.x - lastTouchX
        val deltaY = event.y - lastTouchY

        orbit(
            -deltaX * ORBIT_SENSITIVITY,
            -deltaY * ORBIT_SENSITIVITY
        )

        lastTouchX = event.x
        lastTouchY = event.y
    }

    private fun handleZoom(event: MotionEvent) {
        if (event.pointerCount < 2) return

        val currentDistance = getPinchDistance(event)
        val delta = currentDistance - initialPinchDistance

        zoom(delta * ZOOM_SENSITIVITY)

        initialPinchDistance = currentDistance
    }

    private fun handlePan(event: MotionEvent) {
        val deltaX = event.x - lastTouchX
        val deltaY = event.y - lastTouchY

        pan(-deltaX, deltaY)

        lastTouchX = event.x
        lastTouchY = event.y
    }

    private fun getPinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f

        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }
}