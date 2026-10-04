package com.transvibe.app.data.logging

import android.util.Log
import com.transvibe.app.BuildConfig

object AppLogger {
    fun d(tag: String, message: String) { if (BuildConfig.DEBUG) Log.d(tag, message) }
    fun e(tag: String, message: String, error: Throwable? = null) { if (BuildConfig.DEBUG) Log.e(tag, message, error) }
}
