package com.infusory.lib3drenderer.containerview

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.infusory.lib3drenderer.Tutar
import com.infusory.lib3drenderer.R
import com.infusory.lib3drenderer.containerview.ModelData
import com.infusory.lib3drenderer.containerview.label.LabelOverlayManager
import com.infusory.tutarapp.filament.FilamentEngineManager
import com.infusory.tutarapp.filament.renderer.CameraController
import com.infusory.tutarapp.filament.renderer.Container3DRenderer
import com.infusory.tutarapp.Model3DContainerManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 3D model display container with drag, resize, and interaction support.
 * Uses shared Filament engine for memory optimization.
 *
 * Created via Tutar.createContainer() when user selects a model.
 */
class Container3D @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    initialModelData: String? = null
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "Container3D"
        const val FOLDER = "models"
        private const val AUTO_HIDE_DELAY = 4000L
        private const val FADE_DURATION = 300L
        private const val MIN_WIDTH = 200
        private const val MIN_HEIGHT = 150
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 3.0f
        private const val RESIZE_HANDLE_SIZE = 24f
        private const val RESIZE_HIT_AREA = 50f
        private const val CORNER_INDICATOR_SIZE = 12
    }

    // Unique ID
    private val containerId = "${System.currentTimeMillis()}_${hashCode()}"

    // Renderer
    private var surfaceView: SurfaceView? = null
    private var renderer: Container3DRenderer? = null

    // Touch state
    private var touchEnabled = false
    private var passThroughTouches = true
    private var isHandling3DTouch = false

    // Layout
    private var controlsContainer: LinearLayout? = null
    private var contentContainer: FrameLayout? = null
    private var animationToggleButton: ImageView? = null
    private var interactionButton: ImageView? = null
    private var labelToggleButton: ImageView? = null
    private val sideControlButtons = mutableListOf<ImageView>()
    private val cornerIndicators = mutableListOf<ImageView>()

    // Labels
    private var labelManager: LabelOverlayManager? = null
    private var modelHasLabels = false

    // Loading shimmer
    private var loadingOverlay: FrameLayout? = null
    private var shimmerView: View? = null
    private var shimmerAnimator: ObjectAnimator? = null
    private var loadingStatusText: TextView? = null

    // State
    private var isViewInitialized = false
    private var isModelLoaded = false
    internal var currentModelPath: String? = initialModelData
    internal var currentModelData: ModelData? = null
    private var savedCameraState: CameraController.CameraState? = null

    // Audio
    private var audioPlayer: ModelAudioPlayer? = null

    private var resolvedModelPath: String? = null

    // Auto-hide
    private var autoHideEnabled = true
    private var hideRunnable: Runnable? = null
    private var controlsVisible = true

    // Screen bounds
    private var screenWidth = 0
    private var screenHeight = 0

    // Drag/resize
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var isResizing = false
    private var isPinching = false
    private var activeCorner = Corner.NONE
    private var initialWidth = 0
    private var initialHeight = 0
    private var scaleFactor = 1f
    private var initialDistance = 0f

    // Resize snapshot optimization
    private var resizeSnapshot: Bitmap? = null
    private var snapshotView: ImageView? = null
    private val snapshotHandler = Handler(Looper.getMainLooper())

    private var lastLabelUpdateTime = 0L
    private val LABEL_UPDATE_INTERVAL_MS = 66L

    // Callbacks
    var onLoadingStarted: (() -> Unit)? = null
    var onLoadingCompleted: (() -> Unit)? = null
    var onLoadingFailed: ((String) -> Unit)? = null
    var onRemoveRequest: (() -> Unit)? = null
        set(value) { field = { cleanup(); value?.invoke() } }

    enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var initialCenterX = 0f
            private var initialCenterY = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                initialWidth = width
                initialHeight = height
                initialCenterX = translationX + width / 2f
                initialCenterY = translationY + height / 2f
                scaleFactor = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val dampenedDelta = 1f + (detector.scaleFactor - 1f) * 0.3f
                scaleFactor = (scaleFactor * dampenedDelta).coerceIn(MIN_SCALE, MAX_SCALE)
                resizeFromCenter(
                    (initialWidth * scaleFactor).toInt(),
                    (initialHeight * scaleFactor).toInt(),
                    initialCenterX,
                    initialCenterY
                )
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinching = false
                scaleFactor = 1f
                initialDistance = 0f
            }
        })

    init {
        layoutParams = ViewGroup.MarginLayoutParams(dpToPx(400), dpToPx(350))
        hideRunnable = Runnable { hideControls() }

        val dm = context.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        Model3DContainerManager.registerContainer(this)
        Log.d(TAG, "Container created: $containerId")
    }

    // ==================== Public API ====================

    fun getContainerId(): String = containerId

    fun setTouchEnabled(enabled: Boolean) {
        touchEnabled = enabled
        updateTouchHandling()
    }

    fun setPassThroughTouches(enabled: Boolean) {
        passThroughTouches = enabled
        updateTouchHandling()
    }

    fun setModelData(modelPath: String) {
        Log.d(TAG, "[$containerId] setModelData: $modelPath")
        currentModelPath = modelPath
        currentModelData = null
        initializeAndLoad()
    }

    fun setModelData(modelData: ModelData, fullPath: String) {
        Log.d(TAG, "[$containerId] setModelData: ${modelData.name}, $fullPath")
        currentModelData = modelData
        currentModelPath = fullPath
        initializeAndLoad()
    }

    fun setAutoHideEnabled(enabled: Boolean) {
        autoHideEnabled = enabled
        if (enabled) scheduleAutoHide() else { cancelAutoHide(); showControls() }
    }

    fun pauseRendering() {
        renderer?.onPause()
        audioPlayer?.pause()
    }

    fun resumeRendering() {
        renderer?.onResume()
        audioPlayer?.play()
    }

    // ==================== Label API ====================

    fun showLabels() = labelManager?.showLabels()
    fun hideLabels() = labelManager?.hideLabels()
    fun toggleModelLabels() = labelManager?.toggleLabels()
    fun hasLabels(): Boolean = modelHasLabels
    fun isLabelsVisible(): Boolean = labelManager?.isLabelsVisible() == true

    // ==================== Lifecycle ====================

    fun cleanup() {
        Log.d(TAG, "[$containerId] cleanup()")
        Model3DContainerManager.unregisterContainer(this)
        destroy()
    }

    fun destroy() {
        Log.d(TAG, "[$containerId] destroy()")
        isViewInitialized = false
        isModelLoaded = false
        modelHasLabels = false
        cancelAutoHide()
        hideLoading()
        releaseResizeSnapshot()
        audioPlayer?.release()
        audioPlayer = null
        labelManager?.destroy()
        labelManager = null

        renderer?.let { FilamentEngineManager.destroyRenderer(it) }
        renderer = null
        surfaceView = null

        post { (parent as? ViewGroup)?.removeView(this@Container3D) }
    }

    // ==================== Initialization ====================

    private fun initializeAndLoad() {
        if (!Tutar.isReady()) {
            Log.e(TAG, "[$containerId] SDK not ready!")
            addView(createErrorView("SDK not initialized"))
            return
        }

        if (!isViewInitialized) {
            setupContainer()
        }

        if (isViewInitialized && renderer != null) {
            loadModel()
        }
    }

    private fun setupContainer() {
        if (isViewInitialized) return

        if (!FilamentEngineManager.isInitialized()) {
            Log.e(TAG, "[$containerId] Engine not initialized!")
            addView(createErrorView("3D Engine not ready"))
            return
        }

        removeAllViews()
        createLayout()
        create3DView()
        isViewInitialized = true
        scheduleAutoHide()

        Log.d(TAG, "[$containerId] Setup complete")
    }

    private fun createLayout() {
        val main = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        controlsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.TOP or Gravity.END
        }

        contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundResource(R.drawable.dotted_border_background)
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
        }

        addControlButtons()
        addCornerIndicators()
        main.addView(controlsContainer)
        main.addView(contentContainer)
        addView(main)
    }

    private fun addControlButtons() {
        controlsContainer?.apply {
            interactionButton = createButton(R.drawable.ic_interact, "Interaction") { toggleInteraction() }
            addView(interactionButton!!)
            sideControlButtons.add(interactionButton!!)
            post { updateInteractionButtonColor() }

            addView(createSpacer())

            animationToggleButton = createButton(R.drawable.ic_pause_animation, "Animation") { toggleAnimation() }
            addView(animationToggleButton!!)
            sideControlButtons.add(animationToggleButton!!)

            addView(createSpacer())

            // Label button (hidden until labels are detected in the model)
            labelToggleButton = createButton(android.R.drawable.ic_menu_info_details, "Labels") {
                labelManager?.toggleLabels()
            }.apply { visibility = GONE }
            addView(labelToggleButton!!)
            sideControlButtons.add(labelToggleButton!!)

            addView(createSpacer())

            val recenter = createButton(R.drawable.ic_recenter, "Recenter") {
                renderer?.resetCamera()
            }
            addView(recenter)
            sideControlButtons.add(recenter)

            addView(createSpacer())

            val close = createButton(R.drawable.ic_close_white, "Close") {
                onRemoveRequest?.invoke() ?: cleanup()
            }
            addView(close)
            sideControlButtons.add(close)
        }
    }

    private fun addCornerIndicators() {
        contentContainer?.let { container ->
            listOf(
                Gravity.TOP or Gravity.START,
                Gravity.TOP or Gravity.END,
                Gravity.BOTTOM or Gravity.START,
                Gravity.BOTTOM or Gravity.END
            ).forEach { gravity ->
                val indicator = createCornerIndicator(gravity)
                container.addView(indicator)
                cornerIndicators.add(indicator)
            }
        }
    }

    private fun createCornerIndicator(gravity: Int) = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            dpToPx(CORNER_INDICATOR_SIZE),
            dpToPx(CORNER_INDICATOR_SIZE)
        ).apply {
            this.gravity = gravity
            val margin = dpToPx(2)
            setMargins(margin, margin, margin, margin)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setStroke(dpToPx(2), Color.GRAY)
            setColor(Color.TRANSPARENT)
            cornerRadius = dpToPx(2).toFloat()
        }
        elevation = 2f
        alpha = 0.7f
    }

    private fun createButton(iconRes: Int, desc: String, action: () -> Unit) = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
            setMargins(0, dpToPx(2), 0, dpToPx(2))
        }
        setImageResource(iconRes)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
        }
        scaleType = ImageView.ScaleType.CENTER
        elevation = 4f
        contentDescription = desc

        setOnClickListener { onInteractionStart(); action(); onInteractionEnd() }
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { onInteractionStart(); alpha = 0.6f; scaleX = 0.95f; scaleY = 0.95f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { alpha = 1f; scaleX = 1f; scaleY = 1f; onInteractionEnd() }
            }
            false
        }
    }

    private fun createSpacer() = android.view.View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(0))
    }

    // ==================== 3D View ====================

    private fun create3DView() {
        try {
            surfaceView = SurfaceView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setZOrderMediaOverlay(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
            }
            contentContainer?.addView(surfaceView!!, 0)

            // Pre-create snapshot overlay
            snapshotView = ImageView(context).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.FIT_XY
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            contentContainer?.addView(snapshotView!!)

            // Pre-create shimmer loading overlay
            createLoadingOverlay()

            renderer = FilamentEngineManager.createRenderer(surfaceView!!).apply {
                onLoadingStarted = { this@Container3D.onLoadingStarted?.invoke() }
                onLoadingCompleted = {
                    isModelLoaded = true
                    if (!isAnimationPaused()) {
                        toggleAnimation()
                    }
                    savedCameraState?.let { restoreCameraState(it) }

                    // Attach and play audio if a sibling audio file exists
                    (resolvedModelPath ?: currentModelPath)?.let { path ->
                        audioPlayer = ModelAudioPlayer().apply {
                            attach(path)
                            play()
                        }
                    }

                    this@Container3D.onLoadingCompleted?.invoke()
                }
                onLoadingFailed = { this@Container3D.onLoadingFailed?.invoke(it) }

                // Label extraction callback
                onLabelsExtracted = { labels ->
                    post {
                        modelHasLabels = labels.isNotEmpty()
                        if (modelHasLabels) {
                            labelManager?.setLabels(labels)
                            labelToggleButton?.visibility = VISIBLE
                            Log.d(TAG, "[$containerId] ${labels.size} labels ready")
                        } else {
                            labelToggleButton?.visibility = GONE
                        }
                    }
                }

                // Per-frame label position
                onFrameCallback = {
                    if (modelHasLabels && labelManager?.isLabelsVisible() == true) {
                        val now = System.currentTimeMillis()
                        if (now - lastLabelUpdateTime > LABEL_UPDATE_INTERVAL_MS) {
                            lastLabelUpdateTime = now
                            labelManager?.updatePositions(
                                getCamera(),
                                getViewportWidth(),
                                getViewportHeight(),
                                getLabelWorldPositions()
                            )
                        }
                    }
                }
            }

            // Initialize label overlay (added on top of SurfaceView)
            labelManager = LabelOverlayManager(context).also { lm ->
                contentContainer?.let { lm.attachTo(it) }
            }

            // Fix z-ordering so labels appear above the 3D scene
//            renderer?.disableZOrderOnTop()

            updateTouchHandling()
            Log.d(TAG, "[$containerId] 3D view created")
        } catch (e: Exception) {
            Log.e(TAG, "[$containerId] Failed to create 3D view", e)
            contentContainer?.addView(createFallbackView())
        }
    }

    // ==================== Loading Shimmer ====================

    private fun createLoadingOverlay() {
        loadingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            shimmerView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(100), FrameLayout.LayoutParams.MATCH_PARENT)
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        Color.parseColor("#00FFFFFF"),
                        Color.parseColor("#12FFFFFF"),
                        Color.parseColor("#00FFFFFF")
                    )
                )
            }
            addView(shimmerView!!)

            loadingStatusText = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dpToPx(12) }
                setTextColor(Color.parseColor("#66FFFFFF"))
                textSize = 10f
                text = "Loading..."
            }
            addView(loadingStatusText!!)
        }
        contentContainer?.addView(loadingOverlay!!)
    }

    private fun showLoading(status: String) {
        loadingStatusText?.text = status
        loadingOverlay?.visibility = View.VISIBLE
        startShimmer()
    }

    private fun hideLoading() {
        stopShimmer()
        loadingOverlay?.visibility = View.GONE
    }

    private fun startShimmer() {
        shimmerAnimator?.cancel()
        val sv = shimmerView ?: return
        val overlay = loadingOverlay ?: return

        overlay.post {
            val sweepWidth = dpToPx(100).toFloat()
            val containerWidth = overlay.width.toFloat()
            if (containerWidth <= 0) return@post

            shimmerAnimator = ObjectAnimator.ofFloat(
                sv, "translationX",
                -sweepWidth,
                containerWidth
            ).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    // ==================== Model Loading ====================

    private fun loadModel() {
        val path = currentModelPath ?: currentModelData?.filename ?: run {
            onLoadingFailed?.invoke("No model path")
            return
        }

        if (renderer == null) {
            Log.d(TAG, "[$containerId] Renderer not ready")
            return
        }

        Log.d(TAG, "[$containerId] Loading: $path")
        showLoading("Loading model...")
        onLoadingStarted?.invoke()

        try {
            loadModelBuffer(path)?.let { buffer ->
                renderer?.loadModel(buffer)
                savedCameraState?.let { renderer?.restoreCameraState(it) }
                hideLoading()
                Log.d(TAG, "[$containerId] Model loaded")
            }
        } catch (e: Exception) {
            hideLoading()
            Log.e(TAG, "[$containerId] Load failed", e)
            onLoadingFailed?.invoke("Error: ${e.message}")
        }
    }

    private fun loadModelBuffer(filename: String): ByteBuffer? {
        val actualFilename = filename.substringAfterLast('/')

        val locations = listOf(
            File(context.filesDir, "$FOLDER/$actualFilename"),
            File(context.getExternalFilesDir(null), "$FOLDER/$actualFilename"),
            File(filename),
            File(context.filesDir, "3d_models/$actualFilename"),
            File(context.filesDir, "encrypted_models/$actualFilename")
        )

        for (file in locations) {
            if (file.exists() && file.canRead()) {
                resolvedModelPath = file.absolutePath
                ModelDecryptionUtil.decryptModelFile(file)?.let { return it }
                try {
                    val bytes = file.readBytes()
                    return ByteBuffer.allocateDirect(bytes.size).apply {
                        order(ByteOrder.nativeOrder())
                        put(bytes)
                        rewind()
                    }
                } catch (_: Exception) {}
            }
        }

        tryLoadFromAssets(actualFilename)?.let { return it }

        Log.e(TAG, "[$containerId] Model not found: $actualFilename")
        hideLoading()
        showError("Model not found!!!")
        onLoadingFailed?.invoke("Model not found: $actualFilename")
        return null
    }

    private fun tryLoadFromAssets(filename: String): ByteBuffer? {
        listOf("models/$filename", filename).forEach { path ->
            try {
                context.assets.open(path).use { input ->
                    val bytes = input.readBytes()
                    return ByteBuffer.allocateDirect(bytes.size).apply {
                        order(ByteOrder.nativeOrder())
                        put(bytes)
                        rewind()
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun clearErrorViews() {
        contentContainer?.let { c ->
            for (i in c.childCount - 1 downTo 0) {
                val child = c.getChildAt(i)
                if (child != surfaceView && child != snapshotView
                    && child != loadingOverlay && child !in cornerIndicators) {
                    c.removeViewAt(i)
                }
            }
        }
    }

    private fun showError(msg: String) {
        clearErrorViews()
        controlsContainer?.visibility = View.GONE
        contentContainer?.background=null;
        cornerIndicators.forEach { it.visibility= View.GONE }
        contentContainer?.addView(createErrorView(msg))

    }

    // ==================== Animation ====================

    private fun toggleAnimation() {
        renderer?.toggleAnimation()?.let { paused ->
            animationToggleButton?.setImageResource(
                if (paused) R.drawable.ic_play_animation else R.drawable.ic_pause_animation
            )
            if (paused) audioPlayer?.pause() else audioPlayer?.play()
        } ?: Log.d("No animations", "No animations are available in this model")
    }

    // ==================== Touch ====================

    private fun updateTouchHandling() {
        surfaceView?.apply {
            if (touchEnabled && !passThroughTouches) {
                setOnTouchListener { _, e ->
                    renderer?.onTouchEvent(e)
                    isHandling3DTouch = e.action !in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> onInteractionStart()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            renderer?.saveCameraState()?.let { savedCameraState = it }
                            onInteractionEnd()
                        }
                    }
                    true
                }
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
            } else {
                setOnTouchListener { _, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> onInteractionStart()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onInteractionEnd()
                    }
                    false
                }
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                isHandling3DTouch = false
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isHandling3DTouch && touchEnabled && !passThroughTouches) {
            if (event.action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                isHandling3DTouch = false
            }
            return true
        }

        scaleGestureDetector.onTouchEvent(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { handleDown(event); true }
            MotionEvent.ACTION_POINTER_DOWN -> { handlePointerDown(event); true }
            MotionEvent.ACTION_MOVE -> { handleMove(event); true }
            MotionEvent.ACTION_UP -> { releaseResizeSnapshot(); resetTouch(); onInteractionEnd(); true }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isPinching = false
                    initialDistance = 0f
                    scaleFactor = 1f
                    releaseResizeSnapshot()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> { releaseResizeSnapshot(); resetTouch(); onInteractionEnd(); true }
            else -> super.onTouchEvent(event)
        }
    }

    private fun handleDown(e: MotionEvent) {
        if (e.pointerCount == 1) {
            lastX = e.rawX
            lastY = e.rawY
            activeCorner = getCorner(e.x, e.y)
            isResizing = activeCorner != Corner.NONE
            isDragging = !isResizing && !isPinching
            if (isResizing) captureResizeSnapshot()
            if (isDragging || isResizing) bringToFront()
            onInteractionStart()
        }
    }

    private fun handlePointerDown(e: MotionEvent) {
        if (e.pointerCount == 2) {
            isPinching = true
            isDragging = false
            isResizing = false
            initialWidth = width
            initialHeight = height
            initialDistance = getDistance(e)
            scaleFactor = 1f
            captureResizeSnapshot()
            onInteractionStart()
        }
    }

    private fun handleMove(e: MotionEvent) {
        if (isPinching && e.pointerCount >= 2) {
            val dist = getDistance(e)
            if (initialDistance > 0) {
                scaleFactor = (1f + (dist / initialDistance - 1f) * 0.2f).coerceIn(MIN_SCALE, MAX_SCALE)
                resizeFromCenter(
                    (initialWidth * scaleFactor).toInt(),
                    (initialHeight * scaleFactor).toInt(),
                    translationX + width / 2f,
                    translationY + height / 2f
                )
                updateSnapshotLayout()
            }
        } else if (e.pointerCount == 1 && !isPinching) {
            val dx = e.rawX - lastX
            val dy = e.rawY - lastY

            if (isDragging) {
                translationX += dx
                translationY += dy
            } else if (isResizing) {
                handleResize(dx, dy)
                updateSnapshotLayout()
            }

            lastX = e.rawX
            lastY = e.rawY
        }
    }

    private fun handleResize(dx: Float, dy: Float) {
        val p = layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var nw = p.width
        var nh = p.height
        var dtx = 0f
        var dty = 0f

        when (activeCorner) {
            Corner.TOP_LEFT -> { nw -= dx.toInt(); nh -= dy.toInt(); dtx = dx; dty = dy }
            Corner.TOP_RIGHT -> { nw += dx.toInt(); nh -= dy.toInt(); dty = dy }
            Corner.BOTTOM_LEFT -> { nw -= dx.toInt(); nh += dy.toInt(); dtx = dx }
            Corner.BOTTOM_RIGHT -> { nw += dx.toInt(); nh += dy.toInt() }
            Corner.NONE -> return
        }

        nw = nw.coerceIn(MIN_WIDTH, screenWidth)
        nh = nh.coerceIn(MIN_HEIGHT, screenHeight)

        if (nw != p.width || nh != p.height) {
            p.width = nw
            p.height = nh
            layoutParams = p
            translationX += dtx
            translationY += dty
        }
    }

    private fun resizeFromCenter(w: Int, h: Int, cx: Float, cy: Float) {
        val cw = w.coerceIn(MIN_WIDTH, screenWidth)
        val ch = h.coerceIn(MIN_HEIGHT, screenHeight)

        layoutParams.apply {
            if (width != cw || height != ch) {
                width = cw
                height = ch
                layoutParams = this
            }
        }

        translationX = cx - cw / 2f
        translationY = cy - ch / 2f
    }

    private fun resetTouch() {
        isDragging = false
        isResizing = false
        isPinching = false
        activeCorner = Corner.NONE
        initialDistance = 0f
        isHandling3DTouch = false
    }

    // ==================== Resize Snapshot Optimization ====================

    private fun captureResizeSnapshot() {
        if (resizeSnapshot != null) return

        val sv = surfaceView ?: return
        val overlay = snapshotView ?: return
        if (sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return

        renderer?.onPause()

        try {
            val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)

            PixelCopy.request(sv, bitmap, { result ->
                snapshotHandler.post {
                    if (result == PixelCopy.SUCCESS && (isResizing || isPinching)) {
                        resizeSnapshot = bitmap
                        overlay.setImageBitmap(bitmap)
                        overlay.visibility = View.VISIBLE
                        sv.visibility = View.INVISIBLE
                    } else {
                        bitmap.recycle()
                    }
                }
            }, snapshotHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture resize snapshot", e)
        }
    }

    private fun updateSnapshotLayout() {
        snapshotView?.let { sv ->
            (sv.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = FrameLayout.LayoutParams.MATCH_PARENT
                height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            sv.requestLayout()
        }
    }

    private fun releaseResizeSnapshot() {
        snapshotView?.let { overlay ->
            overlay.visibility = View.GONE
            overlay.setImageBitmap(null)
        }

        resizeSnapshot?.recycle()
        resizeSnapshot = null

        surfaceView?.visibility = View.VISIBLE
        renderer?.onResume()
    }

    private fun getDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun getCorner(x: Float, y: Float): Corner {
        fun d(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
        val h = RESIZE_HANDLE_SIZE / 2
        val leftOffset = (controlsContainer?.width ?: 0).toFloat()
        return when {
            d(x, y, leftOffset + h, h) <= RESIZE_HIT_AREA -> Corner.TOP_LEFT
            d(x, y, width - h, h) <= RESIZE_HIT_AREA -> Corner.TOP_RIGHT
            d(x, y, leftOffset + h, height - h) <= RESIZE_HIT_AREA -> Corner.BOTTOM_LEFT
            d(x, y, width - h, height - h) <= RESIZE_HIT_AREA -> Corner.BOTTOM_RIGHT
            else -> Corner.NONE
        }
    }

    // ==================== Auto-hide ====================

    private fun scheduleAutoHide() {
        if (autoHideEnabled) {
            cancelAutoHide()
            hideRunnable?.let { postDelayed(it, AUTO_HIDE_DELAY) }
        }
    }

    private fun cancelAutoHide() {
        hideRunnable?.let { removeCallbacks(it) }
    }

    private fun onInteractionStart() {
        cancelAutoHide()
        showControls()
    }

    private fun onInteractionEnd() {
        scheduleAutoHide()
    }

    private fun showControls() {
        if (controlsVisible) return
        controlsVisible = true
        sideControlButtons.forEach { it.animate().alpha(1f).setDuration(FADE_DURATION).start() }
        cornerIndicators.forEach { it.animate().alpha(0.7f).setDuration(FADE_DURATION).start() }
        contentContainer?.animate()?.alpha(1f)?.setDuration(FADE_DURATION)
            ?.withStartAction { contentContainer?.setBackgroundResource(R.drawable.dotted_border_background) }
            ?.start()
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        sideControlButtons.forEach { it.animate().alpha(0f).setDuration(FADE_DURATION).start() }
        cornerIndicators.forEach { it.animate().alpha(0f).setDuration(FADE_DURATION).start() }
        contentContainer?.animate()?.alpha(1f)?.setDuration(FADE_DURATION)
            ?.withEndAction { contentContainer?.background = null }
            ?.start()
    }

    private fun updateInteractionButtonColor() {
        interactionButton?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (touchEnabled) Color.BLACK else Color.DKGRAY)
        }
    }

    private fun toggleInteraction() {
        if (touchEnabled) {
            autoHideEnabled = true
            setTouchEnabled(false)
            setPassThroughTouches(true)
        } else {
            setTouchEnabled(true)
            autoHideEnabled = false
            setPassThroughTouches(false)
        }
        updateInteractionButtonColor()
    }

    // ==================== UI Helpers ====================

    private fun createFallbackView() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        setBackgroundColor(Color.parseColor("#263238"))

        addView(TextView(context).apply {
            text = "🎲"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        addView(TextView(context).apply {
            text = "3D Container\n${currentModelData?.name ?: "Unknown"}"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        })
    }

    private fun createErrorView(msg: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F5F5F5"), Color.parseColor("#FAFAFA"))
        ).apply {
            setStroke(dpToPx(1), Color.parseColor("#E0E0E0"))
            cornerRadius = dpToPx(4).toFloat()
        }
        gravity = Gravity.CENTER

        // Frown face drawn with Canvas
        addView(object : View(context) {
            private val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
            }

            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = (minOf(width, height) / 2f) * 0.8f

                // Circle
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dpToPx(2).toFloat()
                paint.color = Color.parseColor("#AAAAAA")
                canvas.drawCircle(cx, cy, r, paint)

                // Eyes
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = Color.parseColor("#999999")
                val eyeY = cy - r * 0.2f
                val eyeSpacing = r * 0.35f
                canvas.drawCircle(cx - eyeSpacing, eyeY, r * 0.08f, paint)
                canvas.drawCircle(cx + eyeSpacing, eyeY, r * 0.08f, paint)

                // Frown arc
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = dpToPx(2).toFloat()
                val frownTop = cy + r * 0.25f
                val frownRect = android.graphics.RectF(
                    cx - r * 0.35f, frownTop,
                    cx + r * 0.35f, frownTop + r * 0.4f
                )
                canvas.drawArc(frownRect, 200f, 140f, false, paint)
            }

            init {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    gravity = Gravity.CENTER
                }
            }
        })

        addView(TextView(context).apply {
            text = msg
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dpToPx(8), 0, 0)
        })

        addView(TextView(context).apply {
            text = "Remove"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CC5500"))
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            background = GradientDrawable().apply {
                setStroke(dpToPx(1), Color.parseColor("#CCCCCC"))
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                gravity = Gravity.CENTER
            }
            setOnClickListener { onRemoveRequest?.invoke() ?: cleanup() }
        })
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    // ==================== Lifecycle ====================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isViewInitialized) {
            renderer?.onResume()
            if (renderer?.isAnimationPaused() != true) {
                audioPlayer?.play()
            }
        }
        animationToggleButton?.setImageResource(
            if (renderer?.isAnimationPaused() == true) R.drawable.ic_play_animation
            else R.drawable.ic_pause_animation
        )
        if (autoHideEnabled) scheduleAutoHide()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
        releaseResizeSnapshot()
        audioPlayer?.pause()
        renderer?.onPause()
        cancelAutoHide()
    }
}