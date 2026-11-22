"""Settings entities (P3.2, F-HA-11, ADR 0015).

Switches, a number and a text entity that show and write leaves of HA's own
settings document. Each write is one revision and one ``set_settings`` push;
the set of entities follows the settings schema version the device registered
with, not its capabilities; they stay available while the device is offline.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager

import aiohttp
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from custom_components.tabula.number import CHIME_VOLUME, IDLE_TIMEOUT
from custom_components.tabula.settings import SettingsSchema
from custom_components.tabula.switch import SETTING_SWITCHES
from custom_components.tabula.text import CLOCK_BACKGROUND
from homeassistant.const import STATE_UNAVAILABLE
from homeassistant.core import HomeAssistant
from homeassistant.helpers import entity_registry as er
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore

# (platform, unique-id key, settings key) for every settings entity
SETTING_ENTITIES = [
    *(("switch", d.key, d.setting) for d in SETTING_SWITCHES),
    ("number", IDLE_TIMEOUT.key, IDLE_TIMEOUT.setting),
    ("number", CHIME_VOLUME.key, CHIME_VOLUME.setting),
    ("text", CLOCK_BACKGROUND.key, CLOCK_BACKGROUND.setting),
]


def eid(hass: HomeAssistant, device_id: str, platform: str, key: str) -> str | None:
    return er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{device_id}_{key}")


async def until(predicate: Callable[[], bool], timeout: float = 10.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def _run(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


@asynccontextmanager
async def tablet(
    hass: HomeAssistant, server: str, paired, tmp_path, schema_version: int
) -> AsyncGenerator[tuple[MockDevice, TLPClient]]:
    """A registered, subscribed mock tablet on the given settings schema."""
    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(capabilities=ALL_CAPABILITIES, base_url=server)
    client = TLPClient(server, store, device, schema_version=schema_version)
    task = asyncio.create_task(_run(client))
    hub = TabulaHub.get(hass)
    assert hub is not None
    try:
        await until(
            lambda: (
                (r := hub.devices.get(paired.device_id)) is not None
                and r.current is not None
                and r.current.subscription_id is not None
            )
        )
        await hass.async_block_till_done()
        yield device, client
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


def test_every_setting_entity_names_a_shared_schema_leaf():
    """The keys exist, are editable, and v1 keys are told apart from v2 ones."""
    schema = SettingsSchema()
    editable = {f.key for f in schema.editable_fields()}
    since = {}
    for _, _, key in SETTING_ENTITIES:
        assert key in editable, key
        since[key] = schema.since(key)
    assert since == {
        "screensaver_enabled": 1,
        "screensaver_idle_timeout": 1,
        "screensaver_brightness_enabled": 2,
        "schedule_enabled": 2,
        "widgets_shown": 2,
        "screensaver_clock_background_source": 2,
        "audio_chime_volume": 2,
    }


async def test_entity_set_follows_the_device_schema_version(
    hass_setup, server, hub, paired, tmp_path
):
    """A schema-1 device gets only the v1 settings entities; schema 2 gets all, and back.

    Capabilities play no part: settings exist for every paired device.
    """
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1):
        present = {
            setting
            for platform, key, setting in SETTING_ENTITIES
            if eid(hass_setup, paired.device_id, platform, key)
        }
        assert present == {"screensaver_enabled", "screensaver_idle_timeout"}

    async with tablet(hass_setup, server, paired, tmp_path, schema_version=2):
        await until(
            lambda: all(eid(hass_setup, paired.device_id, p, k) for p, k, _ in SETTING_ENTITIES)
        )
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        # persisted, so the entity set survives an HA restart before reconnecting
        stored = runtime.entry.data["devices"][paired.device_id]
        assert stored["schema_version"] == 2

    # and back: a device that re-registers as schema 1 loses the v2-only entities
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1):
        await until(
            lambda: (
                {
                    setting
                    for platform, key, setting in SETTING_ENTITIES
                    if eid(hass_setup, paired.device_id, platform, key)
                }
                == {"screensaver_enabled", "screensaver_idle_timeout"}
            )
        )


async def test_master_switch_reaches_the_device_as_one_revision(
    hass_setup, server, hub, paired, tmp_path
):
    """Toggling the master switch saves one revision and pushes it (Done when)."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1) as (device, _):
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        switch = eid(hass_setup, paired.device_id, "switch", "screensaver_enabled")
        assert switch is not None
        assert hass_setup.states.get(switch).state == "on"  # the schema default

        await hass_setup.services.async_call(
            "switch", "turn_off", {"entity_id": switch}, blocking=True
        )
        await until(lambda: device.settings.get("screensaver", {}).get("enabled") is False)
        assert runtime.settings.revision(paired.device_id) == 1
        assert device.settings_revision == 1
        assert hass_setup.states.get(switch).state == "off"
        # the schema-1 device got a translated document: no v2 keys
        assert "clock" not in device.settings["screensaver"]
        assert "widgets" not in device.settings


async def test_v2_entities_write_their_leaves(hass_setup, server, hub, paired, tmp_path):
    """Each v2 settings entity writes its own leaf and reflects HA's copy."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=2) as (device, _):
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        dev = paired.device_id
        await until(lambda: all(eid(hass_setup, dev, p, k) for p, k, _ in SETTING_ENTITIES))

        for key in ("screensaver_brightness_enabled", "widgets_shown"):
            entity = eid(hass_setup, dev, "switch", key)
            await hass_setup.services.async_call(
                "switch", "turn_off", {"entity_id": entity}, blocking=True
            )
            assert hass_setup.states.get(entity).state == "off"
        schedule = eid(hass_setup, dev, "switch", "schedule_enabled")
        await hass_setup.services.async_call(
            "switch", "turn_on", {"entity_id": schedule}, blocking=True
        )
        timeout = eid(hass_setup, dev, "number", "screensaver_idle_timeout")
        await hass_setup.services.async_call(
            "number", "set_value", {"entity_id": timeout, "value": 120}, blocking=True
        )
        background = eid(hass_setup, dev, "text", "clock_background")
        await hass_setup.services.async_call(
            "text",
            "set_value",
            {"entity_id": background, "value": "https://example.org/pic.jpg"},
            blocking=True,
        )

        stored = runtime.settings.get(dev)
        assert stored is not None
        assert stored.revision == 5, "one revision per write"
        s = stored.settings
        assert s["screensaver"]["brightness_enabled"] is False
        assert s["widgets"]["shown"] is False
        assert s["schedule"]["enabled"] is True
        assert s["screensaver"]["idle_timeout"] == 120
        assert s["screensaver"]["clock"]["background_source"] == "https://example.org/pic.jpg"
        assert hass_setup.states.get(timeout).state == "120"
        assert hass_setup.states.get(background).state == "https://example.org/pic.jpg"
        # the schema-2 device converges on the last revision with the v2 keys
        await until(lambda: device.settings_revision == 5)
        assert device.settings["widgets"]["shown"] is False
        assert device.settings["screensaver"]["clock"]["background_source"].endswith("pic.jpg")


async def test_same_background_value_is_still_a_new_revision(
    hass_setup, server, hub, paired, tmp_path
):
    """F-SSV-09: writing the same source again is a write, so the device reloads."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=2) as (device, _):
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        dev = paired.device_id
        await until(lambda: eid(hass_setup, dev, "text", "clock_background") is not None)
        background = eid(hass_setup, dev, "text", "clock_background")
        for _ in range(2):
            await hass_setup.services.async_call(
                "text",
                "set_value",
                {"entity_id": background, "value": "https://example.org/same.jpg"},
                blocking=True,
            )
        assert runtime.settings.revision(dev) == 2
        await until(lambda: device.settings_revision == 2)


async def test_idle_timeout_bounds_come_from_the_schema(hass_setup, server, hub, paired, tmp_path):
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1):
        timeout = eid(hass_setup, paired.device_id, "number", "screensaver_idle_timeout")
        state = hass_setup.states.get(timeout)
        field_ = SettingsSchema().field("screensaver_idle_timeout")
        assert state.attributes["min"] == field_.minimum
        assert state.attributes["max"] == field_.maximum
        assert state.attributes["unit_of_measurement"] == "s"
        assert state.state == str(field_.default)


async def test_concurrent_edits_do_not_interleave_revisions(
    hass_setup, server, hub, paired, tmp_path
):
    """Two writes at once: two revisions, both values kept, device on the last."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=2) as (device, _):
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        dev = paired.device_id
        await until(lambda: all(eid(hass_setup, dev, p, k) for p, k, _ in SETTING_ENTITIES))
        await asyncio.gather(
            hass_setup.services.async_call(
                "switch",
                "turn_off",
                {"entity_id": eid(hass_setup, dev, "switch", "widgets_shown")},
                blocking=True,
            ),
            hass_setup.services.async_call(
                "number",
                "set_value",
                {
                    "entity_id": eid(hass_setup, dev, "number", "screensaver_idle_timeout"),
                    "value": 45,
                },
                blocking=True,
            ),
        )
        stored = runtime.settings.get(dev)
        assert stored is not None
        assert stored.revision == 2
        assert stored.settings["widgets"]["shown"] is False
        assert stored.settings["screensaver"]["idle_timeout"] == 45
        await until(lambda: device.settings_revision == 2)
        assert device.settings["widgets"]["shown"] is False
        assert device.settings["screensaver"]["idle_timeout"] == 45


async def test_offline_write_is_stored_and_converges_at_registration(
    hass_setup, server, hub, paired, tmp_path
):
    """Settings entities stay available offline; the write lands at reconnect."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1) as (device, _):
        pass  # registered once: the entities exist
    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    dev = paired.device_id
    await until(lambda: runtime.devices[dev].current is None)
    switch = eid(hass_setup, dev, "switch", "screensaver_enabled")
    await hass_setup.async_block_till_done()
    assert hass_setup.states.get(switch).state != STATE_UNAVAILABLE

    await hass_setup.services.async_call("switch", "turn_off", {"entity_id": switch}, blocking=True)
    assert runtime.settings.revision(dev) == 1
    assert hass_setup.states.get(switch).state == "off"

    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1) as (device, _):
        await until(lambda: device.settings_revision == 1)
        assert device.settings["screensaver"]["enabled"] is False


async def test_options_flow_style_save_refreshes_the_entity(
    hass_setup, server, hub, paired, tmp_path
):
    """Any save (options flow, service) shows on the entity: HA's copy is the value."""
    async with tablet(hass_setup, server, paired, tmp_path, schema_version=1):
        runtime = TabulaHub.get(hass_setup)
        assert runtime is not None
        dev = paired.device_id
        timeout = eid(hass_setup, dev, "number", "screensaver_idle_timeout")
        await runtime.settings.async_save(dev, {"screensaver": {"idle_timeout": 900}})
        await hass_setup.async_block_till_done()
        assert hass_setup.states.get(timeout).state == "900"
