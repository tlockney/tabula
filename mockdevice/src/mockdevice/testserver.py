"""In-process TLP server for tests: the integration's side of docs/PROTOCOL.md, minus HA.

It speaks HA's WebSocket envelope and auth handshake, the pairing views and /auth/token, and
validates every frame it receives strictly against the schema, so a test fails as soon as the
device sends something the protocol does not allow. It is a test double, not a reference
integration: no persistence, no rate limits, one HA instance per object.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import secrets
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from aiohttp import WSMsgType, web

from mockdevice import hacore
from mockdevice.codec import Codec, Frame, FrameError
from mockdevice.constants import (
    ACCESS_TOKEN_LIFETIME,
    CLAIM_POLL_INTERVAL,
    PAIR_CODE_ATTEMPTS,
    PAIR_PENDING_TTL,
    PROTOCOL_VERSION,
    SCREENSHOT_MAX_BYTES,
    VOICE_CHUNK_BYTES,
)

SUPPORTED_PROTOCOLS = {PROTOCOL_VERSION}
V1_CAPABILITIES = {
    "screen",
    "brightness",
    "adaptive_brightness",
    "screensaver",
    "notifications",
    "audio",
    "battery",
    "navigation",
    "logs",
    "screenshot",
    "illuminance",
    "voice",
}


@dataclass
class Pending:
    device_id: str
    code: str
    nonce_hash: str
    confirmed_token: str | None = None
    attempts: int = 0


@dataclass
class Session:
    ws: web.WebSocketResponse
    device_id: str
    registered: bool = False
    superseded: bool = False
    subscription_id: int | None = None
    last_id: int = 0
    # Voice turns (P7.2): the binary handler IDs this connection registered and
    # the PCM that arrived for each, as HA's ActiveConnection would hold them.
    binary_handlers: dict[int, list[bytes]] = field(default_factory=dict)


@dataclass
class DeviceRecord:
    device_id: str
    ha_device_id: str
    refresh_token: str
    state: dict[str, Any] = field(default_factory=dict)
    capabilities: list[str] = field(default_factory=list)
    current: Session | None = None
    events: list[Frame] = field(default_factory=list)
    logs: list[dict[str, Any]] = field(default_factory=list)
    connections: int = 0
    # The latest screenshot upload (P3.13): its bytes, the command it belongs
    # to, and whether it arrived while that command was still open.
    last_upload: bytes | None = None
    upload_command_id: str | None = None
    upload_before_result: bool = False
    # Feature sections of this device's /test/report entry, keyed by feature.
    # Each feature writes its own key where it handles the device, so the
    # report itself never needs editing (keeps parallel changes apart).
    report: dict[str, Any] = field(default_factory=dict)


class TestServer:
    """Run with `async with TestServer() as server:`; `server.url` is the base URL."""

    __test__ = False  # not a pytest class

    def __init__(
        self,
        codec: Codec | None = None,
        accepted_capabilities: set[str] | None = None,
        settings_revision: int = 0,
        poll_interval: int = int(CLAIM_POLL_INTERVAL),
        host: str = "127.0.0.1",
        port: int = 0,
    ) -> None:
        self.poll_interval = poll_interval
        self.host = host
        self.port = port
        self.codec = codec or Codec()
        self.accepted = accepted_capabilities or V1_CAPABILITIES
        self.settings_revision = settings_revision
        self.pending: dict[str, Pending] = {}
        self.devices: dict[str, DeviceRecord] = {}
        self.access: dict[str, str] = {}  # access token -> device_id
        self.received: list[tuple[str, Frame]] = []
        self.problems: list[str] = []
        self._results: dict[tuple[int, str], asyncio.Future[Frame]] = {}
        self.pending_screenshots: dict[str, str] = {}  # command_id -> device_id (P3.13)
        self._registered = asyncio.Condition()
        # HA core's screensaver-data commands (ADR 0017), on the same socket
        self.ha = hacore.HaCore()
        app = web.Application()
        app.router.add_post("/auth/token", self._token)
        app.router.add_get("/api/websocket", self._websocket)
        app.router.add_post("/api/tabula/pair", self._pair)
        app.router.add_post("/api/tabula/pair/claim", self._claim)
        app.router.add_post("/api/tabula/screenshot", self._screenshot)
        # Test hooks (not part of TLP): what the Python tests and the Android
        # JVM integration test drive the server with over HTTP.
        # A hook is any `_test_<name>` method, served at /test/<name>, so adding
        # one touches no shared list.
        app.router.add_route("*", "/test/{hook}", self._test_hook)
        self._runner = web.AppRunner(app)
        self.url = ""

    async def __aenter__(self) -> TestServer:
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        # aiohttp has no public accessor for the port bound to 0; test-only use of a private.
        port = site._server.sockets[0].getsockname()[1]  # type: ignore[union-attr]
        self.url = f"http://{self.host}:{port}"
        return self

    async def __aexit__(self, *exc: object) -> None:
        for record in self.devices.values():
            if record.current:
                await record.current.ws.close()
        await self._runner.cleanup()

    # --- test controls ----------------------------------------------------

    def add_device(self, device_id: str) -> DeviceRecord:
        """Create a paired device directly (skips the pairing views)."""
        record = DeviceRecord(
            device_id=device_id,
            ha_device_id=secrets.token_hex(16),
            refresh_token=secrets.token_urlsafe(48),
        )
        self.devices[device_id] = record
        return record

    def confirm(self, device_id: str, code: str) -> bool:
        """What the user does in the config flow: type the code shown on the device."""
        pending = self.pending[device_id]
        if code != pending.code:
            pending.attempts += 1
            if pending.attempts >= PAIR_CODE_ATTEMPTS:
                del self.pending[device_id]
            return False
        record = self.add_device(device_id)
        pending.confirmed_token = record.refresh_token
        return True

    def revoke(self, device_id: str) -> None:
        record = self.devices.pop(device_id)
        self.access = {t: d for t, d in self.access.items() if d != device_id}
        if record.current:
            asyncio.get_running_loop().create_task(record.current.ws.close())

    async def wait_registered(self, device_id: str, connections: int = 1) -> DeviceRecord:
        async with self._registered:
            await self._registered.wait_for(
                lambda: (
                    device_id in self.devices and self.devices[device_id].connections >= connections
                )
            )
        return self.devices[device_id]

    async def send_command(
        self, device_id: str, command: str, args: dict[str, Any], timeout: float = 5.0
    ) -> Frame:
        """Send a command on the device's current connection and wait for its result."""
        session = self.devices[device_id].current
        assert session and session.subscription_id, "device has no subscription"
        command_id = secrets.token_hex(8)
        frame = {
            "id": session.subscription_id,
            "type": "event",
            "event": {"command_id": command_id, "command": command, "args": args},
        }
        if command in self.codec.command_types:
            self.codec.validate(self.codec.command_types[command], frame)
        fut: asyncio.Future[Frame] = asyncio.get_running_loop().create_future()
        self._results[(id(session), command_id)] = fut
        if command == "screenshot":
            # The upload view accepts only these (PROTOCOL.md 5, P3.13).
            self.pending_screenshots[command_id] = device_id
        await session.ws.send_str(json.dumps(frame))
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._results.pop((id(session), command_id), None)
            self.pending_screenshots.pop(command_id, None)

    def register_binary_handler(self, session: Session) -> tuple[int, list[bytes]]:
        """Register a binary handler the way HA does for a voice turn (PROTOCOL.md 4.10).

        Returns the one-byte ID and the sink that receives each frame's PCM, so
        a test can assert on what the device streamed. IDs reuse freed slots,
        as `ActiveConnection.async_register_binary_handler` does.
        """
        used = set(session.binary_handlers)
        handler_id = next(i for i in range(1, 256) if i not in used)
        sink: list[bytes] = []
        session.binary_handlers[handler_id] = sink
        return handler_id, sink

    def unregister_binary_handler(self, session: Session, handler_id: int) -> None:
        session.binary_handlers.pop(handler_id, None)

    # --- HTTP -------------------------------------------------------------

    async def _token(self, request: web.Request) -> web.Response:
        form = await request.post()
        if form.get("grant_type") != "refresh_token" or "client_id" in form:
            return web.json_response({"error": "invalid_request"}, status=400)
        for record in self.devices.values():
            if secrets.compare_digest(str(form.get("refresh_token", "")), record.refresh_token):
                token = secrets.token_urlsafe(32)
                self.access[token] = record.device_id
                return web.json_response(
                    {
                        "access_token": token,
                        "expires_in": ACCESS_TOKEN_LIFETIME,
                        "token_type": "Bearer",
                    }
                )
        return web.json_response({"error": "invalid_grant"}, status=400)

    def _http_error(self, status: int, code: str, **extra: Any) -> web.Response:
        body = {"error": code, **extra}
        self.codec.validate("http_error", body)
        return web.json_response(body, status=status)

    async def _body(self, request: web.Request) -> dict[str, Any] | None:
        try:
            body = await request.json()
        except (ValueError, UnicodeDecodeError):
            return None
        return body if isinstance(body, dict) else None

    async def _pair(self, request: web.Request) -> web.Response:
        body = await self._body(request)
        if body is None:
            return self._http_error(400, "invalid_request")
        version = body.get("protocol_version")
        if isinstance(version, int) and version not in SUPPORTED_PROTOCOLS:
            return self._http_error(400, "unsupported_protocol")
        if self._check("http_pair_request", body):
            return self._http_error(400, "invalid_request")
        existing = self.pending.get(body["device_id"])
        if existing and existing.nonce_hash != body["nonce_hash"]:
            return self._http_error(409, "already_pending", retry_after=PAIR_PENDING_TTL)
        if not existing:
            self.pending[body["device_id"]] = Pending(
                body["device_id"], body["code"], body["nonce_hash"]
            )
        return web.json_response(
            {
                "status": "pending",
                "expires_in": PAIR_PENDING_TTL,
                "poll_interval": self.poll_interval,
            },
            status=202,
        )

    async def _claim(self, request: web.Request) -> web.Response:
        body = await self._body(request)
        if body is None or self._check("http_claim_request", body):
            return self._http_error(400, "invalid_request")
        pending = self.pending.get(body["device_id"])
        if pending is None:
            return self._http_error(404, "expired")
        digest = hashlib.sha256(body["nonce"].encode("ascii")).hexdigest()
        if not secrets.compare_digest(digest, pending.nonce_hash):
            return self._http_error(403, "bad_nonce")
        if pending.confirmed_token is None:
            return web.json_response({"status": "pending"}, status=202)
        del self.pending[body["device_id"]]
        record = self.devices[body["device_id"]]
        return web.json_response(
            {
                "status": "paired",
                "credential": {
                    "type": "refresh_token",
                    "refresh_token": pending.confirmed_token,
                    "token_url": "/auth/token",
                },
                "ha_device_id": record.ha_device_id,
            }
        )

    async def _screenshot(self, request: web.Request) -> web.Response:
        """The upload view (PROTOCOL.md 5): accept a capture for an open command.

        Mirrors the integration's view: the device comes from the bearer
        token, the command ID must name a still-pending `screenshot` command
        (409 `no_pending_screenshot` otherwise), the body is capped
        (`413 too_large`), and the answer is `http_screenshot_response`.
        """
        auth = request.headers.get("Authorization", "")
        token = auth[7:] if auth.startswith("Bearer ") else ""
        device_id = self.access.get(token)
        if device_id is None:
            return web.Response(status=401)
        command_id = request.headers.get("X-Tabula-Command-Id", "")
        if self.pending_screenshots.get(command_id) != device_id:
            return self._http_error(409, "no_pending_screenshot")
        if request.content_type != "image/jpeg":  # media type, parameters ignored
            return self._http_error(400, "invalid_request")
        body = await request.read()
        if len(body) > SCREENSHOT_MAX_BYTES:
            return self._http_error(413, "too_large")
        record = self.devices[device_id]
        record.last_upload = body
        record.upload_command_id = command_id
        # The command is still open here, so the client really did upload
        # before answering - the ordering the protocol requires.
        record.upload_before_result = (id(record.current), command_id) in self._results
        record.report["screenshot"] = {
            "uploaded": True,
            "bytes": len(body),
            "command_id": command_id,
            "before_result": record.upload_before_result,
        }
        response = {"status": "stored", "bytes": len(body)}
        self.codec.validate("http_screenshot_response", response)
        return web.json_response(response)

    # --- WebSocket --------------------------------------------------------

    def _check(self, frame_type: str, frame: Frame) -> list[str]:
        problems = self.codec.problems(frame_type, frame)
        self.received.append((frame_type, frame))
        if problems:
            self.problems.append(f"{frame_type}: {'; '.join(problems)}")
        return problems

    async def _websocket(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        await ws.send_json({"type": "auth_required", "ha_version": "test"})
        auth = await ws.receive_json()
        device_id = self.access.get(auth.get("access_token", ""))
        if auth.get("type") != "auth" or device_id is None:
            await ws.send_json({"type": "auth_invalid", "message": "Invalid access token"})
            await ws.close()
            return ws
        await ws.send_json({"type": "auth_ok", "ha_version": "test"})
        session = Session(ws=ws, device_id=device_id)
        try:
            async for msg in ws:
                if msg.type is WSMsgType.BINARY:
                    self._binary(session, bytes(msg.data))
                    continue
                if msg.type is not WSMsgType.TEXT:
                    continue
                frame = json.loads(msg.data)
                if frame.get("type") in hacore.COMMANDS and self._fresh_id(session, frame):
                    await self.ha.handle(session, frame, ws.send_json)
                    continue
                reply = await self._handle(session, frame)
                if reply is not None:
                    await ws.send_json(reply)
        finally:
            self.ha.drop_owner(session)
        record = self.devices.get(device_id)
        if record and record.current is session:
            record.current = None
        return ws

    def _binary(self, session: Session, payload: bytes) -> None:
        """Route a binary frame by its first byte, as HA does (PROTOCOL.md 4.10).

        Frames for an ID this connection never registered, or with a PCM length
        that is not `VOICE_CHUNK_BYTES`, are recorded as problems: the sender is
        strict, so a malformed frame must fail the test, not be tolerated.
        """
        if not payload:
            self.problems.append("binary: empty frame")
            return
        handler_id, pcm = payload[0], payload[1:]
        sink = session.binary_handlers.get(handler_id)
        if sink is None:
            self.problems.append(f"binary: handler {handler_id} is not registered")
            return
        if len(pcm) != VOICE_CHUNK_BYTES:
            self.problems.append(
                f"binary: {len(pcm)} PCM bytes for handler {handler_id}, "
                f"expected {VOICE_CHUNK_BYTES}"
            )
            return
        sink.append(pcm)
        record = self.devices.get(session.device_id)
        if record is not None:
            stats = record.report.setdefault("voice", {"frames": 0, "bytes": 0})
            stats["frames"] += 1
            stats["bytes"] += len(pcm)

    @staticmethod
    def _error(msg_id: Any, code: str, message: str) -> Frame:
        return {
            "id": msg_id,
            "type": "result",
            "success": False,
            "error": {"code": code, "message": message},
        }

    @staticmethod
    def _ack(msg_id: int) -> Frame:
        return {"id": msg_id, "type": "result", "success": True, "result": None}

    @staticmethod
    def _fresh_id(session: Session, frame: Frame) -> bool:
        """HA's shared id rule for a core command; a reused id falls through to id_reuse."""
        msg_id = frame.get("id")
        if not isinstance(msg_id, int) or msg_id <= session.last_id:
            return False
        session.last_id = msg_id
        return True

    async def _handle(self, session: Session, frame: Frame) -> Frame | None:
        msg_id = frame.get("id")
        if not isinstance(msg_id, int) or msg_id <= session.last_id:
            return self._error(msg_id, "id_reuse", "Identifier values have to increase.")
        session.last_id = msg_id
        kind = frame.get("type")
        if kind == "ping":
            self._check("ping", frame)
            return {"id": msg_id, "type": "pong"}
        if not isinstance(kind, str) or not kind.startswith("tabula/"):
            return self._error(msg_id, "unknown_command", "Unknown command.")
        if session.superseded:
            return self._error(msg_id, "superseded", "A newer connection replaced this one.")
        if kind == "tabula/register":
            return await self._register(session, frame)
        if not session.registered:
            return self._error(msg_id, "not_registered", "Send tabula/register first.")
        frame_type = {
            "tabula/subscribe": "subscribe",
            "tabula/state": "state_delta",
            "tabula/result": "command_result",
            "tabula/event": "event",
            "tabula/log": "log",
        }.get(kind)
        if frame_type is None:
            return self._error(msg_id, "unknown_command", "Unknown command.")
        if problems := self._check(frame_type, frame):
            return self._error(msg_id, "invalid_frame", problems[0][:500])
        record = self.devices[session.device_id]
        if frame_type == "subscribe":
            if session.subscription_id is not None:
                return self._error(msg_id, "invalid_frame", "Already subscribed.")
            session.subscription_id = msg_id
            async with self._registered:
                record.connections += 1
                self._registered.notify_all()
        elif frame_type == "state_delta":
            record.state.update(frame["state"])
        elif frame_type == "command_result":
            fut = self._results.get((id(session), frame["command_id"]))
            if fut and not fut.done():
                fut.set_result(frame)
        elif frame_type == "event":
            record.events.append(frame)
        elif frame_type == "log":
            record.logs.extend(frame["lines"])
        return self._ack(msg_id)

    async def _register(self, session: Session, frame: Frame) -> Frame:
        msg_id = frame["id"]
        version = frame.get("protocol_version")
        if isinstance(version, int) and version not in SUPPORTED_PROTOCOLS:
            self.received.append(("register", frame))
            return self._error(msg_id, "unsupported_protocol", f"Protocol {version} unsupported.")
        if problems := self._check("register", frame):
            return self._error(msg_id, "invalid_frame", problems[0][:500])
        if frame["device_id"] != session.device_id:
            return self._error(msg_id, "unknown_device", "Device does not match credential.")
        record = self.devices[session.device_id]
        if record.current and record.current is not session:
            record.current.superseded = True
        record.current = session
        session.registered = True
        record.state = dict(frame["state"])
        record.capabilities = [c for c in frame["capabilities"] if c in self.accepted]
        reply = {
            "id": msg_id,
            "type": "result",
            "success": True,
            "result": {
                "ha_device_id": record.ha_device_id,
                "protocol_version": PROTOCOL_VERSION,
                "settings_revision": self.settings_revision,
                "server_time": datetime.now(UTC).replace(microsecond=0).isoformat(),
                "capabilities": record.capabilities,
            },
        }
        self.codec.validate("register_result", reply)
        return reply

    def assert_clean(self) -> None:
        """Fail if the device sent any frame the schema rejects."""
        if self.problems:
            raise FrameError(None, self.problems)

    # --- test hooks ---------------------------------------------------------
    # Not part of TLP: an HTTP way to do what the in-process tests do directly,
    # so an external client (the Android JVM integration test) can confirm a
    # pairing, send a command and read the server's view.

    async def _test_hook(self, request: web.Request) -> web.Response:
        name = request.match_info["hook"]
        handler = getattr(self, f"_test_{name}", None) if name.isidentifier() else None
        if handler is None or name == "hook":
            return web.json_response({"error": "unknown_hook"}, status=404)
        return await handler(request)

    async def _test_confirm(self, request: web.Request) -> web.Response:
        body = await self._body(request)
        if body is None or "device_id" not in body or "code" not in body:
            return web.json_response({"error": "invalid_request"}, status=400)
        device_id, code = str(body["device_id"]), str(body["code"])
        if device_id not in self.pending:
            return web.json_response({"error": "no_pending"}, status=404)
        return web.json_response({"confirmed": self.confirm(device_id, code)})

    async def _test_command(self, request: web.Request) -> web.Response:
        body = await self._body(request)
        if (
            body is None
            or "device_id" not in body
            or "command" not in body
            or not isinstance(body.get("args"), dict)
        ):
            return web.json_response({"error": "invalid_request"}, status=400)
        try:
            frame = await self.send_command(
                str(body["device_id"]), str(body["command"]), body["args"], timeout=10.0
            )
        except TimeoutError:
            return web.json_response({"error": "timeout"}, status=504)
        except (KeyError, AssertionError):
            return web.json_response({"error": "not_connected"}, status=409)
        return web.json_response({"result": frame})

    async def _test_entity(self, request: web.Request) -> web.Response:
        """Set (`state`, optional `attributes`) or remove (`remove: true`) an HA entity."""
        body = await self._body(request)
        if body is None or not isinstance(body.get("entity_id"), str):
            return web.json_response({"error": "invalid_request"}, status=400)
        if body.get("remove"):
            await self.ha.remove(body["entity_id"])
        elif isinstance(body.get("state"), str):
            attributes = body.get("attributes")
            await self.ha.set_state(
                body["entity_id"],
                body["state"],
                attributes if isinstance(attributes, dict) else None,
            )
        else:
            return web.json_response({"error": "invalid_request"}, status=400)
        return web.json_response({"ok": True})

    async def _test_forecast(self, request: web.Request) -> web.Response:
        body = await self._body(request)
        if (
            body is None
            or not isinstance(body.get("entity_id"), str)
            or body.get("forecast_type") not in hacore.FORECAST_TYPES
            or not isinstance(body.get("forecast"), list)
        ):
            return web.json_response({"error": "invalid_request"}, status=400)
        await self.ha.set_forecast(body["entity_id"], body["forecast_type"], body["forecast"])
        return web.json_response({"ok": True})

    async def _test_display(self, request: web.Request) -> web.Response:
        """Replace the display data: `registry`, flat `translations`, `icons` per category."""
        body = await self._body(request)
        if body is None:
            return web.json_response({"error": "invalid_request"}, status=400)
        if isinstance(body.get("registry"), list):
            self.ha.registry = body["registry"]
        if isinstance(body.get("translations"), dict):
            self.ha.translations = body["translations"]
        if isinstance(body.get("icons"), dict):
            self.ha.icons.update(body["icons"])
        return web.json_response({"ok": True})

    async def _test_drop(self, request: web.Request) -> web.Response:
        """Close a device's live connection, as a network drop or HA restart would."""
        body = await self._body(request)
        record = self.devices.get(str((body or {}).get("device_id")))
        if record is None or record.current is None:
            return web.json_response({"error": "not_connected"}, status=409)
        await record.current.ws.close()
        return web.json_response({"ok": True})

    async def _test_report(self, request: web.Request) -> web.Response:
        return web.json_response(
            {
                "problems": list(self.problems),
                "devices": {
                    device_id: {
                        "connections": record.connections,
                        "registered": record.current is not None,
                        "state": record.state,
                        "capabilities": record.capabilities,
                        "ha_subscriptions": self.ha.report(record.current)
                        if record.current
                        else [],
                        **record.report,
                    }
                    for device_id, record in self.devices.items()
                },
                "received_frames": len(self.received),
                "ha_commands": [frame["type"] for frame in self.ha.commands],
            }
        )
