package com.infusory.lib3drenderer.containerview

import android.util.Log

/**
 * Provides the decryption key by combining Kotlin-side and native-side
 * secret fragments. Neither side alone contains enough information
 * to reconstruct the key.
 *
 * Kotlin holds: 16-byte XOR mask fragment
 * Native holds: XOR-encoded password blob + second 16-byte mask (computed via arithmetic)
 * Result: native combines both masks, XORs the blob, returns the password
 */
internal object NativeKeyProvider {

    private const val TAG = "NativeKeyProvider"

    init {
        System.loadLibrary("render_bridge")
    }

    /**
     * Returns the decryption password as a ByteArray.
     * Caller MUST zero out the returned array after use.
     */
    fun getKey(): ByteArray? {
        return try {
            val mask = kotlinMaskFragment()
            val key = getDecryptionKey(mask)
            // Zero the mask copy
            mask.fill(0)
            key
        } catch (e: Exception) {
            Log.e(TAG, "Key retrieval failed", e)
            null
        }
    }

    /**
     * Kotlin-side mask fragment (first 16 bytes of the XOR key).
     * Stored as computed values, not a readable array literal.
     */
    private fun kotlinMaskFragment(): ByteArray {
        val m = ByteArray(16)
        m[0]  = (0x53 + 0x54).toByte()          // 0xA7
        m[1]  = (0x2E shl 1).toByte()            // 0x5C
        m[2]  = (0x48 + 0x49).toByte()           // 0x91
        m[3]  = (0x79 + 0x7A).toByte()           // 0xF3
        m[4]  = (0xD6 shr 1).toByte()            // 0x6B
        m[5]  = (0x6F shl 1).toByte()            // 0xDE
        m[6]  = (0x21 shl 1).toByte()            // 0x42
        m[7]  = (0x0D + 0x0D).toByte()           // 0x1A
        m[8]  = (0x47 + 0x48).toByte()           // 0x8F
        m[9]  = (0x59 + 0x5A).toByte()           // 0xB3
        m[10] = (0x62 + 0x63).toByte()           // 0xC5
        m[11] = (0x3F shl 1).toByte()            // 0x7E
        m[12] = (0x52 xor 0x7B).toByte()         // 0x29
        m[13] = (0x02 shl 1).toByte()            // 0x04
        m[14] = (0x74 + 0x74).toByte()           // 0xE8
        m[15] = (0x2E + 0x2F).toByte()           // 0x5D
        return m
    }

    // JNI bridge — implemented in cosmiq_keys.cpp
    private external fun getDecryptionKey(kotlinMask: ByteArray): ByteArray?
}