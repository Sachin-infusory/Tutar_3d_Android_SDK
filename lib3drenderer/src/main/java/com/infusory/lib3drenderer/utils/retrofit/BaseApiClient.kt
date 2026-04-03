package com.infusory.lib3drenderer.utils.retrofit

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap

object BaseApiClient {

    private val sRetrofitMap: MutableMap<String, Retrofit> = ConcurrentHashMap()

    @JvmStatic
    @Synchronized
    fun getClient(baseUrl: String): Retrofit? {
        // Kotlin's map access is null-safe
        var retrofit = sRetrofitMap[baseUrl]

        if (retrofit == null) {
            val client = OkHttpClient.Builder()
                .addInterceptor(LoggingInterceptor())
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            sRetrofitMap[baseUrl] = retrofit
        }
        return retrofit
    }
}