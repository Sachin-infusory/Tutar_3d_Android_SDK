package com.infusory.lib3drenderer.repositories.data.response

import com.google.gson.annotations.SerializedName

data class ResponseVerify(
    @SerializedName("status")
    val status: Boolean,
    @SerializedName("data")
    val data: VerifyData
)

data class VerifyData(
    @SerializedName("message")
    val message: String,
    @SerializedName("success")
    val success: Boolean
)
