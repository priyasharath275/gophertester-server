package com.example.gophertester.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat

object PhoneNumber {
    /** Best-effort read of the line1 number. Returns null if unavailable. */
    fun tryRead(context: Context): String? {
        val hasPerm =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) return null

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val raw = runCatching { tm.line1Number }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null

        return normalizePlus(raw)
    }

    /**
     * Normalize to “+<digits>”.
     * - Keeps digits only.
     * - If it already starts with '+', preserves it.
     * - If it starts with '00', converts that to '+'.
     * - Otherwise prefixes '+'.
     */
    private fun normalizePlus(raw: String): String? {
        val trimmed = raw.trim()

        // Extract only digits for the payload
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return null

        return when {
            trimmed.startsWith("+") -> "+$digitsOnly"
            trimmed.startsWith("00") -> "+${digitsOnly.drop(2)}" // handle international '00' prefix
            else -> "+$digitsOnly"
        }
    }
}
