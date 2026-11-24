"""The mock device against the in-process TLP server: pairing, session, commands, recovery."""

from __future__ import annotations

import asyncio

import pytest
from mockdevice.client import Backoff, TLPClient
from mockdevice.codec import Codec
from mockdevice.constants import SCREENSHOT_JPEG
from mockdevice.device import Behavior, MockDevice
from mockdevice.pairing import CredentialRevoked, PairingError, claim, request_pairing
from mockdevice.store import DeviceStore
from mockdevice.testserver import TestServer

codec = Codec()


async def wait_for(predicate, timeout: float = 5.0) -> None:
    async def poll():
        while not predicate():
            await asyncio.sleep(0.01)

    await asyncio.wait_for(poll(), timeout)


async def test_pairing_and_claim(server, http, tmp_path):
    store = DeviceStore.load(tmp_path / "d.json")
    attempt, response = await request_pairing(http, codec, server.url, store.device_id, "Hall")
    assert response["status"] == "pending"
    assert not server.confirm(store.device_id, "000000" if attempt.code != "000000" else "111111")
    assert server.confirm(store.device_id, attempt.code)
    result = await claim(http, codec, server.url, attempt, poll_interval=0.01, timeout=2)
    assert result["credential"]["refresh_token"] == server.devices[store.device_id].refresh_token
    with pytest.raises(PairingError) as err:  # returned once only
        await claim(http, codec, server.url, attempt, poll_interval=0.01, timeout=2)
    assert err.value.code == "expired"


async def test_competing_pair_request_is_refused(server, http, tmp_path):
    store = DeviceStore.load(tmp_path / "d.json")
    await request_pairing(http, codec, server.url, store.device_id)
    with pytest.raises(PairingError) as err:
        await request_pairing(http, codec, server.url, store.device_id)  # new nonce
    assert err.value.code == "already_pending"


async def test_claim_with_wrong_nonce(server, http, tmp_path):
    store = DeviceStore.load(tmp_path / "d.json")
    attempt, _ = await request_pairing(http, codec, server.url, store.device_id)
    attempt.nonce = "A" * 43
    with pytest.raises(PairingError) as err:
        await claim(http, codec, server.url, attempt, poll_interval=0.01, timeout=1)
    assert err.value.code == "bad_nonce"
    assert store.device_id in server.pending  # a guess cannot cancel a real pairing


async def test_register_subscribe_and_state(server, store, start):
    device = MockDevice(base_url=server.url)
    running = await start(store, device)
    record = await server.wait_registered(store.device_id)
    assert record.state == device.state
    assert set(running.client.accepted_capabilities) <= set(device.capabilities)
    device.update(brightness=10)
    device.update(brightness=20)  # coalesced
    device.update(charging=False)
    await wait_for(lambda: record.state["brightness"] == 20 and not record.state["charging"])
    assert store.ha_device_id == record.ha_device_id


async def test_illuminance_round_trip(server, store, start):
    """The capability is accepted, register carries the reading and a delta reaches HA (P3.16)."""
    device = MockDevice(base_url=server.url)
    running = await start(store, device)
    record = await server.wait_registered(store.device_id)
    assert "illuminance" in device.capabilities
    assert "illuminance" in running.client.accepted_capabilities
    assert record.state["illuminance"] == 128.5  # the complete picture (PROTOCOL 4.2)
    device.update(illuminance=64.0)
    await wait_for(lambda: record.state["illuminance"] == 64.0)


@pytest.mark.parametrize(
    ("command", "args", "check"),
    [
        ("screen", {"on": False}, lambda s: s["screen_on"] is False),
        ("screen", {"brightness": 5}, lambda s: s["brightness"] == 5),
        ("media_control", {"action": "mute"}, lambda s: s["muted"] is True),
        ("screensaver", {"active": True, "mode": "dim"}, lambda s: s["screensaver_mode"] == "dim"),
        ("navigate", {"path": "/lovelace/1?x=1"}, lambda s: s["current_page"] == "/lovelace/1"),
    ],
)
async def test_command_ack_updates_state(server, store, start, command, args, check):
    await start(store)
    record = await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, command, args)
    assert result["ok"] is True
    await wait_for(lambda: check(record.state))


async def test_every_command_fixture_is_answered(server, store, start):
    """Each command fixture gets exactly one valid result (ACK or a NACK the protocol allows)."""
    device = MockDevice(base_url=server.url)
    await start(store, device)
    await server.wait_registered(store.device_id)
    import json

    from mockdevice.codec import REPO_ROOT

    fixtures = REPO_ROOT / "schema" / "fixtures" / "tlp" / "v1"
    for path in sorted(fixtures.glob("command_*.json")):
        if path.name.startswith("command_result"):
            continue
        if path.name.startswith("command_voice_"):
            # Voice commands have a lifecycle (start, stream, stop), so they are
            # driven one turn at a time in test_voice.py rather than fired blind.
            continue
        event = json.loads(path.read_text())["event"]
        # Fixtures must not depend on each other's order, so put the device in
        # the state the fixture assumes instead of relying on the glob order: a
        # ring needs an idle device, and a signal belongs to a call that is up.
        call_id = event["args"].get("call_id")
        if event["command"] == "intercom_ring":
            device.hang_up()
        elif (
            event["command"] == "intercom_signal"
            and (device.intercom_call or {}).get("call_id") != call_id
        ):
            device.hang_up()
            await device.handle("intercom_ring", intercom_ring(call_id))
        result = await server.send_command(store.device_id, event["command"], event["args"])
        assert result["ok"] or result["error"]["code"] in {"failed", "unsupported_command"}, path


async def test_set_settings_persists(server, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    args = {"revision": 7, "schema_version": 1, "settings": {"a": 1}}
    result = await server.send_command(store.device_id, "set_settings", args)
    assert result["data"] == {"revision": 7}
    reloaded = DeviceStore.load(store.path)
    assert (reloaded.settings_revision, reloaded.settings) == (7, {"a": 1})


async def test_failure_delay_and_drop_modes(server, store, start):
    behavior = Behavior(fail={"reload"}, drop={"clear_cache"}, delay=0.2)
    await start(store, MockDevice(behavior=behavior, base_url=server.url))
    await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, "reload", {})
    assert result["error"]["code"] == "failed"
    loop = asyncio.get_running_loop()
    t0 = loop.time()
    assert (await server.send_command(store.device_id, "restart_app", {}))["ok"]
    assert loop.time() - t0 >= 0.2
    with pytest.raises(TimeoutError):
        await server.send_command(store.device_id, "clear_cache", {}, timeout=0.5)


async def test_unknown_command_is_nacked(server, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, "teleport", {"to": "mars"})
    assert result["error"]["code"] == "unsupported_command"


async def test_navigate_off_origin_is_refused(server, store, start):
    await start(store)
    await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, "navigate", {"path": "/lovelace"})
    assert result["ok"]
    # The schema already blocks "//host"; the device checks resolution too.
    device = MockDevice(base_url=server.url)
    with pytest.raises(Exception, match="origin"):
        await device.handle("navigate", {"path": "//evil.example/x"})


async def test_events_and_logs(server, store, start):
    device = MockDevice(base_url=server.url)
    await start(store, device)
    record = await server.wait_registered(store.device_id)
    await server.send_command(store.device_id, "log_subscription", {"enabled": True})
    await server.send_command(store.device_id, "notify", {"message": "hi", "tag": "t"})
    await wait_for(lambda: record.events and record.logs, timeout=4)
    assert record.events[0]["event"] == "notification_shown"
    assert any("notify" in line["message"] for line in record.logs)


async def test_reconnects_after_server_drops(server, store, start):
    await start(store)
    record = await server.wait_registered(store.device_id)
    await record.current.ws.close()
    await server.wait_registered(store.device_id, connections=2)
    assert (await server.send_command(store.device_id, "reload", {}))["ok"]


async def test_superseded_connection_closes(server, store, start, tmp_path):
    first = await start(store)
    await server.wait_registered(store.device_id)
    second_store = DeviceStore.load(store.path)  # same identity, second process
    second = await start(second_store)
    await server.wait_registered(store.device_id, connections=2)
    # The first client's next frame is refused as superseded and it closes that socket.
    first.client.device.update(brightness=1)
    await wait_for(lambda: first.client.superseded == 1)
    assert second.client.superseded == 0


async def test_ping_keepalive(server, store, start):
    running = await start(store)
    running.client.ping_interval = 0.05
    await server.wait_registered(store.device_id)
    await wait_for(lambda: any(t == "ping" for t, _ in server.received))


async def test_revoked_credential_stops(server, store, http):
    server.revoke(store.device_id)
    client = TLPClient(server.url, store, MockDevice())
    with pytest.raises(CredentialRevoked):
        await asyncio.wait_for(client.run(http), 5)


async def test_unsupported_protocol_pair(server, http):
    async with http.post(
        f"{server.url}/api/tabula/pair",
        json={"device_id": "x", "protocol_version": 99},
    ) as resp:
        assert resp.status == 400
        assert (await resp.json())["error"] == "unsupported_protocol"


class RecordingBackoff(Backoff):
    def __init__(self) -> None:
        super().__init__(base=0.01, cap=0.02)
        self.stable: list[float] = []

    def connection_ended(self, stable_for: float) -> None:
        self.stable.append(stable_for)
        super().connection_ended(stable_for)


class SlowRegisterServer(TestServer):
    """Takes REGISTER_DELAY to answer tabula/register."""

    REGISTER_DELAY = 0.3

    async def _register(self, session, frame):
        await asyncio.sleep(self.REGISTER_DELAY)
        return await super()._register(session, frame)


async def test_backoff_reset_window_counts_from_registration(tmp_path, http):
    async with SlowRegisterServer() as server:
        store = DeviceStore.load(tmp_path / "d.json")
        store.refresh_token = server.add_device(store.device_id).refresh_token
        backoff = RecordingBackoff()
        client = TLPClient(server.url, store, MockDevice(base_url=server.url), backoff=backoff)
        task = asyncio.create_task(client.run(http))
        try:
            record = await server.wait_registered(store.device_id)
            await asyncio.sleep(0.1)
            # Stale access token: the next attempt fails at auth, before registering.
            server.access.clear()
            await record.current.ws.close()
            await server.wait_registered(store.device_id, connections=2)
        finally:
            client.stop()
            await asyncio.wait_for(task, 5)
    # First connection: stable time excludes the slow register, so it is under the delay.
    assert 0.1 <= backoff.stable[0] < SlowRegisterServer.REGISTER_DELAY
    # The attempt that never registered counts as zero stable time.
    assert backoff.stable[1] == 0.0


# --- screenshots (P3.13) ----------------------------------------------------


async def test_screenshot_command_uploads_before_answering(server, store, start):
    """The upload names the command and lands before the result (PROTOCOL.md 5)."""
    running = await start(store)
    record = await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, "screenshot", {})
    assert result["ok"] is True
    assert record.last_upload == SCREENSHOT_JPEG
    assert record.upload_before_result is True
    assert record.report["screenshot"]["before_result"] is True
    # The capture is consumed: a new capture starts empty.
    assert running.client.device.last_screenshot is None


async def test_screenshot_with_screen_off_skips_the_upload(server, store, start):
    """Screen off: the command still answers ok, but nothing is uploaded (F-MGT-07)."""
    running = await start(store)
    device = running.client.device
    await server.wait_registered(store.device_id)
    device.update(screen_on=False)
    result = await server.send_command(store.device_id, "screenshot", {})
    assert result["ok"] is True
    assert server.devices[store.device_id].last_upload is None


async def test_screenshot_upload_failure_fails_the_command(server, store, start, monkeypatch):
    """An upload that fails fails the command (`failed`, PROTOCOL.md 6)."""

    async def broken(self, command_id, jpeg):
        return False

    monkeypatch.setattr(TLPClient, "_upload_screenshot", broken)
    await start(store)
    await server.wait_registered(store.device_id)
    result = await server.send_command(store.device_id, "screenshot", {})
    assert result["error"]["code"] == "failed"


# --- intercom (P9.1, ADR 0025) --------------------------------------------------

CALL = "01JC9Z6Q2H3W7K5N8T4V0BXDRA"


def intercom_ring(call_id: str = CALL, mode: str = "call") -> dict:
    return {
        "call_id": call_id,
        "caller_device_id": "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b",
        "caller_name": "Office Kiosk",
        "mode": mode,
    }


async def test_intercom_call_answers_and_ends_over_the_session(server, store, start):
    """Ring, answer and hang up as HA drives them, with the events HA would act on."""
    device = MockDevice(base_url=server.url)
    await start(store, device)
    record = await server.wait_registered(store.device_id)

    assert (await server.send_command(store.device_id, "intercom_ring", intercom_ring()))["ok"]
    await wait_for(lambda: record.state.get("intercom_state") == "ringing")

    device.answer_intercom_call()
    await wait_for(lambda: record.state.get("intercom_state") == "in_call")
    await wait_for(lambda: any(e["event"] == "intercom_answer" for e in record.events))

    ended = await server.send_command(
        store.device_id, "intercom_end", {"call_id": CALL, "reason": "command"}
    )
    assert ended["ok"]
    await wait_for(lambda: record.state.get("intercom_state") == "idle")
    await wait_for(
        lambda: any(
            e["event"] == "intercom_ended" and e["data"]["reason"] == "command"
            for e in record.events
        )
    )


async def test_intercom_signal_is_relayed_between_the_two_sides(server, store, start):
    device = MockDevice(base_url=server.url)
    await start(store, device)
    record = await server.wait_registered(store.device_id)
    ringing = await server.send_command(store.device_id, "intercom_ring", intercom_ring())
    assert ringing["ok"]
    offer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    result = await server.send_command(
        store.device_id,
        "intercom_signal",
        {"call_id": CALL, "kind": "offer", "payload": offer},
    )
    assert result["ok"]
    assert device.intercom_signals == [{"kind": "offer", "payload": offer}]

    device.send_intercom_signal("answer", offer)
    await wait_for(
        lambda: any(
            e["event"] == "intercom_signal" and e["data"]["kind"] == "answer" for e in record.events
        )
    )


async def test_intercom_is_negotiated_and_commands_are_gated(server, store, start):
    """A device that never declares the capability is not offered the commands.

    The integration only sends what a device declared (PROTOCOL 4.5); the
    conformance side here checks the two halves it can see: the capability is
    absent from register_result, and the device NACKs the command anyway.
    """
    device = MockDevice(capabilities=["screen", "brightness"], base_url=server.url)
    await start(store, device)
    record = await server.wait_registered(store.device_id)
    assert [c for c in record.capabilities if c.startswith("intercom")] == []
    # PROTOCOL 4.4: a gated state key belongs only to a device that declares the
    # capability. `state` is strict for unknown keys even in a tolerant
    # receiver, so announcing the key would get the whole register refused.
    # Every gated key, not only the intercom ones: a device without the
    # capability must not announce the key that covers it (PROTOCOL 4.4).
    gated = ("intercom_state", "illuminance")
    assert [k for k in record.state if k in gated] == []
    assert [k for k in device.state if k in gated] == []

    for command, args in (
        ("intercom_ring", intercom_ring()),
        ("intercom_signal", {"call_id": CALL, "kind": "offer", "payload": "x"}),
        ("intercom_end", {"call_id": CALL, "reason": "command"}),
    ):
        result = await server.send_command(store.device_id, command, args)
        assert result["error"]["code"] == "unsupported_command", command


async def test_a_declared_intercom_device_is_accepted(server, store, start):
    await start(store)
    record = await server.wait_registered(store.device_id)
    assert {"intercom_listen", "intercom_talk"} <= set(record.capabilities)
    assert record.state.get("intercom_state") == "idle"
