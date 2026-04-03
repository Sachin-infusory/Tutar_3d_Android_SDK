package com.infusory.lib3drenderer.containerview

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.infusory.lib3drenderer.Tutar
import com.infusory.lib3drenderer.R
import com.infusory.lib3drenderer.containerview.data.AuthViewModel
import com.infusory.lib3drenderer.containerview.ModelData
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
 * Created via Lib3DRenderer.createContainer() when user selects a model.
 */
class Container3D @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    initialModelData: String? =null
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
    private val sideControlButtons = mutableListOf<ImageView>()
    private val cornerIndicators = mutableListOf<ImageView>()

    // State
    private var isViewInitialized = false
    private var isModelLoaded = false
    internal var currentModelPath: String? = initialModelData
    internal var currentModelData: ModelData? = null
    private var savedCameraState: CameraController.CameraState? = null

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

    // Download
    private val viewModel: AuthViewModel? by lazy {
        try {
            val owner = (context as? ViewModelStoreOwner)
                ?: (context as? ContextWrapper)?.baseContext as? ViewModelStoreOwner
            owner?.let { ViewModelProvider(it)[AuthViewModel::class.java] }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AuthViewModel", e)
            null
        }
    }
    private var downloadObserver: Observer<Boolean>? = null

    // Callbacks
    var onLoadingStarted: (() -> Unit)? = null
    var onLoadingCompleted: (() -> Unit)? = null
    var onLoadingFailed: ((String) -> Unit)? = null
    var onRemoveRequest: (() -> Unit)? = null
        set(value) { field = { cleanup(); value?.invoke() } }

    enum class Corner { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    data class CameraOrbitState(val viewMatrix: FloatArray) {
        override fun equals(other: Any?) = (other as? CameraOrbitState)?.viewMatrix?.contentEquals(viewMatrix) == true
        override fun hashCode() = viewMatrix.contentHashCode()
    }

    data class ContainerState(
        val x: Float, val y: Float, val width: Int, val height: Int,
        val modelPath: String, val modelName: String, val cameraOrbit: CameraOrbitState? = null
    )

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

    /**
     * Set model data - triggers loading
     */
    fun setModelData(modelPath: String) {
        Log.d(TAG, "[$containerId] setModelData: $modelPath")
        currentModelPath = modelPath
        currentModelData = null
        initializeAndLoad()
    }

    /**
     * Set model with ModelData object
     */
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

    fun pauseRendering() = renderer?.onPause()
    fun resumeRendering() = renderer?.onResume()

    fun cleanup() {
        Log.d(TAG, "[$containerId] cleanup()")
        Model3DContainerManager.unregisterContainer(this)
        destroy()
    }

    fun destroy() {
        Log.d(TAG, "[$containerId] destroy()")
        isViewInitialized = false
        isModelLoaded = false
        cancelAutoHide()
        cleanupObservers()

        renderer?.let { FilamentEngineManager.destroyRenderer(it) }
        renderer = null
        surfaceView = null

        post { (parent as? ViewGroup)?.removeView(this@Container3D) }
    }

    // ==================== State ====================

    fun saveState() = ContainerState(
        translationX, translationY, width, height,
        currentModelPath ?: "", currentModelData?.name ?: "",
        renderer?.saveCameraState()?.let { CameraOrbitState(it.viewMatrix) }
    )

    fun loadState(state: ContainerState) {
        savedCameraState = state.cameraOrbit?.let { CameraController.CameraState(it.viewMatrix) }
        if (state.modelPath.isNotEmpty()) setModelData(state.modelPath)
        layoutParams = (layoutParams ?: ViewGroup.MarginLayoutParams(state.width, state.height)).apply {
            width = state.width
            height = state.height
        }
        translationX = state.x
        translationY = state.y
        requestLayout()
    }

    fun updateCurrentTransform() {
        renderer?.saveCameraState()?.let { savedCameraState = it }
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
            setPadding(0, dpToPx(2), dpToPx(2), dpToPx(2))
        }

        addControlButtons()
        addCornerIndicators()
        main.addView(controlsContainer)
        main.addView(contentContainer)
        addView(main)
    }

    private fun addControlButtons() {
        controlsContainer?.apply {
            // Interaction button
            interactionButton = createButton(R.drawable.ic_interact, "Interaction") { toggleInteraction() }
            addView(interactionButton!!)
            sideControlButtons.add(interactionButton!!)
            post { updateInteractionButtonColor() }

            addView(createSpacer())

            // Animation button
            animationToggleButton = createButton(R.drawable.ic_pause_animation, "Animation") { toggleAnimation() }
            addView(animationToggleButton!!)
            sideControlButtons.add(animationToggleButton!!)

            addView(createSpacer())

            // Close button
            val close = createButton(R.drawable.ic_close_white, "Close") {
                onRemoveRequest?.invoke() ?: cleanup()
            }
            addView(close)
            sideControlButtons.add(close)
        }
    }

    private fun addCornerIndicators() {
        contentContainer?.let { container ->
            // Create corner indicators for all 4 corners
            val corners = listOf(
                Pair(Gravity.TOP or Gravity.START, Corner.TOP_LEFT),
                Pair(Gravity.TOP or Gravity.END, Corner.TOP_RIGHT),
                Pair(Gravity.BOTTOM or Gravity.START, Corner.BOTTOM_LEFT),
                Pair(Gravity.BOTTOM or Gravity.END, Corner.BOTTOM_RIGHT)
            )

            corners.forEach { (gravity, _) ->
                val indicator = createCornerIndicator(gravity)
                container.addView(indicator)
                cornerIndicators.add(indicator)
            }
        }
    }

    private fun createCornerIndicator(gravity: Int): ImageView {
        return ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(CORNER_INDICATOR_SIZE),
                dpToPx(CORNER_INDICATOR_SIZE)
            ).apply {
                this.gravity = gravity
                // Add small margins to position indicators slightly inside the corners
                val margin = dpToPx(4)
                setMargins(margin, margin, margin, margin)
            }

            // Create a corner bracket shape
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dpToPx(2), Color.WHITE)
                setColor(Color.TRANSPARENT)
                cornerRadius = dpToPx(2).toFloat()
            }

            elevation = 2f
            alpha = 0.7f
        }
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
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
            }
            contentContainer?.addView(surfaceView!!, 0) // Add at index 0 so corner indicators are on top

            renderer = FilamentEngineManager.createRenderer(surfaceView!!).apply {
                onLoadingStarted = { this@Container3D.onLoadingStarted?.invoke() }
                onLoadingCompleted = {
                    isModelLoaded = true
                    if (!isAnimationPaused()) {
                        toggleAnimation()
                    }
                    savedCameraState?.let { restoreCameraState(it) }
                    this@Container3D.onLoadingCompleted?.invoke()
                }
                onLoadingFailed = { this@Container3D.onLoadingFailed?.invoke(it) }
            }

            updateTouchHandling()
            Log.d(TAG, "[$containerId] 3D view created")
        } catch (e: Exception) {
            Log.e(TAG, "[$containerId] Failed to create 3D view", e)
            contentContainer?.addView(createFallbackView())
        }
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

        if (!Tutar.isAuthenticated()) {
            onLoadingFailed?.invoke("Not authenticated")
            return
        }

        Log.d(TAG, "[$containerId] Loading: $path")
        onLoadingStarted?.invoke()

        try {
            loadModelBuffer(path)?.let { buffer ->
                renderer?.loadModel(buffer)
                savedCameraState?.let { renderer?.restoreCameraState(it) }
                Log.d(TAG, "[$containerId] Model loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$containerId] Load failed", e)
            onLoadingFailed?.invoke("Error: ${e.message}")
        }
    }

    private fun loadModelBuffer(filename: String): ByteBuffer? {
        val actualFilename = filename.substringAfterLast('/')

        // Try local locations
        val locations = listOf(
            File(context.filesDir, "$FOLDER/$actualFilename"),
            File(context.getExternalFilesDir(null), "$FOLDER/$actualFilename"),
            File(filename),
            File(context.filesDir, "3d_models/$actualFilename"),
            File(context.filesDir, "encrypted_models/$actualFilename")
        )

        for (file in locations) {
            if (file.exists() && file.canRead()) {
                // Try decrypt
                ModelDecryptionUtil.decryptModelFile(file)?.let { return it }
                // Try raw
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

        // Try assets
        tryLoadFromAssets(actualFilename)?.let { return it }

        // Remote download
        Log.d(TAG, "[$containerId] Starting download: $actualFilename")
        initiateRemoteDownload(actualFilename)
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

    private fun initiateRemoteDownload(filename: String) {
        val vm = viewModel ?: run {
            showError("Download unavailable")
            onLoadingFailed?.invoke("Download service unavailable")
            return
        }

        val liveData = vm.downloadFile(context, filename)
        downloadObserver = Observer { success ->
            liveData.removeObserver(downloadObserver!!)
            downloadObserver = null

            Handler(Looper.getMainLooper()).post {
                if (success) {
                    val file = File(context.filesDir, FOLDER).resolve(filename)
                    if (file.exists()) {
                        ModelDecryptionUtil.decryptModelFile(file)?.let { buffer ->
                            renderer?.loadModel(buffer)
                            Log.d(TAG, "[$containerId] Downloaded model loaded")
                            return@post
                        }
                    }
                    showError("Decrypt failed")
                    onLoadingFailed?.invoke("Decrypt failed")
                } else {
                    showError("Download failed")
                    onLoadingFailed?.invoke("Download failed: $filename")
                }
            }
        }
        liveData.observeForever(downloadObserver!!)
    }

    private fun showError(msg: String) {
        contentContainer?.let { c ->
            for (i in c.childCount - 1 downTo 0) {
                if (c.getChildAt(i) != surfaceView && c.getChildAt(i) !in cornerIndicators) {
                    c.removeViewAt(i)
                }
            }
            c.addView(createErrorView(msg))
        }
    }

    private fun cleanupObservers() {
        downloadObserver?.let { obs ->
            try { viewModel?.downloadFile(context, "")?.removeObserver(obs) } catch (_: Exception) {}
        }
        downloadObserver = null
    }

    // ==================== Animation ====================

    private fun toggleAnimation() {
        renderer?.toggleAnimation()?.let { paused ->
            animationToggleButton?.setImageResource(
                if (paused) R.drawable.ic_play_animation else R.drawable.ic_pause_animation
            )
        } ?: Log.d( "No animations" , "No animations are available in this model")
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
                            updateCurrentTransform()
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
            MotionEvent.ACTION_UP -> { resetTouch(); onInteractionEnd(); true }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    isPinching = false
                    initialDistance = 0f
                    scaleFactor = 1f
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> { resetTouch(); onInteractionEnd(); true }
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
            }
        } else if (e.pointerCount == 1 && !isPinching) {
            val dx = e.rawX - lastX
            val dy = e.rawY - lastY

            if (isDragging) {
                translationX += dx
                translationY += dy
            } else if (isResizing) {
                handleResize(dx, dy)
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

    private fun getDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun getCorner(x: Float, y: Float): Corner {
        fun d(x1: Float, y1: Float, x2: Float, y2: Float) = sqrt((x1-x2).pow(2) + (y1-y2).pow(2))
        val h = RESIZE_HANDLE_SIZE / 2
        return when {
            d(x, y, h, h) <= RESIZE_HIT_AREA -> Corner.TOP_LEFT
            d(x, y, width - h, h) <= RESIZE_HIT_AREA -> Corner.TOP_RIGHT
            d(x, y, h, height - h) <= RESIZE_HIT_AREA -> Corner.BOTTOM_LEFT
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
        setBackgroundColor(Color.parseColor("#FF5722"))
        gravity = Gravity.CENTER

        addView(TextView(context).apply {
            text = "❌"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })

        addView(TextView(context).apply {
            text = msg
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dpToPx(8), 0, 0)
        })
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    // ==================== Lifecycle ====================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isViewInitialized) renderer?.onResume()
        animationToggleButton?.setImageResource(
            if (renderer?.isAnimationPaused() == true) R.drawable.ic_play_animation
            else R.drawable.ic_pause_animation
        )
        if (autoHideEnabled) scheduleAutoHide()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer?.onPause()
        cancelAutoHide()
        cleanupObservers()
    }
}