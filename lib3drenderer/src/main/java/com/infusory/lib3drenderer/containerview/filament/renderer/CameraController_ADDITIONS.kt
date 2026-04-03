package com.infusory.tutarapp.filament.renderer//package com.infusory.tutarapp.filament.renderer
//
///**
// * CAMERA CONTROLLER ADDITIONS
// * ===========================
// *
// * Add this method to your existing CameraController.kt file.
// * This is needed for unified rendering mode to update projection
// * without resetting the viewport position.
// */
//
//// Add this method to CameraController class:
//
///**
// * Update only the camera projection for new dimensions.
// * Used by unified rendering where viewport position is set separately.
// *
// * Unlike setViewport(), this does NOT reset the view's viewport to (0,0).
// */
//fun updateProjectionOnly(width: Int, height: Int) {
//    if (width <= 0 || height <= 0) return
//
//    viewportWidth = width
//    viewportHeight = height
//
//    // Only update the camera projection, not the view's viewport
//    updateProjection()
//
//    Log.d(TAG, "Projection updated for ${width}x${height}")
//}
