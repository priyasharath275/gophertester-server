#!/usr/bin/env bash
set -euo pipefail
#
# run.sh – start Redis and the TLS-enabled FastAPI server
# Uses Hypercorn (HTTP/1.1 + HTTP/2 and optional HTTP/3/QUIC) if available,
# falls back to Uvicorn (TLS) otherwise.
#

PID_FILE="currentProcess.txt"
REDIS_PID_FILE="redis.pid"
LOG_FILE="server.log"

# Adjust these to match YOUR certificate (subdomain!)
CERT_PATH="/etc/letsencrypt/live/app.cv2x.org/fullchain.pem"
KEY_PATH="/etc/letsencrypt/live/app.cv2x.org/privkey.pem"

# Our app lives in main.py
APP_MODULE="${APP_MODULE:-main:app}"

REDIS_PORT=${REDIS_PORT:-6379}
REDIS_URL="redis://127.0.0.1:${REDIS_PORT}/0"
export REDIS_URL
export LOG_LEVEL="${LOG_LEVEL:-INFO}"

# ───────────────────────── 0) root no longer required ───────────────────────
# NOTE: this used to force-reexec itself with `sudo` to bind port 443.
# Instead, grant the venv's python/hypercorn the net_bind_service capability
# ONCE (so this script can run non-interactively from a git deploy hook):
#   sudo setcap 'cap_net_bind_service=+ep' $(readlink -f venv/bin/python3)
#   sudo setcap 'cap_net_bind_service=+ep' $(readlink -f venv/bin/hypercorn)

# ───────────────────────── 1) stop old server ───────────────────────────
if [[ -f "$PID_FILE" ]]; then
  OLD_PID=$(<"$PID_FILE" || true)
  if [[ -n "${OLD_PID:-}" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo ">> Stopping previous server (PID $OLD_PID)…"
    kill "$OLD_PID" || true
    sleep 1
  fi
  rm -f "$PID_FILE"
fi

# ───────────────────────── 2) stop old Redis  ───────────────────────────
if [[ -f "$REDIS_PID_FILE" ]]; then
  RPID=$(<"$REDIS_PID_FILE" || true)
  if [[ -n "${RPID:-}" ]] && kill -0 "$RPID" 2>/dev/null; then
    echo ">> Stopping previous redis-server (PID $RPID)…"
    kill "$RPID" || true
    sleep 1
  fi
  rm -f "$REDIS_PID_FILE"
fi

# ───────────────────────── 3) activate venv  ────────────────────────────
if [[ -f "venv/bin/activate" ]]; then
  # shellcheck disable=SC1091
  source venv/bin/activate
else
  echo "Error: virtual environment not found. Create one with:"
  echo "  python3 -m venv venv && source venv/bin/activate"
  echo "  pip install fastapi 'uvicorn[standard]' 'hypercorn[h3]' h2"
  exit 1
fi

# ───────────────────────── 4) start redis-server ────────────────────────
echo ">> Starting redis-server on port ${REDIS_PORT} …"
redis-server --port "${REDIS_PORT}" --daemonize yes
REDIS_REAL_PID=$(pgrep -f "redis-server.*:${REDIS_PORT}" | head -n1 || true)
if [[ -z "${REDIS_REAL_PID:-}" ]]; then
  echo "!! Failed to start redis-server"; exit 1
fi
echo "${REDIS_REAL_PID}" > "$REDIS_PID_FILE"
echo "redis-server PID ${REDIS_REAL_PID}"

# ───────────────────────── 5) free TCP 443 if busy ──────────────────────
echo ">> Ensuring TCP port 443 is free …"
OLD_TCP_PID=$(lsof -iTCP:443 -sTCP:LISTEN -t 2>/dev/null || true)
if [[ -n "${OLD_TCP_PID:-}" ]]; then
  echo "   → killing PID $OLD_TCP_PID"
  kill "$OLD_TCP_PID" || true
  sleep 1
fi

# ───────────────────────── 6) launch server ─────────────────────────────
if command -v hypercorn >/dev/null 2>&1; then
  echo ">> Starting Hypercorn (${APP_MODULE}) on TLS :443 (h1/h2) and QUIC :443 …"
  # HTTP/1.1 + HTTP/2 are negotiated over TLS on TCP:443 (requires 'h2' package).
  # HTTP/3 (QUIC) served on UDP:443 (requires aioquic; installed via 'hypercorn[h3]').
  nohup hypercorn "${APP_MODULE}" \
        --bind 0.0.0.0:443 \
        --certfile "$CERT_PATH" \
        --keyfile  "$KEY_PATH" \
        --quic-bind 0.0.0.0:443 \
        --log-level "${LOG_LEVEL,,}" \
        >> "$LOG_FILE" 2>&1 &
  NEW_PID=$!
else
  echo ">> Hypercorn not found; falling back to Uvicorn (TLS on TCP :443, no HTTP/2/3)…"
  nohup uvicorn "${APP_MODULE}" \
        --host 0.0.0.0 \
        --port 443 \
        --ssl-certfile "$CERT_PATH" \
        --ssl-keyfile  "$KEY_PATH" \
        --log-level "${LOG_LEVEL,,}" \
        >> "$LOG_FILE" 2>&1 &
  NEW_PID=$!
fi

echo "$NEW_PID" > "$PID_FILE"
echo "Server PID $NEW_PID  |  logs → $LOG_FILE"
