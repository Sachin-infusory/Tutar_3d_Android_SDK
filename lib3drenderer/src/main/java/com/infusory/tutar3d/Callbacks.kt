package com.infusory.tutar3d

/**
 * Callback interface for SDK initialization.
 * Use this in Java instead of Kotlin lambda.
 *
 * Java usage:
 * ```java
 * Lib3DRenderer.initialize(this, new InitCallback() {
 *     @Override
 *     public void onResult(boolean success) {
 *         if (success) {
 *             // SDK ready
 *         }
 *     }
 * });
 * ```
 */
fun interface InitCallback {
    fun onResult(success: Boolean)
}

/**
 * Callback interface for loading events.
 *
 * Java usage:
 * ```java
 * container.setOnLoadingStartedListener(new LoadingCallback() {
 *     @Override
 *     public void onLoading() {
 *         showProgress();
 *     }
 * });
 * ```
 */
fun interface LoadingCallback {
    fun onLoading()
}

/**
 * Callback interface for load completion.
 */
fun interface LoadedCallback {
    fun onLoaded()
}

/**
 * Callback interface for load errors.
 *
 * Java usage:
 * ```java
 * container.setOnLoadingFailedListener(new ErrorCallback() {
 *     @Override
 *     public void onError(String message) {
 *         showError(message);
 *     }
 * });
 * ```
 */
fun interface ErrorCallback {
    fun onError(message: String)
}

/**
 * Callback interface for container removal.
 */
fun interface RemoveCallback {
    fun onRemove()
}