package com.infusory.tutar3d.containerview.filament.core

import android.content.Context
import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.infusory.tutar3d.containerview.filament.config.FilamentConfig
import java.nio.ByteBuffer

/**
 * Manages Filament resources: assets, materials, textures.
 * Single Responsibility: Load, cache, and unload 3D assets and related resources.
 * 
 * NOTE: Each container has its own Scene. This manager does NOT manage scenes,
 * it only loads assets. The caller is responsible for adding assets to their scene.
 */
class FilamentResourceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FilamentResourceManager"
    }
    
    private var materialProvider: MaterialProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var indirectLight: IndirectLight? = null
    
    private var isInitialized = false
    
    /**
     * Initialize the resource manager
     */
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        return try {
            val engine = FilamentEngineProvider.getEngine()
            
            // Create material provider (caches materials)
            materialProvider = UbershaderProvider(engine)
            
            // Create asset loader
            assetLoader = AssetLoader(
                engine,
                materialProvider!!,
                EntityManager.get()
            )
            
            // Create resource loader for textures/buffers
            resourceLoader = ResourceLoader(
                engine,
                true   // normalize skinning weights
            )
            
            // Load indirect lighting (will be applied to each scene)
            loadIndirectLight()
            
            isInitialized = true
            SdkLog.d(TAG, "Resource manager initialized")
            true
            
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to initialize resource manager", e)
            destroy()
            false
        }
    }
    
    /**
     * Load indirect lighting from assets
     */
    private fun loadIndirectLight() {
        try {
            val buffer = context.assets.open(FilamentConfig.INDIRECT_LIGHT_ASSET).use { input ->
                ByteBuffer.wrap(input.readBytes())
            }
            
            val engine = FilamentEngineProvider.getEngine()
            
            indirectLight = KTX1Loader.createIndirectLight(engine, buffer).apply {
                intensity = FilamentConfig.INDIRECT_LIGHT_INTENSITY
            }
            
            SdkLog.d(TAG, "Indirect light loaded with intensity ${FilamentConfig.INDIRECT_LIGHT_INTENSITY}")
            
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to load indirect light: ${e.message}")
            // Non-fatal: continue without indirect lighting
        }
    }
    
    /**
     * Get the indirect light to apply to a scene
     */
    fun getIndirectLight(): IndirectLight? = indirectLight
    
    /**
     * Apply indirect light to a scene
     */
    fun applyIndirectLightToScene(scene: Scene) {
        indirectLight?.let { light ->
            scene.indirectLight = light
            SdkLog.d(TAG, "Applied indirect light to scene")
        }
    }
    
    /**
     * Load a glTF/GLB asset from a ByteBuffer.
     * NOTE: The asset is NOT added to any scene. Caller must add to their own scene.
     * 
     * @param buffer The model data
     * @return The loaded FilamentAsset, or null on failure
     */
    fun loadAsset(buffer: ByteBuffer): FilamentAsset? {
        checkInitialized()
        
        return try {
            // Create asset from buffer
            val asset = assetLoader!!.createAsset(buffer)
            
            if (asset == null) {
                SdkLog.e(TAG, "Failed to create asset from buffer")
                return null
            }
            
            // Load external resources (textures, buffers)
            resourceLoader!!.loadResources(asset)
            
            SdkLog.d(TAG, "Asset loaded: ${asset.entities.size} entities")
            asset
            
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to load asset", e)
            null
        }
    }
    
    /**
     * Add an asset's entities to a specific scene
     */
    fun addAssetToScene(asset: FilamentAsset, scene: Scene) {
        asset.entities.forEach { entity ->
            scene.addEntity(entity)
        }
        // Also add the root entity
        scene.addEntity(asset.root)
        SdkLog.d(TAG, "Added ${asset.entities.size} entities to scene")
    }
    
    /**
     * Remove an asset's entities from a specific scene
     */
    fun removeAssetFromScene(asset: FilamentAsset, scene: Scene) {
        asset.entities.forEach { entity ->
            scene.removeEntity(entity)
        }
        scene.removeEntity(asset.root)
        SdkLog.d(TAG, "Removed ${asset.entities.size} entities from scene")
    }
    
    /**
     * Unload and destroy an asset (does NOT remove from scene - caller must do that first)
     */
    fun destroyAsset(asset: FilamentAsset) {
        try {
            assetLoader?.destroyAsset(asset)
            SdkLog.d(TAG, "Asset destroyed")
        } catch (e: Exception) {
            SdkLog.e(TAG, "Error destroying asset", e)
        }
    }
    
    /**
     * Get the asset loader for direct access
     */
    fun getAssetLoader(): AssetLoader {
        checkInitialized()
        return assetLoader!!
    }
    
    /**
     * Get the resource loader for direct access
     */
    fun getResourceLoader(): ResourceLoader {
        checkInitialized()
        return resourceLoader!!
    }
    
    /**
     * Destroy all resources
     */
    fun destroy() {
        SdkLog.d(TAG, "Destroying resource manager...")
        
        val engine = try {
            FilamentEngineProvider.getEngine()
        } catch (e: Exception) {
            null
        }
        
        engine?.let { eng ->
            indirectLight?.let { eng.destroyIndirectLight(it) }
        }
        
        resourceLoader?.destroy()
        assetLoader?.destroy()
        
        (materialProvider as? UbershaderProvider)?.let { provider ->
            provider.destroyMaterials()
            provider.destroy()
        }
        
        indirectLight = null
        resourceLoader = null
        assetLoader = null
        materialProvider = null
        isInitialized = false
        
        SdkLog.d(TAG, "Resource manager destroyed")
    }
    
    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("FilamentResourceManager not initialized")
        }
    }
}
