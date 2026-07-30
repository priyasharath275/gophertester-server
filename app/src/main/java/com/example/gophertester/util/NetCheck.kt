package com.example.gophertester.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetCheck {
    /** Fast, non-blocking: checks current default network capabilities. */
    fun isValidated(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // VALIDATED is present since API 21; if absent on some OEMs, treat INET as enough
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && validated
    }
}
