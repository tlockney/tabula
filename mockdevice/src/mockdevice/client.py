"""TLP WebSocket client: register, subscribe, state pushes, command loop, reconnect."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import random
import time
from collections.abc import Callable
from typing import Any

import aiohttp

from mockdevice import __version__
from mockdevice.codec import UNKNOWN_COMMAND, Codec, Frame, FrameError
from mockdevice.constants import (
    APP_PING_INTERVAL,
    BACKOFF_BASE,
    BACKOFF_MAX,
    BACKOFF_RESET_AFTER,
    LOG_MAX_LINES,
    LOG_MIN_INTERVAL,
    PONG_TIMEOUT,
    PROTOCOL_VERSION,
    REGISTER_TIMEOUT,
    SCHEMA_VERSION,
    STATE_MIN_INTERVAL,
)
from mockdevice.device import CommandError, MockDevice
from mockdevice.pairing import AccessToken, CredentialRevoked, refresh_access_token
from mockdevice.store import DeviceStore

_LOGGER = logging.getLogger(__name__)


class ProtocolRejected(Exception):
    """The server refused registration in a way retrying cannot fix."""

    def __init__(self, code: str, message: str) -> None:
        self.code = code
        super().__init__(f"{code}: {message}")


class Backoff:
    """Equal-jitter exponential backoff (docs/PROTOCOL.md section 7)."""

    def __init__(
        self,
        base: float = BACKOFF_BASE,
        cap: float = BACKOFF_MAX,
        reset_after: float = BACKOFF_RESET_AFTER,
        rng: random.Random | None = None,
    ) -> None:
        self.base, self.cap, self.reset_after = base, cap, reset_after
        self.attempt = 0
        self.rng = rng or random.Random()

    def next_delay(self) -> float:
        d = min(self.cap, self.base * 2**self.attempt)
        self.attempt += 1
        return self.rng.uniform(d / 2, d)

    def connection_ended(self, stable_for: float) -> None:
        if stable_for >= self.reset_after:
            self.attempt = 0


class Connection:
    """One authenticated WebSocket with request/response matching."""

    def __init__(self, ws: aiohttp.ClientWebSocketResponse, codec: Codec) -> None:
        self.ws = ws
        self.codec = codec
        self._next_id = 1
        self._pending: dict[int, asyncio.Future[Frame]] = {}
        self.subscription_id: int | None = None
        self.commands: asyncio.Queue[Frame] = asyncio.Queue()
        self.closed = asyncio.Event()
        self.superseded = False
        self.last_id = 0

    async def send(self, frame_type: str, body: dict[str, Any]) -> asyncio.Future[Frame]:
        msg_id = self._next_id
        self._next_id += 1
        self.last_id = msg_id
        frame = {"id": msg_id, **body}
        fut: asyncio.Future[Frame] = asyncio.get_running_loop().create_future()
        # Fire-and-forget sends never await their future; retrieve its outcome so a
        # closed connection does not log "exception was never retrieved".
        fut.add_done_callback(lambda f: f.cancelled() or f.exception())
        self._pending[msg_id] = fut
        await self.ws.send_str(self.codec.encode(frame_type, frame))
        return fut

    async def request(
        self, frame_type: str, body: dict[str, Any], timeout: float = REGISTER_TIMEOUT
    ) -> Frame:
        fut = await self.send(frame_type, body)
        return await asyncio.wait_for(fut, timeout)

    async def reader(self) -> None:
        try:
            async for msg in self.ws:
                if msg.type is not aiohttp.WSMsgType.TEXT:
                    continue
                for frame in _split(json.loads(msg.data)):
                    self._dispatch(frame)
        finally:
            self.closed.set()
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(ConnectionError("connection closed"))

    def _dispatch(self, frame: Frame) -> None:
        try:
            frame_type = self.codec.classify_server_message(frame)
        except FrameError as e:
            _LOGGER.warning("ignoring server message: %s", e)
            return
        if frame_type.startswith("command_") or frame_type == UNKNOWN_COMMAND:
            if frame.get("id") == self.subscription_id:
                self.commands.put_nowait(frame)
            return
        if frame_type == "error_result" and frame.get("error", {}).get("code") == "superseded":
            # A newer registration replaced this connection (PROTOCOL.md 4.9): close it.
            self.superseded = True
            asyncio.get_running_loop().create_task(self.ws.close())
        fut = self._pending.pop(frame.get("id"), None)
        if fut and not fut.done():
            fut.set_result(frame)


def _split(data: Any) -> list[Frame]:
    """HA may coalesce several messages into one JSON array."""
    return data if isinstance(data, list) else [data]


class TLPClient:
    """Runs the device side of a TLP session until stopped or the credential is revoked."""

    def __init__(
        self,
        ha_url: str,
        store: DeviceStore,
        device: MockDevice,
        codec: Codec | None = None,
        manufacturer: str = "Tabula",
        model: str = "mockdevice",
        os_version: str = "python",
        backoff: Backoff | None = None,
        on_registered: Callable[[Frame], None] | None = None,
    ) -> None:
        self.ha_url = ha_url.rstrip("/")
        self.store = store
        self.device = device
        self.codec = codec or Codec()
        self.identity = {"manufacturer": manufacturer, "model": model, "os_version": os_version}
        self.backoff = backoff or Backoff()
        self.on_registered = on_registered
        self.registered = asyncio.Event()
        self.accepted_capabilities: list[str] = []
        self.ping_interval = APP_PING_INTERVAL
        self.connections = 0
        self._tasks: set[asyncio.Task] = set()
        self._registered_at: float | None = None
        self.superseded = 0  # connections closed because a newer one replaced them
        self._token: AccessToken | None = None
        self._stop = asyncio.Event()
        self._conn: Connection | None = None
        # Set per session; the screenshot upload needs it (PROTOCOL.md 5).
        self._http: aiohttp.ClientSession | None = None
        self._pending_state: dict[str, Any] = {}
        self._state_wakeup = asyncio.Event()
        device.on_change = self._queue_state
        device.on_event = self._queue_event
        self._events: asyncio.Queue[tuple[str, dict]] = asyncio.Queue()

    # --- public API -------------------------------------------------------

    def stop(self) -> None:
        self._stop.set()

    async def run(self, session: aiohttp.ClientSession) -> None:
        """Connect, and reconnect with backoff, until stop() or a fatal error."""
        while not self._stop.is_set():
            self._registered_at = None
            try:
                await self._session(session)
            except (CredentialRevoked, ProtocolRejected):
                raise
            except (aiohttp.ClientError, ConnectionError, TimeoutError, FrameError) as e:
                _LOGGER.info("connection ended: %s", e)
            self.registered.clear()
            if self._stop.is_set():
                break
            # The reset window counts from successful registration (ADR 0002).
            stable = (
                time.monotonic() - self._registered_at if self._registered_at is not None else 0.0
            )
            self.backoff.connection_ended(stable)
            delay = self.backoff.next_delay()
            _LOGGER.info("reconnecting in %.1f s", delay)
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._stop.wait(), delay)

    # --- session ----------------------------------------------------------

    async def _access_token(self, session: aiohttp.ClientSession) -> str:
        if self._token is None or not self._token.fresh():
            assert self.store.refresh_token, "device is not paired"
            self._token = await refresh_access_token(session, self.ha_url, self.store.refresh_token)
        return self._token.token

    async def _session(self, session: aiohttp.ClientSession) -> None:
        self._http = session
        token = await self._access_token(session)
        async with session.ws_connect(f"{self.ha_url}/api/websocket", max_msg_size=0) as ws:
            first = await ws.receive_json(timeout=REGISTER_TIMEOUT)
            if first.get("type") != "auth_required":
                raise ConnectionError(f"unexpected greeting {first}")
            await ws.send_json({"type": "auth", "access_token": token})
            auth = await ws.receive_json(timeout=REGISTER_TIMEOUT)
            if auth.get("type") != "auth_ok":
                self._token = None
                raise ConnectionError(f"auth failed: {auth.get('type')}")
            conn = Connection(ws, self.codec)
            self._conn = conn
            reader = asyncio.create_task(conn.reader())
            try:
                await self._register(conn)
                stopper = asyncio.create_task(self._stop.wait())
                workers = [
                    asyncio.create_task(self._command_loop(conn)),
                    asyncio.create_task(self._state_loop(conn)),
                    asyncio.create_task(self._event_loop(conn)),
                    asyncio.create_task(self._log_loop(conn)),
                    asyncio.create_task(self._ping_loop(conn)),
                ]
                await asyncio.wait([reader, stopper, *workers], return_when=asyncio.FIRST_COMPLETED)
                for t in (stopper, *workers):
                    t.cancel()
                for t in workers:
                    if t.done() and not t.cancelled() and t.exception():
                        raise t.exception()  # type: ignore[misc]
            finally:
                if conn.superseded:
                    self.superseded += 1
                self._conn = None
                await ws.close()
                reader.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await reader

    async def _register(self, conn: Connection) -> None:
        self._pending_state.clear()
        self.device.begin_connection()
        reply = await conn.request(
            "register",
            {
                "type": "tabula/register",
                "device_id": self.store.device_id,
                "protocol_version": PROTOCOL_VERSION,
                "schema_version": SCHEMA_VERSION,
                "app_version": __version__,
                **self.identity,
                "capabilities": self.device.capabilities,
                "settings_revision": self.device.settings_revision,
                "state": dict(self.device.state),
            },
        )
        kind = self.codec.classify_server_message(reply)
        self.codec.validate(kind, reply, lenient=True)
        if kind == "error_result":
            code, message = reply["error"]["code"], reply["error"]["message"]
            if code in ("unsupported_protocol", "unknown_device"):
                raise ProtocolRejected(code, message)
            raise ConnectionError(f"register refused: {code}: {message}")
        self.accepted_capabilities = reply["result"]["capabilities"]
        self.store.ha_device_id = reply["result"]["ha_device_id"]
        self.store.save()
        # Commands can follow the ack in the same read, so know the ID before the reply.
        fut = await conn.send("subscribe", {"type": "tabula/subscribe"})
        conn.subscription_id = conn.last_id
        sub = await asyncio.wait_for(fut, REGISTER_TIMEOUT)
        if self.codec.classify_server_message(sub) != "ack":
            raise ConnectionError(f"subscribe refused: {sub}")
        self.connections += 1
        self._registered_at = time.monotonic()
        self.registered.set()
        if self.on_registered:
            self.on_registered(reply)

    async def _command_loop(self, conn: Connection) -> None:
        while True:
            frame = await conn.commands.get()
            task = asyncio.create_task(self._handle_command(conn, frame))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)

    async def _handle_command(self, conn: Connection, frame: Frame) -> None:
        event = frame["event"]
        command_id, command = event.get("command_id"), event.get("command")
        frame_type = self.codec.command_types.get(command)
        if frame_type is None:
            # A command from a newer server: NACK it, keep the connection (PROTOCOL.md 4.0).
            if isinstance(command_id, str):
                await self._send_result(conn, command_id, "unsupported_command", "unknown command")
            return
        try:
            self.codec.validate(frame_type, frame, lenient=True)
        except FrameError as e:
            if isinstance(command_id, str):
                await self._send_result(conn, command_id, "invalid_args", str(e)[:500])
            return
        if self.device.should_drop(command):
            return
        try:
            data = await self.device.handle(command, event["args"])
        except CommandError as e:
            await self._send_result(conn, command_id, e.code, str(e))
            return
        if command == "screenshot":
            # Upload the capture before answering (PROTOCOL.md 5). A capture of
            # None means the screen was off: nothing to upload (F-MGT-07). An
            # upload that fails fails the command (`failed`, PROTOCOL.md 6).
            jpeg, self.device.last_screenshot = self.device.last_screenshot, None
            if jpeg is not None and not await self._upload_screenshot(command_id, jpeg):
                await self._send_result(conn, command_id, "failed", "screenshot upload failed")
                return
        await self._send_result(conn, command_id, None, None, data)
        if command == "set_settings":
            self.store.settings = self.device.settings
            self.store.settings_revision = self.device.settings_revision
            self.store.save()

    async def _upload_screenshot(self, command_id: str, jpeg: bytes) -> bool:
        """POST the capture to HA (PROTOCOL.md 5); False on any failure.

        Called after the device captured and before the command's result is
        sent, so HA only stores an image for a command that is still pending.
        """
        session = self._http
        assert session is not None, "upload outside a session"
        try:
            token = await self._access_token(session)
            async with session.post(
                f"{self.ha_url}/api/tabula/screenshot",
                data=jpeg,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "image/jpeg",
                    "X-Tabula-Command-Id": command_id,
                },
            ) as resp:
                if resp.status != 200:
                    _LOGGER.warning("screenshot upload rejected: %s", resp.status)
                    return False
                self.codec.validate("http_screenshot_response", await resp.json())
                return True
        except (aiohttp.ClientError, ConnectionError, TimeoutError, FrameError) as e:
            _LOGGER.warning("screenshot upload failed: %s", e)
            return False

    async def _send_result(
        self,
        conn: Connection,
        command_id: str,
        error_code: str | None,
        message: str | None,
        data: dict | None = None,
    ) -> None:
        body: dict[str, Any] = {"type": "tabula/result", "command_id": command_id}
        if error_code:
            body |= {"ok": False, "error": {"code": error_code, "message": message or ""}}
        else:
            body["ok"] = True
            if data:
                body["data"] = data
        with contextlib.suppress(ConnectionError, aiohttp.ClientError):
            await conn.send("command_result", body)

    # --- state and events -------------------------------------------------

    def _queue_state(self, delta: dict[str, Any]) -> None:
        self._pending_state.update(delta)
        self._state_wakeup.set()

    def _queue_event(self, event: str, data: dict[str, Any]) -> None:
        self._events.put_nowait((event, data))

    async def _state_loop(self, conn: Connection) -> None:
        while True:
            await self._state_wakeup.wait()
            self._state_wakeup.clear()
            if self._pending_state:
                delta, self._pending_state = self._pending_state, {}
                await conn.send("state_delta", {"type": "tabula/state", "state": delta})
            await asyncio.sleep(STATE_MIN_INTERVAL)

    async def _event_loop(self, conn: Connection) -> None:
        while True:
            event, data = await self._events.get()
            await conn.send("event", {"type": "tabula/event", "event": event, "data": data})

    async def _log_loop(self, conn: Connection) -> None:
        while True:
            await asyncio.sleep(LOG_MIN_INTERVAL)
            lines = self.device.log_lines[:LOG_MAX_LINES]
            if lines and self.device.log_streaming:
                del self.device.log_lines[: len(lines)]
                await conn.send("log", {"type": "tabula/log", "lines": lines})

    async def _ping_loop(self, conn: Connection) -> None:
        """HA's own pings are invisible here, so send HA's ping command (PROTOCOL.md 4)."""
        while True:
            await asyncio.sleep(self.ping_interval)
            try:
                await conn.request("ping", {"type": "ping"}, timeout=PONG_TIMEOUT)
            except TimeoutError as e:
                raise ConnectionError("no pong") from e
