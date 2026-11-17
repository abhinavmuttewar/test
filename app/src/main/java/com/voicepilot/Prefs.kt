package com.voicepilot

import android.content.Context

/** App settings stored privately on the device. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("voicepilot", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var wakePhrase: String
        get() = sp.getString("wake_phrase", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString("wake_phrase", v.trim().lowercase().ifEmpty { DEFAULT_WAKE }).apply()

    /** "en-IN", "hi-IN" or "auto" (detects English/Hindi). */
    var language: String
        get() = sp.getString("language", "auto") ?: "auto"
        set(v) = sp.edit().putString("language", v).apply()

    companion object { const val DEFAULT_WAKE = "hey phone" }
}
