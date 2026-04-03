package com.infusory.tutarapp.filament.unified

import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Routes touch events on the unified 3D surface to the correct virtual container.
 * 
 * Touch flow:
 * 1. Touch hits unified surface
 * 2. Hit-test against all visible containers (respecting z-order)
 * 3. Route to topmost container's CameraController
 * 4. If no container hit, pass through (return false)
 * 
 * This enables independent orbit/zoom/pan for each 3D model container
 * while sharing a single surface.
 */
class UnifiedTouchRouter(
    private val sceneManager: UnifiedSceneManager
) : View.OnTouchListener {
    
    companion object {
        private const val TAG = "UnifiedTouchRouter"
    }
    
    // Currently active container (receiving touch sequence)
    private var activeContainerId: Int? = null
    
    // Callbacks
    var onContainerTouched: ((Int) -> Unit)? = null
    var onTouchPassThrough: ((MotionEvent) -> Unit)? = null
    
    /**
     * Attach this router to a view
     */
    fun attachTo(view: View) {
        view.setOnTouchListener(this)
        view.isClickable = true
        view.isFocusable = true
        Log.d(TAG, "Touch router attached to view")
    }
    
    /**
     * Detach from view
     */
    fun detachFrom(view: View) {
        view.setOnTouchListener(null)
        activeContainerId = null
        Log.d(TAG, "Touch router detached from view")
    }
    
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false
        
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_CANCEL -> handleActionCancel(event)
            else -> false
        }
    }
    
    /**
     * Manually route a touch event (for external use)
     */
    fun routeTouchEvent(event: MotionEvent): Boolean {
        return onTouch(null, event)
    }
    
    /**
     * Check if a point hits any container
     */
    fun hitTest(x: Float, y: Float): VirtualContainer3D? {
        return sceneManager.getContainerAtPoint(x, y)
    }
    
    // ==================== Touch Handlers ====================
    
    private fun handleActionDown(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        
        // Find container at touch point
        val container = sceneManager.getContainerAtPoint(x, y)
        
        if (container != null) {
            activeContainerId = container.id
            
            // Bring touched container to front
            sceneManager.bringToFront(container.id)
            
            // Notify listener
            onContainerTouched?.invoke(container.id)
            
            // Convert to local coordinates and route to camera controller
            val (localX, localY) = container.screenToLocal(x, y)
            val localEvent = createLocalEvent(event, localX, localY)
            
            val handled = container.cameraController?.onTouchEvent(localEvent) ?: false
            
            Log.d(TAG, "ACTION_DOWN on container ${container.id} at ($x, $y) -> ($localX, $localY), handled=$handled")
            
            return handled
        } else {
            // No container hit - pass through
            activeContainerId = null
            onTouchPassThrough?.invoke(event)
            Log.d(TAG, "ACTION_DOWN at ($x, $y) - no container hit, passing through")
            return false
        }
    }
    
    private fun handlePointerDown(event: MotionEvent): Boolean {
        val containerId = activeContainerId ?: return false
        val container = sceneManager.getContainer(containerId) ?: return false
        
        // Multi-touch - route to active container
        val localEvent = createLocalEventForContainer(event, container)
        return container.cameraController?.onTouchEvent(localEvent) ?: false
    }
    
    private fun handleActionMove(event: MotionEvent): Boolean {
        val containerId = activeContainerId ?: return false
        val container = sceneManager.getContainer(containerId) ?: return false
        
        // Route move to active container's camera controller
        val localEvent = createLocalEventForContainer(event, container)
        return container.cameraController?.onTouchEvent(localEvent) ?: false
    }
    
    private fun handleActionUp(event: MotionEvent): Boolean {
        val containerId = activeContainerId
        activeContainerId = null
        
        if (containerId == null) return false
        
        val container = sceneManager.getContainer(containerId) ?: return false
        
        val localEvent = createLocalEventForContainer(event, container)
        val handled = container.cameraController?.onTouchEvent(localEvent) ?: false
        
        Log.d(TAG, "ACTION_UP on container $containerId")
        
        return handled
    }
    
    private fun handlePointerUp(event: MotionEvent): Boolean {
        val containerId = activeContainerId ?: return false
        val container = sceneManager.getContainer(containerId) ?: return false
        
        val localEvent = createLocalEventForContainer(event, container)
        return container.cameraController?.onTouchEvent(localEvent) ?: false
    }
    
    private fun handleActionCancel(event: MotionEvent): Boolean {
        val containerId = activeContainerId
        activeContainerId = null
        
        if (containerId == null) return false
        
        val container = sceneManager.getContainer(containerId) ?: return false
        
        val localEvent = createLocalEventForContainer(event, container)
        return container.cameraController?.onTouchEvent(localEvent) ?: false
    }
    
    // ==================== Helpers ====================
    
    /**
     * Create a motion event with coordinates translated to container's local space
     */
    private fun createLocalEvent(event: MotionEvent, localX: Float, localY: Float): MotionEvent {
        val offsetX = event.x - localX
        val offsetY = event.y - localY
        
        val localEvent = MotionEvent.obtain(event)
        localEvent.offsetLocation(-offsetX, -offsetY)
        
        return localEvent
    }
    
    /**
     * Create a local event for a container, handling multi-touch
     */
    private fun createLocalEventForContainer(event: MotionEvent, container: VirtualContainer3D): MotionEvent {
        val localEvent = MotionEvent.obtain(event)
        localEvent.offsetLocation(-container.viewportX.toFloat(), -container.viewportY.toFloat())
        return localEvent
    }
}
