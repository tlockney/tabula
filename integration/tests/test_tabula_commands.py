"""Commands and services (P1.4, PROTOCOL.md 4.5-4.6, plan 5.5).

The device side is the mock's TLPClient and MockDevice over a real WebSocket,
except where a test needs to control the reply frame by frame (raw WS client).
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncGenerator
from dataclasses import dataclass
from typing import Any

import aiohttp
import custom_components.tabula as tabula
import pytest
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from helpers import Paired, frame_fixture, ws_connect
from homeassistant.components.tts import TextToSpeechEntity
from homeassistant.config_entries import ConfigEntry, ConfigFlow
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.setup import async_setup_component
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore
from pytest_homeassistant_custom_component.common import (
    MockConfigEntry,
    MockModule,
    MockPlatform,
    mock_config_flow,
    mock_integration,
    mock_platform,
)


def entity_id(hass: HomeAssistant, platform: str, paired: Paired, key: str) -> str:
    eid = er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{paired.device_id}_{key}")
    assert eid is not None, (platform, key)
    return eid


@dataclass
class Tablet:
    """A paired mock tablet, registered and subscribed through the TLPClient."""

    paired: Paired
    device: MockDevice
    client: TLPClient
    events: list[tuple[str, dict]]


async def _run(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


async def wait_until(predicate, timeout: float = 5.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.01)


@pytest.fixture
async def tablet(hass_setup, server, hub, paired, tmp_path) -> AsyncGenerator[Tablet]:
    store = DeviceStore(
        tmp_path / "device.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    device = MockDevice(capabilities=ALL_CAPABILITIES, base_url=server)
    client = TLPClient(server, store, device)
    events: list[tuple[str, dict]] = []
    queue_event = device.on_event

    def on_event(event: str, data: dict) -> None:
        events.append((event, data))
        queue_event(event, data)

    device.on_event = on_event
    task = asyncio.create_task(_run(client))
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    # Registered and subscribed: commands can go out.
    await wait_until(
        lambda: (
            (r := hub_runtime.devices.get(paired.device_id)) is not None
            and r.current is not None
            and r.current.subscription_id is not None
        )
    )
    await hass_setup.async_block_till_done()
    try:
        yield Tablet(paired, device, client, events)
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


# --- notify ---------------------------------------------------------------


async def test_notify_send_message_reaches_device(hass_setup, tablet):
    eid = entity_id(hass_setup, "notify", tablet.paired, "notifications")
    await hass_setup.services.async_call(
        "notify",
        "send_message",
        {"entity_id": eid, "message": "Dinner is ready", "title": "Kitchen"},
        blocking=True,
    )
    assert tablet.device.notifications == [{"message": "Dinner is ready", "title": "Kitchen"}]
    await wait_until(lambda: ("notification_shown", {}) in tablet.events)


async def test_tabula_notify_all_fields_by_device(hass_setup, tablet):
    fields = {
        "message": "Door open",
        "title": "Garage",
        "level": "warning",
        "duration": 30,
        "icon": "mdi:garage-open",
        "chime": True,
        "tag": "garage",
    }
    await hass_setup.services.async_call(
        DOMAIN, "notify", {"device_id": tablet.paired.ha_device_id, **fields}, blocking=True
    )
    assert tablet.device.notifications == [fields]


async def test_tabula_notify_by_entity_target(hass_setup, tablet):
    eid = entity_id(hass_setup, "notify", tablet.paired, "notifications")
    await hass_setup.services.async_call(
        DOMAIN, "notify", {"entity_id": eid, "message": "hi"}, blocking=True
    )
    assert tablet.device.notifications == [{"message": "hi"}]


async def test_tabula_notify_without_target_device_is_rejected(hass_setup, tablet):
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            DOMAIN, "notify", {"device_id": "not-a-device", "message": "hi"}, blocking=True
        )


async def test_tabula_navigate_and_screensaver(hass_setup, tablet):
    target = {"device_id": tablet.paired.ha_device_id}
    await hass_setup.services.async_call(
        DOMAIN, "navigate", {**target, "path": "/lovelace/kitchen"}, blocking=True
    )
    await wait_until(lambda: tablet.device.state["current_page"] == "/lovelace/kitchen")
    await hass_setup.services.async_call(
        DOMAIN, "screensaver", {**target, "active": True, "mode": "photos"}, blocking=True
    )
    assert tablet.device.state["screensaver_active"] is True
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            DOMAIN, "screensaver", {**target, "active": True, "mode": "nope"}, blocking=True
        )


# --- failures ---------------------------------------------------------------


async def test_nack_raises(hass_setup, tablet):
    tablet.device.behavior.fail.add("notify")
    with pytest.raises(HomeAssistantError, match="failed"):
        await hass_setup.services.async_call(
            DOMAIN,
            "notify",
            {"device_id": tablet.paired.ha_device_id, "message": "x"},
            blocking=True,
        )


async def test_timeout_raises(hass_setup, tablet, monkeypatch):
    monkeypatch.setattr(tabula, "COMMAND_TIMEOUT", 0.3)
    tablet.device.behavior.drop.add("reload")
    eid = entity_id(hass_setup, "button", tablet.paired, "reload")
    with pytest.raises(HomeAssistantError, match="did not answer"):
        await hass_setup.services.async_call("button", "press", {"entity_id": eid}, blocking=True)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.result_futures == {}


async def test_offline_device_raises(hass_setup, tablet):
    tablet.client.stop()
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    await wait_until(lambda: hub_runtime.devices[tablet.paired.device_id].current is None)
    with pytest.raises(HomeAssistantError, match="not connected"):
        await hass_setup.services.async_call(
            DOMAIN,
            "notify",
            {"device_id": tablet.paired.ha_device_id, "message": "x"},
            blocking=True,
        )


async def test_undeclared_capability_is_never_sent(hass_setup, server, hub, session, paired):
    """PROTOCOL.md 4.5: no command for a capability the device did not declare."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(
        frame_fixture("register", id=2, device_id=paired.device_id, capabilities=["battery"])
    )
    assert reply["success"], reply
    assert (await client.reply(frame_fixture("subscribe", id=3)))["success"]
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    with pytest.raises(HomeAssistantError, match="does not support"):
        await hub_runtime.async_command(record, "notify", {"message": "x"})
    await client.close()


# --- result matching (raw WebSocket, PROTOCOL.md 4.6) -----------------------


async def _subscribed(session, server, paired, msg_id: int = 2):
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=msg_id, device_id=paired.device_id))
    assert reply["success"], reply
    assert (await client.reply(frame_fixture("subscribe", id=msg_id + 1)))["success"]
    return client


async def _next_command(client) -> dict[str, Any]:
    while True:
        frame = await client.recv()
        if frame["type"] == "event":
            return frame


async def test_result_on_other_connection_is_ignored(
    hass_setup, server, hub, session, paired, monkeypatch
):
    """A result is matched by (connection, command_id); a superseded socket's
    reply is refused, and the command times out."""
    monkeypatch.setattr(tabula, "COMMAND_TIMEOUT", 0.5)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    old = await _subscribed(session, server, paired)
    task = asyncio.create_task(hub_runtime.async_command(record, "reload", {}))
    command = await _next_command(old)
    command_id = command["event"]["command_id"]
    assert command["event"] == {"command_id": command_id, "command": "reload", "args": {}}
    # A newer connection registers; the old one's answer no longer counts.
    new = await _subscribed(session, server, paired, msg_id=10)
    reply = await old.reply(frame_fixture("command_result", id=20, command_id=command_id, ok=True))
    assert reply["error"]["code"] == "superseded"
    # The same command_id answered on the new connection is acked and ignored.
    reply = await new.reply(frame_fixture("command_result", id=21, command_id=command_id, ok=True))
    assert reply["success"], reply
    with pytest.raises(HomeAssistantError, match="did not answer"):
        await task
    await old.close()
    await new.close()


async def test_result_settles_and_late_result_is_acked(hass_setup, server, hub, session, paired):
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    client = await _subscribed(session, server, paired)
    task = asyncio.create_task(hub_runtime.async_command(record, "reload", {}))
    command = await _next_command(client)
    command_id = command["event"]["command_id"]
    reply = await client.reply(
        frame_fixture("command_result", id=20, command_id=command_id, ok=True)
    )
    assert reply["success"], reply
    assert await task == {}
    # Answering twice: acked, no effect.
    reply = await client.reply(
        frame_fixture("command_result", id=21, command_id=command_id, ok=True)
    )
    assert reply["success"], reply
    await client.close()


async def test_disconnect_fails_pending_command_at_once(hass_setup, server, hub, session, paired):
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    client = await _subscribed(session, server, paired)
    task = asyncio.create_task(hub_runtime.async_command(record, "reload", {}))
    await _next_command(client)
    await client.close()
    async with asyncio.timeout(3):
        with pytest.raises(HomeAssistantError, match="disconnected"):
            await task


# --- brightness ownership (plan 5.5) ----------------------------------------


async def test_manual_brightness_turns_adaptive_off(hass_setup, tablet):
    tablet.device.update(adaptive_brightness=True)
    adaptive = entity_id(hass_setup, "switch", tablet.paired, "adaptive_brightness")
    screen = entity_id(hass_setup, "light", tablet.paired, "screen")
    await wait_until(lambda: hass_setup.states.get(adaptive).state == "on")
    await hass_setup.services.async_call(
        "light", "turn_on", {"entity_id": screen, "brightness": 128}, blocking=True
    )
    await wait_until(lambda: hass_setup.states.get(adaptive).state == "off")
    state = hass_setup.states.get(screen)
    await wait_until(lambda: hass_setup.states.get(screen).attributes.get("brightness") == 128)
    assert state is not None
    assert tablet.device.state["brightness"] == 50


async def test_settings_entities_say_settings_are_not_available_yet(hass_setup, tablet):
    eid = entity_id(hass_setup, "switch", tablet.paired, "adaptive_brightness")
    with pytest.raises(HomeAssistantError, match="cannot be changed"):
        await hass_setup.services.async_call("switch", "turn_on", {"entity_id": eid}, blocking=True)


# --- media player and TTS (ADR 0001 item 5) -----------------------------------


class FakeTTS(TextToSpeechEntity):
    _attr_name = "Fake TTS"
    _attr_unique_id = "fake_tts"
    _attr_supported_languages = ("en",)
    _attr_default_language = "en"

    def get_tts_audio(self, message: str, language: str, options: dict[str, Any]):
        return ("mp3", b"ID3fake-" + message.encode())


@pytest.fixture
async def tts_engine(integration, tmp_path) -> AsyncGenerator[str]:
    """A TTS entity provided by a test integration, set up the HA test-suite way."""
    hass = integration
    # tts creates its cache directory under the config dir: keep it out of the venv.
    hass.config.config_dir = str(tmp_path)
    assert await async_setup_component(hass, "media_source", {})
    assert await async_setup_component(hass, "tts", {})

    async def setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
        await hass.config_entries.async_forward_entry_setups(entry, [Platform.TTS])
        return True

    async def setup_platform(
        hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
    ) -> None:
        async_add_entities([FakeTTS()])

    mock_integration(hass, MockModule("fake_tts", async_setup_entry=setup_entry))
    mock_platform(hass, "fake_tts.tts", MockPlatform(async_setup_entry=setup_platform))
    mock_platform(hass, "fake_tts.config_flow")
    with mock_config_flow("fake_tts", ConfigFlow):
        entry = MockConfigEntry(domain="fake_tts")
        entry.add_to_hass(hass)
        assert await hass.config_entries.async_setup(entry.entry_id)
        await hass.async_block_till_done()
        yield "tts.fake_tts"


@pytest.fixture
async def tts_ready(tts_engine, server) -> str:
    """tts views are registered before the server starts (frozen router)."""
    return tts_engine


async def test_tts_speak_sends_fetchable_url(hass_setup, tts_ready, tablet, server, session):
    speaker = entity_id(hass_setup, "media_player", tablet.paired, "speaker")
    await hass_setup.services.async_call(
        "tts",
        "speak",
        {
            "entity_id": tts_ready,
            "media_player_entity_id": speaker,
            "message": "Dinner is ready",
            "cache": False,
        },
        blocking=True,
    )
    assert len(tablet.device.played) == 1
    played = tablet.device.played[0]
    assert played["url"].startswith("/api/tts_proxy/"), played
    assert played["media_type"] == "music"
    # The device resolves the path against its HA base URL and fetches it
    # without its credential (PROTOCOL.md 4.5, ADR 0001 item 5).
    async with session.get(f"{tablet.device.base_url}{played['url']}") as response:
        assert response.status == 200
        assert (await response.read()).startswith(b"ID3fake-Dinner is ready")


async def test_play_media_announce_and_absolute_url(hass_setup, tablet):
    speaker = entity_id(hass_setup, "media_player", tablet.paired, "speaker")
    await hass_setup.services.async_call(
        "media_player",
        "play_media",
        {
            "entity_id": speaker,
            "media_content_id": "https://example.com/chime.mp3",
            "media_content_type": "music",
            "announce": True,
        },
        blocking=True,
    )
    assert tablet.device.played == [
        {"url": "https://example.com/chime.mp3", "media_type": "music", "announce": True}
    ]


async def test_media_controls(hass_setup, tablet):
    speaker = entity_id(hass_setup, "media_player", tablet.paired, "speaker")
    await hass_setup.services.async_call(
        "media_player", "volume_set", {"entity_id": speaker, "volume_level": 0.3}, blocking=True
    )
    await wait_until(lambda: hass_setup.states.get(speaker).attributes.get("volume_level") == 0.3)
    await hass_setup.services.async_call(
        "media_player",
        "volume_mute",
        {"entity_id": speaker, "is_volume_muted": True},
        blocking=True,
    )
    assert tablet.device.state["muted"] is True
