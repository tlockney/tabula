"""tabula/panel_devices: the Tabularium's joined device snapshot (P5.2).

The command is driven over the real WebSocket surface against a paired device,
so the payload is built from the hub's own records, HA's device registry and the
entity registry - the three sources the panel must not stitch itself.
"""

from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any

from custom_components.tabula import DeviceRecord, TabulaHub
from custom_components.tabula.const import PROTOCOL_VERSION
from helpers import WS, Paired, frame_fixture, uid, ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.core import HomeAssistant
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr

COMMAND = {"type": "tabula/panel_devices"}


async def admin_client(hass: HomeAssistant, server: str, session) -> WS:
    """A WebSocket client authenticated as an admin (the panel's audience)."""
    user = await hass.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass.auth.async_create_refresh_token(user)
    return await ws_connect(session, server, refresh.token)


async def devices_of(client: WS, msg_id: int = 1) -> list[dict[str, Any]]:
    """Call the command and return its list."""
    result = await client.reply({"id": msg_id, **COMMAND})
    assert result["success"] is True, result
    assert isinstance(result["result"], list), result
    return result["result"]


async def online_device(hass: HomeAssistant, session, server: str, paired: Paired) -> WS:
    """Register a paired device and leave its socket open."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    assert reply["success"], reply
    return client


async def wait_offline(hass: HomeAssistant, paired: Paired, timeout: float = 5.0) -> None:
    """Wait for the hub to notice the socket closed."""
    hub = TabulaHub.get(hass)
    assert hub is not None
    async with asyncio.timeout(timeout):
        while hub.devices[paired.device_id].connected:
            await hass.async_block_till_done()
            await asyncio.sleep(0.01)


def strings_in(value: Any) -> list[str]:
    """Every string anywhere in a JSON-shaped payload."""
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [item for key, child in value.items() for item in (key, *strings_in(child))]
    if isinstance(value, list):
        return [item for child in value for item in strings_in(child)]
    return []


def keys_in(value: Any) -> set[str]:
    """Every dict key anywhere in a JSON-shaped payload."""
    if isinstance(value, dict):
        nested = {child for item in value.values() for child in keys_in(item)}
        return set(value) | nested
    if isinstance(value, list):
        return {child for item in value for child in keys_in(item)}
    return set()


# --- admin authority ---------------------------------------------------------


async def test_refused_for_a_non_admin(hass_setup, server, hub, session):
    """The device snapshot is admin-only, like every panel command."""
    user = await hass_setup.auth.async_create_system_user("Not an admin")
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        result = await client.reply({"id": 1, **COMMAND})
    finally:
        await client.close()

    assert result["success"] is False, result
    assert result["error"]["code"] == "unauthorized"


async def test_rejects_unknown_keys(hass_setup, server, hub, session):
    """Each panel command validates its own message shape."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await client.reply({"id": 1, **COMMAND, "device_id": "x"})
    finally:
        await client.close()

    assert result["success"] is False, result
    assert result["error"]["code"] == "invalid_format"


async def test_empty_list_with_no_hub(hass_setup, server, session):
    """An unloaded hub is an empty list, not an error (the panel outlives it)."""
    client = await admin_client(hass_setup, server, session)
    try:
        assert await devices_of(client) == []
    finally:
        await client.close()


# --- the online snapshot -----------------------------------------------------


async def test_online_device_payload(hass_setup, server, hub, session, paired):
    """One entry per hub record, joined with the registry and the reported state."""
    area = ar.async_get(hass_setup).async_create("Office")
    dr.async_get(hass_setup).async_update_device(paired.ha_device_id, area_id=area.id)
    device = await online_device(hass_setup, session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        devices = await devices_of(admin)
    finally:
        await admin.close()
        await device.close()

    assert len(devices) == 1
    entry = devices[0]
    assert entry["device_id"] == paired.device_id
    assert entry["ha_device_id"] == paired.ha_device_id
    assert entry["name"] == "Office Kiosk"
    assert entry["model"] == "KFTRPWI"
    assert entry["area_id"] == area.id
    assert entry["area_name"] == "Office"
    assert entry["connected"] is True
    assert entry["last_seen"] is None
    assert entry["app_version"] == "0.1.0"
    assert entry["os_version"].startswith("Fire OS")
    assert entry["protocol_version"] == PROTOCOL_VERSION
    assert entry["settings_schema_version"] == 1
    assert "screen" in entry["capabilities"]
    # The fixture's register frame carries settings_revision 0 and HA stores
    # nothing yet, so the two agree.
    assert entry["settings"] == {"ha_revision": 0, "device_revision": 0, "pending": False}
    assert entry["current_page"] == "/lovelace/0"
    assert entry["screen"] == {
        "screen_on": True,
        "brightness": 60,
        "screensaver_active": False,
        "screensaver_mode": "clock",
    }
    # Entities grouped by platform, each with its live state.
    assert "sensor" in entry["entities"]
    sensors = entry["entities"]["sensor"]
    assert all(set(item) == {"entity_id", "name", "state"} for item in sensors)
    assert all(item["entity_id"].split(".")[0] == "sensor" for item in sensors)
    assert any(item["state"] not in (None, "unavailable") for item in sensors)
    # Grouped platforms are the domains of the device's own entities.
    assert set(entry["entities"]) <= {
        "binary_sensor",
        "button",
        "event",
        "image",
        "light",
        "media_player",
        "notify",
        "number",
        "select",
        "sensor",
        "switch",
        "text",
    }


async def test_pending_settings_are_reported(hass_setup, server, hub, session, paired):
    """HA holding a newer revision than the device reported shows as pending."""
    device = await online_device(hass_setup, session, server, paired)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    await hub_runtime.settings.async_save(paired.device_id, {})
    await hass_setup.async_block_till_done()

    admin = await admin_client(hass_setup, server, session)
    try:
        entry = (await devices_of(admin))[0]
    finally:
        await admin.close()
        await device.close()

    assert entry["settings"]["ha_revision"] == 1
    assert entry["settings"]["device_revision"] == 0
    assert entry["settings"]["pending"] is True


# --- last seen ---------------------------------------------------------------


async def test_last_seen_is_recorded_on_disconnect(hass_setup, server, hub, session, paired):
    """A registered session ending writes last_seen (RFC 3339) and persists it."""
    device = await online_device(hass_setup, session, server, paired)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].last_seen is None

    await device.close()
    await wait_offline(hass_setup, paired)

    record = hub_runtime.devices[paired.device_id]
    assert record.connected is False
    assert record.last_seen is not None
    assert record.last_seen.endswith("Z")
    datetime.fromisoformat(record.last_seen.replace("Z", "+00:00"))
    # Persisted with the record ...
    stored = hass_setup.config_entries.async_entries("tabula")[0]
    assert stored.data["devices"][paired.device_id]["last_seen"] == record.last_seen
    # ... and shown as the offline device's last contact.
    admin = await admin_client(hass_setup, server, session)
    try:
        entry = (await devices_of(admin))[0]
    finally:
        await admin.close()
    assert entry["connected"] is False
    assert entry["last_seen"] == record.last_seen


async def test_last_seen_survives_a_reload(hass_setup, server, hub, session, paired):
    """The value lives in entry data, so an HA restart keeps it."""
    device = await online_device(hass_setup, session, server, paired)
    await device.close()
    await wait_offline(hass_setup, paired)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    seen = hub_runtime.devices[paired.device_id].last_seen
    assert seen is not None

    assert await hass_setup.config_entries.async_reload(hub.entry_id)
    await hass_setup.async_block_till_done()

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].last_seen == seen
    assert hub_runtime.devices[paired.device_id].connected is False


async def test_a_device_without_a_registry_entry_is_still_listed(hass_setup, server, hub, session):
    """The record is the list's spine; the registry only supplies display names."""
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = DeviceRecord(
        device_id=uid(),
        user_id="no-such-user",
        refresh_token_id="no-such-token",
        ha_device_id="a-registry-entry-that-does-not-exist",
        name="Shed Tablet",
    )
    hub_runtime.devices[record.device_id] = record

    admin = await admin_client(hass_setup, server, session)
    try:
        devices = await devices_of(admin)
    finally:
        await admin.close()

    entry = next(item for item in devices if item["device_id"] == record.device_id)
    assert entry["name"] == "Shed Tablet"
    assert entry["model"] is None
    assert entry["area_id"] is None and entry["area_name"] is None
    assert entry["connected"] is False
    assert entry["entities"] == {}


# --- secrets -----------------------------------------------------------------


async def test_no_secrets_in_the_payload(hass_setup, server, hub, session, paired):
    """Credentials, tokens and user ids never leave the hub."""
    device = await online_device(hass_setup, session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        devices = await devices_of(admin)
    finally:
        await admin.close()
        await device.close()

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    text = strings_in(devices)
    assert paired.refresh_token not in text
    assert record.user_id not in text
    assert record.refresh_token_id not in text
    keys = keys_in(devices)
    assert not keys & {
        "user_id",
        "refresh_token",
        "refresh_token_id",
        "nonce",
        "credential",
        "password",
    }
