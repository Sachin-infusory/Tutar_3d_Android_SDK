package com.infusory.lib3drenderer.containerview

import android.content.Context
import android.util.Log
import com.infusory.lib3drenderer.repositories.data.OracleApiClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Downloads model files from Oracle Cloud Storage.
 * No authentication required — uses direct public object storage URLs.
 *
 * Files are saved to: {filesDir}/models/{filename}
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MODELS_FOLDER = "models"

    /**
     * Callback for download result.
     */
    interface DownloadCallback {
        fun onSuccess(file: File)
        fun onFailure(error: String)
    }

    /**
     * Download a model file if not already cached locally.
     *
     * @param context Application or Activity context
     * @param filename The model filename to download
     * @param callback Result callback
     */
    @JvmStatic
    fun downloadIfNeeded(context: Context, filename: String, callback: DownloadCallback) {
        val localFile = getLocalFile(context, filename)

        // Already cached — no download needed
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Model already cached: ${localFile.absolutePath}")
            callback.onSuccess(localFile)
            return
        }

        Log.d(TAG, "Downloading model: $filename")
        downloadFromCloud(context, filename, localFile, callback)
    }

    /**
     * Get the local file path for a model (whether it exists or not).
     */
    @JvmStatic
    fun getLocalFile(context: Context, filename: String): File {
        val modelsDir = File(context.filesDir, MODELS_FOLDER)
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, filename)
    }

    /**
     * Check if a model file is cached locally.
     */
    @JvmStatic
    fun isModelCached(context: Context, filename: String): Boolean {
        return getLocalFile(context, filename).let { it.exists() && it.length() > 0 }
    }

    /**
     * Delete a cached model file.
     */
    @JvmStatic
    fun deleteCachedModel(context: Context, filename: String): Boolean {
        return getLocalFile(context, filename).delete()
    }

    /**
     * Clear all cached models.
     */
    @JvmStatic
    fun clearCache(context: Context) {
        val modelsDir = File(context.filesDir, MODELS_FOLDER)
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { it.delete() }
        }
    }

    // ==================== Private ====================

    private fun downloadFromCloud(
        context: Context,
        filename: String,
        localFile: File,
        callback: DownloadCallback
    ) {
        try {
            val apiInterface = OracleApiClient.getInstance().getApiInterface()

            // The OracleApiInterface should have a method like:
            // @GET("{filename}")
            // fun downloadFile(@Path("filename") filename: String): Call<ResponseBody>
            val call = apiInterface.download(filename)

            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful && response.body() != null) {
                        // Save to disk on background thread
                        Thread {
                            try {
                                val saved = saveToFile(response.body()!!, localFile)
                                if (saved) {
                                    Log.d(TAG, "Model downloaded: ${localFile.absolutePath} (${localFile.length()} bytes)")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        callback.onSuccess(localFile)
                                    }
                                } else {
                                    Log.e(TAG, "Failed to save downloaded file")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        callback.onFailure("Failed to save file to storage")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving file", e)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    callback.onFailure("Save error: ${e.message}")
                                }
                            }
                        }.start()
                    } else {
                        val errorMsg = "Download failed: HTTP ${response.code()} ${response.message()}"
                        Log.e(TAG, errorMsg)
                        callback.onFailure(errorMsg)
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    val errorMsg = "Download failed: ${t.message}"
                    Log.e(TAG, errorMsg, t)
                    callback.onFailure(errorMsg)
                }
            })
        } catch (e: Exception) {
            val errorMsg = "Download error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            callback.onFailure(errorMsg)
        }
    }

    private fun saveToFile(body: ResponseBody, file: File): Boolean {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        return try {
            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            // Write to temp file first, then rename (atomic save)
            val tempFile = File(file.parentFile, "${file.name}.tmp")

            inputStream = body.byteStream()
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            // Atomic rename
            if (tempFile.exists() && tempFile.length() > 0) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            } else {
                tempFile.delete()
                false
            }

            file.exists() && file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file", e)
            false
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }
}
