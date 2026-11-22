#!/usr/bin/env python3
"""P9.0's throwaway signaling relay: one HTTP room, JSON messages (ADR 0028).

Production signaling rides TLP over the device's HA connection (ADR 0025
decision 4). The spike has no HA path yet, so it needs *some* way to hand an
offer, an answer and ICE candidates between two tablets, and that is all this
does. It carries no audio: the media goes device to device over WebRTC on the
LAN (packet question 2).

Two clients in one room, each with a role (`call`, `answer`):

    POST /send?room=R&role=C   body: one JSON message -> {"seq": n}
    GET  /poll?room=R&role=C&since=N
         -> {"server_ms": ms, "seq": n, "messages": [[seq, from, body], ...]}

`server_ms` is the relay's wall clock at the moment the poll was answered; the
spike apps log it beside their own elapsed clock, which is how the packet's
latency figure cancels the two devices' independent clocks. `seq` is the room's
highest sequence number, so a client's cursor never sits on its own messages.

Standard library only (AGENTS.md rule 7: nothing fetched by hand).

    python3 tools/device/intercom_relay.py --port 8099
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_BODY = 256 * 1024
KEEP_PER_ROOM = 4000


class Rooms:
    """Sequence-numbered message lists, one per room, shared by both clients."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._rooms: dict[str, list[tuple[int, str, str]]] = {}
        self._seq: dict[str, int] = {}

    def add(self, room: str, role: str, body: str) -> int:
        with self._lock:
            seq = self._seq.get(room, 0) + 1
            self._seq[room] = seq
            messages = self._rooms.setdefault(room, [])
            messages.append((seq, role, body))
            if len(messages) > KEEP_PER_ROOM:
                del messages[: len(messages) - KEEP_PER_ROOM]
            return seq

    def since(self, room: str, role: str, cursor: int) -> tuple[int, list[tuple[int, str, str]]]:
        with self._lock:
            seq = self._seq.get(room, 0)
            picked = [m for m in self._rooms.get(room, []) if m[0] > cursor and m[1] != role]
            return seq, picked


ROOMS = Rooms()


class Handler(BaseHTTPRequestHandler):
    server_version = "tabula-intercom-spike-relay"

    def _reply(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _query(self) -> tuple[str, str, dict]:
        q = parse_qs(urlparse(self.path).query)
        room = (q.get("room") or [""])[0]
        role = (q.get("role") or [""])[0]
        return room, role, q

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/send":
            self._reply(404, {"error": "no such path"})
            return
        room, role, _ = self._query()
        if not room or not role:
            self._reply(400, {"error": "room and role are required"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self._reply(400, {"error": "empty or oversized body"})
            return
        body = self.rfile.read(length).decode("utf-8", "replace")
        seq = ROOMS.add(room, role, body)
        sys.stderr.write(f"relay: {room} {role} #{seq} {len(body)}B\n")
        self._reply(200, {"seq": seq})

    def do_GET(self) -> None:
        if urlparse(self.path).path != "/poll":
            self._reply(404, {"error": "no such path"})
            return
        room, role, q = self._query()
        if not room or not role:
            self._reply(400, {"error": "room and role are required"})
            return
        cursor = int((q.get("since") or ["0"])[0] or 0)
        seq, messages = ROOMS.since(room, role, cursor)
        self._reply(
            200,
            {
                "server_ms": int(time.time() * 1000),
                "seq": seq,
                "messages": [[m[0], m[1], m[2]] for m in messages],
            },
        )


def main() -> int:
    parser = argparse.ArgumentParser(description="P9.0 intercom signaling relay")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    sys.stderr.write(f"relay: listening on http://{args.host}:{args.port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
