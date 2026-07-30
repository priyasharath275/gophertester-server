package com.example.gophertester.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.example.gophertester.R
import com.example.gophertester.data.LocationTracker
import com.example.gophertester.data.Prefs
import com.example.gophertester.data.dataStore
import com.example.gophertester.model.MessageFactory
import com.example.gophertester.model.NetworkMessage
import com.example.gophertester.model.Payload
import com.example.gophertester.model.UserLocation
import com.example.gophertester.ui.tab.ConnectionRepository
import com.example.gophertester.util.FileManagement
import com.example.gophertester.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import com.example.gophertester.model.Target
import com.example.gophertester.data.QuicClient
import com.example.gophertester.util.NetCheck

class ConnectionService : Service() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "gopher_conn"
        private const val NOTIF_ID = 42

        const val ACTION_IDLE = "com.example.gophertester.action.IDLE"
        const val ACTION_CONNECT = "com.example.gophertester.action.CONNECT"
        const val ACTION_STOP = "com.example.gophertester.action.STOP"

        // NEW: Admin actions
        const val ACTION_ADMIN_PAIR = "com.example.gophertester.action.ADMIN_PAIR"
        const val ACTION_ADMIN_STOP = "com.example.gophertester.action.ADMIN_STOP"

        const val ACTION_ADMIN_LIST = "com.example.gophertester.action.ADMIN_LIST"
        const val ACTION_ADMIN_SESSIONS = "com.example.gophertester.action.ADMIN_SESSIONS"

        // Foreground "poke" to heartbeat or reconnect
        const val ACTION_POKE = "com.example.gophertester.action.POKE"
        private const val EXTRA_TARGET = "extra_target_phone"
        private const val EXTRA_ADMIN_PAIRS = "extra_admin_pairs" // ArrayList<String> items "A|B"

        fun poke(ctx: Context) {
            ctx.startService(Intent(ctx, ConnectionService::class.java).apply { action = ACTION_POKE })
        }

        fun startIdle(ctx: Context) {
            Log.d(TAG, "startIdle()")
            ctx.startService(Intent(ctx, ConnectionService::class.java).apply { action = ACTION_IDLE })
        }

        fun adminRefreshSessions(ctx: Context) {
            ctx.startService(Intent(ctx, ConnectionService::class.java).apply { action = ACTION_ADMIN_SESSIONS })
        }

        fun adminRefreshOnline(ctx: Context) {
            ctx.startService(Intent(ctx, ConnectionService::class.java).apply { action = ACTION_ADMIN_LIST })
        }

        fun connect(ctx: Context, targetPhone: String) {
            val cleaned = targetPhone.trim()
            Log.d(TAG, "connect(target=$cleaned)")
            val i = Intent(ctx, ConnectionService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_TARGET, cleaned)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            Log.d(TAG, "stop()")
            ctx.startService(Intent(ctx, ConnectionService::class.java).apply { action = ACTION_STOP })
        }

        // ───────────── Admin entrypoints (used by AdminViewModel) ─────────────
        fun adminPair(ctx: Context, pairs: List<Pair<String, String>>) {
            val flat = ArrayList(pairs.map { "${it.first}|${it.second}" })
            val i = Intent(ctx, ConnectionService::class.java).apply {
                action = ACTION_ADMIN_PAIR
                putStringArrayListExtra(EXTRA_ADMIN_PAIRS, flat)
            }
            ctx.startService(i)
        }

        fun adminStop(ctx: Context, pairs: List<Pair<String, String>>) {
            val flat = ArrayList(pairs.map { "${it.first}|${it.second}" })
            val i = Intent(ctx, ConnectionService::class.java).apply {
                action = ACTION_ADMIN_STOP
                putStringArrayListExtra(EXTRA_ADMIN_PAIRS, flat)
            }
            ctx.startService(i)
        }
    }

    // Sequence number for messages in current session (peer channel only)
    private var msgSeq: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private lateinit var locationTracker: LocationTracker

    private var heartbeatJob: Job? = null
    private var sendLoopJob: Job? = null
    private var timeSyncJob: Job? = null
    private var receiverTimeoutJob: Job? = null

    private var myPhone: String = "unknown"
    private var targetPhone: String = ""

    // Track peer in session
    private var currentPeer: String? = null

    // Time sync
    private var clockOffsetMs: Long = 0L
    private var lastRttMs: Long = 0L
    private var timeSyncSeq: Long = 1L
    private val pendingTimeSync: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    // Smoothing buffers
    private val offsetSamples = ArrayDeque<Long>()
    private val rttSamples = ArrayDeque<Long>()
    private val MAX_TS_SAMPLES = 15

    // App↔App RTT starts
    private val pendingAppRttStartNs = ConcurrentHashMap<String, Long>()

    // ─────────── Logging state ───────────
    private var activeLogUri: android.net.Uri? = null
    private var activeLogFileName: String? = null

    // New: rotation state
    private var sessionTimestamp: String? = null         // fixed for a session
    private var logPartIndex: Int = 0                    // P1, P2, ...
    private var rowsInCurrentFile: Int = 0               // data rows written to current CSV (excludes header)
    private val MAX_ROWS_PER_FILE = 5000                 // rotate every 5000 rows

    private lateinit var compass: com.example.gophertester.data.CompassTracker

    private var ensureJob: Job? = null
    private val logDir = "Documents/GopherTester"

    // CSV batching (write every 100 rows)
    private val csvBuffer = StringBuilder()
    private var csvBufferedRows = 0
    private val csvLock = Any()

    // CSV header shared by all parts
    private val csvHeader: String by lazy {
        "msg_no," +
                "timestamp," +
                "e_server_iso," +
                "a_rx_server_ms," +
                "env_A.send_ms,env_srv.C.rx_ms,env_srv.C.tx_ms,env_B.rx_ms,env_B.tx_ms,env_srv.D.rx_ms," +
                "delay_A_to_server_ms,delay_server_to_A_ms,delay_server_to_B_ms,delay_B_to_server_ms," +
                "A_lat,A_lon,A_alt,A_acc,A_speed,A_speed_acc,A_bearing,A_bearing_acc," +
                "B_lat,B_lon,B_alt,B_acc,B_speed,B_speed_acc,B_bearing,B_bearing_acc," +
                "app_to_app_rtt_ms,server_B_roundtrip_ms," +
                "b_clock_offset_ms,b_ping_rtt_ms," +
                "a_clock_offset_ms,a_ping_rtt_ms\n"
    }

    // Server-aligned "now" in ms using our time-sync offset (server_time - phone_time).
    private fun serverAlignedNowMs(): Long = System.currentTimeMillis() + clockOffsetMs

    private lateinit var ws: QuicClient

    override fun onCreate() {
        super.onCreate()
        ws = QuicClient(applicationContext)
        locationTracker = LocationTracker(applicationContext)
        locationTracker.start()
        compass = com.example.gophertester.data.CompassTracker(applicationContext)
        compass.start()
        createChannel()
        observeSocket()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_IDLE -> {
                serviceScope.launch { ensureSocketConnected() }
            }
            ACTION_CONNECT -> {
                targetPhone = intent.getStringExtra(EXTRA_TARGET) ?: ""
                Log.d(TAG, "ACTION_CONNECT targetPhone=$targetPhone")
                startForeground(NOTIF_ID, notif("Connecting…"))
                ConnectionRepository.setBlockIncoming(false)
                ConnectionRepository.setReceiverMode(false, null)
                currentPeer = targetPhone
                doConnectFlow()
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                sendStopToPeer(currentPeer)
                stopExchangeOnly("user_stop")
                leaveReceiverMode("user_stop", blockIncoming = false)
                currentPeer = null
                updateNotification("Idle — stopped")
            }

            ACTION_ADMIN_SESSIONS -> {
                ensureSocketConnected()
                sendAdminSessionsRequest()
            }

            ACTION_POKE -> {
                val wsOpen = ConnectionRepository.state.value.wsStatus.startsWith("open")
                if (wsOpen) {
                    sendHeartbeatNow("poke")
                } else {
                    ConnectionRepository.updateStatus("Reconnecting…")
                    ensureSocketConnected()
                }
            }

            // ───────────── Admin intents ─────────────
            ACTION_ADMIN_PAIR -> {
                ensureSocketConnected()
                val pairs = parsePairs(intent)
                if (pairs.isNotEmpty()) {
                    runWhenReadyAndIdentified {
                        ConnectionRepository.updateStatus("Admin: requesting ${pairs.size} pair(s)")
                        retryAdminPair(pairs)
                    }
                }
            }
            ACTION_ADMIN_STOP -> {
                ensureSocketConnected()
                val pairs = parsePairs(intent)
                if (pairs.isNotEmpty()) {
                    runWhenReadyAndIdentified {
                        ConnectionRepository.updateStatus("Admin: stopping ${pairs.size} pair(s)")
                        retryAdminStop(pairs)
                    }
                }
            }

            ACTION_ADMIN_LIST -> {
                ensureSocketConnected()
                sendAdminListRequest()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        fullStop("service_destroy")            // stopExchangeOnly() inside already flushes
        runCatching { compass.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    // Build a fresh envelope with optional msg_no and zero stamps.
    private fun emptyEnvelope(msgNo: Long?): JsonObject = buildJsonObject {
        msgNo?.let { put("msg_no", it) }
        put("stamps", JsonArray(emptyList()))
    }

    // Return a copy of `env` with an additional {k,ms} stamp appended.
    private fun addStamp(env: JsonObject?, key: String, ms: Long): JsonObject {
        val base = env ?: emptyEnvelope(msgNo = null)
        val outStamps = mutableListOf<JsonElement>()
        val existing = base["stamps"] as? JsonArray
        if (existing != null) outStamps.addAll(existing)
        outStamps.add(
            buildJsonObject {
                put("k", key)
                put("ms", ms)
            }
        )
        return buildJsonObject {
            // copy everything except stamps, then put new stamps
            base.forEach { (k, v) -> if (k != "stamps") put(k, v) }
            put("stamps", JsonArray(outStamps))
        }
    }

    // Grab envelope json (if any) from a parsed inbound object.
    private fun extractEnvelope(obj: JsonObject): JsonObject? =
        obj["envelope"]?.let { it as? JsonObject }

    // Utility: find ms value for a given stamp key.
    private fun findStampMs(env: JsonObject, key: String): Long? {
        val arr = env["stamps"] as? JsonArray ?: return null
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val k = o["k"]?.jsonPrimitive?.contentOrNull ?: continue
            if (k == key) return o["ms"]?.jsonPrimitive?.longOrNull
        }
        return null
    }

    private data class DelayTriple(
        val aToServerMs: Double?,   // srv.C.rx - A.send
        val serverToBMs: Double?,   // B.rx - srv.C.tx
        val bToServerMs: Double?    // srv.D.rx - B.tx
    )

    // Compute delays from envelope stamps (returns null if insufficient data).
    private fun computeDelaysFromEnvelope(env: JsonObject?): DelayTriple? {
        env ?: return null

        val aSend   = findStampMs(env, "A.send")
        val cRx     = findStampMs(env, "srv.C.rx")
        val cTx     = findStampMs(env, "srv.C.tx")
        val bRx     = findStampMs(env, "B.rx")
        val bTx     = findStampMs(env, "B.tx")
        val dRx     = findStampMs(env, "srv.D.rx")

        val a2s = if (aSend != null && cRx != null)  (cRx - aSend).toDouble() else null
        val s2b = if (cTx   != null && bRx != null)  (bRx - cTx).toDouble()   else null
        val b2s = if (bTx   != null && dRx != null)  (dRx - bTx).toDouble()   else null

        // If none present, return null so callers can fall back.
        if (a2s == null && s2b == null && b2s == null) return null
        return DelayTriple(a2s, s2b, b2s)
    }

    // Build a copy of NetworkMessage with payload.delays filled from envelope (strings with 3 decimals).
    private fun applyEnvDelaysToMessage(msg: NetworkMessage, env: JsonObject?): NetworkMessage {
        val d = computeDelaysFromEnvelope(env) ?: return msg
        fun fmt(x: Double?) = x?.let { String.format(Locale.US, "%.3f", it) }

        val newDelays = com.example.gophertester.model.Delays(
            delayAToServer = fmt(d.aToServerMs),
            delayServerToB = fmt(d.serverToBMs),
            delayBToServer = fmt(d.bToServerMs)
        )
        return msg.copy(payload = msg.payload.copy(delays = newDelays))
    }

    private fun flushCsvBuffer(force: Boolean = false) {
        synchronized(csvLock) { doFlushCsvLocked(force) }
    }

    private fun doFlushCsvLocked(force: Boolean) {
        if (activeLogUri == null || activeLogFileName == null) return
        if (csvBufferedRows == 0 && !force) return
        if (csvBufferedRows == 0 && force && csvBuffer.isEmpty()) return

        val content = csvBuffer.toString()
        activeLogUri = FileManagement.writeToFile(
            context = applicationContext,
            receivedUri = activeLogUri,
            fileName = activeLogFileName,
            fileFormat = "csv",
            directory = logDir,
            content = content,
            append = true
        )
        csvBuffer.setLength(0)
        csvBufferedRows = 0
        Log.d(TAG, "CSV flush: wrote ${content.length} chars (force=$force)")
    }

    // Compute desired message size in BYTES from DataStore (0 means "no padding", i.e., base)
    private suspend fun readDesiredMessageBytes(): Long {
        val prefs = applicationContext.dataStore.data.first()
        val kb = prefs[Prefs.MSG_SIZE_KB] ?: 1L
        return (kb.coerceAtLeast(0L)) * 1024L
    }

    // Build JSON for send_location_data with optional padding array "pad": [UserLocation, ...]
    private fun buildSendJson(
        reqId: String,
        iso: String,
        a: String,
        b: String,
        locElem: JsonElement,
        padCount: Int,
        msgNo: Long
    ): JsonObject = buildJsonObject {
        put("request_id", reqId)
        put("msg_no", msgNo)
        put("timestamp", iso)
        putJsonObject("target") {
            put("sourceId", a)
            put("destinationId", b)
            put("action", "send_location_data")
        }
        putJsonObject("payload") {
            put("userLocation", locElem)
            if (padCount > 0) {
                val arr = JsonArray(MutableList(padCount) { locElem })
                put("pad", arr)
            }
        }
        // Envelope with A.send stamp (in server time)
        val env = addStamp(
            env = emptyEnvelope(msgNo),
            key = "A.send",
            ms  = serverAlignedNowMs()
        )
        put("envelope", env)
    }

    // Build JSON for reply_location_data with optional padding "pad"
    private fun buildReplyJson(
        requestId: String,
        myPhone: String,
        dest: String,
        locElem: JsonElement,
        clientTimes: JsonObject,
        padCount: Int,
        msgNo: Long?,
        inboundEnvelope: JsonObject?,   // NEW
        bRxMs: Long,                    // NEW
        bTxMs: Long                     // NEW
    ): JsonObject = buildJsonObject {
        put("request_id", requestId)
        msgNo?.let { put("msg_no", it) }
        put("timestamp", Time.isoNow())
        putJsonObject("target") {
            put("sourceId", myPhone)
            put("destinationId", dest)
            put("action", "reply_location_data")
        }
        putJsonObject("payload") {
            put("userLocation", locElem)
            put("clientTimes", clientTimes)

            // report B's current time-sync stats (as strings for schema compatibility)
            put("b_offset_ms", clockOffsetMs.toString())
            put("b_ping_rtt_ms", lastRttMs.toString())

            if (padCount > 0) {
                val arr = JsonArray(MutableList(padCount) { locElem })
                put("pad", arr)
            }
        }

        // Start from inbound envelope (or make a fresh one), then add B stamps.
        val env1 = addStamp(inboundEnvelope ?: emptyEnvelope(msgNo), "B.rx", bRxMs)
        val env2 = addStamp(env1, "B.tx", bTxMs)
        put("envelope", env2)
    }

    // Measure encoded JSON length in bytes (UTF-8)
    private fun jsonByteLen(obj: JsonObject): Int {
        val s = json.encodeToString(obj)
        return s.toByteArray(Charsets.UTF_8).size
    }

    private fun sendAdminSessionsRequest() {
        val msg = NetworkMessage(
            requestId = UUID.randomUUID().toString(),
            timestamp = Time.isoNow(),
            target = Target(sourceId = myPhone, destinationId = "server", action = "admin_sessions"),
            payload = Payload()
        )
        ws.send(json.encodeToString(msg))
    }

    // ───────────────────────────── Internals ─────────────────────────────

    private fun sendAdminListRequest() {
        val msg = NetworkMessage(
            requestId = UUID.randomUUID().toString(),
            timestamp = Time.isoNow(),
            target = Target(
                sourceId = myPhone,
                destinationId = "server",
                action = "admin_list"
            ),
            payload = Payload()
        )
        ws.send(json.encodeToString(msg))
    }

    private fun parsePairs(intent: Intent): List<Pair<String, String>> {
        val flat = intent.getStringArrayListExtra(EXTRA_ADMIN_PAIRS) ?: return emptyList()
        return flat.mapNotNull { s ->
            val parts = s.split("|", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
                parts[0] to parts[1] else null
        }
    }

    // Return Boolean so callers can react to immediate send failure
    private fun sendAdminPairsToServer(pairs: List<Pair<String, String>>): Boolean {
        val msg = MessageFactory.adminPairs(
            uuid = UUID.randomUUID().toString(),
            iso = Time.isoNow(),
            admin = myPhone,
            pairs = pairs
        )
        val text = json.encodeToString(msg)
        val ok = ws.send(text)
        if (!ok) Log.w(TAG, "admin_pairs send() returned false (admin=$myPhone, pairs=$pairs)")
        else Log.d(TAG, "WS-> admin_pairs admin=$myPhone pairs=$pairs")
        return ok
    }

    // Return Boolean so callers can react to immediate send failure
    private fun sendAdminStopsToServer(pairs: List<Pair<String, String>>): Boolean {
        val msg = MessageFactory.adminStops(
            uuid = UUID.randomUUID().toString(),
            iso = Time.isoNow(),
            admin = myPhone,
            pairs = pairs
        )
        val text = json.encodeToString(msg)
        val ok = ws.send(text)
        if (!ok) Log.w(TAG, "admin_stop send() returned false (admin=$myPhone, pairs=$pairs)")
        else Log.d(TAG, "WS-> admin_stop admin=$myPhone pairs=$pairs")
        return ok
    }

    private fun sendExistenceInquiryNow() {
        if (myPhone == "unknown") return
        val b = targetPhone.trim()
        if (b.isBlank()) return

        val reqId = UUID.randomUUID().toString()
        val msg = MessageFactory.existenceInquiry(
            uuid = reqId, iso = Time.isoNow(), a = myPhone, b = b
        )
        val text = json.encodeToString(msg)

        Log.d(TAG, "WS-> existence_inquiry: $text")
        val ok = ws.send(text)
        if (!ok) Log.w(TAG, "existence_inquiry send() returned false")

        ConnectionRepository.updateStatus("Checking user existence…")
    }

    private fun doConnectFlow() {
        serviceScope.launch {
            ConnectionRepository.updateStatus("Connecting…")
            ConnectionRepository.flipConnecting(true)

            // Start/ensure the socket right away
            val t0 = SystemClock.elapsedRealtime()
            Log.d(TAG, "CONNECT tap t0=$t0")
            ensureSocketConnected()
            Log.d(TAG, "CONNECT after ensure() dt=${SystemClock.elapsedRealtime()-t0}ms")

            // Wait for Cronet to open (polling every 50ms up to ~2s is enough)
            var waited = 0
            while (!ConnectionRepository.state.value.wsStatus.startsWith("open") && waited < 2000) {
                delay(50); waited += 50
            }

            // Fire the first existence inquiry as soon as we’re open
            sendExistenceInquiryNow()

            // Keep the existing retry loop
            serviceScope.launch {
                val deadline = SystemClock.elapsedRealtime() + 6_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(700)
                    val st = ConnectionRepository.state.value
                    if (st.connected || st.receiverMode) break   // <- re-check AFTER delay
                    sendExistenceInquiryNow()
                }
            }
        }
    }

    private fun sendHeartbeatNow(reason: String = "manual") {
        val wsOpen = ConnectionRepository.state.value.wsStatus.startsWith("open")
        val idKnown = myPhone != "unknown"
        if (wsOpen && idKnown) {
            val beat = MessageFactory.heartbeat(
                uuid = UUID.randomUUID().toString(),
                iso = Time.isoNow(),
                a = myPhone
            )
            val text = json.encodeToString(beat)
            ws.send(text)
            ConnectionRepository.updateStatus("Heartbeat ✓ ($reason)")
            updateNotification("Online — heartbeat")
        }
    }

    // UPDATED: allow calling with suspend blocks (so we can call retry coroutines)
    private fun runWhenReadyAndIdentified(block: suspend () -> Unit) = serviceScope.launch {
        val deadline = SystemClock.elapsedRealtime() + 2500
        while (SystemClock.elapsedRealtime() < deadline) {
            val wsOpen = ConnectionRepository.state.value.wsStatus.startsWith("open")
            if (wsOpen && myPhone != "unknown") break
            delay(100)
        }
        block()
    }

    private fun ensureSocketConnected() {
        if (ensureJob?.isActive != true) {
            ensureJob = serviceScope.launch {
                while (true) {
                    val storedPhone = applicationContext.dataStore.data
                        .map { it[Prefs.USER_PHONE] ?: "" }.first()

                    if (storedPhone.isNotBlank()) {
                        if (myPhone == "unknown") myPhone = storedPhone  // ← add this
                    }

                    if (storedPhone.isBlank()) {
                        ConnectionRepository.updateStatus("Waiting for permissions/phone…")
                        delay(1000L); continue
                    }

                    val online = NetCheck.isValidated(applicationContext)
                    if (!online) {
                        ConnectionRepository.updateStatus("Offline — waiting for internet…")
                        ws.close(1001, "net down")
                        delay(1000L); continue
                    }

                    val st = ConnectionRepository.state.value.wsStatus
                    val pause = if (st.startsWith("open") || st.startsWith("connecting")) 1500L else 250L
                    delay(pause)

                    if (!st.startsWith("open") && !st.startsWith("connecting")) {
                        Log.d(TAG, "ensureSocketConnected: connecting with phone=$storedPhone")
                        ws.connect(phone = storedPhone)
                    }

                    delay(1500L)
                }
            }
        }
        if (heartbeatJob == null) {
            heartbeatJob = serviceScope.launch {
                var firstBeatSent = false
                while (true) {
                    val wsOpen = ConnectionRepository.state.value.wsStatus.startsWith("open")
                    val idKnown = myPhone != "unknown"
                    if (wsOpen && idKnown) {
                        val beat = MessageFactory.heartbeat(
                            uuid = UUID.randomUUID().toString(),
                            iso = Time.isoNow(),
                            a = myPhone
                        )
                        val text = json.encodeToString(beat)
                        ws.send(text)
                        if (!firstBeatSent) {
                            Log.d(TAG, "WS-> first heartbeat: $text")
                            firstBeatSent = true
                        }
                        ConnectionRepository.updateStatus("Heartbeat ✓")
                        delay(30_000L)
                    } else {
                        delay(250L)
                    }
                }
            }
        }

        if (timeSyncJob == null) {
            timeSyncJob = serviceScope.launch {
                while (true) {
                    val wsOpen = ConnectionRepository.state.value.wsStatus.startsWith("open")
                    val idKnown = myPhone != "unknown"
                    if (wsOpen && idKnown) {
                        sendTimeSyncProbe()
                        delay(45_000L)
                    } else {
                        delay(500L)
                    }
                }
            }
        }
    }

    private fun sendTimeSyncProbe() {
        try {
            val seq = timeSyncSeq++
            val t1Ms = System.currentTimeMillis()
            pendingTimeSync[seq] = t1Ms
            val t1Iso = isoFromMillis(t1Ms)
            val obj = """{"type":"timesync","seq":$seq,"t1":"$t1Iso"}"""
            Log.d(TAG, "WS-> timesync probe: $obj")
            ws.send(obj)
        } catch (t: Throwable) {
            Log.e(TAG, "sendTimeSyncProbe error: ${t.message}", t)
        }
    }

    private fun warmupTimeSyncBurst(count: Int = 8, spacingMs: Long = 200L) {
        serviceScope.launch {
            repeat(count) {
                sendTimeSyncProbe()
                delay(spacingMs)
            }
        }
    }

    private fun observeSocket() {
        serviceScope.launch {
            ws.status.collectLatest { s ->
                Log.d(TAG, "WS status: $s")
                ConnectionRepository.updateWsStatus(s)
            }
        }
        serviceScope.launch {
            ws.incoming.collect { raw ->
                Log.d(TAG, "WS<- $raw")
                handleIncoming(raw)
            }
        }
    }

    private suspend fun handleIncoming(raw: String) {
        try {
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (parsed == null) {
                Log.w(TAG, "handleIncoming: not JSON? raw=$raw")
                return
            }

            // ─── Identity ───────────────────────────────────────────────────────────
            if (parsed["type"]?.jsonPrimitive?.content == "identity") {
                val phone = parsed["phone"]?.jsonPrimitive?.content ?: "unknown"
                Log.d(TAG, "Identity from server: myPhone=$phone")
                myPhone = phone
                ConnectionRepository.updateMyPhone(phone)
                ConnectionRepository.updateStatus("Online as $phone")
                warmupTimeSyncBurst()
                sendTimeSyncProbe()
                return
            }

            // ─── Time sync ──────────────────────────────────────────────────────────
            if (parsed["type"]?.jsonPrimitive?.content == "timesync") {
                onTimeSync(parsed)
                return
            }

            // ─── Envelope messages ──────────────────────────────────────────────────
            val msg = json.decodeFromString<NetworkMessage>(raw)
            val action = msg.target.action
            Log.d(TAG, "handleIncoming: action=$action from=${msg.target.sourceId} to=${msg.target.destinationId}")

            when (action) {
                // ─── Probe result (A learns if B is online) ────────────────────────
                "user_existence_inquiry" -> {
                    val status = msg.payload.userStatus ?: "unknown"
                    val peer = msg.payload.userId ?: "unknown"
                    if (status == "online") {
                        val alreadySending = (sendLoopJob != null)
                        currentPeer = peer
                        ConnectionRepository.setReceiverMode(false, null)
                        ConnectionRepository.setBlockIncoming(false)
                        ConnectionRepository.flipConnected(true)
                        ConnectionRepository.updateStatus("User $peer is online. Starting exchange…")
                        updateNotification("Connected to $peer")

                        if (!alreadySending) {
                            startNewLog()
                            startSendLoop()
                        } else {
                            Log.d(TAG, "Duplicate existence reply; ignoring extra start.")
                        }
                        return
                    } else {
                        ConnectionRepository.updateStatus("User $peer is offline.")
                        ConnectionRepository.flipConnecting(false)
                        updateNotification("Idle — connected")
                    }
                    return
                }

                // ─── Admin lists ───────────────────────────────────────────────────
                "admin_list" -> {
                    val list = msg.payload.online ?: emptyList()
                    ConnectionRepository.updateAdminOnline(list)
                    return
                }

                "admin_sessions" -> {
                    val rawList = msg.payload.sessions ?: emptyList()
                    val pairs = rawList.mapNotNull { if (it.size == 2) it[0] to it[1] else null }
                    ConnectionRepository.updateAdminSessions(pairs)
                    return
                }

                // ─── B receives A's location (C→D) and replies (D) ─────────────────
                "reply_location_data" -> {
                    if (ConnectionRepository.state.value.blockIncoming) {
                        Log.d(TAG, "Incoming session ignored (blocked by user)")
                        return
                    }

                    ConnectionRepository.setReceiverMode(true, msg.target.sourceId)
                    locationTracker.start()

                    // Update UI with A's location (as sent by server)
                    val aLoc = msg.payload.userLocation
                    ConnectionRepository.updatePeerLocation(aLoc)

                    ConnectionRepository.updateStatus("Receiver mode — answering ${msg.target.sourceId}")
                    updateNotification("Receiver mode with ${msg.target.sourceId}")
                    currentPeer = msg.target.sourceId
                    bumpReceiverTimeoutTimer()

                    // Timing for clientTimes
                    val tRecvMonoNs = SystemClock.elapsedRealtimeNanos()
                    val recvCTsIso = Time.isoNow()

                    // Our (B) current location; if not ready, reply with all "N/A"
                    val loc: Location? = locationTracker.best()
                    val locPayload: UserLocation =
                        if (loc == null) {
                            UserLocation(
                                latitude = "N/A", longitude = "N/A",
                                altitude = "N/A", accuracy = "N/A",
                                speed = "N/A", speedAccuracy = "N/A",
                                bearing = "N/A", bearingAccuracy = "N/A"
                            )
                        } else {
                            toUserLocation(loc)
                        }

                    val sendTsIso = Time.isoNow()
                    val procMs = max(0.0, (SystemClock.elapsedRealtimeNanos() - tRecvMonoNs) / 1_000_000.0)

                    // Echo incoming msg_no if present
                    val inMsgNo = parsed["msg_no"]?.jsonPrimitive?.longOrNull

                    val inboundEnv = extractEnvelope(parsed)          // ← carry C’s envelope forward
                    val bRxMs = serverAlignedNowMs()                  // ← stamp B.rx as soon as C arrives

                    serviceScope.launch {
                        val desiredBytes = readDesiredMessageBytes().coerceAtLeast(0L)
                        val locElem = json.encodeToJsonElement(locPayload)
                        val clientTimesObj = buildJsonObject {
                            put("recv_c_ts", recvCTsIso)
                            put("send_d_ts", sendTsIso)
                            put("proc_ms", procMs)
                        }

                        // Find pad count (if any). We’ll rebuild once more with a final B.tx right before send.
                        var pad = 0
                        if (desiredBytes > 0L) {
                            // quick probe size with pad=0
                            var probe = buildReplyJson(
                                requestId = msg.requestId,
                                myPhone = myPhone,
                                dest = msg.target.sourceId,
                                locElem = locElem,
                                clientTimes = clientTimesObj,
                                padCount = 0,
                                msgNo = inMsgNo,
                                inboundEnvelope = inboundEnv,
                                bRxMs = bRxMs,
                                bTxMs = serverAlignedNowMs()
                            )
                            var sz = jsonByteLen(probe)
                            while (sz < desiredBytes && pad < 5000) {
                                pad++
                                probe = buildReplyJson(
                                    requestId = msg.requestId,
                                    myPhone = myPhone,
                                    dest = msg.target.sourceId,
                                    locElem = locElem,
                                    clientTimes = clientTimesObj,
                                    padCount = pad,
                                    msgNo = inMsgNo,
                                    inboundEnvelope = inboundEnv,
                                    bRxMs = bRxMs,
                                    bTxMs = serverAlignedNowMs()
                                )
                                sz = jsonByteLen(probe)
                            }
                        }

                        // Final build with B.tx stamped as close to send as possible
                        val finalReply = buildReplyJson(
                            requestId = msg.requestId,
                            myPhone = myPhone,
                            dest = msg.target.sourceId,
                            locElem = locElem,
                            clientTimes = clientTimesObj,
                            padCount = pad,
                            msgNo = inMsgNo,
                            inboundEnvelope = inboundEnv,
                            bRxMs = bRxMs,
                            bTxMs = serverAlignedNowMs()
                        )
                        ws.send(json.encodeToString(finalReply))
                    }
                }

                // ─── A receives B's reply + computed delays (E) ────────────────────
                "get_location_data" -> {
                    val parsedObj = parsed // keep for clarity
                    val msgWithEnv = applyEnvDelaysToMessage(msg, extractEnvelope(parsedObj))

                    // Server E-creation time (ISO) and A’s server-aligned receive time
                    val eServerIso = msgWithEnv.timestamp
                    val serverTsMs = parseIsoToMillis(eServerIso)
                    val nowAlignedToServerMs = System.currentTimeMillis() + clockOffsetMs
                    val delayServerToA = (nowAlignedToServerMs - serverTsMs).coerceAtLeast(0L).toDouble()

                    // App↔App RTT
                    var appRttMs: Double? = null
                    pendingAppRttStartNs.remove(msgWithEnv.requestId)?.let { t0Ns ->
                        appRttMs = max(0.0, (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1_000_000.0)
                    }

                    val serverRttB = msgWithEnv.payload.serverRttBMs?.toDoubleOrNull()
                    val del = msgWithEnv.payload.delays
                    val bLoc = msgWithEnv.payload.userLocation

                    val isValidB =
                        !(bLoc == null ||
                                bLoc.latitude.toDoubleOrNull() == null ||
                                bLoc.longitude.toDoubleOrNull() == null ||
                                (bLoc.latitude == "0.0" && bLoc.longitude == "0.0"))

                    // Always update delays; show null location in UI if invalid
                    ConnectionRepository.updateDelays(
                        aToServer = del?.delayAToServer?.toDoubleOrNull(),
                        serverToA = delayServerToA,
                        serverToB = del?.delayServerToB?.toDoubleOrNull(),
                        bToServer = del?.delayBToServer?.toDoubleOrNull(),
                        bLocation = if (isValidB) bLoc else null,
                        appToAppRtt = appRttMs,
                        serverRttB = serverRttB
                    )

                    if (!isValidB) {
                        ConnectionRepository.updateStatus("Waiting for valid location from ${msg.target.sourceId}…")
                    }

                    // Envelope stamps (SERVER TIME, ms)
                    val env = extractEnvelope(parsedObj)
                    val aSend   = env?.let { findStampMs(it, "A.send") }
                    val srvCRx  = env?.let { findStampMs(it, "srv.C.rx") }
                    val srvCTx  = env?.let { findStampMs(it, "srv.C.tx") }
                    val bRx     = env?.let { findStampMs(it, "B.rx") }
                    val bTx     = env?.let { findStampMs(it, "B.tx") }
                    val srvDRx  = env?.let { findStampMs(it, "srv.D.rx") }

                    // msg_no (if present) for CSV first column
                    val msgNo = parsedObj["msg_no"]?.jsonPrimitive?.longOrNull

                    // Log a row for every message; appendCsvRow turns blanks/0.0 into "N/A"
                    serviceScope.launch {
                        appendCsvRow(
                            delayServerToA = delayServerToA,
                            appToAppRtt = appRttMs,
                            serverRttB = serverRttB,
                            msg = msgWithEnv,
                            msgNo = msgNo,

                            // NEW args:
                            eServerIso = eServerIso,
                            aRxServerMs = nowAlignedToServerMs,
                            envASendMs = aSend,
                            envSrvCRxMs = srvCRx,
                            envSrvCTxMs = srvCTx,
                            envBRxMs = bRx,
                            envBTxMs = bTx,
                            envSrvDRxMs = srvDRx
                        )

                        val ack = MessageFactory.ack(UUID.randomUUID().toString(), Time.isoNow(), myPhone)
                        ws.send(json.encodeToString(ack))
                    }
                }

                // ─── BSM (Basic Safety Message) reports, e.g. from WezzOn at 10Hz ──
                "bsm_data" -> {
                    val bsm = msg.payload.bsm
                    if (bsm != null) {
                        appendBsmRow(from = msg.target.sourceId, bsm = bsm)
                    }
                }

                // ─── Peer pressed Stop ─────────────────────────────────────────────
                "session_control" -> {
                    when (msg.payload.cmd?.lowercase()) {
                        "stop" -> {
                            stopExchangeOnly("peer_stop")
                            leaveReceiverMode("peer_stop", blockIncoming = false)
                            currentPeer = null
                            updateNotification("Idle — peer stopped")
                        }
                    }
                }

                // ─── Admin forwards from server ────────────────────────────────────
                "admin_connect" -> {
                    val peer = msg.payload.userId ?: msg.payload.adminTo
                    if (!peer.isNullOrBlank()) {
                        Log.d(TAG, "Admin instructs connect to $peer")
                        ConnectionRepository.updateTargetPhone(peer)
                        targetPhone = peer
                        startForeground(NOTIF_ID, notif("Connecting (admin)…"))
                        ConnectionRepository.setBlockIncoming(false)
                        ConnectionRepository.setReceiverMode(false, null)
                        currentPeer = peer
                        doConnectFlow()
                    }
                }

                "admin_stop" -> {
                    val peer = msg.payload.userId
                    Log.d(TAG, "Admin instructs stop (peer=$peer)")
                    sendStopToPeer(peer)
                    stopExchangeOnly("admin_stop")
                    leaveReceiverMode("admin_stop", blockIncoming = false)
                    currentPeer = null
                    updateNotification("Idle — admin stop")
                }

                else -> Unit
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleIncoming error: ${t.message}", t)
        }
    }


    private fun startSendLoop() {
        Log.d(TAG, "startSendLoop() begin; connected=${ConnectionRepository.state.value.connected} peer=$currentPeer")
        sendLoopJob?.cancel()
        locationTracker.start()
        ConnectionRepository.setBlockIncoming(false)

        sendLoopJob = serviceScope.launch {
            // Read once; enforce a minimum of 1 ms
            val periodMs = applicationContext.dataStore.data
                .map { it[Prefs.SEND_INTERVAL_MS] ?: 50L }
                .first()
                .coerceAtLeast(1L)

            ConnectionRepository.flipConnecting(false)
            msgSeq = 0L

            var nextT = SystemClock.elapsedRealtime()

            while (isActive && ConnectionRepository.state.value.connected) {
                // Schedule the next tick first so we always target a fixed cadence
                nextT += periodMs

                // Build the location payload
                val loc = locationTracker.best()
                val locPayload = toUserLocation(loc)
                val locElem = json.encodeToJsonElement(locPayload)

                // Track per-message seq and app↔app RTT start
                val reqId = UUID.randomUUID().toString()
                val thisMsgNo = ++msgSeq
                pendingAppRttStartNs[reqId] = SystemClock.elapsedRealtimeNanos()

                // Build base JSON (with A.send envelope stamp) then pad to desired size
                val desiredBytes = readDesiredMessageBytes().coerceAtLeast(0L)
                var obj = buildSendJson(
                    reqId, Time.isoNow(), myPhone, targetPhone, locElem,
                    padCount = 0, msgNo = thisMsgNo
                )
                val baseBytes = jsonByteLen(obj)

                if (desiredBytes > 0L && desiredBytes > baseBytes) {
                    var n = 1
                    var last = obj
                    while (n < 5_000) {
                        val test = buildSendJson(
                            reqId, Time.isoNow(), myPhone, targetPhone, locElem,
                            padCount = n, msgNo = thisMsgNo
                        )
                        val sz = jsonByteLen(test)
                        last = test
                        if (sz >= desiredBytes) break
                        n++
                    }
                    obj = last
                }

                // Send (QuicClient writes and flushes)
                ws.send(json.encodeToString(obj))

                // Sleep precisely until the next scheduled tick, even if work was slow
                val now = SystemClock.elapsedRealtime()
                val delayMs = (nextT - now).coerceAtLeast(0L)
                delay(delayMs)
            }
        }
    }

    private fun stopExchangeOnly(why: String) {
        Log.d(TAG, "stopExchangeOnly($why)")
        ConnectionRepository.flipConnected(false)
        ConnectionRepository.flipConnecting(false)
        ConnectionRepository.updateStatus("Idle ($why)")
        sendLoopJob?.cancel()
        sendLoopJob = null
        locationTracker.stop()
        pendingAppRttStartNs.clear()
        flushCsvBuffer(force = true)
        activeLogUri = null
        activeLogFileName = null
        // reset session/rotation state
        sessionTimestamp = null
        logPartIndex = 0
        rowsInCurrentFile = 0
    }

    private fun leaveReceiverMode(why: String, blockIncoming: Boolean) {
        receiverTimeoutJob?.cancel()
        receiverTimeoutJob = null
        ConnectionRepository.setReceiverMode(false, null)
        ConnectionRepository.updatePeerLocation(null)
        ConnectionRepository.setBlockIncoming(blockIncoming)
        ConnectionRepository.updateStatus(
            if (blockIncoming) "Idle ($why) — incoming blocked" else "Idle ($why)"
        )
    }

    private fun bumpReceiverTimeoutTimer() {
        receiverTimeoutJob?.cancel()
        receiverTimeoutJob = serviceScope.launch {
            delay(10_000L)
            if (ConnectionRepository.state.value.receiverMode) {
                leaveReceiverMode("receiver timeout", blockIncoming = false)
                currentPeer = null
                updateNotification("Idle — receiver timed out")
            }
        }
    }

    private fun sendStopToPeer(to: String?) {
        if (!to.isNullOrBlank() && myPhone != "unknown") {
            val msg = MessageFactory.sessionStop(
                uuid = UUID.randomUUID().toString(),
                iso = Time.isoNow(),
                from = myPhone,
                to = to
            )
            ws.send(json.encodeToString(msg))
            Log.d(TAG, "WS-> session_control stop sent to $to")
        }
    }

    private fun fullStop(why: String) {
        Log.d(TAG, "fullStop($why)")
        stopExchangeOnly(why)
        leaveReceiverMode(why, blockIncoming = false)
        heartbeatJob?.cancel(); heartbeatJob = null
        timeSyncJob?.cancel(); timeSyncJob = null
        receiverTimeoutJob?.cancel(); receiverTimeoutJob = null
        currentPeer = null
        ws.close()
    }

    private fun toUserLocation(loc: Location?): UserLocation {
        // Defaults
        var bearingStr: String? = null
        var bearingAccStr: String? = null

        if (loc == null) {
            // Location not ready: fill every field with "N/A"
            return UserLocation(
                latitude = "N/A", longitude = "N/A",
                altitude = "N/A", accuracy = "N/A",
                speed = "N/A", speedAccuracy = "N/A",
                bearing = "N/A", bearingAccuracy = "N/A"
            )
        }

        if (loc.hasBearing() && (loc.speed > 0.5f || (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy()))) {
            // Use course-over-ground from location when moving or accuracy available
            bearingStr = loc.bearing.toString()
            if (Build.VERSION.SDK_INT >= 26 && loc.hasBearingAccuracy())
                bearingAccStr = loc.bearingAccuracyDegrees.toString()
        } else {
            // Compass fallback (magnetic -> true north), include an approximate accuracy
            val reading = compass.latest.value
            if (reading != null) {
                var heading = reading.azimuthDeg
                loc.let {
                    val gf = android.hardware.GeomagneticField(
                        it.latitude.toFloat(),
                        it.longitude.toFloat(),
                        it.altitude.toFloat(),
                        System.currentTimeMillis()
                    )
                    heading += gf.declination
                    while (heading < 0f) heading += 360f
                    while (heading >= 360f) heading -= 360f
                }
                bearingStr = String.format(Locale.US, "%.1f", heading)
                bearingAccStr = when (reading.accuracyStatus) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "5.0"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "15.0"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "45.0"
                    else -> null // unknown/unreliable
                }
            }
        }

        return UserLocation(
            latitude = loc.latitude.toString(),
            longitude = loc.longitude.toString(),
            altitude = loc.altitude.toString(),
            accuracy = loc.accuracy.toString(),
            speed = if (loc.hasSpeed()) loc.speed.toString() else null,
            speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy())
                loc.speedAccuracyMetersPerSecond.toString()
            else null,
            bearing = bearingStr,
            bearingAccuracy = bearingAccStr
        )
    }

    private fun startNewLog() {
        // Fix the session timestamp at first log creation
        sessionTimestamp = Time.fileSafeNow()
        logPartIndex = 1
        rowsInCurrentFile = 0

        val fileName = "gopher_log_${sessionTimestamp}_P${logPartIndex}.csv"
        val uri = FileManagement.writeToFile(
            applicationContext,
            null,
            fileName,
            "csv",
            logDir,
            csvHeader,
            append = false
        )
        activeLogUri = uri
        activeLogFileName = fileName
        serviceScope.launch {
            applicationContext.dataStore.edit {
                it[Prefs.LOG_URI] = uri?.toString() ?: ""
                it[Prefs.LOG_FILE] = fileName
            }
        }
        ConnectionRepository.updateLogInfo(fileName, uri?.toString() ?: "")
        csvBuffer.setLength(0)
        csvBufferedRows = 0
    }

    // Rotate to a new part file if rowsInCurrentFile reached MAX_ROWS_PER_FILE.
    // Must be called under csvLock.
    private fun rotateLogFileIfNeededLocked() {
        if (rowsInCurrentFile < MAX_ROWS_PER_FILE) return
        // Flush everything pending to the current file
        doFlushCsvLocked(force = true)

        // Advance to next part
        logPartIndex += 1
        rowsInCurrentFile = 0

        val newFile = "gopher_log_${sessionTimestamp}_P${logPartIndex}.csv"
        val uri = FileManagement.writeToFile(
            applicationContext,
            null,            // new file
            newFile,
            "csv",
            logDir,
            csvHeader,       // write header to each part
            append = false
        )
        activeLogUri = uri
        activeLogFileName = newFile

        // reflect in prefs & UI
        serviceScope.launch {
            applicationContext.dataStore.edit {
                it[Prefs.LOG_URI] = uri?.toString() ?: ""
                it[Prefs.LOG_FILE] = newFile
            }
        }
        ConnectionRepository.updateLogInfo(newFile, uri?.toString() ?: "")

        Log.d(TAG, "Rotated log to $newFile")
    }

    // ───────────────────────────── BSM logging (separate file from the timing CSV) ─────────────────────────────
    private var bsmLogUri: android.net.Uri? = null
    private var bsmLogFileName: String? = null

    private val bsmCsvHeader =
        "received_at,from,msgCnt,tmpId,secMark,lat,lon,elev,accuracy," +
            "speedMps,headingDeg,steeringRaw,accel,brakes,size,raw_text\n"

    /** Appends one row per incoming "bsm_data" message to a CSV under Documents/GopherTester. */
    private fun appendBsmRow(from: String, bsm: com.example.gophertester.model.Bsm) {
        try {
            if (bsmLogFileName == null) {
                val fileName = "bsm_log_${Time.fileSafeNow()}.csv"
                bsmLogFileName = fileName
                bsmLogUri = FileManagement.writeToFile(
                    context = applicationContext,
                    receivedUri = null,
                    fileName = fileName,
                    fileFormat = "csv",
                    directory = logDir,
                    content = bsmCsvHeader,
                    append = false
                )
            }

            val c = bsm.core
            fun csvField(x: String?): String = "\"${(x ?: "").replace("\"", "\"\"")}\""

            val line = listOf(
                Time.isoNow(),
                csvField(from),
                c?.msgCnt?.toString() ?: "",
                c?.tmpId ?: "",
                c?.secMark?.toString() ?: "",
                c?.lat ?: "",
                c?.lon ?: "",
                c?.elev ?: "",
                c?.accuracy ?: "",
                c?.speedMps?.toString() ?: "",
                c?.headingDeg?.toString() ?: "",
                c?.steeringRaw?.toString() ?: "",
                c?.accel ?: "",
                c?.brakes ?: "",
                c?.size ?: "",
                csvField(bsm.text)
            ).joinToString(",") + "\n"

            bsmLogUri = FileManagement.writeToFile(
                context = applicationContext,
                receivedUri = bsmLogUri,
                fileName = bsmLogFileName,
                fileFormat = "csv",
                directory = logDir,
                content = line,
                append = true
            )
        } catch (t: Throwable) {
            Log.e(TAG, "appendBsmRow error: ${t.message}", t)
        }
    }

    private fun appendCsvRow(
        delayServerToA: Double,
        appToAppRtt: Double?,
        serverRttB: Double?,
        msg: NetworkMessage,
        msgNo: Long?,

        eServerIso: String,        // server ISO of E (from msg.timestamp)
        aRxServerMs: Long,         // A’s receive time aligned to server clock
        envASendMs: Long?,         // "A.send"
        envSrvCRxMs: Long?,        // "srv.C.rx"
        envSrvCTxMs: Long?,        // "srv.C.tx"
        envBRxMs: Long?,           // "B.rx"
        envBTxMs: Long?,           // "B.tx"
        envSrvDRxMs: Long?         // "srv.D.rx"
    ) {
        try {
            val del = msg.payload.delays
            val now = Time.isoNow()

            fun fmt(x: Double?) = x?.let { String.format(Locale.US, "%.3f", it) } ?: ""
            fun nz(x: String?) =
                when {
                    x.isNullOrBlank() -> "N/A"
                    x.equals("nan", true) || x.equals("infinity", true) || x.equals("-infinity", true) -> "N/A"
                    else -> x
                }
            fun nzLatLon(x: String?) = if (x.isNullOrBlank() || x == "0.0" || x == "0") "N/A" else nz(x)
            fun msOrBlank(x: Long?) = x?.toString() ?: ""

            val aLoc = toUserLocation(locationTracker.latest.value)
            val bLoc = msg.payload.userLocation ?: UserLocation("","","","","","","","")

            val offsetOut = if (offsetSamples.isEmpty()) "" else clockOffsetMs.toString()
            val rttOut    = if (rttSamples.isEmpty()) "" else lastRttMs.toString()

            val bOffsetStr = msg.payload.bOffsetMs
            val bRttStr    = msg.payload.bPingRttMs

            val line =
                "${msgNo ?: -1}," +
                        "$now," +                           // A local ISO when writing
                        "$eServerIso," +                    // server’s E ISO
                        "$aRxServerMs," +                   // A receive time aligned to server
                        "${msOrBlank(envASendMs)}," +
                        "${msOrBlank(envSrvCRxMs)}," +
                        "${msOrBlank(envSrvCTxMs)}," +
                        "${msOrBlank(envBRxMs)}," +
                        "${msOrBlank(envBTxMs)}," +
                        "${msOrBlank(envSrvDRxMs)}," +
                        "${del?.delayAToServer ?: ""}," +
                        "${fmt(delayServerToA)}," +
                        "${del?.delayServerToB ?: ""}," +
                        "${del?.delayBToServer ?: ""}," +
                        "${nzLatLon(aLoc.latitude)},${nzLatLon(aLoc.longitude)}," +
                        "${nz(aLoc.altitude)},${nz(aLoc.accuracy)}," +
                        "${nz(aLoc.speed)},${nz(aLoc.speedAccuracy)}," +
                        "${nz(aLoc.bearing)},${nz(aLoc.bearingAccuracy)}," +
                        "${nzLatLon(bLoc.latitude)},${nzLatLon(bLoc.longitude)}," +
                        "${nz(bLoc.altitude)},${nz(bLoc.accuracy)}," +
                        "${nz(bLoc.speed)},${nz(bLoc.speedAccuracy)}," +
                        "${nz(bLoc.bearing)},${nz(bLoc.bearingAccuracy)}," +
                        "${fmt(appToAppRtt)}," +
                        "${fmt(serverRttB)}," +
                        // B's metrics (or N/A if absent)
                        "${nz(bOffsetStr)}," +
                        "${nz(bRttStr)}," +
                        // A's own time-sync stats
                        "$offsetOut,$rttOut\n"

            synchronized(csvLock) {
                csvBuffer.append(line)
                csvBufferedRows++
                rowsInCurrentFile++                      // count data rows for rotation

                if (csvBufferedRows >= 100) {
                    doFlushCsvLocked(force = false)
                }

                // rotate if needed (flush already done above)
                rotateLogFileIfNeededLocked()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "appendCsvRow error: ${t.message}", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Gopher Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun notif(text: String): Notification {
        val iconId = runCatching { R.drawable.ic_stat_name }.getOrNull()
            ?: android.R.drawable.stat_sys_data_bluetooth
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconId)
            .setContentTitle("GopherTester")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif(text))
    }

    // ───────────────────────────── Time sync helpers ─────────────────────────────

    private fun onTimeSync(obj: JsonObject) {
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: return
        val t1Ms = pendingTimeSync.remove(seq) ?: return
        val t2Iso = obj["t2"]?.jsonPrimitive?.content ?: return
        val t3Iso = obj["t3"]?.jsonPrimitive?.content ?: return

        val t2 = parseIsoToMillis(t2Iso)
        val t3 = parseIsoToMillis(t3Iso)
        val t4 = System.currentTimeMillis()

        val offsetRaw = (((t2 - t1Ms) + (t3 - t4)) / 2.0).toLong()
        val rttRaw = ((t4 - t1Ms) - (t3 - t2)).coerceAtLeast(0L)

        applyTimeSyncSample(offsetRaw, rttRaw)
        Log.d(
            TAG,
            "timesync: seq=$seq, rawOffset=$offsetRaw, rawRtt=$rttRaw → smoothedOffset=$clockOffsetMs, smoothedRtt=$lastRttMs"
        )

        sendTimeSyncReport()
    }

    private fun applyTimeSyncSample(offset: Long, rtt: Long) {
        if (offsetSamples.size >= MAX_TS_SAMPLES) offsetSamples.removeFirst()
        if (rttSamples.size >= MAX_TS_SAMPLES) rttSamples.removeFirst()
        offsetSamples.addLast(offset)
        rttSamples.addLast(rtt)

        fun medianOf(list: List<Long>): Long {
            if (list.isEmpty()) return 0L
            val s = list.sorted()
            return s[s.size / 2]
        }

        val medOffset = medianOf(offsetSamples.toList())
        val medRtt = medianOf(rttSamples.toList())

        clockOffsetMs = medOffset
        lastRttMs = medRtt

        ConnectionRepository.updateClock(clockOffsetMs, lastRttMs)
    }

    private fun sendTimeSyncReport() {
        try {
            val obj = """{"type":"timesync_report","offset_ms":$clockOffsetMs,"rtt_ms":$lastRttMs}"""
            Log.d(TAG, "WS-> timesync_report: $obj")
            ws.send(obj)
        } catch (t: Throwable) {
            Log.e(TAG, "sendTimeSyncReport error: ${t.message}", t)
        }
    }

    private fun isoFromMillis(ms: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    private fun parseIsoToMillis(iso: String): Long =
        try {
            Instant.parse(iso.replace("+00:00", "Z")).toEpochMilli()
        } catch (t: Throwable) {
            Log.w(TAG, "parseIsoToMillis failed for '$iso': ${t.message}")
            0L
        }

    // ───────────────────────────── Admin retry helpers ─────────────────────────────

    private fun Pair<String, String>.norm(): Pair<String, String> =
        if (first <= second) this else second to first

    private fun sessionsContainAll(pairs: List<Pair<String, String>>): Boolean {
        val want = pairs.map { it.norm() }.toSet()
        val have = ConnectionRepository.state.value.adminSessions.map { it.norm() }.toSet()
        return want.all { it in have }
    }

    private fun sessionsContainNone(pairs: List<Pair<String, String>>): Boolean {
        val drop = pairs.map { it.norm() }.toSet()
        val have = ConnectionRepository.state.value.adminSessions.map { it.norm() }.toSet()
        return drop.none { it in have }
    }

    private suspend fun retryAdminPair(
        pairs: List<Pair<String, String>>,
        timeoutMs: Long = 6_000,
        intervalMs: Long = 700
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var tries = 0
        do {
            tries++
            val ok = sendAdminPairsToServer(pairs)
            if (!ok) ensureSocketConnected()
            sendAdminSessionsRequest()
            delay(intervalMs)
        } while (!sessionsContainAll(pairs) && SystemClock.elapsedRealtime() < deadline)

        if (sessionsContainAll(pairs)) {
            Log.d(TAG, "Admin pair success after $tries tries")
            ConnectionRepository.updateStatus("Admin: paired ${pairs.size} ✓")
        } else {
            Log.w(TAG, "Admin pair timed out after $tries tries")
            ConnectionRepository.updateStatus("Admin: pair timed out")
        }
    }

    private suspend fun retryAdminStop(
        pairs: List<Pair<String, String>>,
        timeoutMs: Long = 6_000,
        intervalMs: Long = 700
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var tries = 0
        do {
            tries++
            val ok = sendAdminStopsToServer(pairs)
            if (!ok) ensureSocketConnected()
            sendAdminSessionsRequest()
            delay(intervalMs)
        } while (!sessionsContainNone(pairs) && SystemClock.elapsedRealtime() < deadline)

        if (sessionsContainNone(pairs)) {
            Log.d(TAG, "Admin stop success after $tries tries")
            ConnectionRepository.updateStatus("Admin: stopped ${pairs.size} ✓")
        } else {
            Log.w(TAG, "Admin stop timed out after $tries tries")
            ConnectionRepository.updateStatus("Admin: stop timed out")
        }
    }
}