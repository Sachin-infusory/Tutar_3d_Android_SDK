package com.infusory.tutarapp.filament.surface

import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.infusory.tutarapp.filament.config.FilamentConfig
import com.infusory.tutarapp.filament.core.FilamentEngineProvider

/**
 * SurfaceManager with adaptive rendering.
 *
 * When beginFrame fails repeatedly, we back off and don't try every frame.
 * This prevents wasting CPU cycles on frames that will fail anyway.
 */
class SurfaceManager(
    private val surfaceView: SurfaceView,
    private val callback: ISurfaceCallback
) {

    companion object {
        private const val TAG = "SurfaceManager"

        // After this many consecutive failures, start skipping frames
        private const val FAILURE_THRESHOLD = 3

        // How many frames to skip after hitting threshold
        private const val SKIP_FRAMES = 2
    }

    private var uiHelper: UiHelper? = null
    private var swapChain: SwapChain? = null
    private var renderer: Renderer? = null

    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile
    private var isReady = false

    var filamentView: View? = null
    var onFrameUpdate: ((Long) -> Unit)? = null

    // Stats
    var frameCount = 0L
        private set
    var successfulFrames = 0L
        private set
    var failedFrames = 0L
        private set
    var skippedFrames = 0L
        private set

    // Adaptive skip logic
    private var consecutiveFailures = 0
    private var framesToSkip = 0

    fun initialize() {
        surfaceView.holder.setFormat(FilamentConfig.SURFACE_FORMAT)
        surfaceView.setZOrderOnTop(FilamentConfig.Z_ORDER_ON_TOP)

        renderer = FilamentEngineProvider.createRenderer()
        Log.d(TAG, "Created Renderer: $renderer")

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = false

            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    handleSurfaceChanged(surface)
                }

                override fun onDetachedFromSurface() {
                    handleSurfaceDestroyed()
                }

                override fun onResized(width: Int, height: Int) {
                    handleSurfaceResized(width, height)
                }
            }

            attachTo(surfaceView)
        }

        Log.d(TAG, "SurfaceManager initialized")
    }

    /**
     * Request a frame to be rendered.
     * Returns true if actually rendered, false if skipped or failed.
     */
    fun requestRender(frameTimeNanos: Long): Boolean {
        if (!isReady) return false

        frameCount++

        // Adaptive skip: if we've failed too many times, skip some frames
        if (framesToSkip > 0) {
            framesToSkip--
            skippedFrames++
            // Still update animation even when skipping render
            onFrameUpdate?.invoke(frameTimeNanos)
            return false
        }

        val r = renderer ?: return false
        val sc = swapChain ?: return false
        val v = filamentView ?: return false

        // Update animations
        onFrameUpdate?.invoke(frameTimeNanos)

        // Try to render
        if (r.beginFrame(sc, frameTimeNanos)) {
            r.render(v)
            r.endFrame()
            successfulFrames++

            // Reset failure counter on success
            consecutiveFailures = 0
            return true
        } else {
            failedFrames++
            consecutiveFailures++

            // If too many failures, start skipping
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                framesToSkip = SKIP_FRAMES
                consecutiveFailures = 0
            }

            return false
        }
    }

    fun getSwapChain(): SwapChain? = swapChain
    fun getRenderer(): Renderer? = renderer
    fun isReady(): Boolean = isReady && swapChain != null
    fun getViewportWidth(): Int = viewportWidth
    fun getViewportHeight(): Int = viewportHeight

    fun destroy() {
        Log.d(TAG, "Destroying SurfaceManager")

        isReady = false

        swapChain?.let {
            try {
                FilamentEngineProvider.destroySwapChain(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SwapChain", e)
            }
        }
        swapChain = null

        renderer?.let {
            try {
                FilamentEngineProvider.destroyRenderer(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying Renderer", e)
            }
        }
        renderer = null

        uiHelper?.detach()
        uiHelper = null

        filamentView = null
        onFrameUpdate = null
    }

    private fun handleSurfaceChanged(surface: Surface) {
        Log.d(TAG, "Surface changed")

        swapChain?.let {
            try {
                FilamentEngineProvider.destroySwapChain(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying old SwapChain", e)
            }
        }

        try {
            // Try without transparency flag - some GPUs have issues with it
            swapChain = FilamentEngineProvider.createSwapChain(surface)
            isReady = true
            consecutiveFailures = 0
            framesToSkip = 0
            callback.onSurfaceAvailable(surface)
            Log.d(TAG, "SwapChain created: $swapChain")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SwapChain", e)
            isReady = false
        }
    }

    private fun handleSurfaceResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportWidth = width
        viewportHeight = height
        callback.onSurfaceResized(width, height)
        Log.d(TAG, "Surface resized: ${width}x${height}")
    }

    private fun handleSurfaceDestroyed() {
        Log.d(TAG, "Surface destroyed")
        isReady = false

        swapChain?.let {
            try {
                FilamentEngineProvider.destroySwapChain(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SwapChain", e)
            }
        }
        swapChain = null

        callback.onSurfaceDestroyed()
    }
}