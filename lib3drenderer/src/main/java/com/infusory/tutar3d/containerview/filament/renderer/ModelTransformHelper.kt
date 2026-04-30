package com.infusory.tutar3d.containerview.filament.renderer

import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
import com.google.android.filament.gltfio.FilamentAsset
import com.infusory.tutar3d.containerview.filament.core.FilamentEngineProvider
import kotlin.math.max

/**
 * Helper for transforming 3D models to fit the view.
 * Single Responsibility: Calculate and apply transforms to normalize model size/position.
 */
object ModelTransformHelper {
    
    private const val TAG = "ModelTransformHelper"
    
    /**
     * Transform the model to fit within a unit cube centered at origin.
     * This normalizes models of different sizes to fit the view.
     */
    fun transformToUnitCube(asset: FilamentAsset) {
        try {
            val engine = FilamentEngineProvider.getEngine()
            val transformManager = engine.transformManager
            
            // Get bounding box - Box has center and halfExtent properties
            val boundingBox = asset.boundingBox
            
            // Get center and half extent from Box
            val center = boundingBox.center
            val halfExtent = boundingBox.halfExtent
            
            val centerX = center[0]
            val centerY = center[1]
            val centerZ = center[2]
            
            val halfExtentX = halfExtent[0]
            val halfExtentY = halfExtent[1]
            val halfExtentZ = halfExtent[2]
            
            // Find maximum extent for uniform scaling
            val maxExtent = max(max(halfExtentX, halfExtentY), halfExtentZ)
            val scaleFactor = if (maxExtent > 0f) 1f / maxExtent else 1f
            
            SdkLog.d(TAG, "Model bounds: center=($centerX, $centerY, $centerZ), maxExtent=$maxExtent, scale=$scaleFactor")
            
            // Get transform instance for root entity
            val rootInstance = transformManager.getInstance(asset.root)
            
            if (rootInstance == 0) {
                SdkLog.w(TAG, "Root entity has no transform component, creating one")
                // Create transform component if it doesn't exist
                transformManager.create(asset.root)
            }
            
            // Build transform matrix: Scale then Translate to center
            val transform = FloatArray(16)
            android.opengl.Matrix.setIdentityM(transform, 0)
            android.opengl.Matrix.scaleM(transform, 0, scaleFactor, scaleFactor, scaleFactor)
            android.opengl.Matrix.translateM(transform, 0, -centerX, -centerY, -centerZ)
            
            // Apply transform
            val instance = transformManager.getInstance(asset.root)
            if (instance != 0) {
                transformManager.setTransform(instance, transform)
                SdkLog.d(TAG, "Transform applied to model")
            } else {
                SdkLog.e(TAG, "Failed to get transform instance after creation")
            }
            
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to transform model to unit cube", e)
        }
    }
    
    /**
     * Get the bounding box dimensions of an asset
     */
    fun getBoundingBoxSize(asset: FilamentAsset): FloatArray {
        val boundingBox = asset.boundingBox
        val halfExtent = boundingBox.halfExtent
        
        // Full size is 2 * halfExtent
        return floatArrayOf(
            halfExtent[0] * 2f,
            halfExtent[1] * 2f,
            halfExtent[2] * 2f
        )
    }
    
    /**
     * Get the center point of an asset's bounding box
     */
    fun getBoundingBoxCenter(asset: FilamentAsset): FloatArray {
        val boundingBox = asset.boundingBox
        val center = boundingBox.center
        
        return floatArrayOf(
            center[0],
            center[1],
            center[2]
        )
    }
    
    /**
     * Apply a custom transform to an asset
     */
    fun setTransform(asset: FilamentAsset, transform: FloatArray) {
        try {
            val engine = FilamentEngineProvider.getEngine()
            val transformManager = engine.transformManager
            
            val instance = transformManager.getInstance(asset.root)
            if (instance != 0) {
                transformManager.setTransform(instance, transform)
            }
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to set transform", e)
        }
    }
    
    /**
     * Get the current transform of an asset
     */
    fun getTransform(asset: FilamentAsset): FloatArray? {
        return try {
            val engine = FilamentEngineProvider.getEngine()
            val transformManager = engine.transformManager
            
            val instance = transformManager.getInstance(asset.root)
            if (instance != 0) {
                val transform = FloatArray(16)
                transformManager.getTransform(instance, transform)
                transform
            } else {
                null
            }
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to get transform", e)
            null
        }
    }
}
