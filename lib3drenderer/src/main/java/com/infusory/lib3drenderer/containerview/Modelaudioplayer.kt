package com.infusory.lib3drenderer.containerview

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays a looping audio track synced with a 3D model's animation.
 *
 * Looks for a sibling audio file next to the GLB:
 *   human_heart.glb → human_heart.mp3 / .ogg / .wav
 *
 * If no audio file exists, all calls are safe no-ops.
 */
class ModelAudioPlayer {

    companion object {
        private const val TAG = "ModelAudioPlayer"
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".ogg", ".wav")
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false

    /**
     * Look for a sibling audio file and prepare it for looped playback.
     * Does nothing if no matching audio file is found.
     */
    fun attach(modelPath: String) {
        release()

        val audioFile = findAudioFile(modelPath) ?: return

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                isLooping = true
                setOnPreparedListener {
                    isPrepared = true
                    Log.d(TAG, "Audio ready: ${audioFile.name}")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    isPrepared = false
                    true
                }
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare audio: ${audioFile.name}", e)
            release()
        }
    }

    fun play() {
        if (!isPrepared) return
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play", e)
        }
    }

    fun pause() {
        if (!isPrepared) return
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause", e)
        }
    }

    fun release() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPrepared = false
    }

    val isPlaying: Boolean
        get() = isPrepared && (mediaPlayer?.isPlaying == true)

    /**
     * Find audio file next to the model:
     *   /path/to/models/heart.glb → /path/to/models/heart.mp3
     */
    private fun findAudioFile(modelPath: String): File? {
        val basePath = modelPath.substringBeforeLast('.')
        for (ext in AUDIO_EXTENSIONS) {
            val file = File(basePath + ext)
            if (file.exists() && file.canRead()) {
                Log.d(TAG, "Found audio: ${file.name}")
                return file
            }
        }
        return null
    }
}