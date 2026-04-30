package com.infusory.tutar3d.containerview

import android.util.Base64
import android.util.Log
import com.infusory.tutar3d.internal.SdkLog
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

    // Neutral tag — logcat shouldn't advertise that this code path is
    // doing decryption. Internal class name is unchanged, but the runtime
    // log output is anonymous.
    private const val TAG = "ModelLoader"

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
            SdkLog.e(TAG, "Model loader unavailable")
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
                SdkLog.e(TAG, "Model file missing: ${file.name}")
                return null
            }

            SdkLog.d(TAG, "Loading model: ${file.name}")

            // Decode base64 straight from the raw bytes. file.readText() would
            // additionally allocate a char[] copy of the whole file (UTF-16),
            // and Base64.decode(String) re-extracts the bytes — for a 30 MB
            // encrypted blob that's two redundant full-file copies.
            val encryptedBytes = Base64.decode(file.readBytes(), Base64.DEFAULT)

            decryptBytes(encryptedBytes, file.name)

        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to load model: ${file.name}", e)
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
            SdkLog.e(TAG, "Failed to load model bytes: $fileName", e)
            null
        }
    }

    /**
     * Decrypts model data when the input is the raw file content (still
     * base64-wrapped). Use this for asset streams that you've read into a
     * byte array; for `File` inputs, prefer `decryptModelFile` which streams
     * directly from disk.
     */
    fun decryptModelFromRawBytes(rawBytes: ByteArray, fileName: String = "unknown"): ByteBuffer? {
        return try {
            val decoded = Base64.decode(rawBytes, Base64.DEFAULT)
            decryptBytes(decoded, fileName)
        } catch (e: Exception) {
            SdkLog.e(TAG, "Failed to load model: $fileName", e)
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
            // Neutral message — exception is caught upstream and logged
            // verbatim, so it must not name the wrapper format either.
            throw Exception("Invalid model header")
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

            // Decode the inner base64 layer directly from bytes — skips a
            // String/char[] allocation the size of the entire decrypted blob.
            val fileData = Base64.decode(decryptedData, Base64.DEFAULT)

            // Zero decrypted intermediate
            Arrays.fill(decryptedData, 0.toByte())

            // No size or "decrypted" wording — leak nothing about the format
            // or content size to logcat.
            SdkLog.d(TAG, "Loaded model: $fileName")

            ByteBuffer.allocateDirect(fileData.size).apply {
                order(ByteOrder.nativeOrder())
                put(fileData)
                rewind()
            }
        }
    }
}