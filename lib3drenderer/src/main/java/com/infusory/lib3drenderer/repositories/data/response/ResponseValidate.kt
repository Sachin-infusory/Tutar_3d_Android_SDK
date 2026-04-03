package com.infusory.lib3drenderer.repositories.data.response

import com.google.gson.annotations.SerializedName

data class ResponseValidate(
    @SerializedName("success")
    val success: Boolean
)