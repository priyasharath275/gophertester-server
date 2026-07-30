package com.example.gophertester.ui.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gophertester.service.ConnectionService
import com.example.gophertester.ui.tab.ConnectionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AdminUiState(
    val ws: String = "—",
    val online: List<String> = emptyList(),
    val activePairs: List<Pair<String, String>> = emptyList(),
    val connecting: Boolean = false,
    val error: String? = null,
    val resetVisualNonce: Int = 0,
    val localPhone: String = ""
)

class AdminViewModel : ViewModel() {
    private val _ui = MutableStateFlow(AdminUiState())
    val ui: StateFlow<AdminUiState> = _ui

    private var pollJob: Job? = null

    // ─── New: pending smoothing windows ─────────────────────────────────────────
    private val pendingConnects = mutableMapOf<Pair<String, String>, Long>() // expiresAtMs
    private val pendingStops    = mutableMapOf<Pair<String, String>, Long>() // expiresAtMs
    private val CONNECT_GRACE_MS = 8_000L
    private val STOP_GRACE_MS    = 5_000L

    private fun Pair<String, String>.normalized(): Pair<String, String> =
        if (first <= second) this else second to first

    private fun normalizeAll(list: List<Pair<String, String>>): Set<Pair<String, String>> =
        list.map { it.normalized() }.toSet()

    private fun recomputeEffective(serverPairs: List<Pair<String, String>>): List<Pair<String, String>> {
        val now = System.currentTimeMillis()

        // Drop expired pendings
        pendingConnects.entries.removeIf { now > it.value }
        pendingStops.entries.removeIf { now > it.value }

        val server = normalizeAll(serverPairs).toMutableSet()

        // Confirmed: if server shows it, no longer "pending connect"
        pendingConnects.keys.retainAll { it !in server }

        // Effective = server ∪ pendingConnects \ pendingStops
        val effective = LinkedHashSet<Pair<String, String>>()
        effective.addAll(server)
        effective.addAll(pendingConnects.keys)
        effective.removeAll(pendingStops.keys)

        return effective.toList()
    }


    init {
        viewModelScope.launch {
            ConnectionRepository.state.collect { st ->
                val effectiveActive = recomputeEffective(st.adminSessions)
                _ui.value = _ui.value.copy(
                    ws = st.wsStatus,
                    online = st.adminOnline,
                    activePairs = effectiveActive,
                    connecting = effectiveActive.isNotEmpty(),
                    error = null,
                    localPhone = st.localPhone
                )
            }
        }
    }

    fun startPolling(context: Context) {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            refreshNow(context)
            while (true) {
                ConnectionService.adminRefreshOnline(context)
                ConnectionService.adminRefreshSessions(context)
                delay(3_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshNow(context: Context) {
        ConnectionService.adminRefreshOnline(context)
        ConnectionService.adminRefreshSessions(context)
    }

    fun connectPairs(context: Context, pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        ConnectionService.adminPair(context, pairs)

        val now = System.currentTimeMillis()
        pairs.forEach { p ->
            val k = p.normalized()
            pendingStops.remove(k) // if we were stopping it, cancel that
            pendingConnects[k] = now + CONNECT_GRACE_MS
        }

        // Immediate UI: show as active (effective) and show Stop
        _ui.value = _ui.value.copy(
            activePairs = recomputeEffective(ConnectionRepository.state.value.adminSessions),
            connecting = true
        )

        // Aggressive refresh burst to converge fast
        refreshNow(context)
        viewModelScope.launch {
            delay(900);  refreshNow(context)
            delay(1_200); refreshNow(context)
        }
    }

    fun stopPairs(context: Context, pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        ConnectionService.adminStop(context, pairs)

        val now = System.currentTimeMillis()
        pairs.forEach { p ->
            val k = p.normalized()
            pendingConnects.remove(k)
            pendingStops[k] = now + STOP_GRACE_MS
        }

        // Immediate UI: remove from effective; button flips to Connect
        _ui.value = _ui.value.copy(
            activePairs = recomputeEffective(ConnectionRepository.state.value.adminSessions),
            connecting = _ui.value.activePairs.isNotEmpty()
        )

        // Ask server now; short refresh burst
        refreshNow(context)
        viewModelScope.launch {
            delay(700);  refreshNow(context)
            delay(1_000); refreshNow(context)
        }
    }

    fun resetVisual() {
        _ui.value = _ui.value.copy(resetVisualNonce = _ui.value.resetVisualNonce + 1)
    }
}
