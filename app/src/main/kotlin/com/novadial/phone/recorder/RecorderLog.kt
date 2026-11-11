package com.novadial.phone.recorder

import android.util.Log

/**
 * Minimal logger for the recorder package, matching the call shape of ShizuCallRecorder's AppLogger
 * (used by the code adapted from it). Writes to logcat only; works in both the app process and the
 * Shizuku shell process. Never logs phone numbers.
 */
object RecorderLog {
    private const val TAG = "NovaDialRecorder"

    fun v(message: String, t: Throwable? = null) { Log.v(TAG, message, t) }
    fun d(message: String, t: Throwable? = null) { Log.d(TAG, message, t) }
    fun i(message: String, t: Throwable? = null) { Log.i(TAG, message, t) }
    fun w(message: String, t: Throwable? = null) { Log.w(TAG, message, t) }
    fun e(message: String, t: Throwable? = null) { Log.e(TAG, message, t) }
}
