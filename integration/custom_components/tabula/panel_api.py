"""tabula/panel_* WebSocket commands: what the Tabularium reads (P5.1, P5.2).

These sit beside, not inside, the device frames in ws_api.py: `tabula/panel_info`
and `tabula/panel_devices` are flat commands in the `tabula/panel_` namespace, so
the two cannot collide and the shell never parses a frame-shaped message.

Every command is admin-only (websocket_api.require_admin), matching the panel's
require_admin=True: the panel is a view over HA's admin authority and never sees
pairing credentials, device users' tokens or screenshot data.

`tabula/panel_devices` is one joined snapshot of the hub's device records, the HA
device registry and the entity registry (P5.2). The browser renders it; it never
stitches the registries itself, so what the panel shows is what the integration
believes.
"""

from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.loader import async_get_integration

from . import DeviceRecord, TabulaHub, logs
from .const import DOMAIN, PROTOCOL_VERSION, SCHEMA_VERSION

# The reported state keys the device list shows (PROTOCOL.md 4.4). Absent keys
# stay null in the payload rather than being invented: an offline device has no
# state at all.
SCREEN_KEYS = ("screen_on", "brightness", "screensaver_active", "screensaver_mode")


@websocket_api.websocket_command({vol.Required("type"): "tabula/panel_info"})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_info(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_info: the shell's status line.

    An unloaded hub is not an error: the panel exists while the entry reloads,
    and then it shows zero devices.
    """
    integration = await async_get_integration(hass, DOMAIN)
    hub = TabulaHub.get(hass)
    connection.send_result(
        msg["id"],
        {
            "version": integration.version,
            "protocol_version": PROTOCOL_VERSION,
            "schema_version": SCHEMA_VERSION,
            "devices": len(hub.devices) if hub is not None else 0,
        },
    )


def _registry_device(hass: HomeAssistant, record: DeviceRecord) -> dr.DeviceEntry | None:
    """The HA device registry entry, or None for a record without one.

    A record outlives its registry entry only in the window before the removal
    listener revokes it; the list still shows it, named from the record.
    """
    return dr.async_get(hass).async_get(record.ha_device_id)


def _area(hass: HomeAssistant, entry: dr.DeviceEntry | None) -> tuple[str | None, str | None]:
    """(area id, area name) for a device; (None, None) when it has no area."""
    if entry is None or entry.area_id is None:
        return None, None
    area = ar.async_get(hass).async_get_area(entry.area_id)
    return entry.area_id, area.name if area is not None else None


def _entities(hass: HomeAssistant, record: DeviceRecord) -> dict[str, list[dict[str, Any]]]:
    """The device's entities grouped by platform, so P5.5 needs no second lookup.

    Grouped by the entity id's domain (`sensor`, `switch`, …) in a stable order.
    """
    registry = er.async_get(hass)
    grouped: dict[str, list[dict[str, Any]]] = {}
    for entry in er.async_entries_for_device(registry, record.ha_device_id):
        platform = entry.entity_id.split(".", 1)[0]
        state = hass.states.get(entry.entity_id)
        grouped.setdefault(platform, []).append(
            {
                "entity_id": entry.entity_id,
                "name": entry.name or entry.original_name,
                "state": state.state if state is not None else None,
            }
        )
    for entities in grouped.values():
        entities.sort(key=lambda item: item["entity_id"])
    return dict(sorted(grouped.items()))


def settings_state(hub: TabulaHub, record: DeviceRecord) -> dict[str, Any]:
    """Settings revisions and whether the integration still owes the device a push.

    The device's revision is what it reported at registration or in its last
    `set_settings` ACK (PROTOCOL.md 4.2, 4.6); `pending` is the integration's own
    rule for a push (settings.schedule_settings_push): a stored document exists
    and the device's revision differs from it in either direction. An offline
    device reports no revision, and a device that never received a document owes
    no push, so both stay not pending.
    """
    session = record.current
    reported = session.reported_settings_revision if session is not None else None
    stored = hub.settings.get(record.device_id)
    return {
        "ha_revision": stored.revision if stored is not None else 0,
        "device_revision": reported,
        "pending": stored is not None and reported is not None and reported != stored.revision,
    }


def device_snapshot(hass: HomeAssistant, hub: TabulaHub, record: DeviceRecord) -> dict[str, Any]:
    """One list entry: identity and link state from the hub, the rest joined in.

    No credentials, tokens, pairing nonces or user ids: the panel is a view over
    admin authority, not a credential store (P5.1).
    """
    entry = _registry_device(hass, record)
    area_id, area_name = _area(hass, entry)
    state = record.state
    registry_name = (entry.name_by_user or entry.name) if entry is not None else None
    return {
        "device_id": record.device_id,
        "ha_device_id": record.ha_device_id,
        # The record first: a name set in the settings editor (F-MGT-03) and a
        # model from the register frame outrank the registry's pairing-time copy.
        "name": record.name or registry_name,
        "model": record.model or (entry.model if entry is not None else None),
        "area_id": area_id,
        "area_name": area_name,
        "connected": record.connected,
        "last_seen": record.last_seen,
        "app_version": record.app_version,
        "os_version": record.os_version,
        "protocol_version": record.protocol_version,
        "settings_schema_version": record.schema_version,
        "capabilities": list(record.capabilities),
        "settings": settings_state(hub, record),
        "current_page": state.get("current_page"),
        "screen": {key: state.get(key) for key in SCREEN_KEYS},
        "entities": _entities(hass, record),
    }


def device_list(hass: HomeAssistant, hub: TabulaHub | None) -> list[dict[str, Any]]:
    """Every paired device, in a stable order (the panel sorts for display)."""
    if hub is None:
        return []
    return [
        device_snapshot(hass, hub, record)
        for record in sorted(hub.devices.values(), key=lambda item: item.device_id)
    ]


@websocket_api.websocket_command({vol.Required("type"): "tabula/panel_devices"})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_devices(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_devices: one joined snapshot for the device list (P5.2).

    An unloaded hub returns an empty list, like `tabula/panel_info` returns zero
    devices: the panel exists while the entry reloads.
    """
    connection.send_result(msg["id"], device_list(hass, TabulaHub.get(hass)))


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the panel commands (called once from async_setup)."""
    websocket_api.async_register_command(hass, ws_panel_info)
    websocket_api.async_register_command(hass, ws_panel_devices)
    # P5.4's log view: the snapshot and the live stream commands (logs.py).
    logs.async_register(hass)
    # The settings editor's two commands live in panel_settings.py (P5.3); it
    # imports settings_state from here, so this import is deferred to keep the
    # module graph acyclic while panel_api stays the one place commands register.
    from .panel_settings import async_register as register_settings

    register_settings(hass)
