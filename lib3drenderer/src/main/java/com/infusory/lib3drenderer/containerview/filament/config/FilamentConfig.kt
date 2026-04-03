package com.infusory.tutarapp.filament.config

import com.google.android.filament.View

/**
 * AGGRESSIVE performance settings for very low-end devices.
 */
object FilamentConfig {

    // ========== Render Quality - MINIMUM ==========
    val RENDER_QUALITY: View.QualityLevel = View.QualityLevel.LOW
    val ANTI_ALIASING: View.AntiAliasing = View.AntiAliasing.NONE
    val DITHERING: View.Dithering = View.Dithering.NONE

    // ========== Lighting ==========
    const val INDIRECT_LIGHT_INTENSITY = 30_000f
    const val INDIRECT_LIGHT_ASSET = "default_env_ibl.ktx"

    // ========== Frame Pacing ==========
    const val TARGET_FPS = 30
    const val TARGET_FRAME_TIME_NS = 1_000_000_000L / TARGET_FPS
    const val FRAME_SKIP_THRESHOLD_NS = 100_000_000L

    // ========== CRITICAL: Animation Optimization ==========
    // Skip bone matrix updates - this is the MOST expensive operation
    // 3 = update bones every 3rd frame (67% reduction in animation CPU)
    // 4 = update bones every 4th frame (75% reduction)
    const val BONE_UPDATE_SKIP_FRAMES = 3

    // ========== Multi-Container Rendering ==========
    // 1 = render one container per frame (fairest, lowest per-container FPS)
    const val MAX_CONTAINERS_PER_FRAME = 1

    // ========== Post-Processing (all disabled) ==========
    const val ENABLE_SHADOWS = false
    const val ENABLE_BLOOM = false
    const val ENABLE_SSAO = false
    const val ENABLE_SSR = false
    const val ENABLE_FOG = false
    const val ENABLE_DOF = false
    const val ENABLE_VIGNETTE = false
    const val ENABLE_DYNAMIC_RESOLUTION = false

    // ========== Surface Settings ==========
    const val SURFACE_FORMAT = android.graphics.PixelFormat.RGBA_8888
    const val Z_ORDER_ON_TOP = true
    const val SWAP_CHAIN_FLAGS = 0L

    // ========== Camera ==========
    const val DEFAULT_CAMERA_FOV = 45.0
    const val DEFAULT_CAMERA_NEAR = 0.1
    const val DEFAULT_CAMERA_FAR = 100.0
    const val DEFAULT_CAMERA_DISTANCE = 4.0

    val CLEAR_COLOR = floatArrayOf(0f, 0f, 0f, 0f)

    // ========== Memory ==========
    const val MAX_CACHED_ASSETS = 3
    const val ASSET_UNLOAD_DELAY_MS = 2000L
}