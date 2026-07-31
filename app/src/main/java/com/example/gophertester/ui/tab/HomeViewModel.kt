package com.umn.wezzon.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.umn.wezzon.data.BsmReporter
import com.umn.wezzon.data.BsmSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the plain map screen: the camera position/zoom, the
 * device's live GPS location + heading (used to place the car icon),
 * and the live uplink/downlink status panel — "sent*" fields reflect the
 * exact payload just transmitted (updated the instant it's sent, no
 * network wait), "received*" fields reflect what the server echoed back
 * for it (updated the instant that arrives). Showing both side by side
 * makes any gap between uplink and downlink immediately visible.
 */
data class HomeUiState(
    val latitude: Double = 44.9778,   // initial camera center (Minneapolis)
    val longitude: Double = -93.2650,
    val zoom: Float = 12f,
    val userLocation: LatLng? = null,
    val userBearing: Float = 0f,
    val userSpeedMps: Float = 0f,     // raw GPS speed, meters/second
    val carScale: Float = 1f,

    // ---- Uplink (exactly what was just sent) ----
    val sentMsgCnt: Int? = null,
    val sentUserId: String? = null,
    val sentLat: Double? = null,
    val sentLon: Double? = null,
    val sentHeadingDeg: Double? = null,
    val sentSpeedMps: Double? = null,

    // ---- Downlink (what the server echoed back) ----
    val receivedMsgCnt: Int? = null,
    val receivedUserId: String? = null,
    val receivedLat: Double? = null,
    val receivedLon: Double? = null,
    val receivedHeadingDeg: Double? = null,
    val receivedSpeedMps: Double? = null
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ---------- BSM (Basic Safety Message) uplink, fixed 10Hz cadence ----------
    private val bsmReporter = BsmReporter()

    // Latest known sample, read by the 10Hz loop on every tick regardless
    // of whether a new GPS fix has arrived since the last tick.
    @Volatile private var latestBsmSample: BsmSample? = null

    // Persisted per-device UUID; set once DataStore has loaded it (see
    // DeviceIdentity.getOrCreate, called from HomeScreen since it needs
    // a Context this plain ViewModel doesn't have).
    @Volatile private var deviceUserId: String? = null

    init {
        bsmReporter.start(
            scope = viewModelScope,
            sample = { latestBsmSample },
            userId = { deviceUserId }
        )

        // Uplink: reflect the exact payload the instant it's transmitted.
        viewModelScope.launch {
            bsmReporter.sentUpdates.collect { s ->
                _uiState.value = _uiState.value.copy(
                    sentMsgCnt = s.msgCnt,
                    sentUserId = s.userId,
                    sentLat = s.lat,
                    sentLon = s.lon,
                    sentHeadingDeg = s.headingDeg,
                    sentSpeedMps = s.speedMps
                )
            }
        }

        // Downlink: reflect the server's round-trip echo the instant it arrives.
        viewModelScope.launch {
            bsmReporter.receivedUpdates.collect { r ->
                _uiState.value = _uiState.value.copy(
                    receivedMsgCnt = r.msgCnt,
                    receivedUserId = r.userId,
                    receivedLat = r.lat,
                    receivedLon = r.lon,
                    receivedHeadingDeg = r.headingDeg,
                    receivedSpeedMps = r.speedMps
                )
            }
        }
    }

    /** Called once, from HomeScreen, after the persisted device UUID loads. */
    fun setDeviceUserId(id: String) {
        deviceUserId = id
    }

    /**
     * Called on every GPS fix with the raw location fields the BSM needs.
     * [gpsHeadingDeg] is the GPS course-over-ground if the fix has one;
     * when absent, falls back to the live compass bearing already tracked
     * in [uiState] (never a stale snapshot, since this reads the current
     * StateFlow value at call time).
     */
    fun reportBsmSample(
        lat: Double,
        lon: Double,
        speedMps: Float,
        gpsHeadingDeg: Float?,
        elevM: Double?,
        accuracyM: Float?
    ) {
        latestBsmSample = BsmSample(
            lat = lat,
            lon = lon,
            speedMps = speedMps,
            headingDeg = gpsHeadingDeg ?: _uiState.value.userBearing,
            elevM = elevM,
            accuracyM = accuracyM
        )
    }

    /**
     * Called on every GPS fix. Updates the marker position, speed and,
     * optionally, the camera zoom (e.g. on the first fix).
     */
    fun updateLocation(
        lat: Double,
        lng: Double,
        zoom: Float? = null,
        speedMps: Float? = null
    ) {
        val current = _uiState.value
        val newZoom = zoom ?: current.zoom
        _uiState.value = current.copy(
            latitude = lat,
            longitude = lng,
            zoom = newZoom,
            userLocation = LatLng(lat, lng),
            carScale = deriveCarScale(newZoom),
            userSpeedMps = speedMps ?: current.userSpeedMps
        )
    }

    /** Called from the compass sensor to rotate the car icon. */
    fun updateBearing(bearing: Float) {
        _uiState.value = _uiState.value.copy(userBearing = bearing)
    }

    /** Keep the car icon a sensible size as the user zooms in/out. */
    fun onCameraZoomChanged(zoom: Float) {
        val current = _uiState.value
        val newScale = deriveCarScale(zoom)
        if (zoom == current.zoom && newScale == current.carScale) return
        _uiState.value = current.copy(zoom = zoom, carScale = newScale)
    }

    private fun deriveCarScale(zoom: Float): Float =
        when {
            zoom >= 18f -> 0.8f
            zoom >= 16f -> 0.6f
            zoom >= 14f -> 0.4f
            else -> 0.3f
        }

    override fun onCleared() {
        super.onCleared()
        bsmReporter.stop()
    }
}