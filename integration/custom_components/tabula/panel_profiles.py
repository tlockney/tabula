"""tabula/panel_profiles and friends: the Tabularium's Profiles section (P5.6).

Seven commands, all admin-only like every ``tabula/panel_*`` command (P5.1), and
all of them a view over ``profiles.ProfileStore`` (ADR 0035):

* ``tabula/panel_profiles`` - every profile with its members, the assignments and
  the devices the panel can assign;
* ``tabula/panel_profile_save`` - create or replace a profile, then apply it;
* ``tabula/panel_profile_delete`` - remove a profile; members move to the empty one;
* ``tabula/panel_profile_assign`` - put a device in a profile;
* ``tabula/panel_profile_diff`` - what a profile (saved or unsaved) would change;
* ``tabula/panel_profile_sync`` - apply a profile now;
* ``tabula/panel_drift`` - members that disagree with HA;
* ``tabula/panel_profile_override`` - set or clear one key's per-device exception.

No payload carries a credential, token or pairing detail: profiles hold
``shared``-role settings only, and the role rule is enforced here by the store,
not by the panel that happens to hide those keys (F-MGT-03).
"""

from __future__ import annotations

from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.components.websocket_api.connection import ActiveConnection
from homeassistant.core import HomeAssistant, callback

from . import DeviceRecord, TabulaHub
from .const import SCHEMA_VERSION
from .profiles import Profile, ProfileError, ProfileStore

NOT_REGISTERED = "not_registered"
PROFILE_NOT_FOUND = "profile_not_found"
DEVICE_NOT_FOUND = "device_not_found"
INVALID_PROFILE = "invalid_profile"


def profile_payload(store: ProfileStore, profile: Profile) -> dict[str, Any]:
    """One profile as the panel reads it: values, members and the empty flag."""
    return {
        "profile_id": profile.profile_id,
        "name": profile.name,
        "builtin": profile.builtin,
        "values": profile.values,
        "members": store.members(profile.profile_id),
        "member_count": len(store.members(profile.profile_id)),
    }


def device_payload(store: ProfileStore, record: DeviceRecord) -> dict[str, Any]:
    """One assignable device: identity, link state and its current exceptions."""
    return {
        "device_id": record.device_id,
        "ha_device_id": record.ha_device_id,
        "name": record.name,
        "connected": record.connected,
        "last_seen": record.last_seen,
        "settings_schema_version": record.schema_version,
        "profile_id": store.assignment(record.device_id),
        "overrides": store.overrides(record.device_id),
    }


def profiles_snapshot(hub: TabulaHub) -> dict[str, Any]:
    """Everything the Profiles section needs, in one call (P5.6 step 8)."""
    store = hub.profiles
    return {
        "schema_version": SCHEMA_VERSION,
        "profiles": [profile_payload(store, profile) for profile in store.profiles()],
        "assignments": {device_id: store.assignment(device_id) for device_id in hub.devices},
        "devices": [
            device_payload(store, record)
            for record in sorted(hub.devices.values(), key=lambda item: item.device_id)
        ],
    }


def _hub(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> TabulaHub | None:
    """The live hub, or a not_registered reply and None."""
    hub = TabulaHub.get(hass)
    if hub is None:
        connection.send_error(msg["id"], NOT_REGISTERED, "Tabula is not set up.")
    return hub


def _profile(
    store: ProfileStore, connection: ActiveConnection, msg: dict[str, Any]
) -> Profile | None:
    """The profile a command names, or a profile_not_found reply and None."""
    profile = store.get(msg["profile_id"])
    if profile is None:
        connection.send_error(msg["id"], PROFILE_NOT_FOUND, "No profile has that id.")
    return profile


def _record(
    hub: TabulaHub, connection: ActiveConnection, msg: dict[str, Any]
) -> DeviceRecord | None:
    """The device a command names, or a device_not_found reply and None."""
    record = hub.devices.get(msg["device_id"])
    if record is None:
        connection.send_error(msg["id"], DEVICE_NOT_FOUND, "No paired device has that id.")
    return record


def _invalid(connection: ActiveConnection, msg: dict[str, Any], error: ProfileError) -> None:
    connection.send_error(msg["id"], INVALID_PROFILE, str(error))


@websocket_api.websocket_command({vol.Required("type"): "tabula/panel_profiles"})
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profiles(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profiles: profiles, assignments and assignable devices."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    connection.send_result(msg["id"], profiles_snapshot(hub))


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_save",
        vol.Required("name"): str,
        vol.Optional("profile_id"): str,
        vol.Optional("values", default=dict): dict,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_save(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_save: validate, store, then apply to the members."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    try:
        profile, rejected = await hub.profiles.async_save(
            profile_id=msg.get("profile_id"), name=msg["name"], values=msg["values"]
        )
    except ProfileError as e:
        _invalid(connection, msg, e)
        return
    applied = await hub.profiles.async_apply_profile(profile.profile_id)
    connection.send_result(
        msg["id"],
        {
            "profile": profile_payload(hub.profiles, profile),
            "applied": applied,
            "rejected": rejected,
        },
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_delete",
        vol.Required("profile_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_delete(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_delete: remove a profile; its members become unassigned.

    Nothing is written to those devices: they keep their last applied values as
    their own (F-MGT-02, ADR 0035).
    """
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    try:
        moved = await hub.profiles.async_delete(msg["profile_id"])
    except ProfileError as e:
        _invalid(connection, msg, e)
        return
    connection.send_result(msg["id"], {"deleted": msg["profile_id"], "moved": moved})


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_assign",
        vol.Required("device_id"): str,
        vol.Required("profile_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_assign(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_assign: put one device in a profile and apply it."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    record = _record(hub, connection, msg)
    if record is None:
        return
    if hub.profiles.get(msg["profile_id"]) is None:
        connection.send_error(msg["id"], PROFILE_NOT_FOUND, "No profile has that id.")
        return
    applied = await hub.profiles.async_assign(record.device_id, msg["profile_id"])
    connection.send_result(
        msg["id"],
        {
            "device_id": record.device_id,
            "profile_id": hub.profiles.assignment(record.device_id),
            "applied": applied,
            "overrides": hub.profiles.overrides(record.device_id),
        },
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_diff",
        vol.Optional("profile_id"): str,
        vol.Optional("device_id"): str,
        # An unsaved document, so the editor can preview before saving.
        vol.Optional("values"): dict,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_diff(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_diff: covered keys against what each member holds."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    profile_id = msg.get("profile_id")
    if profile_id is not None and hub.profiles.get(profile_id) is None:
        connection.send_error(msg["id"], PROFILE_NOT_FOUND, "No profile has that id.")
        return
    device_id = msg.get("device_id")
    if device_id is not None and device_id not in hub.devices:
        connection.send_error(msg["id"], DEVICE_NOT_FOUND, "No paired device has that id.")
        return
    try:
        rows = hub.profiles.diff(
            profile_id=profile_id,
            values=msg.get("values"),
            device_ids=None if device_id is None else [device_id],
        )
    except ProfileError as e:
        _invalid(connection, msg, e)
        return
    connection.send_result(
        msg["id"], {"rows": rows, "changing": sum(1 for row in rows if row["changes"])}
    )


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_sync",
        vol.Required("profile_id"): str,
        vol.Optional("device_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_sync(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_sync: apply a profile to all members or to one."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    if hub.profiles.get(msg["profile_id"]) is None:
        connection.send_error(msg["id"], PROFILE_NOT_FOUND, "No profile has that id.")
        return
    device_id = msg.get("device_id")
    if device_id is not None and device_id not in hub.devices:
        connection.send_error(msg["id"], DEVICE_NOT_FOUND, "No paired device has that id.")
        return
    applied = await hub.profiles.async_apply_profile(
        msg["profile_id"], device_ids=None if device_id is None else [device_id]
    )
    connection.send_result(msg["id"], {"applied": applied})


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_drift",
        vol.Optional("profile_id"): str,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_drift(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_drift: members behind HA's revision, overridden or too old."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    profile_id = msg.get("profile_id")
    if profile_id is not None and hub.profiles.get(profile_id) is None:
        connection.send_error(msg["id"], PROFILE_NOT_FOUND, "No profile has that id.")
        return
    connection.send_result(msg["id"], {"members": hub.profiles.drift(profile_id)})


@websocket_api.websocket_command(
    {
        vol.Required("type"): "tabula/panel_profile_override",
        vol.Required("device_id"): str,
        vol.Required("key"): str,
        # None clears the override and applies the profile's value.
        vol.Optional("value"): object,
    }
)
@websocket_api.require_admin
@websocket_api.async_response
async def ws_panel_profile_override(
    hass: HomeAssistant, connection: ActiveConnection, msg: dict[str, Any]
) -> None:
    """tabula/panel_profile_override: set or clear one key's per-device exception."""
    hub = _hub(hass, connection, msg)
    if hub is None:
        return
    record = _record(hub, connection, msg)
    if record is None:
        return
    try:
        applied = await hub.profiles.async_set_override(
            record.device_id, msg["key"], msg.get("value")
        )
    except ProfileError as e:
        _invalid(connection, msg, e)
        return
    connection.send_result(
        msg["id"],
        {
            "device_id": record.device_id,
            "overrides": hub.profiles.overrides(record.device_id),
            "applied": applied,
        },
    )


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the profile commands (called from panel_api.async_register)."""
    for command in (
        ws_panel_profiles,
        ws_panel_profile_save,
        ws_panel_profile_delete,
        ws_panel_profile_assign,
        ws_panel_profile_diff,
        ws_panel_profile_sync,
        ws_panel_drift,
        ws_panel_profile_override,
    ):
        websocket_api.async_register_command(hass, command)
