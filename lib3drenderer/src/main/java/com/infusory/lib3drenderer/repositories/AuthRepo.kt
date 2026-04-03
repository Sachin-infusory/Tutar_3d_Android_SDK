package com.infusory.lib3drenderer.repositories

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.infusory.lib3drenderer.repositories.data.OracleApiClient
import com.infusory.lib3drenderer.repositories.data.TutarVerseApiClient
import com.infusory.lib3drenderer.repositories.data.TutarVerseTsiClient
import com.infusory.lib3drenderer.repositories.data.helper.ObjectMapper
import com.infusory.lib3drenderer.repositories.data.request.VerifyRequest
import com.infusory.lib3drenderer.repositories.data.response.ResponseValidate
import com.infusory.lib3drenderer.repositories.data.response.ResponseVerify
import okhttp3.ResponseBody
import retrofit2.Call

class AuthRepo private constructor() {

    fun verifyAuthentication(deviceID: String, apiKey: String? = null): MutableLiveData<Boolean> {
        val keyToUse = apiKey ?: TutarVerseTsiClient.API_KEY
        // V is Boolean (the final result)
        val resultLiveData = MutableLiveData<Boolean>()
        val request = VerifyRequest(deviceId = deviceID, apiKey = keyToUse)
        // R is ResponseVerify (the raw API response)
        val callApi: Call<ResponseVerify> = TutarVerseTsiClient.getInstance()
            .getApiInterface()
            .verify(request)

        // 3. Define the ObjectMapper implementation
        val mapper = object : ObjectMapper<Boolean, ResponseVerify> {
            override fun doMapping(
                inputClass: Class<Boolean>,
                responseObj: ResponseVerify?
            ): Boolean? {
                if (responseObj == null) return false
                if(responseObj.status) {
                    return responseObj.data.success
                }else{
                    return false
                }
            }

            override fun doMappingOnList(
                inputClass: Class<Boolean>,
                responseObj: ResponseVerify?
            ): List<Boolean>? {
                return null
            }
        }


        // 4. Call the generic getData function
        TutarVerseTsiClient.getInstance().getData(
            call = callApi,
            mutableLiveData = resultLiveData,
            valueType = Boolean::class.java,
            mappingFunction = mapper
        )

        return resultLiveData
    }

    fun validateAuthentication(
        deviceID: String,
        apiKey: String? = null,
        version: String? = null
    ): MutableLiveData<Boolean> {
        val keyToUse = apiKey ?: TutarVerseApiClient.API_KEY
        val verToUse = version ?: TutarVerseApiClient.VERSION
        // V is Boolean (the final result)
        val resultLiveData = MutableLiveData<Boolean>()

        // R is ResponseVerify (the raw API response)
        val callApi: Call<ResponseValidate> = TutarVerseApiClient.getInstance()
            .getApiInterface()
            .validate(
                deviceId = deviceID,
                apiKey = keyToUse,
                version = verToUse
            )

        // 3. Define the ObjectMapper implementation
        val mapper = object : ObjectMapper<Boolean, ResponseValidate> {
            override fun doMapping(
                inputClass: Class<Boolean>,
                responseObj: ResponseValidate?
            ): Boolean? {
                if (responseObj == null) return false
                return responseObj.success
            }

            override fun doMappingOnList(
                inputClass: Class<Boolean>,
                responseObj: ResponseValidate?
            ): List<Boolean>? {
                return null
            }
        }


        // 4. Call the generic getData function
        TutarVerseApiClient.getInstance().getData(
            call = callApi,
            mutableLiveData = resultLiveData,
            valueType = Boolean::class.java,
            mappingFunction = mapper
        )

        return resultLiveData
    }

    fun download(fileName: String): MutableLiveData<ResponseBody> {

        // V is Boolean (the final result)
        val resultLiveData = MutableLiveData<ResponseBody>()

        // R is ResponseVerify (the raw API response)
        val callApi: Call<ResponseBody> = OracleApiClient.getInstance()
            .getApiInterface()
            .download(fileName)

        // 3. Define the ObjectMapper implementation
        val mapper = object : ObjectMapper<ResponseBody, ResponseBody> {
            override fun doMapping(
                inputClass: Class<ResponseBody>,
                responseObj: ResponseBody?
            ): ResponseBody? {
                return responseObj
            }

            override fun doMappingOnList(
                inputClass: Class<ResponseBody>,
                responseObj: ResponseBody?
            ): List<ResponseBody>? = null
        }


        // 4. Call the generic getData function
        OracleApiClient.getInstance().getData(
            call = callApi,
            mutableLiveData = resultLiveData,
            valueType = ResponseBody::class.java,
            mappingFunction = mapper
        )

        return resultLiveData
    }

    // Companion object holds static-like members
    companion object {
        private const val TAG = "AuthRepo"

        // Thread-safe lazy initialization for the Singleton instance
        val sUserInstance: AuthRepo by lazy { AuthRepo() }

        @JvmStatic
        fun getInstance(): AuthRepo {
            return sUserInstance
        }
    }
}