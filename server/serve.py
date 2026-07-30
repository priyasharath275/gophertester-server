"""
Launches Hypercorn programmatically instead of via the `hypercorn` CLI,
so we can disable HTTP/2 ALPN on the TCP:443 listener.

Why: HTTP/2 does not support the classic `Connection: Upgrade` /
`Upgrade: websocket` handshake that WebSocket clients (OkHttp, browsers,
etc.) rely on for /ws. RFC 8441 defines WebSockets-over-HTTP/2, but it
isn't universally supported client-side, so when Hypercorn's default ALPN
list (["h2", "http/1.1"]) lets a client negotiate h2, /ws breaks with a
non-101 response instead of upgrading.

Fix: restrict the TCP:443 listener's ALPN list to http/1.1 only, so
WebSocket upgrades always work. HTTP/3 (QUIC) on UDP:443 is negotiated
completely separately from this and is unaffected.
"""
import asyncio
import os

from hypercorn.asyncio import serve
from hypercorn.config import Config

from GopherTester.server.serve import app

config = Config()
config.bind = ["0.0.0.0:443"]
config.quic_bind = ["0.0.0.0:443"]
config.certfile = os.environ["CERT_PATH"]
config.keyfile = os.environ["KEY_PATH"]
config.loglevel = os.environ.get("LOG_LEVEL", "INFO")

log_file = os.environ.get("LOG_FILE", "server.log")
config.accesslog = log_file
config.errorlog = log_file

# The important line: only offer HTTP/1.1 on the TCP listener so
# WebSocket (/ws) upgrades work reliably.
config.alpn_protocols = ["http/1.1"]

asyncio.run(serve(app, config))
