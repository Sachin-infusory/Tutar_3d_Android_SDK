package com.infusory.lib3drenderer.containerview

import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility class for decrypting encrypted model files
 */
object ModelDecryptionUtil {

    const val TAG = "ModelDecryptionUtil"
    private const val ENCRYPTION_KEY = "d03958346b286aa4af4e5b088f6cbc08"
    var KEY: String = TAG;
    /**
     * Derives key and IV from password and salt using MD5 (OpenSSL compatible)
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

    fun setDecryptKey(key : String) {
        KEY = ENCRYPTION_KEY;
    }

    fun decryptModelFile(file: File): ByteBuffer? {
        return try {
            if (!file.exists()) {
                Log.e(TAG, "File not found: ${file.absolutePath}")
                return null
            }

            Log.d(TAG, "Decrypting model file: ${file.absolutePath}")

            // Read encrypted file content
            val encryptedString = file.readText()
            val encryptedBytes = Base64.decode(encryptedString, Base64.DEFAULT)

            // Extract salt and ciphertext
            val saltPrefix = "Salted__"
            val saltPrefixBytes = saltPrefix.toByteArray(Charsets.UTF_8)

            // Verify salt prefix
            val prefix = encryptedBytes.sliceArray(0 until saltPrefixBytes.size)
            if (!prefix.contentEquals(saltPrefixBytes)) {
                throw Exception("Salted prefix not found in the encrypted file")
            }

            // Extract salt (8 bytes after prefix)
            val salt = encryptedBytes.sliceArray(
                saltPrefixBytes.size until saltPrefixBytes.size + 8
            )

            // Extract ciphertext (everything after prefix + salt)
            val cipherTextBytes = encryptedBytes.sliceArray(
                saltPrefixBytes.size + 8 until encryptedBytes.size
            )

            // Derive key and IV
            val password = KEY.toByteArray(Charsets.UTF_8)
            val keyAndIv = deriveKeyAndIv(password, salt, 32, 16)
            val key = keyAndIv.sliceArray(0 until 32)
            val iv = keyAndIv.sliceArray(32 until 48)

            // Decrypt using AES/CBC/PKCS5Padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val decryptedData = cipher.doFinal(cipherTextBytes)

            // Decode from base64 (the decrypted data is base64 encoded)
            val decryptedString = String(decryptedData, Charsets.UTF_8)
            val fileData = Base64.decode(decryptedString, Base64.DEFAULT)

            Log.d(TAG, "Successfully decrypted model: ${file.name} (${fileData.size} bytes)")

            // Convert to ByteBuffer
            ByteBuffer.allocateDirect(fileData.size).apply {
                order(ByteOrder.nativeOrder())
                put(fileData)
                rewind()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during decryption: ${file.name}", e)
            null
        }
    }

    /**
     * Decrypts an encrypted model file from a file path
     *
     * @param filePath The path to the encrypted file
     * @return Decrypted ByteBuffer ready for use, or null if decryption fails
     */
    fun decryptModelFile(filePath: String): ByteBuffer? {
        return decryptModelFile(File(filePath))
    }

    /**
     * Decrypts model file bytes directly (if you already have the file content)
     *
     * @param encryptedBytes The encrypted file content as ByteArray
     * @param fileName Optional filename for logging purposes
     * @return Decrypted ByteBuffer ready for use, or null if decryption fails
     */
    fun decryptModelBytes(encryptedBytes: ByteArray, fileName: String = "unknown"): ByteBuffer? {
        return try {
            Log.d(TAG, "Decrypting model bytes: $fileName")

            // Extract salt and ciphertext
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

            // Extract ciphertext (everything after prefix + salt)
            val cipherTextBytes = encryptedBytes.sliceArray(
                saltPrefixBytes.size + 8 until encryptedBytes.size
            )

            // Derive key and IV
            val password = ENCRYPTION_KEY.toByteArray(Charsets.UTF_8)
            val keyAndIv = deriveKeyAndIv(password, salt, 32, 16)
            val key = keyAndIv.sliceArray(0 until 32)
            val iv = keyAndIv.sliceArray(32 until 48)

            // Decrypt using AES/CBC/PKCS5Padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val decryptedData = cipher.doFinal(cipherTextBytes)

            // Decode from base64 (the decrypted data is base64 encoded)
            val decryptedString = String(decryptedData, Charsets.UTF_8)
            val fileData = Base64.decode(decryptedString, Base64.DEFAULT)

            Log.d(TAG, "Successfully decrypted model bytes: $fileName (${fileData.size} bytes)")

            // Convert to ByteBuffer
            ByteBuffer.allocateDirect(fileData.size).apply {
                order(ByteOrder.nativeOrder())
                put(fileData)
                rewind()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during decryption of bytes: $fileName", e)
            null
        }
    }
}