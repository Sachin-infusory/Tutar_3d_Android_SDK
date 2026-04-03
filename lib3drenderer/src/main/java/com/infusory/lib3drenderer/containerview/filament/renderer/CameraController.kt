package com.infusory.tutarapp.filament.renderer

import android.util.Log
import android.view.MotionEvent
import com.google.android.filament.Camera
import com.google.android.filament.Viewport
import com.google.android.filament.View
import com.infusory.tutarapp.filament.config.FilamentConfig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Controls camera positioning and interaction for a 3D view.
 *
 * OPTIMIZATIONS:
 * 1. Cached sine/cosine values (only recalculated when angles change)
 * 2. Pre-computed eye position
 * 3. Reduced object allocation
 */
class CameraController {

    companion object {
        private const val TAG = "CameraController"

        // Touch sensitivity
        private const val ORBIT_SENSITIVITY = 0.005f
        private const val ZOOM_SENSITIVITY = 0.01f
        private const val PAN_SENSITIVITY = 0.005f

        // Camera constraints
        private const val MIN_DISTANCE = 1.0f
        private const val MAX_DISTANCE = 20.0f
        private const val MIN_POLAR = 0.1f  // Avoid gimbal lock at poles
        private const val MAX_POLAR = Math.PI.toFloat() - 0.1f
    }

    // Camera reference
    private var camera: Camera? = null
    private var view: View? = null

    // Viewport dimensions
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Spherical coordinates for orbit camera
    private var azimuth = 0f      // Horizontal angle (radians)
    private var polar = Math.PI.toFloat() / 2f  // Vertical angle (radians)
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
        val viewMatrix: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return viewMatrix.contentEquals((other as CameraState).viewMatrix)
        }
        override fun hashCode(): Int = viewMatrix.contentHashCode()
    }

    /**
     * Initialize with a camera and view
     */
    fun initialize(camera: Camera, view: View) {
        this.camera = camera
        this.view = view

        // Set default position
        resetToDefault()

        Log.d(TAG, "Camera controller initialized")
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

        Log.d(TAG, "Viewport set: ${width}x${height}")
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
        return camera?.let { cam ->
            val viewMatrix = FloatArray(16)
            cam.getViewMatrix(viewMatrix)
            CameraState(viewMatrix)
        }
    }

    /**
     * Restore camera state
     */
    fun restoreState(state: CameraState) {
        camera?.let { cam ->
            try {
                // Extract eye position from view matrix by inverting it
                val modelMatrix = FloatArray(16)
                if (android.opengl.Matrix.invertM(modelMatrix, 0, state.viewMatrix, 0)) {
                    val newEyeX = modelMatrix[12].toDouble()
                    val newEyeY = modelMatrix[13].toDouble()
                    val newEyeZ = modelMatrix[14].toDouble()

                    val upX = modelMatrix[4].toDouble()
                    val upY = modelMatrix[5].toDouble()
                    val upZ = modelMatrix[6].toDouble()

                    cam.lookAt(
                        newEyeX, newEyeY, newEyeZ,
                        targetX.toDouble(), targetY.toDouble(), targetZ.toDouble(),
                        upX, upY, upZ
                    )

                    // Update spherical coordinates from restored position
                    updateSphericalFromCartesian(newEyeX.toFloat(), newEyeY.toFloat(), newEyeZ.toFloat())

                    Log.d(TAG, "Camera state restored")
                }else{

                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore camera state", e)
            }
        }
    }

    fun updateProjectionOnly(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        viewportWidth = width
        viewportHeight = height

        // Only update the camera projection, not the view's viewport
        updateProjection()

        Log.d(TAG, "Projection updated for ${width}x${height}")
    }

    /**
     * Handle touch events for camera manipulation
     * @return true if the event was handled
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
                    TouchMode.ZOOM -> handleZoom(event)
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
                // Switch back to orbit if one finger remains
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
        // Use cached trig values
        if (trigDirty) updateTrigCache()

        // Right vector (perpendicular to view direction in XZ plane)
        val rightX = cachedCosAz
        val rightZ = -cachedSinAz

        // Up vector (always Y-up for simplicity)
        val upY = 1f

        // Apply pan
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

    // ========== Private Methods ==========

    /**
     * Update cached trigonometry values.
     */
    private fun updateTrigCache() {
        cachedSinPolar = sin(polar)
        cachedCosPolar = cos(polar)
        cachedSinAz = sin(azimuth)
        cachedCosAz = cos(azimuth)
        trigDirty = false
    }

    /**
     * Update cached eye position.
     */
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
                0.0, 1.0, 0.0  // Y-up
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