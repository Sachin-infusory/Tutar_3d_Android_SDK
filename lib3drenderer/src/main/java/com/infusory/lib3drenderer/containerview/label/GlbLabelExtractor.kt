package com.infusory.lib3drenderer.containerview.label

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts label data from GLB files by parsing the embedded glTF JSON chunk.
 * Labels are nodes with `extras.prop` set — same data Three.js exposes as userData.prop.
 */
object GlbLabelExtractor {

    private const val TAG = "GlbLabelExtractor"
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_TYPE_JSON = 0x4E4F534A

    data class LabelInfo(
        val text: String,
        val nodeIndex: Int,
        val nodeName: String,
        val localPosition: FloatArray
    )

    fun extractLabels(buffer: ByteBuffer): List<LabelInfo> {
        return try {
            val pos = buffer.position()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.position(pos) // rewind to original position
            extractLabels(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract labels from buffer: ${e.message}")
            emptyList()
        }
    }

    fun extractLabels(glbBytes: ByteArray): List<LabelInfo> {
        return try {
            val jsonStr = extractJsonChunk(glbBytes)
            parseLabelsFromJson(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract labels: ${e.message}")
            emptyList()
        }
    }

    fun hasLabels(buffer: ByteBuffer): Boolean = extractLabels(buffer).isNotEmpty()

    private fun extractJsonChunk(glbBytes: ByteArray): String {
        if (glbBytes.size < 20) throw IllegalArgumentException("Too short for GLB")
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != GLB_MAGIC) throw IllegalArgumentException("Not a valid GLB")
        buf.int // version
        buf.int // totalLength
        val chunkLength = buf.int
        val chunkType = buf.int
        if (chunkType != CHUNK_TYPE_JSON) throw IllegalArgumentException("First chunk not JSON")
        val jsonBytes = ByteArray(chunkLength)
        buf.get(jsonBytes)
        return String(jsonBytes, Charsets.UTF_8)
    }

    private fun parseLabelsFromJson(jsonStr: String): List<LabelInfo> {
        val gltf = JSONObject(jsonStr)
        val nodesArray = gltf.optJSONArray("nodes") ?: return emptyList()
        val labels = mutableListOf<LabelInfo>()

        for (i in 0 until nodesArray.length()) {
            val node = nodesArray.getJSONObject(i)
            val extras = node.optJSONObject("extras") ?: continue
            val prop = extras.optString("prop", "")
            if (prop.isNotEmpty()) {
                val name = node.optString("name", "node_$i")
                val t = node.optJSONArray("translation")
                val position = floatArrayOf(
                    t?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                    t?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                    t?.optDouble(2, 0.0)?.toFloat() ?: 0f,
                )
                labels.add(LabelInfo(prop, i, name, position))
            }
        }
        Log.d(TAG, "Found ${labels.size} labels")
        return labels
    }
}
