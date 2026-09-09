package com.micnubinub.syncthing.http

import android.graphics.Bitmap

sealed class ApiError {
    data class Http(val code: Int, val body: String?, val message: String?) : ApiError()
    data class Network(val cause: Throwable) : ApiError()
    data class Cancelled(val cause: Throwable? = null) : ApiError()
}

fun interface OnErrorListener {
    fun onError(error: ApiError?)
}

fun interface OnSuccessListener {
    fun onSuccess(result: String)
}

interface OnImageSuccessListener {
    fun onImageSuccess(result: Bitmap)
}
