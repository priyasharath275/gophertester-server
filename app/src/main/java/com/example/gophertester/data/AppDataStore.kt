package com.example.gophertester.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * Define DataStore only once in the whole app. Import this extension everywhere.
 */
val Context.dataStore by preferencesDataStore(name = "gopher_prefs")
