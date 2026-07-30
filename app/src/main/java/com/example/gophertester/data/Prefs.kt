package com.example.gophertester.data

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object Prefs {
    val USER_PHONE = stringPreferencesKey("user_phone")
    val LOG_URI    = stringPreferencesKey("active_log_uri")
    val LOG_FILE   = stringPreferencesKey("active_log_file")

    // Added
    val SEND_INTERVAL_MS = longPreferencesKey("send_interval_ms")
    val MSG_SIZE_KB      = longPreferencesKey("msg_size_kb") // message size stored in KB
}
