package com.infusory.lib3drenderer.repositories.data

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.infusory.lib3drenderer.repositories.data.helper.ObjectMapper
import com.infusory.lib3drenderer.utils.retrofit.BaseApiClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit

class OracleApiClient private constructor() { // Private constructor for Singleton

    // Initialize Retrofit client and API interface in the init block
    private val sOracleApiInterface: OracleApiInterface

    init {
        val client: Retrofit? = BaseApiClient.getClient(ORACLE_BASE_URL)
        sOracleApiInterface = client!!.create(OracleApiInterface::class.java)
    }

    fun getApiInterface(): OracleApiInterface {
        return sOracleApiInterface
    }

    // Kotlin's generics are used here. 'V' is the desired type, 'R' is the Retrofit response type.
    fun <V, R> getData(
        call: Call<R>,
        mutableLiveData: MutableLiveData<V>,
        valueType: Class<V>,
        mappingFunction: ObjectMapper<V, R>
    ) {
        call.enqueue(object : Callback<R> { // Anonymous inner class simplified with 'object : ...'
            override fun onResponse(call: Call<R>, response: Response<R>) {
                if (response.isSuccessful) {
                    val data = response.body()
                    // Null safety is handled by the Elvis operator (?:) in case data is null
                    mutableLiveData.value = mappingFunction.doMapping(valueType, data)
                    Log.e(TAG, "onResponse: success ${response.body()}")
                } else {
                    mutableLiveData.value = null
                    Log.e(TAG, "onResponse: failed ${response.message()}")
                }
            }

            override fun onFailure(call: Call<R>, t: Throwable) {
                mutableLiveData.value = null // Set value to null on failure as well
                Log.e(TAG, "onFailure: ${t.message}")
            }
        })
    }

    // Companion object holds static-like members and functions
    companion object {
        private const val TAG = "OracleApiClient" // Const val for compile-time constants
        private const val ORACLE_BASE_URL =
            "https://objectstorage.ap-mumbai-1.oraclecloud.com/n/bm3bhqklizp1/b/sdk-test/o/"

        // Lazy initialization to ensure the instance is created only when first accessed
        @Volatile // Ensures visibility across threads
        private var INSTANCE: OracleApiClient? = null

        fun getInstance(): OracleApiClient {
            // Standard double-check locking for thread-safe singleton
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OracleApiClient().also { INSTANCE = it }
            }
        }
    }
}