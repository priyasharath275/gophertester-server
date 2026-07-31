package com.umn.wezzon.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapsInitializer.Renderer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.umn.wezzon.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.umn.wezzon.data.DeviceIdentity
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.launch

/**
 * Plain map screen: shows the device's current GPS location as a
 * car icon. No parking lots, reservations, or charging overlays.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load (or create) the persisted per-device UUID once, then hand it to
    // the ViewModel so the 10Hz BSM loop can start including it.
    LaunchedEffect(Unit) {
        val id = DeviceIdentity.getOrCreate(context)
        viewModel.setDeviceUserId(id)
    }

    // --- Ensure Maps SDK is initialized before any BitmapDescriptorFactory calls ---
    val appContext = context.applicationContext
    remember(appContext) {
        MapsInitializer.initialize(appContext, Renderer.LATEST, null)
    }

    // Camera state based on ViewModel state
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(state.latitude, state.longitude),
            state.zoom
        )
    }

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    // --------- CONTINUOUS GPS TRACKING → drives the car marker on the map ----------
    var hasCenteredOnFirstFix by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val fineGranted = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            onDispose { }
        } else {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2_000L // update roughly every 2 seconds
            ).setMinUpdateIntervalMillis(1_000L).build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    viewModel.updateLocation(
                        lat = location.latitude,
                        lng = location.longitude,
                        speedMps = if (location.hasSpeed()) location.speed else null
                    )
                    viewModel.reportBsmSample(
                        lat = location.latitude,
                        lon = location.longitude,
                        speedMps = if (location.hasSpeed()) location.speed else 0f,
                        gpsHeadingDeg = if (location.hasBearing()) location.bearing else null,
                        elevM = if (location.hasAltitude()) location.altitude else null,
                        accuracyM = if (location.hasAccuracy()) location.accuracy else null
                    )

                    if (!hasCenteredOnFirstFix) {
                        hasCenteredOnFirstFix = true
                        val latLng = LatLng(location.latitude, location.longitude)
                        scope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                            )
                        }
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )

            onDispose {
                fusedLocationClient.removeLocationUpdates(callback)
            }
        }
    }

    // Local state for current map type (Normal vs Satellite)
    var mapType by remember { mutableStateOf(MapType.NORMAL) }

    // Keep zoom controls but hide the built-in my-location button
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = false
        )
    }

    // --------- DEVICE ORIENTATION (compass) → updates bearing in ViewModel ----------
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rotationVectorSensor = remember {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    DisposableEffect(rotationVectorSensor) {
        if (rotationVectorSensor == null) {
            onDispose { }
        } else {
            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    var azimuthDeg =
                        Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    if (azimuthDeg < 0f) azimuthDeg += 360f
                    viewModel.updateBearing(azimuthDeg)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(
                listener,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---------- MAP ----------
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                isMyLocationEnabled = false,
                mapType = mapType
            ),
            uiSettings = uiSettings
        ) {
            val zoom = cameraPositionState.position.zoom
            LaunchedEffect(zoom) {
                viewModel.onCameraZoomChanged(zoom)
            }

            val carIcon: BitmapDescriptor = remember(state.carScale) {
                bitmapDescriptorFromVector(context, R.drawable.car_icon, state.carScale)
            }

            // Car icon's on-screen pixel height at the current scale, used to
            // place the label just above it (not baked into the same bitmap,
            // since the car icon rotates with heading and the label shouldn't).
            val carHeightPx = remember(state.carScale) {
                val drawable = AppCompatResources.getDrawable(context, R.drawable.car_icon)
                max(1, ((drawable?.intrinsicHeight ?: 1) * state.carScale).toInt())
            }
            val labelGapPx = with(LocalDensity.current) { 6.dp.toPx() }

            state.userLocation?.let { userLatLng ->
                val carMarkerState = rememberUpdatedMarkerState(userLatLng)
                val labelMarkerState = rememberUpdatedMarkerState(userLatLng)

                Marker(
                    state = carMarkerState,
                    icon = carIcon,
                    anchor = Offset(0.5f, 0.5f),
                    rotation = state.userBearing,
                    flat = true,
                    title = "You"
                )

                // ---------- LAT/LNG + SPEED/HEADING LABEL, floating above the car ----------
                val labelLines = remember(userLatLng, state.userSpeedMps, state.userBearing) {
                    val speedMph = state.userSpeedMps * MPS_TO_MPH
                    listOf(
                        String.format(Locale.US, "%.5f, %.5f", userLatLng.latitude, userLatLng.longitude),
                        String.format(
                            Locale.US,
                            "%.1f mph  •  %s (%.0f°)",
                            speedMph,
                            bearingToCardinal(state.userBearing),
                            state.userBearing
                        )
                    )
                }
                val labelIcon = remember(labelLines) {
                    createLatLngLabelIcon(context, labelLines)
                }
                // Anchor math: places the label's bottom edge `labelGapPx` above
                // the car icon's top edge, regardless of zoom/car scale.
                val labelAnchorV = 1f + (carHeightPx / 2f + labelGapPx) / labelIcon.heightPx

                Marker(
                    state = labelMarkerState,
                    icon = labelIcon.descriptor,
                    anchor = Offset(0.5f, labelAnchorV),
                    flat = false, // billboard: stays upright even as the car rotates
                    zIndex = 1f
                )
            }
        }

        // ---------- LAYERS BUTTON ----------
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 11.dp, bottom = 150.dp)
                .size(40.dp)
                .shadow(6.dp, CircleShape, clip = true)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                )
                .clickable {
                    mapType = if (mapType == MapType.NORMAL) {
                        MapType.SATELLITE
                    } else {
                        MapType.NORMAL
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = "Change map type"
            )
        }

        // ---------- CUSTOM "MY LOCATION" BUTTON ----------
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 11.dp, bottom = 100.dp)
                .size(40.dp)
                .shadow(6.dp, CircleShape, clip = true)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape
                )
                .clickable {
                    val fineGranted = ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarseGranted = ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!fineGranted && !coarseGranted) return@clickable

                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        location ?: return@addOnSuccessListener
                        val latLng = LatLng(location.latitude, location.longitude)

                        scope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(latLng, 16f)
                            )
                            viewModel.updateLocation(
                                lat = location.latitude,
                                lng = location.longitude,
                                zoom = 16f,
                                speedMps = if (location.hasSpeed()) location.speed else null
                            )
                            viewModel.updateBearing(location.bearing)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = "My location"
            )
        }

        // ---------- BOTTOM STATUS PANEL: uplink (sent) + downlink (echoed), side by side ----------
        BsmStatusPanel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            sentMsgCnt = state.sentMsgCnt,
            sentUserId = state.sentUserId,
            sentLat = state.sentLat,
            sentLon = state.sentLon,
            sentHeadingDeg = state.sentHeadingDeg,
            sentSpeedMps = state.sentSpeedMps,
            receivedMsgCnt = state.receivedMsgCnt,
            receivedUserId = state.receivedUserId,
            receivedLat = state.receivedLat,
            receivedLon = state.receivedLon,
            receivedHeadingDeg = state.receivedHeadingDeg,
            receivedSpeedMps = state.receivedSpeedMps
        )
    }
}

/**
 * Always-visible panel with two rows sharing one header: "Uplink" shows
 * the exact payload the instant it's transmitted (no network wait —
 * updates in lockstep with the 10Hz send loop), "Downlink" shows the
 * server's round-trip echo the instant it arrives. Showing both together
 * makes any lag between sending and confirmation immediately visible,
 * rather than only ever showing the (already stale-by-one) echoed value.
 */
@Composable
private fun BsmStatusPanel(
    modifier: Modifier = Modifier,
    sentMsgCnt: Int?,
    sentUserId: String?,
    sentLat: Double?,
    sentLon: Double?,
    sentHeadingDeg: Double?,
    sentSpeedMps: Double?,
    receivedMsgCnt: Int?,
    receivedUserId: String?,
    receivedLat: Double?,
    receivedLon: Double?,
    receivedHeadingDeg: Double?,
    receivedSpeedMps: Double?
) {
    val labelStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Monospace
    )
    val rowTagStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.55f),
        textAlign = TextAlign.Start,
        fontFamily = FontFamily.Monospace
    )
    val valueStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = Color.White,
        textAlign = TextAlign.Center,
        fontFamily = FontFamily.Monospace
    )

    fun fmtSeq(v: Int?) = v?.toString() ?: "--"
    fun fmtId(id: String?) = id?.take(8) ?: "--"
    fun fmtLatLon(v: Double?) = v?.let { String.format(Locale.US, "%.5f", it) } ?: "--"
    fun fmtHeading(v: Double?) = v?.let { String.format(Locale.US, "%.1f°", it) } ?: "--"
    fun fmtSpeed(v: Double?) = v?.let { String.format(Locale.US, "%.2f m/s", it) } ?: "--"

    Box(
        modifier = modifier
            .background(
                color = Color(0xCC1B1B1B),
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            // Shared header row
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("", style = rowTagStyle, modifier = Modifier.weight(0.55f))
                Text("Seq", style = labelStyle, modifier = Modifier.weight(0.6f))
                Text("User ID", style = labelStyle, modifier = Modifier.weight(1.2f))
                Text("Lat", style = labelStyle, modifier = Modifier.weight(1f))
                Text("Lon", style = labelStyle, modifier = Modifier.weight(1f))
                Text("Heading", style = labelStyle, modifier = Modifier.weight(1f))
                Text("Speed", style = labelStyle, modifier = Modifier.weight(1f))
            }

            // Uplink row — exactly what was just sent, no network wait.
            // "Seq" (msgCnt) always increments each tick, so it's the
            // clearest visible proof this is really refreshing at 10Hz,
            // even when lat/lon/heading/speed happen to stay identical
            // (e.g. device stationary).
            Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("UP", style = rowTagStyle, modifier = Modifier.weight(0.55f))
                Text(fmtSeq(sentMsgCnt), style = valueStyle, modifier = Modifier.weight(0.6f))
                Text(fmtId(sentUserId), style = valueStyle, modifier = Modifier.weight(1.2f))
                Text(fmtLatLon(sentLat), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtLatLon(sentLon), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtHeading(sentHeadingDeg), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtSpeed(sentSpeedMps), style = valueStyle, modifier = Modifier.weight(1f))
            }

            // Downlink row — what the server echoed back for it.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 1.dp)) {
                Text("DN", style = rowTagStyle, modifier = Modifier.weight(0.55f))
                Text(fmtSeq(receivedMsgCnt), style = valueStyle, modifier = Modifier.weight(0.6f))
                Text(fmtId(receivedUserId), style = valueStyle, modifier = Modifier.weight(1.2f))
                Text(fmtLatLon(receivedLat), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtLatLon(receivedLon), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtHeading(receivedHeadingDeg), style = valueStyle, modifier = Modifier.weight(1f))
                Text(fmtSpeed(receivedSpeedMps), style = valueStyle, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Result of drawing the label: the marker icon plus its pixel
 * height, so the caller can anchor it precisely relative to the car icon.
 */
private data class TextIcon(
    val descriptor: BitmapDescriptor,
    val heightPx: Int
)

private const val MPS_TO_MPH = 2.23694f

/** Nearest 8-point compass direction for a heading in degrees (0 = North). */
private fun bearingToCardinal(bearingDeg: Float): String {
    val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((bearingDeg % 360f) + 360f) % 360f
    val index = ((normalized / 45f) + 0.5f).toInt() % 8
    return directions[index]
}

/**
 * Draws a small rounded, multi-line label (lat/lng on one line, speed +
 * heading on another) as a bitmap, used as a second marker floating
 * above the car icon.
 */
private fun createLatLngLabelIcon(context: Context, lines: List<String>): TextIcon {
    val density = context.resources.displayMetrics.density

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    val paddingH = 12f * density
    val paddingV = 6f * density
    val lineSpacing = 2f * density

    val lineBounds = lines.map { line ->
        Rect().also { textPaint.getTextBounds(line, 0, line.length, it) }
    }
    val maxLineWidth = lineBounds.maxOf { it.width() }
    val lineHeight = lineBounds.maxOf { it.height() }.toFloat()
    val totalTextHeight = lineHeight * lines.size + lineSpacing * (lines.size - 1)

    val width = max(1, (maxLineWidth + paddingH * 2).toInt())
    val height = max(1, (totalTextHeight + paddingV * 2).toInt())

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CC1B1B1B".toColorInt()
    }
    val cornerRadius = 8f * density
    val backgroundRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)

    var slotTop = paddingV
    lines.forEachIndexed { index, line ->
        val bounds = lineBounds[index]
        val slotCenterY = slotTop + lineHeight / 2f
        val baseline = slotCenterY - bounds.exactCenterY()
        canvas.drawText(line, width / 2f, baseline, textPaint)
        slotTop += lineHeight + lineSpacing
    }

    return TextIcon(
        descriptor = BitmapDescriptorFactory.fromBitmap(bitmap),
        heightPx = height
    )
}

/**
 * Convert a vector drawable into a BitmapDescriptor for Google Maps.
 */
private fun bitmapDescriptorFromVector(
    context: Context,
    resId: Int,
    scale: Float = 1f
): BitmapDescriptor {
    val drawable = AppCompatResources.getDrawable(context, resId)
        ?: return BitmapDescriptorFactory.defaultMarker()

    val baseWidth = max(1, (drawable.intrinsicWidth * scale).toInt())
    val baseHeight = max(1, (drawable.intrinsicHeight * scale).toInt())

    val bitmap = createBitmap(baseWidth, baseHeight)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}