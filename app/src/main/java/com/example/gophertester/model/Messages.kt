package com.example.gophertester.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified wire format for all app↔server messages.
 * Backward-compatible with the existing schema, and extended with
 * Envelope/Stamp so clients can compute delays locally.
 */
@Serializable
data class NetworkMessage(
    @SerialName("request_id") val requestId: String,
    val timestamp: String,

    // Optional status fields (server replies / admin)
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null,

    // Routing
    val target: Target,

    // Payload (locations, admin lists, etc.)
    val payload: Payload = Payload(),

    // New: chain-of-custody stamps (all times expressed in SERVER TIME, ms)
    val envelope: Envelope? = null,

    // Back-compat convenience: some senders may include a top-level msg_no
    // (Envelope.msgNo is preferred going forward)
    @SerialName("msg_no") val msgNoTopLevel: Long? = null
)

@Serializable
data class Target(
    @SerialName("sourceId") val sourceId: String,
    @SerialName("destinationId") val destinationId: String,
    val action: String
)

@Serializable
data class Payload(
    val userId: String? = null,
    val userStatus: String? = null,

    // Location data
    val userLocation: UserLocation? = null,

    // BSM (Basic Safety Message) reports, e.g. from WezzOn at 10Hz.
    val bsm: Bsm? = null,

    // Back-compat: server-computed delays (deprecated; prefer Envelope stamps)
    val delays: Delays? = null,

    // Admin fields
    @SerialName("to") val adminTo: String? = null,
    @SerialName("pairs") val pairs: List<List<String>>? = null,
    @SerialName("online") val online: List<String>? = null,
    // list of connected pairs [["+1555...","+1444..."], ...]
    @SerialName("sessions") val sessions: List<List<String>>? = null,

    // Back-compat metrics (deprecated)
    @SerialName("delay_server_to_B_ms") val delayServerToB_compat: String? = null,
    @SerialName("server_rtt_B_ms") val serverRttBMs: String? = null,

    @SerialName("b_offset_ms")   val bOffsetMs: String? = null,
    @SerialName("b_ping_rtt_ms") val bPingRttMs: String? = null,

    // Session control
    @SerialName("cmd") val cmd: String? = null
)

/**
 * A J2735-style Basic Safety Message. Fields are kept as strings where the
 * sender may report "unavailable" (matching the wire format), with a
 * structured [core] for the individually-typed numeric fields.
 */
@Serializable
data class Bsm(
    val text: String? = null,
    val core: BsmCore? = null,
    val partII: String? = null,
    val regional: Int? = null,
    val security: String? = null
)

@Serializable
data class BsmCore(
    val msgCnt: Int? = null,
    val tmpId: String? = null,
    val secMark: Int? = null,
    val lat: String? = null,
    val lon: String? = null,
    val elev: String? = null,
    val accuracy: String? = null,
    val tx: String? = null,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val steeringRaw: Int? = null,
    val accel: String? = null,
    val brakes: String? = null,
    val size: String? = null
)

@Serializable
data class UserLocation(
    val latitude: String,
    val longitude: String,
    val altitude: String? = null,
    val accuracy: String? = null,
    val speed: String? = null,
    val speedAccuracy: String? = null,
    val bearing: String? = null,
    val bearingAccuracy: String? = null
)

/**
 * Back-compat structure for server-computed delays.
 * Prefer computing from Envelope.stamps on the client.
 */
@Serializable
data class Delays(
    @SerialName("delay_A_to_server_ms") val delayAToServer: String? = null,
    @SerialName("delay_server_to_B_ms") val delayServerToB: String? = null,
    @SerialName("delay_B_to_server_ms") val delayBToServer: String? = null
)

/**
 * One timing stamp in SERVER TIME (milliseconds since epoch),
 * recording the moment an event happened at a specific hop.
 *
 * Common keys:
 *  - "A.send"     : A emitted C
 *  - "srv.C.rx"   : server received C from A
 *  - "srv.C.tx"   : server forwarded C to B
 *  - "B.rx"       : B received C
 *  - "B.tx"       : B emitted D
 *  - "srv.D.rx"   : server received D from B
 *  - "srv.D.tx"   : server forwarded D to A
 */
@Serializable
data class Stamp(
    val k: String,
    val ms: Long
)

/**
 * Envelope that travels end-to-end (A→Srv→B→Srv→A) so A can compute all legs.
 */
@Serializable
data class Envelope(
    @SerialName("msg_no") val msgNo: Long? = null,
    val stamps: List<Stamp> = emptyList()
)

/** Convenience factory helpers */
object MessageFactory {

    fun existenceInquiry(uuid: String, iso: String, a: String, b: String, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = a, destinationId = b, action = "user_existence_inquiry"),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    fun sendLocation(uuid: String, iso: String, a: String, b: String, loc: UserLocation, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = a, destinationId = b, action = "send_location_data"),
            payload = Payload(userLocation = loc),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    fun ack(uuid: String, iso: String, a: String, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = a, destinationId = "server", action = "acknowledgment"),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    fun heartbeat(uuid: String, iso: String, a: String, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = a, destinationId = "server", action = "heartbeat"),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    fun sessionStop(uuid: String, iso: String, from: String, to: String, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = from, destinationId = to, action = "session_control"),
            payload = Payload(cmd = "stop"),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    // ───────────── ADMIN: app → server ─────────────
    fun adminPairs(uuid: String, iso: String, admin: String, pairs: List<Pair<String, String>>, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = admin, destinationId = "server", action = "admin_pair"),
            payload = Payload(pairs = pairs.map { listOf(it.first, it.second) }),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )

    fun adminStops(uuid: String, iso: String, admin: String, pairs: List<Pair<String, String>>, envelope: Envelope? = null, msgNo: Long? = null) =
        NetworkMessage(
            requestId = uuid,
            timestamp = iso,
            target = Target(sourceId = admin, destinationId = "server", action = "admin_stop"),
            payload = Payload(pairs = pairs.map { listOf(it.first, it.second) }),
            envelope = envelope,
            msgNoTopLevel = msgNo
        )
}

/* ---------- Optional tiny helpers for building/updating envelopes ---------- */

/** Create an Envelope with a single stamp (e.g., "A.send"). */
fun newEnvelopeWithStamp(msgNo: Long? = null, key: String, ms: Long): Envelope =
    Envelope(msgNo = msgNo, stamps = listOf(Stamp(k = key, ms = ms)))

/** Return a copy of the envelope with an additional stamp appended. */
fun Envelope.withStamp(key: String, ms: Long): Envelope =
    copy(stamps = stamps + Stamp(k = key, ms = ms))

/** Return a copy of the message with an added stamp (creates envelope if missing). */
fun NetworkMessage.withStamp(key: String, ms: Long): NetworkMessage {
    val env = (this.envelope ?: Envelope(msgNo = this.msgNoTopLevel)).withStamp(key, ms)
    return this.copy(envelope = env)
}