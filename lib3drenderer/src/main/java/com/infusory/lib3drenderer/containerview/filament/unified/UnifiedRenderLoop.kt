package com.infusory.tutarapp.filament.unified

import android.util.Log
import android.view.Choreographer
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Render loop for the unified 3D rendering system.
 *
 * OPTIMIZATIONS:
 * - Dynamic frame rate: 60fps when animations active, 30fps when idle
 * - Smart animation detection: Only runs at high FPS when needed
 * - On-demand mode: Renders only when dirty flag is set (for drag/resize)
 * - Adaptive frame skipping: Automatically adjusts to system load
 *
 * FIXES:
 * - Immediate rendering when dirty flag is set (no frame skipping)
 * - Proper vsync alignment for smooth dragging
 *
 * Uses Choreographer for vsync-aligned rendering with minimal latency.
 */
class UnifiedRenderLoop {

    companion object {
        private const val TAG = "UnifiedRenderLoop"

        // Frame rate targets
        private const val FPS_60_INTERVAL_NS = 16_666_666L  // ~60fps
        private const val FPS_30_INTERVAL_NS = 33_333_333L  // ~30fps
        private const val FPS_15_INTERVAL_NS = 66_666_666L  // ~15fps (very low activity)

        // Animation check interval (check every N frames if we need high FPS)
        private const val ANIMATION_CHECK_INTERVAL = 30 // Check every 30 frames (~500ms at 60fps)

        // Frame budget for 60fps (if we exceed this, we're dropping frames)
        private const val FRAME_BUDGET_60FPS_NS = 16_000_000L // 16ms
    }

    // Dependencies
    private var surface: Unified3DSurface? = null
    private var sceneManager: UnifiedSceneManager? = null

    // Choreographer for vsync-aligned rendering
    private val choreographer = Choreographer.getInstance()

    // State
    private var isRunning = false
    private var isPaused = false

    // Dirty flag for on-demand rendering (atomic for thread safety)
    private val isDirty = AtomicBoolean(false)

    // Mode flag - when true, only render when dirty
    private var onDemandMode = false

    // Dynamic frame rate state
    private var targetFrameIntervalNs = FPS_60_INTERVAL_NS
    private var hasActiveAnimations = false
    private var framesSinceAnimationCheck = 0

    // Stats
    private var totalFrames = 0L
    private var lastFrameTimeNanos = 0L
    private var lastRenderedFrameTimeNanos = 0L
    private var droppedFrames = 0
    private var lastFpsCheckTime = 0L
    private var framesInLastSecond = 0
    private var currentFps = 0f

    // Frame timing tracking (for adaptive frame rate)
    private val recentFrameTimes = ArrayDeque<Long>(10)
    private var avgFrameTimeNs = FPS_60_INTERVAL_NS

    // Frame callback - called on every vsync
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            val frameStartTime = System.nanoTime()

            // Update FPS counter
            updateFpsCounter(frameTimeNanos)

            // Adaptive frame rate: Check if we need to adjust target FPS
            if (++framesSinceAnimationCheck >= ANIMATION_CHECK_INTERVAL) {
                updateTargetFrameRate()
                framesSinceAnimationCheck = 0
            }

            // Determine if we should render this frame
            val shouldRender = shouldRenderFrame(frameTimeNanos)

            if (shouldRender) {
                try {
                    renderFrame(frameTimeNanos)

                    // Track frame timing
                    val frameTime = System.nanoTime() - frameStartTime
                    trackFrameTiming(frameTime)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in render frame", e)
                }
            }

            // Schedule next frame with appropriate delay
            scheduleNextFrame()
        }
    }

    /**
     * Initialize the render loop
     */
    fun initialize(surface: Unified3DSurface, sceneManager: UnifiedSceneManager) {
        this.surface = surface
        this.sceneManager = sceneManager
        Log.d(TAG, "Render loop initialized with Choreographer and dynamic frame rate")
    }

    /**
     * Start the render loop in continuous mode
     */
    fun start() {
        if (isRunning) return

        isRunning = true
        isPaused = false
        onDemandMode = false
        lastFrameTimeNanos = System.nanoTime()
        lastRenderedFrameTimeNanos = System.nanoTime()
        lastFpsCheckTime = System.nanoTime()

        // Initial animation check
        updateTargetFrameRate()

        choreographer.postFrameCallback(frameCallback)
        Log.d(TAG, "Render loop started (${getFpsFromInterval(targetFrameIntervalNs)}fps)")
    }

    /**
     * Stop the render loop completely
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        choreographer.removeFrameCallback(frameCallback)

        Log.d(TAG, "Render loop stopped - Stats: " +
                "total frames: $totalFrames, " +
                "dropped: $droppedFrames, " +
                "avg FPS: ${currentFps.toInt()}")
    }

    /**
     * Switch to on-demand rendering mode.
     * In this mode, frames are only rendered when requestRender() is called.
     * The loop still runs to check the dirty flag on each vsync.
     */
    fun pause() {
        onDemandMode = true
        isPaused = true
        Log.d(TAG, "Render loop paused (on-demand mode)")
    }

    /**
     * Switch back to continuous rendering mode.
     */
    fun resume() {
        onDemandMode = false
        isPaused = false

        // Re-evaluate target frame rate
        updateTargetFrameRate()

        // Reset timing to avoid stale deltas
        lastFrameTimeNanos = System.nanoTime()
        lastRenderedFrameTimeNanos = System.nanoTime()

        // Ensure loop is running
        if (isRunning) {
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }

        Log.d(TAG, "Render loop resumed (${getFpsFromInterval(targetFrameIntervalNs)}fps)")
    }

    /**
     * Request a render on the next vsync.
     * This is NON-BLOCKING - it just sets a flag.
     * Call this after viewport updates for smooth movement.
     * 
     * IMPORTANT: When dirty flag is set, the frame will render immediately
     * on the next vsync without any frame skipping logic.
     */
    fun requestRender() {
        isDirty.set(true)
    }

    /**
     * Check if the render loop is in continuous mode
     */
    fun isRunning(): Boolean = isRunning && !onDemandMode

    /**
     * Get render statistics
     */
    fun getStats(): RenderStats {
        val cullingStats = sceneManager?.getCullingStats()

        return RenderStats(
            totalFrames = totalFrames,
            droppedFrames = droppedFrames,
            currentFps = currentFps,
            targetFps = getFpsFromInterval(targetFrameIntervalNs),
            avgFrameTimeMs = avgFrameTimeNs / 1_000_000f,
            containerCount = sceneManager?.getContainerCount() ?: 0,
            visibleContainers = cullingStats?.visibleContainers ?: 0,
            culledContainers = cullingStats?.culledContainers ?: 0,
            animatingContainers = cullingStats?.animatingContainers ?: 0,
            hasActiveAnimations = hasActiveAnimations,
            isRunning = isRunning,
            isPaused = isPaused,
            onDemandMode = onDemandMode
        )
    }

    /**
     * Clean up
     */
    fun destroy() {
        stop()
        surface = null
        sceneManager = null
        recentFrameTimes.clear()
        Log.d(TAG, "Render loop destroyed")
    }

    // ==================== Private - Frame Rate Optimization ====================

    /**
     * Update the target frame rate based on current activity
     */
    private fun updateTargetFrameRate() {
        val sceneManager = this.sceneManager ?: return

        // Check if there are any visible animations
        val hasVisibleAnimations = sceneManager.hasVisibleAnimations()
        hasActiveAnimations = hasVisibleAnimations

        // Determine target frame rate
        val newTarget = when {
            // High FPS for active animations
            hasVisibleAnimations -> FPS_60_INTERVAL_NS

            // Medium FPS when containers are visible but not animating
            sceneManager.getContainerCount() > 0 -> FPS_30_INTERVAL_NS

            // Low FPS when nothing is happening
            else -> FPS_15_INTERVAL_NS
        }

        // Update target if changed
        if (newTarget != targetFrameIntervalNs) {
            val oldFps = getFpsFromInterval(targetFrameIntervalNs)
            val newFps = getFpsFromInterval(newTarget)

            targetFrameIntervalNs = newTarget

            Log.d(TAG, "Frame rate changed: ${oldFps}fps -> ${newFps}fps " +
                    "(animations: $hasVisibleAnimations, containers: ${sceneManager.getContainerCount()})")
        }
    }

    /**
     * Determine if we should render this frame.
     * 
     * FIXED: Always render immediately when dirty flag is set.
     * This ensures smooth viewport updates during drag operations.
     */
    private fun shouldRenderFrame(frameTimeNanos: Long): Boolean {
        // PRIORITY: If dirty flag is set, render immediately (used for viewport updates during drag)
        // This bypasses frame skipping to ensure smooth movement
        if (isDirty.getAndSet(false)) {
            return true
        }
        
        // In on-demand mode without dirty flag, don't render
        if (onDemandMode) {
            return false
        }

        // In continuous mode, respect target frame interval
        val timeSinceLastRendered = frameTimeNanos - lastRenderedFrameTimeNanos

        // Render if we've exceeded the target interval
        if (timeSinceLastRendered >= targetFrameIntervalNs) {
            return true
        }

        // Skip frame if we're ahead of schedule
        // This allows the system to breathe and reduces power consumption
        return false
    }

    /**
     * Schedule the next frame with appropriate timing
     */
    private fun scheduleNextFrame() {
        if (!isRunning) return

        // Always post immediately to catch the next vsync
        // The shouldRenderFrame logic handles timing control
        choreographer.postFrameCallback(frameCallback)
    }

    /**
     * Track frame timing for adaptive frame rate
     */
    private fun trackFrameTiming(frameTimeNs: Long) {
        recentFrameTimes.addLast(frameTimeNs)
        if (recentFrameTimes.size > 10) {
            recentFrameTimes.removeFirst()
        }

        // Calculate average
        avgFrameTimeNs = if (recentFrameTimes.isNotEmpty()) {
            recentFrameTimes.average().toLong()
        } else {
            frameTimeNs
        }

        // Check for dropped frames (exceeded 60fps budget)
        if (frameTimeNs > FRAME_BUDGET_60FPS_NS) {
            droppedFrames++

            // Log warning if we're consistently dropping frames
            if (droppedFrames % 30 == 0) {
                Log.w(TAG, "Performance warning: $droppedFrames dropped frames, " +
                        "avg frame time: ${avgFrameTimeNs / 1_000_000f}ms")
            }
        }
    }

    /**
     * Update FPS counter
     */
    private fun updateFpsCounter(frameTimeNanos: Long) {
        framesInLastSecond++

        val timeSinceLastCheck = frameTimeNanos - lastFpsCheckTime
        if (timeSinceLastCheck >= 1_000_000_000L) { // 1 second
            currentFps = framesInLastSecond * (1_000_000_000f / timeSinceLastCheck)
            framesInLastSecond = 0
            lastFpsCheckTime = frameTimeNanos
        }
    }

    /**
     * Convert frame interval to FPS
     */
    private fun getFpsFromInterval(intervalNs: Long): Int {
        return (1_000_000_000L / intervalNs).toInt()
    }

    // ==================== Private - Rendering ====================

    private fun renderFrame(frameTimeNanos: Long) {
        val surface = this.surface ?: return
        val sceneManager = this.sceneManager ?: return

        if (!surface.isReady()) return

        val renderer = surface.getRenderer() ?: return
        val swapChain = surface.getSwapChain() ?: return

        // Get containers sorted by z-order (with culling applied)
        val containers = sceneManager.getContainersSortedByZOrder()

        if (containers.isEmpty()) {
            renderEmptyFrame(renderer, swapChain, frameTimeNanos)
            lastFrameTimeNanos = frameTimeNanos
            lastRenderedFrameTimeNanos = frameTimeNanos
            totalFrames++
            return
        }

        // Update animations (only for on-screen containers)
        // FIXED: Use consistent frame time for animation updates
        if (!onDemandMode && hasActiveAnimations) {
            sceneManager.updateAnimations(frameTimeNanos)
        }

        // Begin frame
        if (!renderer.beginFrame(swapChain, frameTimeNanos)) {
            return
        }

        // Render each visible container's view
        for (container in containers) {
            container.view?.let { view ->
                renderer.render(view)
            }
        }

        // End frame
        renderer.endFrame()

        lastFrameTimeNanos = frameTimeNanos
        lastRenderedFrameTimeNanos = frameTimeNanos
        totalFrames++
    }

    private fun renderEmptyFrame(renderer: Renderer, swapChain: SwapChain, frameTimeNanos: Long) {
        if (renderer.beginFrame(swapChain, frameTimeNanos)) {
            renderer.endFrame()
        }
    }

    /**
     * Render statistics with performance metrics
     */
    data class RenderStats(
        val totalFrames: Long,
        val droppedFrames: Int,
        val currentFps: Float,
        val targetFps: Int,
        val avgFrameTimeMs: Float,
        val containerCount: Int,
        val visibleContainers: Int,
        val culledContainers: Int,
        val animatingContainers: Int,
        val hasActiveAnimations: Boolean,
        val isRunning: Boolean,
        val isPaused: Boolean,
        val onDemandMode: Boolean
    ) {
        override fun toString(): String {
            return "RenderStats(fps=${currentFps.toInt()}/${targetFps}, " +
                    "frames=$totalFrames, dropped=$droppedFrames, " +
                    "avgTime=${avgFrameTimeMs}ms, " +
                    "containers=$visibleContainers/$containerCount, " +
                    "culled=$culledContainers, animating=$animatingContainers)"
        }
    }
}
