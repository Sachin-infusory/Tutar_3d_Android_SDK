package com.infusory.tutarapp.filament.core///**
// * FILAMENT ENGINE PROVIDER ADDITIONS
// * ===================================
// *
// * Add these methods to your existing FilamentEngineProvider.kt file.
// * These are required for the unified rendering system to create
// * SwapChains directly from surfaces.
// */
//
//package com.infusory.tutarapp.filament.core
//
//import android.view.Surface
//import com.google.android.filament.SwapChain
//
//// Add these methods to FilamentEngineProvider object:
//
///**
// * Create a SwapChain from an Android Surface.
// * Used by unified rendering to bind to the shared surface.
// *
// * @param surface The Android Surface to create SwapChain from
// * @return SwapChain instance or null if failed
// */
//fun createSwapChain(surface: Surface): SwapChain? {
//    return try {
//        engine?.createSwapChain(surface)
//    } catch (e: Exception) {
//        Log.e(TAG, "Failed to create SwapChain", e)
//        null
//    }
//}
//
///**
// * Create a SwapChain with flags.
// *
// * @param surface The Android Surface
// * @param flags SwapChain configuration flags
// * @return SwapChain instance or null if failed
// */
//fun createSwapChain(surface: Surface, flags: Long): SwapChain? {
//    return try {
//        engine?.createSwapChain(surface, flags)
//    } catch (e: Exception) {
//        Log.e(TAG, "Failed to create SwapChain with flags", e)
//        null
//    }
//}
//
///**
// * Destroy a SwapChain.
// *
// * @param swapChain The SwapChain to destroy
// */
//fun destroySwapChain(swapChain: SwapChain) {
//    try {
//        engine?.destroySwapChain(swapChain)
//    } catch (e: Exception) {
//        Log.e(TAG, "Failed to destroy SwapChain", e)
//    }
//}
//
//// ==================== COMPLETE REFERENCE ====================
//// Here's a more complete FilamentEngineProvider if you need to verify
//// all the methods are present:
//
///*
//object FilamentEngineProvider {
//
//    private const val TAG = "FilamentEngineProvider"
//
//    private var engine: Engine? = null
//    private var isInitialized = false
//
//    @Synchronized
//    fun initialize(): Boolean {
//        if (isInitialized) return true
//
//        try {
//            engine = Engine.create()
//            isInitialized = engine != null
//            Log.d(TAG, "Filament Engine initialized: $isInitialized")
//            return isInitialized
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to initialize Engine", e)
//            return false
//        }
//    }
//
//    fun isInitialized(): Boolean = isInitialized
//
//    fun getEngine(): Engine? = engine
//
//    // Renderer
//    fun createRenderer(): Renderer? = engine?.createRenderer()
//    fun destroyRenderer(renderer: Renderer) = engine?.destroyRenderer(renderer)
//
//    // SwapChain - REQUIRED FOR UNIFIED RENDERING
//    fun createSwapChain(surface: Surface): SwapChain? = engine?.createSwapChain(surface)
//    fun createSwapChain(surface: Surface, flags: Long): SwapChain? = engine?.createSwapChain(surface, flags)
//    fun destroySwapChain(swapChain: SwapChain) = engine?.destroySwapChain(swapChain)
//
//    // Scene
//    fun createScene(): Scene? = engine?.createScene()
//    fun destroyScene(scene: Scene) = engine?.destroyScene(scene)
//
//    // View
//    fun createView(): View? = engine?.createView()
//    fun destroyView(view: View) = engine?.destroyView(view)
//
//    // Camera
//    fun createCamera(): Camera? {
//        val entityManager = EntityManager.get()
//        val cameraEntity = entityManager.create()
//        return engine?.createCamera(cameraEntity)
//    }
//
//    fun destroyCamera(camera: Camera) {
//        engine?.let { eng ->
//            eng.destroyCameraComponent(camera.entity)
//            EntityManager.get().destroy(camera.entity)
//        }
//    }
//
//    @Synchronized
//    fun shutdown() {
//        engine?.destroy()
//        engine = null
//        isInitialized = false
//        Log.d(TAG, "Filament Engine shutdown")
//    }
//}
//*/
