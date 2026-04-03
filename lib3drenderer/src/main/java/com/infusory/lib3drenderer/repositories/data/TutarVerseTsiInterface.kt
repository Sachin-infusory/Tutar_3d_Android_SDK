package com.infusory.lib3drenderer.repositories.data

import com.infusory.lib3drenderer.repositories.data.request.VerifyRequest
import com.infusory.lib3drenderer.repositories.data.response.ResponseVerify
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface TutarVerseTsiInterface {

    @POST("/tsi/verify")
    fun verify(
        @Body request: VerifyRequest
    ): Call<ResponseVerify>
}