package com.novadial.phone.recorder

import android.content.Context

/**
 * Stores the Shizuku "auth key for intents" and whether the user enabled call recording.
 *
 * The key is entered by the user (Shizuku app -> "Control Shizuku using automation apps" -> "Show intents",
 * available in the thedjchi Shizuku fork). It authorizes NovaDial to start/stop the Shizuku server through
 * Shizuku's own START/STOP intents. It is never hardcoded and stays in app-private preferences.
 */
object ShizukuAuthKeyStore {
    private const val PREFS_NAME = "shizuku_recorder_prefs"
    private const val KEY_AUTH = "shizuku_auth_key"
    private const val KEY_ALLOW_RECORDING = "allow_record_calls"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAuthKey(context: Context): String = prefs(context).getString(KEY_AUTH, "") ?: ""

    fun setAuthKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AUTH, key).apply()
    }

    /** Set only after the user acknowledged the legal notice and the connection test succeeded */
    fun isRecordingAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_ALLOW_RECORDING, false)

    fun setRecordingAllowed(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_RECORDING, allowed).apply()
    }
}
