package com.infusory.lib3drenderer.repositories.data

import com.infusory.lib3drenderer.utils.retrofit.BaseApiClient
import retrofit2.Retrofit

/**
 * Retrofit client for Oracle Cloud Object Storage downloads.
 */
class OracleApiClient private constructor() {

    private val apiInterface: OracleApiInterface

    init {
        val client: Retrofit = BaseApiClient.getClient(ORACLE_BASE_URL)!!
        apiInterface = client.create(OracleApiInterface::class.java)
    }

    fun getApiInterface(): OracleApiInterface = apiInterface

    companion object {
        private const val ORACLE_BASE_URL =
            "https://objectstorage.ap-mumbai-1.oraclecloud.com/n/bm3bhqklizp1/b/sdk-test/o/"

        @Volatile
        private var INSTANCE: OracleApiClient? = null

        fun getInstance(): OracleApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OracleApiClient().also { INSTANCE = it }
            }
        }
    }
}
