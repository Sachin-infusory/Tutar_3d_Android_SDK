package com.infusory.lib3drenderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.infusory.lib3drenderer.containerview.Container3D
import com.infusory.lib3drenderer.containerview.ModelData
import com.infusory.tutarapp.filament.FilamentEngineManager

/**
 * Main entry point for the 3D Renderer SDK.
 *
 * Kotlin:
 * ```
 * Tutar.initialize(this) { success ->
 *     if (success) {
 *         Tutar.createContainer(this, modelData, modelPath, parent)
 *     }
 * }
 * ```
 *
 * Java:
 * ```java
 * Tutar.initialize(this, success -> {
 *     if (success) {
 *         Tutar.createContainer(context, modelData, modelPath, parent);
 *     }
 * });
 * ```
 */
object Tutar {

    private const val TAG = "Lib3DRenderer"

    private var isInitialized = false
    private var isInitializing = false
    private var appContext: Context? = null
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val activeContainers = mutableListOf<Container3D>()

    private val _initializationState = MutableLiveData<InitializationState>()

    @JvmStatic
    val initializationState: LiveData<InitializationState>
        get() = _initializationState

    enum class InitializationState {
        NOT_INITIALIZED,
        INITIALIZING,
        READY,
        FAILED
    }

    // ==================== Initialization ====================

    @JvmStatic
    @MainThread
    fun initialize(context: Context, callback: InitCallback) {
        initialize(context) { success -> callback.onResult(success) }
    }

    @JvmStatic
    @MainThread
    fun initialize(context: Context, callback: (Boolean) -> Unit) {
        if (isInitialized) {
            Log.d(TAG, "SDK already initialized")
            Handler(Looper.getMainLooper()).post { callback(true) }
            return
        }

        if (isInitializing) {
            Log.d(TAG, "Initialization in progress, queuing callback")
            synchronized(pendingCallbacks) { pendingCallbacks.add(callback) }
            return
        }

        isInitializing = true
        appContext = context.applicationContext
        _initializationState.postValue(InitializationState.INITIALIZING)

        Log.d(TAG, "Starting SDK initialization...")
        initializeFilamentEngine(callback)
    }

    // ==================== Container Creation ====================

    @JvmStatic
    @MainThread
    fun createContainer(
        context: Context,
        modelData: ModelData,
        modelPath: String,
        parent: ViewGroup
    ): Container3D? {
        if (!isReady()) {
            Log.e(TAG, "SDK not initialized. Call initialize() first.")
            return null
        }

        val container = Container3D(context).apply {
            setModelData(modelData, modelPath)
        }

        trackContainer(container)
        parent.addView(container)

        Log.d(TAG, "Created container: ${container.getContainerId()} for: ${modelData.name}")
        return container
    }

    @JvmStatic
    @MainThread
    fun createContainer(
        context: Context,
        modelPath: String,
        parent: ViewGroup
    ): Container3D? {
        if (!isReady()) {
            Log.e(TAG, "SDK not initialized. Call initialize() first.")
            return null
        }

        val container = Container3D(context).apply {
            setModelData(modelPath)
        }

        trackContainer(container)
        parent.addView(container)

        Log.d(TAG, "Created container: ${container.getContainerId()}")
        return container
    }

    @JvmStatic
    @MainThread
    fun createContainer(
        context: Context,
        modelData: ModelData,
        modelPath: String,
        parent: ViewGroup,
        onLoading: LoadingCallback?,
        onLoaded: LoadedCallback?,
        onError: ErrorCallback?
    ): Container3D? {
        val container = createContainer(context, modelData, modelPath, parent) ?: return null

        onLoading?.let { container.onLoadingStarted = { it.onLoading() } }
        onLoaded?.let { container.onLoadingCompleted = { it.onLoaded() } }
        onError?.let { container.onLoadingFailed = { msg -> it.onError(msg) } }

        return container
    }

    @JvmStatic
    @MainThread
    fun createContainerWithCallbacks(
        context: Context,
        modelData: ModelData,
        modelPath: String,
        parent: ViewGroup,
        onLoading: (() -> Unit)? = null,
        onLoaded: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Container3D? {
        val container = createContainer(context, modelData, modelPath, parent) ?: return null

        container.onLoadingStarted = onLoading
        container.onLoadingCompleted = onLoaded
        container.onLoadingFailed = onError

        return container
    }

    @JvmStatic
    @MainThread
    fun createContainerDetached(
        context: Context,
        modelData: ModelData,
        modelPath: String
    ): Container3D? {
        if (!isReady()) {
            Log.e(TAG, "SDK not initialized.")
            return null
        }

        val container = Container3D(context).apply {
            setModelData(modelData, modelPath)
        }

        trackContainer(container)
        Log.d(TAG, "Created detached container: ${container.getContainerId()}")
        return container
    }

    private fun trackContainer(container: Container3D) {
        synchronized(activeContainers) {
            activeContainers.add(container)
        }

        val originalCallback = container.onRemoveRequest
        container.onRemoveRequest = {
            synchronized(activeContainers) {
                activeContainers.remove(container)
            }
            originalCallback?.invoke()
        }
    }

    // ==================== Container Management ====================

    @JvmStatic
    fun getActiveContainers(): List<Container3D> {
        synchronized(activeContainers) {
            return activeContainers.toList()
        }
    }

    @JvmStatic
    fun getContainerCount(): Int {
        synchronized(activeContainers) {
            return activeContainers.size
        }
    }

    @JvmStatic
    @MainThread
    fun removeContainer(container: Container3D) {
        container.cleanup()
    }

    @JvmStatic
    @MainThread
    fun removeAllContainers() {
        synchronized(activeContainers) {
            activeContainers.toList().forEach { it.cleanup() }
            activeContainers.clear()
        }
    }

    @JvmStatic
    fun pauseAll() {
        synchronized(activeContainers) {
            activeContainers.forEach { it.pauseRendering() }
        }
    }

    @JvmStatic
    fun resumeAll() {
        synchronized(activeContainers) {
            activeContainers.forEach { it.resumeRendering() }
        }
    }

    // ==================== State ====================

    @JvmStatic
    fun isReady(): Boolean = isInitialized

    @JvmStatic
    fun isEngineInitialized(): Boolean = FilamentEngineManager.isInitialized()

    // ==================== Lifecycle ====================

    @JvmStatic
    @MainThread
    fun release() {
        Log.d(TAG, "Releasing SDK resources...")

        removeAllContainers()

        isInitialized = false
        isInitializing = false
        appContext = null

        synchronized(pendingCallbacks) {
            pendingCallbacks.clear()
        }

        _initializationState.postValue(InitializationState.NOT_INITIALIZED)
        Log.d(TAG, "SDK released")
    }

    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: InitCallback) {
        reinitializeEngine(context) { success -> callback.onResult(success) }
    }

    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: (Boolean) -> Unit) {
        removeAllContainers()
        appContext = context.applicationContext
        isInitialized = false
        initializeFilamentEngine(callback)
    }

    // ==================== Private ====================

    private fun initializeFilamentEngine(callback: (Boolean) -> Unit) {
        Log.d(TAG, "Initializing Filament...")

        val context = appContext ?: run {
            Log.e(TAG, "Context is null")
            isInitializing = false
            _initializationState.postValue(InitializationState.FAILED)
            notifyCallbacks(false, callback)
            return
        }

        try {
            FilamentEngineManager.initialize(context)
            isInitialized = true
            isInitializing = false
            _initializationState.postValue(InitializationState.READY)
            Log.d(TAG, "SDK ready")
            notifyCallbacks(true, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Filament init failed", e)
            isInitializing = false
            _initializationState.postValue(InitializationState.FAILED)
            notifyCallbacks(false, callback)
        }
    }

    private fun notifyCallbacks(success: Boolean, primaryCallback: (Boolean) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            primaryCallback(success)
            synchronized(pendingCallbacks) {
                pendingCallbacks.forEach { it(success) }
                pendingCallbacks.clear()
            }
        }
    }
}
