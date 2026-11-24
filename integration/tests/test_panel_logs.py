"""The Tabularium's log view: the buffer, the panel stream and the device subscription.

P5.4. Every test drives the real WebSocket surface: `tabula/log` frames arrive the
way the app sends them, the device answers the commands it receives like the app,
and the panel commands are the buffer's only reader.
"""

from __future__ import annotations

import asyncio
from typing import Any

from custom_components.tabula import TabulaHub
from custom_components.tabula.const import LOG_BUFFER_LINES
from helpers import WS, Paired, frame_fixture, ws_connect
from homeassistant.auth.const import GROUP_ID_ADMIN
from homeassistant.core import HomeAssistant

SNAPSHOT = "tabula/panel_logs"
SUBSCRIBE = "tabula/panel_logs_subscribe"


async def admin_client(hass: HomeAssistant, server: str, session) -> WS:
    """A WebSocket client authenticated as an admin (the panel's audience)."""
    user = await hass.auth.async_create_system_user("Panel admin", group_ids=[GROUP_ID_ADMIN])
    refresh = await hass.auth.async_create_refresh_token(user)
    return await ws_connect(session, server, refresh.token)


async def online_device(session, server: str, paired: Paired) -> WS:
    """Register a paired device and leave its socket open."""
    client = await ws_connect(session, server, paired.refresh_token)
    reply = await client.reply(frame_fixture("register", id=2, device_id=paired.device_id))
    assert reply["success"], reply
    # Subscribed: commands go out on the subscription (PROTOCOL.md 4.5).
    assert (await client.reply(frame_fixture("subscribe", id=3)))["success"]
    # HA requires each message's id to exceed the last (id_reuse otherwise); the
    # device numbers its frames well above the handshake's.
    client.next_id = 500
    return client


def alloc(device: WS) -> int:
    """The next message id on one connection: HA rejects a reuse or a lower one."""
    device.next_id += 1
    return device.next_id


async def panel(client: WS, msg_type: str, **fields: Any) -> dict[str, Any]:
    """Send one panel command with a fresh id and return its reply."""
    return await client.reply({"id": alloc(client), "type": msg_type, **fields})


def log_frame(msg_id: int, lines: list[dict[str, Any]]) -> dict[str, Any]:
    """One `tabula/log` frame, shaped as the device sends it (PROTOCOL 4.8)."""
    return {"id": msg_id, "type": "tabula/log", "lines": lines}


def line(message: str, level: str = "info", tag: str = "app") -> dict[str, Any]:
    return {"ts": "2026-11-24T20:15:30Z", "level": level, "tag": tag, "message": message}


async def push_lines(device: WS, lines: list[dict[str, Any]]) -> None:
    """Send a log frame and wait for its ack."""
    reply = await device.reply(log_frame(alloc(device), lines))
    assert reply["success"], reply


async def next_command(device: WS, timeout: float = 5.0) -> dict[str, Any]:
    """The next command the integration sends, answered OK like the app does.

    Results of the device's own frames are skipped: they are acks, not commands.
    """
    while True:
        msg = await device.recv(timeout)
        if msg["type"] == "event":
            break
    event = msg["event"]
    await device.send(
        {
            "id": alloc(device),
            "type": "tabula/result",
            "command_id": event["command_id"],
            "ok": True,
        }
    )
    return event


async def subscribe(admin: WS, device: WS, device_id: str, **extra: Any) -> dict[str, Any]:
    """Send a subscribe and answer the command it produces; returns the panel's reply."""
    pending = asyncio.create_task(
        panel(admin, SUBSCRIBE, device_id=device_id, enabled=True, **extra)
    )
    command = await next_command(device)
    reply = await pending
    assert command["command"] == "log_subscription", command
    return reply


async def wait_disconnected(hass: HomeAssistant, paired: Paired, timeout: float = 5.0) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    async with asyncio.timeout(timeout):
        while hub.devices[paired.device_id].connected:
            await asyncio.sleep(0.01)


# --- the snapshot ------------------------------------------------------------


async def test_snapshot_bound_and_order(hass_setup, server, hub, session, paired):
    """HA's ring keeps the newest LOG_BUFFER_LINES lines, oldest first."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        for start in range(0, LOG_BUFFER_LINES + 100, 200):
            await push_lines(device, [line(f"line {i}") for i in range(start, start + 200)])
        result = await panel(admin, SNAPSHOT, device_id=paired.device_id)
    finally:
        await admin.close()
        await device.close()

    assert result["success"] is True, result
    payload = result["result"]
    assert payload["capacity"] == LOG_BUFFER_LINES
    assert len(payload["lines"]) == LOG_BUFFER_LINES
    # The oldest were dropped and the newest kept, in arrival order.
    assert payload["lines"][-1]["message"] == f"line {LOG_BUFFER_LINES + 199}"
    assert payload["lines"][0]["message"] == "line 200"
    assert payload["connected"] is True
    assert payload["subscription"] == {
        "active": False,
        "viewers": 0,
        "level": None,
        "expires_at": None,
    }


async def test_snapshot_of_an_offline_device(hass_setup, server, hub, session, paired):
    """An offline device still shows its buffer, and its stream stays off."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        await push_lines(device, [line("before it dropped")])
        await device.close()
        await wait_disconnected(hass_setup, paired)
        result = await panel(admin, SNAPSHOT, device_id=paired.device_id)
    finally:
        await admin.close()

    payload = result["result"]
    assert payload["connected"] is False
    assert payload["last_seen"] is not None
    assert [item["message"] for item in payload["lines"]] == ["before it dropped"]
    assert payload["subscription"]["active"] is False


async def test_snapshot_is_admin_only(hass_setup, server, hub, session):
    """The log buffer is admin-only, like every panel command."""
    user = await hass_setup.auth.async_create_system_user("Not an admin")
    refresh = await hass_setup.auth.async_create_refresh_token(user)
    client = await ws_connect(session, server, refresh.token)
    try:
        result = await panel(client, SNAPSHOT, device_id="nope")
    finally:
        await client.close()

    assert result["success"] is False
    assert result["error"]["code"] == "unauthorized"


async def test_snapshot_rejects_an_unknown_device(hass_setup, server, hub, session):
    """A device id the hub does not know is an error, not an empty buffer."""
    client = await admin_client(hass_setup, server, session)
    try:
        result = await panel(client, SNAPSHOT, device_id="no-such-device")
    finally:
        await client.close()

    assert result["success"] is False
    assert result["error"]["code"] == "not_found"


# --- the device subscription -------------------------------------------------


async def test_subscribe_sends_the_command_and_relays_lines(
    hass_setup, server, hub, session, paired
):
    """The first viewer starts the device's subscription and gets the lines live."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        reply = await subscribe(admin, device, paired.device_id)
        assert reply["success"] is True, reply
        assert reply["result"]["level"] is None
        assert reply["result"]["expires_at"] is not None

        await push_lines(device, [line("live one"), line("live two", level="warning")])
        event = await admin.recv()
        assert event["type"] == "event"
        assert [item["message"] for item in event["event"]["lines"]] == ["live one", "live two"]
    finally:
        await admin.close()
        await device.close()


async def test_second_viewer_shares_one_subscription(hass_setup, server, hub, session, paired):
    """Two viewers on one device share it; the second sends the device nothing."""
    device = await online_device(session, server, paired)
    first = await admin_client(hass_setup, server, session)
    second = await admin_client(hass_setup, server, session)
    try:
        assert (await subscribe(first, device, paired.device_id))["success"] is True
        # The same level, so the second viewer needs no command of its own.
        reply = await panel(second, SUBSCRIBE, device_id=paired.device_id, enabled=True)
        assert reply["success"] is True, reply
        assert reply["result"]["viewers"] == 2

        await push_lines(device, [line("shared by both")])
        for client in (first, second):
            event = await client.recv()
            assert [item["message"] for item in event["event"]["lines"]] == ["shared by both"]
    finally:
        await first.close()
        await second.close()
        await device.close()


async def test_the_last_viewer_disables_the_subscription(hass_setup, server, hub, session, paired):
    """Closing the last stream sends `{enabled: false}`; closing one of two does not."""
    device = await online_device(session, server, paired)
    first = await admin_client(hass_setup, server, session)
    second = await admin_client(hass_setup, server, session)
    try:
        assert (await subscribe(first, device, paired.device_id))["success"] is True
        await panel(second, SUBSCRIBE, device_id=paired.device_id, enabled=True)

        await panel(first, SUBSCRIBE, device_id=paired.device_id, enabled=False)
        # Still one viewer, so the device was not told to stop.
        try:
            unexpected = await next_command(device, timeout=0.3)
        except TimeoutError:
            unexpected = None
        assert unexpected is None, unexpected

        stopping = asyncio.create_task(
            panel(second, SUBSCRIBE, device_id=paired.device_id, enabled=False)
        )
        command = await next_command(device)
        assert command["command"] == "log_subscription"
        assert command["args"] == {"enabled": False}
        assert (await stopping)["success"] is True
    finally:
        await first.close()
        await second.close()
        await device.close()


async def test_a_panel_disconnect_cleans_up(hass_setup, server, hub, session, paired):
    """A panel socket closing drops its stream, and the last one stops the device."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    assert (await subscribe(admin, device, paired.device_id))["success"] is True
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.logs.viewers(hub_runtime.devices[paired.device_id]).count == 1

    await admin.close()

    async with asyncio.timeout(5):
        while hub_runtime.logs.viewers(hub_runtime.devices[paired.device_id]).count:
            await asyncio.sleep(0.01)
    # The device hears the stop before its socket goes away.
    command = await next_command(device)
    assert command["args"] == {"enabled": False}
    await device.close()


async def test_a_level_filter_travels_to_the_device(hass_setup, server, hub, session, paired):
    """The subscription's minimum level is the device's own filter."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        pending = asyncio.create_task(
            panel(admin, SUBSCRIBE, device_id=paired.device_id, enabled=True, level="warning")
        )
        command = await next_command(device)
        reply = await pending
        assert command["args"] == {"enabled": True, "level": "warning"}
        assert reply["success"] is True, reply
        assert reply["result"]["level"] == "warning"
    finally:
        await admin.close()
        await device.close()


async def test_an_offline_device_refuses_a_stream(hass_setup, server, hub, session, paired):
    """Streaming an offline device is an error the panel turns into a note."""
    admin = await admin_client(hass_setup, server, session)
    try:
        reply = await panel(admin, SUBSCRIBE, device_id=paired.device_id, enabled=True)
    finally:
        await admin.close()

    assert reply["success"] is False
    assert reply["error"]["code"] == "device_offline"


async def test_the_ttl_refresh_is_scheduled_and_cancelled(hass_setup, server, hub, session, paired):
    """A watcher schedules the device's TTL refresh; the last one to leave cancels it."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    try:
        assert (await subscribe(admin, device, paired.device_id))["success"] is True
        viewers = hub_runtime.logs.viewers(hub_runtime.devices[paired.device_id])
        assert viewers.cancel_refresh is not None, "the TTL refresh must be scheduled"
        assert viewers.expires_at is not None

        # The last viewer leaving stops the device: answer the command it sends.
        stopping = asyncio.create_task(
            panel(admin, SUBSCRIBE, device_id=paired.device_id, enabled=False)
        )
        assert (await next_command(device))["args"] == {"enabled": False}
        assert (await stopping)["success"] is True
        assert viewers.cancel_refresh is None, "nobody watches, so nothing is refreshed"
    finally:
        await admin.close()
        await device.close()


async def test_a_device_dropping_ends_the_stream(hass_setup, server, hub, session, paired):
    """The device's own connection ending tells the panel the stream is off."""
    device = await online_device(session, server, paired)
    admin = await admin_client(hass_setup, server, session)
    try:
        assert (await subscribe(admin, device, paired.device_id))["success"] is True
        await device.close()
        event = await admin.recv()
        assert event["event"]["subscription"]["active"] is False
        await wait_disconnected(hass_setup, paired)
    finally:
        await admin.close()


async def test_a_log_frame_still_validates(hass_setup, server, hub, session, paired):
    """A malformed batch is refused and leaves the ring alone (PROTOCOL 4.8)."""
    device = await online_device(session, server, paired)
    try:
        bad = await device.reply(log_frame(alloc(device), [line("bad level", level="chatty")]))
        assert bad["success"] is False

        await push_lines(device, [line("a real line")])
        hub_runtime = TabulaHub.get(hass_setup)
        assert hub_runtime is not None
        assert [item["message"] for item in hub_runtime.devices[paired.device_id].logs] == [
            "a real line"
        ]
    finally:
        await device.close()


async def test_the_integration_never_logs_a_line(hass_setup, server, hub, session, paired, caplog):
    """The ring is memory only: the integration writes no line to HA's log (P5.4).

    HA's own debug logging of a received frame is not the integration talking:
    the rule is that nothing in this component logs a line's text.
    """
    device = await online_device(session, server, paired)
    try:
        await push_lines(device, [line("zebra-mango-77 in the buffer")])
        ours = [record for record in caplog.records if record.name.startswith("custom_components")]
        assert not any("zebra-mango-77" in record.getMessage() for record in ours), [
            record.getMessage() for record in ours
        ]
    finally:
        await device.close()
