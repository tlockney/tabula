"""tabula/panel_* WebSocket commands: what the Tabularium shell reads (P5.1).

These sit beside, not inside, the device frames in ws_api.py: `tabula/panel_info`
is a flat command in the `tabula/panel_` namespace, so the two cannot collide
and the shell never parses a frame-shaped message.

Every command is admin-only (websocket_api.require_admin), matching the panel's
require_admin=True: the panel is a view over HA's admin authority and never sees
pairing credentials, device users' tokens or screenshot data.
"""

from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant, callback
from homeassistant.loader import async_get_integration

from . import TabulaHub
from .const import DOMAIN, PROTOCOL_VERSION, SCHEMA_VERSION


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


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the panel commands (called once from async_setup)."""
    websocket_api.async_register_command(hass, ws_panel_info)
