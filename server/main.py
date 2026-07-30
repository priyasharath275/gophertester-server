#!/usr/bin/env python3
"""
FastAPI server for GopherTester
- WebSocket path: /ws
- HTTP/2/3 JSON-Lines (duplex) path: /h3/stream
- Split HTTP/2/3 paths (proxy-safe):
    - Downlink (server→client): GET  /h3/down?phone=...
    - Uplink   (client→server): POST /h3/up?phone=...
- Logs: log.txt (rolling) with JSON-ish entries for easy grepping
- Time sync:
    client -> {"type":"timesync","seq":N,"t1":"<client ISO>"}
    server -> {"type":"timesync","seq":N,"t1":..., "t2":..., "t3":...}
    client -> {"type":"timesync_report","offset_ms":<int>,"rtt_ms":<int>}

Server routing rules (C/D/E from the notes):
- Never echo get_location_data back to the origin. Route strictly by target.destinationId.
- For any routed message, if sourceId == destinationId -> drop & log.
- Per-client outbound queues are unbounded (or effectively so): we backpressure instead of dropping.
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from datetime import datetime, timezone
from typing import Dict, Any, Optional, Callable, Awaitable

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import JSONResponse, StreamingResponse

# ───────────────────────── Logging (→ log.txt) ─────────────────────────
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FILE = Path(__file__).with_name("log.txt")
# Durable append-only store for BSM (Basic Safety Message) reports, so
# data lands here even if the receiving app isn't connected right now.
BSM_STORE_FILE = Path(__file__).with_name("bsm_store.jsonl")
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
LOG_MESSAGES = os.environ.get("LOG_MESSAGES", "0") == "1"

logger = logging.getLogger("gopher")
logger.setLevel(getattr(logging, LOG_LEVEL, logging.INFO))

_fmt = logging.Formatter(
    fmt="%(asctime)sZ\t%(levelname)s\t%(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)

_file = RotatingFileHandler(LOG_FILE, maxBytes=5_000_000, backupCount=3, encoding="utf-8")
_file.setFormatter(_fmt)
logger.addHandler(_file)

_console = logging.StreamHandler()
_console.setFormatter(_fmt)
logger.addHandler(_console)


def log_msg(event: str, **fields):
    """Compact JSON-like single-line event to make grepping easy."""
    try:
        payload = json.dumps(fields, ensure_ascii=False, separators=(",", ":"))
    except Exception:
        payload = str(fields)
    logger.info("%s %s", event, payload)


# ───────────────────────── Optional Redis ─────────────────────────
REDIS_URL = os.environ.get("REDIS_URL")
redis = None
try:
    if REDIS_URL:
        import redis.asyncio as _redis  # type: ignore
        redis = _redis.from_url(REDIS_URL, decode_responses=True)
        log_msg("redis_init", url=REDIS_URL)
except Exception as e:
    log_msg("redis_error", error=str(e))
    redis = None

# ───────────────────────── Envelope helpers ─────────────────────────
def get_env(obj: dict) -> dict:
    """Extract/normalize an envelope object from a message dict."""
    env = obj.get("envelope")
    if not isinstance(env, dict):
        env = {}
    stamps = env.get("stamps")
    if not isinstance(stamps, list):
        env["stamps"] = []
    return env


def add_stamp(env: dict, key: str, ms: Optional[int] = None) -> dict:
    """Return a copy of env with an extra {k, ms} stamp appended."""
    if not isinstance(env, dict):
        env = {}
    stamps = list(env.get("stamps") or [])
    stamps.append({"k": key, "ms": int(ms if ms is not None else now_ms())})
    out = dict(env)
    out["stamps"] = stamps
    return out


def stamp_ms(env: dict, key: str) -> Optional[int]:
    """Get the ms value for a given stamp key."""
    if not isinstance(env, dict):
        return None
    for it in env.get("stamps", []):
        try:
            if it.get("k") == key:
                return int(it.get("ms"))
        except Exception:
            pass
    return None


def compute_delays_from_env(env: dict) -> dict:
    """
    Compute path delays from envelope stamps when available.

    Returns keys (floats): delay_A_to_server_ms, delay_server_to_B_ms,
    delay_B_to_server_ms, server_rtt_B_ms (subset if some stamps missing).
    """
    a_send = stamp_ms(env, "A.send")
    c_rx   = stamp_ms(env, "srv.C.rx")
    c_tx   = stamp_ms(env, "srv.C.tx")
    b_rx   = stamp_ms(env, "B.rx")
    b_tx   = stamp_ms(env, "B.tx")
    d_rx   = stamp_ms(env, "srv.D.rx")

    out: Dict[str, Optional[float]] = {
        "delay_A_to_server_ms": (c_rx - a_send) if (a_send is not None and c_rx is not None) else None,
        "delay_server_to_B_ms": (b_rx - c_tx)   if (c_tx   is not None and b_rx is not None) else None,
        "delay_B_to_server_ms": (d_rx - b_tx)   if (b_tx   is not None and d_rx is not None) else None,
        "server_rtt_B_ms":      (d_rx - c_tx)   if (c_tx   is not None and d_rx is not None) else None,
    }
    return {k: float(v) for k, v in out.items() if v is not None}

# ───────────────────────── Time helpers ─────────────────────────
def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_iso(s: str) -> datetime:
    if s.endswith("Z"):
        s = s.replace("Z", "+00:00")
    return datetime.fromisoformat(s)


def ms_between(then_iso: str, now_iso: Optional[str] = None) -> float:
    a = parse_iso(then_iso)
    b = parse_iso(now_iso) if now_iso else datetime.now(timezone.utc)
    return (b - a).total_seconds() * 1000.0


def iso_to_ms(iso: str) -> int:
    try:
        return int(parse_iso(iso).timestamp() * 1000.0)
    except Exception:
        return 0


def now_ms() -> int:
    return int(time.time() * 1000.0)


# ───────────────────────── Transport abstraction ─────────────────────────
class Channel:
    async def send(self, text: str) -> None: ...
    async def close(self) -> None: ...


class WSChannel(Channel):
    def __init__(self, ws: WebSocket):
        self.ws = ws

    async def send(self, text: str) -> None:
        await self.ws.send_text(text)

    async def close(self) -> None:
        try:
            await self.ws.close()
        except Exception:
            pass


class H3Channel(Channel):
    """JSONL over HTTP stream: outbound lines are queued to the writer."""
    def __init__(self):
        # Unbounded: we apply backpressure on producers via await, no silent drops.
        self.q: asyncio.Queue[Optional[str]] = asyncio.Queue()

    async def send(self, text: str) -> None:
        await self.q.put(text)

    async def close(self) -> None:
        # Signal writer to finish
        await self.q.put(None)


# ───────────────────────── Peer (per-user writer) ─────────────────────────
class Peer:
    """Per-connection serializer + backpressure via a dedicated writer task."""
    def __init__(self, phone: str, ch: Channel):
        self.phone = phone
        self.ch = ch
        # Unbounded: never drop data frames; producers await put().
        self.send_q: asyncio.Queue[str] = asyncio.Queue()
        self.writer_task = asyncio.create_task(self._writer())

    async def _writer(self):
        try:
            while True:
                line = await self.send_q.get()  # FIFO + backpressure
                await self.ch.send(line)
        except asyncio.CancelledError:
            pass
        except Exception as e:
            log_msg("peer_writer_error", phone=self.phone, error=str(e))

    async def enqueue(self, obj: dict):
        line = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
        await self.send_q.put(line)

    async def close(self):
        try:
            await self.ch.close()
        except Exception:
            pass
        try:
            self.writer_task.cancel()
        except Exception:
            pass


# ───────────────────────── App & state ─────────────────────────
app = FastAPI(title="GopherTester Main Server")

# Connections map to Peer (wraps WS or H3 channel)
connections: Dict[str, Peer] = {}
last_seen: Dict[str, float] = {}          # epoch seconds
pending: Dict[str, Dict[str, Any]] = {}   # by request_id

# Per-phone clock info (server_time - phone_time)
clock_offset_ms: Dict[str, int] = {}
clock_rtt_ms: Dict[str, int] = {}

active_sessions: Dict[frozenset[str], float] = {}  # key=frozenset({a,b}) -> last_seen_epoch_secs
SESSION_TTL = 15      # seconds without traffic means drop

SWEEP_INTERVAL = 10
OFFLINE_AFTER = 45
PENDING_TTL = 120


async def send_to(user: Optional[str], obj: dict) -> bool:
    """Send a JSON object to a connected user (any transport)."""
    if not user:
        return False
    peer = connections.get(user)
    if not peer:
        return False
    try:
        await peer.enqueue(obj)
        return True
    except Exception as e:
        log_msg("send_to_error", to=user, error=str(e))
        return False


# ───────────────────────── HTTP access log ─────────────────────────
@app.middleware("http")
async def http_logger(request: Request, call_next):
    t0 = time.perf_counter()
    resp = None
    try:
        resp = await call_next(request)
        return resp
    finally:
        dt_ms = (time.perf_counter() - t0) * 1000.0
        log_msg(
            "http",
            method=request.method,
            path=str(request.url.path),
            status=getattr(resp, "status_code", "?"),
            ms=round(dt_ms, 1),
            ip=request.client.host if request.client else None,
        )


# ───────────────────────── Background sweepers ─────────────────────────
async def sweeper():
    while True:
        await asyncio.sleep(SWEEP_INTERVAL)
        now = time.time()

        # offline
        to_drop = [u for u, ts in list(last_seen.items()) if now - ts > OFFLINE_AFTER]
        if to_drop:
            log_msg("sweep_offline", users=to_drop)
        for u in to_drop:
            peer = connections.pop(u, None)
            last_seen.pop(u, None)
            clock_offset_ms.pop(u, None)
            clock_rtt_ms.pop(u, None)
            try:
                if peer:
                    await peer.close()
            except Exception:
                pass
        if redis:
            try:
                pipe = redis.pipeline()
                for u in list(connections.keys()):
                    pipe.setex(f"online:{u}", OFFLINE_AFTER, "1")
                await pipe.execute()
            except Exception as e:
                log_msg("redis_sweep_error", error=str(e))

        # stale pending
        stale = [rid for rid, info in list(pending.items()) if now - info.get("t_created", now) > PENDING_TTL]
        for rid in stale:
            pending.pop(rid, None)
        if stale:
            log_msg("sweep_pending", removed=stale)

        # stale sessions
        stale_keys = [k for k, ts in list(active_sessions.items()) if now - ts > SESSION_TTL]
        for k in stale_keys:
            active_sessions.pop(k, None)
        if stale_keys:
            log_msg("sweep_sessions", removed=[list(k) for k in stale_keys])


@app.on_event("startup")
async def _startup():
    log_msg("startup", log_file=str(LOG_FILE))
    asyncio.create_task(sweeper())


# ───────────────────────── HTTP info ─────────────────────────
@app.get("/health")
async def health():
    # local in-process connections
    online_local = set(connections.keys())

    # If Redis is configured, merge cluster-wide presence
    online_redis = set()
    if redis:
        try:
            keys = await redis.keys("online:*")
            online_redis = {k.split("online:", 1)[1] for k in keys}
        except Exception as e:
            log_msg("redis_health_error", error=str(e))

    online = sorted(online_local | online_redis)
    return {"status": "ok", "online": online, "count": len(online)}


@app.get("/")
async def root():
    return JSONResponse({"service": "GopherTester", "ws": "/ws", "h3": "/h3/stream", "online": len(connections)})


# ───────────────────────── Shared message handler ─────────────────────────
async def handle_message(
    obj: dict,
    phone: str,
    send_self: Callable[[dict], Awaitable[None]],
):
    msg_type = (obj.get("type") or "").strip().lower()
    target = obj.get("target") or {}
    action = (target.get("action") or "").strip().lower()
    req_id = obj.get("request_id")

    # Ensure request_id always exists so pending[...] has a reliable key
    if not req_id:
        req_id = f"{phone}-{now_ms()}"
        obj["request_id"] = req_id

    if logger.isEnabledFor(logging.DEBUG):
        logger.debug("MSG %s %s", action or msg_type, json.dumps(obj, ensure_ascii=False))

    # ───── strict self-route guard for routed actions
    if action in ("send_location_data", "reply_location_data", "get_location_data", "session_control"):
        src = target.get("sourceId") or phone
        dst = target.get("destinationId")
        if not dst:
            log_msg("drop_no_destination", action=action, request_id=req_id, src=src)
            return
        if dst == src:
            log_msg("drop_self_route", action=action, request_id=req_id, phone=phone)
            return

    # ───── timesync ping-pong
    if msg_type == "timesync" or action == "timesync":
        seq = obj.get("seq")
        t1 = obj.get("t1")
        t2 = iso_now()
        reply = {"type": "timesync", "seq": seq, "t1": t1, "t2": t2, "t3": iso_now()}
        await send_self(reply)
        log_msg("timesync", phone=phone, seq=seq)
        return

    # ───── client reports offset/rtt (server_time - phone_time)
    if msg_type == "timesync_report":
        off = int(obj.get("offset_ms", 0) or 0)
        rtt = int(obj.get("rtt_ms", 0) or 0)
        clock_offset_ms[phone] = off
        clock_rtt_ms[phone] = rtt
        log_msg("timesync_report", phone=phone, offset_ms=off, rtt_ms=rtt)
        return

    # ───── heartbeat
    if action == "heartbeat":
        log_msg("heartbeat", phone=phone)
        return

    # ───── A1/A2 existence
    if action == "user_existence_inquiry":
        user_b = target.get("destinationId")
        status = "online" if (user_b and user_b in connections) else "offline"
        log_msg("existence_inquiry", from_=phone, to=user_b, status=status, request_id=req_id)
        reply = {
            "request_id": req_id,
            "timestamp": iso_now(),
            "status": "success",
            "code": 200,
            "message": "The user exists and is online." if status == "online" else "The user exists but is offline.",
            "target": {"sourceId": "server", "destinationId": phone, "action": "user_existence_inquiry"},
            "payload": {"userId": user_b, "userStatus": status},
        }
        await send_self(reply)
        return

    # ───── C: A -> Server (towards B)
    if action == "send_location_data":
        user_a = target.get("sourceId") or phone
        user_b = target.get("destinationId")
        ts_a_iso = obj.get("timestamp") or ""

        # Stamp envelope at server receive & send
        env_in   = get_env(obj)
        env_c_rx = add_stamp(env_in, "srv.C.rx", now_ms())

        # Prefer A’s offset if known; else simple difference
        t_now_before = now_ms()
        if user_a in clock_offset_ms:
            t_a_send_srv = iso_to_ms(ts_a_iso) + int(clock_offset_ms[user_a])
            delay_a_to_server = max(0.0, float(t_now_before - t_a_send_srv))
            method = "offset"
        else:
            delay_a_to_server = float(ms_between(ts_a_iso))
            method = "naive"

        pending[req_id] = {
            "a": user_a,
            "b": user_b,
            "delay_A_to_server_ms": delay_a_to_server,  # fallback if env missing
            "ts_a": ts_a_iso,
            "t_created": time.time(),
        }
        log_msg(
            "send_location",
            request_id=req_id,
            a=user_a,
            b=user_b,
            delay_A_to_server_ms=round(delay_a_to_server, 3),
            method=method,
        )

        forward_c = {
            "request_id": req_id,
            "timestamp": iso_now(),
            "target": {"sourceId": user_a, "destinationId": user_b, "action": "reply_location_data"},
            "payload": {"userLocation": (obj.get("payload") or {}).get("userLocation")},
        }
        if "msg_no" in obj:
            forward_c["msg_no"] = obj["msg_no"]

        # Stamp srv.C.tx right before forwarding
        env_c_tx = add_stamp(env_c_rx, "srv.C.tx", now_ms())
        forward_c["envelope"] = env_c_tx

        if user_b and await send_to(user_b, forward_c):
            pending[req_id]["t_c_sent_ms"] = now_ms()  # keep for fallback
            log_msg("forward_to_B", request_id=req_id, to=user_b, ok=True)
        else:
            reply = {
                "request_id": req_id,
                "timestamp": iso_now(),
                "status": "success",
                "code": 200,
                "message": "The user exists but is offline.",
                "target": {"sourceId": "server", "destinationId": phone, "action": "user_existence_inquiry"},
                "payload": {"userId": user_b, "userStatus": "offline"},
            }
            await send_self(reply)
            log_msg("forward_to_B", request_id=req_id, to=user_b, ok=False)
        return

    # ───── D: B -> Server (reply with location)
    if action == "reply_location_data":
        info = pending.get(req_id) or {}
        user_b = target.get("sourceId") or phone
        dst_user = target.get("destinationId") or info.get("a")  # strict route by destinationId; fallback to pending
        t_now = now_ms()

        # Envelope: add srv.D.rx on arrival
        env_in = get_env(obj)
        env_d_rx = add_stamp(env_in, "srv.D.rx", t_now)

        # Existing clientTimes (still used as fallback)
        payload = obj.get("payload") or {}
        client_times = (payload.get("clientTimes") or {}) if isinstance(payload, dict) else {}

        # Derive delays from envelope (preferred)
        env_delays = compute_delays_from_env(env_d_rx)

        # Fallbacks for server->B and B->server if some stamps are missing
        t_c_sent_ms = info.get("t_c_sent_ms")

        delay_server_to_b = env_delays.get("delay_server_to_B_ms")
        if delay_server_to_b is None and t_c_sent_ms:
            # If we got recv_c_ts from client, use offset; else RTT/2
            offset_b = int(clock_offset_ms.get(user_b, 0))
            recv_c_iso = client_times.get("recv_c_ts") or ""
            if recv_c_iso:
                t_b_recv_c_srv = iso_to_ms(recv_c_iso) + offset_b
                delay_server_to_b = max(0.0, float(t_b_recv_c_srv - t_c_sent_ms))
            else:
                delay_server_to_b = max(0.0, float((t_now - t_c_sent_ms) / 2.0))

        delay_b_to_server = env_delays.get("delay_B_to_server_ms")
        if delay_b_to_server is None and t_c_sent_ms is not None:
            # Approx using RTT minus s->B minus processing if we have them
            proc_ms = None
            if isinstance(client_times.get("proc_ms"), (int, float)):
                proc_ms = float(client_times["proc_ms"])
            else:
                send_d_iso = client_times.get("send_d_ts") or ""
                recv_c_iso = client_times.get("recv_c_ts") or ""
                if send_d_iso and recv_c_iso:
                    proc_ms = float((iso_to_ms(send_d_iso) - iso_to_ms(recv_c_iso)))
            if proc_ms is not None and delay_server_to_b is not None:
                rtt_total = float(t_now - t_c_sent_ms)
                delay_b_to_server = max(0.0, rtt_total - delay_server_to_b - max(0.0, proc_ms))

        delay_a_to_server = env_delays.get("delay_A_to_server_ms")
        if delay_a_to_server is None:
            delay_a_to_server = float(info.get("delay_A_to_server_ms", 0.0))

        server_rtt_b = env_delays.get("server_rtt_B_ms")
        if server_rtt_b is None:
            server_rtt_b = float(t_now - t_c_sent_ms) if t_c_sent_ms else 0.0

        # NEW: B's own time-sync metrics (strings), with server-side fallback if missing
        b_off = (payload.get("b_offset_ms") if isinstance(payload, dict) else None) or (
            str(int(clock_offset_ms[user_b])) if user_b in clock_offset_ms else None
        )
        b_rtt = (payload.get("b_ping_rtt_ms") if isinstance(payload, dict) else None) or (
            str(int(clock_rtt_ms[user_b])) if user_b in clock_rtt_ms else None
        )

        if dst_user and user_b:
            active_sessions[frozenset((dst_user, user_b))] = time.time()

        # E: deliver to A (with envelope propagated so A can compute too)
        if dst_user and dst_user in connections:
            forward_e = {
                "request_id": req_id,
                "timestamp": iso_now(),
                "target": {"sourceId": user_b, "destinationId": dst_user, "action": "get_location_data"},
                "payload": {
                    "userLocation": payload.get("userLocation"),
                    "delays": {
                        "delay_A_to_server_ms": f"{float(delay_a_to_server or 0.0):.3f}",
                        "delay_server_to_B_ms": f"{float(delay_server_to_b or 0.0):.3f}",
                        "delay_B_to_server_ms": f"{float(delay_b_to_server or 0.0):.3f}",
                    },
                    "server_rtt_B_ms": f"{float(server_rtt_b or 0.0):.3f}",

                    # NEW: echo B’s time-sync stats to A (strings, if available)
                    "b_offset_ms": b_off,
                    "b_ping_rtt_ms": b_rtt,
                },
                "envelope": env_d_rx,
            }
            if "msg_no" in obj:
                forward_e["msg_no"] = obj["msg_no"]
            await send_to(dst_user, forward_e)

            log_msg(
                "deliver_to_A",
                request_id=req_id,
                to=dst_user,
                delays={
                    "A->S": round(float(delay_a_to_server or 0.0), 3),
                    "S->B": round(float(delay_server_to_b or 0.0), 3),
                    "B->S": round(float(delay_b_to_server or 0.0), 3),
                    "S->B->S": round(float(server_rtt_b or 0.0), 3),
                },
            )
        else:
            log_msg("deliver_to_A_failed", request_id=req_id, to=dst_user)
        return


    # ───── BSM (Basic Safety Message) reports, e.g. from WezzOn at 10Hz.
    # Always persisted server-side (durable), and forwarded live to the
    # destination if it happens to be connected right now.
    if action == "bsm_data":
        src = target.get("sourceId") or phone
        dst = target.get("destinationId")

        try:
            record = {
                "stored_at": iso_now(),
                "request_id": req_id,
                "from": src,
                "to": dst,
                "payload": obj.get("payload"),
            }
            with BSM_STORE_FILE.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        except Exception as e:
            log_msg("bsm_store_error", error=str(e))

        delivered = False
        if dst and dst != src:
            delivered = await send_to(dst, obj)

        # Echo the message back to the sender itself, so the sending client
        # (e.g. WezzOn) can display what the server actually received, at
        # the same cadence it was sent — a round-trip confirmation loop.
        echoed = False
        try:
            echoed = await send_to(src, obj)
        except Exception as e:
            log_msg("bsm_echo_error", src=src, error=str(e))

        log_msg("bsm_data", request_id=req_id, from_=src, to=dst, delivered=delivered, echoed=echoed)
        return

    # ───── (Safety) If a client ever sends get_location_data, route strictly by destinationId (no echo).
    if action == "get_location_data":
        src = target.get("sourceId") or phone
        dst = target.get("destinationId")
        if not dst or dst == src:
            log_msg("drop_get_location_bad", src=src, dst=dst, request_id=req_id)
            return
        ok = await send_to(dst, obj)
        log_msg("forward_get_location", src=src, dst=dst, ok=ok, request_id=req_id)
        return

    # ───── Admin: pair users A->B (push down to clients)
    if action == "admin_pair":
        pairs = ((obj.get("payload") or {}).get("pairs") or []) if isinstance(obj.get("payload"), dict) else []
        for p in pairs:
            try:
                a, b = p[0], p[1]
            except Exception:
                continue
            forward = {
                "request_id": req_id or f"adm-{int(time.time()*1000)}",
                "timestamp": iso_now(),
                "target": {"sourceId": "server", "destinationId": a, "action": "admin_connect"},
                "payload": {"userId": b},
            }
            ok = await send_to(a, forward)
            log_msg("admin_pair_connect", a=a, b=b, ok=ok)
        return

    # ───── Admin: list users
    if action == "admin_list":
        online_local = set(connections.keys())
        online_redis = set()
        if redis:
            try:
                keys = await redis.keys("online:*")
                online_redis = {k.split("online:", 1)[1] for k in keys}
            except Exception as e:
                log_msg("redis_health_error", error=str(e))
        online = sorted(online_local | online_redis)
        reply = {
            "request_id": req_id or f"adminlist-{now_ms()}",
            "timestamp": iso_now(),
            "target": {"sourceId": "server", "destinationId": phone, "action": "admin_list"},
            "payload": {"online": online},
        }
        await send_self(reply)
        log_msg("admin_list_reply", to=phone, count=len(online))
        return

    # ───── Admin: stop paired users (tell both sides to stop)
    if action == "admin_stop":
        pairs = ((obj.get("payload") or {}).get("pairs") or []) if isinstance(obj.get("payload"), dict) else []
        for p in pairs:
            try:
                a, b = p[0], p[1]
            except Exception:
                continue

            # remove the active session right away (undirected key)
            active_sessions.pop(frozenset((a, b)), None)

            # tell both sides to stop
            for src, dst in ((a, b), (b, a)):
                forward = {
                    "request_id": req_id or f"admstop-{int(time.time()*1000)}",
                    "timestamp": iso_now(),
                    "target": {"sourceId": "server", "destinationId": src, "action": "session_control"},
                    "payload": {"cmd": "stop", "peer": dst},
                }
                ok = await send_to(src, forward)
                log_msg("admin_stop_forward", src=src, dst=dst, ok=ok)
        return

    # ───── Session control
    if action == "session_control":
        user_src = target.get("sourceId") or phone
        user_dst = target.get("destinationId")
        payload = obj.get("payload") or {}
        log_msg("session_control_in", from_=user_src, to=user_dst, payload=payload, request_id=req_id)

        # Drop the session immediately if someone sends stop
        if isinstance(payload, dict) and (payload.get("cmd") or "").lower() == "stop" and user_dst:
            active_sessions.pop(frozenset((user_src, user_dst)), None)

        forward = {
            "request_id": req_id,
            "timestamp": iso_now(),
            "target": {"sourceId": user_src, "destinationId": user_dst, "action": "session_control"},
            "payload": payload,
        }
        ok = await send_to(user_dst, forward)
        log_msg("session_control_forwarded", to=user_dst, ok=ok)
        return

    # ───── Admin: sessions list
    if action == "admin_sessions":
        pairs = []
        for k in list(active_sessions.keys()):
            a, b = sorted(list(k))
            pairs.append([a, b])
        reply = {
            "request_id": req_id or f"adminsessions-{now_ms()}",
            "timestamp": iso_now(),
            "target": {"sourceId": "server", "destinationId": phone, "action": "admin_sessions"},
            "payload": {"sessions": pairs},
        }
        await send_self(reply)
        log_msg("admin_sessions_reply", to=phone, count=len(pairs))
        return

    # ───── F: ack
    if action == "acknowledgment":
        pending.pop(req_id, None)
        log_msg("ack", request_id=req_id, from_=phone)
        return

    # Unknown
    log_msg("unknown_action", phone=phone, action=action or msg_type)


# ───────────────────────── WebSocket endpoint ─────────────────────────
@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()

    phone = websocket.query_params.get("phone") or websocket.headers.get("x-user-phone")
    if not phone:
        phone = f"anon-{str(id(websocket))[-6:]}"
    ch = WSChannel(websocket)
    peer = Peer(phone, ch)
    connections[phone] = peer
    last_seen[phone] = time.time()
    if redis:
        try:
            await redis.setex(f"online:{phone}", OFFLINE_AFTER, "1")
        except Exception as e:
            log_msg("redis_set_error", phone=phone, error=str(e))

    # Tell client its identity
    await peer.enqueue({"type": "identity", "phone": phone})
    log_msg(
        "ws_connect",
        phone=phone,
        ip=getattr(websocket.client, "host", None),
        port=getattr(websocket.client, "port", None),
    )

    try:
        while True:
            msg_text = await websocket.receive_text()
            try:
                obj = json.loads(msg_text)
            except Exception as e:
                log_msg("ws_bad_json", phone=phone, error=str(e))
                continue

            last_seen[phone] = time.time()
            if redis:
                try:
                    await redis.setex(f"online:{phone}", OFFLINE_AFTER, "1")
                except Exception as e:
                    log_msg("redis_refresh_error", phone=phone, error=str(e))

            async def _send_self(payload: dict) -> None:
                await peer.enqueue(payload)

            await handle_message(obj, phone, _send_self)

    except WebSocketDisconnect:
        log_msg("ws_disconnect", phone=phone)
    finally:
        try:
            peer_in_map = connections.get(phone)
            if peer_in_map is peer:
                connections.pop(phone, None)
        except Exception:
            pass
        try:
            await peer.close()
        except Exception:
            pass
        last_seen.pop(phone, None)
        clock_offset_ms.pop(phone, None)
        clock_rtt_ms.pop(phone, None)
        if redis:
            try:
                await redis.delete(f"online:{phone}")
            except Exception as e:
                log_msg("redis_del_error", phone=phone, error=str(e))


# ───────────────────────── HTTP/2/3 JSON-Lines endpoint (duplex) ─────────────────────────
@app.post("/h3/stream")
async def h3_stream(request: Request):
    """
    Keep the response open and stream JSONL to the client (outbound),
    while reading JSON texts from the request body (inbound).
    Works best over HTTP/2 or HTTP/3.

    IMPORTANT: Only the reader() consumes request.stream(). The writer() does NOT
    call request.is_disconnected(); StreamingResponse cancels the generator when
    the client disconnects. This avoids competing for ASGI receive events.
    """
    phone = request.query_params.get("phone") or request.headers.get("x-user-phone") or f"anon-{id(request)%10_000:04d}"
    ch = H3Channel()
    peer = Peer(phone, ch)
    connections[phone] = peer
    last_seen[phone] = time.time()
    if redis:
        try:
            await redis.setex(f"online:{phone}", OFFLINE_AFTER, "1")
        except Exception as e:
            log_msg("redis_set_error", phone=phone, error=str(e))

    # Send identity immediately (will flush once writer starts)
    await peer.enqueue({"type": "identity", "phone": phone})
    log_msg("h3_connect", phone=phone, ip=getattr(request.client, "host", None))

    async def reader():
        """Consume JSON texts from the request body. Robust to missing newlines."""
        buf = ""
        decoder = json.JSONDecoder()
        try:
            async for chunk in request.stream():
                last_seen[phone] = time.time()
                try:
                    buf += chunk.decode("utf-8", "ignore")
                except Exception:
                    continue

                # Pull as many complete JSON values out of 'buf' as we can.
                i = 0
                n = len(buf)
                while True:
                    # Skip whitespace between JSON texts
                    while i < n and buf[i].isspace():
                        i += 1
                    if i >= n:
                        break
                    try:
                        obj, j = decoder.raw_decode(buf, i)  # parses one complete JSON value
                    except ValueError:
                        # Incomplete JSON at the end; keep it for the next chunk.
                        break
                    # Successfully decoded [i:j)
                    i = j
                    async def _send_self(payload: dict) -> None:
                        await peer.enqueue(payload)
                    await handle_message(obj, phone, _send_self)

                # Trim processed prefix to keep buffer small
                if i > 0:
                    buf = buf[i:]
                    n = len(buf)
        except Exception as e:
            log_msg("h3_reader_error", phone=phone, error=str(e))
        finally:
            log_msg("h3_reader_eos", phone=phone)

    async def writer():
        """Yield queued outbound lines to the client; cleanup on disconnect."""
        # kick headers early (forces headers to flush)
        yield ""
        try:
            while True:
                item = await ch.q.get()
                if item is None:  # our channel was closed
                    break
                yield item + "\n"
        except asyncio.CancelledError:
            # client disconnected; fall through to cleanup
            pass
        finally:
            # cleanup connection bookkeeping
            try:
                peer_in_map = connections.get(phone)
                if peer_in_map is peer:
                    connections.pop(phone, None)
            except Exception:
                pass
            try:
                await peer.close()
            except Exception:
                pass
            last_seen.pop(phone, None)
            clock_offset_ms.pop(phone, None)
            clock_rtt_ms.pop(phone, None)
            if redis:
                try:
                    await redis.delete(f"online:{phone}")
                except Exception as e:
                    log_msg("redis_del_error", phone=phone, error=str(e))
            log_msg("h3_disconnect", phone=phone)

    # Start reading in background; return a streaming response for outbound
    asyncio.create_task(reader())
    headers = {
        "Cache-Control": "no-store",
        "X-Accel-Buffering": "no",  # nginx: disable buffering
        # NOTE: Do not send "Connection: keep-alive" on HTTP/2/3
    }
    return StreamingResponse(writer(), media_type="application/jsonl; charset=utf-8", headers=headers)


# ───────────────────────── Split HTTP/2/3 endpoints (proxy-safe) ─────────────────────────
@app.get("/h3/down")
async def h3_down(request: Request):
    phone = request.query_params.get("phone") or request.headers.get("x-user-phone") or f"anon-{id(request)%10_000:04d}"
    ch = H3Channel()
    peer = Peer(phone, ch)
    connections[phone] = peer
    last_seen[phone] = time.time()
    if redis:
        try:
            await redis.setex(f"online:{phone}", OFFLINE_AFTER, "1")
        except Exception as e:
            log_msg("redis_set_error", phone=phone, error=str(e))

    await peer.enqueue({"type": "identity", "phone": phone})
    log_msg("h3_down_connect", phone=phone, ip=getattr(request.client, "host", None))

    async def writer():
        yield ""  # flush headers
        try:
            while True:
                item = await ch.q.get()
                if item is None:
                    break
                yield item + "\n"
        except asyncio.CancelledError:
            pass
        finally:
            try:
                if connections.get(phone) is peer:
                    connections.pop(phone, None)
            except Exception:
                pass
            try:
                await peer.close()
            except Exception:
                pass
            last_seen.pop(phone, None)
            clock_offset_ms.pop(phone, None)
            clock_rtt_ms.pop(phone, None)
            if redis:
                try:
                    await redis.delete(f"online:{phone}")
                except Exception as e:
                    log_msg("redis_del_error", phone=phone, error=str(e))
            log_msg("h3_down_disconnect", phone=phone)

    headers = {"Cache-Control": "no-store", "X-Accel-Buffering": "no"}
    return StreamingResponse(writer(), media_type="application/jsonl; charset=utf-8", headers=headers)


@app.post("/h3/up")
async def h3_up(request: Request):
    phone = request.query_params.get("phone") or request.headers.get("x-user-phone") or f"anon-{id(request)%10_000:04d}"
    log_msg("h3_up_connect", phone=phone, ip=getattr(request.client, "host", None))

    buf = ""
    try:
        async for chunk in request.stream():
            last_seen[phone] = time.time()
            try:
                part = chunk.decode("utf-8", "ignore")
            except Exception:
                part = ""
            if not part:
                continue
            buf += part
            while "\n" in buf:
                line, buf = buf.split("\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception as e:
                    log_msg("h3_up_bad_json", phone=phone, error=str(e))
                    continue

                async def _send_self(payload: dict) -> None:
                    # route back to this phone’s downlink if connected
                    await send_to(phone, payload)

                await handle_message(obj, phone, _send_self)
    except Exception as e:
        log_msg("h3_up_reader_error", phone=phone, error=str(e))
    finally:
        log_msg("h3_up_disconnect", phone=phone)
    return {"ok": True}


# ───────────────────────── Entrypoint (uvicorn) ─────────────────────────
if __name__ == "__main__":
    import uvicorn

    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "8080"))
    reload = os.environ.get("RELOAD", "false").lower() == "true"

    # Note: for true HTTP/2 or HTTP/3 with /h3/stream or /h3/down,/h3/up, prefer Hypercorn:
    #   hypercorn main:app --bind 0.0.0.0:8080 --h2
    # For HTTP/3:
    #   hypercorn main:app --quic-bind 0.0.0.0:8443 --certfile cert.pem --keyfile key.pem
    uvicorn.run("main:app", host=host, port=port, reload=reload, log_level=LOG_LEVEL.lower())
