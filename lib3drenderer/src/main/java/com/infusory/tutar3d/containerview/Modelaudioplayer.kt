package com.infusory.tutar3d.containerview

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
import java.io.File
import java.util.concurrent.Executors

/**
 * Plays a looping audio track synced with a 3D model's animation.
 *
 * Searches for a sibling audio file using these strategies (in order):
 *   1. Next to the resolved model file:  /path/to/heart.glb -> /path/to/heart.mp3
 *   2. In filesDir/models/{baseName}.{ext}  (covers cloud-downloaded models)
 *   3. In assets/models/{baseName}.{ext} or assets/{baseName}.{ext}
 *
 * If no audio file is found, all calls are safe no-ops.
 *
 * Threading:
 *   - Public methods (attach/play/pause/release) are safe to call from the main thread.
 *   - The MediaPlayer instance is constructed and operated on a dedicated worker
 *     HandlerThread. This is critical: MediaPlayer's EventHandler is bound to the
 *     thread that constructs it, and on some Android implementations (notably MTK
 *     panel builds like CVTE MTK9679) the audio HAL's listAudioPatches /
 *     getRoutedDevice JNI calls take 1-3 seconds. If the player were constructed
 *     on the main thread, those routing-change events would freeze rendering.
 *     Running everything on a worker thread keeps those stalls off the UI.
 */
class ModelAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "ModelAudioPlayer"
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".ogg", ".wav")
        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ModelAudioPlayer-IO").apply { isDaemon = true }
        }
    }

    // Lifecycle / state. All written from mpHandler thread, read from anywhere
    // (main + IO + mp), so volatile.
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isPrepared = false
    @Volatile private var playRequested = false
    @Volatile private var released = false
    @Volatile private var muted = false
    @Volatile private var prepareStartedAt = 0L

    /**
     * Fires on the main thread once attach() has determined whether a
     * matching audio file exists for this model. true = audio file found
     * and a player is being prepared; false = no audio file for this model.
     */
    var onAudioAvailable: ((Boolean) -> Unit)? = null

    val isMuted: Boolean get() = muted

    // Dedicated thread that owns the MediaPlayer. Construction here binds the
    // MediaPlayer.EventHandler (routing changes, prepared, error, info) to this
    // thread's Looper, keeping slow audio-HAL callbacks off the main thread.
    private val mpThread = HandlerThread("ModelAudioPlayer-MP").apply { start() }
    private val mpHandler = Handler(mpThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(modelPath: String) {
        // Reset any previous playback (synchronously sets flags so subsequent
        // public calls behave correctly; the actual player teardown is queued).
        released = false
        playRequested = false
        muted = false
        mpHandler.post { teardownPlayerLocked() }

        ioExecutor.execute {
            val source = resolveSource(modelPath)
            // Notify availability on the main thread so UI (e.g. mute button)
            // can be shown/hidden. Fires regardless of whether prepare succeeds.
            mainHandler.post {
                if (!released) onAudioAvailable?.invoke(source != null)
            }
            mpHandler.post {
                if (released) {
                    source?.close()
                    return@post
                }
                if (source == null) {
                    SdkLog.d(TAG, "No audio file for $modelPath")
                    return@post
                }
                preparePlayer(source)
            }
        }
    }

    /**
     * Mute or unmute the audio. Implemented as a volume change so playback
     * position is preserved and there's no codec restart cost.
     */
    fun setMuted(value: Boolean) {
        muted = value
        mpHandler.post {
            if (released) return@post
            try {
                val v = if (value) 0f else 1f
                mediaPlayer?.setVolume(v, v)
            } catch (e: Exception) {
                SdkLog.e(TAG, "setVolume failed", e)
            }
        }
    }

    private fun preparePlayer(source: AudioSource) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                isLooping = true
                source.applyTo(this)
                setOnPreparedListener { mp ->
                    if (released) {
                        try { mp.release() } catch (_: Exception) {}
                        return@setOnPreparedListener
                    }
                    isPrepared = true
                    SdkLog.d(TAG, "Audio ready (prep=${System.currentTimeMillis() - prepareStartedAt}ms)")
                    if (muted) {
                        try { mp.setVolume(0f, 0f) } catch (_: Exception) {}
                    }
                    if (playRequested) {
                        try { mp.start() } catch (e: Exception) { SdkLog.e(TAG, "start failed", e) }
                    }
                }
                setOnErrorListener { _, what, extra ->
                    SdkLog.e(TAG, "MediaPlayer ERROR: what=${errorName(what)}($what) extra=$extra")
                    isPrepared = false
                    true
                }
                setOnInfoListener { _, what, extra ->
                    SdkLog.w(TAG, "MediaPlayer INFO: ${infoName(what)}($what) extra=$extra")
                    false
                }
                prepareStartedAt = System.currentTimeMillis()
                prepareAsync()
            }
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to start async prepare", e)
            source.close()
            teardownPlayerLocked()
        }
    }

    fun play() {
        playRequested = true
        mpHandler.post {
            if (released || !isPrepared) return@post
            val t0 = System.nanoTime()
            try {
                mediaPlayer?.let { if (!it.isPlaying) it.start() }
            } catch (e: Exception) {
                SdkLog.e(TAG, "Failed to play", e)
                return@post
            }
            val durMs = (System.nanoTime() - t0) / 1_000_000
            if (durMs > 50) SdkLog.w(TAG, "start() took ${durMs}ms on audio thread")
        }
    }

    fun pause() {
        playRequested = false
        mpHandler.post {
            if (released || !isPrepared) return@post
            val t0 = System.nanoTime()
            try {
                mediaPlayer?.let { if (it.isPlaying) it.pause() }
            } catch (e: Exception) {
                SdkLog.e(TAG, "Failed to pause", e)
                return@post
            }
            val durMs = (System.nanoTime() - t0) / 1_000_000
            if (durMs > 50) SdkLog.w(TAG, "pause() took ${durMs}ms on audio thread")
        }
    }

    fun release() {
        released = true
        playRequested = false
        isPrepared = false
        mpHandler.post {
            teardownPlayerLocked()
            mpThread.quitSafely()
        }
    }

    /** Must be called from mpHandler thread. */
    private fun teardownPlayerLocked() {
        try {
            mediaPlayer?.let {
                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPrepared = false
    }

    val isPlaying: Boolean
        get() = isPrepared && (mediaPlayer?.isPlaying == true)

    private sealed class AudioSource {
        abstract fun applyTo(mp: MediaPlayer)
        abstract fun close()

        class FileSource(private val path: String) : AudioSource() {
            override fun applyTo(mp: MediaPlayer) { mp.setDataSource(path) }
            override fun close() {}
        }

        class AssetSource(private val afd: AssetFileDescriptor) : AudioSource() {
            override fun applyTo(mp: MediaPlayer) {
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                close()
            }
            override fun close() { try { afd.close() } catch (_: Exception) {} }
        }
    }

    private fun infoName(what: Int) = when (what) {
        MediaPlayer.MEDIA_INFO_BUFFERING_START -> "BUFFERING_START"
        MediaPlayer.MEDIA_INFO_BUFFERING_END -> "BUFFERING_END"
        MediaPlayer.MEDIA_INFO_AUDIO_NOT_PLAYING -> "AUDIO_NOT_PLAYING"
        MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> "NOT_SEEKABLE"
        MediaPlayer.MEDIA_INFO_METADATA_UPDATE -> "METADATA_UPDATE"
        MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING -> "BAD_INTERLEAVING"
        MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING -> "VIDEO_TRACK_LAGGING"
        else -> "UNKNOWN"
    }

    private fun errorName(what: Int) = when (what) {
        MediaPlayer.MEDIA_ERROR_UNKNOWN -> "UNKNOWN"
        MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "SERVER_DIED"
        MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> "NOT_VALID_FOR_PROGRESSIVE_PLAYBACK"
        else -> "OTHER"
    }

    private fun resolveSource(modelPath: String): AudioSource? {
        val basePath = modelPath.substringBeforeLast('.')
        val baseName = basePath.substringAfterLast('/').substringAfterLast('\\')

        for (ext in AUDIO_EXTENSIONS) {
            val f = File(basePath + ext)
            if (f.exists() && f.canRead()) {
                SdkLog.d(TAG, "Found audio: ${f.name}")
                return AudioSource.FileSource(f.absolutePath)
            }
        }

        if (baseName.isNotEmpty()) {
            for (ext in AUDIO_EXTENSIONS) {
                val f = File(context.filesDir, "models/$baseName$ext")
                if (f.exists() && f.canRead()) {
                    SdkLog.d(TAG, "Found audio in filesDir/models: ${f.name}")
                    return AudioSource.FileSource(f.absolutePath)
                }
            }
        }

        if (baseName.isNotEmpty()) {
            for (ext in AUDIO_EXTENSIONS) {
                for (path in listOf("models/$baseName$ext", "$baseName$ext")) {
                    try {
                        val afd = context.assets.openFd(path)
                        SdkLog.d(TAG, "Found audio in assets: $path")
                        return AudioSource.AssetSource(afd)
                    } catch (_: Exception) {}
                }
            }
        }

        return null
    }
}
