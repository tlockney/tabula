"""tabula.* services (plan 5.5, FEATURES F-HA-14): device commands that take parameters.

Each service is targeted the HA way (devices, entities or areas). A target
resolves to the Tabula tablets it names: a device directly, an entity through
the device it belongs to. Every targeted tablet gets the command; any failure
(offline, capability not declared, NACK, timeout) is raised to the caller, so
nothing fails silently (F-HA-14).
"""

from __future__ import annotations

import asyncio
from typing import Any

import voluptuous as vol
from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.target import TargetSelection, async_extract_referenced_entity_ids

from . import DeviceRecord, TabulaHub
from .const import DOMAIN

SERVICE_NOTIFY = "notify"
SERVICE_NAVIGATE = "navigate"
SERVICE_SCREENSAVER = "screensaver"

# Field limits mirror the TLP schema (command_notify, command_navigate,
# command_screensaver); the command is validated strictly again before sending.
NOTIFY_SCHEMA = vol.Schema(
    {
        **cv.ENTITY_SERVICE_FIELDS,
        vol.Required("message"): vol.All(cv.string, vol.Length(min=1, max=1000)),
        vol.Optional("title"): vol.All(cv.string, vol.Length(max=200)),
        vol.Optional("level"): vol.In(["info", "success", "warning", "error"]),
        vol.Optional("duration"): vol.All(vol.Coerce(int), vol.Range(min=0, max=3600)),
        vol.Optional("icon"): vol.All(cv.string, vol.Match(r"^mdi:[a-z0-9-]+$")),
        vol.Optional("chime"): cv.boolean,
        vol.Optional("tag"): vol.All(cv.string, vol.Length(max=64)),
    }
)

NAVIGATE_SCHEMA = vol.Schema(
    {
        **cv.ENTITY_SERVICE_FIELDS,
        vol.Required("path"): vol.All(
            cv.string, vol.Length(max=2048), vol.Match(r"^/(?![/\\])[^\s\\]*$")
        ),
    }
)

SCREENSAVER_SCHEMA = vol.Schema(
    {
        **cv.ENTITY_SERVICE_FIELDS,
        vol.Required("active"): cv.boolean,
        vol.Optional("mode"): cv.string,
    }
)

TARGET_KEYS = set(cv.ENTITY_SERVICE_FIELDS)


def target_records(hass: HomeAssistant, hub: TabulaHub, call: ServiceCall) -> list[DeviceRecord]:
    """The paired tablets a service call targets; raises when it names none."""
    # helpers.target is the stable home of this API: helpers.service's wrapper
    # was removed in later HA releases (it is gone in 2026.11).
    selected = async_extract_referenced_entity_ids(hass, TargetSelection(call.data))
    ha_devices = set(selected.referenced_devices)
    registry = er.async_get(hass)
    for entity_id in selected.referenced | selected.indirectly_referenced:
        entry = registry.async_get(entity_id)
        if entry is not None and entry.platform == DOMAIN and entry.device_id:
            ha_devices.add(entry.device_id)
    records = [r for r in hub.devices.values() if r.ha_device_id in ha_devices]
    if not records:
        raise ServiceValidationError(translation_domain=DOMAIN, translation_key="no_target_devices")
    return records


async def _send_all(
    hass: HomeAssistant, call: ServiceCall, command: str, args: dict[str, Any]
) -> None:
    hub = TabulaHub.get(hass)
    if hub is None:
        raise ServiceValidationError(translation_domain=DOMAIN, translation_key="hub_not_loaded")
    records = target_records(hass, hub, call)
    results = await asyncio.gather(
        *(hub.async_command(record, command, args) for record in records),
        return_exceptions=True,
    )
    # Every tablet was tried; the first failure is reported.
    for result in results:
        if isinstance(result, BaseException):
            raise result


def _args(call: ServiceCall) -> dict[str, Any]:
    return {k: v for k, v in call.data.items() if k not in TARGET_KEYS}


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the services once per HA instance (async_setup)."""

    async def notify(call: ServiceCall) -> None:
        await _send_all(hass, call, "notify", _args(call))

    async def navigate(call: ServiceCall) -> None:
        await _send_all(hass, call, "navigate", _args(call))

    async def screensaver(call: ServiceCall) -> None:
        args = _args(call)
        hub = TabulaHub.get(hass)
        if hub is not None and "mode" in args:
            modes = hub.tlp.enum_values("state", "screensaver_mode")
            if args["mode"] not in modes:
                raise ServiceValidationError(
                    translation_domain=DOMAIN,
                    translation_key="invalid_screensaver_mode",
                    translation_placeholders={"mode": args["mode"], "modes": ", ".join(modes)},
                )
        await _send_all(hass, call, "screensaver", args)

    hass.services.async_register(DOMAIN, SERVICE_NOTIFY, notify, schema=NOTIFY_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_NAVIGATE, navigate, schema=NAVIGATE_SCHEMA)
    hass.services.async_register(
        DOMAIN, SERVICE_SCREENSAVER, screensaver, schema=SCREENSAVER_SCHEMA
    )
