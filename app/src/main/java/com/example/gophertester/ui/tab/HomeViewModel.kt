package com.example.gophertester.ui.tab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gophertester.data.Prefs
import com.example.gophertester.data.dataStore
import com.example.gophertester.model.UserLocation
import com.example.gophertester.service.ConnectionService
import com.example.gophertester.util.PhoneNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import com.example.gophertester.util.ContactsResolver
import com.example.gophertester.util.NetCheck

class HomeViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ConnectionRepository.state.value)
    val ui: StateFlow<HomeUiState> = _ui

    init {
        viewModelScope.launch {
            ConnectionRepository.state.collect { _ui.value = it }
        }
    }

    fun setTargetInput(raw: String) {
        val normalized = ContactsResolver.ensurePlus(raw)
        ConnectionRepository.updateTargetDisplay(raw)
        ConnectionRepository.updateTargetPhone(normalized)
    }

    /** When user picks a contact: show "Name(+1...)" but store normalized phone. */
    fun setTargetFromContact(name: String, normalizedPhone: String) {
        val display = "$name($normalizedPhone)"
        ConnectionRepository.updateTargetDisplay(display)
        ConnectionRepository.updateTargetPhone(normalizedPhone)
    }

    fun setTargetPhone(text: String) { // keep for any existing calls
        setTargetInput(text)
    }

    /** Called from Activity on startup and after permission result. */
    fun onAppLaunch(context: Context) {
        viewModelScope.launch {
            val online = NetCheck.isValidated(context)
            ConnectionRepository.updateStatus(if (online) "Internet OK" else "Offline")

            // Load saved send interval (default 50 ms)
            val savedInterval = context.dataStore.data
                .map { it[Prefs.SEND_INTERVAL_MS] ?: 50L }
                .first()
            ConnectionRepository.updateSendIntervalMs(savedInterval)

            // Load saved desired size (KB) — default 1 KB, clamp to [0..15]
            val savedKbRaw = context.dataStore.data
                .map { it[Prefs.MSG_SIZE_KB] ?: 1L }
                .first()
            val savedKb = savedKbRaw.coerceIn(0L, 15L)
            if (savedKb != savedKbRaw) {
                // normalize persisted value if an older install had >15
                context.dataStore.edit { it[Prefs.MSG_SIZE_KB] = savedKb }
            }
            ConnectionRepository.updateDesiredSizeKb(savedKb)

            // Load stored phone if any
            val stored = context.dataStore.data.map { it[Prefs.USER_PHONE] ?: "" }.first()
            if (stored.isNotBlank()) {
                ConnectionRepository.updateLocalPhone(stored)
                ConnectionRepository.setAskForPhone(false) // ensure dialog is hidden
                return@launch
            }

            // Try to read from TelephonyManager if permission is granted
            val read = PhoneNumber.tryRead(context)
            if (!read.isNullOrBlank()) {
                ConnectionRepository.setAskForPhone(false) // hide dialog if previously shown
                savePhoneAndReconnect(context, read)
            } else {
                // Ask user with a dialog
                ConnectionRepository.setAskForPhone(true)
            }
        }
    }

    private fun normalizePhone(raw: String): String =
        raw.trim().filterIndexed { i, ch -> ch.isDigit() || (i == 0 && ch == '+') }

    fun toggleConnect(context: Context) {
        val s = ConnectionRepository.state.value
        if (!(s.connected || s.connecting || s.receiverMode)) {
            val normalized = ContactsResolver.ensurePlus(s.targetPhone)
            ConnectionRepository.updateTargetPhone(normalized)

            when {
                normalized.isBlank() -> {
                    ConnectionRepository.updateStatus("Enter a phone like +15551234567")
                    return
                }
                ContactsResolver.canonical(normalized) ==
                        ContactsResolver.canonical(s.localPhone) -> {
                    ConnectionRepository.updateStatus("Choose a different phone (not your own).")
                    return
                }
            }
            ConnectionService.connect(context, normalized)
        } else {
            ConnectionService.stop(context)
        }
    }

    fun cancelAskPhone() {
        ConnectionRepository.setAskForPhone(false)
    }

    fun savePhoneAndReconnect(context: Context, phone: String) {
        viewModelScope.launch {
            // Persist
            context.dataStore.edit { it[Prefs.USER_PHONE] = phone }
            ConnectionRepository.updateLocalPhone(phone)
            ConnectionRepository.setAskForPhone(false) // guarantee dialog closes

            // Reconnect service so it includes phone in WS URL/headers
            ConnectionService.stop(context)
            delay(250)
            ConnectionService.startIdle(context)
        }
    }

    /** Persist and reflect the user-selected send interval (ms). */
    fun saveSendIntervalMs(context: Context, ms: Long) {
        val clamped = ms.coerceAtLeast(1L) // avoid 0 or negative
        viewModelScope.launch {
            context.dataStore.edit { it[Prefs.SEND_INTERVAL_MS] = clamped }
            ConnectionRepository.updateSendIntervalMs(clamped)
        }
    }

    /**
     * Persist and reflect the desired message size in KB.
     * Rules: 0 -> default (no padding); >15 -> cap to 15 KB.
     */
    fun saveDesiredSizeKb(context: Context, kb: Long) {
        val clamped = kb.coerceIn(0L, 15L) // ← enforce [0..15]
        viewModelScope.launch {
            context.dataStore.edit { it[Prefs.MSG_SIZE_KB] = clamped }
            ConnectionRepository.updateDesiredSizeKb(clamped)
        }
    }
}

/** Shared UI state & updater for simplicity */
data class HomeUiState(
    val myPhone: String = "—",
    val localPhone: String = "",
    val targetPhone: String = "",      // always normalized (+digits) behind the scenes
    val targetDisplay: String = "",    // what the TextField shows (may include name)
    val wsStatus: String = "—",
    val status: String = "Idle",

    // Sender/connect states
    val connecting: Boolean = false,
    val connected: Boolean = false,

    // Receiver Mode
    val receiverMode: Boolean = false,
    val receiverFrom: String? = null, // A's phone
    val peerLat: String? = null,      // kept for backward UI compatibility
    val peerLon: String? = null,      // kept for backward UI compatibility
    val peerLocation: UserLocation? = null, // NEW: full A location when receiver
    val blockIncoming: Boolean = false, // if true, ignore/deny incoming sessions

    val askForPhone: Boolean = false,

    // Sender metrics/peer (B) location (shown only when NOT in receiver mode)
    val delayAtoServer: Double? = null,
    val delayServerToA: Double? = null,
    val delayServerToB: Double? = null,
    val delayBtoServer: Double? = null,

    // New: round-trips
    val appToAppRtt: Double? = null,     // A→Srv→B→Srv→A
    val serverRttB: Double? = null,      // Srv→B→Srv (from server)

    val bLat: String? = null,            // kept for backward UI compatibility
    val bLon: String? = null,            // kept for backward UI compatibility
    val bLocation: UserLocation? = null, // NEW: full B location for display
    val logFileName: String? = null,
    val logUri: String? = null,

    // Time-sync metrics
    val clockOffsetMs: Long? = null,   // server_time - phone_time
    val clockRttMs: Long? = null,

    val adminOnline: List<String> = emptyList(),
    val adminSessions: List<Pair<String, String>> = emptyList(),

    // Existing
    val sendIntervalMs: Long = 50L,

    // NEW: desired message size in KB (0..15)
    val desiredSizeKb: Long = 1L
)

object ConnectionRepository {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    fun updateWsStatus(s: String) = update { it.copy(wsStatus = s) }
    fun updateStatus(s: String) = update { it.copy(status = s) }
    fun updateMyPhone(p: String) = update { it.copy(myPhone = p) }
    fun updateLocalPhone(p: String) = update { it.copy(localPhone = p) }

    fun updateTargetPhone(p: String) = update { it.copy(targetPhone = p) }
    fun updateTargetDisplay(s: String) = update { it.copy(targetDisplay = s) }

    fun setAskForPhone(v: Boolean) = update { it.copy(askForPhone = v) }
    fun flipConnecting(v: Boolean) = update { it.copy(connecting = v) }
    fun flipConnected(v: Boolean) = update { it.copy(connected = v, connecting = false) }

    // Receiver mode controls
    fun setReceiverMode(on: Boolean, from: String?) = update {
        it.copy(
            receiverMode = on,
            receiverFrom = if (on) from else null,
            // when entering receiver mode, we also clear sender-state UI
            connecting = if (on) false else it.connecting,
            connected = if (on) false else it.connected
        )
    }

    // Store full peer location + keep old lat/lon for any legacy UI
    fun updatePeerLocation(loc: UserLocation?) = update {
        it.copy(
            peerLocation = loc,
            peerLat = loc?.latitude,
            peerLon = loc?.longitude
        )
    }

    fun setBlockIncoming(v: Boolean) = update { it.copy(blockIncoming = v) }

    // Accept full B location (and also populate the legacy lat/lon)
    fun updateDelays(
        aToServer: Double?,
        serverToA: Double?,
        serverToB: Double?,
        bToServer: Double?,
        bLocation: UserLocation?,
        appToAppRtt: Double?,
        serverRttB: Double?
    ) = update {
        it.copy(
            delayAtoServer = aToServer,
            delayServerToA = serverToA,
            delayServerToB = serverToB,
            delayBtoServer = bToServer,
            appToAppRtt = appToAppRtt,
            serverRttB = serverRttB,
            bLocation = bLocation,
            bLat = bLocation?.latitude,
            bLon = bLocation?.longitude
        )
    }

    fun updateLogInfo(name: String?, uri: String?) = update {
        it.copy(logFileName = name, logUri = uri)
    }

    // Update clock metrics for the UI
    fun updateClock(offsetMs: Long?, rttMs: Long?) = update {
        it.copy(clockOffsetMs = offsetMs, clockRttMs = rttMs)
    }

    // Existing
    fun updateSendIntervalMs(ms: Long) = update { it.copy(sendIntervalMs = ms) }

    // NEW: desired message size in KB
    fun updateDesiredSizeKb(kb: Long) = update { it.copy(desiredSizeKb = kb) }

    private fun update(block: (HomeUiState) -> HomeUiState) {
        _state.value = block(_state.value)
    }

    fun updateAdminOnline(list: List<String>) = update { it.copy(adminOnline = list) }

    fun updateAdminSessions(pairs: List<Pair<String, String>>) = update {
        it.copy(adminSessions = pairs)
    }
}
