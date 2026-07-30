package com.example.gophertester.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot

/**
 * Lightweight, always-on tracker that prefers network/coarse when GPS isn't available.
 * Keeps the last good fix so receiver mode can always respond.
 */
class LocationTracker(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)

    // Latest update we received (may be coarse). UI/service reads this.
    val latest = MutableStateFlow<Location?>(null)

    // Sticky cache of the last non-null location we saw.
    private var lastGood: Location? = null

    private var callback: LocationCallback? = null

    private var lastForSpeed: Location? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (callback != null) return // already running

        // Balanced power works indoors via Wi-Fi/cell. GPS will be used if available.
        val req = LocationRequest.Builder(1000L) // 1s desired
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2000L)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val enriched = enrichWithSpeed(loc)
                latest.value = enriched
                lastGood = enriched
            }
        }
        callback = cb
        fused.requestLocationUpdates(req, cb, Looper.getMainLooper())

        // Seed quickly from cached last known if any.
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                latest.value = loc
                lastGood = loc
            }
        }
    }

    private fun enrichWithSpeed(locIn: Location): Location {
        val loc = Location(locIn) // copy; Location is mutable
        val prev = lastForSpeed
        val nowNs = if (Build.VERSION.SDK_INT >= 17) SystemClock.elapsedRealtimeNanos() else 0L
        val prevNs = prev?.elapsedRealtimeNanos ?: 0L
        val dtSec =
            if (nowNs > 0L && prevNs > 0L) (nowNs - prevNs) / 1_000_000_000.0
            else ((loc.time - (prev?.time ?: loc.time)).coerceAtLeast(1L) / 1000.0)

        if (prev != null && dtSec > 0.4) {
            if (!loc.hasSpeed()) {
                val d = loc.distanceTo(prev)          // meters
                var v = (d / dtSec).toFloat()         // m/s
                if (v < 0.3f) v = 0f                  // deadband to kill jitter when still
                loc.speed = v
            }
            if (Build.VERSION.SDK_INT >= 26 && !loc.hasSpeedAccuracy()) {
                // Rough 1σ speed-accuracy from position accuracies
                val combErrM = hypot(loc.accuracy.toDouble(), prev.accuracy.toDouble()).toFloat()
                val vAcc = (combErrM / dtSec).toFloat()   // m/s
                // Location has a public setter from API 26
                loc.speedAccuracyMetersPerSecond = vAcc
            }
        }
        lastForSpeed = loc
        return loc
    }

    fun stop() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    /** Best immediately-available fix (latest or sticky last known). */
    fun best(): Location? = latest.value ?: lastGood
}
