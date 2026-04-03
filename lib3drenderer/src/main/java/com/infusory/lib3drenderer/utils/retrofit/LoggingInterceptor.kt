package com.infusory.lib3drenderer.utils.retrofit

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class LoggingInterceptor : Interceptor {

    companion object {
        private const val TAG = "LoggingInterceptor"
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url().toString()
        val method = request.method()
        Log.e(TAG, "intercept: URL $url")
        Log.e(TAG, "method: Method $method")
        if (method != "GET" && request.body() != null) {
            val buffer = okio.Buffer()
            request.body()?.writeTo(buffer)
            Log.e(TAG, "Body: ${buffer.readUtf8()}")
        }
        // Print all headers
        for (name in request.headers().names()) {
            val value = request.header(name)
            Log.e("LoggingInterceptor", "➡️ Header: $name = $value")
        }
        return chain.proceed(request)
    }
}