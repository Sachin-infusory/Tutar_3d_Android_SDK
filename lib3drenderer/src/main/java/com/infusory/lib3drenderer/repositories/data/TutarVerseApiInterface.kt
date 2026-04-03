package com.infusory.lib3drenderer.repositories.data

import com.infusory.lib3drenderer.repositories.data.response.ResponseValidate
import retrofit2.Call
import retrofit2.http.Header
import retrofit2.http.POST

interface TutarVerseApiInterface {

    @POST("/offline-bundles/_/validate")
    fun validate(
        @Header("x-tutar-device-id") deviceId: String,
        @Header("x-tutar-version") version: String,
        @Header("x-tutar-api-key") apiKey: String,
    ): Call<ResponseValidate>
}