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


def test_backoff_equal_jitter_and_reset():
    backoff = Backoff(base=1, cap=8, reset_after=60, rng=random.Random(1))
    delays = [backoff.next_delay() for _ in range(6)]
    for delay, ceiling in zip(delays, [1, 2, 4, 8, 8, 8], strict=True):
        assert ceiling / 2 <= delay <= ceiling
    backoff.connection_ended(stable_for=59)
    assert backoff.attempt == 6
    backoff.connection_ended(stable_for=60)
    assert backoff.attempt == 0
