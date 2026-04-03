package com.infusory.tutarapp.filament.unified

import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.Viewport
import com.infusory.tutarapp.filament.core.FilamentEngineProvider
import com.infusory.tutarapp.filament.core.FilamentResourceManager
import com.infusory.tutarapp.filament.renderer.CameraController
import com.infusory.tutarapp.filament.renderer.ModelTransformHelper
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages multiple Filament scenes and views for unified viewport-based rendering.
 *
 * OPTIMIZATIONS:
 * - Frustum culling: Skip rendering containers completely off-screen
 * - Partial visibility detection: Pause animations for off-screen containers
 * - Visibility state caching: Avoid redundant visibility checks
 */
class UnifiedSceneManager(
    private val resourceManager: FilamentResourceManager
) {
    companion object {
        private const val TAG = "UnifiedSceneManager"
        private const val MAX_CONTAINERS = 10

        // Culling thresholds
        private const val OFFSCREEN_MARGIN = 50 // pixels - allow some margin for smooth transitions
    }

    // Container storage
    private val containers = ConcurrentHashMap<Int, VirtualContainer3D>()
    private val idGenerator = AtomicInteger(0)

    // Surface dimensions (needed for viewport calculations and culling)
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Visibility cache - tracks which containers are on-screen
    private val visibilityCache = ConcurrentHashMap<Int, Boolean>()

    // Performance tracking
    private var lastCulledCount = 0
    private var lastVisibleCount = 0

    // Callbacks
    var onContainerLoadingStarted: ((Int) -> Unit)? = null
    var onContainerLoadingCompleted: ((Int) -> Unit)? = null
    var onContainerLoadingFailed: ((Int, String) -> Unit)? = null

    /**
     * Update surface dimensions (called when surface is created/resized)
     */
    fun setSurfaceDimensions(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        // Invalidate visibility cache on surface change
        visibilityCache.clear()

        // Update all container viewports with new surface size
        containers.values.forEach { container ->
            updateContainerViewport(container)
            // Recalculate visibility
            updateContainerVisibility(container)
        }

        Log.d(TAG, "Surface dimensions set: ${width}x${height}, ${containers.size} viewports updated")
    }

    /**
     * Create a new virtual container
     */
    fun createContainer(
        viewportX: Int = 0,
        viewportY: Int = 0,
        viewportWidth: Int = 400,
        viewportHeight: Int = 350
    ): VirtualContainer3D {

        if (containers.size >= MAX_CONTAINERS) {
            Log.w(TAG, "Maximum container limit reached ($MAX_CONTAINERS)")
        }

        val id = idGenerator.incrementAndGet()

        val container = VirtualContainer3D(
            id = id,
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            zOrder = containers.size // New containers on top
        )

        // Initialize Filament objects for this container
        initializeContainerFilament(container)

        // Calculate initial visibility
        updateContainerVisibility(container)

        containers[id] = container

        Log.d(TAG, "Created virtual container $id (total: ${containers.size})")

        return container
    }

    /**
     * Get a container by ID
     */
    fun getContainer(id: Int): VirtualContainer3D? = containers[id]

    /**
     * Get all containers sorted by z-order (lowest first for rendering)
     * WITH CULLING: Only returns visible, on-screen containers
     */
    fun getContainersSortedByZOrder(): List<VirtualContainer3D> {
        val allContainers = containers.values
            .filter { it.isVisible && it.isInitialized }

        // Apply culling - only render containers that are on-screen
        val visibleContainers = allContainers.filter { container ->
            isContainerOnScreen(container)
        }

        // Update stats
        lastVisibleCount = visibleContainers.size
        lastCulledCount = allContainers.size - visibleContainers.size

        if (lastCulledCount > 0) {
            Log.v(TAG, "Culled $lastCulledCount containers (rendering ${lastVisibleCount})")
        }

        return visibleContainers.sortedBy { it.zOrder }
    }

    /**
     * Get container at a screen point (for touch routing)
     * Returns the topmost container (highest z-order) at that point
     */
    fun getContainerAtPoint(screenX: Float, screenY: Float): VirtualContainer3D? {
        return containers.values
            .filter { it.isVisible && it.isInitialized && it.containsPoint(screenX, screenY) }
            .maxByOrNull { it.zOrder }
    }

    /**
     * Update container viewport bounds
     */
    fun updateContainerBounds(id: Int, x: Int, y: Int, width: Int, height: Int) {
        containers[id]?.let { container ->
            container.updateBounds(x, y, width, height)
            updateContainerViewport(container)

            // Update visibility state
            updateContainerVisibility(container)

            Log.d(TAG, "Updated container $id bounds: [$x, $y, ${width}x${height}]")
        }
    }

    /**
     * Update container visibility
     */
    fun setContainerVisible(id: Int, visible: Boolean) {
        containers[id]?.let { container ->
            container.isVisible = visible

            // Update visibility cache
            if (!visible) {
                visibilityCache[id] = false
            } else {
                updateContainerVisibility(container)
            }

            Log.d(TAG, "Container $id visibility: $visible")
        }
    }

    /**
     * Bring container to front (highest z-order)
     */
    fun bringToFront(id: Int) {
        containers[id]?.let { container ->
            val maxZ = containers.values.maxOfOrNull { it.zOrder } ?: 0
            container.zOrder = maxZ + 1
            Log.d(TAG, "Container $id brought to front (z=${container.zOrder})")
        }
    }

    /**
     * Load a model into a container
     */
    fun loadModel(containerId: Int, buffer: ByteBuffer) {
        val container = containers[containerId] ?: run {
            Log.e(TAG, "Container $containerId not found")
            onContainerLoadingFailed?.invoke(containerId, "Container not found")
            return
        }

        if (!container.isInitialized) {
            // Defer loading until initialized
            container.pendingModelBuffer = buffer
            Log.d(TAG, "Container $containerId not initialized, deferring model load")
            return
        }

        onContainerLoadingStarted?.invoke(containerId)

        try {
            // Unload existing model
            unloadContainerModel(container)

            // Load new model
            val asset = resourceManager.loadAsset(buffer)
            if (asset == null) {
                onContainerLoadingFailed?.invoke(containerId, "Failed to load asset")
                return
            }

            container.asset = asset

            // Add to container's scene
            container.scene?.let { scene ->
                resourceManager.addAssetToScene(asset, scene)
            }

            // Transform to unit cube
            ModelTransformHelper.transformToUnitCube(asset)

            // Initialize animation controller
            container.animationController = AnimationControllerUnified().apply {
                initialize(asset.instance.animator)
            }

            // Reset camera
            container.cameraController?.resetToDefault()

            container.isModelLoaded = true

            onContainerLoadingCompleted?.invoke(containerId)
            Log.d(TAG, "Model loaded for container $containerId")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading model for container $containerId", e)
            onContainerLoadingFailed?.invoke(containerId, "Error: ${e.message}")
        }
    }

    /**
     * Load a model from file path
     */
    fun loadModelFromPath(containerId: Int, modelPath: String, modelBuffer: ByteBuffer) {
        containers[containerId]?.let { container ->
            container.modelPath = modelPath
        }
        loadModel(containerId, modelBuffer)
    }

    /**
     * Destroy a container and release its resources
     */
    fun destroyContainer(id: Int) {
        val container = containers.remove(id) ?: return

        Log.d(TAG, "Destroying container $id")

        // Remove from visibility cache
        visibilityCache.remove(id)

        // Unload model
        unloadContainerModel(container)

        // Destroy Filament objects
        destroyContainerFilament(container)

        Log.d(TAG, "Container $id destroyed (remaining: ${containers.size})")
    }

    /**
     * Update animations for all containers
     * WITH OPTIMIZATION: Only update animations for on-screen containers
     */
    fun updateAnimations(frameTimeNanos: Long) {
        containers.values.forEach { container ->
            if (container.isVisible && container.isModelLoaded) {
                // Only update animations for containers that are on-screen
                val isOnScreen = visibilityCache[container.id] ?: false

                if (isOnScreen) {
                    container.animationController?.update(frameTimeNanos)
                }
            }
        }
    }

    /**
     * Check if any container has active animations
     */
    fun hasAnyAnimations(): Boolean {
        return containers.values.any { container ->
            container.isVisible &&
                    container.isModelLoaded &&
                    container.animationController?.hasAnimations() == true &&
                    !container.animationController!!.isPaused()
        }
    }

    /**
     * Check if any on-screen container has active animations
     */
    fun hasVisibleAnimations(): Boolean {
        return containers.values.any { container ->
            container.isVisible &&
                    container.isModelLoaded &&
                    (visibilityCache[container.id] ?: false) &&
                    container.animationController?.hasAnimations() == true &&
                    !container.animationController!!.isPaused()
        }
    }

    /**
     * Get the number of active containers
     */
    fun getContainerCount(): Int = containers.size

    /**
     * Get culling statistics
     */
    fun getCullingStats(): CullingStats {
        return CullingStats(
            totalContainers = containers.size,
            visibleContainers = lastVisibleCount,
            culledContainers = lastCulledCount,
            animatingContainers = containers.values.count {
                it.animationController?.hasAnimations() == true &&
                        !it.animationController!!.isPaused()
            }
        )
    }

    /**
     * Destroy all containers
     */
    fun destroyAll() {
        Log.d(TAG, "Destroying all ${containers.size} containers")

        containers.keys.toList().forEach { id ->
            destroyContainer(id)
        }

        visibilityCache.clear()
    }

    // ==================== Private Methods ====================

    /**
     * Check if a container's viewport is on-screen (with margin)
     * Uses cached result when possible for performance
     */
    private fun isContainerOnScreen(container: VirtualContainer3D): Boolean {
        // Return cached value if available
        visibilityCache[container.id]?.let { return it }

        // Calculate and cache
        val isVisible = calculateOnScreenState(container)
        visibilityCache[container.id] = isVisible
        return isVisible
    }

    /**
     * Calculate if container is on-screen (actual computation)
     */
    private fun calculateOnScreenState(container: VirtualContainer3D): Boolean {
        // Container is off-screen if:
        // - Right edge is before left edge of screen (with margin)
        // - Left edge is after right edge of screen (with margin)
        // - Bottom edge is before top edge of screen (with margin)
        // - Top edge is after bottom edge of screen (with margin)

        val containerRight = container.viewportX + container.viewportWidth
        val containerBottom = container.viewportY + container.viewportHeight

        val isOffScreenLeft = containerRight < -OFFSCREEN_MARGIN
        val isOffScreenRight = container.viewportX > surfaceWidth + OFFSCREEN_MARGIN
        val isOffScreenTop = containerBottom < -OFFSCREEN_MARGIN
        val isOffScreenBottom = container.viewportY > surfaceHeight + OFFSCREEN_MARGIN

        return !(isOffScreenLeft || isOffScreenRight || isOffScreenTop || isOffScreenBottom)
    }

    /**
     * Update container's visibility state in cache
     * Call this when container bounds change
     */
    private fun updateContainerVisibility(container: VirtualContainer3D) {
        val wasVisible = visibilityCache[container.id] ?: true
        val isVisible = calculateOnScreenState(container)

        visibilityCache[container.id] = isVisible

        // Log visibility changes
        if (wasVisible != isVisible) {
            Log.d(TAG, "Container ${container.id} visibility changed: $wasVisible -> $isVisible")
        }
    }

    private fun initializeContainerFilament(container: VirtualContainer3D) {
        try {
            // Create dedicated scene for this container
            container.scene = FilamentEngineProvider.createScene()
            container.scene?.let { scene ->
                resourceManager.applyIndirectLightToScene(scene)
            }

            // Create view with this scene
            container.view = FilamentEngineProvider.createView()
            container.view?.scene = container.scene

            // Create camera
            container.camera = FilamentEngineProvider.createCamera()
            container.view?.camera = container.camera

            // Initialize camera controller WITHOUT setting viewport
            // We'll manage viewport ourselves
            container.cameraController = CameraController().apply {
                initialize(container.camera!!, container.view!!)
            }

            // Set initial viewport position
            if (container.viewportWidth > 0 && container.viewportHeight > 0) {
                updateContainerViewport(container)
            }

            container.isInitialized = true

            // Load pending model if any
            container.pendingModelBuffer?.let { buffer ->
                container.pendingModelBuffer = null
                loadModel(container.id, buffer)
            }

            Log.d(TAG, "Initialized Filament objects for container ${container.id}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Filament for container ${container.id}", e)
            container.isInitialized = false
        }
    }

    private fun destroyContainerFilament(container: VirtualContainer3D) {
        container.cameraController?.clear()
        container.cameraController = null

        container.camera?.let { FilamentEngineProvider.destroyCamera(it) }
        container.camera = null

        container.view?.let { FilamentEngineProvider.destroyView(it) }
        container.view = null

        container.scene?.let { FilamentEngineProvider.destroyScene(it) }
        container.scene = null

        container.isInitialized = false
    }

    private fun unloadContainerModel(container: VirtualContainer3D) {
        container.asset?.let { asset ->
            container.scene?.let { scene ->
                resourceManager.removeAssetFromScene(asset, scene)
            }
            resourceManager.destroyAsset(asset)
        }
        container.asset = null
        container.animationController?.clear()
        container.animationController = null
        container.isModelLoaded = false
    }

    private fun updateContainerViewport(container: VirtualContainer3D) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        if (container.viewportWidth <= 0 || container.viewportHeight <= 0) return

        container.view?.let { view ->
            // Filament's Viewport uses bottom-left origin (OpenGL convention)
            // Convert from Android's top-left origin
            val viewportX = container.viewportX
            val viewportY = surfaceHeight - container.viewportY - container.viewportHeight

            // IMPORTANT: Allow negative coordinates for partial off-screen rendering
            // Filament will clip the rendering to the visible portion

            view.viewport = Viewport(
                viewportX,
                viewportY,
                container.viewportWidth,
                container.viewportHeight
            )

            // Update camera projection for the viewport aspect ratio
            container.camera?.setProjection(
                45.0,  // FOV
                container.viewportWidth.toDouble() / container.viewportHeight.toDouble(),
                0.1,   // near
                100.0, // far
                Camera.Fov.VERTICAL
            )
        }
    }

    /**
     * Statistics for culling performance
     */
    data class CullingStats(
        val totalContainers: Int,
        val visibleContainers: Int,
        val culledContainers: Int,
        val animatingContainers: Int
    )
}