"""Wake-word detection surfaces (P7.1, ADR 0030, PROTOCOL.md 4.7).

One TLP event feeds three HA surfaces: the ``tabula_wake_word`` bus event, the
``event`` entity, and the two state keys (``last_wake_word`` timestamp,
``wake_word_active`` listening flag). Everything is gated on the device
declaring the ``wake_word`` capability, so a device with the setting off can
still pair and behave exactly as before.
"""

from __future__ import annotations

import asyncio
from datetime import datetime

from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from helpers import Paired, frame_fixture, ws_connect
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers import entity_registry as er

WAKE_CAPS = [*frame_fixture("register")["capabilities"], "wake_word"]


def entity_id(hass: HomeAssistant, platform: str, paired: Paired, key: str) -> str | None:
    return er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{paired.device_id}_{key}")


async def register(client, paired: Paired, capabilities: list[str] | None = None) -> dict:
    overrides: dict = {"id": 2, "device_id": paired.device_id}
    if capabilities is not None:
        overrides["capabilities"] = capabilities
    reply = await client.reply(frame_fixture("register", **overrides))
    assert reply["success"], reply
    return reply["result"]


async def until(predicate, timeout: float = 5.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def test_wake_word_event_relays_and_fills_the_entity(
    hass_setup, server, hub, session, paired
):
    """The detection reaches the bus, the event entity and the timestamp sensor."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, WAKE_CAPS)
    await hass_setup.async_block_till_done()

    seen: list[Event] = []

    @callback
    def catch(event: Event) -> None:
        seen.append(event)

    hass_setup.bus.async_listen("tabula_wake_word", catch)

    detected = await client.reply(
        frame_fixture(
            "event",
            id=9,
            event="wake_word_detected",
            data={"phrase": "okay_nabu", "probability": 231},
        )
    )
    assert detected["success"], detected

    event_entity = entity_id(hass_setup, "event", paired, "wake_word")
    last = entity_id(hass_setup, "sensor", paired, "last_wake_word")
    active = entity_id(hass_setup, "binary_sensor", paired, "wake_word_active")
    assert event_entity is not None and last is not None and active is not None

    # the state keys arrive with the event (state_delta), so one frame moves all
    delta = await client.reply(
        frame_fixture(
            "state_delta",
            id=10,
            state={
                "wake_word_active": True,
                "last_wake_word": "2026-11-22T09:15:04Z",
            },
        )
    )
    assert delta["success"], delta

    await until(
        lambda: hass_setup.states.get(event_entity) is not None
        and hass_setup.states.get(last).state != "unknown"
    )
    await hass_setup.async_block_till_done()

    assert len(seen) == 1
    bus = seen[0]
    assert bus.data["type"] == "wake_word_detected"
    assert bus.data["phrase"] == "okay_nabu"
    assert bus.data["probability"] == 231
    assert bus.data["device_id"] == paired.ha_device_id
    assert bus.data["tabula_device_id"] == paired.device_id

    state = hass_setup.states.get(event_entity)
    assert state.attributes["event_type"] == "detected"
    assert state.attributes["phrase"] == "okay_nabu"
    assert state.attributes["probability"] == 231

    last_state = hass_setup.states.get(last)
    assert datetime.fromisoformat(last_state.state) == datetime.fromisoformat(
        "2026-11-22T09:15:04+00:00"
    )

    active_state = hass_setup.states.get(active)
    assert active_state.state == "on"

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert [f["event"] for f in runtime.devices[paired.device_id].events] == [
        "wake_word_detected"
    ]
    await client.close()


async def test_wake_word_entities_need_the_capability(hass_setup, server, hub, session, paired):
    """A device with the setting off declares no capability and gets no surface."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)  # the golden register frame: no wake_word
    await hass_setup.async_block_till_done()

    assert entity_id(hass_setup, "event", paired, "wake_word") is None
    assert entity_id(hass_setup, "sensor", paired, "last_wake_word") is None
    assert entity_id(hass_setup, "binary_sensor", paired, "wake_word_active") is None

    # and the event still relays: the capability gates the entities, not the relay
    seen: list[Event] = []

    @callback
    def catch(event: Event) -> None:
        seen.append(event)

    hass_setup.bus.async_listen("tabula_wake_word", catch)
    reply = await client.reply(
        frame_fixture(
            "event",
            id=9,
            event="wake_word_detected",
            data={"phrase": "okay_nabu", "probability": 200},
        )
    )
    assert reply["success"], reply
    await hass_setup.async_block_till_done()
    assert len(seen) == 1
    await client.close()


async def test_other_devices_detections_do_not_touch_this_entity(
    hass_setup, server, hub, session, paired
):
    """Two tablets fire the same bus type; the entity keeps its own device's data."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, WAKE_CAPS)
    await hass_setup.async_block_till_done()

    event_entity = entity_id(hass_setup, "event", paired, "wake_word")
    assert event_entity is not None

    # a bus event from another device, as if a second tablet had detected it
    hass_setup.bus.async_fire(
        "tabula_wake_word",
        {
            "type": "wake_word_detected",
            "phrase": "okay_nabu",
            "probability": 255,
            "device_id": "other-ha-device",
            "tabula_device_id": "00000000-0000-4000-8000-000000000000",
        },
    )
    await hass_setup.async_block_till_done()
    assert hass_setup.states.get(event_entity).attributes["event_type"] is None
    await client.close()
