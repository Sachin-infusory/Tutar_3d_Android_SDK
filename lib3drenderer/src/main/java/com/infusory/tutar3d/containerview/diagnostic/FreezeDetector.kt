package com.infusory.tutar3d.containerview.diagnostic

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog that runs on its own thread and detects main-thread stalls.
 *
 * The main render loop, audio playback callbacks, and most touch/interaction
 * code run on the main thread. A long stall there freezes the whole UI and
 * silences MediaPlayer. Because the stalled thread can't log, we run a probe
 * from a worker thread: post a no-op runnable to main, time how long until it
 * runs, and if it exceeds STALL_THRESHOLD_MS, dump:
 *   - main thread stack trace (where it's stuck)
 *   - heap usage (rules out GC)
 *   - stall duration
 *
 * Enable/disable via [enabled]. Started/stopped automatically by the render loop.
 */
object FreezeDetector {
    private const val TAG = "FreezeDetector"
    private const val POLL_INTERVAL_MS = 500L
    private const val STALL_THRESHOLD_MS = 1000L

    @Volatile
    var enabled = true

    private val running = AtomicBoolean(false)
    private var watchdogThread: HandlerThread? = null
    private var watchdogHandler: Handler? = null
    private var mainHandler: Handler? = null
    private val mainResponsiveAt = AtomicLong(0L)
    private var lastReportedStallAnchor = 0L

    fun start() {
        if (!enabled) return
        if (running.getAndSet(true)) return
        mainHandler = Handler(Looper.getMainLooper())
        watchdogThread = HandlerThread("freeze-detector").apply { start() }
        watchdogHandler = Handler(watchdogThread!!.looper)
        mainResponsiveAt.set(System.currentTimeMillis())
        scheduleProbe()
        SdkLog.i(TAG, "FreezeDetector started (threshold=${STALL_THRESHOLD_MS}ms)")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        watchdogHandler?.removeCallbacksAndMessages(null)
        watchdogThread?.quitSafely()
        watchdogThread = null
        watchdogHandler = null
        mainHandler = null
        SdkLog.i(TAG, "FreezeDetector stopped")
    }

    private fun scheduleProbe() {
        val handler = watchdogHandler ?: return
        handler.postDelayed({
            if (!running.get()) return@postDelayed
            mainHandler?.post { mainResponsiveAt.set(System.currentTimeMillis()) }

            val now = System.currentTimeMillis()
            val lastResponsive = mainResponsiveAt.get()
            val stallDuration = now - lastResponsive

            if (stallDuration > STALL_THRESHOLD_MS) {
                if (lastReportedStallAnchor != lastResponsive) {
                    lastReportedStallAnchor = lastResponsive
                    reportStall(stallDuration)
                } else {
                    SdkLog.w(TAG, "Main thread still stalled (${stallDuration}ms)")
                }
            } else if (lastReportedStallAnchor != 0L && lastResponsive > lastReportedStallAnchor) {
                SdkLog.w(TAG, "Main thread responsive again")
                lastReportedStallAnchor = 0L
            }
            scheduleProbe()
        }, POLL_INTERVAL_MS)
    }

    private fun reportStall(durationMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val stack = try { mainThread.stackTrace } catch (_: Throwable) { emptyArray() }

        val rt = Runtime.getRuntime()
        val totalMb = rt.totalMemory() / (1024 * 1024)
        val freeMb = rt.freeMemory() / (1024 * 1024)
        val usedMb = totalMb - freeMb
        val maxMb = rt.maxMemory() / (1024 * 1024)

        SdkLog.e(TAG, "==== MAIN THREAD STALLED for ${durationMs}ms ====")
        SdkLog.e(TAG, "Heap: used=${usedMb}MB total=${totalMb}MB max=${maxMb}MB")
        SdkLog.e(TAG, "Main thread state: ${mainThread.state}")
        SdkLog.e(TAG, "Stack (${stack.size} frames):")
        val limit = stack.size.coerceAtMost(50)
        for (i in 0 until limit) {
            SdkLog.e(TAG, "  at ${stack[i]}")
        }
        if (stack.size > limit) {
            SdkLog.e(TAG, "  ... ${stack.size - limit} more frames")
        }
        SdkLog.e(TAG, "==== END STALL REPORT ====")
    }
}
