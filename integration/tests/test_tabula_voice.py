"""Voice turns on the HA side (P7.2, PROTOCOL.md 4.7 and 4.10).

The integration relays the two turn events onto ``tabula_voice`` and gates the
two turn commands on the ``voice`` capability. The audio itself is binary
frames, which HA routes by handler id; P7.3 owns driving a turn.
"""

from __future__ import annotations

import asyncio

import pytest
from custom_components.tabula import TabulaHub
from helpers import Paired, frame_fixture, ws_connect
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError

TURN = "01JCVTURN00000000000000AA"
VOICE_CAPS = [*frame_fixture("register")["capabilities"], "voice"]


async def register(
    client, paired: Paired, capabilities: list[str] | None = None, msg_id: int = 2
) -> dict:
    overrides: dict = {"id": msg_id, "device_id": paired.device_id}
    if capabilities is not None:
        overrides["capabilities"] = capabilities
    reply = await client.reply(frame_fixture("register", **overrides))
    assert reply["success"], reply
    return reply["result"]


async def subscribed(client, msg_id: int = 3) -> None:
    assert (await client.reply(frame_fixture("subscribe", id=msg_id)))["success"]


async def test_turn_events_relay_to_the_bus(hass_setup, server, hub, session, paired):
    """Both turn events reach ``tabula_voice`` with their data and both ids."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired, VOICE_CAPS)
    await subscribed(client)
    await hass_setup.async_block_till_done()

    seen: list[Event] = []

    @callback
    def catch(event: Event) -> None:
        seen.append(event)

    hass_setup.bus.async_listen("tabula_voice", catch)

    started = await client.reply(
        frame_fixture(
            "event",
            id=9,
            event="voice_turn_started",
            data={"turn_id": TURN, "phrase": "okay_nabu"},
        )
    )
    assert started["success"], started
    ended = await client.reply(
        frame_fixture(
            "event", id=10, event="voice_turn_ended", data={"turn_id": TURN, "reason": "timeout"}
        )
    )
    assert ended["success"], ended
    await hass_setup.async_block_till_done()

    assert [e.data["type"] for e in seen] == ["voice_turn_started", "voice_turn_ended"]
    first, last = seen[0].data, seen[1].data
    assert first["turn_id"] == TURN
    assert first["phrase"] == "okay_nabu"
    assert last["turn_id"] == TURN
    assert last["reason"] == "timeout"
    for data in (first, last):
        assert data["device_id"] == paired.ha_device_id
        assert data["tabula_device_id"] == paired.device_id

    runtime = TabulaHub.get(hass_setup)
    assert runtime is not None
    assert [f["event"] for f in runtime.devices[paired.device_id].events] == [
        "voice_turn_started",
        "voice_turn_ended",
    ]
    await client.close()


async def test_the_voice_capability_is_kept_and_gates_the_commands(
    hass_setup, server, hub, session, paired
):
    """Without ``voice`` neither command goes out; with it, one does."""
    client = await ws_connect(session, server, paired.refresh_token)
    # The golden register frame carries no voice capability.
    await register(client, paired)
    await subscribed(client)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    assert "voice" not in record.capabilities
    args = {"turn_id": TURN, "handler_id": 7, "start_stage": "stt"}
    with pytest.raises(HomeAssistantError, match="does not support"):
        await hub_runtime.async_command(record, "voice_start", args)
    with pytest.raises(HomeAssistantError, match="does not support"):
        await hub_runtime.async_command(record, "voice_stop", {"turn_id": TURN, "reason": "ended"})

    # Declaring it (a re-registration, as a reconnect does) turns the gate off.
    await register(client, paired, VOICE_CAPS, msg_id=5)
    assert "voice" in record.capabilities
    task = asyncio.create_task(
        hub_runtime.async_command(record, "voice_stop", {"turn_id": TURN, "reason": "ended"})
    )
    frame = await _next_event(client)
    assert frame["event"]["command"] == "voice_stop"
    assert frame["event"]["args"] == {"turn_id": TURN, "reason": "ended"}
    # The device answers; an unknown turn is still ok (PROTOCOL.md 4.10).
    reply = await client.reply(
        frame_fixture("command_result", id=6, command_id=frame["event"]["command_id"], ok=True)
    )
    assert reply["success"], reply
    assert await task == {}
    await client.close()


async def _next_event(client) -> dict:
    while True:
        frame = await client.recv()
        if frame["type"] == "event":
            return frame
