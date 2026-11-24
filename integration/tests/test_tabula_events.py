"""Device events relay to the HA event bus (plan 5.5, PROTOCOL.md 4.3).

The relay runs inside the websocket handler, so once the client's reply to the
event frame arrives the bus events have already fired. Each TLP event maps to
one HA event; grouped kinds carry the specific name in ``type``.
"""

from __future__ import annotations

import asyncio

from custom_components.tabula import TabulaHub
from helpers import frame_fixture, ws_connect
from homeassistant.core import Event, callback


async def _registered_subscribed(session, server, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    assert reply["success"], reply
    subscribed = await client.reply(frame_fixture("subscribe", id=3))
    assert subscribed["success"], subscribed
    return client


def _listen(hass, names):
    seen: list[Event] = []

    # @callback: runs inside async_fire, so the list is complete once the
    # websocket reply arrives (a plain function would run in the executor).
    @callback
    def catch(event: Event) -> None:
        seen.append(event)

    for name in names:
        hass.bus.async_listen(name, catch)
    return seen


async def test_notification_events_relay_to_the_bus(hass_setup, server, hub, session, paired):
    client = await _registered_subscribed(session, server, paired)
    seen = _listen(hass_setup, ["tabula_notification"])

    shown = await client.reply(
        frame_fixture("event", id=9, event="notification_shown", data={"tag": "door"})
    )
    assert shown["success"], shown
    dismissed = await client.reply(
        frame_fixture(
            "event", id=10, event="notification_dismissed", data={"tag": "door", "reason": "touch"}
        )
    )
    assert dismissed["success"], dismissed

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    # settle: the relay and the hub's diagnostics record are written as the
    # frames are handled, which a busy CI runner can interleave with the acks
    for _ in range(100):
        if len(seen) == 2 and len(record.events) == 2:
            break
        await asyncio.sleep(0.05)

    assert len(seen) == 2
    by_type = {e.data["type"]: e for e in seen}
    assert set(by_type) == {"notification_shown", "notification_dismissed"}
    for event in seen:
        assert event.event_type == "tabula_notification"
        assert event.data["device_id"] == paired.ha_device_id
        assert event.data["tabula_device_id"] == paired.device_id
    assert by_type["notification_shown"].data["tag"] == "door"
    assert by_type["notification_dismissed"].data["reason"] == "touch"
    # The frames also land in the hub's diagnostics record.
    assert [f["event"] for f in record.events] == ["notification_shown", "notification_dismissed"]
    await client.close()


async def test_screensaver_events_group_one_bus_event(hass_setup, server, hub, session, paired):
    client = await _registered_subscribed(session, server, paired)
    seen = _listen(hass_setup, ["tabula_screensaver"])

    started = await client.reply(
        frame_fixture(
            "event", id=9, event="screensaver_started", data={"mode": "clock", "trigger": "idle"}
        )
    )
    assert started["success"], started

    assert len(seen) == 1
    event = seen[0]
    assert event.event_type == "tabula_screensaver"
    assert event.data["type"] == "screensaver_started"
    assert event.data["mode"] == "clock"
    assert event.data["trigger"] == "idle"
    assert event.data["device_id"] == paired.ha_device_id
    assert event.data["tabula_device_id"] == paired.device_id
    await client.close()


async def test_media_finished_event_relays_to_the_bus(hass_setup, server, hub, session, paired):
    """tabula_media carries media_finished with its reason (plan 5.7, PROTOCOL 4.7)."""
    client = await _registered_subscribed(session, server, paired)
    seen = _listen(hass_setup, ["tabula_media"])

    reply = await client.reply(
        frame_fixture("event", id=9, event="media_finished", data={"reason": "ended"})
    )
    assert reply["success"], reply

    assert len(seen) == 1
    event = seen[0]
    assert event.event_type == "tabula_media"
    assert event.data["type"] == "media_finished"
    assert event.data["reason"] == "ended"
    assert event.data["device_id"] == paired.ha_device_id
    assert event.data["tabula_device_id"] == paired.device_id
    await client.close()


async def test_unknown_event_is_rejected_not_relayed(hass_setup, server, hub, session, paired):
    client = await _registered_subscribed(session, server, paired)
    seen = _listen(hass_setup, ["tabula_notification", "tabula_screensaver", "tabula_media"])

    reply = await client.reply(frame_fixture("event", id=9, event="disco_party", data={}))
    assert reply["success"] is False
    assert reply["error"]["code"] == "invalid_frame"

    await asyncio.sleep(0.1)
    await hass_setup.async_block_till_done()
    assert seen == []
    await client.close()


async def test_event_before_register_is_rejected(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("event", id=2))
    assert reply["success"] is False
    await client.close()


async def test_intercom_events_relay_to_the_bus(hass_setup, server, hub, session, paired):
    """P9.1: the five intercom events reach the bus as ``tabula_intercom``.

    The services, entities and the call relay arrive with P9.2; what this
    packet owes HA is that a device's call events are not dropped.
    """
    client = await _registered_subscribed(session, server, paired)
    seen = _listen(hass_setup, ["tabula_intercom"])
    call = "01JC9Z6Q2H3W7K5N8T4V0BXDRA"

    frames = [
        ("intercom_answer", {"call_id": call}),
        ("intercom_decline", {"call_id": call}),
        ("intercom_signal", {"call_id": call, "kind": "answer", "payload": "v=0\r\n"}),
        ("intercom_ended", {"call_id": call, "reason": "hangup"}),
        (
            "intercom_start_request",
            {"call_id": call, "mode": "broadcast", "target_area_id": "kitchen"},
        ),
    ]
    for index, (event, data) in enumerate(frames):
        reply = await client.reply(frame_fixture("event", id=10 + index, event=event, data=data))
        assert reply["success"], (event, reply)

    for _ in range(100):
        if len(seen) == len(frames):
            break
        await asyncio.sleep(0.01)
    assert [event.data["type"] for event in seen] == [name for name, _ in frames]
    assert seen[0].data["call_id"] == call
    assert seen[0].data["device_id"] == paired.ha_device_id
    assert seen[0].data["tabula_device_id"] == paired.device_id
    assert seen[4].data["mode"] == "broadcast"
    await client.close()
