"""The Tabularium's log view: HA's ring, the panel's streams, one device subscription.

P5.4 (F-MGT-06). Three things live here:

- the panel's snapshot (`tabula/panel_logs`), which is HA's per-device ring, the
  newest last, bounded by `LOG_BUFFER_LINES`;
- the panel's live streams (`tabula/panel_logs_subscribe`), which are reference
  counted per device: the first viewer turns the device's `log_subscription` on,
  the last one turns it off, and a second viewer shares the first's subscription;
- the TTL refresh: while at least one viewer is open the integration re-sends
  `log_subscription {enabled: true}` (PROTOCOL 4.8) so the device's own 600 s
  limit never fires under a watcher, and the refresh stops the moment nobody is
  watching.

Lines are never written to HA's log or diagnostics by this module: the ring is
memory only and the panel is its only reader.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback
from homeassistant.helpers.event import async_call_later

from . import DeviceRecord, TabulaHub
from .const import LOG_BUFFER_LINES, LOG_LEVELS, LOG_SUBSCRIPTION_TTL

_LOGGER = logging.getLogger(__name__)

# How often the device's subscription is extended: half its TTL, so one lost
# refresh is still inside the window (the ADR's TTL is what a forgotten viewer
# leaves behind).
REFRESH_INTERVAL = LOG_SUBSCRIPTION_TTL / 2


@dataclass
class LogViewers:
    """One device's open panel streams: the device subscription's reference count."""

    # One stream per panel connection (a socket has one Logs tab open): the
    # connection object's id -> that stream's listener. Keyed by connection and
    # not by message id, because a close arrives as a NEW message and HA numbers
    # messages strictly upward, so it cannot reuse the subscribe's id.
    listeners: dict[int, Callable[[dict[str, Any]], None]] = field(default_factory=dict)
    # The minimum level the device streams at, or None for everything.
    level: str | None = None
    # When the device's own subscription expires, in epoch seconds.
    expires_at: float | None = None
    # The pending TTL refresh.
    cancel_refresh: CALLBACK_TYPE | None = None

    @property
    def count(self) -> int:
        return len(self.listeners)


class LogStreams:
    """The hub's log state: per-device viewer sets and the TTL refresher."""

    def __init__(self, hub: TabulaHub) -> None:
        self.hub = hub
        self._viewers: dict[str, LogViewers] = {}

    # --- reads -------------------------------------------------------------

    def viewers(self, record: DeviceRecord) -> LogViewers:
        """The device's viewer set, created empty on first use."""
        return self._viewers.setdefault(record.device_id, LogViewers())

    def snapshot(self, record: DeviceRecord) -> dict[str, Any]:
        """`tabula/panel_logs`: HA's ring plus the subscription's state."""
        viewers = self.viewers(record)
        return {
            "device_id": record.device_id,
            "connected": record.connected,
            "last_seen": record.last_seen,
            "capacity": LOG_BUFFER_LINES,
            # Oldest first, as the device sent them (the panel decides on order).
            "lines": list(record.logs),
            "subscription": {
                "active": viewers.count > 0 and record.connected,
                "viewers": viewers.count,
                "level": viewers.level,
                "expires_at": _iso(viewers.expires_at),
            },
        }

    # --- streams -----------------------------------------------------------

    async def async_add_viewer(
        self,
        record: DeviceRecord,
        connection: ActiveConnection,
        msg_id: int,
        level: str | None,
    ) -> dict[str, Any]:
        """Add one panel stream; turns the device subscription on when it is first.

        Returns the subscription's state for the command's result.
        """
        viewers = self.viewers(record)
        first = viewers.count == 0
        viewers.listeners[id(connection)] = _listener(connection, msg_id)
        # The device holds one level per subscription, so a second viewer whose
        # level differs (widening back to everything included) needs the command
        # again: otherwise the device would keep filtering at the older floor
        # while this stream reports the new one.
        changed = level != viewers.level
        viewers.level = level
        if first or changed:
            # A first viewer needs both the command and the TTL refresh; a new
            # level needs the command, and keeps the refresh already running.
            await self._async_enable(record, level)
        self._schedule_refresh(record)
        return {
            "level": viewers.level,
            "expires_at": _iso(viewers.expires_at),
            "viewers": viewers.count,
        }

    async def async_remove_viewer(self, record: DeviceRecord, connection: ActiveConnection) -> None:
        """Drop one panel stream; the device's subscription ends with the last."""
        viewers = self.viewers(record)
        viewers.listeners.pop(id(connection), None)
        if viewers.count:
            return
        viewers.level = None
        self._cancel_refresh(record)
        if not record.connected:
            return
        try:
            await self.hub.async_command(record, "log_subscription", {"enabled": False})
        except Exception as err:
            _LOGGER.debug("log subscription for %s did not stop: %s", record.device_id, err)
        viewers.expires_at = None

    @callback
    def push(self, record: DeviceRecord, lines: list[dict[str, Any]]) -> None:
        """Relay a `tabula/log` frame's lines to every open panel stream."""
        viewers = self._viewers.get(record.device_id)
        if viewers is None or not lines:
            return
        message = {"lines": lines}
        for listener in list(viewers.listeners.values()):
            listener(message)

    @callback
    def device_offline(self, record: DeviceRecord) -> None:
        """The device dropped: no subscription survives it (PROTOCOL 4.8)."""
        viewers = self._viewers.get(record.device_id)
        if viewers is None:
            return
        self._cancel_refresh(record)
        viewers.expires_at = None
        for listener in list(viewers.listeners.values()):
            listener({"subscription": {"active": False, "expires_at": None}})

    @callback
    def shutdown(self) -> None:
        """The hub unloaded: cancel every refresh; sockets close on their own."""
        for device_id in list(self._viewers):
            cancel = self._viewers[device_id].cancel_refresh
            if cancel is not None:
                cancel()
            self._viewers[device_id].cancel_refresh = None
        self._viewers.clear()

    # --- the device's subscription -----------------------------------------

    async def _async_enable(self, record: DeviceRecord, level: str | None) -> None:
        """Send `log_subscription {enabled: true}` and record its expiry."""
        args: dict[str, Any] = {"enabled": True}
        if level is not None:
            args["level"] = level
        try:
            await self.hub.async_command(record, "log_subscription", args)
        except Exception as err:
            _LOGGER.debug("log subscription for %s did not start: %s", record.device_id, err)
            self.viewers(record).expires_at = None
            return
        self.viewers(record).expires_at = time.time() + LOG_SUBSCRIPTION_TTL

    def _schedule_refresh(self, record: DeviceRecord) -> None:
        """Extend the device's TTL while a viewer is open; one timer per device."""
        viewers = self.viewers(record)
        if viewers.cancel_refresh is not None or viewers.count == 0:
            return

        async def _refresh(_now: Any) -> None:
            viewers.cancel_refresh = None
            if viewers.count == 0:
                return
            if not record.connected:
                # The device's own connection teardown ends it; nothing to renew.
                return
            await self._async_enable(record, viewers.level)
            self._schedule_refresh(record)

        viewers.cancel_refresh = async_call_later(self.hub.hass, REFRESH_INTERVAL, _refresh)

    def _cancel_refresh(self, record: DeviceRecord) -> None:
        viewers = self.viewers(record)
        if viewers.cancel_refresh is not None:
            viewers.cancel_refresh()
            viewers.cancel_refresh = None


def _listener(connection: ActiveConnection, msg_id: int) -> Callable[[dict[str, Any]], None]:
    """A panel stream's sink: one HA event per message, on the panel's own id."""

    def _send(message: dict[str, Any]) -> None:
        connection.send_message(websocket_api.event_message(msg_id, message))

    return _send


def _iso(epoch: float | None) -> str | None:
    """Epoch seconds as RFC 3339, for the panel's expiry line."""
    if epoch is None:
        return None
    return (datetime.now(UTC) + timedelta(seconds=max(epoch - time.time(), 0))).isoformat()


# --- WebSocket commands -----------------------------------------------------


def _record(hub: TabulaHub | None, device_id: str) -> DeviceRecord | None:
    return None if hub is None else hub.devices.get(device_id)


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_logs",
        vol.Required("device_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_logs(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_logs: the buffered lines, the buffer's bound and the stream state."""
    hub = TabulaHub.get(hass)
    record = _record(hub, msg["device_id"])
    if hub is None or record is None:
        connection.send_error(msg["id"], "not_found", f"no paired device {msg['device_id']}")
        return
    connection.send_result(msg["id"], hub.logs.snapshot(record))


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_logs_subscribe",
        vol.Required("device_id"): str,
        vol.Required("enabled"): bool,
        vol.Optional("level"): vol.In(LOG_LEVELS),
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_logs_subscribe(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_logs_subscribe: open or close one panel log stream."""
    hub = TabulaHub.get(hass)
    record = _record(hub, msg["device_id"])
    if hub is None or record is None:
        connection.send_error(msg["id"], "not_found", f"no paired device {msg['device_id']}")
        return
    if not msg["enabled"]:
        await hub.logs.async_remove_viewer(record, connection)
        connection.send_result(msg["id"], {"active": False})
        return
    if not record.connected:
        connection.send_error(
            msg["id"], "device_offline", "the device is offline; showing its buffer only"
        )
        return
    state = await hub.logs.async_add_viewer(record, connection, msg["id"], msg.get("level"))
    # HA runs this when the panel socket closes or the panel unsubscribes: the
    # stream's reference count drops with it, and the last one stops the device.
    connection.subscriptions[msg["id"]] = lambda: hass.async_create_task(
        hub.logs.async_remove_viewer(record, connection)
    )
    connection.send_result(msg["id"], state)


def async_register(hass: HomeAssistant) -> None:
    """Register the log commands (called from panel_api.async_register)."""
    websocket_api.async_register_command(hass, ws_panel_logs)
    websocket_api.async_register_command(hass, ws_panel_logs_subscribe)
