package com.infusory.tutarapp.filament.core

import android.util.Log
import android.view.Surface
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.utils.Utils
import com.infusory.tutarapp.filament.config.FilamentConfig

/**
 * Singleton provider for the shared Filament Engine.
 * Single Responsibility: Create, hold, and destroy the Filament Engine instance.
 */
object FilamentEngineProvider {
    
    private const val TAG = "FilamentEngineProvider"
    
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    
    @Volatile
    private var isInitialized = false
    
    /**
     * Initialize the Filament engine. Must be called once before using.
     * Thread-safe.
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }
        
        return try {
            // Initialize Filament native libraries
            Utils.init()
            
            // Create engine
            engine = Engine.create().also { eng ->
                Log.d(TAG, "Engine created with backend: ${eng.backend}")
            }
            
            // Create renderer with optimized clear options
            renderer = engine!!.createRenderer().apply {
                clearOptions = clearOptions.apply {
                    clearColor = FilamentConfig.CLEAR_COLOR
                    clear = true
                }
            }
            
            isInitialized = true
            Log.d(TAG, "Filament engine initialized successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Filament engine", e)
            cleanup()
            false
        }
    }
    
    /**
     * Get the shared Engine instance
     * @throws IllegalStateException if not initialized
     */
    fun getEngine(): Engine {
        checkInitialized()
        return engine!!
    }
    
    /**
     * Get the shared Renderer instance
     */
    fun getRenderer(): Renderer {
        checkInitialized()
        return renderer!!
    }



    fun createSwapChain(surface: Surface): SwapChain? {
        return try {
            engine?.createSwapChain(surface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SwapChain", e)
            null
        }
    }

    /**
     * Create a SwapChain with flags.
     *
     * @param surface The Android Surface
     * @param flags SwapChain configuration flags
     * @return SwapChain instance or null if failed
     */
    fun createSwapChain(surface: Surface, flags: Long): SwapChain? {
        return try {
            engine?.createSwapChain(surface, flags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SwapChain with flags", e)
            null
        }
    }

    /**
     * Destroy a SwapChain.
     *
     * @param swapChain The SwapChain to destroy
     */
    fun destroySwapChain(swapChain: SwapChain) {
        try {
            engine?.destroySwapChain(swapChain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy SwapChain", e)
        }
    }


    /**
     * Create a new Renderer for a container
     * Each container having its own Renderer ensures complete isolation
     */
    fun createRenderer(): Renderer {
        checkInitialized()
        return engine!!.createRenderer().apply {
            clearOptions = clearOptions.apply {
                clearColor = FilamentConfig.CLEAR_COLOR
                clear = true
            }
        }
    }
    
    /**
     * Destroy a Renderer
     */
    fun destroyRenderer(renderer: Renderer) {
        engine?.destroyRenderer(renderer)
    }
    
    /**
     * Create a new Scene for a container (each container gets its own scene)
     */
    fun createScene(): Scene {
        checkInitialized()
        return engine!!.createScene()
    }
    
    /**
     * Destroy a Scene
     */
    fun destroyScene(scene: Scene) {
        engine?.destroyScene(scene)
    }
    
    /**
     * Create a new View configured for low-end devices
     */
    fun createView(): View {
        checkInitialized()
        return engine!!.createView().apply {
            configureForPerformance()
            // Note: scene is NOT assigned here - each container assigns its own scene
        }
    }
    
    /**
     * Destroy a View
     */
    fun destroyView(view: View) {
        engine?.destroyView(view)
    }
    
    /**
     * Create a new Camera
     */
    fun createCamera(): Camera {
        checkInitialized()
        val entity = EntityManager.get().create()
        return engine!!.createCamera(entity)
    }
    
    /**
     * Destroy a Camera and its entity
     */
    fun destroyCamera(camera: Camera) {
        engine?.let { eng ->
            val entity = camera.entity
            eng.destroyCameraComponent(entity)
            EntityManager.get().destroy(entity)
        }
    }
    
    /**
     * Create a SwapChain for a surface
     */
    fun createSwapChain(surface: Any, flags: Long = SwapChain.CONFIG_TRANSPARENT): SwapChain {
        checkInitialized()
        return engine!!.createSwapChain(surface, flags)
    }
    
    /**
     * Destroy a SwapChain
     */
//    fun destroySwapChain(swapChain: SwapChain) {
//        engine?.destroySwapChain(swapChain)
//    }
//
    /**
     * Check if engine is initialized
     */
    fun isInitialized(): Boolean = isInitialized
    
    /**
     * Shutdown and release all resources
     */
    @Synchronized
    fun shutdown() {
        if (!isInitialized) return
        
        Log.d(TAG, "Shutting down Filament engine...")
        cleanup()
        Log.d(TAG, "Filament engine shutdown complete")
    }
    
    private fun cleanup() {
        engine?.let { eng ->
            renderer?.let { eng.destroyRenderer(it) }
            eng.destroy()
        }
        
        renderer = null
        engine = null
        isInitialized = false
    }
    
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("FilamentEngineProvider not initialized. Call initialize() first.")
        }
    }
    
    /**
     * Configure a View for maximum performance on low-end devices
     */
    private fun View.configureForPerformance() {
        // Transparency support
        blendMode = View.BlendMode.TRANSLUCENT
        
        // Lowest quality settings
        renderQuality = renderQuality.apply {
            hdrColorBuffer = FilamentConfig.RENDER_QUALITY
        }
        
        // Disable anti-aliasing
        antiAliasing = FilamentConfig.ANTI_ALIASING
        
        // Disable dithering
        dithering = FilamentConfig.DITHERING
        
        // Disable all post-processing effects
        ambientOcclusionOptions = ambientOcclusionOptions.apply { 
            enabled = FilamentConfig.ENABLE_SSAO 
        }
        bloomOptions = bloomOptions.apply { 
            enabled = FilamentConfig.ENABLE_BLOOM 
        }
        screenSpaceReflectionsOptions = screenSpaceReflectionsOptions.apply { 
            enabled = FilamentConfig.ENABLE_SSR 
        }
        fogOptions = fogOptions.apply { 
            enabled = FilamentConfig.ENABLE_FOG 
        }
        depthOfFieldOptions = depthOfFieldOptions.apply { 
            enabled = FilamentConfig.ENABLE_DOF 
        }
        vignetteOptions = vignetteOptions.apply { 
            enabled = FilamentConfig.ENABLE_VIGNETTE 
        }
        
        // Disable dynamic resolution
        dynamicResolutionOptions = dynamicResolutionOptions.apply {
            enabled = FilamentConfig.ENABLE_DYNAMIC_RESOLUTION
        }
    }
}
