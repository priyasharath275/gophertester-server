package com.example.gophertester.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Headers
import org.chromium.net.BidirectionalStream
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Split-mode QUIC/H3 client:
 *   - Downlink: GET /h3/down?phone=... (read-only streaming)
 *   - Uplink  : POST /h3/up?phone=...  (write-only JSONL, strictly serialized)
 *
 * This avoids proxy buffering/half-duplex quirks seen with a single duplex stream.
 */
class QuicClient(
    context: Context,
    private val serverBase: String = SERVER_BASE
) {

    companion object {
        private const val TAG = "QuicClient"
        const val SERVER_BASE = "https://app.cv2x.org"
        private const val PATH_DOWN = "/h3/down"
        private const val PATH_UP = "/h3/up"

        private val NEXT_ID = AtomicInteger(1)
    }

    private val engine: CronetEngine = CronetEngine.Builder(context)
        .enableHttp2(true)
        .enableQuic(true)
        .build()

    /** Single-thread executor for Cronet callbacks AND our write scheduling. */
    private val exec = Executors.newSingleThreadExecutor()

    // Downlink (GET) – read-only
    @Volatile private var rxRequest: UrlRequest? = null
    @Volatile private var rxBuffer: ByteBuffer? = null
    private val rxLineBuf = StringBuilder()

    // Uplink (POST) – write-only via strictly serialized queue
    @Volatile private var txStream: BidirectionalStream? = null
    private val txConnecting = AtomicBoolean(false)
    private val txLock = Any()
    private val writerLock = Any()
    private val outQueue = ArrayDeque<ByteBuffer>()
    @Volatile private var writeActive = false
    private val writeStartNs = Collections.synchronizedMap(IdentityHashMap<ByteBuffer, Long>())

    // Connection IDs & timing
    @Volatile private var connId = 0
    @Volatile private var tConnectStartMs: Long = 0L
    @Volatile private var tRxOpenMs: Long = 0L
    @Volatile private var tTxReadyMs: Long = 0L
    @Volatile private var tFirstInboundMs: Long = 0L
    @Volatile private var tFirstSendMs: Long = 0L

    // Streams state
    @Volatile private var phoneParam: String? = null

    // Public signals
    private val _incoming = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming = _incoming.asSharedFlow()

    private val _status = MutableSharedFlow<String>(
        replay = 1, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val status = _status.asSharedFlow()

    private fun dtSinceConnect(): Long =
        if (tConnectStartMs == 0L) 0 else (SystemClock.elapsedRealtime() - tConnectStartMs)

    /** Establish GET downlink and POST uplink. Call once (idempotent-ish). */
    fun connect(phone: String? = null, headers: Headers = Headers.Builder().build()) {
        if (rxRequest != null && txStream != null) {
            Log.d(TAG, "connect(): already open")
            return
        }
        connId = NEXT_ID.getAndIncrement()
        phoneParam = phone
        tConnectStartMs = SystemClock.elapsedRealtime()
        tRxOpenMs = 0L; tTxReadyMs = 0L; tFirstInboundMs = 0L; tFirstSendMs = 0L

        // ---- Downlink (GET /h3/down)
        if (rxRequest == null) {
            val downUrl = Uri.parse(serverBase).buildUpon()
                .appendEncodedPath(PATH_DOWN.trimStart('/'))
                .apply { if (!phone.isNullOrBlank()) appendQueryParameter("phone", phone) }
                .build().toString()

            Log.d(TAG, "[c#$connId] RX connect: $downUrl")
            val cb = RxCallback(this)
            val b = engine.newUrlRequestBuilder(downUrl, cb, exec)
                .addHeader("Accept", "application/json")
                .allowDirectExecutor() // fine; we still run on exec
            for (i in 0 until headers.size) b.addHeader(headers.name(i), headers.value(i))
            val req = b.build()
            rxBuffer = ByteBuffer.allocateDirect(64 * 1024)
            rxBuffer?.clear()
            rxRequest = req
            _status.tryEmit("connecting")
            req.start()
        }

        // ---- Uplink (POST /h3/up)
        if (txStream == null && !txConnecting.get()) {
            synchronized(txLock) {
                if (txStream == null && txConnecting.compareAndSet(false, true)) {
                    val upUrl = Uri.parse(serverBase).buildUpon()
                        .appendEncodedPath(PATH_UP.trimStart('/'))
                        .apply { if (!phone.isNullOrBlank()) appendQueryParameter("phone", phone) }
                        .build().toString()

                    Log.d(TAG, "[c#$connId] TX connect: $upUrl phone=${phone ?: "—"}")
                    val cb = TxCallback(this)
                    val builder = engine
                        .newBidirectionalStreamBuilder(upUrl, cb, exec)
                        .setHttpMethod("POST")
                        .delayRequestHeadersUntilFirstFlush(true)
                        .addHeader("Accept", "application/json")
                        .addHeader("Content-Type", "application/jsonl")
                    if (!phone.isNullOrBlank()) builder.addHeader("x-user-phone", phone)
                    for (i in 0 until headers.size) builder.addHeader(headers.name(i), headers.value(i))
                    val s = builder.build()
                    s.start()
                    txStream = s
                }
            }
        }
    }

    /** Enqueue a JSON line to the uplink. Adds '\n' if missing. */
    fun send(text: String): Boolean {
        val s = txStream ?: run {
            Log.w(TAG, "[c#$connId] send: TX not ready (dt=${dtSinceConnect()}ms)")
            return false
        }
        val payload = if (text.endsWith('\n')) text else "$text\n"
        val data = payload.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocateDirect(data.size).apply { put(data); flip() }
        synchronized(writerLock) {
            outQueue.addLast(buf)
            if (!writeActive) {
                writeActive = true
                exec.execute { drainOne(s) }
            }
        }
        return true
    }

    /** Close both directions. */
    fun close(code: Int = 0, reason: String = "normal") {
        Log.d(TAG, "[c#$connId] close($code,$reason) dt=${dtSinceConnect()}ms")

        // RX
        try { rxRequest?.cancel() } catch (_: Throwable) {}
        rxRequest = null
        rxBuffer = null

        // TX
        val s = txStream
        txStream = null
        txConnecting.set(false)
        try {
            if (s != null) {
                val empty = ByteBuffer.allocateDirect(0)
                s.write(empty, /*endOfStream=*/true)
                s.flush()
                s.cancel() // ensure teardown
            }
        } catch (_: Throwable) {
        } finally {
            synchronized(writerLock) {
                outQueue.clear()
                writeActive = false
                writeStartNs.clear()
            }
        }
        _status.tryEmit("closed:$code:$reason")
    }

    // ─────────────────────────── INTERNALS ───────────────────────────

    /** TX: send exactly one buffer if available. */
    private fun drainOne(currentStream: BidirectionalStream?) {
        val s = currentStream ?: txStream
        if (s == null) {
            synchronized(writerLock) { outQueue.clear(); writeActive = false }
            Log.w(TAG, "[c#$connId] drainOne: TX stream null; cleared queue")
            return
        }
        val next: ByteBuffer? = synchronized(writerLock) {
            if (outQueue.isEmpty()) { writeActive = false; null } else outQueue.removeFirst()
        }
        if (next == null) return

        try {
            val nowNs = System.nanoTime()
            writeStartNs[next] = nowNs
            val size = next.remaining()
            if (tFirstSendMs == 0L) {
                tFirstSendMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "[c#$connId] TX send(first): $size B dt=${dtSinceConnect()}ms")
            } else {
                val inflight = 1 + synchronized(writerLock) { outQueue.size }
                Log.d(TAG, "[c#$connId] TX send: $size B inflight=$inflight dt=${dtSinceConnect()}ms")
            }
            s.write(next, false)
            s.flush()
            Log.d(TAG, "[c#$connId] TX flush ok inflight=${synchronized(writerLock){outQueue.size+1}} dt=${dtSinceConnect()}ms")
        } catch (t: Throwable) {
            Log.e(TAG, "[c#$connId] TX write/flush failed: ${t.message}", t)
            try { s.cancel() } catch (_: Throwable) {}
            txStream = null
            synchronized(writerLock) { outQueue.clear(); writeActive = false }
            _status.tryEmit("failure:${t.message ?: "write_error"}")
        }
    }

    /** Prime TX upload (write a single '\n') to push headers. */
    private fun primeUpload(s: BidirectionalStream) {
        val primer = ByteBuffer.allocateDirect(1).apply { put('\n'.code.toByte()); flip() }
        synchronized(writerLock) {
            if (writeActive) {
                outQueue.addFirst(primer)
            } else {
                writeActive = true
                exec.execute {
                    writeStartNs[primer] = System.nanoTime()
                    try {
                        s.write(primer, false)
                        s.flush()
                        Log.d(TAG, "[c#$connId] TX prime 1B")
                    } catch (t: Throwable) {
                        Log.w(TAG, "[c#$connId] TX prime failed: ${t.message}")
                        writeActive = false
                    }
                }
            }
        }
    }

    // ─────────────────────────── RX callbacks ───────────────────────────

    private class RxCallback(private val owner: QuicClient) : UrlRequest.Callback() {
        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            request.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            owner.tRxOpenMs = SystemClock.elapsedRealtime()
            val proto = info.negotiatedProtocol ?: "?"
            val code = info.httpStatusCode
            Log.d(TAG, "[c#${owner.connId}] RX started: proto=$proto code=$code dt=${owner.tRxOpenMs - owner.tConnectStartMs}ms")
            owner._status.tryEmit("open_rx")
            val buf = owner.rxBuffer ?: ByteBuffer.allocateDirect(64 * 1024).also { owner.rxBuffer = it }
            buf.clear()
            request.read(buf)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            try {
                byteBuffer.flip()
                if (byteBuffer.hasRemaining()) {
                    if (owner.tFirstInboundMs == 0L) {
                        owner.tFirstInboundMs = SystemClock.elapsedRealtime()
                        Log.d(TAG, "[c#${owner.connId}] RX first bytes dt=${owner.tFirstInboundMs - owner.tConnectStartMs}ms")
                    }
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    owner.rxLineBuf.append(String(bytes, StandardCharsets.UTF_8))

                    var idx = owner.rxLineBuf.indexOf("\n")
                    while (idx >= 0) {
                        val line = owner.rxLineBuf.substring(0, idx).trim()
                        if (line.isNotEmpty()) {
                            owner._incoming.tryEmit(line)
                            Log.d(TAG, "[c#${owner.connId}] RX line (${line.length}B) dt=${owner.dtSinceConnect()}ms")
                        }
                        owner.rxLineBuf.delete(0, idx + 1)
                        idx = owner.rxLineBuf.indexOf("\n")
                    }
                }
            } finally {
                byteBuffer.clear()
            }
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            Log.d(TAG, "[c#${owner.connId}] RX succeeded")
            owner._status.tryEmit("closed:0:rx_success")
            owner.rxRequest = null
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            Log.e(TAG, "[c#${owner.connId}] RX failed: ${error.message}", error)
            owner._status.tryEmit("failure:${error.message ?: "rx_failed"}")
            owner.rxRequest = null
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            Log.d(TAG, "[c#${owner.connId}] RX canceled")
            owner._status.tryEmit("closed:0:rx_canceled")
            owner.rxRequest = null
        }
    }

    // ─────────────────────────── TX callbacks ───────────────────────────

    private class TxCallback(private val owner: QuicClient) : BidirectionalStream.Callback() {
        override fun onStreamReady(stream: BidirectionalStream) {
            owner.tTxReadyMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "[c#${owner.connId}] TX ready dt=${owner.tTxReadyMs - owner.tConnectStartMs}ms")
            owner._status.tryEmit("connecting_tx")
            owner.primeUpload(stream)
        }

        override fun onResponseHeadersReceived(stream: BidirectionalStream, info: UrlResponseInfo) {
            val proto = info.negotiatedProtocol ?: "?"
            val code = info.httpStatusCode
            Log.d(TAG, "[c#${owner.connId}] TX headers proto=$proto code=$code")
            owner._status.tryEmit("open") // we consider overall “open” once TX is up; RX is already open.
            synchronized(owner.writerLock) {
                if (!owner.writeActive && owner.outQueue.isNotEmpty()) {
                    owner.writeActive = true
                    owner.exec.execute { owner.drainOne(stream) }
                }
            }
        }

        override fun onReadCompleted(stream: BidirectionalStream, info: UrlResponseInfo?, buffer: ByteBuffer, endOfStream: Boolean) {
            // TX is write-only; ignore any response body (some proxies may send keepalives)
            buffer.clear()
            if (endOfStream) {
                Log.d(TAG, "[c#${owner.connId}] TX got EOS")
            } else {
                try { stream.read(buffer) } catch (_: Throwable) {}
            }
        }

        override fun onWriteCompleted(stream: BidirectionalStream, info: UrlResponseInfo?, buffer: ByteBuffer, endOfStream: Boolean) {
            val t0 = owner.writeStartNs.remove(buffer)
            if (t0 != null) {
                val durMs = (System.nanoTime() - t0) / 1_000_000.0
                val inflight = synchronized(owner.writerLock) { owner.outQueue.size }
                Log.d(TAG, "[c#${owner.connId}] TX onWriteCompleted: ${"%.3f".format(durMs)}ms inflight=$inflight dt=${owner.dtSinceConnect()}ms")
            }
            owner.exec.execute { owner.drainOne(stream) }
        }

        override fun onSucceeded(stream: BidirectionalStream, info: UrlResponseInfo?) {
            Log.d(TAG, "[c#${owner.connId}] TX succeeded")
            owner._status.tryEmit("closed:0:tx_success")
            owner.txStream = null
            owner.txConnecting.set(false)
        }

        override fun onFailed(stream: BidirectionalStream, info: UrlResponseInfo?, error: CronetException) {
            Log.e(TAG, "[c#${owner.connId}] TX failed: ${error.message}", error)
            owner._status.tryEmit("failure:${error.message ?: "tx_failed"}")
            owner.txStream = null
            owner.txConnecting.set(false)
            synchronized(owner.writerLock) { owner.outQueue.clear(); owner.writeActive = false; owner.writeStartNs.clear() }
        }

        override fun onCanceled(stream: BidirectionalStream, info: UrlResponseInfo?) {
            Log.d(TAG, "[c#${owner.connId}] TX canceled")
            owner._status.tryEmit("closed:0:tx_canceled")
            owner.txStream = null
            owner.txConnecting.set(false)
            synchronized(owner.writerLock) { owner.outQueue.clear(); owner.writeActive = false; owner.writeStartNs.clear() }
        }
    }
}