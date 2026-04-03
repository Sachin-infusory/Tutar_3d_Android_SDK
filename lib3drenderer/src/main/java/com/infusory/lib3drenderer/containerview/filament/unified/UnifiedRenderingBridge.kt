package com.infusory.tutarapp.filament.unified

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.infusory.tutarapp.filament.FilamentEngineManager
import com.infusory.tutarapp.filament.renderer.CameraController
import java.nio.ByteBuffer

/**
 * Main facade for the unified 3D rendering system.
 * 
 * This singleton coordinates all components:
 * - Unified3DSurface: The fullscreen SurfaceView
 * - UnifiedSceneManager: Manages virtual containers
 * - UnifiedRenderLoop: Handles frame rendering
 * - UnifiedTouchRouter: Routes touches to containers
 * 
 * Usage:
 * 1. Call initialize() in Activity.onCreate()
 * 2. Call createContainer() for each 3D model
 * 3. Call updateContainerBounds() when containers move/resize
 * 4. Call destroyContainer() when containers are removed
 * 5. Call shutdown() in Activity.onDestroy()
 */
object UnifiedRenderingBridge {
    
    private const val TAG = "UnifiedRenderingBridge"
    
    // Components
    private var surface: Unified3DSurface? = null
    private var sceneManager: UnifiedSceneManager? = null
    private var renderLoop: UnifiedRenderLoop? = null
    private var touchRouter: UnifiedTouchRouter? = null
    
    // State
    private var isInitialized = false
    private var applicationContext: Context? = null
    
    // Callbacks (for Container3D to hook into)
    var onContainerLoadingStarted: ((Int) -> Unit)? = null
    var onContainerLoadingCompleted: ((Int) -> Unit)? = null
    var onContainerLoadingFailed: ((Int, String) -> Unit)? = null
    
    /**
     * Initialize the unified rendering system.
     * 
     * @param context Application or Activity context
     * @param parentLayout The parent ViewGroup to add the unified surface to
     * @param surfaceIndex Index at which to insert the surface (default 1, after annotation surface)
     * @return true if initialization successful
     */
    @Synchronized
    fun initialize(
        context: Context,
        parentLayout: ViewGroup,
        surfaceIndex: Int = 1
    ): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }
        
        // Ensure FilamentEngineManager is initialized
        if (!FilamentEngineManager.isInitialized()) {
            Log.e(TAG, "FilamentEngineManager not initialized!")
            return false
        }
        
        applicationContext = context.applicationContext
        
        try {
            // Create unified surface
            surface = Unified3DSurface(context).apply {
                onSurfaceReady = { width, height ->
                    onSurfaceReadyInternal(width, height)
                }
                onSurfaceResized = { width, height ->
                    sceneManager?.setSurfaceDimensions(width, height)
                }
                onSurfaceDestroyed = {
                    renderLoop?.stop()
                }
            }
            
            // Add surface to parent layout
            surface!!.create(parentLayout, surfaceIndex)
            
            // Create scene manager
            sceneManager = UnifiedSceneManager(
                FilamentEngineManager.getResourceManager()
            ).apply {
                onContainerLoadingStarted = { id ->
                    this@UnifiedRenderingBridge.onContainerLoadingStarted?.invoke(id)
                }
                onContainerLoadingCompleted = { id ->
                    this@UnifiedRenderingBridge.onContainerLoadingCompleted?.invoke(id)
                }
                onContainerLoadingFailed = { id, error ->
                    this@UnifiedRenderingBridge.onContainerLoadingFailed?.invoke(id, error)
                }
            }
            
            // Create render loop
            renderLoop = UnifiedRenderLoop().apply {
                initialize(surface!!, sceneManager!!)
            }
            
            // Create touch router
            touchRouter = UnifiedTouchRouter(sceneManager!!)
            surface!!.getSurfaceView()?.let { surfaceView ->
                touchRouter!!.attachTo(surfaceView)
            }
            
            isInitialized = true
            Log.d(TAG, "UnifiedRenderingBridge initialized successfully")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UnifiedRenderingBridge", e)
            cleanup()
            return false
        }
    }
    
    /**
     * Check if initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Create a new virtual container.
     * 
     * @param x Initial X position (screen pixels)
     * @param y Initial Y position (screen pixels)
     * @param width Initial width (screen pixels)
     * @param height Initial height (screen pixels)
     * @return Container ID, or -1 if failed
     */
    fun createContainer(
        x: Int = 0,
        y: Int = 0,
        width: Int = 400,
        height: Int = 350
    ): Int {
        checkInitialized()
        
        val container = sceneManager!!.createContainer(x, y, width, height)
        
        // Start render loop if not running
        if (renderLoop?.isRunning() != true) {
            renderLoop?.start()
        }
        
        Log.d(TAG, "Created container ${container.id}")
        return container.id
    }
    
    /**
     * Load a model into a container.
     * 
     * @param containerId Container ID
     * @param buffer Model data buffer (GLB/GLTF)
     */
    fun loadModel(containerId: Int, buffer: ByteBuffer) {
        checkInitialized()
        sceneManager?.loadModel(containerId, buffer)
    }
    
    /**
     * Load a model from path into a container.
     * 
     * @param containerId Container ID
     * @param modelPath Path to model file
     * @param buffer Model data buffer
     */
    fun loadModelFromPath(containerId: Int, modelPath: String, buffer: ByteBuffer) {
        checkInitialized()
        sceneManager?.loadModelFromPath(containerId, modelPath, buffer)
    }
    
    /**
     * Update container bounds (call when container is moved/resized).
     * This is NON-BLOCKING - viewport is updated and render is requested.
     * 
     * @param containerId Container ID
     * @param x New X position (screen coordinates)
     * @param y New Y position (screen coordinates)
     * @param width New width
     * @param height New height
     */
    fun updateContainerBounds(containerId: Int, x: Int, y: Int, width: Int, height: Int) {
        sceneManager?.updateContainerBounds(containerId, x, y, width, height)
        // Request render on next vsync (non-blocking)
        renderLoop?.requestRender()
    }
    
    /**
     * Set container visibility.
     */
    fun setContainerVisible(containerId: Int, visible: Boolean) {
        sceneManager?.setContainerVisible(containerId, visible)
    }
    
    /**
     * Bring container to front.
     */
    fun bringContainerToFront(containerId: Int) {
        sceneManager?.bringToFront(containerId)
    }
    
    /**
     * Destroy a container.
     */
    fun destroyContainer(containerId: Int) {
        sceneManager?.destroyContainer(containerId)
        
        // Stop render loop if no containers left
        if (sceneManager?.getContainerCount() == 0) {
            renderLoop?.stop()
        }
        
        Log.d(TAG, "Destroyed container $containerId")
    }
    
    /**
     * Toggle animation for a container.
     * 
     * @return true if paused, false if playing, null if no animations
     */
    fun toggleAnimation(containerId: Int): Boolean? {
        return sceneManager?.getContainer(containerId)?.toggleAnimation()
    }
    
    /**
     * Check if animation is paused for a container.
     */
    fun isAnimationPaused(containerId: Int): Boolean {
        return sceneManager?.getContainer(containerId)?.isAnimationPaused() ?: true
    }
    
    /**
     * Check if a container has animations.
     */
    fun hasAnimations(containerId: Int): Boolean {
        return sceneManager?.getContainer(containerId)?.hasAnimations() ?: false
    }
    
    /**
     * Save camera state for a container.
     */
    fun saveCameraState(containerId: Int): CameraController.CameraState? {
        return sceneManager?.getContainer(containerId)?.saveCameraState()
    }
    
    /**
     * Restore camera state for a container.
     */
    fun restoreCameraState(containerId: Int, state: CameraController.CameraState) {
        sceneManager?.getContainer(containerId)?.restoreCameraState(state)
    }
    
    /**
     * Reset camera for a container.
     */
    fun resetCamera(containerId: Int) {
        sceneManager?.getContainer(containerId)?.cameraController?.resetToDefault()
    }
    
    /**
     * Route a touch event to the appropriate container.
     * Call this if you need manual touch routing.
     * 
     * @return true if handled by a container
     */
    fun routeTouchEvent(event: MotionEvent): Boolean {
        return touchRouter?.routeTouchEvent(event) ?: false
    }
    
    /**
     * Get container at a screen point.
     * 
     * @return Container ID, or null if no container at point
     */
    fun getContainerAtPoint(x: Float, y: Float): Int? {
        return sceneManager?.getContainerAtPoint(x, y)?.id
    }
    
    /**
     * Check if 3D touch interaction is enabled for a container.
     */
    fun isTouchEnabled(containerId: Int): Boolean {
        return sceneManager?.getContainer(containerId)?.let { container ->
            container.isVisible && container.isInitialized
        } ?: false
    }
    
    /**
     * Set touch enabled state.
     * For unified rendering, this controls whether the container receives touch events.
     */
    fun setTouchEnabled(containerId: Int, enabled: Boolean) {
        sceneManager?.setContainerVisible(containerId, enabled)
    }
    
    /**
     * Switch to on-demand rendering mode.
     * In this mode, the render loop only renders when requestRender() is called.
     * Use this during drag/resize for smooth movement without animation overhead.
     */
    fun pauseRendering() {
        renderLoop?.pause()
    }


    fun getDetailedStats(): DetailedStats? {
        if (!isInitialized) return null

        val renderStats = renderLoop?.getStats()
        val cullingStats = sceneManager?.getCullingStats()

        return DetailedStats(
            renderStats = renderStats,
            cullingStats = cullingStats
        )
    }

    /**
     * Get culling statistics
     */
    fun getCullingStats(): UnifiedSceneManager.CullingStats? {
        return sceneManager?.getCullingStats()
    }

    /**
     * Log performance statistics to Logcat
     */
    fun logPerformanceStats() {
        if (!isInitialized) {
            Log.d(TAG, "Not initialized")
            return
        }

        val stats = getDetailedStats() ?: return

        Log.i(TAG, "=== Performance Statistics ===")
        Log.i(TAG, "FPS: ${stats.renderStats?.currentFps?.toInt() ?: 0}/${stats.renderStats?.targetFps ?: 0}")
        Log.i(TAG, "Frame time: ${stats.renderStats?.avgFrameTimeMs?.let { "%.2f".format(it) } ?: "N/A"}ms")
        Log.i(TAG, "Total frames: ${stats.renderStats?.totalFrames ?: 0}")
        Log.i(TAG, "Dropped frames: ${stats.renderStats?.droppedFrames ?: 0}")
        Log.i(TAG, "Containers: ${stats.cullingStats?.visibleContainers ?: 0}/${stats.cullingStats?.totalContainers ?: 0}")
        Log.i(TAG, "Culled: ${stats.cullingStats?.culledContainers ?: 0}")
        Log.i(TAG, "Animating: ${stats.cullingStats?.animatingContainers ?: 0}")
        Log.i(TAG, "Has active animations: ${stats.renderStats?.hasActiveAnimations ?: false}")
        Log.i(TAG, "Mode: ${if (stats.renderStats?.onDemandMode == true) "On-demand" else "Continuous"}")
        Log.i(TAG, "=============================")
    }

    /**
     * Force an animation check and frame rate update
     * Useful for testing or when you know state has changed
     */
    fun updateFrameRate() {
        sceneManager?.hasVisibleAnimations()?.let { hasAnimations ->
            Log.d(TAG, "Frame rate update triggered, has animations: $hasAnimations")
        }
    }

    /**
     * Combined statistics data class
     */
    data class DetailedStats(
        val renderStats: UnifiedRenderLoop.RenderStats?,
        val cullingStats: UnifiedSceneManager.CullingStats?
    ) {
        fun getEfficiency(): Float {
            val visible = cullingStats?.visibleContainers?.toFloat() ?: 0f
            val total = cullingStats?.totalContainers?.toFloat() ?: 1f
            if (total == 0f) return 1f
            return visible / total
        }

        fun getCullingPercentage(): Float {
            val culled = cullingStats?.culledContainers?.toFloat() ?: 0f
            val total = cullingStats?.totalContainers?.toFloat() ?: 1f
            if (total == 0f) return 0f
            return (culled / total) * 100f
        }

        override fun toString(): String {
            return buildString {
                appendLine("Detailed Performance Stats:")
                appendLine("  FPS: ${renderStats?.currentFps?.toInt()}/${renderStats?.targetFps}")
                appendLine("  Frame Time: ${"%.2f".format(renderStats?.avgFrameTimeMs)}ms")
                appendLine("  Dropped: ${renderStats?.droppedFrames}")
                appendLine("  Containers: ${cullingStats?.visibleContainers}/${cullingStats?.totalContainers}")
                appendLine("  Culling: ${"%.1f".format(getCullingPercentage())}%")
                appendLine("  Animating: ${cullingStats?.animatingContainers}")
                appendLine("  Mode: ${if (renderStats?.onDemandMode == true) "On-demand" else "Continuous"}")
            }
        }
    }

    /**
     * Switch back to continuous rendering mode.
     * Use this when drag/resize completes to resume animations.
     */
    fun resumeRendering() {
        if (sceneManager?.getContainerCount() ?: 0 > 0) {
            renderLoop?.resume()
        }
    }
    
    /**
     * Request a render on the next vsync.
     * This is NON-BLOCKING - just sets a flag for the render loop.
     * Use this during drag/resize for smooth movement.
     */
    fun requestRender() {
        renderLoop?.requestRender()
    }
    
    /**
     * Check if in on-demand rendering mode.
     */
    fun isRenderingPaused(): Boolean {
        return !(renderLoop?.isRunning() ?: false)
    }
    
    /**
     * Get the unified surface view (for advanced use).
     */
    fun getSurfaceView(): View? = surface?.getSurfaceView()
    
    /**
     * Get number of active containers.
     */
    fun getContainerCount(): Int = sceneManager?.getContainerCount() ?: 0
    
    /**
     * Get render statistics.
     */
    fun getStats(): UnifiedRenderLoop.RenderStats? = renderLoop?.getStats()
    
    /**
     * Shutdown the unified rendering system.
     * Call in Activity.onDestroy().
     */
    @Synchronized
    fun shutdown() {
        if (!isInitialized) return
        
        Log.d(TAG, "Shutting down UnifiedRenderingBridge")
        
        cleanup()
        
        Log.d(TAG, "UnifiedRenderingBridge shutdown complete")
    }
    
    // ==================== Private ====================
    
    private fun onSurfaceReadyInternal(width: Int, height: Int) {
        Log.d(TAG, "Surface ready: ${width}x${height}")
        
        sceneManager?.setSurfaceDimensions(width, height)
        
        // Start render loop if there are containers
        if (sceneManager?.getContainerCount() ?: 0 > 0) {
            renderLoop?.start()
        }
    }
    
    private fun cleanup() {
        renderLoop?.destroy()
        renderLoop = null
        
        surface?.getSurfaceView()?.let { view ->
            touchRouter?.detachFrom(view)
        }
        touchRouter = null
        
        sceneManager?.destroyAll()
        sceneManager = null
        
        surface?.destroy()
        surface = null
        
        applicationContext = null
        isInitialized = false
    }
    
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("UnifiedRenderingBridge not initialized. Call initialize() first.")
        }
    }
}
