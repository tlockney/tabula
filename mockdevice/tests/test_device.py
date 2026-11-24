from __future__ import annotations

import random

import pytest
from mockdevice.client import Backoff
from mockdevice.constants import (
    INTERCOM_CALL_LIMIT_S,
    INTERCOM_RING_LIMIT_S,
    INTERCOM_SIGNAL_MAX_BYTES,
)
from mockdevice.device import (
    Behavior,
    CommandError,
    MockDevice,
    page_path,
    redact,
    same_origin,
)


def settings(revision: int) -> dict:
    return {"revision": revision, "schema_version": 1, "settings": {"x": revision}}


async def test_stale_revision_only_within_a_connection():
    device = MockDevice(settings_revision=9)
    device.begin_connection()
    # HA is authoritative: a lower revision than stored is applied on a new connection.
    assert await device.handle("set_settings", settings(4)) == {"revision": 4}
    with pytest.raises(CommandError) as err:
        await device.handle("set_settings", settings(3))
    assert err.value.code == "stale_revision"
    device.begin_connection()
    assert await device.handle("set_settings", settings(3)) == {"revision": 3}


async def test_screen_brightness_turns_off_adaptive():
    device = MockDevice()
    device.state["adaptive_brightness"] = True
    deltas = []
    device.on_change = deltas.append
    await device.handle("screen", {"brightness": 30})
    assert deltas == [{"brightness": 30, "adaptive_brightness": False}]


async def test_notify_same_tag_replaces():
    device = MockDevice()
    events = []
    device.on_event = lambda e, d: events.append((e, d))
    await device.handle("notify", {"message": "a", "tag": "door"})
    await device.handle("notify", {"message": "b", "tag": "door"})
    assert [n["message"] for n in device.notifications] == ["b"]
    assert events == [
        ("notification_shown", {"tag": "door"}),
        ("notification_dismissed", {"tag": "door", "reason": "replaced"}),
        ("notification_shown", {"tag": "door"}),
    ]


async def test_navigate_strips_query_and_stays_on_origin():
    device = MockDevice(base_url="http://ha.local:8123")
    events = []
    device.on_event = lambda e, d: events.append((e, d))
    await device.handle("navigate", {"path": "/lovelace/kitchen?edit=1#x"})
    assert device.state["current_page"] == "/lovelace/kitchen"
    assert events == [("page_changed", {"path": "/lovelace/kitchen"})]


@pytest.mark.parametrize(
    ("path", "ok"),
    [("/lovelace/0", True), ("//evil.example/x", False), ("/\\evil", True), ("/a/../b", True)],
)
def test_same_origin(path, ok):
    assert same_origin("http://ha.local:8123", path) is ok


def test_page_path():
    assert page_path("/a/b?c=1#d") == "/a/b"


@pytest.mark.parametrize(
    ("line", "secret"),
    [
        ("GET /api/tts_proxy/abc?authSig=SECRET1", "SECRET1"),
        ("url http://ha/x?access_token=SECRET2&y=1", "SECRET2"),
        ("Authorization: Bearer SECRET3", "SECRET3"),
        ('{"refresh_token": "SECRET4"}', "SECRET4"),
    ],
)
def test_redact(line, secret):
    assert secret not in redact(line)


async def test_media_stop_emits_finished():
    device = MockDevice()
    events = []
    device.on_event = lambda e, d: events.append((e, d))
    await device.handle("play_media", {"url": "/api/tts_proxy/x.mp3", "media_type": "music"})
    await device.handle("media_control", {"action": "stop"})
    assert events == [("media_finished", {"reason": "stopped"})]
    assert device.state["media_state"] == "idle"


async def test_illuminance_is_a_state_key_covered_by_its_capability():
    """The mock "has a light sensor": the key and capability exist (P3.16, F-HA-06)."""
    device = MockDevice()
    deltas = []
    device.on_change = deltas.append
    assert "illuminance" in device.capabilities
    assert device.state["illuminance"] == 128.5
    assert device.update(illuminance=64.0) == {"illuminance": 64.0}
    assert deltas == [{"illuminance": 64.0}]
    # a steady reading produces no delta (HA's recorder is not flooded)
    assert device.update(illuminance=64.0) == {}


async def test_missing_capability_and_failure_mode():
    device = MockDevice(capabilities=["screen"], behavior=Behavior(fail={"screen"}))
    with pytest.raises(CommandError) as err:
        await device.handle("notify", {"message": "x"})
    assert err.value.code == "unsupported_command"
    with pytest.raises(CommandError) as err:
        await device.handle("screen", {"on": False})
    assert err.value.code == "failed"


# --- start target (P3.15, F-DSH-02, F-DSH-15) --------------------------------


def with_settings(device: MockDevice, dashboard: dict) -> None:
    device.settings = {"dashboard": dashboard}


async def test_go_home_loads_the_start_path():
    device = MockDevice(base_url="http://ha.local:8123")
    events = []
    device.on_event = lambda e, d: events.append((e, d))
    with_settings(device, {"start_path": "/lovelace/kitchen", "return_home_after": 0})
    await device.handle("go_home", {})
    assert device.state["current_page"] == "/lovelace/kitchen"
    assert events == [("page_changed", {"path": "/lovelace/kitchen"})]


async def test_go_home_without_a_start_path_is_the_default_page():
    device = MockDevice(base_url="http://ha.local:8123")
    with_settings(device, {})
    await device.handle("go_home", {})
    assert device.state["current_page"] == "/"


async def test_go_home_refuses_an_off_origin_start_path():
    device = MockDevice(base_url="http://ha.local:8123")
    with_settings(device, {"start_path": "//evil.example/x"})
    with pytest.raises(CommandError) as err:
        await device.handle("go_home", {})
    assert err.value.code == "invalid_args"


async def test_registration_lands_on_the_start_path():
    device = MockDevice(base_url="http://ha.local:8123")
    with_settings(device, {"start_path": "/lovelace/kitchen#popup"})
    device.state["current_page"] = "/lovelace/0"
    device.begin_connection()
    assert device.state["current_page"] == "/lovelace/kitchen", "the fragment is not current_page"
    # an off-origin path falls back: the page stays where it was
    with_settings(device, {"start_path": "//evil.example/x"})
    device.state["current_page"] = "/lovelace/0"
    device.begin_connection()
    assert device.state["current_page"] == "/lovelace/0"


def test_idle_return_fires_after_the_delay_and_a_touch_resets_it():
    clock = {"now": 100.0}
    device = MockDevice(base_url="http://ha.local:8123", clock=lambda: clock["now"])
    with_settings(device, {"start_path": "/lovelace/kitchen", "return_home_after": 60})
    device.state["current_page"] = "/lovelace/0"
    events = []
    device.on_event = lambda e, d: events.append((e, d))

    assert device.tick() is False, "before the delay"
    clock["now"] += 59
    assert device.tick() is False
    device.touch()  # a touch resets the countdown
    clock["now"] += 30
    assert device.tick() is False, "the touch moved the deadline"
    clock["now"] += 30
    assert device.tick() is True, "returns to the start target"
    assert device.state["current_page"] == "/lovelace/kitchen"
    assert events == [("page_changed", {"path": "/lovelace/kitchen"})]

    # the countdown starts over once it fired
    clock["now"] += 1
    assert device.tick() is False


def test_idle_return_runs_behind_a_screensaver_and_skips_when_already_home():
    clock = {"now": 0.0}
    device = MockDevice(base_url="http://ha.local:8123", clock=lambda: clock["now"])
    with_settings(device, {"start_path": "/lovelace/kitchen", "return_home_after": 60})
    device.state["screensaver_active"] = True  # the countdown does not care (F-OPT)
    clock["now"] += 60
    assert device.tick() is True

    device.state["current_page"] = "/lovelace/kitchen"
    device.state["screensaver_active"] = False
    clock["now"] += 60
    assert device.tick() is False, "already on the start target: no navigation"
    # no configured start target: the return stays inert. The frontend
    # redirects "/" client-side to its default panel, which the page can
    # never rest on, so returning would reload once per delay period
    # forever (review round 3, ADR 0021 item 7) - empty and "/" are the
    # same case.
    with_settings(device, {"start_path": "", "return_home_after": 60})
    device.state["current_page"] = "/config"
    clock["now"] += 60
    assert device.tick() is False
    with_settings(device, {"start_path": "/", "return_home_after": 60})
    clock["now"] += 60
    assert device.tick() is False
    assert device.state["current_page"] == "/config"


async def test_a_changed_return_delay_restarts_the_mock_countdown():
    clock = {"now": 0.0}
    device = MockDevice(base_url="http://ha.local:8123", clock=lambda: clock["now"])
    device.begin_connection()
    settings = {"revision": 1, "schema_version": 2, "settings": {}}
    await device.handle(
        "set_settings",
        {
            **settings,
            "settings": {"dashboard": {"start_path": "/lovelace/kitchen", "return_home_after": 60}},
        },
    )
    clock["now"] += 50
    assert device.tick() is False
    # a changed delay restarts the countdown (ADR 0021 item 5); an unchanged
    # one (same document resent) does not
    await device.handle(
        "set_settings",
        {
            **settings,
            "revision": 2,
            "settings": {"dashboard": {"start_path": "/lovelace/kitchen", "return_home_after": 90}},
        },
    )
    clock["now"] += 60
    assert device.tick() is False, "the new delay counts from its own apply"
    clock["now"] += 30
    assert device.tick() is True


def voice(revision: int, enabled: bool) -> dict:
    return {
        "revision": revision,
        "schema_version": 3,
        "settings": {"voice": {"wake_word_enabled": enabled}},
    }


async def test_wake_word_listening_follows_the_setting_not_the_capability():
    """P7.1: wake_word is always declared; the switch drives wake_word_active.

    The app cannot re-register mid-session, so a capability that tracked the
    switch would only reach HA on the next connection, and a device whose
    grant was withdrawn could never say it is not listening (ADR 0030 3, 5).
    """
    device = MockDevice()
    assert "wake_word" in device.capabilities
    assert device.state.get("wake_word_active") is False

    await device.handle("set_settings", voice(1, True))
    assert "wake_word" in device.capabilities
    assert device.state["wake_word_active"] is True

    await device.handle("set_settings", voice(2, False))
    assert "wake_word" in device.capabilities
    assert device.state["wake_word_active"] is False


async def test_detection_emits_the_event_then_the_timestamp():
    """The event frame leads; the state delta follows with last_wake_word (4.7)."""
    device = MockDevice()
    events: list[tuple[str, dict]] = []
    deltas: list[dict] = []
    device.on_event = lambda name, data: events.append((name, data))
    device.on_change = deltas.append
    await device.handle("set_settings", voice(1, True))

    device.detect_wake_word("okay_nabu", 231)
    assert events == [("wake_word_detected", {"phrase": "okay_nabu", "probability": 231})]
    assert list(deltas[-1]) == ["last_wake_word"]
    assert deltas[-1]["last_wake_word"].endswith("Z")


def test_detection_is_impossible_while_the_setting_is_off():
    device = MockDevice()
    with pytest.raises(CommandError) as err:
        device.detect_wake_word()
    assert err.value.code == "unsupported_command"


# --- intercom (P9.1, ADR 0025) -------------------------------------------------

CALL = "01JC9Z6Q2H3W7K5N8T4V0BXDRA"
CALLER = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b"


def ring(mode: str = "call", call_id: str = CALL) -> dict:
    return {
        "call_id": call_id,
        "caller_device_id": CALLER,
        "caller_name": "Office Kiosk",
        "mode": mode,
    }


class Clock:
    """An injectable monotonic clock for the intercom deadlines."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def intercom_device(**kw) -> tuple[MockDevice, Clock, list[tuple[str, dict]]]:
    clock = Clock()
    device = MockDevice(clock=clock, **kw)
    events: list[tuple[str, dict]] = []
    device.on_event = lambda name, data: events.append((name, data))
    return device, clock, events


async def test_ring_answer_and_the_hard_call_limit():
    """A call rings, is answered, and ends by itself at INTERCOM_CALL_LIMIT_S (guard 2)."""
    device, clock, events = intercom_device()
    await device.handle("intercom_ring", ring())
    assert device.state["intercom_state"] == "ringing"
    assert device.intercom_call["caller_name"] == "Office Kiosk"

    device.answer_intercom_call()
    assert device.state["intercom_state"] == "in_call"
    assert events == [("intercom_answer", {"call_id": CALL})]

    clock.now += INTERCOM_CALL_LIMIT_S - 1
    assert device.check_intercom_timers() is None
    clock.now += 1
    assert device.check_intercom_timers() == "limit"
    assert device.state["intercom_state"] == "idle"
    assert events[-1] == ("intercom_ended", {"call_id": CALL, "reason": "limit"})


async def test_ring_limit_ends_an_unanswered_call():
    """45 s of ringing and the call is over, so a call into an empty room ends (guard 2)."""
    device, clock, events = intercom_device()
    await device.handle("intercom_ring", ring())
    clock.now += INTERCOM_RING_LIMIT_S - 1
    assert device.check_intercom_timers() is None
    clock.now += 1
    assert device.check_intercom_timers() == "timeout"
    assert device.state["intercom_state"] == "idle"
    assert events == [("intercom_ended", {"call_id": CALL, "reason": "timeout"})]


async def test_decline_returns_to_idle_without_ending():
    device, _, events = intercom_device()
    await device.handle("intercom_ring", ring())
    device.decline_intercom_call()
    assert device.state["intercom_state"] == "idle"
    assert device.intercom_call is None
    assert events == [("intercom_decline", {"call_id": CALL})]


async def test_answering_needs_a_ringing_call():
    device, _, _ = intercom_device()
    with pytest.raises(CommandError) as err:
        device.answer_intercom_call()
    assert err.value.code == "invalid_args"


async def test_a_second_ring_is_busy():
    device, _, _ = intercom_device()
    await device.handle("intercom_ring", ring())
    with pytest.raises(CommandError) as err:
        await device.handle("intercom_ring", ring(call_id="01JC9Z6Q2H3W7K5N8T4V0BXDRA2"))
    assert err.value.code == "busy"


async def test_broadcast_plays_at_once_and_is_not_answered():
    """One-way and live (ADR 0025 decision 2): the receiver is in the call, mic closed."""
    device, _, events = intercom_device()
    await device.handle("intercom_ring", ring(mode="broadcast"))
    assert device.state["intercom_state"] == "in_call"
    assert events == []
    with pytest.raises(CommandError):
        device.answer_intercom_call()


async def test_intercom_end_only_acts_on_that_call():
    device, _, events = intercom_device()
    await device.handle("intercom_ring", ring())
    await device.handle("intercom_end", {"call_id": "another-call-0001", "reason": "command"})
    assert device.state["intercom_state"] == "ringing"

    await device.handle("intercom_end", {"call_id": CALL, "reason": "command"})
    assert device.state["intercom_state"] == "idle"
    assert events == [("intercom_ended", {"call_id": CALL, "reason": "command"})]


async def test_a_reconnect_drops_the_call_and_says_so():
    """No auto-resume after a reconnect or restart (ADR 0028 guard 4)."""
    device, _, events = intercom_device()
    await device.handle("intercom_ring", ring())
    device.begin_connection()
    assert device.state["intercom_state"] == "idle"
    assert events == [("intercom_ended", {"call_id": CALL, "reason": "disconnect"})]
    # A device that had no call says nothing on its next registration.
    device.begin_connection()
    assert len(events) == 1


async def test_signals_are_relayed_for_the_current_call_only():
    device, _, events = intercom_device()
    await device.handle("intercom_ring", ring())
    offer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    await device.handle("intercom_signal", {"call_id": CALL, "kind": "offer", "payload": offer})
    assert device.intercom_signals == [{"kind": "offer", "payload": offer}]

    with pytest.raises(CommandError) as err:
        await device.handle(
            "intercom_signal", {"call_id": "another-call-0001", "kind": "offer", "payload": offer}
        )
    assert err.value.code == "invalid_args"

    device.send_intercom_signal("answer", offer)
    assert events[-1] == (
        "intercom_signal",
        {"call_id": CALL, "kind": "answer", "payload": offer},
    )


async def test_an_oversized_signal_is_refused():
    device, _, _ = intercom_device()
    await device.handle("intercom_ring", ring())
    big = "x" * (INTERCOM_SIGNAL_MAX_BYTES + 1)
    with pytest.raises(CommandError) as err:
        await device.handle("intercom_signal", {"call_id": CALL, "kind": "offer", "payload": big})
    assert err.value.code == "invalid_args"


async def test_start_request_carries_one_target_and_a_call_id():
    device, _, events = intercom_device()
    device.start_intercom_call("call", target_device_id="9c1e4b7f2a3d4c5e8f9a0b1c2d3e4f50")
    event, data = events[-1]
    assert event == "intercom_start_request"
    assert data["mode"] == "call"
    assert data["target_device_id"] == "9c1e4b7f2a3d4c5e8f9a0b1c2d3e4f50"
    assert 8 <= len(data["call_id"]) <= 64

    device.start_intercom_call("broadcast")
    assert set(events[-1][1]) == {"call_id", "mode"}

    twice = {"mode": "broadcast", "target_area_id": "k", "target_device_id": "d"}
    for kwargs in ({"mode": "call"}, twice):
        with pytest.raises(CommandError) as err:
            device.start_intercom_call(**kwargs)
        assert err.value.code == "invalid_args"


async def test_intercom_commands_need_the_capability():
    device, _, _ = intercom_device(capabilities=["screen", "brightness"])
    for command, args in (
        ("intercom_ring", ring()),
        ("intercom_signal", {"call_id": CALL, "kind": "offer", "payload": "x"}),
        ("intercom_end", {"call_id": CALL, "reason": "command"}),
    ):
        with pytest.raises(CommandError) as err:
            await device.handle(command, args)
        assert err.value.code == "unsupported_command"


async def test_intercom_state_is_idle_until_a_call():
    device, _, deltas = intercom_device()
    deltas.clear()
    device.on_change = deltas.append
    await device.handle("intercom_ring", ring())
    assert deltas == [{"intercom_state": "ringing"}]


def test_backoff_equal_jitter_and_reset():
    backoff = Backoff(base=1, cap=8, reset_after=60, rng=random.Random(1))
    delays = [backoff.next_delay() for _ in range(6)]
    for delay, ceiling in zip(delays, [1, 2, 4, 8, 8, 8], strict=True):
        assert ceiling / 2 <= delay <= ceiling
    backoff.connection_ended(stable_for=59)
    assert backoff.attempt == 6
    backoff.connection_ended(stable_for=60)
    assert backoff.attempt == 0
