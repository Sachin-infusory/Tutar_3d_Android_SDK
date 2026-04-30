package com.infusory.tutar3d.internal

import android.util.Log

/**
 * Internal logging facade for the lib3drenderer SDK.
 *
 * Why this exists:
 *  - SDKs should not pollute consumer logcat in steady state. By default the
 *    debug/info/verbose channels are silent; consumers opt in via
 *    [com.infusory.tutar3d.Tutar.setDebugLogging] when they need to
 *    diagnose an integration issue.
 *  - Errors and warnings remain always-on — those signal real problems the
 *    consumer should be able to see without flipping a switch — but the
 *    messages they carry must not leak implementation detail (encryption
 *    scheme, internal file paths, decrypted byte counts, etc.).
 *
 * All SDK code should call this object instead of `android.util.Log` directly.
 */
internal object SdkLog {

    @Volatile
    var debugEnabled: Boolean = false

    fun d(tag: String, message: String) {
        if (debugEnabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (debugEnabled) Log.i(tag, message)
    }

    fun v(tag: String, message: String) {
        if (debugEnabled) Log.v(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
