package com.infusory.tutarapp.filament.unified

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.infusory.tutarapp.filament.core.FilamentEngineProvider

/**
 * Manages the fullscreen SurfaceView used for unified 3D rendering.
 * 
 * This creates a single SurfaceView that all virtual 3D containers render to,
 * using viewport-based rendering instead of multiple SurfaceViews.
 * 
 * Key responsibilities:
 * - Create and manage the fullscreen SurfaceView
 * - Handle SurfaceHolder callbacks
 * - Create/destroy Filament SwapChain
 * - Create single Filament Renderer for all containers
 */
class Unified3DSurface(private val context: Context) : SurfaceHolder.Callback {
    
    companion object {
        private const val TAG = "Unified3DSurface"
    }
    
    // Surface view
    private var surfaceView: SurfaceView? = null
    private var surfaceHolder: SurfaceHolder? = null
    
    // Filament objects
    private var swapChain: SwapChain? = null
    private var renderer: Renderer? = null
    
    // State
    private var isReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    
    // Callbacks
    var onSurfaceReady: ((Int, Int) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null
    var onSurfaceResized: ((Int, Int) -> Unit)? = null
    
    /**
     * Create and add the unified surface view to the parent layout.
     * 
     * @param parent The parent ViewGroup to add the surface to
     * @param insertIndex Index at which to insert the surface (for z-ordering)
     * @return The created SurfaceView
     */
    fun create(parent: ViewGroup, insertIndex: Int = 1): SurfaceView {
        if (surfaceView != null) {
            Log.w(TAG, "Surface already created")
            return surfaceView!!
        }
        
        surfaceView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Transparent background so annotation surface shows through where no 3D content
            setBackgroundColor(Color.TRANSPARENT)
            // Don't place on top - we want it below Container3D overlays
            setZOrderOnTop(false)
            setZOrderMediaOverlay(false)
            // Set pixel format for transparency
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        
        // Add to parent at specified index
        parent.addView(surfaceView, insertIndex)
        
        // Setup surface holder callbacks
        surfaceHolder = surfaceView!!.holder
        surfaceHolder?.addCallback(this)
        
        Log.d(TAG, "Unified 3D surface created and added to layout at index $insertIndex")
        
        return surfaceView!!
    }
    
    /**
     * Remove and destroy the surface view
     */
    fun destroy() {
        Log.d(TAG, "Destroying unified 3D surface")
        
        // Remove surface holder callback
        surfaceHolder?.removeCallback(this)
        
        // Destroy Filament objects
        destroyFilamentObjects()
        
        // Remove from parent
        surfaceView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        
        surfaceView = null
        surfaceHolder = null
        isReady = false
    }
    
    /**
     * Get the Filament SwapChain for rendering
     */
    fun getSwapChain(): SwapChain? = swapChain
    
    /**
     * Get the Filament Renderer
     */
    fun getRenderer(): Renderer? = renderer
    
    /**
     * Check if the surface is ready for rendering
     */
    fun isReady(): Boolean = isReady
    
    /**
     * Get the surface dimensions
     */
    fun getSurfaceWidth(): Int = surfaceWidth
    fun getSurfaceHeight(): Int = surfaceHeight
    
    /**
     * Get the SurfaceView (for touch handling)
     */
    fun getSurfaceView(): SurfaceView? = surfaceView
    
    // ==================== SurfaceHolder.Callback ====================
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        
        try {
            // Create SwapChain from the surface
            swapChain = FilamentEngineProvider.createSwapChain(holder.surface)
            
            // Create single Renderer for all unified rendering
            renderer = FilamentEngineProvider.createRenderer()
            
            if (swapChain != null && renderer != null) {
                Log.d(TAG, "Filament SwapChain and Renderer created successfully")
            } else {
                Log.e(TAG, "Failed to create Filament objects")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Filament objects", e)
        }
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        
        surfaceWidth = width
        surfaceHeight = height
        
        // Recreate swap chain with new size if needed
        if (swapChain != null) {
            try {
                // Destroy old swap chain
                swapChain?.let { FilamentEngineProvider.destroySwapChain(it) }
                
                // Create new swap chain
                swapChain = FilamentEngineProvider.createSwapChain(holder.surface)
                
                isReady = (swapChain != null && renderer != null)
                
                if (isReady) {
                    Log.d(TAG, "Surface ready for rendering")
                    onSurfaceReady?.invoke(width, height)
                }
                
                onSurfaceResized?.invoke(width, height)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error recreating swap chain", e)
                isReady = false
            }
        } else {
            // First time - check if ready
            isReady = (swapChain != null && renderer != null)
            if (isReady) {
                onSurfaceReady?.invoke(width, height)
            }
        }
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        
        isReady = false
        destroyFilamentObjects()
        onSurfaceDestroyed?.invoke()
    }
    
    // ==================== Private ====================
    
    private fun destroyFilamentObjects() {
        swapChain?.let { 
            try {
                FilamentEngineProvider.destroySwapChain(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying swap chain", e)
            }
        }
        swapChain = null
        
        renderer?.let {
            try {
                FilamentEngineProvider.destroyRenderer(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying renderer", e)
            }
        }
        renderer = null
    }
}
