"""tabula/* WebSocket command semantics (PROTOCOL.md section 4).

Registration adopts the connection, state merges, results and events arrive,
supersession cuts the old socket off, and unsupported protocol majors raise a
repair issue.
"""

from __future__ import annotations

import asyncio

from custom_components.tabula import TabulaHub
from custom_components.tabula.const import FRAME_MAX_BYTES, PROTOCOL_VERSION
from helpers import Paired, frame_fixture, ws_connect
from homeassistant.helpers import issue_registry as ir

STATE_DELTA = {"brightness": 42, "muted": True}


async def register(client, paired: Paired, msg_id: int = 2) -> dict:
    """Send the golden register frame for the paired device."""
    reply = await client.reply(frame_fixture("register", id=msg_id, device_id=paired.device_id))
    assert reply["success"], reply
    return reply["result"]


# --- register and subscribe -----------------------------------------------------------


async def test_register_returns_hub_fields(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    result = await register(client, paired)
    assert result["ha_device_id"] == paired.ha_device_id
    assert result["protocol_version"] == PROTOCOL_VERSION
    assert result["settings_revision"] == 0
    assert result["server_time"]
    assert isinstance(result["capabilities"], list)

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    fixture = frame_fixture("register", device_id=paired.device_id)
    assert record.state == fixture["state"]
    assert record.app_version == fixture["app_version"]
    assert record.current is not None

    await client.close()
    # The register-time cleanup detaches the session when the socket closes.
    assert record.current is None


async def test_subscribe_and_state_delta(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    subscribed = await client.reply(frame_fixture("subscribe", id=3))
    assert subscribed["success"], subscribed
    delta = await client.reply(frame_fixture("state_delta", id=4, state=STATE_DELTA))
    assert delta["success"], delta

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    assert record.state["brightness"] == 42
    assert record.state["muted"] is True
    # The register state is still there; the delta only touched two keys.
    assert "screen_on" in record.state
    await client.close()


async def test_second_subscribe_rejected(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    first = await client.reply(frame_fixture("subscribe", id=3))
    assert first["success"], first
    second = await client.reply(frame_fixture("subscribe", id=4))
    assert second["success"] is False
    assert second["error"]["code"] == "invalid_frame"
    await client.close()


async def test_socket_close_clears_session(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await client.reply(frame_fixture("subscribe", id=3))
    await client.close()
    await asyncio.sleep(0.2)
    await hass_setup.async_block_till_done()
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.sessions == {}
    assert hub_runtime.devices[paired.device_id].current is None


# --- frames before register -----------------------------------------------------------


async def test_frames_before_register_not_registered(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    for frame in (
        frame_fixture("subscribe", id=2),
        frame_fixture("state_delta", id=3, state=STATE_DELTA),
    ):
        reply = await client.reply(frame)
        assert reply["success"] is False
        assert reply["error"]["code"] == "not_registered"
    # The device record was untouched by the rejected frames.
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].state == {}
    await client.close()


# --- unsupported protocol and the repair issue -----------------------------------------


async def test_unsupported_protocol_repair_issue(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(
        frame_fixture("register", id=2, device_id=paired.device_id, protocol_version=99)
    )
    assert reply["success"] is False
    assert reply["error"]["code"] == "unsupported_protocol"

    issues = ir.async_get(hass_setup)
    issue = issues.async_get_issue("tabula", f"unsupported_protocol_{paired.device_id}")
    assert issue is not None, sorted(issues.issues)

    # A supported register clears the issue (the device came back down to v1).
    result = await register(client, paired, msg_id=3)
    assert result["ha_device_id"] == paired.ha_device_id
    assert issues.async_get_issue("tabula", f"unsupported_protocol_{paired.device_id}") is None
    await client.close()


# --- supersession (PROTOCOL.md 4.9) ---------------------------------------------------


async def test_newest_connection_wins(hass_setup, server, hub, session, paired):
    old = await ws_connect(session, server, paired.refresh_token)
    await register(old, paired)
    await old.reply(frame_fixture("subscribe", id=3))

    new = await ws_connect(session, server, paired.refresh_token)
    result = await register(new, paired, msg_id=2)
    assert result["ha_device_id"] == paired.ha_device_id

    # Every frame from the replaced connection answers superseded, including a
    # new register (PROTOCOL.md 4.9).
    state = await old.reply(frame_fixture("state_delta", id=4, state=STATE_DELTA))
    assert state["success"] is False
    assert state["error"]["code"] == "superseded"
    again = await old.reply(frame_fixture("register", id=5, device_id=paired.device_id))
    assert again["success"] is False
    assert again["error"]["code"] == "superseded"

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].current is not None
    assert not hub_runtime.devices[paired.device_id].current.superseded
    await old.close()
    await new.close()


async def test_register_only_connection_is_superseded(hass_setup, server, hub, session, paired):
    """A connection that never subscribed still answers superseded (4.9)."""
    old = await ws_connect(session, server, paired.refresh_token)
    await register(old, paired)

    new = await ws_connect(session, server, paired.refresh_token)
    await register(new, paired, msg_id=2)

    state = await old.reply(frame_fixture("state_delta", id=4, state=STATE_DELTA))
    assert state["success"] is False
    assert state["error"]["code"] == "superseded"

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    # Both sessions exist while both sockets are open; the old one stays so it
    # can keep answering "superseded" (it is not deleted on supersession).
    assert len(hub_runtime.sessions) == 2

    await old.close()
    for _ in range(100):  # HA runs the register cleanup when the socket closes
        if len(hub_runtime.sessions) == 1:
            break
        await asyncio.sleep(0.05)
    assert len(hub_runtime.sessions) == 1, "closing must run the register-time cleanup"
    await new.close()


# --- validation, tolerance and size ----------------------------------------------------


async def test_unknown_state_key_invalid(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    reply = await client.reply(frame_fixture("state_delta", id=3, state={"nope": 1}))
    assert reply["success"] is False
    assert reply["error"]["code"] == "invalid_frame"
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert "nope" not in hub_runtime.devices[paired.device_id].state
    await client.close()


async def test_result_event_log_accepted(hass_setup, server, hub, session, paired):
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    await client.reply(frame_fixture("subscribe", id=3))

    ack = await client.reply(frame_fixture("command_result", id=4))
    assert ack["success"], ack
    event = await client.reply(frame_fixture("event", id=5))
    assert event["success"], event
    log = await client.reply(frame_fixture("log", id=6))
    assert log["success"], log

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    record = hub_runtime.devices[paired.device_id]
    assert len(record.events) == 1
    assert record.events[0]["event"] == frame_fixture("event")["event"]
    assert len(record.logs) == len(frame_fixture("log")["lines"])
    await client.close()


async def test_frame_over_max_bytes_rejected(hass_setup, server, hub, session, paired):
    """An over-size frame is rejected even when its known parts validate."""
    client = await ws_connect(session, server, paired.refresh_token)
    await register(client, paired)
    padding = "x" * (FRAME_MAX_BYTES // 1024 + 1) * 1024
    frame = frame_fixture("state_delta", id=3, state=STATE_DELTA)
    frame["padding"] = padding
    assert len(repr(frame)) > FRAME_MAX_BYTES
    reply = await client.reply(frame)
    assert reply["success"] is False
    assert reply["error"]["code"] == "invalid_frame"
    assert "exceeds" in reply["error"]["message"]
    await client.close()
