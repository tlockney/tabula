"""The intercom directory a tablet's screen lists (P9.4, ADR 0025 decision 6).

The screen lists the other intercom-capable tablets by HA area name (the device
name when no area is set), and it needs an HA device registry id to put in
``target_device_id``. P9.1's Result item 10 named that as P9.4's dependency: a
device has neither, and ``register_result`` carries only its own
``ha_device_id``.

The answer is a device-scoped WebSocket command on the tablet's own connection,
next to TLP (ADR 0017: one socket, ``HaChannel`` in the app), not a new TLP
frame: the protocol's frames are unchanged, so P9.1b can still reshape them
without this file moving. It sits beside ``tabula/panel_*`` for the same reason
those do - a flat command in the ``tabula/`` namespace that is not a frame. It
is *not* admin-only like the panel: the connection is a paired tablet's, the
tablet resolves from the connection's user exactly as ``tabula/register`` does,
and a device gets its own neighbours and nothing else.

Same-area tablets are left out, because neither a two-way call nor a broadcast
reaches one (ADR 0025 consequences, ADR 0028: two devices in one room feed
back); ``intercom._select_targets`` makes the same exclusion when a service
call names them. Both sides therefore agree on what "the other devices" means,
and the screen cannot offer a call the integration would refuse.
"""

from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr

from . import DeviceRecord, TabulaHub
from .intercom import CAP_LISTEN, CAP_TALK

COMMAND = "tabula/intercom_directory"


def area_of(hass: HomeAssistant, record: DeviceRecord) -> str | None:
    """The HA area this tablet is assigned to, if any (as ``IntercomManager`` reads it)."""
    entry = dr.async_get(hass).async_get(record.ha_device_id)
    return entry.area_id if entry is not None else None


def directory(hass: HomeAssistant, hub: TabulaHub, caller: DeviceRecord) -> list[dict[str, Any]]:
    """The tablets ``caller`` may ring, with the labels its screen shows.

    One entry per intercom-capable device: the HA device registry id the
    ``intercom_start_request`` carries, the device's name, its area (name and
    id), whether it can talk back and whether it is already in a call. The
    caller itself and its own area's tablets are not listed.
    """
    devices = dr.async_get(hass)
    areas = ar.async_get(hass)
    caller_area = area_of(hass, caller)
    listed: list[dict[str, Any]] = []
    for record in hub.devices.values():
        if record.device_id == caller.device_id:
            continue
        if CAP_LISTEN not in record.capabilities:
            continue
        area_id = area_of(hass, record)
        if caller_area is not None and area_id == caller_area:
            continue
        area = areas.async_get_area(area_id) if area_id is not None else None
        listed.append(
            {
                "device_id": record.ha_device_id,
                "name": record.name or record.device_id[:8],
                "area_id": area_id,
                "area_name": area.name if area is not None else None,
                "can_talk": CAP_TALK in record.capabilities,
                "in_call": hub.intercom.call_for_device(record.device_id) is not None,
            }
        )
    # The screen's order is the list's: by the label it shows, then by name.
    listed.sort(key=lambda item: ((item["area_name"] or item["name"]).casefold(), item["name"].casefold()))
    return listed


@websocket_api.websocket_command({vol.Required("type"): COMMAND})
@websocket_api.async_response
async def ws_intercom_directory(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/intercom_directory: the peers this tablet's intercom screen lists.

    A connection that is not a paired tablet is refused rather than answered
    with an empty list: an empty list is a real answer ("nobody to call"), and
    a caller must not read the two the same way.
    """
    hub = TabulaHub.get(hass)
    if hub is None:
        connection.send_error(
            msg["id"], "unknown_device", "This connection is not a paired tablet."
        )
        return
    record = hub.device_for_user(connection.user)
    if record is None:
        connection.send_error(
            msg["id"], "unknown_device", "This connection is not a paired tablet."
        )
        return
    if CAP_LISTEN not in record.capabilities:
        connection.send_error(
            msg["id"], "not_supported", "This tablet cannot take part in an intercom call."
        )
        return
    connection.send_result(msg["id"], {"devices": directory(hass, hub, record)})


def async_register(hass: HomeAssistant) -> None:
    """Register the directory command (called once from async_setup)."""
    websocket_api.async_register_command(hass, ws_intercom_directory)
