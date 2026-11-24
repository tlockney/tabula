"""tabula.* services (plan 5.5, FEATURES F-HA-14): device commands that take parameters.

Each service is targeted the HA way (devices, entities or areas). A target
resolves to the Tabula tablets it names: a device directly, an entity through
the device it belongs to. Every targeted tablet gets the command; any failure
(offline, capability not declared, NACK, timeout) is raised to the caller, so
nothing fails silently (F-HA-14).
"""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING, Any

import voluptuous as vol
from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er

if TYPE_CHECKING:
    from homeassistant.helpers.target import SelectedEntities

# Target extraction moved across HA releases (supported floor 2025.1.0,
# ADR 0001 item 8): helpers.service.async_extract_referenced_entity_ids took
# the ServiceCall itself from the start; helpers.target exists from 2025.8 but
# only gains TargetSelection in 2026.1, from where the helpers.service wrapper
# is deprecated (a log line per call) and gone by 2026.10. Prefer helpers.target
# wherever TargetSelection exists; fall back to helpers.service below it.
try:
    from homeassistant.helpers.target import (
        TargetSelection,
    )
    from homeassistant.helpers.target import (
        async_extract_referenced_entity_ids as _extract_target_ids,
    )
except ImportError:
    from homeassistant.helpers.service import (
        async_extract_referenced_entity_ids as _referenced_entity_ids,
    )
else:

    def _referenced_entity_ids(hass: HomeAssistant, call: ServiceCall) -> SelectedEntities:
        """Referenced entities of a service call, the HA 2026.1+ way."""
        return _extract_target_ids(hass, TargetSelection(call.data))


from . import DeviceRecord, TabulaHub
from .const import DOMAIN

SERVICE_NOTIFY = "notify"
SERVICE_NAVIGATE = "navigate"
SERVICE_SCREENSAVER = "screensaver"
SERVICE_INTERCOM_CALL = "intercom_call"
SERVICE_INTERCOM_END = "intercom_end"

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

# Intercom (P9.2, ADR 0025 decision 5). `caller` is an HA device registry id,
# restricted to a paired tablet by the services.yaml device selector, and the
# call id's bounds mirror the schema's call_id (the same form as command_id,
# ADR 0002).
INTERCOM_CALL_SCHEMA = vol.Schema(
    {
        **cv.ENTITY_SERVICE_FIELDS,
        vol.Required("caller"): vol.All(cv.string, vol.Length(min=1, max=64)),
        vol.Optional("mode", default="call"): vol.In(["call", "broadcast"]),
    }
)

INTERCOM_END_SCHEMA = vol.Schema(
    {
        **cv.ENTITY_SERVICE_FIELDS,
        vol.Optional("call_id"): vol.All(cv.string, vol.Length(min=8, max=64)),
    }
)

TARGET_KEYS = set(cv.ENTITY_SERVICE_FIELDS)


def _targeted(call: ServiceCall) -> bool:
    """Whether this call names any target at all."""
    return any(call.data.get(key) for key in TARGET_KEYS)


def target_records(hass: HomeAssistant, hub: TabulaHub, call: ServiceCall) -> list[DeviceRecord]:
    """The paired tablets a service call targets; raises when it names none.

    Devices are used directly, and entities, areas, floors and labels resolve
    to the Tabula devices they belong to (ADR 0010 item 3). An area reaches its
    tablets through the device registry, so a tablet whose entities are all
    disabled still resolves.
    """
    selected = _referenced_entity_ids(hass, call)
    ha_devices = set(selected.referenced_devices)
    registry = er.async_get(hass)
    for entity_id in selected.referenced | selected.indirectly_referenced:
        entry = registry.async_get(entity_id)
        if entry is not None and entry.platform == DOMAIN and entry.device_id:
            ha_devices.add(entry.device_id)
    # SelectedEntities carries referenced_areas on every supported HA release
    # (ADR 0001 item 8); getattr keeps the floor leg honest if one lacks it.
    devices = dr.async_get(hass)
    for area_id in getattr(selected, "referenced_areas", set()):
        ha_devices.update(entry.id for entry in dr.async_entries_for_area(devices, area_id))
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

    async def intercom_call(call: ServiceCall) -> None:
        """Ring one tablet, or start a broadcast (P9.2, ADR 0025 decision 5).

        A two-way call names one device (or an area holding exactly one), and
        a broadcast with no target reaches every tablet that can hear, except
        the caller. The call itself, its relay and its timers live in
        `intercom.IntercomManager`; this resolves the caller and the target.
        """
        hub = TabulaHub.get(hass)
        if hub is None:
            raise ServiceValidationError(
                translation_domain=DOMAIN, translation_key="hub_not_loaded"
            )
        caller = hub.record_for_ha_device(call.data.get("caller"))
        if caller is None:
            raise ServiceValidationError(
                translation_domain=DOMAIN,
                translation_key="intercom_caller_unknown",
                translation_placeholders={"device": str(call.data.get("caller"))},
            )
        callees = target_records(hass, hub, call) if _targeted(call) else None
        await hub.intercom.async_start_call(caller, callees, call.data.get("mode", "call"))

    async def intercom_end(call: ServiceCall) -> None:
        """End a call by id, or whatever call the targeted tablets are in.

        Reaches every device in the call in every call state (ADR 0028 guard
        3), and raising when there is nothing to end keeps it from failing
        silently (`tabula.notify`'s rule, ADR 0010 item 3).
        """
        hub = TabulaHub.get(hass)
        if hub is None:
            raise ServiceValidationError(
                translation_domain=DOMAIN, translation_key="hub_not_loaded"
            )
        call_id = call.data.get("call_id")
        targeted = _targeted(call)
        if call_id and targeted:
            raise ServiceValidationError(
                translation_domain=DOMAIN, translation_key="intercom_end_one_of"
            )
        if call_id:
            await hub.intercom.async_end_call(call_id)
        elif targeted:
            await hub.intercom.async_end_device_calls(target_records(hass, hub, call))
        else:
            raise ServiceValidationError(
                translation_domain=DOMAIN, translation_key="intercom_end_needs_target"
            )

    hass.services.async_register(DOMAIN, SERVICE_NOTIFY, notify, schema=NOTIFY_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_NAVIGATE, navigate, schema=NAVIGATE_SCHEMA)
    hass.services.async_register(
        DOMAIN, SERVICE_SCREENSAVER, screensaver, schema=SCREENSAVER_SCHEMA
    )
    hass.services.async_register(
        DOMAIN, SERVICE_INTERCOM_CALL, intercom_call, schema=INTERCOM_CALL_SCHEMA
    )
    hass.services.async_register(
        DOMAIN, SERVICE_INTERCOM_END, intercom_end, schema=INTERCOM_END_SCHEMA
    )
