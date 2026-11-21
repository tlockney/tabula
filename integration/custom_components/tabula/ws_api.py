"""tabula/* WebSocket commands (docs/PROTOCOL.md section 4).

Order of checks per frame, mirroring the mock test server the conformance suite
exercises (mockdevice/src/mockdevice/testserver.py):

1. hub loaded - otherwise not_registered;
2. superseded session - every frame from a replaced connection answers superseded,
   including a new tabula/register (newest registration wins, PROTOCOL.md 4.9);
3. protocol version (register only, before validation, PROTOCOL.md 4.2);
4. connection binding and device match (4.3);
5. schema validation, lenient with closed state keys (4.0, 4.4, section 8);
6. size: no frame over FRAME_MAX_BYTES (PROTOCOL.md 4, "Size").

Commands resolve the device from connection.user; no frame after register carries a
device identity. Frames that arrive before register answer not_registered.
"""

from __future__ import annotations

import json
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant, callback

from . import TabulaHub
from .const import (
    FRAME_MAX_BYTES,
    PROTOCOL_VERSION,
    SUPPORTED_PROTOCOL_VERSIONS,
)


def _reject_size(frame: dict[str, Any]) -> bool:
    """PROTOCOL.md 4, "Size": no frame may exceed FRAME_MAX_BYTES (UTF-8 JSON)."""
    return len(json.dumps(frame).encode()) > FRAME_MAX_BYTES


def _frame(
    hub: TabulaHub, connection: ActiveConnection, msg: dict[str, Any], frame_type: str
) -> dict[str, Any] | None:
    """Validate one frame; answer invalid_frame and return None when rejected."""
    if (frame := hub.tlp.accept(frame_type, msg)) is None:
        problems = hub.tlp.problems(frame_type, msg)
        connection.send_error(
            msg["id"], "invalid_frame", (problems[0] if problems else "invalid frame")[:500]
        )
        return None
    if _reject_size(frame):
        connection.send_error(msg["id"], "invalid_frame", f"frame exceeds {FRAME_MAX_BYTES} bytes")
        return None
    return frame


@callback
def ws_register(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/register: adopt this connection as the device's current session."""
    hub = TabulaHub.get(hass)
    if hub is None:
        connection.send_error(msg["id"], "not_registered", "Tabula is not set up.")
        return
    session = hub.sessions.get(connection)
    if session is not None and session.superseded:
        connection.send_error(msg["id"], "superseded", "A newer connection replaced this one.")
        return
    record = hub.device_for_user(connection.user)
    # The protocol version is read before the rest of the frame is validated
    # (PROTOCOL.md 4.2): a newer major gets unsupported_protocol, not invalid_frame.
    version = msg.get("protocol_version")
    if isinstance(version, int) and version not in SUPPORTED_PROTOCOL_VERSIONS:
        if record is not None:
            hub.mark_unsupported_protocol(record, version)
        connection.send_error(
            msg["id"],
            "unsupported_protocol",
            f"Protocol {version} not supported; this integration supports {PROTOCOL_VERSION}",
        )
        return
    if (frame := _frame(hub, connection, msg, "register")) is None:
        return
    if record is None or frame["device_id"] != record.device_id:
        # Connection binding (PROTOCOL.md 4.3): the credential's device or no device.
        connection.send_error(msg["id"], "unknown_device", "Device does not match credential.")
        return
    connection.send_result(msg["id"], hub.accept_registration(connection, record, frame))


def _session(hub: TabulaHub, connection: ActiveConnection, msg: dict[str, Any]) -> Any | None:
    """The connection's registered session, or an error reply."""
    session = hub.sessions.get(connection)
    if session is None or not session.registered:
        connection.send_error(msg["id"], "not_registered", "Send tabula/register first.")
        return None
    if session.superseded:
        connection.send_error(msg["id"], "superseded", "A newer connection replaced this one.")
        return None
    return session


@callback
def ws_subscribe(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/subscribe: commands will arrive on this frame id (P1.4)."""
    hub = TabulaHub.get(hass)
    if hub is None or (session := _session(hub, connection, msg)) is None:
        return
    if _frame(hub, connection, msg, "subscribe") is None:
        return
    if session.subscription_id is not None:
        connection.send_error(msg["id"], "invalid_frame", "Already subscribed.")
        return
    hub.subscribe(session, msg["id"])
    connection.send_result(msg["id"])


@callback
def ws_state(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/state: merge the delta into the device's state."""
    hub = TabulaHub.get(hass)
    if hub is None or (session := _session(hub, connection, msg)) is None:
        return
    if (frame := _frame(hub, connection, msg, "state_delta")) is None:
        return
    assert session.device is not None
    session.device.state.update(frame["state"])
    connection.send_result(msg["id"])


@callback
def ws_result(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/result: settle a pending command; unknown ids are just acked (4.6)."""
    hub = TabulaHub.get(hass)
    if hub is None or (session := _session(hub, connection, msg)) is None:
        return
    if (frame := _frame(hub, connection, msg, "command_result")) is None:
        return
    assert session.device is not None
    future = hub.result_futures.get((session.device.device_id, frame["command_id"]))
    if future is not None and not future.done():
        future.set_result(frame)
    connection.send_result(msg["id"])


@callback
def ws_event(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/event: accepted and kept for diagnostics; the bus relay is P1.5."""
    hub = TabulaHub.get(hass)
    if hub is None or (session := _session(hub, connection, msg)) is None:
        return
    if (frame := _frame(hub, connection, msg, "event")) is None:
        return
    assert session.device is not None
    session.device.events.append(frame)
    connection.send_result(msg["id"])


@callback
def ws_log(hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]) -> None:
    """tabula/log: accepted and kept for diagnostics; the log viewer is Phase 5."""
    hub = TabulaHub.get(hass)
    if hub is None or (session := _session(hub, connection, msg)) is None:
        return
    if (frame := _frame(hub, connection, msg, "log")) is None:
        return
    assert session.device is not None
    session.device.logs.extend(frame["lines"])
    connection.send_result(msg["id"])


COMMANDS = (
    (ws_register, "tabula/register"),
    (ws_subscribe, "tabula/subscribe"),
    (ws_state, "tabula/state"),
    (ws_result, "tabula/result"),
    (ws_event, "tabula/event"),
    (ws_log, "tabula/log"),
)


def _full_frame_schema(command: str) -> vol.All:
    """A command schema that passes the whole TLP frame through to the handler.

    The type-only dict form caps messages at {id, type}; TLP frames are the whole
    message, and each handler validates them against the JSON schema itself
    (this is the documented vol.All escape in websocket_api.decorators).
    """
    return vol.All(vol.Schema({vol.Required("type"): command}, extra=vol.ALLOW_EXTRA))


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the tabula commands once per HA instance (set up by async_setup)."""
    for handler, command in COMMANDS:
        websocket_api.async_register_command(
            hass, websocket_api.websocket_command(_full_frame_schema(command))(handler)
        )
