package com.senkiro.translateapp.utils

import android.util.Log

object Logger {
    private const val TAG = "TranslateApp"

    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun e(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)
}
