package com.infusory.lib3drenderer.containerview.data

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.infusory.lib3drenderer.containerview.Container3D
import com.infusory.lib3drenderer.repositories.AuthRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream

class AuthViewModel(application: Application) : AndroidViewModel(application) {


    companion object {
        private const val TAG = "AuthViewModel"
    }

    fun verifyAuthentication(
        deviceId: String,
    ): MutableLiveData<Boolean> {
        val result = MutableLiveData<Boolean>()
        val response = AuthRepo.getInstance().verifyAuthentication(deviceId)

        val verificationObserver = object : Observer<Boolean?> {
            override fun onChanged(isVerified: Boolean?) {
                if (isVerified == true) {
                    Log.e(TAG, "Verified")
                    val validationResponse = AuthRepo.getInstance().validateAuthentication(deviceId)

                    val validationObserver = object : Observer<Boolean?> {
                        override fun onChanged(isValidated: Boolean?) {
                            val finalResult = isValidated ?: false
                            Log.e(TAG, "validated : $finalResult")
                            result.postValue(true)
                        }
                    }
                    validationResponse.observeForever(validationObserver)

                }
                else if (isVerified == false || isVerified == null) {
                    Log.e(TAG, "Verification failed (value was $isVerified).")
                    result.postValue(false)
                }
            }
        }

        response.observeForever(verificationObserver)
        return result
    }

    fun downloadFile(context: Context, fileName: String): MutableLiveData<Boolean> {
        val result = MutableLiveData<Boolean>()
        val encryptedModelsDir = File(context.filesDir, Container3D.FOLDER)
        val modelFile = File(encryptedModelsDir, fileName)

        if (modelFile.exists() && modelFile.canRead()) {
            Log.d(TAG, "Found encrypted model: ${modelFile.absolutePath}")
            result.postValue(true)
            return result
        } else {
            Log.d(TAG, "Downloading: $fileName")
            val responseBodyLiveData = AuthRepo.getInstance().download(fileName)
            responseBodyLiveData.observeForever { body ->
                if (body != null) {
                    saveFileToStorage(fileName, body, result)
                } else {
                    Log.e(TAG, "Downloading: $fileName Error")
                    result.postValue(false)
                }
            }
        }
        return result
    }

    fun forceDownloadFile(context: Context, fileName: String): MutableLiveData<Boolean> {
        val result = MutableLiveData<Boolean>()
        val responseBodyLiveData = AuthRepo.getInstance().download(fileName)
        Log.d(TAG, "Downloading: $fileName")
        responseBodyLiveData.observeForever { body ->
            if (body != null) {
                saveFileToStorage(fileName, body, result)
            } else {
                Log.e(TAG, "Downloading: $fileName Error")
                result.postValue(false)
            }
        }
        return result
    }

    private fun saveFileToStorage(
        fileName: String,
        responseBody: ResponseBody,
        resultLiveData: MutableLiveData<Boolean>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val modelsDir = File(context.filesDir, Container3D.FOLDER)
            if (!modelsDir.exists()) {
                val created = modelsDir.mkdirs()
                Log.d(TAG, "Models directory created: $created")
            }
            val outputFile = File(modelsDir, fileName)
            try {
                responseBody.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        val fileSize = responseBody.contentLength()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                        }
                        output.flush()
                    }
                    Log.d(TAG, "File saved successfully:")
                    resultLiveData.postValue(true)
                }
            } catch (e: Exception) {
                Log.e("FileDownload", "Error saving file: ${e.message}")
                resultLiveData.postValue(false)
            }
        }
    }
}