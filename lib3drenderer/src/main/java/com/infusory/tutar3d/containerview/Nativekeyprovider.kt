package com.infusory.tutar3d.containerview

import android.util.Log
import com.infusory.tutar3d.internal.SdkLog

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
            SdkLog.e(TAG, "Key retrieval failed", e)
            null
        }
    }

    /**
     * Kotlin-side mask fragment (first 16 bytes of the XOR key).
     * Stored as computed values, not a readable array literal.
     */

    // test key mask..............
//    private fun kotlinMaskFragment(): ByteArray {
//        val m = ByteArray(16)
//        m[0]  = (0x53 + 0x54).toByte()          // 0xA7
//        m[1]  = (0x2E shl 1).toByte()            // 0x5C
//        m[2]  = (0x48 + 0x49).toByte()           // 0x91
//        m[3]  = (0x79 + 0x7A).toByte()           // 0xF3
//        m[4]  = (0xD6 shr 1).toByte()            // 0x6B
//        m[5]  = (0x6F shl 1).toByte()            // 0xDE
//        m[6]  = (0x21 shl 1).toByte()            // 0x42
//        m[7]  = (0x0D + 0x0D).toByte()           // 0x1A
//        m[8]  = (0x47 + 0x48).toByte()           // 0x8F
//        m[9]  = (0x59 + 0x5A).toByte()           // 0xB3
//        m[10] = (0x62 + 0x63).toByte()           // 0xC5
//        m[11] = (0x3F shl 1).toByte()            // 0x7E
//        m[12] = (0x52 xor 0x7B).toByte()         // 0x29
//        m[13] = (0x02 shl 1).toByte()            // 0x04
//        m[14] = (0x74 + 0x74).toByte()           // 0xE8
//        m[15] = (0x2E + 0x2F).toByte()           // 0x5D
//        return m
//    }


    private fun kotlinMaskFragment(): ByteArray {
        val m = ByteArray(16)
        m[0]  = (0x29 shl 1).toByte()           // 0x52
        m[1]  = (0x02 shr 1).toByte()           // 0x01
        m[2]  = (0x76 * 0x02).toByte()          // 0xEC
        m[3]  = (0x28 + 0x29).toByte()          // 0x51
        m[4]  = (0x7F shl 1).toByte()           // 0xFE
        m[5]  = (0x53 + 0x53).toByte()          // 0xA6
        m[6]  = (0x59 + 0x5A).toByte()          // 0xB3
        m[7]  = (0x53 * 0x02).toByte()          // 0xA6
        m[8]  = (0x6C shr 1).toByte()           // 0x36
        m[9]  = (0x71 * 0x02).toByte()          // 0xE2
        m[10] = (0x78 * 0x02).toByte()          // 0xF0
        m[11] = (0x02 * 0x02).toByte()          // 0x04
        m[12] = (0x09 + 0x0A).toByte()          // 0x13
        m[13] = (0x7C + 0x77).toByte()          // 0xF3
        m[14] = (0x5B * 0x02).toByte()          // 0xB6
        m[15] = (0x25 * 0x02).toByte()          // 0x4A
        return m
    }

    // JNI bridge — implemented in cosmiq_keys.cpp
    private external fun getDecryptionKey(kotlinMask: ByteArray): ByteArray?
}