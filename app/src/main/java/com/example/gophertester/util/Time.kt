package com.example.gophertester.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Time {
    private val ISO = DateTimeFormatter.ISO_INSTANT
    private val FILE_SAFE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)

    fun isoNow(): String = ISO.format(Instant.now())

    fun fileSafeNow(): String = FILE_SAFE.format(Instant.now())

    fun msBetween(earlierIso: String, laterIso: String): Long {
        val a = Instant.parse(earlierIso)
        val b = Instant.parse(laterIso)
        return b.toEpochMilli() - a.toEpochMilli()
    }
}
