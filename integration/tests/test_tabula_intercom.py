"""Intercom calls in the integration (P9.2, ADR 0025 decisions 4-5, ADR 0028).

The device side is a raw WebSocket client per tablet, driven with the golden
TLP fixtures: every command the integration sends is recorded and answered, so
the tests see the wire the way a tablet does. No audio is played or captured
anywhere here; the payloads are the peers' own signaling text, which HA copies
without reading.

The last section drives calls the way a person speaks them (P9.6, ADR 0036):
the real conversation agent matches a sentence to a Tabula intent, so the
sentences on disk, the capability gate, the spoken names and every refusal are
tested through the same path a satellite uses.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncGenerator, Callable
from dataclasses import dataclass, field
from datetime import timedelta
from functools import partial
from pathlib import Path
from typing import Any

import hassil
import pytest
import yaml
from custom_components.tabula import TabulaHub, intercom
from custom_components.tabula.const import DOMAIN, INTERCOM_PERSONS
from custom_components.tabula.events import (
    INTERCOM_ANSWERED,
    INTERCOM_ENDED,
    INTERCOM_EVENTS,
    INTERCOM_MISSED,
    INTERCOM_STARTED,
)
from custom_components.tabula.intents import (
    INTENT_BROADCAST,
    INTENT_CALL,
    PersonMapError,
    parse_persons,
    persons_to_text,
)
from helpers import WS, Paired, frame_fixture, pair_device, uid, ws_connect
from homeassistant.components import conversation
from homeassistant.core import Context, Event, HomeAssistant, callback
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import intent as intent_helper
from homeassistant.util import dt as dt_util
from homeassistant.util.async_ import run_callback_threadsafe
from pytest_homeassistant_custom_component.common import async_fire_time_changed

try:
    # phacc's own wrapper: it hands the fire to HA's loop with
    # `run_callback_threadsafe` and waits. It refuses to be called from inside
    # any event loop, which is why `advance` stands in an executor thread.
    from pytest_homeassistant_custom_component.common import fire_time_changed
except ImportError:  # pragma: no cover - only the oldest supported harness

    def fire_time_changed(hass: HomeAssistant, when) -> None:  # type: ignore[misc]
        """The same wrapper, for a harness that does not export it."""
        run_callback_threadsafe(hass.loop, partial(async_fire_time_changed, hass, when)).result()


@pytest.fixture
def expected_lingering_timers(request: pytest.FixtureRequest) -> bool:
    """One unload test tolerates a leftover aiohttp websocket heartbeat timer.

    HA 2026.10+ (aiohttp 3.14) stopped cancelling prior heartbeat handles, so
    the tablet's socket can leave one scheduled when an unload closes it while
    the test's own connection is still open. The timer belongs to the test
    harness's aiohttp, not to the integration, and every other test here keeps
    the strict check (see test_tabula_lifecycle.py for the same exemption).
    """
    return request.node.name == "test_voice_unload_removes_the_intents_and_the_sentences"


# The fixtures' capability list, with the intercom capabilities on top. A
# tablet that can hear has `intercom_listen`; one that can capture as well has
# `intercom_talk` (PROTOCOL.md 4.5, ADR 0025 decision 7).
BASE_CAPS: list[str] = frame_fixture("register")["capabilities"]
INTERCOM_CAPS: list[str] = [
    *BASE_CAPS,
    "intercom_listen",
    "intercom_talk",
    "intercom_caller",
]
# A build that predates the caller's own frame (ADR 0037 decision 8): it can be
# rung and can answer, and can never be the prepared caller of an HA call.
NO_CALLER_CAPS: list[str] = [*BASE_CAPS, "intercom_listen", "intercom_talk"]
LISTEN_ONLY_CAPS: list[str] = [*BASE_CAPS, "intercom_listen"]
NO_INTERCOM_CAPS: list[str] = list(BASE_CAPS)

CALL_ID = "01JC9Z6Q2H3W7K5N8T4V0BXDRA"
OFFER = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
ANSWER = "v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"


def register_frame(
    paired: Paired, caps: list[str], *, msg_id: int = 2, kind: str = "idle"
) -> dict[str, Any]:
    """A register frame for the capabilities given, with the matching state."""
    state = dict(frame_fixture("register")["state"])
    if "intercom_listen" in caps:
        # PROTOCOL 4.4: a capability-gated key is only sent by a device that
        # declares the capability that covers it.
        state["intercom_state"] = kind
    return frame_fixture(
        "register",
        id=msg_id,
        device_id=paired.device_id,
        capabilities=caps,
        state=state,
    )


class Tablet:
    """A paired tablet on its own WebSocket, answering commands on the wire."""

    def __init__(self, client: WS, paired: Paired, name: str) -> None:
        self.client = client
        self.paired = paired
        self.name = name
        self.commands: list[dict[str, Any]] = []
        # command name -> (code, message) for a NACK instead of ok.
        self.nack: dict[str, tuple[str, str]] = {}
        self._msg_id = 100
        self._reader: asyncio.Task[None] | None = None
        self._send_lock = asyncio.Lock()

    async def start(self) -> None:
        self._reader = asyncio.create_task(self._read())

    async def _read(self) -> None:
        while True:
            try:
                frame = await self.client.recv(timeout=60)
            except (AssertionError, TimeoutError, ConnectionError):
                return
            if frame.get("type") != "event":
                continue
            command = frame["event"]
            self.commands.append(command)
            self._msg_id += 1
            if (refusal := self.nack.get(command["command"])) is not None:
                reply = frame_fixture(
                    "command_result",
                    id=self._msg_id,
                    command_id=command["command_id"],
                    ok=False,
                    error={"code": refusal[0], "message": refusal[1]},
                )
            else:
                reply = frame_fixture(
                    "command_result", id=self._msg_id, command_id=command["command_id"], ok=True
                )
            await self.send(reply)

    async def send(self, frame: dict[str, Any]) -> None:
        async with self._send_lock:
            await self.client.send(frame)

    async def event(self, fixture: str, data: dict[str, Any]) -> None:
        """Send a device event (PROTOCOL.md 4.7) without waiting for its ack."""
        self._msg_id += 1
        await self.send(frame_fixture(fixture, id=self._msg_id, data=data))

    async def state(self, state: dict[str, Any]) -> None:
        self._msg_id += 1
        await self.send(frame_fixture("state_delta", id=self._msg_id, state=state))

    async def reregister(self, caps: list[str], kind: str = "idle") -> None:
        """Register again on the same socket, as a relaunched app does."""
        self._msg_id += 1
        await self.send(register_frame(self.paired, caps, msg_id=self._msg_id, kind=kind))

    async def next_command(self, name: str, timeout: float = 5.0) -> dict[str, Any]:
        """The next command of that name, consumed from the record."""
        async with asyncio.timeout(timeout):
            while True:
                for index, command in enumerate(self.commands):
                    if command["command"] == name:
                        return self.commands.pop(index)
                await asyncio.sleep(0.01)

    async def no_command(self, name: str, wait: float = 0.3) -> None:
        """Nothing of that name arrived within `wait`."""
        await asyncio.sleep(wait)
        assert not [c for c in self.commands if c["command"] == name], self.commands

    async def close(self) -> None:
        if self._reader is not None:
            self._reader.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reader
        await self.client.close()


@dataclass
class Bus:
    """The four call-lifecycle events, collected as they fire."""

    hass: HomeAssistant
    events: dict[str, list[Event]] = field(default_factory=dict)

    def __post_init__(self) -> None:
        for name in INTERCOM_EVENTS:
            self.events[name] = []
            self.hass.bus.async_listen(name, self._capture(name))

    def _capture(self, name: str) -> Callable[[Event], None]:
        @callback
        def handler(event: Event) -> None:
            self.events[name].append(event)

        return handler

    async def wait(self, name: str, count: int = 1, timeout: float = 5.0) -> Event:
        async with asyncio.timeout(timeout):
            while len(self.events[name]) < count:
                await asyncio.sleep(0.01)
        return self.events[name][count - 1]

    def silent(self, name: str) -> bool:
        return not self.events[name]


@pytest.fixture(autouse=True)
async def clean_calls(hass_setup) -> AsyncGenerator[None]:
    """Drop every call and timer the test left behind."""
    yield
    hub = TabulaHub.get(hass_setup)
    if hub is not None:
        hub.intercom.shutdown()


@pytest.fixture(name="bus")
def bus_fixture(hass_setup) -> Bus:
    return Bus(hass_setup)


@pytest.fixture(name="make_tablet")
async def make_tablet_fixture(
    hass_setup, server, hub, session
) -> AsyncGenerator[Callable[..., Any]]:
    """Pair a tablet and put it on a subscribed WebSocket."""
    made: list[Tablet] = []

    async def make(
        name: str = "Tablet", caps: list[str] | None = None, kind: str = "idle"
    ) -> Tablet:
        paired = await pair_device(hass_setup, server, uid(), name=name)
        client = await ws_connect(session, server, paired.refresh_token)
        tablet = Tablet(client, paired, name)
        reply = await client.reply(register_frame(paired, caps or INTERCOM_CAPS, kind=kind))
        assert reply["success"], reply
        reply = await client.reply(frame_fixture("subscribe", id=3))
        assert reply["success"], reply
        await tablet.start()
        await hass_setup.async_block_till_done()
        made.append(tablet)
        return tablet

    yield make
    for tablet in made:
        await tablet.close()


def active_calls(hass: HomeAssistant) -> int:
    hub_runtime = TabulaHub.get(hass)
    assert hub_runtime is not None
    return len(hub_runtime.intercom.calls)


async def advance(hass: HomeAssistant, seconds: float = 1.0) -> None:
    """Move HA's clock, which is what fires its call-later timers in tests.

    The fire happens on HA's loop (the thread-safe helper), because the timer
    callbacks schedule work with `hass.async_create_task`, which is only legal
    there — the same contract the integration's own timers rely on.
    """
    when = dt_util.utcnow() + timedelta(seconds=seconds)
    await hass.async_add_executor_job(fire_time_changed, hass, when)
    await hass.async_block_till_done()


async def until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


async def start_call(
    hass: HomeAssistant,
    caller: Tablet,
    callee: Tablet | None,
    *,
    mode: str = "call",
) -> None:
    data: dict[str, Any] = {"caller": caller.paired.ha_device_id, "mode": mode}
    if callee is not None:
        data["device_id"] = callee.paired.ha_device_id
    await hass.services.async_call(DOMAIN, "intercom_call", data, blocking=True)


async def end_call(hass: HomeAssistant, call_id: str) -> None:
    await hass.services.async_call(DOMAIN, "intercom_end", {"call_id": call_id}, blocking=True)


async def answered_call(
    hass: HomeAssistant, caller: Tablet, callee: Tablet, *, signal: bool = True
) -> str:
    """A call that has been answered (and, by default, has exchanged a signal)."""
    await start_call(hass, caller, callee)
    call_id = (await callee.next_command("intercom_ring"))["args"]["call_id"]
    await callee.event("event.intercom_answer", {"call_id": call_id})
    if signal:
        await caller.event(
            "event.intercom_signal",
            {"call_id": call_id, "kind": "offer", "payload": OFFER},
        )
        await callee.next_command("intercom_signal")
    return call_id


def entity_id(hass: HomeAssistant, platform: str, paired: Paired, key: str) -> str | None:
    return er.async_get(hass).async_get_entity_id(platform, DOMAIN, f"{paired.device_id}_{key}")


def disable_entities(hass: HomeAssistant, tablet: Tablet) -> None:
    """Hide every entity of a tablet, as a household that disabled them has."""
    registry = er.async_get(hass)
    for entry in er.async_entries_for_device(registry, tablet.paired.ha_device_id):
        registry.async_update_entity(entry.entity_id, disabled_by=er.RegistryEntryDisabler.USER)


def put_in_area(hass: HomeAssistant, tablet: Tablet, area_name: str) -> str:
    """Assign a tablet's HA device to an area, creating the area once."""
    registry = ar.async_get(hass)
    area = registry.async_get_area_by_name(area_name) or registry.async_create(area_name)
    dr.async_get(hass).async_update_device(tablet.paired.ha_device_id, area_id=area.id)
    return area.id


# --- a call, end to end ------------------------------------------------------


async def test_call_rings_answers_relays_and_ends(hass_setup, make_tablet, bus):
    """The whole path: ring, answer, both signal directions, hang up from HA."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")

    await start_call(hass_setup, office, kitchen)

    ring = await kitchen.next_command("intercom_ring")
    call_id = ring["args"]["call_id"]
    assert ring["args"] == {
        "call_id": call_id,
        "caller_device_id": office.paired.device_id,
        "caller_name": "Office Kiosk",
        "mode": "call",
    }
    # The caller is not rung for its own call.
    await office.no_command("intercom_ring", wait=0.1)

    started = await bus.wait(INTERCOM_STARTED)
    assert started.data == {
        "call_id": call_id,
        "mode": "call",
        "caller": office.paired.ha_device_id,
        "callees": [kitchen.paired.ha_device_id],
        "tabula_caller": office.paired.device_id,
        "tabula_callees": [kitchen.paired.device_id],
    }

    await kitchen.event("event.intercom_answer", {"call_id": call_id})
    answered = await bus.wait(INTERCOM_ANSWERED)
    assert answered.data["call_id"] == call_id
    assert answered.data["answered_by"] == kitchen.paired.ha_device_id

    # The offer goes caller -> callee, unchanged.
    await office.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "offer", "payload": OFFER}
    )
    relayed = await kitchen.next_command("intercom_signal")
    assert relayed["args"] == {"call_id": call_id, "kind": "offer", "payload": OFFER}

    # And the answer comes back the other way, unchanged.
    await kitchen.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "answer", "payload": ANSWER}
    )
    relayed = await office.next_command("intercom_signal")
    assert relayed["args"] == {"call_id": call_id, "kind": "answer", "payload": ANSWER}

    await end_call(hass_setup, call_id)
    for tablet in (office, kitchen):
        end = await tablet.next_command("intercom_end")
        assert end["args"] == {"call_id": call_id, "reason": "command"}
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["call_id"] == call_id
    assert ended.data["reason"] == "command"

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.intercom.calls == {}


async def test_decline_ends_the_call_for_both_sides(hass_setup, make_tablet, bus):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    await kitchen.event("event.intercom_decline", {"call_id": call_id})

    for tablet in (office, kitchen):
        end = await tablet.next_command("intercom_end")
        assert end["args"] == {"call_id": call_id, "reason": "decline"}
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["reason"] == "decline"
    assert bus.silent(INTERCOM_MISSED)


# --- timers ------------------------------------------------------------------


async def test_unanswered_call_is_missed_at_the_ring_limit(
    hass_setup, make_tablet, bus, monkeypatch
):
    """The ring limit ends an unanswered call as missed (ADR 0028 guard 2)."""
    monkeypatch.setattr(intercom, "INTERCOM_RING_LIMIT_S", 0.2)
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    await advance(hass_setup)
    missed = await bus.wait(INTERCOM_MISSED)
    assert missed.data["call_id"] == call_id
    assert missed.data["reason"] == "timeout"
    for tablet in (office, kitchen):
        end = await tablet.next_command("intercom_end")
        assert end["args"] == {"call_id": call_id, "reason": "timeout"}
    # A missed call fires the missed event instead of ended, once.
    assert bus.silent(INTERCOM_ENDED)


async def test_answered_call_that_never_connects_times_out(
    hass_setup, make_tablet, bus, monkeypatch
):
    """Answered, but no signal in time: the connect timeout ends it."""
    monkeypatch.setattr(intercom, "INTERCOM_CONNECT_TIMEOUT_S", 0.2)
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await kitchen.event("event.intercom_answer", {"call_id": call_id})
    await bus.wait(INTERCOM_ANSWERED)

    await advance(hass_setup)
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["reason"] == "timeout"
    for tablet in (office, kitchen):
        end = await tablet.next_command("intercom_end")
        assert end["args"] == {"call_id": call_id, "reason": "timeout"}
    assert bus.silent(INTERCOM_MISSED)


async def test_hard_limit_ends_a_connected_call(hass_setup, make_tablet, bus, monkeypatch):
    """Every call ends at the hard limit, however it is going (guard 2)."""
    monkeypatch.setattr(intercom, "INTERCOM_RING_LIMIT_S", 5.0)
    monkeypatch.setattr(intercom, "INTERCOM_CONNECT_TIMEOUT_S", 5.0)
    monkeypatch.setattr(intercom, "INTERCOM_CALL_LIMIT_S", 0.3)
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    call_id = await answered_call(hass_setup, office, kitchen)

    await advance(hass_setup)
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["call_id"] == call_id
    assert ended.data["reason"] == "limit"
    for tablet in (office, kitchen):
        end = await tablet.next_command("intercom_end")
        assert end["args"] == {"call_id": call_id, "reason": "limit"}


async def test_end_from_ha_reaches_every_device_in_every_state(hass_setup, make_tablet, bus):
    """Guard 3, state by state: ringing, connecting, in a call, broadcasting."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")

    # ringing
    await start_call(hass_setup, office, kitchen)
    ringing = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await end_call(hass_setup, ringing)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": ringing,
            "reason": "command",
        }

    # connecting: answered, no signal yet
    await start_call(hass_setup, office, kitchen)
    connecting = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await kitchen.event("event.intercom_answer", {"call_id": connecting})
    await bus.wait(INTERCOM_ANSWERED)
    await end_call(hass_setup, connecting)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": connecting,
            "reason": "command",
        }

    # in a call: answered and signaling
    in_call = await answered_call(hass_setup, office, kitchen)
    await end_call(hass_setup, in_call)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": in_call,
            "reason": "command",
        }

    # broadcasting
    await start_call(hass_setup, office, kitchen, mode="broadcast")
    broadcasting = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await end_call(hass_setup, broadcasting)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": broadcasting,
            "reason": "command",
        }

    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.intercom.calls == {}
    assert len(bus.events[INTERCOM_ENDED]) == 4


async def test_end_by_device_target(hass_setup, make_tablet):
    """`intercom_end` can name a tablet instead of a call id."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    await hass_setup.services.async_call(
        DOMAIN,
        "intercom_end",
        {"device_id": kitchen.paired.ha_device_id},
        blocking=True,
    )
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": call_id,
            "reason": "command",
        }


async def test_end_with_nothing_to_end_is_an_error(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    with pytest.raises(ServiceValidationError):
        await end_call(hass_setup, "01JC9Z6Q2H3W7K5N8T4V0BXDRA")
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(DOMAIN, "intercom_end", {}, blocking=True)
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            DOMAIN,
            "intercom_end",
            {"call_id": "01JC9Z6Q2H3W7K5N8T4V0BXDRA", "device_id": office.paired.ha_device_id},
            blocking=True,
        )
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            DOMAIN,
            "intercom_end",
            {"device_id": office.paired.ha_device_id},
            blocking=True,
        )


# --- disconnects and restarts (guard 4) ---------------------------------------


async def test_device_disconnect_mid_call_ends_it_for_the_other_side(hass_setup, make_tablet, bus):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    call_id = await answered_call(hass_setup, office, kitchen)

    await kitchen.close()

    end = await office.next_command("intercom_end")
    assert end["args"] == {"call_id": call_id, "reason": "disconnect"}
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["reason"] == "disconnect"
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.intercom.calls == {}


async def test_re_register_mid_call_never_resumes(hass_setup, make_tablet, bus, session, server):
    """A restart ends the call and is told to hang up, not rung again."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    call_id = await answered_call(hass_setup, office, kitchen)

    # The tablet comes back on a new socket, still reporting the old call.
    client = await ws_connect(session, server, kitchen.paired.refresh_token)
    restarted = Tablet(client, kitchen.paired, "Kitchen Kiosk")
    reply = await client.reply(register_frame(kitchen.paired, INTERCOM_CAPS, kind="in_call"))
    assert reply["success"], reply
    reply = await client.reply(frame_fixture("subscribe", id=3))
    assert reply["success"], reply
    await restarted.start()

    assert (await office.next_command("intercom_end"))["args"] == {
        "call_id": call_id,
        "reason": "disconnect",
    }
    # The stale state is answered with intercom_end, not a new ring.
    assert (await restarted.next_command("intercom_end"))["args"] == {
        "call_id": call_id,
        "reason": "disconnect",
    }
    await restarted.no_command("intercom_ring")
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["reason"] == "disconnect"
    await restarted.close()


async def test_stale_call_state_ha_never_had_is_logged_not_guessed(hass_setup, make_tablet, caplog):
    """A non-idle state from a call HA does not know cannot be ended by id."""
    caplog.set_level("WARNING")
    tablet = await make_tablet("Kitchen Kiosk", kind="in_call")
    await hass_setup.async_block_till_done()
    await tablet.no_command("intercom_end")
    assert "HA has no call to end" in caplog.text


# --- who may be called -------------------------------------------------------


async def test_two_tablets_in_one_area_are_refused_a_call(hass_setup, make_tablet):
    """Same-area calls feed back (ADR 0025 consequences): refuse and say why."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, kitchen, "Office")

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, office, kitchen)
    assert err.value.translation_key == "intercom_same_area"
    await kitchen.no_command("intercom_ring")
    await office.no_command("intercom_end")


async def test_broadcast_skips_a_tablet_in_the_callers_area(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, kitchen, "Office")

    await start_call(hass_setup, office, None, mode="broadcast")

    ring = await bedroom.next_command("intercom_ring")
    assert ring["args"]["mode"] == "broadcast"
    await kitchen.no_command("intercom_ring")
    await office.no_command("intercom_ring")
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    call = hub_runtime.intercom.calls[ring["args"]["call_id"]]
    assert call.callees == [bedroom.paired.device_id]
    assert call.state == intercom.STATE_BROADCASTING


async def test_broadcast_without_a_target_rings_every_listening_tablet(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")

    await start_call(hass_setup, office, None, mode="broadcast")

    for tablet in (kitchen, bedroom):
        ring = await tablet.next_command("intercom_ring")
        assert ring["args"]["mode"] == "broadcast"
        assert ring["args"]["caller_device_id"] == office.paired.device_id
    await office.no_command("intercom_ring")
    # A broadcast is never answered: it is live from the start.
    assert active_calls(hass_setup) == 1


async def test_a_tablet_that_cannot_hear_is_never_rung(hass_setup, make_tablet):
    """Capability gating: only a device that declared `intercom_listen` is rung."""
    office = await make_tablet("Office Kiosk")
    plain = await make_tablet("Plain Tablet", caps=NO_INTERCOM_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk", caps=LISTEN_ONLY_CAPS)

    await hass_setup.services.async_call(
        DOMAIN,
        "intercom_call",
        {
            "caller": office.paired.ha_device_id,
            "device_id": [plain.paired.ha_device_id, kitchen.paired.ha_device_id],
        },
        blocking=True,
    )
    await kitchen.next_command("intercom_ring")
    assert plain.commands == []

    # Nothing left that can hear: an error, not a call that rings nobody.
    with pytest.raises(ServiceValidationError):
        await start_call(hass_setup, office, plain)


async def test_a_tablet_already_in_a_call_is_not_rung_again(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    await start_call(hass_setup, office, kitchen)
    await kitchen.next_command("intercom_ring")

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, bedroom, kitchen)
    assert err.value.translation_key == "intercom_busy"
    await kitchen.no_command("intercom_ring")


async def test_a_ring_refused_busy_ends_the_callers_side(hass_setup, make_tablet, bus):
    """PROTOCOL 4.5: a busy NACK becomes the caller's `intercom_end` reason."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    kitchen.nack["intercom_ring"] = ("busy", "already in a call")

    with pytest.raises(ServiceValidationError):
        await start_call(hass_setup, office, kitchen)

    end = await office.next_command("intercom_end")
    assert end["args"]["reason"] == "busy"
    ended = await bus.wait(INTERCOM_ENDED)
    assert ended.data["reason"] == "busy"


async def test_caller_must_be_a_tablet_that_can_talk(hass_setup, make_tablet):
    listen_only = await make_tablet("Kitchen Kiosk", caps=LISTEN_ONLY_CAPS)
    office = await make_tablet("Office Kiosk")
    with pytest.raises(ServiceValidationError):
        await start_call(hass_setup, listen_only, office)
    with pytest.raises(ServiceValidationError):
        await hass_setup.services.async_call(
            DOMAIN,
            "intercom_call",
            {"caller": "not-a-tablet", "device_id": office.paired.ha_device_id},
            blocking=True,
        )


async def test_two_calls_on_different_pairs_run_apart(hass_setup, make_tablet):
    """Each call relays only between its own two peers."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    hall = await make_tablet("Hall Kiosk")

    await start_call(hass_setup, office, kitchen)
    first = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await start_call(hass_setup, bedroom, hall)
    second = (await hall.next_command("intercom_ring"))["args"]["call_id"]
    assert first != second

    await office.event(
        "event.intercom_signal", {"call_id": first, "kind": "offer", "payload": OFFER}
    )
    relayed = await kitchen.next_command("intercom_signal")
    assert relayed["args"]["call_id"] == first
    await bedroom.event(
        "event.intercom_signal", {"call_id": second, "kind": "offer", "payload": ANSWER}
    )
    relayed = await hall.next_command("intercom_signal")
    assert relayed["args"] == {"call_id": second, "kind": "offer", "payload": ANSWER}
    # Neither pair saw the other's signaling.
    await kitchen.no_command("intercom_signal", wait=0.1)
    await hall.no_command("intercom_signal", wait=0.1)
    assert active_calls(hass_setup) == 2


async def test_signal_from_a_tablet_not_in_the_call_is_not_relayed(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    await bedroom.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "offer", "payload": OFFER}
    )
    await kitchen.no_command("intercom_signal", wait=0.3)
    await office.no_command("intercom_signal", wait=0.1)


# --- a call started on the tablet's own screen -------------------------------


async def test_device_start_request_uses_the_same_path(hass_setup, make_tablet, bus):
    """`intercom_start_request` resolves the target and rings it (ADR 0025 d6)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")

    await kitchen.event(
        "event.intercom_start_request",
        {
            "call_id": CALL_ID,
            "mode": "call",
            "target_device_id": office.paired.ha_device_id,
        },
    )

    ring = await office.next_command("intercom_ring")
    assert ring["args"] == {
        "call_id": CALL_ID,
        "caller_device_id": kitchen.paired.device_id,
        "caller_name": "Kitchen Kiosk",
        "mode": "call",
    }
    started = await bus.wait(INTERCOM_STARTED)
    assert started.data["call_id"] == CALL_ID
    assert started.data["caller"] == kitchen.paired.ha_device_id


async def test_device_start_request_by_area_and_broadcast(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, office, "Kitchen")

    await kitchen.event(
        "event.intercom_start_request",
        {"call_id": CALL_ID, "mode": "broadcast", "target_area_id": "kitchen"},
    )
    ring = await office.next_command("intercom_ring")
    assert ring["args"]["call_id"] == CALL_ID
    assert ring["args"]["mode"] == "broadcast"

    # A broadcast that names no target reaches every listening tablet.
    await end_call(hass_setup, CALL_ID)
    await office.next_command("intercom_end")
    office2 = await make_tablet("Office Kiosk Two")
    await kitchen.event(
        "event.intercom_start_request",
        {"call_id": "01JC9Z6Q2H3W7K5N8T4V0BXDRB", "mode": "broadcast"},
    )
    for tablet in (office, office2):
        ring = await tablet.next_command("intercom_ring")
        assert ring["args"]["call_id"] == "01JC9Z6Q2H3W7K5N8T4V0BXDRB"


async def test_device_start_request_without_talk_is_refused(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    listen_only = await make_tablet("Kitchen Kiosk", caps=LISTEN_ONLY_CAPS)

    await listen_only.event(
        "event.intercom_start_request",
        {"call_id": CALL_ID, "mode": "call", "target_device_id": office.paired.ha_device_id},
    )
    await office.no_command("intercom_ring")
    # The tablet is in the call it minted, so it is told to stop (ADR 0037 d10).
    assert (await listen_only.next_command("intercom_end"))["args"] == {
        "call_id": CALL_ID,
        "reason": "command",
    }


async def test_device_start_request_with_an_unknown_target_is_ignored(hass_setup, make_tablet):
    kitchen = await make_tablet("Kitchen Kiosk")
    await kitchen.event(
        "event.intercom_start_request",
        {
            "call_id": CALL_ID,
            "mode": "call",
            "target_device_id": "9c1e4b7f2a3d4c5e8f9a0b1c2d3e4f50",
        },
    )
    await kitchen.no_command("intercom_ring")
    assert active_calls(hass_setup) == 0
    # The early return ends what the device started, too (ADR 0037 decision 10).
    assert (await kitchen.next_command("intercom_end"))["args"] == {
        "call_id": CALL_ID,
        "reason": "command",
    }


async def test_a_reused_call_id_is_refused_and_the_live_call_survives(hass_setup, make_tablet, bus):
    """A device reusing a live call's id must not orphan that call (P9.2 review)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    hall = await make_tablet("Hall Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    await bedroom.event(
        "event.intercom_start_request",
        {"call_id": call_id, "mode": "call", "target_device_id": hall.paired.ha_device_id},
    )
    await hall.no_command("intercom_ring")
    assert active_calls(hass_setup) == 1
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.intercom.calls[call_id].callees == [kitchen.paired.device_id]
    assert bus.silent(INTERCOM_ENDED)

    # The original call still ends from HA, on the id it was started with.
    await end_call(hass_setup, call_id)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"]["call_id"] == call_id
    assert (await bus.wait(INTERCOM_ENDED)).data["call_id"] == call_id


async def test_same_socket_reregister_mid_call_clears_the_stale_state(hass_setup, make_tablet, bus):
    """A re-register on an open subscription drains the deferred end (review)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    call_id = await answered_call(hass_setup, office, kitchen)

    await kitchen.reregister(INTERCOM_CAPS, kind="in_call")

    assert (await office.next_command("intercom_end"))["args"] == {
        "call_id": call_id,
        "reason": "disconnect",
    }
    assert (await kitchen.next_command("intercom_end"))["args"] == {
        "call_id": call_id,
        "reason": "disconnect",
    }
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.intercom._pending_reset == {}
    assert (await bus.wait(INTERCOM_ENDED)).data["reason"] == "disconnect"


async def test_intercom_call_by_area_reaches_a_tablet_with_no_live_entities(
    hass_setup, make_tablet
):
    """An area target resolves through the device registry (P9.2 decision 10)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    area_id = put_in_area(hass_setup, kitchen, "Kitchen")
    disable_entities(hass_setup, kitchen)
    await hass_setup.async_block_till_done()

    await hass_setup.services.async_call(
        DOMAIN,
        "intercom_call",
        {"caller": office.paired.ha_device_id, "area_id": area_id},
        blocking=True,
    )
    assert (await kitchen.next_command("intercom_ring"))["args"]["caller_device_id"] == (
        office.paired.device_id
    )

    # And the same target ends it.
    await hass_setup.services.async_call(
        DOMAIN, "intercom_end", {"area_id": area_id}, blocking=True
    )
    assert (await kitchen.next_command("intercom_end"))["args"]["reason"] == "command"
    assert active_calls(hass_setup) == 0


async def test_notify_by_area_reaches_a_tablet_with_no_live_entities(hass_setup, make_tablet):
    """The widened area resolution serves the other services too (review)."""
    kitchen = await make_tablet("Kitchen Kiosk")
    area_id = put_in_area(hass_setup, kitchen, "Kitchen")
    disable_entities(hass_setup, kitchen)
    await hass_setup.async_block_till_done()

    await hass_setup.services.async_call(
        DOMAIN,
        "notify",
        {"area_id": area_id, "message": "Dinner is ready"},
        blocking=True,
    )
    assert (await kitchen.next_command("notify"))["args"]["message"] == "Dinner is ready"


# --- the caller's own frame and per-peer signaling (P9.1b, ADR 0037) ---------


async def test_an_ha_call_prepares_the_caller_with_the_callee_and_the_id(
    hass_setup, make_tablet, bus
):
    """The caller learns the id and the peer it is calling (decision 2)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")

    await start_call(hass_setup, office, kitchen)

    ring = await kitchen.next_command("intercom_ring")
    call_id = ring["args"]["call_id"]
    start = await office.next_command("intercom_start")
    assert start["args"] == {
        "call_id": call_id,
        "mode": "call",
        "peers": [{"device_id": kitchen.paired.device_id, "name": "Kitchen Kiosk"}],
    }

    # The call it was given is the call that ends, on that id.
    await end_call(hass_setup, call_id)
    for tablet in (office, kitchen):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": call_id,
            "reason": "command",
        }
    assert (await bus.wait(INTERCOM_ENDED)).data["call_id"] == call_id


async def test_a_callers_early_offer_is_held_and_flushed_not_dropped(
    hass_setup, make_tablet, monkeypatch
):
    """Nothing is relayed before the caller has the call (decision 5)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    manager = hub_runtime.intercom
    release = asyncio.Event()
    original = manager._async_prepare_caller

    async def held(*args: Any, **kwargs: Any) -> None:
        await release.wait()
        await original(*args, **kwargs)

    monkeypatch.setattr(manager, "_async_prepare_caller", held)
    task = hass_setup.async_create_task(start_call(hass_setup, office, kitchen))
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]

    # The offer leaves before intercom_start has been answered: it waits.
    await office.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "offer", "payload": OFFER}
    )
    await kitchen.no_command("intercom_signal", wait=0.2)
    assert manager.calls[call_id].pending_signals

    release.set()
    await task
    relayed = await kitchen.next_command("intercom_signal")
    assert relayed["args"] == {"call_id": call_id, "kind": "offer", "payload": OFFER}
    assert manager.calls[call_id].ready is True


async def test_a_broadcast_routes_each_offer_to_one_receiver(hass_setup, make_tablet):
    """One connection per receiver, star-shaped at the caller (decisions 1 and 4)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")

    await start_call(hass_setup, office, None, mode="broadcast")
    kitchen_ring = await kitchen.next_command("intercom_ring")
    bedroom_ring = await bedroom.next_command("intercom_ring")
    call_id = kitchen_ring["args"]["call_id"]
    assert bedroom_ring["args"]["call_id"] == call_id

    start = await office.next_command("intercom_start")
    assert start["args"]["mode"] == "broadcast"
    assert {peer["device_id"] for peer in start["args"]["peers"]} == {
        kitchen.paired.device_id,
        bedroom.paired.device_id,
    }

    # Each receiver is offered its own connection, and only its own.
    await office.event(
        "event.intercom_signal",
        {
            "call_id": call_id,
            "kind": "offer",
            "payload": OFFER,
            "peer_device_id": kitchen.paired.device_id,
        },
    )
    relayed = await kitchen.next_command("intercom_signal")
    assert relayed["args"] == {"call_id": call_id, "kind": "offer", "payload": OFFER}
    await bedroom.no_command("intercom_signal", wait=0.2)

    # Each answer goes to the caller alone, labelled with its sender.
    await kitchen.event(
        "event.intercom_signal",
        {"call_id": call_id, "kind": "answer", "payload": ANSWER},
    )
    relayed = await office.next_command("intercom_signal")
    assert relayed["args"] == {
        "call_id": call_id,
        "kind": "answer",
        "payload": ANSWER,
        "peer_device_id": kitchen.paired.device_id,
    }
    # No receiver sees another receiver's signal.
    await bedroom.no_command("intercom_signal", wait=0.2)
    assert active_calls(hass_setup) == 1


async def test_a_two_device_call_carries_no_peer_device_id(hass_setup, make_tablet):
    """v1 frames stay v1 where one connection is enough (decision 8)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    await start_call(hass_setup, office, kitchen)
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await kitchen.event("event.intercom_answer", {"call_id": call_id})

    await office.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "offer", "payload": OFFER}
    )
    assert (await kitchen.next_command("intercom_signal"))["args"] == {
        "call_id": call_id,
        "kind": "offer",
        "payload": OFFER,
    }

    await kitchen.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "answer", "payload": ANSWER}
    )
    assert (await office.next_command("intercom_signal"))["args"] == {
        "call_id": call_id,
        "kind": "answer",
        "payload": ANSWER,
    }


async def test_a_signal_naming_an_unknown_peer_is_dropped_and_the_call_survives(
    hass_setup, make_tablet, bus
):
    """An event has no result to NACK: log it, drop it, keep the call (decision 4)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    await start_call(hass_setup, office, None, mode="broadcast")
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await bedroom.next_command("intercom_ring")
    await office.next_command("intercom_start")

    await office.event(
        "event.intercom_signal",
        {
            "call_id": call_id,
            "kind": "offer",
            "payload": OFFER,
            "peer_device_id": "1b2c3d4e-5f60-4a1b-8c2d-3e4f5a6b7c8d",
        },
    )
    await kitchen.no_command("intercom_signal", wait=0.2)
    await bedroom.no_command("intercom_signal", wait=0.1)
    assert active_calls(hass_setup) == 1
    assert bus.silent(INTERCOM_ENDED)

    # A named peer still relays: the call was never in doubt.
    await office.event(
        "event.intercom_signal",
        {
            "call_id": call_id,
            "kind": "offer",
            "payload": OFFER,
            "peer_device_id": bedroom.paired.device_id,
        },
    )
    assert (await bedroom.next_command("intercom_signal"))["args"]["payload"] == OFFER


async def test_a_broadcast_over_the_cap_is_refused_and_rings_nobody(hass_setup, make_tablet):
    """Nine receivers is more than one broadcast carries; never trim (decision 7)."""
    office = await make_tablet("Office Kiosk")
    receivers = [await make_tablet(f"Room {i}") for i in range(9)]

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, office, None, mode="broadcast")
    assert err.value.translation_key == "intercom_broadcast_too_many"
    for receiver in receivers:
        await receiver.no_command("intercom_ring", wait=0.05)
    await office.no_command("intercom_start")
    assert active_calls(hass_setup) == 0
    assert intercom.INTERCOM_BROADCAST_MAX_TARGETS == 8


async def test_an_ha_call_without_the_caller_capability_is_refused(hass_setup, make_tablet):
    """A caller that cannot be told about its own call is refused, not half-run."""
    older = await make_tablet("Office Kiosk", caps=NO_CALLER_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, older, kitchen)
    assert err.value.translation_key == "intercom_caller_unsupported"
    await kitchen.no_command("intercom_ring")
    await older.no_command("intercom_start")
    assert active_calls(hass_setup) == 0


async def test_a_device_started_call_by_that_caller_still_works(hass_setup, make_tablet):
    """The frame is not needed for a call the device started on its own screen."""
    older = await make_tablet("Office Kiosk", caps=NO_CALLER_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")

    await older.event(
        "event.intercom_start_request",
        {
            "call_id": CALL_ID,
            "mode": "call",
            "target_device_id": kitchen.paired.ha_device_id,
        },
    )
    assert (await kitchen.next_command("intercom_ring"))["args"]["call_id"] == CALL_ID
    await older.no_command("intercom_start")
    await older.event(
        "event.intercom_signal", {"call_id": CALL_ID, "kind": "offer", "payload": OFFER}
    )
    assert (await kitchen.next_command("intercom_signal"))["args"]["payload"] == OFFER


async def test_a_broadcast_receiver_without_the_caller_capability_hears_it(hass_setup, make_tablet):
    """Receivers need no new build: v1 offers, v1 answers (decision 8)."""
    office = await make_tablet("Office Kiosk")
    older = await make_tablet("Kitchen Kiosk", caps=NO_CALLER_CAPS)

    await start_call(hass_setup, office, older, mode="broadcast")
    call_id = (await older.next_command("intercom_ring"))["args"]["call_id"]
    start = await office.next_command("intercom_start")
    assert start["args"]["peers"] == [
        {"device_id": older.paired.device_id, "name": "Kitchen Kiosk"}
    ]

    await office.event(
        "event.intercom_signal", {"call_id": call_id, "kind": "offer", "payload": OFFER}
    )
    assert (await older.next_command("intercom_signal"))["args"] == {
        "call_id": call_id,
        "kind": "offer",
        "payload": OFFER,
    }


@pytest.mark.parametrize(
    ("nack", "key", "reason"),
    [
        ("busy", "intercom_busy", "busy"),
        ("failed", "intercom_start_failed", "error"),
    ],
)
async def test_a_refused_intercom_start_ends_the_call(
    hass_setup, make_tablet, bus, nack, key, reason
):
    """No caller side, no call: the callee is never left ringing (decision 9)."""
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    office.nack["intercom_start"] = (nack, f"{nack} from the tablet")

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, office, kitchen)
    assert err.value.translation_key == key

    end = await kitchen.next_command("intercom_end")
    assert end["args"]["reason"] == reason
    await kitchen.no_command("intercom_signal", wait=0.1)
    assert active_calls(hass_setup) == 0
    assert (await bus.wait(INTERCOM_ENDED)).data["reason"] == reason


async def test_a_device_broadcast_without_the_caller_capability_is_ended(hass_setup, make_tablet):
    """A refusal on the device-event path ends what the device started (decision 10)."""
    older = await make_tablet("Office Kiosk", caps=NO_CALLER_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")

    await older.event("event.intercom_start_request", {"call_id": CALL_ID, "mode": "broadcast"})
    end = await older.next_command("intercom_end")
    assert end["args"] == {"call_id": CALL_ID, "reason": "command"}
    await kitchen.no_command("intercom_ring")
    assert active_calls(hass_setup) == 0


async def test_a_one_receiver_broadcast_needs_the_caller_capability(hass_setup, make_tablet):
    """One resolved receiver is a target list, not a two-device call (decision 8)."""
    older = await make_tablet("Office Kiosk", caps=NO_CALLER_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, older, "Office")
    put_in_area(hass_setup, kitchen, "Kitchen")

    await older.event(
        "event.intercom_start_request",
        {"call_id": CALL_ID, "mode": "broadcast", "target_area_id": "kitchen"},
    )
    assert (await older.next_command("intercom_end"))["args"] == {
        "call_id": CALL_ID,
        "reason": "command",
    }
    await kitchen.no_command("intercom_ring")

    # The same one-receiver broadcast runs when the caller declares it.
    office = await make_tablet("Office Two")
    put_in_area(hass_setup, office, "Office")
    await office.event(
        "event.intercom_start_request",
        {"call_id": "01JC9Z6Q2H3W7K5N8T4V0BXDRC", "mode": "broadcast", "target_area_id": "kitchen"},
    )
    ring = await kitchen.next_command("intercom_ring")
    assert ring["args"]["mode"] == "broadcast"
    start = await office.next_command("intercom_start")
    assert start["args"]["peers"] == [
        {"device_id": kitchen.paired.device_id, "name": "Kitchen Kiosk"}
    ]


async def test_an_oversize_device_broadcast_is_ended(hass_setup, make_tablet, monkeypatch):
    """The cap is the same one the schema and the service path use."""
    monkeypatch.setattr(intercom, "INTERCOM_BROADCAST_MAX_TARGETS", 1)
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")

    await office.event("event.intercom_start_request", {"call_id": CALL_ID, "mode": "broadcast"})
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    await kitchen.no_command("intercom_ring", wait=0.1)
    await bedroom.no_command("intercom_ring", wait=0.1)
    assert active_calls(hass_setup) == 0


async def test_a_device_call_into_a_room_of_two_is_ended(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, kitchen, "Kitchen")
    put_in_area(hass_setup, bedroom, "Kitchen")

    await office.event(
        "event.intercom_start_request",
        {"call_id": CALL_ID, "mode": "call", "target_area_id": "kitchen"},
    )
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    await kitchen.no_command("intercom_ring")
    await bedroom.no_command("intercom_ring")
    assert active_calls(hass_setup) == 0


async def test_a_device_call_into_its_own_area_is_ended(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, kitchen, "Office")

    await office.event(
        "event.intercom_start_request",
        {
            "call_id": CALL_ID,
            "mode": "call",
            "target_device_id": kitchen.paired.ha_device_id,
        },
    )
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    await kitchen.no_command("intercom_ring")
    assert active_calls(hass_setup) == 0


async def test_a_device_call_to_a_busy_tablet_is_ended(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    await start_call(hass_setup, bedroom, kitchen)
    await kitchen.next_command("intercom_ring")

    await office.event(
        "event.intercom_start_request",
        {
            "call_id": CALL_ID,
            "mode": "call",
            "target_device_id": kitchen.paired.ha_device_id,
        },
    )
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    await kitchen.no_command("intercom_ring", wait=0.1)
    # The call that was already up is untouched.
    assert active_calls(hass_setup) == 1


async def test_a_device_call_with_a_reused_id_is_ended(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    hall = await make_tablet("Hall Kiosk")
    await start_call(hass_setup, bedroom, hall)
    call_id = (await hall.next_command("intercom_ring"))["args"]["call_id"]

    await office.event(
        "event.intercom_start_request",
        {
            "call_id": call_id,
            "mode": "call",
            "target_device_id": kitchen.paired.ha_device_id,
        },
    )
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    await kitchen.no_command("intercom_ring", wait=0.1)
    assert active_calls(hass_setup) == 1


async def test_a_device_call_to_a_tablet_that_cannot_hear_is_ended(hass_setup, make_tablet):
    office = await make_tablet("Office Kiosk")
    plain = await make_tablet("Plain Tablet", caps=NO_INTERCOM_CAPS)

    await office.event(
        "event.intercom_start_request",
        {"call_id": CALL_ID, "mode": "call", "target_device_id": plain.paired.ha_device_id},
    )
    assert (await office.next_command("intercom_end"))["args"]["reason"] == "command"
    assert plain.commands == []
    assert active_calls(hass_setup) == 0


async def test_a_broadcast_caller_with_talk_and_no_listen_is_refused(hass_setup, make_tablet):
    """A caller with no return path would be a silent broadcast (decision 2)."""
    talk_only = await make_tablet(
        "Office Kiosk", caps=[*BASE_CAPS, "intercom_talk", "intercom_caller"]
    )
    kitchen = await make_tablet("Kitchen Kiosk")

    with pytest.raises(ServiceValidationError) as err:
        await start_call(hass_setup, talk_only, kitchen, mode="broadcast")
    assert err.value.translation_key == "intercom_caller_cannot_call"
    await kitchen.no_command("intercom_ring")
    await talk_only.no_command("intercom_start")
    assert active_calls(hass_setup) == 0


async def test_the_call_limit_ends_a_broadcast_for_every_receiver(
    hass_setup, make_tablet, bus, monkeypatch
):
    """Guard 2 on the star: one call, one limit, one end (decision 6)."""
    monkeypatch.setattr(intercom, "INTERCOM_CALL_LIMIT_S", 0.3)
    office = await make_tablet("Office Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    bedroom = await make_tablet("Bedroom Kiosk")
    await start_call(hass_setup, office, None, mode="broadcast")
    call_id = (await kitchen.next_command("intercom_ring"))["args"]["call_id"]
    await bedroom.next_command("intercom_ring")

    await advance(hass_setup)
    for tablet in (office, kitchen, bedroom):
        assert (await tablet.next_command("intercom_end"))["args"] == {
            "call_id": call_id,
            "reason": "limit",
        }
    assert (await bus.wait(INTERCOM_ENDED)).data["reason"] == "limit"


# --- the sensor --------------------------------------------------------------


async def test_intercom_sensor_follows_the_device(hass_setup, make_tablet):
    kitchen = await make_tablet("Kitchen Kiosk")
    plain = await make_tablet("Plain Tablet", caps=NO_INTERCOM_CAPS)

    eid = entity_id(hass_setup, "sensor", kitchen.paired, "intercom_state")
    assert eid is not None
    await until(lambda: hass_setup.states.get(eid) is not None)
    await until(lambda: hass_setup.states.get(eid).state == "idle")
    await kitchen.state({"intercom_state": "ringing"})
    await until(lambda: hass_setup.states.get(eid).state == "ringing")
    await kitchen.state({"intercom_state": "in_call"})
    await until(lambda: hass_setup.states.get(eid).state == "in_call")

    # A tablet that cannot hear a call has no intercom sensor at all.
    assert entity_id(hass_setup, "sensor", plain.paired, "intercom_state") is None


async def test_intercom_entities_follow_a_capability_gained_later(hass_setup, make_tablet):
    """A tablet that starts declaring the capability gets the sensor then."""
    kitchen = await make_tablet("Kitchen Kiosk", caps=NO_INTERCOM_CAPS)
    assert entity_id(hass_setup, "sensor", kitchen.paired, "intercom_state") is None

    await kitchen.reregister(INTERCOM_CAPS)
    await until(
        lambda: entity_id(hass_setup, "sensor", kitchen.paired, "intercom_state") is not None
    )


# --- P9.6: calls started by voice (ADR 0036) --------------------------------
#
# The caller is the tablet that heard the request (`device_id` of the assist
# satellite's turn), so every request here goes through the real conversation
# agent with that device id. HA's own built-in intents are in the same agent,
# which is why the negative checks matter as much as the positive ones.

# A tablet that can hear a request (`voice`, the assist satellite's capability,
# P7.3) and capture a call (`intercom_talk`): both, or the intents do not exist.
VOICE_CAPS: list[str] = [*INTERCOM_CAPS, "voice"]
# Hears but cannot capture: it may be called, it cannot start one.
LISTEN_VOICE_CAPS: list[str] = [*LISTEN_ONLY_CAPS, "voice"]


def sentences_path(hass: HomeAssistant) -> Path:
    """Where the integration writes its sentence set (HA's documented location)."""
    return Path(hass.config.path("custom_sentences", "en", "tabula_intercom.yaml"))


def registered_intents(hass: HomeAssistant) -> set[str]:
    """The Tabula intents registered right now."""
    return {
        handler.intent_type
        for handler in intent_helper.async_get(hass)
        if handler.intent_type in (INTENT_CALL, INTENT_BROADCAST)
    }


async def wait_for_caps(hass: HomeAssistant, tablet: Tablet, caps: list[str]) -> None:
    """Wait until the hub has taken the tablet's latest register frame.

    `reregister` reaches Home Assistant over the socket, so the record (and the
    capability signal the gate follows) can still be the old one when the send
    returns.
    """
    hub_runtime = TabulaHub.get(hass)
    assert hub_runtime is not None
    await until(lambda: hub_runtime.devices[tablet.paired.device_id].capabilities == caps)


async def say(
    hass: HomeAssistant, text: str, device_id: str | None = None
) -> conversation.ConversationResult:
    """Speak a sentence through the default conversation agent, as a satellite does."""
    return await conversation.async_converse(
        hass,
        text,
        conversation_id=None,
        context=Context(),
        language="en",
        device_id=device_id,
    )


def speech(result: conversation.ConversationResult) -> str | None:
    """What the assistant says back."""
    return result.response.speech.get("plain", {}).get("speech")


async def test_intents_exist_only_while_a_tablet_can_hear(hass_setup, make_tablet):
    """The gate: no tablet that can hear and capture, no intents and no sentences."""
    assert registered_intents(hass_setup) == set()
    assert not sentences_path(hass_setup).exists()

    # Hearing is not enough: a listen-only tablet cannot start a call.
    await make_tablet("Kitchen Kiosk", caps=LISTEN_VOICE_CAPS)
    await hass_setup.async_block_till_done()
    assert registered_intents(hass_setup) == set()
    assert not sentences_path(hass_setup).exists()

    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    await hass_setup.async_block_till_done()
    assert registered_intents(hass_setup) == {INTENT_CALL, INTENT_BROADCAST}
    assert sentences_path(hass_setup).exists()

    # A tablet that stops declaring the capability closes the gate again.
    await office.reregister(NO_INTERCOM_CAPS)
    await wait_for_caps(hass_setup, office, NO_INTERCOM_CAPS)
    await hass_setup.async_block_till_done()
    assert registered_intents(hass_setup) == set()
    assert not sentences_path(hass_setup).exists()


async def test_sentences_match_the_way_ha_reads_them(hass_setup, make_tablet):
    """The file on disk is a sentence set hassil matches, and it leaves HA's alone."""
    await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    document = yaml.safe_load(sentences_path(hass_setup).read_text(encoding="utf-8"))
    assert document["language"] == "en"
    intents = hassil.Intents.from_dict(document)

    call = hassil.recognize("call the kitchen", intents)
    assert call is not None
    assert call.intent.name == INTENT_CALL
    assert {name: entity.value for name, entity in call.entities.items()} == {
        "target": "the kitchen"
    }

    # "everyone" is a broadcast, not a room called everyone: the broadcast
    # sentences come first, because hassil stops at the first intent that matches.
    broadcast = hassil.recognize("call everyone", intents)
    assert broadcast is not None
    assert broadcast.intent.name == INTENT_BROADCAST

    # An announcement is HA's own HassBroadcast intent, so the words after
    # "broadcast" are left to it (ADR 0036 decision 3).
    assert hassil.recognize("broadcast the time", intents) is None


async def test_voice_calls_a_room_and_rings_it(hass_setup, make_tablet):
    """``call the kitchen`` rings the room's tablet, from the tablet that spoke."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, kitchen, "Kitchen")

    result = await say(hass_setup, "call the kitchen", office.paired.ha_device_id)

    assert speech(result) == "Calling Kitchen."
    ring = await kitchen.next_command("intercom_ring")
    assert ring["args"]["caller_device_id"] == office.paired.device_id
    assert ring["args"]["mode"] == "call"
    await office.no_command("intercom_ring", wait=0.1)
    assert active_calls(hass_setup) == 1


async def test_voice_calls_a_tablet_by_name(hass_setup, make_tablet):
    """A tablet's own name works without an area."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")

    result = await say(hass_setup, "call Kitchen Kiosk", office.paired.ha_device_id)

    assert speech(result) == "Calling Kitchen Kiosk."
    assert (await kitchen.next_command("intercom_ring"))["args"]["mode"] == "call"


async def test_voice_unknown_name_is_answered_not_guessed(hass_setup, make_tablet):
    """An unknown room is said back, and no call is made."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, kitchen, "Kitchen")

    result = await say(hass_setup, "call the attic", office.paired.ha_device_id)

    assert speech(result) == "I could not find the attic."
    await kitchen.no_command("intercom_ring", wait=0.1)
    assert active_calls(hass_setup) == 0


async def test_voice_needs_a_tablet_to_start_from(hass_setup, make_tablet):
    """A request from a device that is not a paired tablet starts nothing."""
    await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, (await make_tablet("Hall Kiosk")), "Hall")

    result = await say(hass_setup, "call the kitchen")

    assert speech(result) == "This device cannot start an intercom call."
    assert active_calls(hass_setup) == 0


async def test_voice_calls_a_person_from_the_options_map(hass_setup, make_tablet):
    """A name the household wrote resolves to its area, and is said back by name."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, kitchen, "Kitchen")
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    hass_setup.config_entries.async_update_entry(
        hub_runtime.entry,
        options={
            **hub_runtime.entry.options,
            INTERCOM_PERSONS: [{"name": "Thomas", "area": "Kitchen"}],
        },
    )

    result = await say(hass_setup, "call Thomas", office.paired.ha_device_id)

    assert speech(result) == "Calling Thomas."
    assert (await kitchen.next_command("intercom_ring"))["args"]["mode"] == "call"


async def test_voice_calls_everyone_as_a_broadcast(hass_setup, make_tablet):
    """``call everyone`` is live one-way audio to every other tablet that can hear."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    study = await make_tablet("Study Kiosk")
    for tablet, area in ((office, "Office"), (kitchen, "Kitchen"), (study, "Study")):
        put_in_area(hass_setup, tablet, area)

    result = await say(hass_setup, "call everyone", office.paired.ha_device_id)

    assert speech(result) == "Broadcasting."
    for tablet in (kitchen, study):
        assert (await tablet.next_command("intercom_ring"))["args"]["mode"] == "broadcast"
    await office.no_command("intercom_ring", wait=0.1)


async def test_voice_a_room_of_two_tablets_is_refused(hass_setup, make_tablet):
    """Two tablets in one room have no single answer, so the request is spoken back."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    first = await make_tablet("Kitchen Wall")
    second = await make_tablet("Kitchen Counter")
    put_in_area(hass_setup, first, "Kitchen")
    put_in_area(hass_setup, second, "Kitchen")

    result = await say(hass_setup, "call the kitchen", office.paired.ha_device_id)

    assert speech(result) == "That is more than one tablet: Kitchen Wall, Kitchen Counter."
    assert active_calls(hass_setup) == 0


async def test_voice_a_call_into_the_same_room_is_refused(hass_setup, make_tablet):
    """Two speakers in one room feed back, so the refusal is spoken, not silent."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    desk = await make_tablet("Desk Kiosk")
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, office, "Office")
    put_in_area(hass_setup, desk, "Office")
    put_in_area(hass_setup, kitchen, "Kitchen")

    result = await say(hass_setup, "call Desk Kiosk", office.paired.ha_device_id)

    assert speech(result) == "Desk Kiosk is in the same room, so a call would echo."
    await desk.no_command("intercom_ring", wait=0.1)
    await kitchen.no_command("intercom_ring", wait=0.1)
    assert active_calls(hass_setup) == 0


async def test_voice_a_busy_tablet_is_said_back(hass_setup, make_tablet):
    """A tablet that refuses the ring because it is in a call is named in the answer."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, kitchen, "Kitchen")
    kitchen.nack["intercom_ring"] = ("busy", "already in a call")

    result = await say(hass_setup, "call the kitchen", office.paired.ha_device_id)

    assert speech(result) == "Kitchen Kiosk is already in a call."
    assert active_calls(hass_setup) == 0


async def test_voice_picks_up_sentences_after_the_agent_first_spoke(hass_setup, make_tablet):
    """A hub that gains a voice tablet after the agent cached `en` still works.

    The default agent parses the sentence set once per language; the sentence
    file appears later, so the integration asks the agent to drop that cache.
    The two requests are different sentences on purpose: the agent also caches
    a fuzzy match per sentence and turn source, and only a new sentence makes
    the agent look at the set again.
    """
    office = await make_tablet("Office Kiosk", caps=NO_INTERCOM_CAPS)
    kitchen = await make_tablet("Kitchen Kiosk")
    put_in_area(hass_setup, kitchen, "Kitchen")
    # Prime the agent with the intents it had before the gate opened.
    primed = await say(hass_setup, "call the garden", office.paired.ha_device_id)
    assert speech(primed) != "I could not find the garden."

    await office.reregister(VOICE_CAPS)
    await wait_for_caps(hass_setup, office, VOICE_CAPS)
    await hass_setup.async_block_till_done()
    assert registered_intents(hass_setup) == {INTENT_CALL, INTENT_BROADCAST}

    result = await say(hass_setup, "call the kitchen", office.paired.ha_device_id)
    assert speech(result) == "Calling Kitchen."


async def test_voice_unload_removes_the_intents_and_the_sentences(hass_setup, make_tablet, hub):
    """An unloaded hub leaves nothing behind for the next request to match."""
    office = await make_tablet("Office Kiosk", caps=VOICE_CAPS)
    put_in_area(hass_setup, office, "Office")
    assert sentences_path(hass_setup).exists()

    assert await hass_setup.config_entries.async_unload(hub.entry_id)
    await hass_setup.async_block_till_done()

    assert registered_intents(hass_setup) == set()
    assert not sentences_path(hass_setup).exists()


# --- the person map ---------------------------------------------------------


def test_person_map_is_a_list_of_names_with_one_target_each():
    """The editor's YAML becomes a list of names, each with one area or tablet."""
    assert parse_persons("") == []
    assert parse_persons("[]") == []
    assert parse_persons(
        "- name: Thomas\n  area: Kitchen\n- name: Study\n  device: Study tablet\n"
    ) == [
        {"name": "Thomas", "area": "Kitchen"},
        {"name": "Study", "device": "Study tablet"},
    ]
    # A list from a service call passes through the same checks.
    assert parse_persons([{"name": "Thomas", "area": "Kitchen"}]) == [
        {"name": "Thomas", "area": "Kitchen"}
    ]


@pytest.mark.parametrize(
    "text",
    [
        "not: a list\n",
        "- area: Kitchen\n",
        "- name: Thomas\n",
        "- name: Thomas\n  area: Kitchen\n  device: Study\n",
        "- name: Thomas\n  area: Kitchen\n- name: Thomas\n  area: Study\n",
    ],
)
def test_person_map_refuses_what_it_cannot_use(text):
    """Every unusable entry is refused, with the reason, not stored and ignored."""
    with pytest.raises(PersonMapError):
        parse_persons(text)


def test_person_map_round_trips_through_the_editor():
    """What the editor shows is what it will accept back."""
    persons = [{"name": "Thomas", "area": "Kitchen"}]
    assert parse_persons(persons_to_text(persons)) == persons


async def test_options_flow_edits_the_person_map(hass_setup, server, hub, session, paired):
    """The names are an option of the hub entry, edited as YAML."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    assert flow["type"] == "menu"
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"next_step_id": "persons"}
    )
    assert flow["type"] == "form"
    assert flow["step_id"] == "persons"
    assert flow["data_schema"]({"persons": "- name: Thomas\n  area: Kitchen\n"}) == {
        "persons": "- name: Thomas\n  area: Kitchen\n"
    }

    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"persons": "- name: Thomas\n  area: Kitchen\n"}
    )
    assert flow["type"] == "create_entry", flow
    await hass_setup.async_block_till_done()
    assert hub.options[INTERCOM_PERSONS] == [{"name": "Thomas", "area": "Kitchen"}]


async def test_options_flow_refuses_an_unusable_person_map(
    hass_setup, server, hub, session, paired
):
    """A bad list reshows the editor with the error, and stores nothing."""
    flow = await hass_setup.config_entries.options.async_init(hub.entry_id)
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"next_step_id": "persons"}
    )
    flow = await hass_setup.config_entries.options.async_configure(
        flow["flow_id"], {"persons": "- name: Thomas\n"}
    )
    assert flow["type"] == "form"
    assert flow["errors"] == {"persons": "invalid_persons"}
    # The editor says what to fix, not just that something is wrong.
    assert "give one of area or device" in flow["description_placeholders"]["problems"]
    assert INTERCOM_PERSONS not in hub.options
