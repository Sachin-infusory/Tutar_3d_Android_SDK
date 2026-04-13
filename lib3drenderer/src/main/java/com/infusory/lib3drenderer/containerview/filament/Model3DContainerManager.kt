package com.infusory.tutarapp

import android.util.Log
import com.infusory.lib3drenderer.containerview.Container3D

/**
 * Tracks active 3D model container instances.
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

    fun getContainerCount(): Int = containers.size
}