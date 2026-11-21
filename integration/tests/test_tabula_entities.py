"""Phase 1 entity platforms (P1.3, plan 5.5).

State deltas reach entities quickly, availability follows the connection,
entities exist only for declared capabilities, and nothing is removed on
disconnect.
"""

from __future__ import annotations

import asyncio
import time

import pytest
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from custom_components.tabula.entity import TabulaEntity
from custom_components.tabula.sensor import _timestamp
from helpers import Paired, frame_fixture, pair_device, ws_connect
from homeassistant.const import STATE_UNAVAILABLE
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er

ALL_CAPS = frame_fixture("register")["capabilities"]


def entity_id(hass: HomeAssistant, platform: str, paired: Paired, key: str) -> str | None:
    return er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{paired.device_id}_{key}")


async def register(client, paired: Paired, capabilities: list[str] | None = None) -> dict:
    overrides: dict = {"id": 2, "device_id": paired.device_id}
    if capabilities is not None:
        overrides["capabilities"] = capabilities
    reply = await client.reply(frame_fixture("register", **overrides))
    assert reply["success"], reply
    return reply["result"]


async def wait_state(hass: HomeAssistant, eid: str, predicate, timeout: float = 1.0) -> float:
    """Seconds until predicate(state) holds; fails after timeout."""
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        state = hass.states.get(eid)
        if state is not None and predicate(state):
            return time.monotonic() - start
        await asyncio.sleep(0.005)
    raise AssertionError(f"{eid} did not reach the expected state: {hass.states.get(eid)}")


async def test_entities_created_from_register(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()

    expected = {
        ("light", "screen"): "on",
        ("switch", "screensaver"): "off",
        ("switch", "keep_screen_on"): "on",
        ("switch", "adaptive_brightness"): "off",
        ("number", "screensaver_brightness"): "20",
        ("select", "screensaver_mode"): "clock",
        ("sensor", "battery"): "87",
        ("binary_sensor", "charging"): "on",
        ("sensor", "current_page"): "/lovelace/0",
        ("sensor", "network"): "wifi",
        ("sensor", "app_version"): "0.1.0",
        ("media_player", "speaker"): "idle",
    }
    for (platform, key), value in expected.items():
        eid = entity_id(hass_setup, platform, paired, key)
        assert eid is not None, (platform, key)
        assert hass_setup.states.get(eid).state == value, (platform, key)

    light = hass_setup.states.get(entity_id(hass_setup, "light", paired, "screen"))
    assert light.attributes["brightness"] == round(60 * 255 / 100)
    speaker = hass_setup.states.get(entity_id(hass_setup, "media_player", paired, "speaker"))
    assert speaker.attributes["volume_level"] == 0.5
    assert speaker.attributes["is_volume_muted"] is False
    nxt = hass_setup.states.get(entity_id(hass_setup, "sensor", paired, "next_screensaver"))
    assert nxt.state == "2026-11-21T14:08:12+00:00"
    for key in ("reload", "restart_app", "clear_cache"):
        assert entity_id(hass_setup, "button", paired, key) is not None
    assert entity_id(hass_setup, "notify", paired, "notifications") is not None
    await client.close()


async def test_state_delta_reaches_entity_under_one_second(
    hass_setup, server, hub, session, paired
):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "light", paired, "screen")
    assert eid is not None

    await client.send(frame_fixture("state_delta", id=3, state={"brightness": 42}))
    elapsed = await wait_state(
        hass_setup, eid, lambda s: s.attributes.get("brightness") == round(42 * 255 / 100)
    )
    assert elapsed < 1.0

    # A delta for keys the entity does not read leaves it alone (one write per
    # affected entity per frame).
    before = hass_setup.states.get(eid).last_updated
    reply = await client.reply(frame_fixture("state_delta", id=4, state={"battery_level": 50}))
    assert reply["success"], reply
    battery = entity_id(hass_setup, "sensor", paired, "battery")
    await wait_state(hass_setup, battery, lambda s: s.state == "50")
    assert hass_setup.states.get(eid).last_updated == before
    await client.close()


async def test_disconnect_unavailable_reconnect_restores(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    ids = [
        e.entity_id
        for e in er.async_entries_for_config_entry(er.async_get(hass_setup), hub.entry_id)
        if e.unique_id.startswith(paired.device_id)
        and e.disabled_by is None
        and not e.unique_id.endswith("_connected")
    ]
    assert len(ids) >= 10

    await client.close()
    for eid in ids:
        await wait_state(hass_setup, eid, lambda s: s.state == STATE_UNAVAILABLE)
    # Nothing is removed on disconnect.
    registry = er.async_get(hass_setup)
    assert all(registry.async_get(eid) is not None for eid in ids)

    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    for eid in ids:
        await wait_state(hass_setup, eid, lambda s: s.state != STATE_UNAVAILABLE)
    await client.close()


async def test_undeclared_capability_creates_no_entity(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    caps = [c for c in ALL_CAPS if c not in {"audio", "notifications", "battery"}]
    await register(client, paired, caps)
    await hass_setup.async_block_till_done()

    assert entity_id(hass_setup, "media_player", paired, "speaker") is None
    assert entity_id(hass_setup, "notify", paired, "notifications") is None
    assert entity_id(hass_setup, "sensor", paired, "battery") is None
    assert entity_id(hass_setup, "binary_sensor", paired, "charging") is None
    # Entities covered by no capability always exist.
    assert entity_id(hass_setup, "sensor", paired, "app_version") is not None
    assert entity_id(hass_setup, "button", paired, "reload") is not None
    assert entity_id(hass_setup, "light", paired, "screen") is not None
    await client.close()


async def test_capability_gained_later_adds_entity(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, [c for c in ALL_CAPS if c != "audio"])
    await hass_setup.async_block_till_done()
    assert entity_id(hass_setup, "media_player", paired, "speaker") is None
    await client.close()

    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, ALL_CAPS)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "media_player", paired, "speaker")
    assert eid is not None
    assert hass_setup.states.get(eid).state == "idle"
    await client.close()


async def test_light_without_brightness_is_on_off(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, [c for c in ALL_CAPS if c != "brightness"])
    await hass_setup.async_block_till_done()
    state = hass_setup.states.get(entity_id(hass_setup, "light", paired, "screen"))
    assert state.attributes["supported_color_modes"] == ["onoff"]
    assert state.attributes.get("brightness") is None
    await client.close()


async def test_entities_survive_reload_unavailable(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    await client.close()

    assert await hass_setup.config_entries.async_reload(hub.entry_id)
    await hass_setup.async_block_till_done()
    # Capabilities are stored, so entities come back before the tablet reconnects.
    eid = entity_id(hass_setup, "media_player", paired, "speaker")
    assert eid is not None
    assert hass_setup.states.get(eid).state == STATE_UNAVAILABLE


async def test_command_before_subscribe_raises(hass_setup, server, hub, session, paired):
    """Registered but not subscribed: nothing can carry the command (PROTOCOL.md 4.1)."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "button", paired, "reload")
    with pytest.raises(HomeAssistantError):
        await hass_setup.services.async_call("button", "press", {"entity_id": eid}, blocking=True)
    await client.close()


async def test_superseded_connection_keeps_entities_available(
    hass_setup, server, hub, session, paired
):
    first = await ws_connect(session, server, paired.refresh_token)
    await register(first, paired)
    second = await ws_connect(session, server, paired.refresh_token)
    await register(second, paired)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "light", paired, "screen")
    # The old socket closing must not mark the device unavailable.
    await first.close()
    await hass_setup.async_block_till_done()
    assert hass_setup.states.get(eid).state != STATE_UNAVAILABLE
    record = TabulaHub.get(hass_setup).devices[paired.device_id]
    assert record.connected
    await second.close()


async def test_dropped_capability_removes_its_entities(hass_setup, server, hub, session, paired):
    """No permanently unavailable placeholders (FEATURES F-HA-02)."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, ALL_CAPS)
    await hass_setup.async_block_till_done()
    assert entity_id(hass_setup, "media_player", paired, "speaker") is not None
    await client.close()

    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, [c for c in ALL_CAPS if c != "audio"])
    await hass_setup.async_block_till_done()
    assert entity_id(hass_setup, "media_player", paired, "speaker") is None
    assert entity_id(hass_setup, "light", paired, "screen") is not None
    await client.close()


async def test_connectivity_sensor_reads_off_when_disconnected(
    hass_setup, server, hub, session, paired
):
    """The link sensor stays available and reads off (FEATURES F-HA-09, F-HA-04)."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "binary_sensor", paired, "connected")
    assert eid is not None
    assert hass_setup.states.get(eid).state == "on"
    await client.close()
    await wait_state(hass_setup, eid, lambda s: s.state == "off")


async def test_repaired_device_entities_come_back(hass_setup, server, hub, session, paired):
    """Re-pairing replaces the device record; the same entities follow the new one."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "light", paired, "screen")
    await client.close()
    await wait_state(hass_setup, eid, lambda s: s.state == STATE_UNAVAILABLE)

    again = await pair_device(hass_setup, server, paired.device_id)
    client = await ws_connect(session, server, again.refresh_token)
    await register(client, again)
    await wait_state(hass_setup, eid, lambda s: s.state == "on")
    await client.close()


async def test_deleted_then_repaired_device_gets_entities(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()
    await client.close()

    dr.async_get(hass_setup).async_remove_device(paired.ha_device_id)
    await hass_setup.async_block_till_done()
    assert entity_id(hass_setup, "light", paired, "screen") is None

    again = await pair_device(hass_setup, server, paired.device_id)
    client = await ws_connect(session, server, again.refresh_token)
    await register(client, again)
    await hass_setup.async_block_till_done()
    eid = entity_id(hass_setup, "light", again, "screen")
    assert eid is not None
    assert hass_setup.states.get(eid).state == "on"
    await client.close()


async def test_no_capabilities_still_gets_always_present_entities(
    hass_setup, server, hub, session, paired
):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, [])
    await hass_setup.async_block_till_done()
    assert entity_id(hass_setup, "button", paired, "reload") is not None
    assert entity_id(hass_setup, "binary_sensor", paired, "connected") is not None
    assert entity_id(hass_setup, "light", paired, "screen") is None
    await client.close()


def test_timestamp_without_offset_is_unknown():
    assert _timestamp("2026-11-21T14:08:12") is None
    assert _timestamp("2026-11-21T14:08:12Z") is not None


async def test_one_write_per_affected_entity_per_frame(
    hass_setup, server, hub, session, paired, monkeypatch
):
    """A frame writes each entity that reads a changed key once, and no others."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await hass_setup.async_block_till_done()

    writes: list[str] = []
    original = TabulaEntity.async_write_ha_state

    def spy(self):
        writes.append(self.entity_description.key)
        original(self)

    monkeypatch.setattr(TabulaEntity, "async_write_ha_state", spy)
    reply = await client.reply(
        frame_fixture("state_delta", id=3, state={"brightness": 10, "screen_on": False})
    )
    assert reply["success"], reply
    await hass_setup.async_block_till_done()
    assert writes == ["screen"]
    await client.close()
