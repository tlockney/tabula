"""tabula/panel_settings and tabula/panel_settings_set: the settings editor (P5.3).

The read command is driven over the real WebSocket surface, so the payload is
built from the hub's own record and the settings store. The write command is
exercised twice: directly (validation, revisions, rejection) and end to end with
the mock device playing the Android app (one push per save, an offline save
delivered at the next registration, and a key the device's schema cannot take
stored but not pushed).
"""

from __future__ import annotations

import asyncio
from typing import Any

from custom_components.tabula import TabulaHub
from custom_components.tabula.const import SCHEMA_VERSION
from helpers import WS, Paired, frame_fixture, uid, ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.core import HomeAssistant

READ = {"type": "tabula/panel_settings"}
WRITE = {"type": "tabula/panel_settings_set"}


async def admin_client(hass: HomeAssistant, server: str, session) -> WS:
    """A WebSocket client authenticated as an admin (the panel's audience)."""
    user = await hass.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass.auth.async_create_refresh_token(user)
    return await ws_connect(session, server, refresh.token)


async def read_settings(client: WS, device_id: str, msg_id: int = 1) -> dict[str, Any]:
    """Call the read command and return its payload."""
    result = await client.reply({"id": msg_id, **READ, "device_id": device_id})
    assert result["success"] is True, result
    return result["result"]


async def write_settings(
    client: WS, device_id: str, values: dict[str, Any], msg_id: int = 2
) -> dict[str, Any]:
    """Call the write command and return its payload."""
    result = await client.reply({"id": msg_id, **WRITE, "device_id": device_id, "values": values})
    assert result["success"] is True, result
    return result["result"]


async def online_device(hass: HomeAssistant, session, server: str, paired: Paired) -> WS:
    """Register a paired device and leave its socket open."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    assert reply["success"], reply
    return client


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


# --- authority and addressing -------------------------------------------------


async def test_refused_for_a_non_admin(hass_setup, server, hub, session, paired):
    """Both commands are admin-only, like every panel command."""
    user = await hass_setup.auth.async_create_system_user("Not an admin")
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        read = await client.reply({"id": 1, **READ, "device_id": paired.device_id})
        write = await client.reply(
            {
                "id": 2,
                **WRITE,
                "device_id": paired.device_id,
                "values": {"screen_brightness": 10},
            }
        )
    finally:
        await client.close()

    assert read["success"] is False and read["error"]["code"] == "unauthorized"
    assert write["success"] is False and write["error"]["code"] == "unauthorized"


async def test_unknown_device_is_an_error(hass_setup, server, hub, session):
    """A device the hub does not know is device_not_found, not an empty payload."""
    client = await admin_client(hass_setup, server, session)
    try:
        read = await client.reply({"id": 1, **READ, "device_id": uid()})
        write = await client.reply(
            {"id": 2, **WRITE, "device_id": uid(), "values": {"screen_brightness": 10}}
        )
    finally:
        await client.close()

    for result in (read, write):
        assert result["success"] is False, result
        assert result["error"]["code"] == "device_not_found"


async def test_rejects_unknown_keys_in_the_message(hass_setup, server, hub, session, paired):
    """Each command validates its own message shape."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await client.reply(
            {"id": 1, **WRITE, "device_id": paired.device_id, "values": {}, "extra": 1}
        )
    finally:
        await client.close()

    assert result["success"] is False, result
    assert result["error"]["code"] == "invalid_format"


# --- the read payload ---------------------------------------------------------


async def test_read_a_device_with_no_document(hass_setup, server, hub, session, paired):
    """No stored document reads as the schema defaults, flagged as not stored."""
    client = await admin_client(hass_setup, server, session)
    try:
        payload = await read_settings(client, paired.device_id)
    finally:
        await client.close()

    assert payload["device_id"] == paired.device_id
    assert payload["ha_device_id"] == paired.ha_device_id
    assert payload["stored"] is False
    assert payload["ha_revision"] == 0
    assert payload["device_revision"] is None
    assert payload["pending"] is False
    assert payload["schema_version"] == SCHEMA_VERSION
    # Never registered: the hub knows no schema version for it.
    assert payload["device_schema_version"] is None
    # The document shown is the defaults, and they are what it would run with.
    assert payload["settings"] == payload["defaults"]
    assert payload["settings"]["screensaver"]["clock"]["text_color"] == "#FFFFFFFF"
    # A key with no default keeps its group out of the defaults document.
    assert "identity" not in payload["defaults"]


async def test_read_a_stored_device(hass_setup, server, hub, session, paired):
    """A stored document, its revision, and the same settings-state rule P5.2 lists."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    await runtime.settings.async_save(
        paired.device_id, {"screen": {"brightness": 70}, "identity": {"name": "Shed"}}
    )
    device = await online_device(hass_setup, session, server, paired)
    client = await admin_client(hass_setup, server, session)
    try:
        payload = await read_settings(client, paired.device_id)
    finally:
        await client.close()
        await device.close()

    assert payload["stored"] is True
    assert payload["ha_revision"] == 1
    # The device registered with settings_revision 0 and has not been pushed yet.
    assert payload["device_revision"] == 0
    assert payload["pending"] is True
    assert payload["settings"]["screen"]["brightness"] == 70
    assert payload["settings"]["identity"]["name"] == "Shed"
    # The defaults stay the schema's own: the editor shows them for unset keys.
    assert payload["defaults"]["screen"]["brightness"] == 60
    assert payload["device_schema_version"] == 1
    assert "screen" in payload["capabilities"]
    # Since: which schema version introduced each key (plan 5.4, P3.2's mapping).
    assert payload["field_since"]["screen_brightness"] == 1
    assert payload["field_since"]["schedule_slots"] == 2
    assert payload["field_since"]["voice_wake_word_enabled"] == 3


# --- writing ------------------------------------------------------------------


async def test_multi_key_write_is_one_revision(hass_setup, server, hub, session, paired):
    """Several keys save as one revision, so no device sees half a change (ADR 0011)."""
    client = await admin_client(hass_setup, server, session)
    values = {
        "screen_brightness": 42,
        "screensaver_idle_timeout": 120,
        "screensaver_clock_text_color": "#FF112233",
    }
    try:
        first = await write_settings(client, paired.device_id, values)
        second = await write_settings(client, paired.device_id, {"screen_brightness": 43}, 3)
    finally:
        await client.close()

    assert first == {"revision": 1, "applied": sorted(values), "rejected": []}
    assert second == {"revision": 2, "applied": ["screen_brightness"], "rejected": []}
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.revision == 2
    assert stored.settings["screen"]["brightness"] == 43
    assert stored.settings["screensaver"]["idle_timeout"] == 120
    assert stored.settings["screensaver"]["clock"]["text_color"] == "#FF112233"


async def test_one_rejected_key_still_applies_the_rest(hass_setup, server, hub, session, paired):
    """F-MGT-01: rejected keys come back with reasons and the valid ones apply."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(
            client,
            paired.device_id,
            {
                "screensaver_idle_timeout": 90,
                "screen_brightness": 200,  # above the schema's maximum
                "screensaver_mode": "not-a-mode",
            },
        )
    finally:
        await client.close()

    assert result["revision"] == 1
    assert result["applied"] == ["screensaver_idle_timeout"]
    rejected = {item["key"]: item["reason"] for item in result["rejected"]}
    assert set(rejected) == {"screen_brightness", "screensaver_mode"}
    assert "200" in rejected["screen_brightness"]
    assert "not-a-mode" in rejected["screensaver_mode"]
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.settings["screensaver"]["idle_timeout"] == 90
    assert stored.settings["screen"]["brightness"] == 60  # the schema default


async def test_nothing_valid_saves_nothing(hass_setup, server, hub, session, paired):
    """No accepted key means no save and no revision bump."""
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    await runtime.settings.async_save(paired.device_id, {"screen": {"brightness": 50}})
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(client, paired.device_id, {"screen_brightness": -1})
    finally:
        await client.close()

    assert result["revision"] is None
    assert result["applied"] == []
    assert [item["key"] for item in result["rejected"]] == ["screen_brightness"]
    stored = runtime.settings.get(paired.device_id)
    assert stored is not None
    assert stored.revision == 1
    assert stored.settings["screen"]["brightness"] == 50


async def test_unknown_and_non_leaf_keys_are_rejected_per_key(
    hass_setup, server, hub, session, paired
):
    """An unknown key and a group path are each that key's rejection, not a 500."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(
            client,
            paired.device_id,
            {
                "screensaver": {"idle_timeout": 30},
                "screensaver_colour": "red",
                "screensaver_idle_timeout": 30,
            },
        )
    finally:
        await client.close()

    assert result["applied"] == ["screensaver_idle_timeout"]
    reasons = {item["key"]: item["reason"] for item in result["rejected"]}
    assert set(reasons) == {"screensaver", "screensaver_colour"}
    assert "group" in reasons["screensaver"]
    assert "name" in reasons["screensaver_colour"]


async def test_wrong_shapes_are_rejected_per_key(hass_setup, server, hub, session, paired):
    """Array leaves and slot objects are validated as a whole, per key."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(
            client,
            paired.device_id,
            {
                # Not a list at all.
                "at_a_glance_entities": "sensor.door",
                # A slot without its required start.
                "schedule_slots": [{"mode": "clock"}],
                # A slot property the schema does not have.
                "widgets_entity_visible_values": ["ok", 3],
            },
        )
    finally:
        await client.close()

    assert result["applied"] == []
    reasons = {item["key"]: item["reason"] for item in result["rejected"]}
    assert set(reasons) == {
        "at_a_glance_entities",
        "schedule_slots",
        "widgets_entity_visible_values",
    }
    assert "start" in reasons["schedule_slots"]
    assert "3" in reasons["widgets_entity_visible_values"]


async def test_a_slot_list_saves_in_one_document(hass_setup, server, hub, session, paired):
    """The schedule's slots and At a Glance entities survive a round trip."""
    slots = [
        {"start": "22:30", "mode": "dim", "brightness": 5, "widgets": False},
        {"start": "06:30", "mode": "clock"},
    ]
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(
            client,
            paired.device_id,
            {"schedule_slots": slots, "at_a_glance_entities": ["sensor.door"]},
        )
        payload = await read_settings(client, paired.device_id, 3)
    finally:
        await client.close()

    assert result["revision"] == 1
    assert payload["settings"]["schedule"]["slots"] == slots
    assert payload["settings"]["at_a_glance"]["entities"] == ["sensor.door"]


async def test_identity_name_renames_the_ha_device(hass_setup, server, hub, session, paired):
    """A name write follows the options flow's rule and updates the registry."""
    client = await admin_client(hass_setup, server, session)
    try:
        await write_settings(client, paired.device_id, {"identity_name": "Kitchen Tablet"})
    finally:
        await client.close()

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert runtime.devices[paired.device_id].name == "Kitchen Tablet"


async def test_no_secrets_in_either_payload(hass_setup, server, hub, session, paired):
    """Credentials, tokens and user ids never travel with the settings."""
    device = await online_device(hass_setup, session, server, paired)
    client = await admin_client(hass_setup, server, session)
    try:
        read = await read_settings(client, paired.device_id)
        written = await write_settings(client, paired.device_id, {"screen_brightness": 33})
    finally:
        await client.close()
        await device.close()

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record = runtime.devices[paired.device_id]
    text = strings_in([read, written])
    assert paired.refresh_token not in text
    assert record.user_id not in text
    assert record.refresh_token_id not in text
    keys = keys_in([read, written])
    assert not keys & {
        "user_id",
        "refresh_token",
        "refresh_token_id",
        "nonce",
        "credential",
        "password",
        "token",
    }


# --- against the mock device --------------------------------------------------


async def connect_device(hass_setup, server, paired, tmp_path, settings_revision: int = 0):
    """A registered, subscribed mock device connected to the live hub.

    Same shape as the settings e2e suite's helper: the mock's TLPClient plays the
    Android app, so the integration pushes over a real socket.
    """
    from mockdevice.client import TLPClient
    from mockdevice.device import ALL_CAPABILITIES, MockDevice
    from mockdevice.store import DeviceStore

    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(
        capabilities=ALL_CAPABILITIES,
        base_url=server,
        settings_revision=settings_revision,
    )
    registered = asyncio.Event()
    client = TLPClient(server, store, device, on_registered=lambda result: registered.set())
    task = asyncio.create_task(_run_client(client))
    await asyncio.wait_for(registered.wait(), 10)
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    record = runtime.devices[paired.device_id]
    for _ in range(250):
        if record.current is not None and record.current.subscription_id is not None:
            break
        await asyncio.sleep(0.02)
    assert record.current is not None and record.current.subscription_id is not None
    return device, client, task


async def _run_client(client) -> None:
    import aiohttp

    async with aiohttp.ClientSession() as session:
        await client.run(session)


def count_pushes(device) -> list[int]:
    """Count the set_settings pushes the device receives, with their revisions."""
    revisions: list[int] = []
    original = device._cmd_set_settings

    def counting(args):
        revisions.append(args["revision"])
        return original(args)

    device._cmd_set_settings = counting
    return revisions


async def settle(predicate, timeout: float = 10.0) -> None:
    """Wait for a condition another task settles."""
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def test_a_write_reaches_the_device_in_one_push(
    hass_setup, server, hub, session, paired, tmp_path, hass_storage
):
    """A multi-key save is one revision and one set_settings command."""
    device, client, task = await connect_device(hass_setup, server, paired, tmp_path)
    pushes = count_pushes(device)
    admin = await admin_client(hass_setup, server, session)
    try:
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        result = await write_settings(
            admin,
            paired.device_id,
            {"screen_brightness": 25, "screensaver_idle_timeout": 240},
        )
        assert result["revision"] == 1
        await settle(lambda: device.settings_revision == 1)
        assert pushes == [1]
        assert device.settings["screen"]["brightness"] == 25
        assert device.settings["screensaver"]["idle_timeout"] == 240
        record = runtime.devices[paired.device_id]
        await settle(
            lambda: record.current is not None and record.current.reported_settings_revision == 1
        )
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def test_offline_write_is_pushed_after_reconnect(
    hass_setup, server, hub, session, paired, tmp_path, hass_storage
):
    """An offline device keeps the stored write and receives it on reconnect."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(client, paired.device_id, {"screen_brightness": 15})
    finally:
        await client.close()
    assert result["revision"] == 1

    device, mock, task = await connect_device(hass_setup, server, paired, tmp_path)
    try:
        await settle(lambda: device.settings_revision == 1)
        assert device.settings["screen"]["brightness"] == 15
    finally:
        mock.stop()
        await asyncio.wait_for(task, 10)


async def test_a_key_the_device_is_too_old_for_is_stored_not_pushed(
    hass_setup, server, hub, session, paired, tmp_path, hass_storage
):
    """A v3 key on a schema-1 device is stored and translated down, not lost."""
    device, client, task = await connect_device(hass_setup, server, paired, tmp_path)
    admin = await admin_client(hass_setup, server, session)
    try:
        result = await write_settings(
            admin,
            paired.device_id,
            {"voice_wake_word_enabled": True, "screen_brightness": 20},
        )
        assert result["applied"] == ["screen_brightness", "voice_wake_word_enabled"]
        await settle(lambda: device.settings_revision == 1)
        # The device took the revision and the key it cannot understand is gone.
        assert device.settings["screen"]["brightness"] == 20
        assert "voice" not in device.settings
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        stored = runtime.settings.get(paired.device_id)
        assert stored is not None
        assert stored.settings["voice"]["wake_word_enabled"] is True
        payload = await read_settings(admin, paired.device_id, 3)
        assert payload["field_since"]["voice_wake_word_enabled"] > (
            payload["device_schema_version"] or 0
        )
    finally:
        await admin.close()
        client.stop()
        await asyncio.wait_for(task, 10)
