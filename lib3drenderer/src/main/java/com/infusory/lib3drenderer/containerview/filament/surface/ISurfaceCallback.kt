package com.infusory.tutarapp.filament.surface

import android.view.Surface

/**
 * Interface for receiving surface lifecycle events.
 * Follows Interface Segregation Principle.
 */
interface ISurfaceCallback {
    
    /**
     * Called when a native window surface becomes available
     */
    fun onSurfaceAvailable(surface: Surface)
    
    /**
     * Called when the surface is resized
     */
    fun onSurfaceResized(width: Int, height: Int)
    
    /**
     * Called when the surface is being destroyed
     */
    fun onSurfaceDestroyed()
}
