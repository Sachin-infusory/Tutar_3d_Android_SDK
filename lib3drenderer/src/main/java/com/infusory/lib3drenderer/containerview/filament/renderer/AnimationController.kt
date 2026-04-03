package com.infusory.tutarapp.filament.renderer

import android.util.Log
import com.google.android.filament.gltfio.Animator
import com.infusory.tutarapp.filament.config.FilamentConfig

/**
 * Animation controller with aggressive optimization for low-end devices.
 */
class AnimationController {

    companion object {
        private const val TAG = "AnimationController"
    }

    private var animator: Animator? = null

    private var animationCount = 0
    private var cachedDurations: FloatArray? = null
    private var maxDuration = 0f

    private var startTimeNanos = 0L
    private var pausedAtNanos = 0L

    private var paused = true
    private var playAll = true
    private var currentIndex = 0

    private var names: List<String>? = null

    // Frame counter for bone skipping
    private var frameCounter = 0

    // Timing stats
    private var totalApplyTimeNs = 0L
    private var totalBoneTimeNs = 0L
    private var updateCount = 0

    fun initialize(animator: Animator?) {
        this.animator = animator ?: return

        animationCount = animator.animationCount
        if (animationCount == 0) return

        cachedDurations = FloatArray(animationCount) { i ->
            animator.getAnimationDuration(i)
        }

        maxDuration = cachedDurations?.maxOrNull() ?: 0f

        startTimeNanos = System.nanoTime()
        pausedAtNanos = 0L
        paused = false
        playAll = animationCount > 1
        currentIndex = 0
        names = null
        frameCounter = 0

        Log.d(TAG, "Initialized: $animationCount animations, maxDuration=$maxDuration")
    }

    /**
     * Update animations with timing measurement.
     */
    fun update(frameTimeNanos: Long) {
        val anim = animator ?: return
        if (paused || animationCount == 0) return

        val durations = cachedDurations ?: return

        frameCounter++
        updateCount++

        val elapsedNanos = frameTimeNanos - startTimeNanos
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0

        // PHASE 1: Apply animation (sets pose, relatively cheap)
        val applyStart = System.nanoTime()

        if (playAll) {
            if (maxDuration > 0f && elapsedSeconds >= maxDuration) {
                startTimeNanos = frameTimeNanos
            }

            for (i in 0 until animationCount) {
                val duration = durations[i]
                if (duration > 0f) {
                    val time = (elapsedSeconds % duration).toFloat()
                    anim.applyAnimation(i, time)
                }
            }
        } else {
            val duration = durations[currentIndex]
            if (duration > 0f && elapsedSeconds >= duration) {
                currentIndex = (currentIndex + 1) % animationCount
                startTimeNanos = frameTimeNanos
                anim.applyAnimation(currentIndex, 0f)
            } else {
                anim.applyAnimation(currentIndex, elapsedSeconds.toFloat())
            }
        }

        val applyTime = System.nanoTime() - applyStart
        totalApplyTimeNs += applyTime

        // PHASE 2: Update bone matrices (VERY EXPENSIVE - skip on some frames)
        val skipFrames = FilamentConfig.BONE_UPDATE_SKIP_FRAMES
        if (skipFrames <= 1 || frameCounter % skipFrames == 0) {
            val boneStart = System.nanoTime()
            anim.updateBoneMatrices()
            val boneTime = System.nanoTime() - boneStart
            totalBoneTimeNs += boneTime
        }

        // Log timing every 60 updates
        if (updateCount % 60 == 0) {
            val avgApplyMs = (totalApplyTimeNs / 60) / 1_000_000f
            val avgBoneMs = (totalBoneTimeNs / (60 / skipFrames.coerceAtLeast(1))) / 1_000_000f
            Log.d(TAG, "⏱️ Apply: %.2fms | Bones: %.2fms (skip=$skipFrames)".format(avgApplyMs, avgBoneMs))
            totalApplyTimeNs = 0L
            totalBoneTimeNs = 0L
        }
    }

    fun togglePause(): Boolean {
        if (animationCount == 0) return false

        paused = !paused

        if (paused) {
            pausedAtNanos = System.nanoTime() - startTimeNanos
        } else {
            startTimeNanos = System.nanoTime() - pausedAtNanos
            frameCounter = 0
        }

        return paused
    }

    fun isPaused(): Boolean = paused
    fun hasAnimations(): Boolean = animationCount > 0
    fun getAnimationCount(): Int = animationCount

    fun getAnimationNames(): List<String> {
        if (names == null && animator != null && animationCount > 0) {
            names = List(animationCount) { i -> animator!!.getAnimationName(i) }
        }
        return names ?: emptyList()
    }

    fun setCurrentAnimation(index: Int) {
        if (index < 0 || index >= animationCount) return
        currentIndex = index
        playAll = false
        startTimeNanos = System.nanoTime()
        frameCounter = 0
    }

    fun playAllAnimations() {
        playAll = true
        startTimeNanos = System.nanoTime()
        frameCounter = 0
    }

    fun clear() {
        animator = null
        animationCount = 0
        cachedDurations = null
        maxDuration = 0f
        names = null
        frameCounter = 0
    }
}