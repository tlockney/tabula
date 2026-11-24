"""tabula/panel_settings and tabula/panel_settings_set: the per-device editor (P5.3).

The Tabularium's Settings tab reads and writes one device's settings document.
Settings stay authoritative in HA (plan 5.6): these commands are a second editor
next to the options flow, not a second store. A write validates each submitted
leaf against the schema, saves the valid keys as **one** revision through
settings.async_set_settings (ADR 0011: one save and one push, so a device never
sees half a change) and returns what applied and what did not, with a reason per
rejected key (F-MGT-01).

Validation is per key by design. Each leaf's own subschema (settings.py
`SettingsSchema.validate_value`) judges that value alone, so one bad key is
rejected while its neighbours still apply; then the assembled document is
validated once as a cross-key guard, and any key implicated in a document-level
problem is rejected too.

Both commands are admin-only, like every `tabula/panel_*` command, and neither
payload carries a device credential, token or pairing detail (P5.1): the
document holds only settings, and `connection.ha_url` is HA's own address.
"""

from __future__ import annotations

import copy
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant, callback

from . import DeviceRecord, TabulaHub
from .const import SCHEMA_VERSION
from .panel_api import settings_state
from .settings import SettingsError, async_set_settings, set_path

DEVICE_NOT_FOUND = "device_not_found"
NOT_REGISTERED = "not_registered"


def settings_snapshot(hub: TabulaHub, record: DeviceRecord) -> dict[str, Any]:
    """Everything the Settings tab needs for one device (P5.3, step 1).

    The stored document, or the schema defaults when the device has none (with
    ``stored`` saying which); the defaults on their own, so the editor can show
    an unset key as its default; the revisions and the push state from
    panel_api's single rule; the device's schema version, so a key it is too old
    to receive can say so (`field_since` maps every key to the version that
    introduced it, settings.SettingsSchema.since); and the capabilities list,
    which tells the editor what the device can do at all.

    Settings only travel outward, and only settings: no credential, token,
    pairing nonce or user id enters the payload.
    """
    schema = hub.settings.schema
    stored = hub.settings.get(record.device_id)
    return {
        "device_id": record.device_id,
        "ha_device_id": record.ha_device_id,
        "name": record.name,
        "connected": record.connected,
        "stored": stored is not None,
        "settings": stored.settings if stored is not None else schema.defaults(),
        "defaults": schema.defaults(),
        "schema_version": SCHEMA_VERSION,
        "device_schema_version": record.schema_version,
        "capabilities": list(record.capabilities),
        "field_since": {field.key: schema.since(field.key) for field in schema.fields()},
        **settings_state(hub, record),
    }


def _blamed_keys(problems: list[str], keys: tuple[str, ...] | list[str]) -> set[str]:
    """The submitted keys a document-level problem points at.

    A problem names a path (``schedule/slots/0/start: ...``); the key whose leaf
    the path runs through is the one to reject. A path that names no submitted
    key blames none of them, which the caller treats as a problem with the whole
    save.
    """
    blamed: set[str] = set()
    for problem in problems:
        path = problem.partition(": ")[0].replace("/", "_")
        for key in keys:
            if path == key or path.startswith(f"{key}_"):
                blamed.add(key)
    return blamed


def split_values(
    hub: TabulaHub, base: dict[str, Any], values: dict[str, Any]
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    """Split submitted values into what the schema accepts and what it rejects.

    Per key: a group key is a wrong shape (the editor edits leaves), an unknown
    key names nothing the schema has, and a value the leaf's own subschema
    refuses comes back with the schema's message. The accepted keys are then
    applied to ``base`` and the document is validated once, so a cross-key rule
    (should the schema ever grow one) still rejects rather than half-saving.
    """
    schema = hub.settings.schema
    groups = schema.group_keys()
    accepted: dict[str, Any] = {}
    rejected: list[dict[str, str]] = []
    for key, value in values.items():
        key = str(key)
        if key in groups:
            rejected.append(
                {"key": key, "reason": "This is a group; set the keys inside it instead."}
            )
            continue
        try:
            problems = schema.validate_value(key, value)
        except KeyError:
            rejected.append({"key": key, "reason": "No setting has that name."})
            continue
        if problems:
            rejected.append({"key": key, "reason": "; ".join(problems)})
            continue
        accepted[key] = value

    # The cross-key guard: at most one pass per key, so this terminates.
    while accepted:
        document = copy.deepcopy(base)
        for key, value in accepted.items():
            set_path(document, schema.field(key).path, value)
        try:
            schema.validate(document)
        except SettingsError as e:
            blamed = _blamed_keys(e.problems, list(accepted))
            # ``blamed`` empty means the problem names no submitted key: reject
            # them all. Copy it: the set/dict is mutated inside the loop.
            for key in list(blamed or accepted):
                rejected.append({"key": key, "reason": "; ".join(e.problems)})
                del accepted[key]
            continue
        break
    return accepted, rejected


async def async_apply_values(
    hub: TabulaHub, record: DeviceRecord, values: dict[str, Any]
) -> dict[str, Any]:
    """Save the valid keys as one revision; return applied and rejected (step 2).

    Nothing valid means no save and no revision bump: the response carries
    ``revision: None`` and the rejected list alone. A save bumps the revision
    and schedules the push exactly as an entity or options-flow write does, so
    an offline device keeps the stored write and receives it at its next
    registration (plan 5.6). The revision comes back so the editor can wait for
    the device's ACK (its reported revision) before claiming it applied.
    """
    stored = hub.settings.get(record.device_id)
    base = stored.settings if stored is not None else hub.settings.schema.defaults()
    accepted, rejected = split_values(hub, base, values)
    if not accepted:
        return {"revision": None, "applied": [], "rejected": rejected}
    try:
        saved = await async_set_settings(hub, record.device_id, accepted)
    except SettingsError as e:
        # Defensive: split_values already validated the assembled document, so
        # this is a schema/code mismatch. Report it per key, never as a 500.
        return {
            "revision": None,
            "applied": [],
            "rejected": [{"key": key, "reason": "; ".join(e.problems)} for key in accepted]
            + rejected,
        }
    hub.apply_identity(record)
    return {"revision": saved.revision, "applied": sorted(accepted), "rejected": rejected}


def _record(
    hub: TabulaHub | None, connection: ActiveConnection, msg: dict[str, Any]
) -> DeviceRecord | None:
    """The record the command names, or an error reply and None."""
    if hub is None:
        connection.send_error(msg["id"], NOT_REGISTERED, "Tabula is not set up.")
        return None
    record = hub.devices.get(msg["device_id"])
    if record is None:
        connection.send_error(msg["id"], DEVICE_NOT_FOUND, "No paired device has that id.")
        return None
    return record


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_settings",
        vol.Required("device_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_settings(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_settings: one device's stored settings and schema state."""
    hub = TabulaHub.get(hass)
    record = _record(hub, connection, msg)
    if hub is None or record is None:
        return
    connection.send_result(msg["id"], settings_snapshot(hub, record))


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_settings_set",
        vol.Required("device_id"): str,
        # {flat settings key: value}. Keys and values are judged per key below,
        # so a wrong shape is that key's rejection, not a protocol error.
        vol.Required("values"): dict,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_settings_set(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_settings_set: validate, then save as one revision."""
    hub = TabulaHub.get(hass)
    record = _record(hub, connection, msg)
    if hub is None or record is None:
        return
    connection.send_result(msg["id"], await async_apply_values(hub, record, msg["values"]))


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the settings commands (called from panel_api.async_register)."""
    websocket_api.async_register_command(hass, ws_panel_settings)
    websocket_api.async_register_command(hass, ws_panel_settings_set)
