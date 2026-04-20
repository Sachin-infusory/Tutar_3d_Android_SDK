package com.infusory.tutarapp.filament.renderer

import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.gltfio.FilamentAsset
import com.infusory.lib3drenderer.containerview.label.GlbLabelExtractor
import com.infusory.tutarapp.filament.core.FilamentEngineProvider
import com.infusory.tutarapp.filament.core.FilamentResourceManager
import com.infusory.tutarapp.filament.core.IRenderable
import com.infusory.tutarapp.filament.surface.ISurfaceCallback
import com.infusory.tutarapp.filament.surface.SurfaceManager
import java.nio.ByteBuffer

/**
 * Container3DRenderer - each container has its own Renderer via SurfaceManager.
 */
class Container3DRenderer(
    private val surfaceView: SurfaceView,
    private val resourceManager: FilamentResourceManager
) : IRenderable, ISurfaceCallback {

    companion object {
        private const val TAG = "Container3DRenderer"
    }

    override var rendererId: Int = -1
        private set

    private var surfaceManager: SurfaceManager? = null

    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var asset: FilamentAsset? = null

    private val animationController = AnimationController()
    private val cameraController = CameraController()

    private var isInitialized = false
    private var isPaused = false
    private var pendingModelBuffer: ByteBuffer? = null

    // Label entity tracking
    private var labelEntities: List<Pair<Int, GlbLabelExtractor.LabelInfo>> = emptyList()
    private val _worldMatrix = FloatArray(16) // reusable per-frame, avoids allocation
    private var _labelPositions: MutableList<FloatArray>? = null

    var onActiveStateChanged: (() -> Unit)? = null
    var onLoadingStarted: (() -> Unit)? = null
    var onLoadingCompleted: (() -> Unit)? = null
    var onLoadingFailed: ((String) -> Unit)? = null
    var onLabelsExtracted: ((List<GlbLabelExtractor.LabelInfo>) -> Unit)? = null
    var onFrameCallback: (() -> Unit)? = null

    fun initialize(id: Int) {
        if (isInitialized) return

        rendererId = id

        try {
            Log.d(TAG, "[$rendererId] Initializing...")

            scene = FilamentEngineProvider.createScene()
            if (scene == null) {
                Log.e(TAG, "[$rendererId] Failed to create scene")
                onLoadingFailed?.invoke("Failed to create scene")
                return
            }
            resourceManager.applyIndirectLightToScene(scene!!)

            view = FilamentEngineProvider.createView()
            if (view == null) {
                Log.e(TAG, "[$rendererId] Failed to create view")
                onLoadingFailed?.invoke("Failed to create view")
                return
            }
            view?.scene = scene

            camera = FilamentEngineProvider.createCamera()
            if (camera == null) {
                Log.e(TAG, "[$rendererId] Failed to create camera")
                onLoadingFailed?.invoke("Failed to create camera")
                return
            }
            view?.camera = camera

            cameraController.initialize(camera!!, view!!)

            surfaceManager = SurfaceManager(surfaceView, this)
            surfaceManager?.filamentView = view
            surfaceManager?.onFrameUpdate = { frameTimeNanos ->
                animationController.update(frameTimeNanos)
                onFrameCallback?.invoke()
            }
            surfaceManager?.initialize()

            isInitialized = true
            Log.d(TAG, "[$rendererId] Initialized")

            pendingModelBuffer?.let {
                loadModel(it)
                pendingModelBuffer = null
            }

        } catch (e: Exception) {
            Log.e(TAG, "[$rendererId] Failed to initialize", e)
            onLoadingFailed?.invoke("Init failed: ${e.message}")
        }
    }

    fun loadModel(buffer: ByteBuffer) {
        if (!isInitialized) {
            pendingModelBuffer = buffer
            return
        }

        Log.d(TAG, "[$rendererId] Loading model...")
        onLoadingStarted?.invoke()

        try {
            unloadCurrentAsset()

            asset = resourceManager.loadAsset(buffer)
            if (asset == null) {
                onLoadingFailed?.invoke("Failed to load model")
                return
            }

            scene?.let { resourceManager.addAssetToScene(asset!!, it) }

            ModelTransformHelper.transformToUnitCube(asset!!)
            animationController.initialize(asset!!.instance.animator)
            cameraController.resetToDefault()

            onLoadingCompleted?.invoke()
            Log.d(TAG, "[$rendererId] Model loaded")

            // Extract labels and resolve to Filament entities
            try {
                val labels = GlbLabelExtractor.extractLabels(buffer)
                if (labels.isNotEmpty()) {
                    labelEntities = labels.mapNotNull { labelInfo ->
                        val entity = asset?.getFirstEntityByName(labelInfo.nodeName) ?: 0
                        if (entity != 0) {
                            Log.d(TAG, "Resolved label entity: ${labelInfo.nodeName} → $entity")
                            Pair(entity, labelInfo)
                        } else {
                            Log.w(TAG, "Label entity not found: ${labelInfo.nodeName}")
                            null
                        }
                    }
                }
                onLabelsExtracted?.invoke(labels)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "[$rendererId] Load failed", e)
            onLoadingFailed?.invoke("Error: ${e.message}")
        }
    }

    private fun unloadCurrentAsset() {
        asset?.let {
            scene?.let { s -> resourceManager.removeAssetFromScene(it, s) }
            resourceManager.destroyAsset(it)
            asset = null
            animationController.clear()
            labelEntities = emptyList()
            _labelPositions = null
        }
    }

    // ========== Label World Positions ==========

    /**
     * Query Filament's TransformManager for the current world position
     * of each labeled node. Returns null if no labels or engine unavailable.
     * Call this each frame for animated models.
     */
    fun getLabelWorldPositions(): List<FloatArray>? {
        if (labelEntities.isEmpty()) return null
        val engine = FilamentEngineProvider.getEngine() ?: return null
        val tm = engine.transformManager

        // Reuse list, only allocate once
        if (_labelPositions == null || _labelPositions!!.size != labelEntities.size) {
            _labelPositions = labelEntities.map { floatArrayOf(0f, 0f, 0f) }.toMutableList()
        }

        for (i in labelEntities.indices) {
            val (entity, _) = labelEntities[i]
            val instance = tm.getInstance(entity)
            if (instance != 0) {
                tm.getWorldTransform(instance, _worldMatrix)
                _labelPositions!![i][0] = _worldMatrix[12]
                _labelPositions!![i][1] = _worldMatrix[13]
                _labelPositions!![i][2] = _worldMatrix[14]
            }
        }
        return _labelPositions
    }

    // ========== IRenderable ==========

    override fun isActive(): Boolean {
        val surfaceReady = surfaceManager?.isReady() == true
        return isInitialized && !isPaused && surfaceReady
    }

    override fun updateAnimations(frameTimeNanos: Long) {}

    override fun getSwapChain(): SwapChain? = surfaceManager?.getSwapChain()

    override fun getView(): View? = view

    override fun getRenderer(): Renderer? = surfaceManager?.getRenderer()

    fun requestRender(frameTimeNanos: Long) {
        surfaceManager?.requestRender(frameTimeNanos)
    }

    fun getSuccessRate(): Int {
        val sm = surfaceManager ?: return 0
        val total = sm.successfulFrames + sm.failedFrames
        return if (total > 0) (sm.successfulFrames * 100 / total).toInt() else 0
    }

    fun getSuccessfulFrames(): Long = surfaceManager?.successfulFrames ?: 0
    fun getFailedFrames(): Long = surfaceManager?.failedFrames ?: 0
    fun getTotalFrames(): Long = surfaceManager?.frameCount ?: 0

    override fun onPause() {
        isPaused = true
        onActiveStateChanged?.invoke()
    }

    override fun onResume() {
        isPaused = false
        onActiveStateChanged?.invoke()
    }

    override fun destroy() {
        Log.d(TAG, "[$rendererId] Destroying...")

        isInitialized = false

        unloadCurrentAsset()
        animationController.clear()
        cameraController.clear()

        surfaceManager?.destroy()
        surfaceManager = null

        camera?.let { FilamentEngineProvider.destroyCamera(it) }
        camera = null

        view?.let { FilamentEngineProvider.destroyView(it) }
        view = null

        scene?.let { FilamentEngineProvider.destroyScene(it) }
        scene = null

        rendererId = -1
    }

    // ========== ISurfaceCallback ==========

    override fun onSurfaceAvailable(surface: Surface) {
        Log.d(TAG, "[$rendererId] Surface available")
        onActiveStateChanged?.invoke()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        cameraController.setViewport(width, height)
    }

    override fun onSurfaceDestroyed() {
        Log.d(TAG, "[$rendererId] Surface destroyed")
        onActiveStateChanged?.invoke()
    }

    // ========== Animation Control ==========

    fun toggleAnimation(): Boolean? {
        if (!animationController.hasAnimations()) return null
        return animationController.togglePause()
    }

    fun isAnimationPaused(): Boolean = animationController.isPaused()
    fun getAnimationCount(): Int = animationController.getAnimationCount()
    fun getAnimationNames(): List<String> = animationController.getAnimationNames()
    fun hasAnimations(): Boolean = animationController.hasAnimations()

    // ========== Camera Control ==========

    fun onTouchEvent(event: MotionEvent): Boolean = cameraController.onTouchEvent(event)

    fun saveCameraState(): CameraController.CameraState? = cameraController.saveState()

    fun restoreCameraState(state: CameraController.CameraState) = cameraController.restoreState(state)

    fun resetCamera() = cameraController.resetToDefault()

    // ========== Label Support ==========

    fun getCamera(): Camera? = camera

    fun getViewportWidth(): Int = cameraController.getViewportWidth()

    fun getViewportHeight(): Int = cameraController.getViewportHeight()
}