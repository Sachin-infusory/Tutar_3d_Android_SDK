package com.infusory.tutarapp.filament.core

import android.util.Log
import android.view.Choreographer
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.infusory.tutarapp.filament.renderer.Container3DRenderer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface IRenderable {
    val rendererId: Int
    fun isActive(): Boolean
    fun updateAnimations(frameTimeNanos: Long)
    fun getRenderer(): Renderer?
    fun getSwapChain(): SwapChain?
    fun getView(): View?
    fun onPause()
    fun onResume()
    fun destroy()
}

/**
 * Render loop that requests render from each container.
 *
 * Each container has its own Renderer (created in SurfaceManager).
 * Each container independently decides if it can render based on
 * its SwapChain availability.
 */
class FilamentRenderLoop {

    companion object {
        private const val TAG = "RenderLoop"
        private const val LOG_INTERVAL = 60
    }

    private val containers = ArrayList<Container3DRenderer>(8)
    private val idMap = HashMap<Int, Container3DRenderer>()
    private val nextId = AtomicInteger(0)

    private var choreographer: Choreographer? = null
    private val isRunning = AtomicBoolean(false)

    private val modificationLock = Any()

    @Volatile var frameCount = 0L
        private set

    private var lastChoreographerTimeNanos = 0L
    private var choreographerIntervalAccumulator = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning.get()) return

            choreographer?.postFrameCallback(this)

            if (lastChoreographerTimeNanos > 0) {
                choreographerIntervalAccumulator += frameTimeNanos - lastChoreographerTimeNanos
            }
            lastChoreographerTimeNanos = frameTimeNanos

            frameCount++

            val list: List<Container3DRenderer>
            synchronized(modificationLock) {
                list = ArrayList(containers)
            }

            val count = list.size
            if (count == 0) return

            // Request render from each container
            // Each container has its own Renderer and handles its own beginFrame/endFrame
            for (container in list) {
                if (container.isActive()) {
                    container.requestRender(frameTimeNanos)
                }
            }

            // Logging
            if (frameCount % LOG_INTERVAL == 0L) {
                val avgChoreographerMs = (choreographerIntervalAccumulator / LOG_INTERVAL) / 1_000_000f
                val realFps = if (avgChoreographerMs > 0) 1000f / avgChoreographerMs else 0f


                // Per-container stats
                for (c in list) {
                    val total = c.getTotalFrames()
                    val success = c.getSuccessfulFrames()
                    val rate = c.getSuccessRate()
                }

                choreographerIntervalAccumulator = 0L
            }
        }
    }

    fun markActiveListDirty() {
        // Not needed in this implementation
    }

    fun register(container: Container3DRenderer): Int {
        synchronized(modificationLock) {
            val id = nextId.getAndIncrement()
            containers.add(container)
            idMap[id] = container

            if (containers.size == 1) {
                start()
            }

            Log.d(TAG, "Registered container $id (total: ${containers.size})")
            return id
        }
    }

    fun unregister(id: Int) {
        synchronized(modificationLock) {
            val container = idMap.remove(id) ?: return
            containers.remove(container)

            if (containers.isEmpty()) {
                stop()
            }

            Log.d(TAG, "Unregistered container $id (total: ${containers.size})")
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        lastChoreographerTimeNanos = 0L
        choreographerIntervalAccumulator = 0L
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
        Log.d(TAG, "Started - Each container has its own Renderer")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        choreographer?.removeFrameCallback(frameCallback)
        Log.d(TAG, "Stopped")
    }

    fun pauseAll() {
        synchronized(modificationLock) {
            for (c in containers) c.onPause()
        }
    }

    fun resumeAll() {
        synchronized(modificationLock) {
            for (c in containers) c.onResume()
        }
    }

    fun getRenderableCount(): Int = containers.size
    fun getActiveCount(): Int = containers.count { it.isActive() }
    fun isRunning(): Boolean = isRunning.get()

    fun getStats() = FrameStats(frameCount, 0, containers.size, getActiveCount(), isRunning.get())

    data class FrameStats(
        val totalFrames: Long,
        val skippedFrames: Long,
        val renderableCount: Int,
        val activeCount: Int,
        val isRunning: Boolean
    )
}