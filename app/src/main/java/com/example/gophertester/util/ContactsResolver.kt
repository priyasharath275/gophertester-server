package com.example.gophertester.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.app.ActivityCompat
import java.util.concurrent.ConcurrentHashMap

object ContactsResolver {
    private val cache = ConcurrentHashMap<String, String>() // digitsOnly -> display name
    @Volatile private var loaded = false

    private fun hasPerm(ctx: Context) =
        ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private fun loadAll(ctx: Context) {
        if (loaded || !hasPerm(ctx)) return
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = c.getString(iName)?.trim().orEmpty()
                    val num  = c.getString(iNum).orEmpty()
                    val key  = canonical(num)
                    if (key.isNotEmpty() && name.isNotEmpty()) cache.putIfAbsent(key, name)
                }
            }
        } catch (_: Throwable) {
            // swallow; just fall back to showing numbers
        } finally {
            loaded = true
        }
    }

    /** Keep digits only for matching (+ vs no + treated the same). */
    fun canonical(raw: String?): String = raw?.filter { it.isDigit() } ?: ""

    /** Ensure output like +<digits>, tolerate 00 prefix or no prefix. */
    fun ensurePlus(raw: String): String {
        val t = raw.trim()
        val digits = canonical(t)
        if (digits.isEmpty()) return ""
        return when {
            t.startsWith("+") -> "+$digits"
            t.startsWith("00") -> "+${digits.drop(2)}"
            else -> "+$digits"
        }
    }

    fun nameFor(ctx: Context, number: String): String? {
        if (!loaded) loadAll(ctx)
        return cache[canonical(number)]
    }

    /** For Admin UI chips: show contact name if we have it, append (me) when it's the local phone. */
    fun displayLabel(ctx: Context, number: String, selfNumber: String?): String {
        val base = nameFor(ctx, number) ?: number
        val isMe = canonical(number) == canonical(selfNumber ?: "")
        return if (isMe) "$base(me)" else base
    }
}
