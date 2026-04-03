package com.infusory.lib3drenderer.repositories.data

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.infusory.lib3drenderer.repositories.data.helper.ObjectMapper
import com.infusory.lib3drenderer.utils.retrofit.BaseApiClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit

class TutarVerseTsiClient private constructor() { // Private constructor for Singleton

    // Initialize Retrofit client and API interface in the init block
    private val sTutarVerseTsiInterface: TutarVerseTsiInterface

    init {
        val client: Retrofit? = BaseApiClient.getClient(TUTAR_BASE_URL)
        sTutarVerseTsiInterface = client!!.create(TutarVerseTsiInterface::class.java)
    }

    fun getApiInterface(): TutarVerseTsiInterface {
        return sTutarVerseTsiInterface
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
                    Log.e(TAG, "onResponse: success ${response.body()}")
                    val mappedValue = mappingFunction.doMapping(valueType, data)
                    mutableLiveData.postValue(mappedValue)
                } else {
                    Log.e(TutarVerseTsiClient.TAG, "onResponse: failed ${response.message()}")
                    mutableLiveData.postValue(mappingFunction.doMapping(valueType, null))
                }
            }

            override fun onFailure(call: Call<R>, t: Throwable) {
                mutableLiveData.postValue(mappingFunction.doMapping(valueType, null))
                Log.e(TutarVerseTsiClient.TAG, "onFailure: ${t.message}")
            }
        })
    }

    // Companion object holds static-like members and functions
    companion object {
        private const val TAG = "TutarVerseTsiClient" // Const val for compile-time constants
        private const val TUTAR_BASE_URL = " https://tsi.tutarverse.com"
        const val API_KEY =
            "4np8szqAKyqxqGDlaByzUCYLOom0KMqDort9FLm8dFh7sNIKehXNpcBENsLHIr827lrm2NDh5kNQBDyunjMy3wqq"
        const val VERSION = "1.3.0"

        // Lazy initialization to ensure the instance is created only when first accessed
        @Volatile // Ensures visibility across threads
        private var INSTANCE: TutarVerseTsiClient? = null

        fun getInstance(): TutarVerseTsiClient {
            // Standard double-check locking for thread-safe singleton
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TutarVerseTsiClient().also { INSTANCE = it }
            }
        }
    }
}