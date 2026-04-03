package com.infusory.lib3drenderer

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.infusory.lib3drenderer.containerview.Container3D
import com.infusory.lib3drenderer.containerview.ModelDecryptionUtil
import com.infusory.lib3drenderer.containerview.data.AuthViewModel
import com.infusory.lib3drenderer.containerview.ModelData
import com.infusory.tutarapp.filament.FilamentEngineManager

/**
 * Main entry point for the 3D Renderer SDK.
 *
 * <h3>Kotlin Usage:</h3>
 * <pre>{@code
 * Lib3DRenderer.initialize(this) { success ->
 *     if (success) {
 *         Lib3DRenderer.createContainer(this, modelData, modelPath, parent)
 *     }
 * }
 * }</pre>
 *
 * <h3>Java Usage:</h3>
 * <pre>{@code
 * Lib3DRenderer.initialize(this, success -> {
 *     if (success) {
 *         Lib3DRenderer.createContainer(this, modelData, modelPath, parent);
 *     }
 * });
 *
 * // Or with interface
 * Lib3DRenderer.initialize(this, new InitCallback() {
 *     public void onResult(boolean success) {
 *         if (success) {
 *             Lib3DRenderer.createContainer(context, modelData, modelPath, parent);
 *         }
 *     }
 * });
 * }</pre>
 */
object Tutar {

    private const val TAG = "Lib3DRenderer"

    // State
    private var isInitialized = false
    private var isInitializing = false
    private var isSDKVerified = false
    private var appContext: Context? = null
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val activeContainers = mutableListOf<Container3D>()

    // Observable state
    private val _initializationState = MutableLiveData<InitializationState>()

    @JvmStatic
    val initializationState: LiveData<InitializationState>
        get() = _initializationState

    /**
     * Initialization state enum.
     */
    enum class InitializationState {
        NOT_INITIALIZED,
        INITIALIZING,
        VERIFYING_SDK,
        INITIALIZING_ENGINE,
        READY,
        FAILED
    }

    /**
     * Configuration options for SDK initialization.
     *
     * Java usage:
     * ```java
     * Lib3DRenderer.Config config = new Lib3DRenderer.Config.Builder()
     *     .skipAuthentication(true)
     *     .decryptionKey("your-key")
     *     .enableDebugLogging(true)
     *     .build();
     * ```
     */
    data class Config @JvmOverloads constructor(
        val skipAuthentication: Boolean = false,
        val decryptionKey: String? = null,
        val enableDebugLogging: Boolean = false
    ) {
        /**
         * Builder for Java users.
         */
        class Builder {
            private var skipAuthentication = false
            private var decryptionKey: String? = null
            private var enableDebugLogging = false

            fun skipAuthentication(skip: Boolean) = apply { this.skipAuthentication = skip }
            fun decryptionKey(key: String?) = apply { this.decryptionKey = key }
            fun enableDebugLogging(enable: Boolean) = apply { this.enableDebugLogging = enable }

            fun build() = Config(skipAuthentication, decryptionKey, enableDebugLogging)
        }

        companion object {
            @JvmStatic
            fun builder() = Builder()

            @JvmStatic
            fun getDefault() = Config()
        }
    }

    // ==================== Initialization ====================

    /**
     * Initialize the SDK with default configuration.
     * Call this once in Activity's onCreate().
     *
     * @param context Activity or Application context
     * @param callback Called when initialization completes
     */
    @JvmStatic
    @MainThread
    fun initialize(context: Context, callback: InitCallback) {
        initialize(context, Config(), callback)
    }

    /**
     * Initialize the SDK with Kotlin lambda.
     */
    @JvmStatic
    @MainThread
    fun initialize(context: Context, callback: (Boolean) -> Unit) {
        initialize(context, Config(), callback)
    }

    /**
     * Initialize the SDK with custom configuration.
     *
     * @param context Activity or Application context
     * @param config Configuration options
     * @param callback Called when initialization completes
     */
    @JvmStatic
    @MainThread
    fun initialize(context: Context, config: Config, callback: InitCallback) {
        initialize(context, config) { success -> callback.onResult(success) }
    }

    /**
     * Initialize with config and Kotlin lambda.
     */
    @JvmStatic
    @MainThread
    fun initialize(context: Context, config: Config, callback: (Boolean) -> Unit) {
        if (isInitialized && isSDKVerified) {
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

        if (config.skipAuthentication) {
            Log.d(TAG, "Skipping authentication (debug mode)")
            config.decryptionKey?.let { ModelDecryptionUtil.setDecryptKey(it) }
            isSDKVerified = true
            initializeFilamentEngine(callback)
        } else {
            verifyAuthentication(context, config, callback)
        }
    }

    // ==================== Container Creation ====================

    /**
     * Create a new 3D container for a model selected by user.
     *
     * @param context Activity context
     * @param modelData Model metadata
     * @param modelPath Full path to model file
     * @param parent Parent ViewGroup to add container to
     * @return Container3D instance, or null if SDK not ready
     */
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

    /**
     * Create container with just model path.
     */
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

    /**
     * Create container with callbacks (Java-friendly).
     *
     * @param context Activity context
     * @param modelData Model metadata
     * @param modelPath Model file path
     * @param parent Parent ViewGroup
     * @param onLoading Called when loading starts (nullable)
     * @param onLoaded Called when model loads (nullable)
     * @param onError Called on error (nullable)
     * @return Container3D instance or null
     */
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

    /**
     * Create container with Kotlin lambdas.
     */
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

    /**
     * Create container without adding to parent (for custom positioning).
     */
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

    /**
     * Get list of all active containers.
     */
    @JvmStatic
    fun getActiveContainers(): List<Container3D> {
        synchronized(activeContainers) {
            return activeContainers.toList()
        }
    }

    /**
     * Get number of active containers.
     */
    @JvmStatic
    fun getContainerCount(): Int {
        synchronized(activeContainers) {
            return activeContainers.size
        }
    }

    /**
     * Remove a specific container.
     */
    @JvmStatic
    @MainThread
    fun removeContainer(container: Container3D) {
        container.cleanup()
    }

    /**
     * Remove all containers.
     */
    @JvmStatic
    @MainThread
    fun removeAllContainers() {
        synchronized(activeContainers) {
            activeContainers.toList().forEach { it.cleanup() }
            activeContainers.clear()
        }
    }

    /**
     * Pause all containers. Call in Activity onPause().
     */
    @JvmStatic
    fun pauseAll() {
        synchronized(activeContainers) {
            activeContainers.forEach { it.pauseRendering() }
        }
    }

    /**
     * Resume all containers. Call in Activity onResume().
     */
    @JvmStatic
    fun resumeAll() {
        synchronized(activeContainers) {
            activeContainers.forEach { it.resumeRendering() }
        }
    }

    // ==================== State ====================

    /**
     * Check if SDK is ready to use.
     */
    @JvmStatic
    fun isReady(): Boolean = isInitialized && isSDKVerified

    /**
     * Check if Filament engine is initialized.
     */
    @JvmStatic
    fun isEngineInitialized(): Boolean = FilamentEngineManager.isInitialized()

    /**
     * Check if SDK authentication passed.
     */
    @JvmStatic
    fun isAuthenticated(): Boolean = isSDKVerified

    // ==================== Lifecycle ====================

    /**
     * Release all SDK resources. Call in Activity onDestroy().
     */
    @JvmStatic
    @MainThread
    fun release() {
        Log.d(TAG, "Releasing SDK resources...")

        removeAllContainers()

        try {
//            FilamentEngineManager.destroyRenderer
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying FilamentEngineManager", e)
        }

        isInitialized = false
        isInitializing = false
        appContext = null

        synchronized(pendingCallbacks) {
            pendingCallbacks.clear()
        }

        _initializationState.postValue(InitializationState.NOT_INITIALIZED)
        Log.d(TAG, "SDK released")
    }

    /**
     * Reinitialize engine after configuration change.
     */
    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: InitCallback) {
        reinitializeEngine(context) { success -> callback.onResult(success) }
    }

    @JvmStatic
    @MainThread
    fun reinitializeEngine(context: Context, callback: (Boolean) -> Unit) {
        if (!isSDKVerified) {
            Log.e(TAG, "Cannot reinitialize - not authenticated")
            callback(false)
            return
        }

        removeAllContainers()
        appContext = context.applicationContext

        try {
//            FilamentEngineManager.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying engine", e)
        }

        isInitialized = false
        initializeFilamentEngine(callback)
    }

    // ==================== Private ====================

    private fun verifyAuthentication(context: Context, config: Config, callback: (Boolean) -> Unit) {
        _initializationState.postValue(InitializationState.VERIFYING_SDK)
        Log.d(TAG, "Verifying authentication...")

        if (ModelDecryptionUtil.KEY != ModelDecryptionUtil.TAG) {
            Log.d(TAG, "Already authenticated")
            isSDKVerified = true
            initializeFilamentEngine(callback)
            return
        }

        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val authViewModel = AuthViewModel(context.applicationContext as Application)
        val liveData = authViewModel.verifyAuthentication(deviceId)

        liveData.observeForever(object : androidx.lifecycle.Observer<Boolean> {
            override fun onChanged(success: Boolean) {
                liveData.removeObserver(this)

                if (success) {
                    Log.d(TAG, "Authentication successful")
                    config.decryptionKey?.let { ModelDecryptionUtil.setDecryptKey(it) }
                        ?: ModelDecryptionUtil.setDecryptKey("TODO")
                    isSDKVerified = true
                    initializeFilamentEngine(callback)
                } else {
                    Log.e(TAG, "Authentication failed")
                    isInitializing = false
                    _initializationState.postValue(InitializationState.FAILED)
                    notifyCallbacks(false, callback)
                }
            }
        })
    }

    private fun initializeFilamentEngine(callback: (Boolean) -> Unit) {
        _initializationState.postValue(InitializationState.INITIALIZING_ENGINE)
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