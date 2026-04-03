package com.infusory.tutarapp.filament.unified

import com.google.android.filament.Camera
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.gltfio.FilamentAsset
import com.infusory.tutarapp.filament.renderer.CameraController
import java.nio.ByteBuffer

/**
 * Data class representing a virtual 3D container in the unified rendering system.
 * 
 * This holds all the state needed to render a 3D model in a specific viewport
 * on the shared surface, without creating its own SurfaceView.
 * 
 * Lifecycle:
 * 1. Created when a 3D container is added
 * 2. Filament objects (scene, view, camera) created during initialization
 * 3. Model loaded into the scene
 * 4. Viewport updated as container is moved/resized
 * 5. Destroyed when container is removed
 */
data class VirtualContainer3D(
    /** Unique identifier for this virtual container */
    val id: Int,
    
    /** Viewport position - X coordinate in screen pixels */
    var viewportX: Int = 0,
    
    /** Viewport position - Y coordinate in screen pixels */
    var viewportY: Int = 0,
    
    /** Viewport width in screen pixels */
    var viewportWidth: Int = 400,
    
    /** Viewport height in screen pixels */
    var viewportHeight: Int = 350,
    
    /** Z-order for rendering and touch hit-testing (higher = on top) */
    var zOrder: Int = 0,
    
    /** Whether this container should be rendered */
    var isVisible: Boolean = true,
    
    /** Path to the model file */
    var modelPath: String? = null,
    
    /** Display name of the model */
    var modelName: String = ""
) {
    // ==================== Filament Objects ====================
    // These are managed by UnifiedSceneManager
    
    /** Filament Scene - contains the 3D model for this container */
    internal var scene: Scene? = null
    
    /** Filament View - defines viewport and camera for rendering */
    internal var view: View? = null
    
    /** Filament Camera - controls perspective for this container */
    internal var camera: Camera? = null
    
    /** Currently loaded asset */
    internal var asset: FilamentAsset? = null
    
    /** Camera controller for touch-based manipulation */
    internal var cameraController: CameraController? = null
    
    /** Animation controller for this container's model */
    internal var animationController: AnimationControllerUnified? = null
    
    // ==================== State ====================
    
    /** Whether Filament objects have been initialized */
    var isInitialized: Boolean = false
        internal set
    
    /** Whether a model is currently loaded */
    var isModelLoaded: Boolean = false
        internal set
    
    /** Pending model buffer (if set before initialization) */
    internal var pendingModelBuffer: ByteBuffer? = null
    
    // ==================== Viewport Helpers ====================
    
    /**
     * Update viewport bounds
     */
    fun updateBounds(x: Int, y: Int, width: Int, height: Int) {
        viewportX = x
        viewportY = y
        viewportWidth = width
        viewportHeight = height
    }
    
    /**
     * Check if a point (in screen coordinates) is inside this container's viewport
     */
    fun containsPoint(screenX: Float, screenY: Float): Boolean {
        return screenX >= viewportX && 
               screenX <= viewportX + viewportWidth &&
               screenY >= viewportY && 
               screenY <= viewportY + viewportHeight
    }
    
    /**
     * Convert screen coordinates to local viewport coordinates
     */
    fun screenToLocal(screenX: Float, screenY: Float): Pair<Float, Float> {
        return Pair(screenX - viewportX, screenY - viewportY)
    }
    
    /**
     * Get the viewport rectangle as an IntArray [x, y, width, height]
     */
    fun getViewportRect(): IntArray {
        return intArrayOf(viewportX, viewportY, viewportWidth, viewportHeight)
    }
    
    // ==================== Camera State ====================
    
    /**
     * Save current camera state for persistence
     */
    fun saveCameraState(): CameraController.CameraState? {
        return cameraController?.saveState()
    }
    
    /**
     * Restore camera state from saved data
     */
    fun restoreCameraState(state: CameraController.CameraState) {
        cameraController?.restoreState(state)
    }
    
    // ==================== Animation ====================
    
    /**
     * Toggle animation playback
     * @return true if paused after toggle, false if playing, null if no animations
     */
    fun toggleAnimation(): Boolean? {
        return animationController?.togglePause()
    }
    
    /**
     * Check if animation is currently paused
     */
    fun isAnimationPaused(): Boolean {
        return animationController?.isPaused() ?: true
    }
    
    /**
     * Check if this container's model has animations
     */
    fun hasAnimations(): Boolean {
        return animationController?.hasAnimations() ?: false
    }
    
    override fun toString(): String {
        return "VirtualContainer3D(id=$id, viewport=[$viewportX,$viewportY,${viewportWidth}x${viewportHeight}], " +
               "zOrder=$zOrder, visible=$isVisible, initialized=$isInitialized, modelLoaded=$isModelLoaded)"
    }
}

/**
 * Lightweight animation controller for unified rendering.
 * 
 * FIXED: Proper delta-time based animation accumulation.
 * 
 * The animation time is accumulated using delta time between frames,
 * and wraps around based on the actual animation duration for looping.
 */
class AnimationControllerUnified {
    
    private var animator: com.google.android.filament.gltfio.Animator? = null
    private var animationCount = 0
    private var isPaused = false
    private var lastFrameTimeNanos = 0L
    
    // Accumulated animation time in seconds for each animation
    private var animationTimes: FloatArray = FloatArray(0)
    
    // Animation durations cached for looping
    private var animationDurations: FloatArray = FloatArray(0)
    
    fun initialize(animator: com.google.android.filament.gltfio.Animator) {
        this.animator = animator
        this.animationCount = animator.animationCount
        this.isPaused = false
        this.lastFrameTimeNanos = System.nanoTime()
        
        // Initialize animation time tracking
        animationTimes = FloatArray(animationCount) { 0f }
        animationDurations = FloatArray(animationCount) { i ->
            animator.getAnimationDuration(i)
        }
    }
    
    fun clear() {
        animator = null
        animationCount = 0
        isPaused = false
        animationTimes = FloatArray(0)
        animationDurations = FloatArray(0)
    }
    
    fun hasAnimations(): Boolean = animationCount > 0
    
    fun isPaused(): Boolean = isPaused
    
    fun togglePause(): Boolean? {
        if (animationCount == 0) return null
        isPaused = !isPaused
        if (!isPaused) {
            // Reset last frame time to avoid a big delta jump after unpausing
            lastFrameTimeNanos = System.nanoTime()
        }
        return isPaused
    }
    
    /**
     * Update animations using proper delta time accumulation.
     * 
     * @param frameTimeNanos The Choreographer frame time in nanoseconds
     */
    fun update(frameTimeNanos: Long) {
        if (isPaused || animationCount == 0) return
        
        animator?.let { anim ->
            // Calculate delta time since last frame
            val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f
            lastFrameTimeNanos = frameTimeNanos
            
            // Clamp delta to avoid huge jumps (e.g., after app resume)
            // Max delta of 100ms (10fps minimum assumption)
            val clampedDelta = deltaSeconds.coerceIn(0f, 0.1f)
            
            // Update all animations with accumulated time
            for (i in 0 until animationCount) {
                // Accumulate time
                animationTimes[i] += clampedDelta
                
                // Loop: wrap around based on animation duration
                val duration = animationDurations[i]
                if (duration > 0f && animationTimes[i] > duration) {
                    animationTimes[i] = animationTimes[i] % duration
                }
                
                // Apply the animation at the current accumulated time
                anim.applyAnimation(i, animationTimes[i])
            }
            
            // Update bone matrices after all animations are applied
            anim.updateBoneMatrices()
        }
    }
    
    /**
     * Reset all animations to the beginning
     */
    fun resetAnimations() {
        for (i in animationTimes.indices) {
            animationTimes[i] = 0f
        }
    }
    
    /**
     * Set the time for a specific animation
     */
    fun setAnimationTime(animationIndex: Int, time: Float) {
        if (animationIndex in animationTimes.indices) {
            animationTimes[animationIndex] = time.coerceAtLeast(0f)
        }
    }
    
    /**
     * Get current time for a specific animation
     */
    fun getAnimationTime(animationIndex: Int): Float {
        return if (animationIndex in animationTimes.indices) {
            animationTimes[animationIndex]
        } else {
            0f
        }
    }
    
    /**
     * Get duration of a specific animation
     */
    fun getAnimationDuration(animationIndex: Int): Float {
        return if (animationIndex in animationDurations.indices) {
            animationDurations[animationIndex]
        } else {
            0f
        }
    }
    
    fun getAnimationCount(): Int = animationCount
}
