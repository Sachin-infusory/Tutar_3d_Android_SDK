package com.infusory.tutarapp.filament

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.infusory.tutarapp.filament.core.FilamentEngineProvider
import com.infusory.tutarapp.filament.core.FilamentRenderLoop
import com.infusory.tutarapp.filament.core.FilamentResourceManager
import com.infusory.tutarapp.filament.renderer.Container3DRenderer

/**
 * Facade for the Filament rendering system.
 * Coordinates all Filament components and provides a simple API for creating 3D containers.
 *
 * Architecture:
 * - Single shared Engine (FilamentEngineProvider)
 * - Single shared Renderer (FilamentEngineProvider)
 * - Single shared ResourceManager (materials, asset loader)
 * - Each container has its OWN Scene (isolation)
 * - Single render loop dispatches to all containers
 */
object FilamentEngineManager {

    private const val TAG = "FilamentEngineManager"

    // Core components
    private var resourceManager: FilamentResourceManager? = null
    private val renderLoop = FilamentRenderLoop()

    // State
    @Volatile
    private var isInitialized = false
    private var applicationContext: Context? = null

    /**
     * Initialize the Filament engine. Call once from Application.onCreate().
     * @param context Application context
     * @return true if initialization successful
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }

        applicationContext = context.applicationContext

        try {
            // Initialize engine provider first
            if (!FilamentEngineProvider.initialize()) {
                Log.e(TAG, "Failed to initialize FilamentEngineProvider")
                return false
            }

            // Initialize resource manager
            resourceManager = FilamentResourceManager(applicationContext!!).also { manager ->
                if (!manager.initialize()) {
                    Log.e(TAG, "Failed to initialize FilamentResourceManager")
                    cleanup()
                    return false
                }
            }

            isInitialized = true
            Log.d(TAG, "FilamentEngineManager initialized successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FilamentEngineManager", e)
            cleanup()
            return false
        }
    }

    /**
     * Create a new 3D renderer for a SurfaceView
     * @param surfaceView The SurfaceView to render to
     * @return Container3DRenderer instance
     */
    fun createRenderer(surfaceView: SurfaceView): Container3DRenderer {
        checkInitialized()

        val renderer = Container3DRenderer(surfaceView, resourceManager!!)

        // Register with render loop and get ID
        val id = renderLoop.register(renderer)

        // Set up callback to notify render loop when surface becomes ready
        renderer.onActiveStateChanged = {
            renderLoop.markActiveListDirty()
        }

        // Initialize the renderer with its ID
        renderer.initialize(id)

        Log.d(TAG, "Created renderer $id (total: ${renderLoop.getRenderableCount()})")

        return renderer
    }

    /**
     * Destroy a renderer and release its resources
     */
    fun destroyRenderer(renderer: Container3DRenderer) {
        val id = renderer.rendererId

        // Clear callback
        renderer.onActiveStateChanged = null

        // Unregister from render loop
        renderLoop.unregister(id)

        // Destroy the renderer
        renderer.destroy()

        Log.d(TAG, "Destroyed renderer $id (total: ${renderLoop.getRenderableCount()})")
    }

    /**
     * Pause rendering (call from Activity.onPause)
     */
    fun pauseRendering() {
        if (!isInitialized) return

        renderLoop.pauseAll()
        renderLoop.stop()

        Log.d(TAG, "Rendering paused")
    }

    /**
     * Resume rendering (call from Activity.onResume)
     */
    fun resumeRendering() {
        if (!isInitialized) return

        if (renderLoop.getRenderableCount() > 0) {
            renderLoop.start()
        }
        renderLoop.resumeAll()

        Log.d(TAG, "Rendering resumed")
    }

    /**
     * Shutdown and release all resources (call from Application.onTerminate)
     */
    @Synchronized
    fun shutdown() {
        if (!isInitialized) return

        Log.d(TAG, "Shutting down FilamentEngineManager...")

        cleanup()

        Log.d(TAG, "FilamentEngineManager shutdown complete")
    }

    /**
     * Check if the engine is initialized
     */
    fun isInitialized(): Boolean = isInitialized

    /**
     * Get the number of active renderers
     */
    fun getRendererCount(): Int = renderLoop.getRenderableCount()

    /**
     * Get the number of currently active (rendering) containers
     */
    fun getActiveCount(): Int = renderLoop.getActiveCount()

    /**
     * Get render loop statistics for debugging
     */
    fun getStats(): FilamentRenderLoop.FrameStats = renderLoop.getStats()

    /**
     * Get the resource manager (for advanced usage)
     */
    fun getResourceManager(): FilamentResourceManager {
        checkInitialized()
        return resourceManager!!
    }

    /**
     * Get the render loop (for advanced usage)
     */
    fun getRenderLoop(): FilamentRenderLoop = renderLoop

    // ========== Private Methods ==========

    private fun cleanup() {
        // Stop render loop
        renderLoop.stop()

        // Destroy resource manager
        resourceManager?.destroy()
        resourceManager = null

        // Shutdown engine provider
        FilamentEngineProvider.shutdown()

        isInitialized = false
        applicationContext = null
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("FilamentEngineManager not initialized. Call initialize() first.")
        }
    }
}