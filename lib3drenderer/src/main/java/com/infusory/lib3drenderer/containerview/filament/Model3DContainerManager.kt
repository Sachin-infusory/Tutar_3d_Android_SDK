package com.infusory.tutarapp

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.infusory.tutarapp.filament.FilamentEngineManager
import com.infusory.tutarapp.filament.renderer.CameraController
import com.infusory.lib3drenderer.containerview.Container3D
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages 3D model containers - registration, serialization, and lifecycle.
 */
object Model3DContainerManager {

    private const val TAG = "Model3DContainerManager"

    private val containers = mutableListOf<Container3D>()

    fun registerContainer(container: Container3D) {
        containers.add(container)
        Log.d(TAG, "Registered container. Total: ${containers.size}")
    }

    fun unregisterContainer(container: Container3D) {
        containers.remove(container)
        Log.d(TAG, "Unregistered container. Total: ${containers.size}")
    }

    /**
     * Save all 3D model containers to JSON (runs on background thread)
     */
    fun saveAllContainersToJsonAsync(
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonArray = JSONArray()

                val containersCopy = withContext(Dispatchers.Main) {
                    containers.toList()
                }

                val totalContainers = containersCopy.size

                containersCopy.forEachIndexed { index, container ->
                    val state = withContext(Dispatchers.Main) {
                        try {
                            container.updateCurrentTransform()
                            delay(50)
                            container.saveState()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving container state", e)
                            null
                        }
                    }

                    state?.let {
                        val jsonObject = JSONObject().apply {
                            put("x", it.x)
                            put("y", it.y)
                            put("width", it.width)
                            put("height", it.height)
                            put("modelPath", it.modelPath)
                            put("modelName", it.modelName)

                            it.cameraOrbit?.let { orbit ->
                                val matrixArray = JSONArray()
                                orbit.viewMatrix.forEach { value -> matrixArray.put(value.toDouble()) }
                                put("cameraViewMatrix", matrixArray)
                            }
                        }
                        jsonArray.put(jsonObject)
                    }

                    withContext(Dispatchers.Main) {
                        onProgress(index + 1, totalContainers)
                    }

                    Log.d(TAG, "Saved 3D model container ${index + 1}/$totalContainers")
                }

                val jsonString = jsonArray.toString()
                Log.d(TAG, "Total JSON size: ${jsonString.length / 1024} KB")

                withContext(Dispatchers.Main) {
                    onComplete(jsonString)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving containers", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    /**
     * Load 3D model containers from JSON
     */
    fun loadContainersFromJsonAsync(
        context: Context,
        json: String,
        parent: ViewGroup,
        onContainerCreated: (Container3D) -> Unit,
        onComplete: () -> Unit = {},
        onError: (Exception) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonArray = JSONArray(json)
                val totalContainers = jsonArray.length()

                for (i in 0 until jsonArray.length()) {
                    val containerJson = jsonArray.getJSONObject(i)

                    val x = containerJson.getDouble("x").toFloat()
                    val y = containerJson.getDouble("y").toFloat()
                    val width = containerJson.getInt("width")
                    val height = containerJson.getInt("height")
                    val modelPath = containerJson.getString("modelPath")
                    val modelName = containerJson.getString("modelName")

                    val cameraOrbit = if (containerJson.has("cameraViewMatrix")) {
                        val matrixArray = containerJson.getJSONArray("cameraViewMatrix")
                        val viewMatrix = FloatArray(16) { idx ->
                            matrixArray.getDouble(idx).toFloat()
                        }
                        Container3D.CameraOrbitState(viewMatrix = viewMatrix)
                    } else {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        val container = Container3D(context, initialModelData = modelPath)

                        parent.addView(container)

                        val containerState = Container3D.ContainerState(
                            x = x,
                            y = y,
                            width = width,
                            height = height,
                            modelPath = modelPath,
                            modelName = modelName,
                            cameraOrbit = cameraOrbit
                        )

                        container.currentModelData = com.infusory.lib3drenderer.containerview.ModelData(
                            name = modelName,
                            filename = modelPath
                        )

                        container.loadState(containerState)

                        Log.d(TAG, "Loaded 3D model: $modelName at ($x, $y)")

                        onContainerCreated(container)
                        onProgress(i + 1, totalContainers)
                    }

                    Log.d(TAG, "Loaded 3D model container ${i + 1}/$totalContainers")
                }

                withContext(Dispatchers.Main) {
                    onComplete()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading containers", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun getContainerCount(): Int = containers.size

    fun pauseAllRendering() {
        containers.forEach { it.pauseRendering() }
    }

    fun resumeAllRendering() {
        containers.forEach { it.resumeRendering() }
    }

    /**
     * Properly destroy all containers and clear references
     */
    fun clearAll(onComplete: () -> Unit = {}) {
        CoroutineScope(Dispatchers.Main).launch {
            val containersCopy = containers.toList()

            Log.d(TAG, "Clearing ${containersCopy.size} containers")

            containersCopy.forEach { container ->
                try {
                    container.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error destroying container", e)
                }
            }

            containers.clear()

            System.gc()

            Log.d(TAG, "All containers cleared. Total: ${containers.size}")

            onComplete()
        }
    }

    /**
     * Remove a specific container
     */
    fun removeContainer(container: Container3D, onComplete: () -> Unit = {}) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                unregisterContainer(container)
                container.destroy()
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing container", e)
            }
        }
    }

    /**
     * Get all active containers (that are still attached to window)
     */
    fun getActiveContainers(): List<Container3D> {
        return containers.filter { it.isAttachedToWindow }
    }

    /**
     * Clean up orphaned container references
     */
    fun cleanupOrphanedContainers() {
        val orphaned = containers.filterNot { it.isAttachedToWindow }
        if (orphaned.isNotEmpty()) {
            Log.d(TAG, "Removing ${orphaned.size} orphaned containers")
            containers.removeAll(orphaned.toSet())
        }
    }

    /**
     * Get Filament engine statistics
     */
    fun getEngineStats(): String {
        return if (FilamentEngineManager.isInitialized()) {
            val stats = FilamentEngineManager.getStats()
            "Renderers: ${stats.renderableCount}, Frames: ${stats.totalFrames}"
        } else {
            "Engine not initialized"
        }
    }
}
