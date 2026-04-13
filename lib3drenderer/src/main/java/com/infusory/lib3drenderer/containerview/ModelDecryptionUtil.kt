package com.infusory.lib3drenderer.containerview

import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility class for decrypting encrypted model files.
 *
 * The decryption key is derived at runtime from scattered fragments
 * across Kotlin and native (C++) layers. No readable key exists in the binary.
 */
object ModelDecryptionUtil {

    private const val TAG = "ModelDecryptionUtil"

    /**
     * Derives key and IV from password and salt using MD5 (OpenSSL EVP_BytesToKey compatible)
     */
    private fun deriveKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        keyLength: Int,
        ivLength: Int
    ): ByteArray {
        val totalLength = keyLength + ivLength
        val derived = mutableListOf<Byte>()
        var previous = byteArrayOf()

        while (derived.size < totalLength) {
            val current = previous + password + salt
            val md = MessageDigest.getInstance("MD5")
            previous = md.digest(current)
            derived.addAll(previous.toList())
        }

        return derived.take(totalLength).toByteArray()
    }

    /**
     * Retrieves the decryption password from the native key provider,
     * uses it for decryption, then zeros it out.
     */
    private inline fun <T> withPassword(block: (ByteArray) -> T): T? {
        val password = NativeKeyProvider.getKey()
        if (password == null) {
            Log.e(TAG, "Failed to retrieve decryption key")
            return null
        }
        return try {
            block(password)
        } finally {
            // Zero out the password immediately after use
            Arrays.fill(password, 0.toByte())
        }
    }

    fun decryptModelFile(file: File): ByteBuffer? {
        return try {
            if (!file.exists()) {
                Log.e(TAG, "File not found: ${file.absolutePath}")
                return null
            }

            Log.d(TAG, "Decrypting model file: ${file.absolutePath}")

            val encryptedString = file.readText()
            val encryptedBytes = Base64.decode(encryptedString, Base64.DEFAULT)

            decryptBytes(encryptedBytes, file.name)

        } catch (e: Exception) {
            Log.e(TAG, "Error during decryption: ${file.name}", e)
            null
        }
    }

    fun decryptModelFile(filePath: String): ByteBuffer? {
        return decryptModelFile(File(filePath))
    }

    fun decryptModelBytes(encryptedBytes: ByteArray, fileName: String = "unknown"): ByteBuffer? {
        return try {
            decryptBytes(encryptedBytes, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Error during decryption of bytes: $fileName", e)
            null
        }
    }

    /**
     * Core decryption logic — shared by all entry points.
     */
    private fun decryptBytes(encryptedBytes: ByteArray, fileName: String): ByteBuffer? {
        val saltPrefix = "Salted__"
        val saltPrefixBytes = saltPrefix.toByteArray(Charsets.UTF_8)

        // Verify salt prefix
        val prefix = encryptedBytes.sliceArray(0 until saltPrefixBytes.size)
        if (!prefix.contentEquals(saltPrefixBytes)) {
            throw Exception("Salted prefix not found in the encrypted data")
        }

        // Extract salt (8 bytes after prefix)
        val salt = encryptedBytes.sliceArray(
            saltPrefixBytes.size until saltPrefixBytes.size + 8
        )

        // Extract ciphertext
        val cipherTextBytes = encryptedBytes.sliceArray(
            saltPrefixBytes.size + 8 until encryptedBytes.size
        )

        // Get password from native key provider, decrypt, zero password
        return withPassword { password ->
            val keyAndIv = deriveKeyAndIv(password, salt, 32, 16)
            val key = keyAndIv.sliceArray(0 until 32)
            val iv = keyAndIv.sliceArray(32 until 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val decryptedData = cipher.doFinal(cipherTextBytes)

            // Zero derived key material
            Arrays.fill(key, 0.toByte())
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(keyAndIv, 0.toByte())

            // Decode from base64
            val decryptedString = String(decryptedData, Charsets.UTF_8)
            val fileData = Base64.decode(decryptedString, Base64.DEFAULT)

            // Zero decrypted intermediate
            Arrays.fill(decryptedData, 0.toByte())

            Log.d(TAG, "Successfully decrypted: $fileName (${fileData.size} bytes)")

            ByteBuffer.allocateDirect(fileData.size).apply {
                order(ByteOrder.nativeOrder())
                put(fileData)
                rewind()
            }
        }
    }
}