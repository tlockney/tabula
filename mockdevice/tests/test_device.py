from __future__ import annotations

import random

import pytest
from mockdevice.client import Backoff
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
    # and the default target is "/" when nothing is configured
    with_settings(device, {"start_path": "", "return_home_after": 60})
    device.state["current_page"] = "/config"
    clock["now"] += 60
    assert device.tick() is True
    assert device.state["current_page"] == "/"


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
    # a changed delay restarts the countdown (ADR 0019 item 5); an unchanged
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


def test_backoff_equal_jitter_and_reset():
    backoff = Backoff(base=1, cap=8, reset_after=60, rng=random.Random(1))
    delays = [backoff.next_delay() for _ in range(6)]
    for delay, ceiling in zip(delays, [1, 2, 4, 8, 8, 8], strict=True):
        assert ceiling / 2 <= delay <= ceiling
    backoff.connection_ended(stable_for=59)
    assert backoff.attempt == 6
    backoff.connection_ended(stable_for=60)
    assert backoff.attempt == 0
