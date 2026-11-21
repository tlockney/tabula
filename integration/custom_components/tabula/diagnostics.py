"""Diagnostics: device summaries and pairing state, never credentials (ADR 0001).

No tokens, pairing codes or nonces appear in the output - a pairing code is a
one-time secret until confirmed, and a refresh token grants API access.
"""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from . import TabulaHub


def _device_summary(record: Any) -> dict[str, Any]:
    return {
        "device_id": record.device_id,
        "ha_device_id": record.ha_device_id,
        "name": record.name,
        "model": record.model,
        "manufacturer": record.manufacturer,
        "os_version": record.os_version,
        "app_version": record.app_version,
        "protocol_version": record.protocol_version,
        "connected": record.current is not None,
        "capabilities": list(record.capabilities),
        "state": dict(record.state),
    }


def _diagnostics(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    hub = TabulaHub.get(hass)
    if hub is None:
        return {"hub": "not loaded"}
    return {
        "devices": [_device_summary(record) for record in hub.devices.values()],
        "pending_pairings": [
            {
                "device_id": pending.device_id,
                "attempts": pending.attempts,
                "confirmed": pending.confirmed_token is not None,
            }
            for pending in hub.pending.all()
        ],
        "options": dict(entry.options),
    }


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry
) -> dict[str, Any]:
    return _diagnostics(hass, entry)


async def async_get_device_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry, device: Any
) -> dict[str, Any]:
    hub = TabulaHub.get(hass)
    if hub is None:
        return {"hub": "not loaded"}
    device_id = next((ident[1] for ident in device.identifiers if ident[0] == "tabula"), None)
    record = hub.devices.get(device_id) if device_id else None
    return {"device": _device_summary(record)} if record else {"device": None}
