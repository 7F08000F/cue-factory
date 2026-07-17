package com.cuefactory.app.util

import android.util.Log

/** Structured log tags for logcat filtering: `adb logcat -s CueFactory`. */
object AppLog {
    const val TAG = "CueFactory"

    fun d(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.d(TAG, msg, tr) else Log.d(TAG, msg)
    }

    fun i(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.i(TAG, msg, tr) else Log.i(TAG, msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
    }

    fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
    }
}
