package com.infusory.tutar3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import com.infusory.tutar3d.containerview.Container3D
import com.infusory.tutar3d.internal.SdkLog
import com.infusory.tutar3d.containerview.filament.FilamentEngineManager

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

    // Auto pause/resume via process lifecycle. Consumers don't need to wire
    // pauseAll()/resumeAll() into their Activity manually — this observer
    // does it on the SDK's behalf when the app foregrounds/backgrounds.
    private var processLifecycleObserverAttached = false
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Process foregrounded. resumeAll() is a no-op when nothing was paused.
            if (isInitialized) resumeAll()
        }

        override fun onStop(owner: LifecycleOwner) {
            // Process backgrounded. ProcessLifecycleOwner debounces config
            // changes (~700ms) before firing onStop, so we won't churn the
            // engine on rotation.
            if (isInitialized) pauseAll()
        }
    }

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
            SdkLog.d(TAG, "SDK already initialized")
            Handler(Looper.getMainLooper()).post { callback(true) }
            return
        }

        if (isInitializing) {
            SdkLog.d(TAG, "Initialization in progress, queuing callback")
            synchronized(pendingCallbacks) { pendingCallbacks.add(callback) }
            return
        }

        isInitializing = true
        appContext = context.applicationContext
        _initializationState.postValue(InitializationState.INITIALIZING)

        SdkLog.d(TAG, "Starting SDK initialization...")
        initializeFilamentEngine(callback)
    }

    // ==================== Container Creation ====================

    /**
     * Creates a container and attaches it to [parent].
     *
     * [modelPath] may be a bare filename (e.g. `"Bird.glb"`) — in which case
     * the SDK searches `filesDir/models/`, `getExternalFilesDir/models/`, and
     * `assets/models/` — or an absolute path. Returns `null` if the SDK has
     * not been initialised.
     */
    @JvmStatic
    @MainThread
    fun createContainer(
        context: Context,
        modelPath: String,
        parent: ViewGroup
    ): Container3D? {
        if (!isReady()) {
            SdkLog.e(TAG, "SDK not initialized. Call initialize() first.")
            return null
        }

        val container = Container3D(context).apply {
            setModelData(modelPath)
        }

        trackContainer(container)
        parent.addView(container)

        SdkLog.d(TAG, "Created container: ${container.getContainerId()}")
        return container
    }

    /**
     * Java-friendly overload that wires loading callbacks before the model
     * begins loading. Pass `null` for any callback you don't need.
     */
    @JvmStatic
    @MainThread
    fun createContainer(
        context: Context,
        modelPath: String,
        parent: ViewGroup,
        onLoading: LoadingCallback?,
        onLoaded: LoadedCallback?,
        onError: ErrorCallback?
    ): Container3D? {
        val container = createContainer(context, modelPath, parent) ?: return null

        onLoading?.let { container.onLoadingStarted = { it.onLoading() } }
        onLoaded?.let { container.onLoadingCompleted = { it.onLoaded() } }
        onError?.let { container.onLoadingFailed = { msg -> it.onError(msg) } }

        return container
    }

    /**
     * Kotlin-friendly overload using lambdas. All callbacks are optional.
     */
    @JvmStatic
    @MainThread
    fun createContainerWithCallbacks(
        context: Context,
        modelPath: String,
        parent: ViewGroup,
        onLoading: (() -> Unit)? = null,
        onLoaded: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): Container3D? {
        val container = createContainer(context, modelPath, parent) ?: return null

        container.onLoadingStarted = onLoading
        container.onLoadingCompleted = onLoaded
        container.onLoadingFailed = onError

        return container
    }

    /**
     * Creates a container without adding it to a parent. The caller is
     * responsible for inserting it into the view hierarchy. Useful when the
     * container needs to be configured (touch, callbacks) before display.
     */
    @JvmStatic
    @MainThread
    fun createContainerDetached(
        context: Context,
        modelPath: String
    ): Container3D? {
        if (!isReady()) {
            SdkLog.e(TAG, "SDK not initialized.")
            return null
        }

        val container = Container3D(context).apply {
            setModelData(modelPath)
        }

        trackContainer(container)
        SdkLog.d(TAG, "Created detached container: ${container.getContainerId()}")
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

    // ==================== Logging ====================

    /**
     * Enables or disables verbose SDK logging (debug/info/verbose channels).
     *
     * The SDK is silent by default at those levels. Errors and warnings are
     * always logged regardless of this flag, but contain no implementation
     * detail (encryption, internal paths, byte counts) — so leaving this off
     * in production is safe.
     *
     * Recommended usage: enable while reproducing an integration issue, then
     * disable. Filter logcat with `adb logcat -s <SDK tag>:*` to isolate.
     */
    @JvmStatic
    fun setDebugLogging(enabled: Boolean) {
        SdkLog.debugEnabled = enabled
    }

    // ==================== Lifecycle ====================

    @JvmStatic
    @MainThread
    fun release() {
        SdkLog.d(TAG, "Releasing SDK resources...")

        detachProcessLifecycleObserver()

        // Order matters: cleanup() on each container destroys its renderer
        // (which holds Scene/View/Camera references into the engine), so
        // containers must go before we tear down the engine itself.
        removeAllContainers()

        // Shut down the shared Filament engine. Without this the Engine,
        // shared Renderer, and ResourceManager keep their native allocations
        // alive for the rest of the process — leaking across Activity
        // recreations and any future re-initialize.
        FilamentEngineManager.shutdown()

        isInitialized = false
        isInitializing = false
        appContext = null

        synchronized(pendingCallbacks) {
            pendingCallbacks.clear()
        }

        _initializationState.postValue(InitializationState.NOT_INITIALIZED)
        SdkLog.d(TAG, "SDK released")
    }

    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: InitCallback) {
        reinitializeEngine(context) { success -> callback.onResult(success) }
    }

    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: (Boolean) -> Unit) {
        // Tear down the existing engine before creating a new one. Without
        // this the previous Engine/Renderer/ResourceManager are leaked, and
        // FilamentEngineProvider.initialize() short-circuits as already-init.
        removeAllContainers()
        FilamentEngineManager.shutdown()
        appContext = context.applicationContext
        isInitialized = false
        initializeFilamentEngine(callback)
    }

    // ==================== Private ====================

    private fun initializeFilamentEngine(callback: (Boolean) -> Unit) {
        SdkLog.d(TAG, "Initializing Filament...")

        val context = appContext ?: run {
            SdkLog.e(TAG, "Context is null")
            isInitializing = false
            _initializationState.postValue(InitializationState.FAILED)
            notifyCallbacks(false, callback)
            return
        }

        try {
            FilamentEngineManager.initialize(context)
            isInitialized = true
            isInitializing = false
            attachProcessLifecycleObserver()
            _initializationState.postValue(InitializationState.READY)
            SdkLog.d(TAG, "SDK ready")
            notifyCallbacks(true, callback)
        } catch (e: Exception) {
            SdkLog.e(TAG, "Filament init failed", e)
            isInitializing = false
            _initializationState.postValue(InitializationState.FAILED)
            notifyCallbacks(false, callback)
        }
    }

    @MainThread
    private fun attachProcessLifecycleObserver() {
        if (processLifecycleObserverAttached) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        processLifecycleObserverAttached = true
    }

    @MainThread
    private fun detachProcessLifecycleObserver() {
        if (!processLifecycleObserverAttached) return
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        processLifecycleObserverAttached = false
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
